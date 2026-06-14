package com.sjherp.infra.persistence.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.BillOfMaterials;
import com.sjherp.domain.production.BillOfMaterialsQuery;
import com.sjherp.domain.production.BomLine;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * {@link JdbcBillOfMaterialsRepository} 真库集成测试（M5-T01）。
 *
 * <p>使用 {@link MySqlContainerTestBase} 提供的共享 MySQL 8.4 容器（Flyway 全量迁移已完成）。
 * 每个测试方法使用 {@code uniqueSuffix()} 生成唯一的 productId，避免同一容器内不同测试方法
 * 触发 BOM 唯一约束（uk_bom_product_version / uk_bom_active）。
 *
 * <p>bom 表无 FK 到 product 表（V25 迁移注释说明 product_id 为逻辑外键），
 * 因此无需预先插入商品档案行。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>save（插入）+ findById → 头与行数据的完整落库与回读；</li>
 *   <li>findByProductAndVersion → 按产品+版本精准定位；</li>
 *   <li>findActiveByProductId → 同产品多版本时只返回 ENABLED 版本；</li>
 *   <li>active_flag 唯一约束 → 同产品第二条 ENABLED 触发 DataIntegrityViolationException；</li>
 *   <li>findChildProductIds → 只检索 ENABLED BOM 下的子件；</li>
 *   <li>search 分页 → productId 过滤 + 分页计数正确。</li>
 * </ul>
 */
class JdbcBillOfMaterialsRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcBillOfMaterialsRepository repository =
            new JdbcBillOfMaterialsRepository(jdbc);

    // ================================================================ 1. 头+行 round-trip

    @Test
    void save新BOM后findById_头与行均正确落库() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        BomLine line = new BomLine(3L, new BigDecimal("10.500000"),
                new BigDecimal("0.050000"), 5L);
        BillOfMaterials bom = new BillOfMaterials(productId, 1, "备注", List.of(line), "alice");

        repository.save(bom);

        // save 后 id 已被回填（不再为 null）
        assertThat(bom.getId()).isNotNull().isPositive();

        Optional<BillOfMaterials> found = repository.findById(bom.getId());

        assertThat(found).isPresent();
        BillOfMaterials loaded = found.get();
        assertThat(loaded.getProductId()).isEqualTo(productId);
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getStatus()).isEqualTo(ArchiveStatus.ENABLED);
        assertThat(loaded.getRemark()).isEqualTo("备注");
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");
        assertThat(loaded.getUpdatedBy()).isEqualTo("alice");
        // BOM 行校验
        assertThat(loaded.getLines()).hasSize(1);
        BomLine loadedLine = loaded.getLines().get(0);
        assertThat(loadedLine.childProductId()).isEqualTo(3L);
        assertThat(loadedLine.quantity()).isEqualByComparingTo(new BigDecimal("10.500000"));
        assertThat(loadedLine.scrapRate()).isEqualByComparingTo(new BigDecimal("0.050000"));
        assertThat(loadedLine.unitId()).isEqualTo(5L);
    }

    @Test
    void save更新后_行整体替换() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        BomLine line1 = new BomLine(2L, new BigDecimal("5"), BigDecimal.ZERO, 1L);
        BillOfMaterials bom = new BillOfMaterials(productId, 1, "初始", List.of(line1), "alice");
        repository.save(bom);

        // 更新：替换行为两行（BillOfMaterials.update 接受 remark + List<BomLine> + operator）
        BomLine lineA = new BomLine(10L, new BigDecimal("3"), BigDecimal.ZERO, 1L);
        BomLine lineB = new BomLine(11L, new BigDecimal("7"), new BigDecimal("0.010000"), 2L);
        bom.update("更新后", List.of(lineA, lineB), "bob");
        repository.save(bom);

        Optional<BillOfMaterials> found = repository.findById(bom.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getLines()).hasSize(2);
        assertThat(found.get().getRemark()).isEqualTo("更新后");
        assertThat(found.get().getUpdatedBy()).isEqualTo("bob");
    }

    // ================================================================ 2. findByProductAndVersion

    @Test
    void findByProductAndVersion_按产品和版本精准定位() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        // 同一产品建 v1（ENABLED）和 v2（DISABLED）
        BomLine line = new BomLine(2L, new BigDecimal("1"), BigDecimal.ZERO, 1L);
        // v1 直接保存（ENABLED）
        BillOfMaterials v1 = new BillOfMaterials(productId, 1, "版本1", List.of(line), "alice");
        repository.save(v1);
        // v2 需先 disable v1，再创建 v2
        v1.disable("alice");
        repository.save(v1);
        BillOfMaterials v2 = new BillOfMaterials(productId, 2, "版本2", List.of(line), "alice");
        repository.save(v2);

        Optional<BillOfMaterials> foundV1 = repository.findByProductAndVersion(productId, 1);
        Optional<BillOfMaterials> foundV2 = repository.findByProductAndVersion(productId, 2);
        Optional<BillOfMaterials> notFound = repository.findByProductAndVersion(productId, 99);

        assertThat(foundV1).isPresent();
        assertThat(foundV1.get().getVersion()).isEqualTo(1);
        assertThat(foundV1.get().getStatus()).isEqualTo(ArchiveStatus.DISABLED);

        assertThat(foundV2).isPresent();
        assertThat(foundV2.get().getVersion()).isEqualTo(2);
        assertThat(foundV2.get().getStatus()).isEqualTo(ArchiveStatus.ENABLED);

        assertThat(notFound).isEmpty();
    }

    // ================================================================ 3. findActiveByProductId

    @Test
    void findActiveByProductId_只返回ENABLED版本() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        BomLine line = new BomLine(2L, new BigDecimal("1"), BigDecimal.ZERO, 1L);

        // v1 创建后停用
        BillOfMaterials v1 = new BillOfMaterials(productId, 1, "v1", List.of(line), "tester");
        repository.save(v1);
        v1.disable("tester");
        repository.save(v1);

        // v2 创建（ENABLED）
        BillOfMaterials v2 = new BillOfMaterials(productId, 2, "v2", List.of(line), "tester");
        repository.save(v2);

        Optional<BillOfMaterials> active = repository.findActiveByProductId(productId);

        assertThat(active).isPresent();
        assertThat(active.get().getVersion()).isEqualTo(2);
        assertThat(active.get().getStatus()).isEqualTo(ArchiveStatus.ENABLED);
    }

    @Test
    void findActiveByProductId_无ENABLED版本时返回空() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        BomLine line = new BomLine(2L, new BigDecimal("1"), BigDecimal.ZERO, 1L);

        BillOfMaterials bom = new BillOfMaterials(productId, 1, "v1", List.of(line), "tester");
        repository.save(bom);
        bom.disable("tester");
        repository.save(bom);

        Optional<BillOfMaterials> active = repository.findActiveByProductId(productId);
        assertThat(active).isEmpty();
    }

    // ================================================================ 4. active_flag 唯一约束

    @Test
    void 同产品第二条ENABLED_触发唯一约束异常() {
        long productId = Long.parseLong(uniqueSuffix(), 36);

        // 第一条 ENABLED BOM 正常插入
        String nowStr = "2026-01-01 00:00:00.000000";
        jdbc.update(
                "INSERT INTO bom (product_id, version, status, remark, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 1, 'ENABLED', NULL, 'sys', ?, 'sys', ?)",
                productId, nowStr, nowStr);

        // 直接绕过领域服务用裸 SQL 插入第二条同 productId 的 ENABLED 记录，
        // 预期触发 uk_bom_active 唯一约束（active_flag 生成列不允许同产品两条 ENABLED）
        assertThatThrownBy(() ->
                jdbc.update(
                        "INSERT INTO bom (product_id, version, status, remark, "
                                + "created_by, created_at, updated_by, updated_at) "
                                + "VALUES (?, 2, 'ENABLED', NULL, 'sys', ?, 'sys', ?)",
                        productId, nowStr, nowStr))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ================================================================ 5. findChildProductIds

    @Test
    void findChildProductIds_只统计ENABLED_BOM的子件() {
        long parentProductId = Long.parseLong(uniqueSuffix(), 36);

        // ENABLED BOM：子件 20、21
        BomLine line20 = new BomLine(20L, new BigDecimal("1"), BigDecimal.ZERO, 1L);
        BomLine line21 = new BomLine(21L, new BigDecimal("2"), BigDecimal.ZERO, 1L);
        BillOfMaterials enabledBom = new BillOfMaterials(
                parentProductId, 1, "启用版本", List.of(line20, line21), "tester");
        repository.save(enabledBom);

        // DISABLED BOM（同 parent，另一版本）：子件 30（应被过滤）
        // 先把 v1 停用，再创建 v2（ENABLED），再停用 v2，再创建 v3（DISABLED 直接建不了，需先 enable v2 再 disable）
        // 简化策略：直接用裸 SQL 插入一条 DISABLED BOM 含子件 30
        String nowStr = "2026-01-01 00:00:00.000000";
        jdbc.update(
                "INSERT INTO bom (product_id, version, status, remark, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 99, 'DISABLED', NULL, 'sys', ?, 'sys', ?)",
                parentProductId, nowStr, nowStr);
        Long disabledBomId = jdbc.queryForObject(
                "SELECT id FROM bom WHERE product_id = ? AND version = 99",
                Long.class, parentProductId);
        assertThat(disabledBomId).isNotNull();
        jdbc.update(
                "INSERT INTO bom_line (bom_id, line_no, child_product_id, quantity, scrap_rate, unit_id) "
                        + "VALUES (?, 1, 30, 1.000000, 0.000000, 1)",
                disabledBomId);

        List<Long> childIds = repository.findChildProductIds(parentProductId);

        // 仅包含 ENABLED BOM 的子件 20、21；DISABLED BOM 的子件 30 应被排除
        assertThat(childIds).containsExactlyInAnyOrder(20L, 21L);
        assertThat(childIds).doesNotContain(30L);
    }

    // ================================================================ 6. search 分页

    @Test
    void search_按productId过滤_分页计数正确() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        BomLine line = new BomLine(2L, new BigDecimal("1"), BigDecimal.ZERO, 1L);

        // 插入 3 个版本（v1/v2 DISABLED，v3 ENABLED）
        BillOfMaterials v1 = new BillOfMaterials(productId, 1, "v1", List.of(line), "tester");
        repository.save(v1);
        v1.disable("tester");
        repository.save(v1);

        BillOfMaterials v2 = new BillOfMaterials(productId, 2, "v2", List.of(line), "tester");
        repository.save(v2);
        v2.disable("tester");
        repository.save(v2);

        BillOfMaterials v3 = new BillOfMaterials(productId, 3, "v3", List.of(line), "tester");
        repository.save(v3);

        // 按 productId 查全部（不过滤 status），共 3 条
        PageResult<BillOfMaterials> all = repository.search(
                new BillOfMaterialsQuery(productId, null, 1, 10));
        assertThat(all.total()).isEqualTo(3L);
        assertThat(all.items()).hasSize(3);
        assertThat(all.page()).isEqualTo(1);
        assertThat(all.size()).isEqualTo(10);

        // 按 productId + status=ENABLED，只有 v3
        PageResult<BillOfMaterials> enabledOnly = repository.search(
                new BillOfMaterialsQuery(productId, ArchiveStatus.ENABLED, 1, 10));
        assertThat(enabledOnly.total()).isEqualTo(1L);
        assertThat(enabledOnly.items()).hasSize(1);
        assertThat(enabledOnly.items().get(0).getVersion()).isEqualTo(3);

        // 分页边界：第 1 页 size=2，应返回 2 条，total 仍为 3
        PageResult<BillOfMaterials> page1 = repository.search(
                new BillOfMaterialsQuery(productId, null, 1, 2));
        assertThat(page1.total()).isEqualTo(3L);
        assertThat(page1.items()).hasSize(2);

        // 第 2 页 size=2，应返回 1 条（最后一条）
        PageResult<BillOfMaterials> page2 = repository.search(
                new BillOfMaterialsQuery(productId, null, 2, 2));
        assertThat(page2.total()).isEqualTo(3L);
        assertThat(page2.items()).hasSize(1);
    }

    @Test
    void search_无匹配_返回空列表() {
        // 使用一个极大但合法的 productId（不会在本次测试中被插入），搜索应返回空结果（total=0）
        // product_id BIGINT UNSIGNED 合法范围 0..2^63-1（JdbcTemplate 以 long 传入）
        long nonExistentProductId = 999_999_999_999L + Long.parseLong(uniqueSuffix(), 36) % 1_000_000L;
        PageResult<BillOfMaterials> result = repository.search(
                new BillOfMaterialsQuery(nonExistentProductId, null, 1, 10));
        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.items()).isEmpty();
    }
}
