package com.sjherp.domain.partner;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 供应商分页查询条件。
 *
 * @param keyword 关键字（匹配编码/名称/联系人/电话，模糊；空白视为不过滤）
 * @param status  状态过滤；null 表示不过滤
 * @param page    页码（从 1 开始）
 * @param size    每页条数（1–200）
 */
public record SupplierQuery(String keyword, ArchiveStatus status, int page, int size) {

    /** 每页条数上限（防止一次拉全表） */
    public static final int MAX_SIZE = 200;

    public SupplierQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 必须在 1-" + MAX_SIZE + " 之间: " + size);
        }
        keyword = (keyword == null || keyword.isBlank()) ? null : keyword.strip();
    }
}
