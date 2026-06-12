package com.sjherp.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.inventory.InventoryBalanceRepository;
import com.sjherp.domain.inventory.InventoryPolicy;
import com.sjherp.domain.inventory.InventoryService;
import com.sjherp.domain.inventory.InventoryTransactionRepository;
import com.sjherp.domain.inventory.MovingWeightedAverageCalculator;
import com.sjherp.infra.persistence.inventory.JdbcInventoryBalanceRepository;
import com.sjherp.infra.persistence.inventory.JdbcInventoryTransactionRepository;

/**
 * 库存装配（M3-T01b）：仓储 MySQL 实现 + 成本策略 + 策略配置 + 领域服务 + 事务包装。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link PartnerInfraConfig}）。装配前置：D-8 已还（审计写入事务感知），
 * 跨表外层事务回滚不会留幽灵审计。
 *
 * <p><b>事务与审计的装配关系</b>（调用链自外向内）：
 * <pre>
 * 调用方 → {@link TransactionalInventoryService}（@Transactional 开跨表外层事务）
 *        → {@link InventoryService} Bean（@Audited 在领域方法上，AuditAspect 自动代理拦截）
 *        → 仓储（锁 balance 行 → UPDATE balance + INSERT transaction，随外层事务原子提交）
 * </pre>
 * {@code InventoryService} 必须注册为 Bean 审计切面才会代理它；但<b>调用方只准注入
 * {@link TransactionalInventoryService}</b>——直接调 InventoryService Bean 没有外层事务，
 * lockForUpdate（Propagation.MANDATORY）会 fail-fast 拒绝（防绕过事务边界误用）。
 */
@Configuration
public class InventoryInfraConfig {

    // ---------------- 仓储（MySQL 实现，唯一写入口铁律：仅 InventoryService 可写两表） ----------------

    @Bean
    public InventoryBalanceRepository inventoryBalanceRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInventoryBalanceRepository(jdbcTemplate);
    }

    @Bean
    public InventoryTransactionRepository inventoryTransactionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInventoryTransactionRepository(jdbcTemplate);
    }

    // ---------------- 成本策略与库存策略（配置绑定见 application.yml sjherp.inventory.*） ----------------

    /**
     * 成本核算策略（Q-2 预留）：v1.0 仅支持移动加权 MOVING_AVERAGE；配置成其他值
     * 启动期 fail-fast（FIFO 留实现位不留死代码，接入时在此扩展分支）。
     */
    @Bean
    public CostingStrategy costingStrategy(
            @Value("${sjherp.inventory.costing-method:MOVING_AVERAGE}") String costingMethod) {
        if (!"MOVING_AVERAGE".equals(costingMethod)) {
            throw new IllegalStateException("不支持的成本核算方法 sjherp.inventory.costing-method="
                    + costingMethod + "（v1.0 仅支持 MOVING_AVERAGE；FIFO 留实现位）");
        }
        return new MovingWeightedAverageCalculator();
    }

    /** 库存策略：负库存开关默认 false（拆解 §1.5，打开需在部署文档确认，成本口径退化） */
    @Bean
    public InventoryPolicy inventoryPolicy(
            @Value("${sjherp.inventory.allow-negative-stock:false}") boolean allowNegativeStock) {
        return new InventoryPolicy(allowNegativeStock);
    }

    // ---------------- 领域服务 + 事务包装（库存两表的唯一写入口） ----------------

    /**
     * 库存领域服务（@Audited 写方法由 AuditAspect 自动代理）。
     * <b>调用方不要直接注入本 Bean</b>——一律经 {@link #transactionalInventoryService}。
     */
    @Bean
    public InventoryService inventoryService(InventoryBalanceRepository inventoryBalanceRepository,
                                             InventoryTransactionRepository inventoryTransactionRepository,
                                             CostingStrategy costingStrategy,
                                             InventoryPolicy inventoryPolicy) {
        return new InventoryService(inventoryBalanceRepository, inventoryTransactionRepository,
                costingStrategy, inventoryPolicy);
    }

    /** 事务包装（T01c REST / Agent 工具及后续单据服务注入的入口） */
    @Bean
    public TransactionalInventoryService transactionalInventoryService(InventoryService inventoryService) {
        return new TransactionalInventoryService(inventoryService);
    }
}
