package com.sjherp.infra.persistence.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLine;
import com.sjherp.domain.production.MaterialIssueQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * {@link JdbcMaterialIssueRepository} 真库集成测试（M5-T04）。
 *
 * <p>使用 {@link MySqlContainerTestBase} 提供的共享 MySQL 8.4 容器（Flyway 全量迁移已完成）。
 * 每个测试方法使用 {@code uniqueSuffix()} 生成唯一 workOrderDocNo/productId/unitId，
 * 避免同一容器内不同测试触发唯一键冲突。
 *
 * <p>material_issue / material_issue_line 表无 FK 到 work_order / product / unit 表（逻辑外键），
 * 无需预建档案行。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>insert + findByDocNo → 头+行完整落库与回读（含可选字段 issuedCost=null 时落库）；</li>
 *   <li>过账后 issuedCost 回填 → update 后回读值正确；</li>
 *   <li>update（status 流转）→ status 持久化；</li>
 *   <li>search 按 workOrderDocNo 过滤 → 只返回目标领料单；</li>
 *   <li>search 按 status 过滤 → 只返回匹配状态；</li>
 *   <li>search 分页 → total/page/size/items 正确；</li>
 *   <li>findByDocNo 不存在 → empty。</li>
 * </ul>
 */
class JdbcMaterialIssueRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcMaterialIssueRepository repository =
            new JdbcMaterialIssueRepository(jdbc);

    // ================================================================ 1. insert + findByDocNo round-trip

    @Test
    void save新领料单后findByDocNo_头行字段完整落库() {
        long productId   = Long.parseLong(uniqueSuffix(), 36);
        long unitId      = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo   = "WO-IT-" + uniqueSuffix();
        String docNo     = "MI-IT-" + uniqueSuffix();

        MaterialIssueLine line = MaterialIssueLine.create(
                1, productId,
                new BigDecimal("10.000000"),   // 应领量
                new BigDecimal("10.000000"),   // 实领量
                unitId);

        MaterialIssue mi = MaterialIssue.create(docNo, woDocNo, 1L, "集成测试备注", List.of(line), "alice");

        repository.save(mi);

        // insert 后头 id 已被回填
        assertThat(mi.getId()).isNotNull().isPositive();

        Optional<MaterialIssue> found = repository.findByDocNo(docNo);
        assertThat(found).isPresent();
        MaterialIssue loaded = found.get();

        assertThat(loaded.getId()).isEqualTo(mi.getId());
        assertThat(loaded.getDocNo()).isEqualTo(docNo);
        assertThat(loaded.getWorkOrderDocNo()).isEqualTo(woDocNo);
        assertThat(loaded.getWarehouseId()).isEqualTo(1L);
        assertThat(loaded.getRemark()).isEqualTo("集成测试备注");
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(loaded.getReversalOfId()).isNull();
        assertThat(loaded.getReversedById()).isNull();
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");

        // 行校验
        assertThat(loaded.getLines()).hasSize(1);
        MaterialIssueLine loadedLine = loaded.getLines().get(0);
        assertThat(loadedLine.getLineNo()).isEqualTo(1);
        assertThat(loadedLine.getProductId()).isEqualTo(productId);
        assertThat(loadedLine.getRequiredQty()).isEqualByComparingTo("10.000000");
        assertThat(loadedLine.getQuantity()).isEqualByComparingTo("10.000000");
        assertThat(loadedLine.getUnitId()).isEqualTo(unitId);
        // 建单时 issuedCost 为 null（未过账）
        assertThat(loadedLine.getIssuedCost()).isNull();
    }

    @Test
    void save多行领料单_findByDocNo_多行按行号顺序返回() {
        long productId1  = Long.parseLong(uniqueSuffix(), 36);
        long productId2  = Long.parseLong(uniqueSuffix(), 36);
        long unitId      = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo   = "WO-IT-" + uniqueSuffix();
        String docNo     = "MI-IT-" + uniqueSuffix();

        MaterialIssueLine line1 = MaterialIssueLine.create(1, productId1,
                new BigDecimal("5.000000"), new BigDecimal("5.000000"), unitId);
        MaterialIssueLine line2 = MaterialIssueLine.create(2, productId2,
                new BigDecimal("3.000000"), new BigDecimal("3.000000"), unitId);

        MaterialIssue mi = MaterialIssue.create(docNo, woDocNo, 2L, null, List.of(line1, line2), "bob");
        repository.save(mi);

        MaterialIssue loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getLines()).hasSize(2);
        assertThat(loaded.getLines().get(0).getLineNo()).isEqualTo(1);
        assertThat(loaded.getLines().get(1).getLineNo()).isEqualTo(2);
        assertThat(loaded.getRemark()).isNull();  // 可空备注
    }

    // ================================================================ 2. 过账后 issuedCost 回填后 update 正确落库

    @Test
    void 过账后issuedCost回填_update后回读值正确() {
        long productId   = Long.parseLong(uniqueSuffix(), 36);
        long unitId      = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo   = "WO-IT-" + uniqueSuffix();
        String docNo     = "MI-IT-" + uniqueSuffix();

        MaterialIssueLine line = MaterialIssueLine.create(
                1, productId,
                new BigDecimal("8.000000"), new BigDecimal("8.000000"), unitId);

        MaterialIssue mi = MaterialIssue.create(docNo, woDocNo, 3L, null, List.of(line), "charlie");
        repository.save(mi);

        // 模拟过账：回填 issuedCost（移动加权出库成本绝对值）
        mi.getLines().get(0).assignIssuedCost(new BigDecimal("96.00"));
        // 状态推进到 COMPLETED（模拟过账完成）
        mi.approve("charlie");
        mi.startExecution("charlie");
        mi.complete("charlie");
        repository.save(mi);  // update

        MaterialIssue loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(loaded.getLines()).hasSize(1);
        // issuedCost 过账后落库为 2 位小数
        assertThat(loaded.getLines().get(0).getIssuedCost()).isEqualByComparingTo("96.00");
    }

    // ================================================================ 3. status 持久化

    @Test
    void approve流转后save_status持久化为APPROVED() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "MI-IT-" + uniqueSuffix();

        MaterialIssueLine line = MaterialIssueLine.create(1, productId,
                new BigDecimal("1.000000"), new BigDecimal("1.000000"), unitId);
        MaterialIssue mi = MaterialIssue.create(docNo, "WO-IT-" + uniqueSuffix(), 1L, null,
                List.of(line), "dave");
        repository.save(mi);

        mi.approve("dave");
        repository.save(mi);

        assertThat(repository.findByDocNo(docNo).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void cancel流转后save_status持久化为CANCELLED() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "MI-IT-" + uniqueSuffix();

        MaterialIssueLine line = MaterialIssueLine.create(1, productId,
                new BigDecimal("2.000000"), new BigDecimal("2.000000"), unitId);
        MaterialIssue mi = MaterialIssue.create(docNo, "WO-IT-" + uniqueSuffix(), 1L, null,
                List.of(line), "eve");
        repository.save(mi);

        mi.cancel("eve");
        repository.save(mi);

        assertThat(repository.findByDocNo(docNo).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.CANCELLED);
    }

    // ================================================================ 4. findByDocNo 不存在

    @Test
    void findByDocNo_不存在_返回empty() {
        Optional<MaterialIssue> result = repository.findByDocNo("MI-NOT-EXIST-99999");
        assertThat(result).isEmpty();
    }

    // ================================================================ 5. search 过滤与分页

    @Test
    void search_按workOrderDocNo过滤_只返回目标领料单() {
        long productId    = Long.parseLong(uniqueSuffix(), 36);
        long unitId       = Long.parseLong(uniqueSuffix(), 36);
        String targetWo   = "WO-TGT-" + uniqueSuffix();
        String otherWo    = "WO-OTH-" + uniqueSuffix();

        // 目标工单下 2 张领料单
        for (int i = 0; i < 2; i++) {
            MaterialIssueLine l = MaterialIssueLine.create(1, productId,
                    new BigDecimal("1.000000"), new BigDecimal("1.000000"), unitId);
            MaterialIssue mi = MaterialIssue.create("MI-IT-" + uniqueSuffix(), targetWo, 1L, null,
                    List.of(l), "tester");
            repository.save(mi);
        }
        // 干扰工单下 1 张
        MaterialIssueLine lOther = MaterialIssueLine.create(1, productId,
                new BigDecimal("1.000000"), new BigDecimal("1.000000"), unitId);
        repository.save(MaterialIssue.create("MI-IT-" + uniqueSuffix(), otherWo, 1L, null,
                List.of(lOther), "tester"));

        PageResult<MaterialIssue> result = repository.search(
                new MaterialIssueQuery(targetWo, null, 1, 20));

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(m -> m.getWorkOrderDocNo().equals(targetWo));
    }

    @Test
    void search_按status过滤_只返回匹配状态() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo = "WO-IT-" + uniqueSuffix();

        // 2 张 DRAFT + 1 张 APPROVED
        for (int i = 0; i < 2; i++) {
            MaterialIssueLine l = MaterialIssueLine.create(1, productId,
                    new BigDecimal("1.000000"), new BigDecimal("1.000000"), unitId);
            repository.save(MaterialIssue.create("MI-IT-" + uniqueSuffix(), woDocNo, 1L, null,
                    List.of(l), "tester"));
        }
        MaterialIssueLine l3 = MaterialIssueLine.create(1, productId,
                new BigDecimal("1.000000"), new BigDecimal("1.000000"), unitId);
        MaterialIssue miApproved = MaterialIssue.create("MI-IT-" + uniqueSuffix(), woDocNo, 1L, null,
                List.of(l3), "tester");
        repository.save(miApproved);
        miApproved.approve("tester");
        repository.save(miApproved);

        PageResult<MaterialIssue> draftResult = repository.search(
                new MaterialIssueQuery(woDocNo, DocumentStatus.DRAFT, 1, 20));
        assertThat(draftResult.total()).isEqualTo(2L);
        assertThat(draftResult.items()).allMatch(m -> m.getStatus() == DocumentStatus.DRAFT);

        PageResult<MaterialIssue> approvedResult = repository.search(
                new MaterialIssueQuery(woDocNo, DocumentStatus.APPROVED, 1, 20));
        assertThat(approvedResult.total()).isEqualTo(1L);
        assertThat(approvedResult.items()).allMatch(m -> m.getStatus() == DocumentStatus.APPROVED);
    }

    @Test
    void search_分页_total正确_item数量正确() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo = "WO-IT-" + uniqueSuffix();

        // 插入 5 张领料单
        for (int i = 0; i < 5; i++) {
            MaterialIssueLine l = MaterialIssueLine.create(1, productId,
                    new BigDecimal("1.000000"), new BigDecimal("1.000000"), unitId);
            repository.save(MaterialIssue.create("MI-IT-" + uniqueSuffix(), woDocNo, 1L, null,
                    List.of(l), "tester"));
        }

        // 第 1 页 size=2 → 2 条，total=5
        PageResult<MaterialIssue> page1 = repository.search(
                new MaterialIssueQuery(woDocNo, null, 1, 2));
        assertThat(page1.total()).isEqualTo(5L);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);

        // 第 3 页 size=2 → 1 条（最后一条）
        PageResult<MaterialIssue> page3 = repository.search(
                new MaterialIssueQuery(woDocNo, null, 3, 2));
        assertThat(page3.total()).isEqualTo(5L);
        assertThat(page3.items()).hasSize(1);
    }

    @Test
    void search_无匹配_返回空结果() {
        PageResult<MaterialIssue> result = repository.search(
                new MaterialIssueQuery("WO-NOT-EXIST-9999", null, 1, 20));
        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.items()).isEmpty();
    }
}
