package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.loop.ToolCallRecord;
import com.sjherp.agent.reply.DocumentRef;

class DocumentRefExtractorTest {

    @Test
    void 成功工具结果按工具名映射单据类型并读取docNo() {
        List<DocumentRef> refs = DocumentRefExtractor.fromToolRecords(List.of(
                success("create_purchase_order", "{\"success\":true,\"data\":{\"docNo\":\"PO-202607-0001\"}}"),
                success("query_sales_order", "{\"success\":true,\"data\":{\"doc_no\":\"SO-202607-0002\"}}"),
                success("post_purchase_receipt", "{\"success\":true,\"data\":{\"docNo\":\"PR-202607-0003\"}}")));

        assertThat(refs).containsExactly(
                DocumentRef.of("PURCHASE_ORDER", "PO-202607-0001", "采购订单"),
                DocumentRef.of("SALES_ORDER", "SO-202607-0002", "销售订单"),
                DocumentRef.of("PURCHASE_RECEIPT", "PR-202607-0003", "采购入库单"));
    }

    @Test
    void 失败调用_未知工具_无单号均跳过() {
        List<DocumentRef> refs = DocumentRefExtractor.fromToolRecords(List.of(
                new ToolCallRecord("1", "create_purchase_order", "{}",
                        "{\"success\":false,\"error\":\"库存不足\"}", false, 1),
                success("create_customer", "{\"success\":true,\"data\":{\"id\":7}}"),
                success("create_purchase_order", "{\"success\":true,\"data\":{}}"),
                success("create_payment_account", "{\"success\":true,\"data\":{\"docNo\":\"FA-1\"}}")));

        assertThat(refs).isEmpty();
    }

    @Test
    void 采购发票优先于采购订单片段匹配() {
        List<DocumentRef> refs = DocumentRefExtractor.fromToolRecords(List.of(
                success("post_purchase_invoice", "{\"success\":true,\"data\":{\"docNo\":\"PINV-202607-0001\"}}")));
        assertThat(refs).containsExactly(
                DocumentRef.of("PURCHASE_INVOICE", "PINV-202607-0001", "采购发票"));
    }

    @Test
    void merge时模型声明优先_工具结果补缺且按type加单号去重() {
        List<DocumentRef> merged = DocumentRefExtractor.merge(
                List.of(DocumentRef.of("PURCHASE_ORDER", "PO-1", "模型标题")),
                List.of(
                        DocumentRef.of("PURCHASE_ORDER", "PO-1", "工具标题"),
                        DocumentRef.of("SALES_ORDER", "SO-1", "销售订单")));
        assertThat(merged).containsExactly(
                DocumentRef.of("PURCHASE_ORDER", "PO-1", "模型标题"),
                DocumentRef.of("SALES_ORDER", "SO-1", "销售订单"));
    }

    private static ToolCallRecord success(String toolName, String resultJson) {
        return new ToolCallRecord("c1", toolName, "{}", resultJson, true, 10);
    }
}
