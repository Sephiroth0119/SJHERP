package com.sjherp.domain.gl;

/**
 * 账期已关闭领域异常（M4-T01，验收②）：在已关账账期过账时抛出 → REST 409。
 *
 * <p>继承 {@link IllegalStateException}：关账后禁止过账（CLAUDE.md 原则 2：期间不可随意重开）。
 * 关账守卫在 {@link VoucherService#post}（服务层可访问账期仓储）。
 */
public class PeriodClosedException extends IllegalStateException {

    public PeriodClosedException(String period) {
        super("账期[" + period + "] 已关闭，禁止过账");
    }
}
