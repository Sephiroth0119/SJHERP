package com.sjherp.domain.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 商品档案聚合根（后续客户/供应商/仓库档案的模式样板）。
 *
 * <p>档案不是单据：没有状态机流转，只有启用/停用两态（{@link ArchiveStatus}）。
 * 档案**不可物理删除**（历史单据引用必须永远可追溯），下线即停用。
 *
 * <p>聚合边界：商品 + 商品级多单位换算（{@link UnitConversion}）整体读写，
 * 换算表不单独存在。code 全局唯一（唯一性经仓储校验，由 {@link ProductService}
 * 把关；数据库 tenant_id+code 联合唯一键兜底）。
 */
public final class Product implements AuditTarget {

    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 200;
    private static final int SPEC_MAX_LENGTH = 200;
    private static final int BARCODE_MAX_LENGTH = 64;
    private static final int REMARK_MAX_LENGTH = 500;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 商品编码（全局唯一；可手填或由编号规则 SKU-年月-序号 自动生成） */
    private String code;

    private String name;

    /** 规格型号（如 "500ml"、"304 不锈钢 2mm"），可空 */
    private String spec;

    /** 所属类目 id，可空（小企业允许先建商品后归类） */
    private Long categoryId;

    /** 基本单位 id（库存与成本核算的计量基准，必填） */
    private long baseUnitId;

    /** 条码，可空 */
    private String barcode;

    private ArchiveStatus status;

    /** 备注，可空 */
    private String remark;

    /** 商品级多单位换算（如 1 箱 = 12 瓶）；换算单位不得重复、不得等于基本单位 */
    private List<UnitConversion> unitConversions;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建商品，初始状态为启用（id 由仓储落库后回填） */
    public Product(String code, String name, String spec, Long categoryId, long baseUnitId,
                   String barcode, String remark, List<UnitConversion> unitConversions, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.spec = validateOptional(spec, SPEC_MAX_LENGTH, "规格");
        this.categoryId = categoryId;
        this.baseUnitId = baseUnitId;
        this.barcode = validateOptional(barcode, BARCODE_MAX_LENGTH, "条码");
        this.remark = validateOptional(remark, REMARK_MAX_LENGTH, "备注");
        this.unitConversions = validateConversions(unitConversions, baseUnitId);
        this.status = ArchiveStatus.ENABLED;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private Product(Long id, String code, String name, String spec, Long categoryId, long baseUnitId,
                    String barcode, ArchiveStatus status, String remark, List<UnitConversion> unitConversions,
                    String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.spec = spec;
        this.categoryId = categoryId;
        this.baseUnitId = baseUnitId;
        this.barcode = barcode;
        this.status = status;
        this.remark = remark;
        this.unitConversions = List.copyOf(unitConversions);
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static Product restore(long id, String code, String name, String spec, Long categoryId,
                                  long baseUnitId, String barcode, ArchiveStatus status, String remark,
                                  List<UnitConversion> unitConversions,
                                  String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        return new Product(id, code, name, spec, categoryId, baseUnitId, barcode, status, remark,
                unitConversions, createdBy, createdAt, updatedBy, updatedAt);
    }

    /**
     * 整体更新基础信息与换算表（code 唯一性由 {@link ProductService} 经仓储校验）。
     * 停用商品也允许修正信息（如更名后再启用），但状态只能走 enable/disable。
     */
    public void update(String code, String name, String spec, Long categoryId, long baseUnitId,
                       String barcode, String remark, List<UnitConversion> unitConversions, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.spec = validateOptional(spec, SPEC_MAX_LENGTH, "规格");
        this.categoryId = categoryId;
        this.baseUnitId = baseUnitId;
        this.barcode = validateOptional(barcode, BARCODE_MAX_LENGTH, "条码");
        this.remark = validateOptional(remark, REMARK_MAX_LENGTH, "备注");
        this.unitConversions = validateConversions(unitConversions, baseUnitId);
        touch(operator);
    }

    /** 启用：仅停用状态可启用（启用已启用商品视为误操作，直接拒绝） */
    public void enable(String operator) {
        if (status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("商品[" + code + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用：仅启用状态可停用；停用后新单据不得引用，历史数据不受影响 */
    public void disable(String operator) {
        if (status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException("商品[" + code + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("商品 id 已分配，不可重复分配: " + this.id);
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
            throw new IllegalArgumentException("商品编码不能为空");
        }
        String trimmed = code.strip();
        if (trimmed.length() > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("商品编码不能超过 " + CODE_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("商品名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("商品名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
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

    /** 换算表校验：换算单位不得重复、不得等于基本单位（基本单位换算率恒为 1，无需登记） */
    private static List<UnitConversion> validateConversions(List<UnitConversion> conversions, long baseUnitId) {
        if (conversions == null || conversions.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>();
        List<UnitConversion> result = new ArrayList<>(conversions.size());
        for (UnitConversion conversion : conversions) {
            Objects.requireNonNull(conversion, "换算项不能为空");
            if (conversion.unitId() == baseUnitId) {
                throw new IllegalArgumentException("基本单位无需登记换算率（恒为 1）: unitId=" + baseUnitId);
            }
            if (!seen.add(conversion.unitId())) {
                throw new IllegalArgumentException("同一换算单位不可重复登记: unitId=" + conversion.unitId());
            }
            result.add(conversion);
        }
        return List.copyOf(result);
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

    public String getSpec() {
        return spec;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public long getBaseUnitId() {
        return baseUnitId;
    }

    public String getBarcode() {
        return barcode;
    }

    public ArchiveStatus getStatus() {
        return status;
    }

    public String getRemark() {
        return remark;
    }

    /** 不可变换算表 */
    public List<UnitConversion> getUnitConversions() {
        return unitConversions;
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

    // ---------------- 审计目标（M2-T07） ----------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return code;
    }

    @Override
    public String auditSummary() {
        return "编码=" + AuditTarget.text(code) + ", 名称=" + AuditTarget.text(name)
                + ", 规格=" + AuditTarget.text(spec) + ", 条码=" + AuditTarget.text(barcode)
                + ", 基本单位id=" + baseUnitId
                + ", 类目id=" + (categoryId == null ? "-" : categoryId)
                + ", 换算数=" + unitConversions.size() + ", 状态=" + status.label();
    }
}
