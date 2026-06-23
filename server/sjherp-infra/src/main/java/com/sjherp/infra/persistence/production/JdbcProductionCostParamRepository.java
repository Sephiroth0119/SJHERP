package com.sjherp.infra.persistence.production;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.production.ProductionCostParam;
import com.sjherp.domain.production.ProductionCostParamRepository;

/**
 * 生产成本参数 MySQL 仓储实现（M5-T06，只读）。
 */
@Transactional(readOnly = true)
public class JdbcProductionCostParamRepository implements ProductionCostParamRepository {

    private static final long TENANT_ID = 0L;

    private final JdbcTemplate jdbc;

    public JdbcProductionCostParamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProductionCostParam> findByPeriod(String period) {
        List<ProductionCostParam> rows = jdbc.query(
                "SELECT period, default_labor_rate, overhead_rate FROM production_cost_param "
                        + "WHERE tenant_id=? AND period=?",
                (rs, rn) -> new ProductionCostParam(
                        rs.getString("period"),
                        rs.getBigDecimal("default_labor_rate"),
                        rs.getBigDecimal("overhead_rate")),
                TENANT_ID, period);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
