package com.sjherp.domain.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * MaterialIssueLine 值对象单元测试（M5-T04）。
 *
 * <p>覆盖：数量精度缩放、应领量为 0 允许、实领量为 0 拒绝、
 * issuedCost 舍入、负成本拒绝、assignId 只许一次。
 */
class MaterialIssueLineTest {

    // ---------------------------------------------------------------- 建单精度

    @Test
    void create_数量自动缩放到6位小数() {
        // 传入 3 位小数，期望存储为 6 位
        MaterialIssueLine line = MaterialIssueLine.create(1, 101L,
                new BigDecimal("5.000"), new BigDecimal("4.500"), 1L);

        assertThat(line.getRequiredQty()).isEqualByComparingTo("5.000000");
        assertThat(line.getQuantity()).isEqualByComparingTo("4.500000");
        assertThat(line.getRequiredQty().scale()).isEqualTo(6);
        assertThat(line.getQuantity().scale()).isEqualTo(6);
        // 建单时 issuedCost 为 null
        assertThat(line.getIssuedCost()).isNull();
    }

    // ---------------------------------------------------------------- 应领量 = 0 允许

    @Test
    void create_应领量为0_允许() {
        // 应领量可为 0（仅记录不领或由齐套检查决定实领量）
        MaterialIssueLine line = MaterialIssueLine.create(1, 101L,
                BigDecimal.ZERO, new BigDecimal("2"), 1L);

        assertThat(line.getRequiredQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------- 实领量 <= 0 拒绝

    @Test
    void create_实领量为0_抛异常() {
        assertThatThrownBy(() -> MaterialIssueLine.create(1, 101L,
                BigDecimal.ONE, BigDecimal.ZERO, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须大于 0");
    }

    @Test
    void create_实领量为负_抛异常() {
        assertThatThrownBy(() -> MaterialIssueLine.create(1, 101L,
                BigDecimal.ONE, new BigDecimal("-1"), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须大于 0");
    }

    // ---------------------------------------------------------------- issuedCost 精度

    @Test
    void assignIssuedCost_金额缩放到2位小数() {
        MaterialIssueLine line = MaterialIssueLine.create(1, 101L,
                new BigDecimal("5"), new BigDecimal("4"), 1L);

        // 传入 4 位小数，期望 HALF_UP 缩为 2 位
        line.assignIssuedCost(new BigDecimal("120.5678"));

        assertThat(line.getIssuedCost()).isEqualByComparingTo("120.57");
        assertThat(line.getIssuedCost().scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 负成本拒绝

    @Test
    void assignIssuedCost_负数_抛异常() {
        MaterialIssueLine line = MaterialIssueLine.create(1, 101L,
                new BigDecimal("5"), new BigDecimal("4"), 1L);

        assertThatThrownBy(() -> line.assignIssuedCost(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }

    // ---------------------------------------------------------------- assignId 只许一次

    @Test
    void assignId_第二次调用_抛异常() {
        MaterialIssueLine line = MaterialIssueLine.create(1, 101L,
                new BigDecimal("5"), new BigDecimal("4"), 1L);

        line.assignId(100L);

        assertThatThrownBy(() -> line.assignId(200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已分配");
    }
}
