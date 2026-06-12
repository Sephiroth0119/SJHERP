package com.sjherp.app.tool.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;

/**
 * 商品档案创建工具（M2-T08，HIGH——档案创建影响主数据，框架强制确认卡片）。
 *
 * <p>基本单位按名称解析（用户在聊天里说"单位是箱"，而非单位 id）：
 * 单位不存在时返回清晰错误并列出已有单位，由模型引导用户先建单位或改用已有单位
 * ——本批工具不含创建单位能力，绝不静默代建。
 * 写操作经 {@link ProductService} 唯一入口，编码自动生成（SKU-年月-序号）。
 */
public class CreateProductTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateProductTool.class);

    public static final String NAME = "create_product";

    private final ProductService productService;
    private final UnitService unitService;

    public CreateProductTool(ProductService productService, UnitService unitService) {
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.unitService = Objects.requireNonNull(unitService, "unitService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建商品档案：名称与基本单位必填（基本单位传单位名称，如\"个\"\"箱\"\"千克\"），"
                + "规格/条码/备注可选，编码自动生成（SKU-年月-序号）。"
                + "调用前先在回复正文中向用户复述要创建的商品要点；系统会自动请求用户确认后才真正执行。"
                + "若基本单位在系统中不存在，本工具会报错并列出已有单位——此时引导用户改用已有单位，"
                + "或先到界面新建单位，不要编造单位。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "name":{"type":"string","description":"商品名称（必填，200 字以内）"},\
                "base_unit":{"type":"string","description":"基本单位名称（必填，如\\"个\\"\\"箱\\"，须是系统中已存在的计量单位）"},\
                "spec":{"type":"string","description":"规格型号，可选（如\\"500ml\\"）"},\
                "barcode":{"type":"string","description":"条码，可选"},\
                "remark":{"type":"string","description":"备注，可选"}},\
                "required":["name","base_unit"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "catalog:create_product";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String unitName = ArchiveToolSupport.str(arguments.get("base_unit"));

        // 单位按名称解析：不存在给清晰错误（列出已有单位），由模型引导用户处理
        Optional<Unit> unit = unitService.findAll().stream()
                .filter(u -> u.getName().equals(unitName))
                .findFirst();
        if (unit.isEmpty()) {
            String existing = unitService.findAll().stream()
                    .map(Unit::getName)
                    .collect(Collectors.joining("、"));
            return ToolResult.fail("计量单位「" + unitName + "」不存在，无法创建商品。"
                    + (existing.isBlank() ? "系统中还没有任何计量单位。" : "系统中已有单位：" + existing + "。")
                    + "请引导用户改用已有单位，或先在系统界面（基础档案-计量单位）创建该单位后再试；"
                    + "当前聊天中没有创建单位的工具，不要替用户编造单位。");
        }

        ProductCommand command = new ProductCommand(
                null, // 编码自动生成
                ArchiveToolSupport.str(arguments.get("name")),
                ArchiveToolSupport.str(arguments.get("spec")),
                null, // 类目可后续在界面归类
                unit.get().getId(),
                ArchiveToolSupport.str(arguments.get("barcode")),
                ArchiveToolSupport.str(arguments.get("remark")),
                null);
        try {
            Product product = productService.create(command, ArchiveToolSupport.operator(context));
            log.info("Agent 创建商品（code={}, name={}, operator={}, sessionId={}）",
                    product.getCode(), product.getName(),
                    ArchiveToolSupport.operator(context), context.sessionId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", product.getId());
            data.put("code", product.getCode());
            data.put("name", product.getName());
            data.put("baseUnit", unit.get().getName());
            data.put("status", ArchiveToolSupport.statusLabel(product.getStatus()));
            return ToolResult.ok(data);
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（名称超长、编码冲突等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("商品创建被拒绝: " + e.getMessage());
        }
    }
}
