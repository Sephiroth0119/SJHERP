package com.sjherp.app.audit;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.infra.persistence.audit.AuditLogEntry;

/**
 * 统一审计切面（M2-T07，CLAUDE.md 原则 3）：拦截 app 装配的领域 Service Bean
 * 上标注 {@link Audited} 的公有写方法，业务成功后写一行 audit_log。
 *
 * <p>审计边界 = 领域 Service 写方法：Agent 工具与 REST API 共用同一 Service Bean，
 * 单点拦截即双路径覆盖（人工操作 operator=登录名，Agent 操作 operator=agent:&lt;userId&gt;，
 * 由调用方按既有约定传入 operator 参数，切面原样取用）。
 *
 * <p>变更摘要：方法执行<b>前</b>对「按 id 写」的方法（update/enable/disable/...）
 * 反射调用同一 Service 的 {@code get(long)} 取变更前快照，与返回值的变更后摘要拼成
 * 「变更前 → 变更后」；create 类只记创建后快照。完整字段级 diff 留 TODO（见 docs/审计日志.md）。
 *
 * <p>失败兜底：审计取材/落库的任何异常只 WARN + 计数（{@link AuditMetrics}），
 * 绝不影响业务方法的返回（与 PersistingAgentInvocationListener 同哲学）；
 * 业务方法本身抛异常时不记审计（写操作未发生）。
 *
 * <p>落库经 {@link TransactionAwareAuditWriter}（D-8 幽灵审计修复）：存在外层业务
 * 事务时延迟到事务提交后插入（回滚则不写），无事务时立即插入。
 */
@Aspect
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    /** summary 列防御性截断长度（TEXT 列本身够大，截断只为防极端膨胀） */
    private static final int SUMMARY_MAX_LENGTH = 2000;

    private final TransactionAwareAuditWriter auditWriter;
    private final AuditMetrics metrics;

    public AuditAspect(TransactionAwareAuditWriter auditWriter, AuditMetrics metrics) {
        this.auditWriter = auditWriter;
        this.metrics = metrics;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        // 变更前快照（best-effort）：失败不影响业务
        String beforeSummary = captureBeforeQuietly(joinPoint);

        Object result = joinPoint.proceed();

        insertQuietly(joinPoint, audited, beforeSummary, result);
        return result;
    }

    // ---------------------------------------------------------------
    // 审计取材与落库（全部 quiet：任何异常不外抛）
    // ---------------------------------------------------------------

    private void insertQuietly(ProceedingJoinPoint joinPoint, Audited audited,
                               String beforeSummary, Object result) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String operator = extractOperator(signature.getMethod(), joinPoint.getArgs());

            Long targetId = null;
            String targetCode = null;
            String afterSummary = null;
            if (result instanceof AuditTarget target) {
                targetId = target.auditTargetId();
                targetCode = target.auditTargetCode();
                afterSummary = target.auditSummary();
            } else {
                // 返回值不可审计（void 等）：尽力从首个 long 入参取目标 id
                targetId = extractLeadingIdArg(signature.getMethod(), joinPoint.getArgs());
            }

            // 事务感知写入：有外层业务事务 → afterCommit 后插（回滚不写）；无事务 → 立即插
            auditWriter.write(new AuditLogEntry(null, operator, audited.action(),
                    audited.targetType(), targetId, targetCode,
                    buildSummary(beforeSummary, afterSummary),
                    AuditContext.sessionId(), Instant.now()));
        } catch (RuntimeException e) {
            // 此处兜底的是「取材/注册」阶段的异常；插入阶段的异常由 writer 内部兜底
            metrics.recordFailure();
            log.warn("审计日志取材失败（action={}, method={}），业务不受影响但审计缺失，需尽快排查"
                            + "（累计失败 {} 次）",
                    audited.action(), joinPoint.getSignature().toShortString(),
                    metrics.failureCount(), e);
        }
    }

    /**
     * 变更前快照：方法首参为 long/Long id 时，反射调用目标 Service 的 get(long)
     * 取当前聚合并立即固化摘要字符串（领域对象随后被业务方法原地修改，必须先取字符串）。
     */
    private String captureBeforeQuietly(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            // 基本类型 long 入参经 AOP 反射装箱为 Long
            if (args.length == 0 || !(args[0] instanceof Long)) {
                return null;
            }
            long id = (Long) args[0];
            Method getMethod = joinPoint.getTarget().getClass().getMethod("get", long.class);
            Object before = getMethod.invoke(joinPoint.getTarget(), id);
            return before instanceof AuditTarget target ? target.auditSummary() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // get 不存在 / 聚合不存在等：放弃 before 快照，不影响业务与 after 审计
            return null;
        }
    }

    /** 摘要拼装：有前后且不同 → 「变更前 → 变更后」；否则记可得的一侧（after 优先） */
    private static String buildSummary(String before, String after) {
        String summary;
        if (before != null && after != null && !before.equals(after)) {
            summary = "变更前[" + before + "] → 变更后[" + after + "]";
        } else {
            summary = after != null ? after : before;
        }
        if (summary != null && summary.length() > SUMMARY_MAX_LENGTH) {
            summary = summary.substring(0, SUMMARY_MAX_LENGTH) + "...(已截断)";
        }
        return summary;
    }

    /**
     * 操作人提取：优先取名为 operator 的 String 参数（全仓约定，编译开 -parameters，
     * Boot 父 POM 默认）；兜底取最后一个 String 参数；再兜底记 unknown（不应发生）。
     */
    private static String extractOperator(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType() == String.class && "operator".equals(parameters[i].getName())
                    && args[i] instanceof String s) {
                return s;
            }
        }
        for (int i = parameters.length - 1; i >= 0; i--) {
            if (parameters[i].getType() == String.class && args[i] instanceof String s) {
                return s;
            }
        }
        return "unknown";
    }

    /** 返回值不可审计时的目标 id 兜底：首参为 long/Long 视为目标主键 */
    private static Long extractLeadingIdArg(Method method, Object[] args) {
        if (args.length > 0 && (method.getParameterTypes()[0] == long.class
                || method.getParameterTypes()[0] == Long.class) && args[0] instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
