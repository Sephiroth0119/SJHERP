package com.sjherp.domain.production;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 退料单分页查询条件（M5-T04）。
 *
 * @param materialIssueDocNo 按原领料单号过滤（null 表示不限）
 * @param status             按状态过滤（null 表示不限）
 * @param page               页码（从 1 开始）
 * @param size               每页条数
 */
public record MaterialReturnQuery(String materialIssueDocNo, DocumentStatus status, int page, int size) {
}
