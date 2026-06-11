package com.sjherp.domain.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * 计量单位档案（如：瓶、箱、千克）。
 *
 * <p>精度（precision）表示以该单位计量时数量保留的小数位数：
 * "瓶"通常 0 位（整瓶），"千克"可 3 位。数量运算一律 BigDecimal，
 * 该精度即出入库数量的 setScale 依据。
 */
public final class Unit {

    /** 单位精度上限：6 位小数（与数量列 DECIMAL(18,6) 对齐） */
    public static final int MAX_PRECISION = 6;

    private static final int NAME_MAX_LENGTH = 50;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    private String name;

    /** 以该单位计量时数量保留的小数位数（0–6） */
    private int precision;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建单位（id 由仓储落库后回填） */
    public Unit(String name, int precision, String operator) {
        this.name = validateName(name);
        this.precision = validatePrecision(precision);
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    /** 持久层重建（仅供仓储实现使用） */
    private Unit(Long id, String name, int precision,
                 String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.precision = precision;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static Unit restore(long id, String name, int precision,
                               String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        return new Unit(id, name, precision, createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 修改名称与精度（精度调整只影响后续录入的舍入，不回溯历史数据） */
    public void update(String name, int precision, String operator) {
        this.name = validateName(name);
        this.precision = validatePrecision(precision);
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("单位 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private void touch(String operator) {
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("单位名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("单位名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static int validatePrecision(int precision) {
        if (precision < 0 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("单位精度必须在 0-" + MAX_PRECISION + " 之间: " + precision);
        }
        return precision;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPrecision() {
        return precision;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
