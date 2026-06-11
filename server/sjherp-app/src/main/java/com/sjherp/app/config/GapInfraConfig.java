package com.sjherp.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.gap.RecordProcessGapTool;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.infra.persistence.gap.JdbcGapRecordRepository;

/**
 * 流程缺口通道（M1-T04）装配：仓储 MySQL 实现 + 领域服务 + Agent 工具注册。
 *
 * <p>record_process_gap 是第一个**常驻注册**的业务工具（非 dev-only）：
 * 缺口记录通道是自进化闭环的第一环，所有环境都必须可用。
 * 装配约定同 {@link CatalogInfraConfig}（domain/infra 类不加 Spring 注解）。
 */
@Configuration
public class GapInfraConfig {

    private static final Logger log = LoggerFactory.getLogger(GapInfraConfig.class);

    @Bean
    public GapRecordRepository gapRecordRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcGapRecordRepository(jdbcTemplate);
    }

    /** 编号生成器复用 CatalogInfraConfig 注册的 DocumentNumberGenerator（GAP-年月-序号） */
    @Bean
    public GapRecordService gapRecordService(GapRecordRepository gapRecordRepository,
                                             DocumentNumberGenerator documentNumberGenerator) {
        return new GapRecordService(gapRecordRepository, documentNumberGenerator);
    }

    /** 常驻注册缺口记录工具（NORMAL 风险：记录缺口不产生业务影响，不走高风险拦截） */
    @Bean
    public RecordProcessGapTool recordProcessGapTool(ToolRegistry toolRegistry,
                                                     GapRecordService gapRecordService) {
        RecordProcessGapTool tool = new RecordProcessGapTool(gapRecordService);
        toolRegistry.register(tool);
        log.info("已注册流程缺口记录工具：record_process_gap（NORMAL，常驻）");
        return tool;
    }
}
