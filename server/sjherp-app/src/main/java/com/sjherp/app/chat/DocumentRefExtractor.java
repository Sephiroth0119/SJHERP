package com.sjherp.app.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.loop.ToolCallRecord;
import com.sjherp.agent.reply.DocumentRef;

/**
 * 从成功的工具调用结果提取单据引用（M7-T04）。
 *
 * <p>不依赖模型自觉：工具 data 中的 docNo/doc_no 才是真源。未知工具、失败调用、
 * 无单号的结果一律跳过（fail-closed，不编造单据卡片）。
 */
public final class DocumentRefExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 较长的工具名片段优先，避免 purchase_order 被误匹配为更短键。
     * payment_account / query_payables 等无单据工作台的工具不在表中。
     */
    private static final List<ToolDocumentMapping> MAPPINGS = List.of(
            new ToolDocumentMapping("purchase_invoice", "PURCHASE_INVOICE", "采购发票"),
            new ToolDocumentMapping("purchase_receipt", "PURCHASE_RECEIPT", "采购入库单"),
            new ToolDocumentMapping("purchase_order", "PURCHASE_ORDER", "采购订单"),
            new ToolDocumentMapping("sales_invoice", "SALES_INVOICE", "销售发票"),
            new ToolDocumentMapping("sales_delivery", "SALES_DELIVERY", "销售出库单"),
            new ToolDocumentMapping("sales_order", "SALES_ORDER", "销售订单"),
            new ToolDocumentMapping("collection_receipt", "COLLECTION_RECEIPT", "收款单"),
            new ToolDocumentMapping("payment_disbursement", "PAYMENT_DISBURSEMENT", "付款单"),
            new ToolDocumentMapping("stock_count", "STOCK_COUNT", "盘点单"),
            new ToolDocumentMapping("material_issue", "MATERIAL_ISSUE", "领料单"),
            new ToolDocumentMapping("material_return", "MATERIAL_RETURN", "退料单"),
            new ToolDocumentMapping("production_report", "PRODUCTION_REPORT", "报工单"),
            new ToolDocumentMapping("cost_settlement", "COST_SETTLEMENT", "成本结转单"),
            new ToolDocumentMapping("work_order", "WORK_ORDER", "生产工单"),
            new ToolDocumentMapping("transfer", "TRANSFER", "调拨单"),
            new ToolDocumentMapping("voucher", "VOUCHER", "记账凭证")
    );

    private DocumentRefExtractor() {
    }

    public static List<DocumentRef> fromToolRecords(List<ToolCallRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, DocumentRef> unique = new LinkedHashMap<>();
        for (ToolCallRecord record : records) {
            if (record == null || !record.success()) {
                continue;
            }
            ToolDocumentMapping mapping = mappingOf(record.toolName());
            if (mapping == null) {
                continue;
            }
            String docNo = readDocNo(record.resultContent());
            if (docNo == null) {
                continue;
            }
            unique.putIfAbsent(mapping.type() + "\0" + docNo,
                    DocumentRef.of(mapping.type(), docNo, mapping.title()));
        }
        return List.copyOf(unique.values());
    }

    /** 模型声明的引用优先；工具回填补缺（同 type+docNo 不重复）。 */
    public static List<DocumentRef> merge(List<DocumentRef> fromReply, List<DocumentRef> fromTools) {
        LinkedHashMap<String, DocumentRef> unique = new LinkedHashMap<>();
        addAll(unique, fromReply);
        addAll(unique, fromTools);
        return unique.isEmpty() ? List.of() : List.copyOf(unique.values());
    }

    private static void addAll(Map<String, DocumentRef> unique, List<DocumentRef> refs) {
        if (refs == null) {
            return;
        }
        for (DocumentRef ref : refs) {
            if (ref == null) {
                continue;
            }
            unique.putIfAbsent(ref.type() + "\0" + ref.docNo(), ref);
        }
    }

    private static ToolDocumentMapping mappingOf(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        for (ToolDocumentMapping mapping : MAPPINGS) {
            if (toolName.contains(mapping.toolFragment())) {
                return mapping;
            }
        }
        return null;
    }

    private static String readDocNo(String resultContent) {
        if (resultContent == null || resultContent.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(resultContent);
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) {
                return null;
            }
            String docNo = text(data, "docNo");
            if (docNo == null) {
                docNo = text(data, "doc_no");
            }
            return docNo;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private record ToolDocumentMapping(String toolFragment, String type, String title) {
    }

    /** 测试可见：保证映射顺序稳定、覆盖关键工具名 */
    static List<String> knownTypes() {
        List<String> types = new ArrayList<>();
        for (ToolDocumentMapping mapping : MAPPINGS) {
            types.add(mapping.type());
        }
        return types;
    }
}
