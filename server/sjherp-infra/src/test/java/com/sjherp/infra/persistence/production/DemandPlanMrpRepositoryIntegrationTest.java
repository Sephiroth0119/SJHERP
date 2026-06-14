package com.sjherp.infra.persistence.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.DemandPlan;
import com.sjherp.domain.production.DemandPlanLine;
import com.sjherp.domain.production.DemandPlanQuery;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpSuggestion;
import com.sjherp.domain.production.SuggestionType;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DemandPlan + MrpRun 仓储集成测试——真实 MySQL 8.4（Testcontainers）+ Flyway 迁移。
 *
 * <p>验证持久化 round-trip、分页查询，以及 SO 剩余需求 SQL 的正确性。
 * 每个测试使用唯一后缀（{@link #uniqueSuffix()}）确保数据互不干扰。
 */
class DemandPlanMrpRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcDemandPlanRepository demandPlanRepo = new JdbcDemandPlanRepository(jdbc);
    private final JdbcMrpRunRepository mrpRunRepo = new JdbcMrpRunRepository(jdbc);

    // ================================================================ 测试 1：需求计划 round-trip

    /**
     * 场景 1：need_plan 头+行 round-trip
     * 保存含 2 行的需求计划，再按 docNo 查回，验证所有字段一致。
     */
    @Test
    void 需求计划_保存并按docNo查回() {
        String suffix = uniqueSuffix();
        String docNo = "DP-IT-" + suffix;
        LocalDate planDate = LocalDate.of(2025, 3, 1);

        // 插入 2 行
        DemandPlanLine line1 = new DemandPlanLine(1001L, new BigDecimal("100.000000"), 1L, LocalDate.of(2025, 3, 10));
        DemandPlanLine line2 = new DemandPlanLine(1002L, new BigDecimal("50.500000"), 2L, null);
        DemandPlan plan = DemandPlan.restore(
                null, docNo, planDate, ArchiveStatus.ENABLED, "集成测试备注",
                List.of(line1, line2),
                "test-user", Instant.now(), "test-user", Instant.now());

        demandPlanRepo.save(plan);
        assertNotNull(plan.getId(), "保存后应回填 id");

        // 查回
        Optional<DemandPlan> found = demandPlanRepo.findByDocNo(docNo);
        assertThat(found).isPresent();
        DemandPlan loaded = found.get();

        // 头字段验证
        assertEquals(docNo, loaded.getDocNo());
        assertEquals(planDate, loaded.getPlanDate());
        assertEquals(ArchiveStatus.ENABLED, loaded.getStatus());
        assertEquals("集成测试备注", loaded.getRemark());
        assertEquals("test-user", loaded.getCreatedBy());

        // 行验证
        assertThat(loaded.getLines()).hasSize(2);

        DemandPlanLine loadedLine1 = loaded.getLines().stream()
                .filter(l -> l.productId() == 1001L).findFirst().orElseThrow();
        assertThat(loadedLine1.quantity()).isEqualByComparingTo("100");
        assertEquals(1L, loadedLine1.unitId());
        assertEquals(LocalDate.of(2025, 3, 10), loadedLine1.dueDate());

        DemandPlanLine loadedLine2 = loaded.getLines().stream()
                .filter(l -> l.productId() == 1002L).findFirst().orElseThrow();
        assertThat(loadedLine2.quantity()).isEqualByComparingTo("50.5");
        assertThat(loadedLine2.dueDate()).isNull();
    }

    // ================================================================ 测试 2：MRP 运行 round-trip

    /**
     * 场景 2：mrp_run 头+建议行 round-trip
     * 保存一次含 3 条建议的 MRP 运行，再按 docNo 查回验证。
     */
    @Test
    void MRP运行_保存并按docNo查回() {
        String suffix = uniqueSuffix();
        String docNo = "MRP-IT-" + suffix;
        Instant runAt = Instant.parse("2025-04-01T08:00:00Z");

        MrpSuggestion sA = new MrpSuggestion(
                SuggestionType.PRODUCTION, 2001L, 0,
                new BigDecimal("100.000000"), new BigDecimal("10.000000"),
                new BigDecimal("90.000000"), 1L);
        MrpSuggestion sB = new MrpSuggestion(
                SuggestionType.PRODUCTION, 2002L, 1,
                new BigDecimal("180.000000"), new BigDecimal("20.000000"),
                new BigDecimal("160.000000"), 1L);
        MrpSuggestion sC = new MrpSuggestion(
                SuggestionType.PURCHASE, 2003L, 2,
                new BigDecimal("480.000000"), new BigDecimal("50.000000"),
                new BigDecimal("430.000000"), 1L);

        MrpRun run = new MrpRun(docNo, runAt, 1L, true, false,
                "MRP 集成测试", "mrp-operator", List.of(sA, sB, sC));

        mrpRunRepo.save(run);
        assertNotNull(run.getId(), "保存后应回填 id");

        // 按 docNo 查回（含建议行）
        Optional<MrpRun> found = mrpRunRepo.findByDocNo(docNo);
        assertThat(found).isPresent();
        MrpRun loaded = found.get();

        // 头字段
        assertEquals(docNo, loaded.getDocNo());
        assertEquals(1L, loaded.getWarehouseId());
        assertTrue(loaded.isIncludeForecast());
        assertThat(loaded.isIncludeSalesOrder()).isFalse();
        assertEquals("MRP 集成测试", loaded.getRemark());
        assertEquals("mrp-operator", loaded.getCreatedBy());

        // 建议行
        assertThat(loaded.getSuggestions()).hasSize(3);

        MrpSuggestion loadedA = loaded.getSuggestions().stream()
                .filter(s -> s.productId() == 2001L).findFirst().orElseThrow();
        assertEquals(SuggestionType.PRODUCTION, loadedA.type());
        assertEquals(0, loadedA.level());
        assertThat(loadedA.grossRequirement()).isEqualByComparingTo("100");
        assertThat(loadedA.onHand()).isEqualByComparingTo("10");
        assertThat(loadedA.netRequirement()).isEqualByComparingTo("90");

        MrpSuggestion loadedC = loaded.getSuggestions().stream()
                .filter(s -> s.productId() == 2003L).findFirst().orElseThrow();
        assertEquals(SuggestionType.PURCHASE, loadedC.type());
        assertEquals(2, loadedC.level());
        assertThat(loadedC.netRequirement()).isEqualByComparingTo("430");
    }

    // ================================================================ 测试 3：分页查询

    /**
     * 场景 3：需求计划分页查询
     * 插入 3 条计划，分页查询 page=1 size=2，验证总数和当前页条数。
     */
    @Test
    void 需求计划_分页查询() {
        String suffix = uniqueSuffix();
        // 插入 3 条计划（status=ENABLED）
        for (int i = 1; i <= 3; i++) {
            String docNo = "DP-PAGE-" + suffix + "-" + i;
            DemandPlanLine line = new DemandPlanLine(3000L + i, new BigDecimal("10"), 1L, null);
            DemandPlan plan = DemandPlan.restore(
                    null, docNo, LocalDate.now(), ArchiveStatus.ENABLED, null,
                    List.of(line),
                    "page-test", Instant.now(), "page-test", Instant.now());
            demandPlanRepo.save(plan);
        }

        // 查询全部（不过滤状态），但由于 DB 可能有其他测试数据，仅验证≥3
        DemandPlanQuery query = new DemandPlanQuery(ArchiveStatus.ENABLED, 1, 2);
        PageResult<DemandPlan> result = demandPlanRepo.search(query);

        // 总数至少 3
        assertThat(result.total()).isGreaterThanOrEqualTo(3);
        // 第一页最多 2 条
        assertThat(result.items()).hasSizeLessThanOrEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
    }

    // ================================================================ 测试 4：SO 剩余需求 SQL

    /**
     * 场景 4：JdbcMrpDemandSource SQL 验证
     * 手动插入 sales_order + sales_order_line 模拟 APPROVED 销售订单，
     * 验证 openSalesOrderDemand 能正确汇总未交货余量，
     * 并且已全量发货行不出现在结果中。
     */
    @Test
    void SO剩余需求_SQL汇总正确() {
        String suffix = uniqueSuffix();
        long productId = Long.parseLong(suffix, 36) % 100_000_000L + 4_000_000_000L;
        // 确保正数且足够唯一（bigint范围内）
        if (productId <= 0) productId = Math.abs(productId) + 1;

        // 插入 sales_order（status=APPROVED）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String soDocNo = "SO-MRP-" + suffix;

        // sales_order schema（V16）: id, tenant_id, doc_no, customer_id, order_date,
        //   remark, status, reversal_of_id, reversed_by_id, created_by, created_at, updated_by, updated_at
        // 注意：无 warehouse_id / currency 字段
        jdbc.update(
                "INSERT INTO sales_order "
                        + "(tenant_id, doc_no, customer_id, order_date, status, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (0, ?, 1, ?, 'APPROVED', 'test', ?, 'test', ?)",
                soDocNo, LocalDate.now(), now, now);

        Long soId = jdbc.queryForObject("SELECT id FROM sales_order WHERE doc_no = ?", Long.class, soDocNo);
        assertNotNull(soId);

        // sales_order_line schema（V16）:
        //   id, tenant_id, sales_order_id, line_no, product_id, quantity,
        //   unit_price, amount(NOT NULL), delivered_qty(NOT NULL DEFAULT 0)
        // 插入 3 行：
        //   行1: quantity=100, delivered_qty=30  → 剩余 70
        //   行2: quantity=50,  delivered_qty=50  → 剩余 0（全量发货，不应出现）
        //   行3: quantity=80,  delivered_qty=0   → 剩余 80（未发货）
        // 总剩余 = 70 + 80 = 150（同一商品 id）
        for (int i = 1; i <= 3; i++) {
            BigDecimal qty = i == 1 ? new BigDecimal("100") :
                            i == 2 ? new BigDecimal("50") : new BigDecimal("80");
            BigDecimal delivered = i == 1 ? new BigDecimal("30") :
                                  i == 2 ? new BigDecimal("50") : BigDecimal.ZERO;
            // amount = quantity × unit_price（此处取整，仅需通过 NOT NULL 约束）
            BigDecimal amount = qty.multiply(new BigDecimal("10.00"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            jdbc.update(
                    "INSERT INTO sales_order_line "
                            + "(tenant_id, sales_order_id, line_no, product_id, quantity, "
                            + "unit_price, amount, delivered_qty) "
                            + "VALUES (0, ?, ?, ?, ?, 10.00, ?, ?)",
                    soId, i, productId, qty, amount, delivered);
        }

        // 调用 SO 需求源
        JdbcMrpDemandSource demandSource = new JdbcMrpDemandSource(jdbc);
        java.util.Map<Long, BigDecimal> demand = demandSource.openSalesOrderDemand();

        // 验证：该商品剩余需求 = 70 + 80 = 150
        assertThat(demand).containsKey(productId);
        assertThat(demand.get(productId)).isEqualByComparingTo("150");
    }

    // ================================================================ 测试 5：findAllEnabled

    /**
     * 场景 5：findAllEnabled 仅返回 ENABLED 状态的计划
     */
    @Test
    void 需求计划_findAllEnabled只返回启用状态() {
        String suffix = uniqueSuffix();

        // 插入 ENABLED 计划
        String enabledDocNo = "DP-EN-" + suffix;
        DemandPlanLine line = new DemandPlanLine(5001L, new BigDecimal("100"), 1L, null);
        DemandPlan enabled = DemandPlan.restore(
                null, enabledDocNo, LocalDate.now(), ArchiveStatus.ENABLED, null,
                List.of(line), "tester", Instant.now(), "tester", Instant.now());
        demandPlanRepo.save(enabled);

        // 插入 DISABLED 计划
        String disabledDocNo = "DP-DIS-" + suffix;
        DemandPlan disabled = DemandPlan.restore(
                null, disabledDocNo, LocalDate.now(), ArchiveStatus.DISABLED, null,
                List.of(new DemandPlanLine(5002L, new BigDecimal("50"), 1L, null)),
                "tester", Instant.now(), "tester", Instant.now());
        demandPlanRepo.save(disabled);

        List<DemandPlan> all = demandPlanRepo.findAllEnabled();

        // ENABLED 计划在结果中
        assertThat(all.stream().anyMatch(p -> p.getDocNo().equals(enabledDocNo))).isTrue();
        // DISABLED 计划不在结果中
        assertThat(all.stream().anyMatch(p -> p.getDocNo().equals(disabledDocNo))).isFalse();
    }

    // ================================================================ 测试 6：MRP 历史分页

    /**
     * 场景 6：MRP 历史分页列表（不含建议行）
     */
    @Test
    void MRP历史_分页列表不含建议行() {
        String suffix = uniqueSuffix();

        // 插入 2 条 MRP 运行
        for (int i = 1; i <= 2; i++) {
            String docNo = "MRP-HIST-" + suffix + "-" + i;
            MrpRun run = new MrpRun(docNo, Instant.now(), 1L, true, true,
                    null, "hist-test", List.of());
            mrpRunRepo.save(run);
        }

        // 分页查询
        PageResult<MrpRun> result = mrpRunRepo.searchHistory(1, 50);

        // 总记录数至少 2
        assertThat(result.total()).isGreaterThanOrEqualTo(2);

        // 历史列表中每条 MRP 的建议行应为空（searchHistory 不回带明细）
        for (MrpRun run : result.items()) {
            assertThat(run.getSuggestions()).isEmpty();
        }
    }
}
