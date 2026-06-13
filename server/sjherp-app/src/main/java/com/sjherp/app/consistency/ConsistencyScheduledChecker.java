package com.sjherp.app.consistency;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时一致性检查（M3-T13「检查 Agent」雏形）。
 *
 * <p>按配置 cron（{@code sjherp.consistency.cron}，默认每日凌晨 3 点）自动跑一遍
 * {@link ConsistencyCheckService#check()}，发现 break 按严重度记日志告警：
 * 有 ERROR 记 ERROR 日志、有 WARN（无 ERROR）记 WARN 日志、全平记 INFO。
 *
 * <p>开关 {@code sjherp.consistency.enabled}（{@link ConditionalOnProperty}，<b>默认关</b>）：
 * 本地/测试不跑（避免污染、并发抢库），生产显式打开。需类级 {@code @EnableScheduling} 在别处启用
 * （见 sharedEdits：放 SjherpApplication 或 ConsistencyConfig）。
 *
 * <p>M3 vs M6 边界：本期只记日志，不接 LLM 上报；LLM 化检查 Agent 与通知/纠错建议属 M6-T06，
 * 留 TODO 占位。本类只读账本、绝不改账。
 */
@Component
@ConditionalOnProperty(prefix = "sjherp.consistency", name = "enabled", havingValue = "true")
public class ConsistencyScheduledChecker {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyScheduledChecker.class);

    /** 单条 break 日志精简上限（避免一次告警刷屏；REST 仍返回全量）。 */
    private static final int MAX_LOGGED_BREAKS = 20;

    private final ConsistencyCheckService consistencyCheckService;

    public ConsistencyScheduledChecker(ConsistencyCheckService consistencyCheckService) {
        this.consistencyCheckService = Objects.requireNonNull(consistencyCheckService,
                "consistencyCheckService 不能为空");
    }

    /** 定时巡检：cron 配置化，默认每日凌晨 3 点。 */
    @Scheduled(cron = "${sjherp.consistency.cron:0 0 3 * * *}")
    public void runScheduledCheck() {
        ConsistencyReport report = consistencyCheckService.check();
        if (report.clean()) {
            log.info("数据一致性定时巡检：账已对平（0 break，时刻 {}）", report.checkedAt());
            return;
        }
        long errors = report.errorCount();
        long warns = report.warnCount();
        // 严重度分流：ERROR 优先（动了真账，硬阻断信号）
        if (errors > 0) {
            log.error("数据一致性定时巡检发现 {} 项 ERROR、{} 项 WARN（时刻 {}）——账本被破坏，需立即排查",
                    errors, warns, report.checkedAt());
        } else {
            log.warn("数据一致性定时巡检发现 {} 项 WARN（时刻 {}）——存在越界/风险态，需关注",
                    warns, report.checkedAt());
        }
        int logged = 0;
        for (ConsistencyBreak b : report.breaks()) {
            if (logged++ >= MAX_LOGGED_BREAKS) {
                log.warn("……还有 {} 条 break 未列出（详见 GET /api/consistency/check）",
                        report.breaks().size() - MAX_LOGGED_BREAKS);
                break;
            }
            log.warn("  [{}] {} key={} 期望={} 实际={}：{}", b.severity(), b.checkType().displayName(),
                    b.key(), b.expected(), b.actual(), b.message());
        }
        // TODO(M6-T06)：接入 LLM 检查 Agent，对 break 生成核对说明并推送通知/纠错建议（本期仅日志）。
    }
}
