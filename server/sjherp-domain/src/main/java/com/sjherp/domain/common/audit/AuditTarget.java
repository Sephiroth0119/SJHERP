package com.sjherp.domain.common.audit;

/**
 * 可审计目标（M2-T07）：聚合根实现本接口后，AuditAspect 能从
 * {@link Audited} 写方法的返回值中提取目标标识与关键字段摘要。
 *
 * <p>摘要约定：只含业务关键字段（编码/名称/状态/关键属性），
 * <b>绝不包含敏感内容</b>（密码哈希等）；更新类操作切面会在执行前后各取
 * 一次摘要，拼出「变更前 → 变更后」（完整字段级 diff 留 TODO，见 docs/审计日志.md）。
 */
public interface AuditTarget {

    /** 目标主键（落库前可能为 null） */
    Long auditTargetId();

    /** 目标业务编码（商品/客户编码、登录名、缺口编号等；无编码档案返回名称） */
    String auditTargetCode();

    /** 关键字段摘要（中文标签，禁止包含敏感信息） */
    String auditSummary();

    /** 摘要字段的 null 安全格式化：null / 空白 → "-" */
    static String text(Object value) {
        if (value == null) {
            return "-";
        }
        String s = String.valueOf(value);
        return s.isBlank() ? "-" : s;
    }
}
