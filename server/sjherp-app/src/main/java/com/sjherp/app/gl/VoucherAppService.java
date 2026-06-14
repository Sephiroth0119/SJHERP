package com.sjherp.app.gl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.gl.GlDtos.VoucherLineRequest;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherQuery;
import com.sjherp.domain.gl.VoucherService;

/**
 * 凭证应用服务（M4-T01，全系统最高风险的财务核心）：REST {@code GlVoucherController} 的公共入口。
 *
 * <p>职责：
 * <ul>
 *   <li>建单：凭证号自动生成（{@link #VOUCHER_RULE} 前缀 VCH，按凭证日期所属年月段计序），
 *       账期由凭证日期推算（{@code YearMonth.from(voucherDate)} → yyyyMM）→ 调领域
 *       {@link VoucherService#create}（借贷平衡在领域层构造时强制，不平 → 400）；</li>
 *   <li>过账：外层 {@code @Transactional} 包住「账期 OPEN 校验 + 凭证状态变更」原子事务——账期已关账
 *       领域层抛 PeriodClosedException（→ 409）并回滚（验收②）；</li>
 *   <li>查询 / 试算平衡 / 科目余额：委托领域服务（只读）。</li>
 * </ul>
 *
 * <p>扩展点（T02）：自动凭证（采购入库/销售出库等业务单据过账联动生成凭证）将复用
 * {@link VoucherService#create} 的来源单据号/类型回填能力，并在过账外层事务内追加凭证联动。
 */
@Service
public class VoucherAppService {

    /**
     * 凭证编号规则：VCH-202606-0001（前缀 VCH，按凭证日期所属年月段计序）。
     * 凭证字「记」存于 voucher.word 列供打印，不进编号（中文不兼容 [A-Z] 前缀，拆解 §8 决策 5）。
     */
    static final DocumentNumberRule VOUCHER_RULE = DocumentNumberRule.of("VCH");

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final VoucherService voucherService;
    private final DocumentNumberGenerator numberGenerator;

    public VoucherAppService(VoucherService voucherService, DocumentNumberGenerator numberGenerator) {
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建凭证（草稿）：自动 VCH- 编号，账期由凭证日期推算，借贷平衡在领域层强制。
     *
     * @param voucherDate 凭证日期（必填，决定账期与编号年月段）
     * @param summary     凭证摘要（可空）
     * @param lines       行输入（≥2 行；恰好借或贷一方 > 0、Σ借==Σ贷由领域层强制）
     * @param operator    操作人
     */
    @Transactional
    public Voucher create(LocalDate voucherDate, String summary, List<VoucherLineRequest> lines,
                          String operator) {
        Objects.requireNonNull(voucherDate, "凭证日期不能为空");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("凭证至少要有 2 行（一借一贷起）");
        }
        YearMonth yearMonth = YearMonth.from(voucherDate);
        String period = yearMonth.format(PERIOD_FORMAT);
        // 凭证号按凭证日期所属年月段计序（与账期年月段一致），不取建单系统当月
        String docNo = numberGenerator.generate(VOUCHER_RULE, yearMonth);
        List<VoucherLineInput> domainLines = lines.stream()
                .map(line -> new VoucherLineInput(line.accountCode(), line.debit(), line.credit(),
                        line.summary()))
                .toList();
        return voucherService.create(docNo, period, voucherDate, summary, domainLines, operator);
    }

    /**
     * 过账凭证（DRAFT → APPROVED）：外层事务包「账期 OPEN 校验 + 状态变更」。
     * 账期已关账 → 领域层抛 PeriodClosedException（409）并回滚（验收②）。
     */
    @Transactional
    public Voucher post(String docNo, String operator) {
        // 扩展点（T02）：此外层事务内将追加自动凭证联动（业务单据 ↔ 凭证）
        return voucherService.post(docNo, operator);
    }

    /**
     * 冲销已过账凭证（红字凭证，M4-T07a）：外层事务包「红字号生成 + 借贷对调红字凭证过账 + 原凭证
     * REVERSED + 双向 linkage」原子事务。红字号按 <b>原凭证日期所属年月段</b> 计序（与原凭证账期一致，
     * 复用 VCH- 编号规则），随后委托领域 {@link VoucherService#reverse}（校验/对调/幂等/账期 OPEN/linkage
     * 全在领域层）。
     *
     * <p>异常（沿用 {@link GlExceptionHandler} 契约）：原凭证不存在 → 404；非 APPROVED / 已冲销 / 既存红字 /
     * 账期已关账 → 409。
     *
     * @param docNo    被冲销的原凭证号
     * @param operator 操作人
     * @return 已过账的红字凭证
     */
    @Transactional
    public Voucher reverse(String docNo, String operator) {
        Objects.requireNonNull(docNo, "原凭证号不能为空");
        Voucher original = voucherService.get(docNo);
        // 红字凭证号按原凭证日期年月段计序（与原凭证账期一致，与建单分层一致——号在 app 层生成）
        String reversalDocNo = numberGenerator.generate(VOUCHER_RULE,
                YearMonth.from(original.getVoucherDate()));
        return voucherService.reverse(docNo, reversalDocNo, operator);
    }

    /** 按单据号查（不存在抛 VoucherNotFoundException → 404） */
    @Transactional(readOnly = true)
    public Voucher get(String docNo) {
        return voucherService.get(docNo);
    }

    /** 分页查询（按账期/状态过滤，可空；按创建倒序） */
    @Transactional(readOnly = true)
    public PageResult<Voucher> search(VoucherQuery query) {
        return voucherService.search(query);
    }

    /** 试算平衡：某账期已过账（APPROVED）凭证行按科目汇总借贷发生额（按科目编码升序） */
    @Transactional(readOnly = true)
    public List<AccountBalance> trialBalance(String period) {
        return voucherService.trialBalance(period);
    }

    /** 某科目某账期已过账借贷余额（无发生额返回零额） */
    @Transactional(readOnly = true)
    public AccountBalance accountBalance(String accountCode, String period) {
        return voucherService.accountBalance(accountCode, period);
    }
}
