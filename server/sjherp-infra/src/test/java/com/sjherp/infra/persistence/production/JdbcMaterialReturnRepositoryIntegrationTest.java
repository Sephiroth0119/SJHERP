package com.sjherp.infra.persistence.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLine;
import com.sjherp.domain.production.MaterialReturnQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * {@link JdbcMaterialReturnRepository} 真库集成测试（M5-T04）。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>insert + findByDocNo → 头+行完整落库（含 srcIssueLineNo null 与非 null 两种情形）；</li>
 *   <li>returnedCost null 建单时 → update 回填后 → findByDocNo 回读值正确；</li>
 *   <li>status 流转（APPROVED / CANCELLED）持久化；</li>
 *   <li>search 按 materialIssueDocNo 过滤；</li>
 *   <li>search 按 status 过滤；</li>
 *   <li>search 分页；</li>
 *   <li>findByDocNo 不存在 → empty。</li>
 * </ul>
 */
class JdbcMaterialReturnRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcMaterialReturnRepository repository =
            new JdbcMaterialReturnRepository(jdbc);

    // ================================================================ 1. insert + findByDocNo round-trip

    @Test
    void save新退料单后findByDocNo_头行字段完整落库_srcIssueLineNo非null() {
        long productId  = Long.parseLong(uniqueSuffix(), 36);
        long unitId     = Long.parseLong(uniqueSuffix(), 36);
        String miDocNo  = "MI-IT-" + uniqueSuffix();
        String docNo    = "MR-IT-" + uniqueSuffix();

        // srcIssueLineNo=1（非 null，追溯到原领料单行 1）
        MaterialReturnLine line = MaterialReturnLine.create(1, productId,
                new BigDecimal("5.000000"), unitId, 1);

        MaterialReturn mr = MaterialReturn.create(docNo, miDocNo, 10L, "退料备注", List.of(line), "alice");
        repository.save(mr);

        // insert 后头 id 已被回填
        assertThat(mr.getId()).isNotNull().isPositive();

        Optional<MaterialReturn> found = repository.findByDocNo(docNo);
        assertThat(found).isPresent();
        MaterialReturn loaded = found.get();

        assertThat(loaded.getId()).isEqualTo(mr.getId());
        assertThat(loaded.getDocNo()).isEqualTo(docNo);
        assertThat(loaded.getMaterialIssueDocNo()).isEqualTo(miDocNo);
        assertThat(loaded.getWarehouseId()).isEqualTo(10L);
        assertThat(loaded.getRemark()).isEqualTo("退料备注");
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(loaded.getReversalOfId()).isNull();
        assertThat(loaded.getReversedById()).isNull();
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");

        // 行校验
        assertThat(loaded.getLines()).hasSize(1);
        MaterialReturnLine loadedLine = loaded.getLines().get(0);
        assertThat(loadedLine.getLineNo()).isEqualTo(1);
        assertThat(loadedLine.getProductId()).isEqualTo(productId);
        assertThat(loadedLine.getQuantity()).isEqualByComparingTo("5.000000");
        assertThat(loadedLine.getUnitId()).isEqualTo(unitId);
        assertThat(loadedLine.getSrcIssueLineNo()).isEqualTo(1);      // 非 null
        assertThat(loadedLine.getReturnedCost()).isNull();            // 未过账
    }

    @Test
    void save退料单_srcIssueLineNo为null_回读也为null() {
        long productId  = Long.parseLong(uniqueSuffix(), 36);
        long unitId     = Long.parseLong(uniqueSuffix(), 36);
        String miDocNo  = "MI-IT-" + uniqueSuffix();
        String docNo    = "MR-IT-" + uniqueSuffix();

        // srcIssueLineNo=null（不追溯原行号）
        MaterialReturnLine line = MaterialReturnLine.create(1, productId,
                new BigDecimal("2.000000"), unitId, null);

        MaterialReturn mr = MaterialReturn.create(docNo, miDocNo, 5L, null, List.of(line), "bob");
        repository.save(mr);

        MaterialReturn loaded = repository.findByDocNo(docNo).orElseThrow();
        MaterialReturnLine loadedLine = loaded.getLines().get(0);

        assertThat(loadedLine.getSrcIssueLineNo()).isNull();  // NULL 正确回读
        assertThat(loaded.getRemark()).isNull();              // 备注也可空
    }

    @Test
    void save多行退料单_多行按行号顺序返回() {
        long productId1  = Long.parseLong(uniqueSuffix(), 36);
        long productId2  = Long.parseLong(uniqueSuffix(), 36);
        long unitId      = Long.parseLong(uniqueSuffix(), 36);
        String miDocNo   = "MI-IT-" + uniqueSuffix();
        String docNo     = "MR-IT-" + uniqueSuffix();

        MaterialReturnLine line1 = MaterialReturnLine.create(1, productId1,
                new BigDecimal("3.000000"), unitId, 1);
        MaterialReturnLine line2 = MaterialReturnLine.create(2, productId2,
                new BigDecimal("7.000000"), unitId, null);

        MaterialReturn mr = MaterialReturn.create(docNo, miDocNo, 1L, null,
                List.of(line1, line2), "charlie");
        repository.save(mr);

        MaterialReturn loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getLines()).hasSize(2);
        assertThat(loaded.getLines().get(0).getLineNo()).isEqualTo(1);
        assertThat(loaded.getLines().get(0).getSrcIssueLineNo()).isEqualTo(1);
        assertThat(loaded.getLines().get(1).getLineNo()).isEqualTo(2);
        assertThat(loaded.getLines().get(1).getSrcIssueLineNo()).isNull();
    }

    // ================================================================ 2. returnedCost 回填后 update 正确落库

    @Test
    void 过账后returnedCost回填_update后回读值正确() {
        long productId  = Long.parseLong(uniqueSuffix(), 36);
        long unitId     = Long.parseLong(uniqueSuffix(), 36);
        String miDocNo  = "MI-IT-" + uniqueSuffix();
        String docNo    = "MR-IT-" + uniqueSuffix();

        MaterialReturnLine line = MaterialReturnLine.create(1, productId,
                new BigDecimal("4.000000"), unitId, 2);

        MaterialReturn mr = MaterialReturn.create(docNo, miDocNo, 1L, null, List.of(line), "dave");
        repository.save(mr);

        // 模拟过账：状态推进到 COMPLETED 并回填成本
        mr.getLines().get(0).assignReturnedCost(new BigDecimal("48.00"));
        mr.approve("dave");
        mr.startExecution("dave");
        mr.complete("dave");
        repository.save(mr);

        MaterialReturn loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(loaded.getLines()).hasSize(1);
        assertThat(loaded.getLines().get(0).getReturnedCost()).isEqualByComparingTo("48.00");
    }

    // ================================================================ 3. status 持久化

    @Test
    void approve流转后save_status持久化为APPROVED() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "MR-IT-" + uniqueSuffix();

        MaterialReturnLine line = MaterialReturnLine.create(1, productId,
                new BigDecimal("1.000000"), unitId, null);
        MaterialReturn mr = MaterialReturn.create(docNo, "MI-IT-" + uniqueSuffix(), 1L, null,
                List.of(line), "eve");
        repository.save(mr);

        mr.approve("eve");
        repository.save(mr);

        assertThat(repository.findByDocNo(docNo).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void cancel流转后save_status持久化为CANCELLED() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "MR-IT-" + uniqueSuffix();

        MaterialReturnLine line = MaterialReturnLine.create(1, productId,
                new BigDecimal("1.000000"), unitId, null);
        MaterialReturn mr = MaterialReturn.create(docNo, "MI-IT-" + uniqueSuffix(), 1L, null,
                List.of(line), "frank");
        repository.save(mr);

        mr.cancel("frank");
        repository.save(mr);

        assertThat(repository.findByDocNo(docNo).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.CANCELLED);
    }

    // ================================================================ 4. findByDocNo 不存在

    @Test
    void findByDocNo_不存在_返回empty() {
        Optional<MaterialReturn> result = repository.findByDocNo("MR-NOT-EXIST-99999");
        assertThat(result).isEmpty();
    }

    // ================================================================ 5. search 过滤与分页

    @Test
    void search_按materialIssueDocNo过滤_只返回目标退料单() {
        long productId    = Long.parseLong(uniqueSuffix(), 36);
        long unitId       = Long.parseLong(uniqueSuffix(), 36);
        String targetMi   = "MI-TGT-" + uniqueSuffix();
        String otherMi    = "MI-OTH-" + uniqueSuffix();

        // 目标领料单下 2 张退料单
        for (int i = 0; i < 2; i++) {
            MaterialReturnLine l = MaterialReturnLine.create(1, productId,
                    new BigDecimal("1.000000"), unitId, null);
            repository.save(MaterialReturn.create("MR-IT-" + uniqueSuffix(), targetMi, 1L, null,
                    List.of(l), "tester"));
        }
        // 干扰：其他领料单下 1 张
        MaterialReturnLine lOther = MaterialReturnLine.create(1, productId,
                new BigDecimal("1.000000"), unitId, null);
        repository.save(MaterialReturn.create("MR-IT-" + uniqueSuffix(), otherMi, 1L, null,
                List.of(lOther), "tester"));

        PageResult<MaterialReturn> result = repository.search(
                new MaterialReturnQuery(targetMi, null, 1, 20));

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(m -> m.getMaterialIssueDocNo().equals(targetMi));
    }

    @Test
    void search_按status过滤_只返回匹配状态() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String miDocNo = "MI-IT-" + uniqueSuffix();

        // 2 张 DRAFT + 1 张 APPROVED
        for (int i = 0; i < 2; i++) {
            MaterialReturnLine l = MaterialReturnLine.create(1, productId,
                    new BigDecimal("1.000000"), unitId, null);
            repository.save(MaterialReturn.create("MR-IT-" + uniqueSuffix(), miDocNo, 1L, null,
                    List.of(l), "tester"));
        }
        MaterialReturnLine l3 = MaterialReturnLine.create(1, productId,
                new BigDecimal("1.000000"), unitId, null);
        MaterialReturn mrApproved = MaterialReturn.create("MR-IT-" + uniqueSuffix(), miDocNo, 1L, null,
                List.of(l3), "tester");
        repository.save(mrApproved);
        mrApproved.approve("tester");
        repository.save(mrApproved);

        PageResult<MaterialReturn> draftResult = repository.search(
                new MaterialReturnQuery(miDocNo, DocumentStatus.DRAFT, 1, 20));
        assertThat(draftResult.total()).isEqualTo(2L);
        assertThat(draftResult.items()).allMatch(m -> m.getStatus() == DocumentStatus.DRAFT);

        PageResult<MaterialReturn> approvedResult = repository.search(
                new MaterialReturnQuery(miDocNo, DocumentStatus.APPROVED, 1, 20));
        assertThat(approvedResult.total()).isEqualTo(1L);
        assertThat(approvedResult.items()).allMatch(m -> m.getStatus() == DocumentStatus.APPROVED);
    }

    @Test
    void search_分页_total正确_item数量正确() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String miDocNo = "MI-IT-" + uniqueSuffix();

        // 插入 5 张退料单
        for (int i = 0; i < 5; i++) {
            MaterialReturnLine l = MaterialReturnLine.create(1, productId,
                    new BigDecimal("1.000000"), unitId, null);
            repository.save(MaterialReturn.create("MR-IT-" + uniqueSuffix(), miDocNo, 1L, null,
                    List.of(l), "tester"));
        }

        // 第 1 页 size=2 → 2 条，total=5
        PageResult<MaterialReturn> page1 = repository.search(
                new MaterialReturnQuery(miDocNo, null, 1, 2));
        assertThat(page1.total()).isEqualTo(5L);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);

        // 第 3 页 size=2 → 1 条（最后一条）
        PageResult<MaterialReturn> page3 = repository.search(
                new MaterialReturnQuery(miDocNo, null, 3, 2));
        assertThat(page3.total()).isEqualTo(5L);
        assertThat(page3.items()).hasSize(1);
    }

    @Test
    void search_无匹配_返回空结果() {
        PageResult<MaterialReturn> result = repository.search(
                new MaterialReturnQuery("MI-NOT-EXIST-9999", null, 1, 20));
        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.items()).isEmpty();
    }
}
