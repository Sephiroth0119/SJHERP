package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * BOM 行（值对象，从属 {@link BillOfMaterials} 聚合）。
 *
 * <p>语义：父件 1 单位净需求对应 {@code quantity} 单位的子件，
 * 损耗率 {@code scrapRate} 采用<b>加成法</b>（制造业主流，SAP/用友/金蝶默认）：
 * {@code 毛需求 = 净需求 × (1 + scrapRate)}。
 * 注：yield 法为 ÷(1−r)，本系统不采用（注释留档）。
 *
 * <p>原则 5：数量/损耗率一律 {@link BigDecimal}（数据库 DECIMAL），禁止 float/double。
 *
 * @param childProductId 子件商品 id（不得等于父件，由聚合根构造器额外校验）
 * @param quantity       净用量（DECIMAL(18,6)，必须 &gt; 0，小数位 ≤ 6）
 * @param scrapRate      损耗率（DECIMAL(8,6)，范围 [0, 1)，小数位 ≤ 6）
 * @param unitId         子件计量单位 id
 */
public record BomLine(long childProductId, BigDecimal quantity, BigDecimal scrapRate, long unitId) {

    /** 数量/损耗率最大小数位数（数据库列 DECIMAL 精度对齐） */
    public static final int MAX_SCALE = 6;

    public BomLine {
        Objects.requireNonNull(quantity, "BOM 行用量不能为空");
        Objects.requireNonNull(scrapRate, "BOM 行损耗率不能为空");

        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("BOM 行用量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "BOM 行用量小数位数不能超过 " + MAX_SCALE + " 位: " + quantity.toPlainString());
        }
        if (scrapRate.signum() < 0) {
            throw new IllegalArgumentException("损耗率不能为负数: " + scrapRate.toPlainString());
        }
        // scrapRate >= 1 会导致净需求无限发散，拒绝
        if (scrapRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(
                    "损耗率必须小于 1（小于 100%）: " + scrapRate.toPlainString());
        }
        if (scrapRate.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "损耗率小数位数不能超过 " + MAX_SCALE + " 位: " + scrapRate.toPlainString());
        }
    }

    /**
     * 加成法计算毛需求：{@code netParentQty × (1 + scrapRate)}。
     *
     * <p>乘法不舍入（同 {@link com.sjherp.domain.catalog.UnitConversion#toBaseQuantity} 约定），
     * 舍入交调用方（MRP 展开 T02 决定精度）。
     *
     * @param netParentQty 父件净需求数量（必须 &gt; 0）
     * @return 子件毛需求（= netParentQty × (1 + scrapRate)）
     */
    public BigDecimal grossQuantity(BigDecimal netParentQty) {
        Objects.requireNonNull(netParentQty, "父件净需求不能为空");
        if (netParentQty.signum() <= 0) {
            throw new IllegalArgumentException("父件净需求必须大于 0: " + netParentQty.toPlainString());
        }
        // 加成法：毛需求 = 净需求 × (1 + 损耗率)
        return netParentQty.multiply(BigDecimal.ONE.add(scrapRate));
    }
}
