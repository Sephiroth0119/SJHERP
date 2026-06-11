package com.sjherp.domain.gap;

/**
 * 流程缺口分页查询条件。
 *
 * @param status 状态过滤；null 表示不过滤
 * @param module 业务模块过滤；null 表示不过滤
 * @param page   页码（从 1 开始）
 * @param size   每页条数（1–200）
 */
public record GapRecordQuery(GapStatus status, BusinessModule module, int page, int size) {

    /** 每页条数上限（防止一次拉全表，约定同 ProductQuery） */
    public static final int MAX_SIZE = 200;

    public GapRecordQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 必须在 1-" + MAX_SIZE + " 之间: " + size);
        }
    }
}
