package com.sjherp.domain.payment;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 付款单仓储接口（端口，实现在 infra 层，M4-T04b）。
 *
 * <p>单据头与行项目同表族（payment_disbursement / payment_disbursement_line），保存时整聚合落库
 * （新建插头+插行、更新落状态）。读取按单据号装配整聚合。
 */
public interface PaymentDisbursementRepository {

    /** 保存付款单聚合（新建时回填头与各行自增 id；已存在时更新状态与冲销关联） */
    void save(PaymentDisbursement disbursement);

    /** 按单据号查（不存在返回空） */
    Optional<PaymentDisbursement> findByDocNo(String docNo);

    /** 分页查询（按供应商/资金账户/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<PaymentDisbursement> search(PaymentDisbursementQuery query);
}
