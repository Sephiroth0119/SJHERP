package com.sjherp.domain.production;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkOrder 聚合根工厂方法单元测试（M5-T03）。
 *
 * <p>纯 JUnit5，无 Spring，无 DB。验证 create() / createFromSuggestion() 的输入校验与初始状态。
 */
class WorkOrderTest {

    // ================================================================ create() 工厂

    @Test
    void create_正常参数_初态DRAFT_completedQty为ZERO() {
        WorkOrder wo = WorkOrder.create(
                "WO-202606-0001",
                100L,
                new BigDecimal("50"),
                1L,
                null, null, null,
                null, null,
                "备注",
                "alice");

        assertThat(wo.getDocNo()).isEqualTo("WO-202606-0001");
        assertThat(wo.getProductId()).isEqualTo(100L);
        assertThat(wo.getPlannedQty()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(wo.getUnitId()).isEqualTo(1L);
        assertThat(wo.getCompletedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wo.getSourceType()).isEqualTo(WorkOrderSourceType.MANUAL);
        assertThat(wo.getMrpRunDocNo()).isNull();
        assertThat(wo.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(wo.getCreatedBy()).isEqualTo("alice");
        assertThat(wo.getId()).isNull();  // 仓储回填前 id 为 null
    }

    @Test
    void create_计划数量为null_抛IllegalArgumentException() {
        assertThatThrownBy(() ->
                WorkOrder.create("WO-TEST", 1L, null, 1L,
                        null, null, null, null, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计划数量");
    }

    @Test
    void create_计划数量为零_抛IllegalArgumentException() {
        assertThatThrownBy(() ->
                WorkOrder.create("WO-TEST", 1L, BigDecimal.ZERO, 1L,
                        null, null, null, null, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计划数量");
    }

    @Test
    void create_计划数量为负数_抛IllegalArgumentException() {
        assertThatThrownBy(() ->
                WorkOrder.create("WO-TEST", 1L, new BigDecimal("-1"), 1L,
                        null, null, null, null, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计划数量");
    }

    // ================================================================ createFromSuggestion() 工厂

    @Test
    void createFromSuggestion_正常参数_来源MRP_SUGGESTION() {
        WorkOrder wo = WorkOrder.createFromSuggestion(
                "WO-202606-0002",
                200L,
                new BigDecimal("30"),
                2L,
                "MRP-202606-0001",
                "bob");

        assertThat(wo.getDocNo()).isEqualTo("WO-202606-0002");
        assertThat(wo.getProductId()).isEqualTo(200L);
        assertThat(wo.getPlannedQty()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(wo.getUnitId()).isEqualTo(2L);
        assertThat(wo.getCompletedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wo.getSourceType()).isEqualTo(WorkOrderSourceType.MRP_SUGGESTION);
        assertThat(wo.getMrpRunDocNo()).isEqualTo("MRP-202606-0001");
        assertThat(wo.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(wo.getCreatedBy()).isEqualTo("bob");
    }

    @Test
    void createFromSuggestion_净需求为零_抛IllegalArgumentException() {
        assertThatThrownBy(() ->
                WorkOrder.createFromSuggestion(
                        "WO-TEST", 1L, BigDecimal.ZERO, 1L, "MRP-000", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("净需求量");
    }

    @Test
    void createFromSuggestion_净需求为负_抛IllegalArgumentException() {
        assertThatThrownBy(() ->
                WorkOrder.createFromSuggestion(
                        "WO-TEST", 1L, new BigDecimal("-5"), 1L, "MRP-000", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("净需求量");
    }
}
