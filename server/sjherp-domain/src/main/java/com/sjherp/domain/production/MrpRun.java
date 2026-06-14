package com.sjherp.domain.production;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * MRP 运行结果聚合根（M5-T02）。
 *
 * <p>每次 MRP 运行产生新记录（regenerative 全重算，历史保留），
 * docNo 前缀 MRP-，suggestions 为本次运行的所有建议行。
 */
public class MrpRun {

    private Long id;
    private final String docNo;
    private final Instant runAt;
    private final long warehouseId;
    private final boolean includeForecast;
    private final boolean includeSalesOrder;
    private final String remark;
    private final String createdBy;
    private final List<MrpSuggestion> suggestions;

    /** 新建构造（id 由仓储回填）。 */
    public MrpRun(String docNo, Instant runAt, long warehouseId,
                  boolean includeForecast, boolean includeSalesOrder,
                  String remark, String createdBy, List<MrpSuggestion> suggestions) {
        this.docNo = Objects.requireNonNull(docNo, "docNo 不能为空");
        this.runAt = Objects.requireNonNull(runAt, "runAt 不能为空");
        this.warehouseId = warehouseId;
        this.includeForecast = includeForecast;
        this.includeSalesOrder = includeSalesOrder;
        this.remark = remark;
        this.createdBy = createdBy;
        this.suggestions = List.copyOf(Objects.requireNonNull(suggestions, "建议行不能为空"));
    }

    private MrpRun(Long id, String docNo, Instant runAt, long warehouseId,
                   boolean includeForecast, boolean includeSalesOrder,
                   String remark, String createdBy, List<MrpSuggestion> suggestions) {
        this.id = id;
        this.docNo = docNo;
        this.runAt = runAt;
        this.warehouseId = warehouseId;
        this.includeForecast = includeForecast;
        this.includeSalesOrder = includeSalesOrder;
        this.remark = remark;
        this.createdBy = createdBy;
        this.suggestions = List.copyOf(suggestions);
    }

    /** 持久化层重建（restore 工厂方法）。 */
    public static MrpRun restore(Long id, String docNo, Instant runAt, long warehouseId,
                                 boolean includeForecast, boolean includeSalesOrder,
                                 String remark, String createdBy, List<MrpSuggestion> suggestions) {
        return new MrpRun(id, docNo, runAt, warehouseId, includeForecast, includeSalesOrder,
                remark, createdBy, suggestions);
    }

    /** 仓储回填 id（一次性）。 */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 已分配，不可重复赋值: " + this.id);
        }
        this.id = id;
    }

    // -------- getter --------

    public Long getId() { return id; }
    public String getDocNo() { return docNo; }
    public Instant getRunAt() { return runAt; }
    public long getWarehouseId() { return warehouseId; }
    public boolean isIncludeForecast() { return includeForecast; }
    public boolean isIncludeSalesOrder() { return includeSalesOrder; }
    public String getRemark() { return remark; }
    public String getCreatedBy() { return createdBy; }
    public List<MrpSuggestion> getSuggestions() { return Collections.unmodifiableList(suggestions); }
}
