package com.sjherp.infra.persistence.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 仓库仓储真实 MySQL 最小往返测试（X-2）：insert → findById → search 一条路径。
 */
class JdbcWarehouseRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcWarehouseRepository warehouseRepository = new JdbcWarehouseRepository(jdbc);

    @Test
    void 仓库_保存后读回并可按编码搜索() {
        String code = "WH" + uniqueSuffix();
        Warehouse warehouse = new Warehouse(code, "测试仓库", "仓库地址", "王五", true, "tester");

        warehouseRepository.save(warehouse);

        assertThat(warehouse.getId()).isNotNull();
        Optional<Warehouse> found = warehouseRepository.findById(warehouse.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(code);
        assertThat(found.get().getName()).isEqualTo("测试仓库");
        assertThat(found.get().getManager()).isEqualTo("王五");
        assertThat(found.get().isLocationEnabled()).isTrue();

        assertThat(warehouseRepository.existsByCode(code)).isTrue();
        PageResult<Warehouse> page = warehouseRepository.search(new WarehouseQuery(code, null, 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).getCode()).isEqualTo(code);
    }
}
