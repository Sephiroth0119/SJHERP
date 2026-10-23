package com.sjherp.app.pilot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 虚构演示企业种子包（M8-T07）。名称必须带「演示」或「Demo」前缀，禁止空名假数据。
 *
 * <p>v1.0 单租户：同一库同时只装一包。经济口径对齐月末关账验收（采购 100@12.50、销售 60@25.00），
 * 便于关账预检断言毛利 750.00。
 */
public record DemoCompanyPack(
        String id,
        String companyLabel,
        String unitName,
        String warehouseName,
        String tradeProductName,
        String openingProductName,
        String supplierName,
        String customerName,
        String paymentAccountName,
        String bankName,
        String accountNo,
        String glAccountCode,
        BigDecimal purchaseQty,
        BigDecimal purchasePrice,
        BigDecimal salesQty,
        BigDecimal salesPrice,
        BigDecimal openingQty,
        BigDecimal openingCost) {

    public static final DemoCompanyPack SUNSHINE = new DemoCompanyPack(
            "sunshine",
            "演示阳光文具",
            "演示件",
            "演示阳光仓",
            "演示中性笔",
            "演示草稿纸",
            "演示晨光供应商",
            "演示文具零售客户",
            "演示工行基本户",
            "演示银行",
            "6222-DEMO-0001",
            "1002",
            new BigDecimal("100"),
            new BigDecimal("12.50"),
            new BigDecimal("60"),
            new BigDecimal("25.00"),
            new BigDecimal("20"),
            new BigDecimal("8.00"));

    public static final DemoCompanyPack QINGHE = new DemoCompanyPack(
            "qinghe",
            "演示青禾食品",
            "演示千克",
            "演示青禾仓",
            "演示挂面",
            "演示面粉",
            "演示粮油供应商",
            "演示社区超市客户",
            "演示农行基本户",
            "演示农业银行",
            "6228-DEMO-0002",
            "1002",
            new BigDecimal("100"),
            new BigDecimal("12.50"),
            new BigDecimal("60"),
            new BigDecimal("25.00"),
            new BigDecimal("20"),
            new BigDecimal("8.00"));

    public DemoCompanyPack {
        Objects.requireNonNull(id, "pack id 不能为空");
        requireDemoName(companyLabel, "演示企业名称");
        requireDemoName(unitName, "单位名称");
        requireDemoName(warehouseName, "仓库名称");
        requireDemoName(tradeProductName, "经销商品名称");
        requireDemoName(openingProductName, "期初商品名称");
        requireDemoName(supplierName, "供应商名称");
        requireDemoName(customerName, "客户名称");
        requireDemoName(paymentAccountName, "资金账户名称");
        requireDemoName(bankName, "开户行");
        if (accountNo == null || accountNo.isBlank()
                || !(accountNo.contains("DEMO") || accountNo.contains("演示"))) {
            throw new IllegalArgumentException("银行账号必须含 DEMO 或 演示，避免被当成真实账号");
        }
        if (glAccountCode == null || glAccountCode.isBlank()) {
            throw new IllegalArgumentException("资金账户科目不能为空");
        }
        Objects.requireNonNull(purchaseQty, "采购数量不能为空");
        Objects.requireNonNull(purchasePrice, "采购单价不能为空");
        Objects.requireNonNull(salesQty, "销售数量不能为空");
        Objects.requireNonNull(salesPrice, "销售单价不能为空");
        Objects.requireNonNull(openingQty, "期初数量不能为空");
        Objects.requireNonNull(openingCost, "期初单价不能为空");
    }

    public String seedMarker() {
        return "SJHERP-DEMO-SEED:" + id;
    }

    public static DemoCompanyPack byId(String packId) {
        if (packId == null || packId.isBlank()) {
            return SUNSHINE;
        }
        String normalized = packId.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "sunshine" -> SUNSHINE;
            case "qinghe" -> QINGHE;
            default -> throw new IllegalArgumentException(
                    "未知演示种子包: " + packId + "（支持 sunshine / qinghe）");
        };
    }

    public static List<DemoCompanyPack> all() {
        return List.of(SUNSHINE, QINGHE);
    }

    static void requireDemoName(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空，禁止空名假数据");
        }
        String trimmed = name.strip();
        if (!trimmed.startsWith("演示") && !trimmed.startsWith("Demo")) {
            throw new IllegalArgumentException(label + "必须使用「演示」或「Demo」前缀: " + trimmed);
        }
    }
}
