package com.sjherp.domain.purchase;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 采购发票仓储接口（端口，实现在 infra 层，M3-T07）。
 *
 * <p>单据头与行项目同表族（purchase_invoice / purchase_invoice_line），保存时整聚合落库
 * （新建插头+插行、更新落状态）。读取按单据号装配整聚合。
 */
public interface PurchaseInvoiceRepository {

    /** 保存采购发票聚合（新建时回填头与各行自增 id；已存在时更新状态与冲销关联） */
    void save(PurchaseInvoice invoice);

    /** 按单据号查（不存在返回空） */
    Optional<PurchaseInvoice> findByDocNo(String docNo);

    /** 分页查询（按供应商/采购入库单号/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<PurchaseInvoice> search(PurchaseInvoiceQuery query);
}
