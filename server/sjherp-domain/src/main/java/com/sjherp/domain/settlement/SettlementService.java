package com.sjherp.domain.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableNotFoundException;
import com.sjherp.domain.receivable.ReceivableRepository;

/**
 * 核销引擎（M4-T03，路线图 §6）：对某笔应收/应付施加一次核销——推进子账已核销额与状态，
 * 并落一条只追加的 {@link SettlementRecord}（可审计真源）。
 *
 * <p>纯 Java 零依赖：依赖应收/应付/核销记录三个仓储端口，由 app 层装配并加事务边界。
 *
 * <h2>范围裁定（设计真源 §0，本批最关键约束）</h2>
 * 复式记账下「减少应收 + 收到现金」是同一笔经济业务，脱离收付款的独立核销即记账错误。故本批：
 * <ul>
 *   <li>引擎<b>不生成 GL 凭证</b>、<b>不暴露独立 settle 的 REST/Agent 写入口</b>——
 *       {@link #settleReceivable}/{@link #settlePayable} 仅供 M4-T04 收付款单过账在<b>同事务内</b>触发
 *       （届时 T04 配套生成现金侧凭证）；</li>
 *   <li>本批生产路径无任何核销触发器，故「只动子账不动 GL」不会发生（无账实分歧）；引擎写方法的直接
 *       调用仅存在于测试。</li>
 * </ul>
 *
 * <p>核销冲销/红冲 → M4-T07（按 {@code paymentDocNo} 反查记录反向）。两个写方法 {@code @Audited}
 * （CLAUDE.md 原则 3）；超额核销由子账 {@code settle} 硬拒绝（OverSettlementException）。
 */
public class SettlementService {

    private final ReceivableRepository receivableRepository;
    private final AccountsPayableRepository payableRepository;
    private final SettlementRecordRepository settlementRepository;

    public SettlementService(ReceivableRepository receivableRepository,
                             AccountsPayableRepository payableRepository,
                             SettlementRecordRepository settlementRepository) {
        this.receivableRepository = Objects.requireNonNull(receivableRepository,
                "receivableRepository 不能为空");
        this.payableRepository = Objects.requireNonNull(payableRepository,
                "payableRepository 不能为空");
        this.settlementRepository = Objects.requireNonNull(settlementRepository,
                "settlementRepository 不能为空");
    }

    /**
     * 核销一笔应收（收款冲应收，M4-T04 同事务内触发）：装载应收 → {@code ar.settle(amount)}
     * （超额抛 OverSettlementException → 400）→ 保存子账（UPDATE settled_amount/status）→ 落核销记录。
     *
     * @param receivableId   应收主键（不存在抛 {@link ReceivableNotFoundException} → 404）
     * @param amount         本次核销金额（> 0）
     * @param settlementDate 核销业务日（非空）
     * @param paymentDocNo   收付款单号（T03 传 null；T04 收付款单回填）
     * @param operator       操作人（审计；非空）
     * @return 落库后的核销记录（含回填 id）
     */
    @Audited(action = "settlement.receivable", targetType = "settlement")
    public SettlementRecord settleReceivable(long receivableId, BigDecimal amount,
                                             LocalDate settlementDate, String paymentDocNo,
                                             String operator) {
        requireOperator(operator);
        Objects.requireNonNull(settlementDate, "核销业务日不能为空");
        AccountsReceivable ar = receivableRepository.findById(receivableId)
                .orElseThrow(() -> new ReceivableNotFoundException(receivableId));
        ar.settle(amount);
        receivableRepository.save(ar);
        SettlementRecord record = SettlementRecord.record(SettlementType.RECEIVABLE,
                ar.getId(), ar.getSourceDocNo(), amount, settlementDate, paymentDocNo, operator);
        settlementRepository.save(record);
        return record;
    }

    /**
     * 核销一笔应付（付款冲应付，M4-T04 同事务内触发）：与 {@link #settleReceivable} 对称。
     *
     * @param payableId      应付主键（不存在抛 {@link PayableNotFoundException} → 404）
     * @param amount         本次核销金额（> 0）
     * @param settlementDate 核销业务日（非空）
     * @param paymentDocNo   收付款单号（T03 传 null；T04 收付款单回填）
     * @param operator       操作人（审计；非空）
     * @return 落库后的核销记录（含回填 id）
     */
    @Audited(action = "settlement.payable", targetType = "settlement")
    public SettlementRecord settlePayable(long payableId, BigDecimal amount,
                                          LocalDate settlementDate, String paymentDocNo,
                                          String operator) {
        requireOperator(operator);
        Objects.requireNonNull(settlementDate, "核销业务日不能为空");
        AccountsPayable ap = payableRepository.findById(payableId)
                .orElseThrow(() -> new PayableNotFoundException(payableId));
        ap.settle(amount);
        payableRepository.save(ap);
        SettlementRecord record = SettlementRecord.record(SettlementType.PAYABLE,
                ap.getId(), ap.getSourceDocNo(), amount, settlementDate, paymentDocNo, operator);
        settlementRepository.save(record);
        return record;
    }

    /** 某笔应收的核销历史（只读，按发生先后） */
    public List<SettlementRecord> findReceivableSettlements(long receivableId) {
        return settlementRepository.findByTarget(SettlementType.RECEIVABLE, receivableId);
    }

    /** 某笔应付的核销历史（只读，按发生先后） */
    public List<SettlementRecord> findPayableSettlements(long payableId) {
        return settlementRepository.findByTarget(SettlementType.PAYABLE, payableId);
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
