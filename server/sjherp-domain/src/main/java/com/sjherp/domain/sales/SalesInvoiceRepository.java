package com.sjherp.domain.sales;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 销售发票仓储接口（端口，实现在 infra 层，M3-T10）。
 *
 * <p>单据头与行项目同表族（sales_invoice / sales_invoice_line），保存时整聚合落库
 * （新建插头+插行、更新落状态）。读取按单据号装配整聚合。
 */
public interface SalesInvoiceRepository {

    /** 保存发票聚合（新建时回填头与各行自增 id；已存在时更新状态与冲销关联） */
    void save(SalesInvoice invoice);

    /** 按单据号查（不存在返回空） */
    Optional<SalesInvoice> findByDocNo(String docNo);

    /**
     * 按单据号加写锁读取；状态写在既有外层事务中先锁发票头，保证同一发票只推进一次。
     * 内存替身默认退化为普通读取，生产 JDBC 实现必须使用 tenant-scoped {@code FOR UPDATE}。
     */
    default Optional<SalesInvoice> findByDocNoForUpdate(String docNo) {
        return findByDocNo(docNo);
    }

    /** 分页查询（按客户/出库单/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<SalesInvoice> search(SalesInvoiceQuery query);
}
