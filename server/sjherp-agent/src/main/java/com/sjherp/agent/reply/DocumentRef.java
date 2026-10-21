package com.sjherp.agent.reply;

import java.util.Objects;

/**
 * Agent 回复中的单据引用（选项返回协议 v0.1 的加性可选字段，M7-T04）。
 *
 * <p>前端据此渲染可点击卡片，跳转到对应传统 UI 工作台详情。卡片只携带
 * 稳定标识（type + docNo），不附带金额/数量等敏感明细；无权限时前端 fail-closed
 * 不渲染，不额外拉详情。
 *
 * @param type  单据类型稳定标识（如 PURCHASE_ORDER），见 docs/选项返回协议.md
 * @param docNo 业务单据号（如 PO-202607-0001）
 * @param title 可选展示标题（中文）；缺省时前端用类型中文名
 */
public record DocumentRef(String type, String docNo, String title) {

    public DocumentRef {
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(docNo, "docNo 不能为空");
        type = type.strip();
        docNo = docNo.strip();
        if (type.isEmpty()) {
            throw new IllegalArgumentException("单据引用 type 不能为空");
        }
        if (docNo.isEmpty()) {
            throw new IllegalArgumentException("单据引用 docNo 不能为空");
        }
        title = title == null || title.isBlank() ? null : title.strip();
    }

    public static DocumentRef of(String type, String docNo) {
        return new DocumentRef(type, docNo, null);
    }

    public static DocumentRef of(String type, String docNo, String title) {
        return new DocumentRef(type, docNo, title);
    }
}
