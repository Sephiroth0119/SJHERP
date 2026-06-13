package com.sjherp.domain.payable;

/**
 * 应付账款分页查询条件（M3-T07）。
 *
 * @param supplierId 供应商 id 过滤（可空）
 * @param status     应付状态过滤（可空；本期数据恒 OPEN）
 * @param page       页码（从 1 起）
 * @param size       每页条数
 */
public record AccountsPayableQuery(Long supplierId, PayableStatus status, int page, int size) {

    public AccountsPayableQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
