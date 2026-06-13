package com.sjherp.domain.receivable;

/**
 * 应收账款分页查询条件（M3-T10）。
 *
 * @param customerId 客户 id 过滤（可空）
 * @param status     状态过滤（可空）
 * @param page       页码（从 1 起）
 * @param size       每页条数
 */
public record ReceivableQuery(Long customerId, ReceivableStatus status, int page, int size) {

    public ReceivableQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
