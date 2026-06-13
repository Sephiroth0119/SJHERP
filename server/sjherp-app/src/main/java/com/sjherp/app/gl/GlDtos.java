package com.sjherp.app.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.AccountingPeriod;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 总账（科目 / 账期 / 凭证）API 的请求/响应 DTO（M4-T01）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额在 JSON 响应中一律以<b>字符串</b>承载
 * （{@link BigDecimal#toPlainString()}），绝不用 JSON 数字；请求金额用 {@link BigDecimal} 接收
 * （Jackson 从字符串/数字均可反序列化为 BigDecimal，避免 double 中间态）。
 */
public final class GlDtos {

    private GlDtos() {
    }

    // =============================================================== 科目

    /** 建科目请求：编码 + 名称 + 类别 + 余额方向 + 上级编码（可空）+ 是否末级 */
    public record CreateAccountRequest(
            @NotNull(message = "科目编码不能为空") String code,
            @NotNull(message = "科目名称不能为空") String name,
            @NotNull(message = "科目类别不能为空（ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS）") String type,
            @NotNull(message = "余额方向不能为空（DEBIT/CREDIT）") String balanceDir,
            String parentCode,
            @NotNull(message = "是否末级不能为空") Boolean isLeaf) {
    }

    /** 科目响应（编码/名称/类别/方向/层级/末级/启停/预置） */
    public record AccountResponse(String code, String name, String type, String typeLabel,
                                  String balanceDir, String balanceDirLabel, String parentCode,
                                  int level, boolean leaf, boolean enabled, boolean preset) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getCode(), account.getName(),
                    account.getType().name(), account.getType().label(),
                    account.getBalanceDir().name(), account.getBalanceDir().label(),
                    account.getParentCode(), account.getLevel(), account.isLeaf(),
                    account.isEnabled(), account.isPreset());
        }
    }

    // =============================================================== 账期

    /** 开启账期请求：账期键 yyyyMM */
    public record OpenPeriodRequest(
            @NotNull(message = "账期不能为空（yyyyMM）") String period) {
    }

    /** 账期响应（账期键/年月/状态/关账标记） */
    public record PeriodResponse(String period, int year, int month, String status, String statusLabel,
                                 String closedBy, String closedAt) {

        public static PeriodResponse from(AccountingPeriod period) {
            return new PeriodResponse(period.getPeriod(), period.getYear(), period.getMonth(),
                    period.getStatus().name(), period.getStatus().label(),
                    period.getClosedBy(),
                    period.getClosedAt() == null ? null : period.getClosedAt().toString());
        }
    }

    // =============================================================== 凭证

    /** 建凭证请求：凭证日期（必填，决定账期）+ 摘要（可空）+ 行数组（≥2 行，借贷平衡） */
    public record CreateVoucherRequest(
            @NotNull(message = "凭证日期不能为空") LocalDate voucherDate,
            String summary,
            @NotEmpty(message = "凭证至少要有 2 行（一借一贷起）") @Valid List<VoucherLineRequest> lines) {
    }

    /** 建凭证行输入：挂账科目 + 借方/贷方金额（恰好一方 > 0，校验在领域层）+ 行摘要 */
    public record VoucherLineRequest(
            @NotNull(message = "凭证行科目编码不能为空") String accountCode,
            BigDecimal debit,
            BigDecimal credit,
            String summary) {
    }

    /** 凭证响应（单据头 + 行项目；金额字符串） */
    public record VoucherResponse(String docNo, String period, LocalDate voucherDate, String word,
                                  String totalAmount, String summary, String sourceDocNo,
                                  String sourceDocType, String status, List<VoucherLineResponse> lines) {

        public static VoucherResponse from(Voucher voucher) {
            List<VoucherLineResponse> lines = voucher.getLines().stream()
                    .map(VoucherLineResponse::from).toList();
            return new VoucherResponse(voucher.getDocNo(), voucher.getPeriod(), voucher.getVoucherDate(),
                    voucher.getWord(), plain(voucher.getTotalAmount()), voucher.getSummary(),
                    voucher.getSourceDocNo(), voucher.getSourceDocType(),
                    voucher.getStatus().name(), lines);
        }
    }

    /** 凭证行响应：行号 + 科目 + 借/贷金额 + 行摘要（金额字符串） */
    public record VoucherLineResponse(int lineNo, String accountCode, String debit, String credit,
                                      String summary) {

        static VoucherLineResponse from(VoucherLine line) {
            return new VoucherLineResponse(line.getLineNo(), line.getAccountCode(),
                    plain(line.getDebit()), plain(line.getCredit()), line.getSummary());
        }
    }

    // =============================================================== 试算平衡 / 科目余额

    /** 科目余额响应（科目编码 + 本期借/贷发生额 + 净额；金额字符串） */
    public record AccountBalanceResponse(String accountCode, String totalDebit, String totalCredit,
                                         String netBalance) {

        public static AccountBalanceResponse from(AccountBalance balance) {
            return new AccountBalanceResponse(balance.accountCode(), plain(balance.totalDebit()),
                    plain(balance.totalCredit()), plain(balance.netBalance()));
        }
    }

    /** 试算平衡响应：账期 + 各科目余额 + 全期借/贷合计（合计应相等，金额字符串） */
    public record TrialBalanceResponse(String period, List<AccountBalanceResponse> balances,
                                       String totalDebit, String totalCredit) {

        public static TrialBalanceResponse from(String period, List<AccountBalance> balances) {
            BigDecimal totalDebit = balances.stream().map(AccountBalance::totalDebit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal totalCredit = balances.stream().map(AccountBalance::totalCredit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return new TrialBalanceResponse(period,
                    balances.stream().map(AccountBalanceResponse::from).toList(),
                    plain(totalDebit), plain(totalCredit));
        }
    }

    // =============================================================== 分页

    /** 分页响应（与库存/采购/销售 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<VoucherResponse> ofVouchers(PageResult<Voucher> result) {
            return new PageResponse<>(
                    result.items().stream().map(VoucherResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
