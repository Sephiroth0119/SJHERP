package com.sjherp.domain.fund;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 资金账户分页查询条件（M4-T04a）。
 *
 * @param keyword 关键字（匹配编码/名称/开户行，模糊；空白视为不过滤）
 * @param status  状态过滤；null 表示不过滤
 * @param page    页码（从 1 开始）
 * @param size    每页条数（1–200）
 */
public record PaymentAccountQuery(String keyword, ArchiveStatus status, int page, int size) {

    /** 每页条数上限（防止一次拉全表） */
    public static final int MAX_SIZE = 200;

    public PaymentAccountQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 必须在 1-" + MAX_SIZE + " 之间: " + size);
        }
        keyword = (keyword == null || keyword.isBlank()) ? null : keyword.strip();
    }
}
