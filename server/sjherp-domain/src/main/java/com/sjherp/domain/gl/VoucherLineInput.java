package com.sjherp.domain.gl;

import java.math.BigDecimal;

/**
 * 凭证行输入（M4-T01）：{@link VoucherService#create} 的逐行入参。
 *
 * <p>仅承载用户/调用方意图（科目 + 借/贷金额 + 摘要），行级不变式（恰好借或贷一方 &gt; 0、
 * 非负、2 位）在 {@link VoucherLine#create} 强制；行号由服务按输入顺序从 1 起编排。
 *
 * @param accountCode 挂账科目编码（须末级且启用，由 VoucherService 校验）
 * @param debit       借方金额（非负，最多 2 位小数；可为 null 视作 0）
 * @param credit      贷方金额（非负，最多 2 位小数；可为 null 视作 0）
 * @param summary     行摘要（可空）
 */
public record VoucherLineInput(String accountCode, BigDecimal debit, BigDecimal credit, String summary) {
}
