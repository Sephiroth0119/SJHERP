package com.sjherp.domain.consistency;

/** 一致性检查报告分页查询参数。 */
public record ConsistencyRunQuery(int page, int size) {

    public ConsistencyRunQuery {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }
}
