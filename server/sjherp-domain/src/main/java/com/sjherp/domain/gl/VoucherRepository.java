package com.sjherp.domain.gl;

import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 凭证仓储接口（端口，实现在 infra 层，M4-T01）。
 *
 * <p>单据头与行项目同表族（voucher / voucher_line），保存时整聚合落库（新建插头+插行回填 id；
 * 更新只改头状态/冲销关联/审计，不触行表）。读取按单据号装配整聚合（行按 line_no 有序）。
 */
public interface VoucherRepository {

    /** 保存凭证聚合（新建时回填头与各行自增 id；已存在时更新状态与冲销关联） */
    void save(Voucher voucher);

    /** 按单据号查（不存在返回空） */
    Optional<Voucher> findByDocNo(String docNo);

    /** 分页查询（按账期/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<Voucher> search(VoucherQuery query);

    /** 按来源单据号查（T02 自动凭证幂等预留：同来源单据已生成凭证则不重复生成） */
    List<Voucher> findBySourceDocNo(String sourceDocNo);

    /**
     * 派生聚合：某账期<b>已过账（APPROVED）</b>凭证行按科目汇总借贷发生额（试算平衡/科目余额用）。
     * 实现命中 idx_voucher_line_account；结果按科目编码升序。
     */
    List<AccountBalance> aggregateBalances(String period);
}
