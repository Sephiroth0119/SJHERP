package com.sjherp.domain.common;

import java.util.List;
import java.util.Objects;

/**
 * 分页查询结果（领域层通用值对象，避免各仓储接口各造一套分页返回）。
 *
 * @param items 当前页数据（不可变副本）
 * @param total 满足条件的总条数
 * @param page  页码（从 1 开始）
 * @param size  每页条数
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public PageResult {
        Objects.requireNonNull(items, "items 不能为空");
        if (total < 0) {
            throw new IllegalArgumentException("total 不能为负数: " + total);
        }
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size 必须 >= 1: " + size);
        }
        items = List.copyOf(items);
    }
}
