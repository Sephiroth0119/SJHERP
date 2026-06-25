package com.sjherp.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.production.KittingCheckAppService;
import com.sjherp.app.production.MaterialIssueAppService;
import com.sjherp.app.production.MaterialReturnAppService;
import com.sjherp.app.production.ProductionCostSettlementAppService;
import com.sjherp.app.production.ProductionReportAppService;
import com.sjherp.app.tool.production.ApproveCostSettlementTool;
import com.sjherp.app.tool.production.ApproveMaterialIssueTool;
import com.sjherp.app.tool.production.ApproveMaterialReturnTool;
import com.sjherp.app.tool.production.ApproveProductionReportTool;
import com.sjherp.app.tool.production.CancelWorkOrderTool;
import com.sjherp.app.tool.production.CheckKittingTool;
import com.sjherp.app.tool.production.CompleteWorkOrderTool;
import com.sjherp.app.tool.production.CreateCostSettlementTool;
import com.sjherp.app.tool.production.CreateMaterialIssueTool;
import com.sjherp.app.tool.production.CreateMaterialReturnTool;
import com.sjherp.app.tool.production.CreateProductionReportTool;
import com.sjherp.app.tool.production.CreateWorkOrderFromMrpTool;
import com.sjherp.app.tool.production.CreateWorkOrderTool;
import com.sjherp.app.tool.production.PostCostSettlementTool;
import com.sjherp.app.tool.production.PostMaterialIssueTool;
import com.sjherp.app.tool.production.PostMaterialReturnTool;
import com.sjherp.app.tool.production.PostProductionReportTool;
import com.sjherp.app.tool.production.QueryCostSettlementTool;
import com.sjherp.app.tool.production.QueryMaterialIssueTool;
import com.sjherp.app.tool.production.QueryMaterialReturnTool;
import com.sjherp.app.tool.production.QueryMrpRunTool;
import com.sjherp.app.tool.production.QueryProductionReportTool;
import com.sjherp.app.tool.production.QueryWorkOrderTool;
import com.sjherp.app.tool.production.ReleaseWorkOrderTool;
import com.sjherp.app.tool.production.ReverseWorkOrderTool;
import com.sjherp.app.tool.production.StartWorkOrderTool;

/**
 * 生产模块 Agent 工具装配（M5-T07）。
 *
 * <p>独立于 {@link DomainToolConfig}（后者已临界爆炸，见设计真源 docs/M5拆解-生产Agent工具.md
 * D-6），生产工具单独装配。<b>常驻注册</b> 26 个（所有 profile 生效）：
 * 工单 8（query_work_order NORMAL + create / create_work_order_from_mrp / release /
 * start / complete / cancel / reverse_work_order 7 HIGH）+ 领料 4（query NORMAL +
 * create / approve / post 3 HIGH）+ 退料 4（query NORMAL + create / approve / post 3 HIGH）
 * + check_kitting 1 NORMAL + 报工 4（query NORMAL + create / approve / post 3 HIGH）
 * + 成本结转 4（query NORMAL + create / approve / post 3 HIGH）+ query_mrp_run 1 NORMAL。
 * 合计 7 NORMAL + 19 HIGH。
 *
 * <p>权限点（零新增，复用 REST 同口径）：工单 production:wo、领料/退料/齐套 production:material、
 * 报工 production:report、成本结转 production:cost、MRP 查询 production:mrp。
 * 全部经各应用/事务服务唯一写入口执行（CLAUDE.md 原则 1：工具即领域服务，绝不绕过）；
 * 建单·审核(approve)·过账(post) 各为独立 HIGH 工具，忠于状态机与职责分离，框架级 HITL 确认。
 *
 * <p>⚠️ 新增工具后必须同步：{@code HighRiskToolPermissionTest} 注册清单与数量基线、
 * docs/领域工具清单.md、LlmAgent 系统提示词「当前业务能力」段。
 */
@Configuration
public class ProductionToolConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductionToolConfig.class);

    ProductionToolConfig(ToolRegistry registry,
                         TransactionalWorkOrderService transactionalWorkOrderService,
                         MaterialIssueAppService materialIssueAppService,
                         MaterialReturnAppService materialReturnAppService,
                         KittingCheckAppService kittingCheckAppService,
                         ProductionReportAppService productionReportAppService,
                         ProductionCostSettlementAppService productionCostSettlementAppService,
                         TransactionalMrpService transactionalMrpService) {
        // 工单查询（NORMAL，production:wo）
        registry.register(new QueryWorkOrderTool(transactionalWorkOrderService));
        // 工单建单/状态流转（HIGH，production:wo：建单/下达/开工/完工/作废/冲销影响生产承诺与库存）
        registry.register(new CreateWorkOrderTool(transactionalWorkOrderService));
        registry.register(new CreateWorkOrderFromMrpTool(transactionalWorkOrderService));
        registry.register(new ReleaseWorkOrderTool(transactionalWorkOrderService));
        registry.register(new StartWorkOrderTool(transactionalWorkOrderService));
        registry.register(new CompleteWorkOrderTool(transactionalWorkOrderService));
        registry.register(new CancelWorkOrderTool(transactionalWorkOrderService));
        registry.register(new ReverseWorkOrderTool(transactionalWorkOrderService));
        // 领料单（HIGH 建/审/过账影响库存出库；NORMAL 查询；production:material）
        registry.register(new CreateMaterialIssueTool(materialIssueAppService));
        registry.register(new ApproveMaterialIssueTool(materialIssueAppService));
        registry.register(new PostMaterialIssueTool(materialIssueAppService));
        registry.register(new QueryMaterialIssueTool(materialIssueAppService));
        // 退料单（HIGH 建/审/过账按原领料成本入库；NORMAL 查询；production:material）
        registry.register(new CreateMaterialReturnTool(materialReturnAppService));
        registry.register(new ApproveMaterialReturnTool(materialReturnAppService));
        registry.register(new PostMaterialReturnTool(materialReturnAppService));
        registry.register(new QueryMaterialReturnTool(materialReturnAppService));
        // 齐套检查（NORMAL，只读，production:material）
        registry.register(new CheckKittingTool(kittingCheckAppService));
        // 报工单（HIGH 建/审/过账完工入库结转料费；NORMAL 查询；production:report）
        registry.register(new CreateProductionReportTool(productionReportAppService));
        registry.register(new ApproveProductionReportTool(productionReportAppService));
        registry.register(new PostProductionReportTool(productionReportAppService));
        registry.register(new QueryProductionReportTool(productionReportAppService));
        // 月末成本结转单（HIGH 建/审/过账追加完工工费 + 出 GL；NORMAL 查询；production:cost）
        registry.register(new CreateCostSettlementTool(productionCostSettlementAppService));
        registry.register(new ApproveCostSettlementTool(productionCostSettlementAppService));
        registry.register(new PostCostSettlementTool(productionCostSettlementAppService));
        registry.register(new QueryCostSettlementTool(productionCostSettlementAppService));
        // MRP 运行查询（NORMAL，含生产/采购建议，production:mrp）
        registry.register(new QueryMrpRunTool(transactionalMrpService));
        log.info("已注册生产模块 Agent 工具（M5-T07，常驻 26 个）："
                + "查询 7 个（NORMAL）+ 写 19 个（HIGH）");
    }
}
