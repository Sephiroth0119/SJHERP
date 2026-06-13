package com.sjherp.app.consistency;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 数据一致性校验结构化报告（M3-T13 检查 Agent）。供 REST / Agent 工具 / 定时检查三处复用。
 *
 * <p>{@code breaks} 只收对不上的差异（全平时为空 = 报告「干净」）。便捷方法 {@link #clean()} /
 * {@link #errorCount()} / {@link #warnCount()} 供调用方快速判定与展示。
 *
 * @param checkedAt 本次校验时刻（UTC）
 * @param breaks    全部差异（按规则顺序收集；可能含每仓每商品多行）
 */
public record ConsistencyReport(Instant checkedAt, List<ConsistencyBreak> breaks) {

    public ConsistencyReport {
        Objects.requireNonNull(checkedAt, "checkedAt 不能为空");
        breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }

    /** 是否干净（无任何 break）。 */
    public boolean clean() {
        return breaks.isEmpty();
    }

    /** ERROR 级 break 条数（里程碑出口硬阻断项计数）。 */
    public long errorCount() {
        return countBySeverity(ConsistencySeverity.ERROR);
    }

    /** WARN 级 break 条数。 */
    public long warnCount() {
        return countBySeverity(ConsistencySeverity.WARN);
    }

    /** INFO 级 break 条数。 */
    public long infoCount() {
        return countBySeverity(ConsistencySeverity.INFO);
    }

    private long countBySeverity(ConsistencySeverity severity) {
        return breaks.stream().filter(b -> b.severity() == severity).count();
    }
}
