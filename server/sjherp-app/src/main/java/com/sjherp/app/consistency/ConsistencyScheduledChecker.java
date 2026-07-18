package com.sjherp.app.consistency;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sjherp.domain.consistency.ConsistencyCheckRun;

/**
 * 定时一致性检查（M3-T13「检查 Agent」雏形）。
 *
 * <p>按配置 cron（{@code sjherp.consistency.cron}，默认每日凌晨 3 点）自动跑一遍
 * {@link ConsistencyCheckRunner#runScheduled()}，发现 break 按严重度记日志告警：
 * 有 ERROR 记 ERROR 日志、有 WARN（无 ERROR）记 WARN 日志、全平记 INFO。
 *
 * <p>开关 {@code sjherp.consistency.enabled}（{@link ConditionalOnProperty}，<b>默认关</b>）：
 * 本地/测试不跑（避免污染、并发抢库），生产显式打开。需类级 {@code @EnableScheduling} 在别处启用
 * （见 sharedEdits：放 SjherpApplication 或 ConsistencyConfig）。
 *
 * <p>日志只记录运行编号和计数，不记录差异正文或异常消息；报告与通知由统一运行器负责。
 */
@Component
@ConditionalOnProperty(prefix = "sjherp.consistency", name = "enabled", havingValue = "true")
public class ConsistencyScheduledChecker {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyScheduledChecker.class);

    private final ConsistencyCheckRunner consistencyCheckRunner;

    public ConsistencyScheduledChecker(ConsistencyCheckRunner consistencyCheckRunner) {
        this.consistencyCheckRunner = Objects.requireNonNull(consistencyCheckRunner,
                "consistencyCheckRunner 不能为空");
    }

    /** 定时巡检：cron 配置化，默认每日凌晨 3 点。 */
    @Scheduled(cron = "${sjherp.consistency.cron:0 0 3 * * *}")
    public void runScheduledCheck() {
        ConsistencyCheckRun run = consistencyCheckRunner.runScheduled();
        if (run.clean()) {
            log.info("数据一致性定时巡检完成（runNo={}，总数=0）", run.runNo());
            return;
        }
        if (run.errorCount() > 0) {
            log.error("数据一致性定时巡检发现差异（runNo={}，总数={}，ERROR={}，WARN={}，INFO={}）",
                    run.runNo(), run.totalCount(), run.errorCount(), run.warnCount(), run.infoCount());
        } else {
            log.warn("数据一致性定时巡检发现差异（runNo={}，总数={}，ERROR={}，WARN={}，INFO={}）",
                    run.runNo(), run.totalCount(), run.errorCount(), run.warnCount(), run.infoCount());
        }
    }
}
