package com.sjherp.domain.gl;

/**
 * 会计期间状态（M4-T01）：开启 / 关闭。
 *
 * <p>账期不走单据状态机（{@link com.sjherp.domain.common.DocumentStatus}），
 * 只有 OPEN / CLOSED 两态：OPEN 期允许过账，CLOSED 期禁止过账
 * （CLAUDE.md 原则 2：期间不可随意重开；关账后禁止过账）。
 */
public enum PeriodStatus {

    /** 开启：允许在本期过账 */
    OPEN("开启"),

    /** 关闭：禁止在本期过账（重开为高敏操作，权限 finance:period_reopen） */
    CLOSED("关闭");

    private final String label;

    PeriodStatus(String label) {
        this.label = label;
    }

    /** 中文标签（审计摘要 / 用户可见文案统一出口） */
    public String label() {
        return label;
    }
}
