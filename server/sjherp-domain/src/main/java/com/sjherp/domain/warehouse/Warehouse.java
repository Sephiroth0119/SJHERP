package com.sjherp.domain.warehouse;

import java.time.Instant;
import java.util.Objects;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 仓库档案聚合根（模式样板：商品档案 {@code domain/catalog/Product}）。
 *
 * <p>档案不是单据：没有状态机流转，只有启用/停用两态（{@link ArchiveStatus}）。
 * 档案**不可物理删除**（历史单据引用必须永远可追溯），下线即停用。
 *
 * <p>小企业从简：仓库必有，库位可选——本期 {@code locationEnabled} 仅为
 * 开关字段，库位表与库位维度的库存留 M3 按需补齐。
 */
public final class Warehouse {

    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 200;
    private static final int ADDRESS_MAX_LENGTH = 255;
    private static final int MANAGER_MAX_LENGTH = 64;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 仓库编码（租户内唯一；可手填或由编号规则 WH-年月-序号 自动生成） */
    private String code;

    private String name;

    /** 地址，可空 */
    private String address;

    /** 负责人，可空 */
    private String manager;

    /**
     * 是否启用库位管理。
     *
     * <p>本期仅字段预留：true 表示该仓库的出入库未来需指定库位；
     * 库位表（storage_location）与库位维度的库存余额留 M3 按需建设。
     */
    private boolean locationEnabled;

    private ArchiveStatus status;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建仓库，初始状态为启用（id 由仓储落库后回填） */
    public Warehouse(String code, String name, String address, String manager,
                     boolean locationEnabled, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.address = validateOptional(address, ADDRESS_MAX_LENGTH, "地址");
        this.manager = validateOptional(manager, MANAGER_MAX_LENGTH, "负责人");
        this.locationEnabled = locationEnabled;
        this.status = ArchiveStatus.ENABLED;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private Warehouse(Long id, String code, String name, String address, String manager,
                      boolean locationEnabled, ArchiveStatus status,
                      String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.address = address;
        this.manager = manager;
        this.locationEnabled = locationEnabled;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static Warehouse restore(long id, String code, String name, String address, String manager,
                                    boolean locationEnabled, ArchiveStatus status,
                                    String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        return new Warehouse(id, code, name, address, manager, locationEnabled, status,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /**
     * 整体更新基础信息（code 唯一性由 {@link WarehouseService} 经仓储校验）。
     * 停用仓库也允许修正信息，但状态只能走 enable/disable。
     */
    public void update(String code, String name, String address, String manager,
                       boolean locationEnabled, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.address = validateOptional(address, ADDRESS_MAX_LENGTH, "地址");
        this.manager = validateOptional(manager, MANAGER_MAX_LENGTH, "负责人");
        this.locationEnabled = locationEnabled;
        touch(operator);
    }

    /** 启用：仅停用状态可启用（重复启用视为误操作，直接拒绝） */
    public void enable(String operator) {
        if (status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("仓库[" + code + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用：仅启用状态可停用；停用后新单据不得引用，历史数据不受影响 */
    public void disable(String operator) {
        if (status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException("仓库[" + code + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("仓库 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private void touch(String operator) {
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------
    // 校验
    // ---------------------------------------------------------------

    private static String validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("仓库编码不能为空");
        }
        String trimmed = code.strip();
        if (trimmed.length() > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("仓库编码不能超过 " + CODE_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("仓库名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("仓库名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /** 可空字段：空白视为 null，超长拒绝 */
    private static String validateOptional(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    // ---------------------------------------------------------------
    // 只读访问器
    // ---------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getManager() {
        return manager;
    }

    public boolean isLocationEnabled() {
        return locationEnabled;
    }

    public ArchiveStatus getStatus() {
        return status;
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
