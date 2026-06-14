package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售发票过账生成应收所需的能力端口（M3-T10）。
 *
 * <p>领域 {@link SalesInvoiceService} 通过本端口生成应收账款，而不直接依赖应收聚合的服务类
 * （保持销售与应收两聚合的解耦，由 app 层装配适配到 {@code ReceivableService.open}）。
 * 同来源单据号幂等由实现方（应收服务）保证。
 *
 * <p>事务边界由 app 装配的发票服务外层事务保证：发票状态变更与应收挂账原子提交。
 */
public interface ReceivablePostingPort {

    /**
     * 开票挂应收（OPEN，未核销）。
     *
     * @param customerId  客户 id
     * @param amount      应收金额（= 发票金额）
     * @param sourceDocNo 来源销售发票号
     * @param dueDate     到期日（可空）
     * @param operator    操作人
     */
    void open(long customerId, BigDecimal amount, String sourceDocNo, LocalDate dueDate, String operator);

    /**
     * 冲销某来源发票的应收（M4-T07b 销售发票红冲）：按发票号装载应收并整笔冲回（OPEN → REVERSED）。
     * 仅未发生任何核销且仍 OPEN 的应收可冲（已核销须先冲对应收款单 T07c），否则实现方抛
     * {@link IllegalStateException} 引导。同外层事务原子。
     *
     * @param sourceDocNo 来源销售发票号
     * @param operator    操作人
     */
    void reverse(String sourceDocNo, String operator);
}
