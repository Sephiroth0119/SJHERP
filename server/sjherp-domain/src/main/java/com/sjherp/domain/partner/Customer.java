package com.sjherp.domain.partner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 客户档案聚合根（模式样板：商品档案 {@code domain/catalog/Product}）。
 *
 * <p>档案不是单据：没有状态机流转，只有启用/停用两态（{@link ArchiveStatus}）。
 * 档案**不可物理删除**（历史单据引用必须永远可追溯），下线即停用。
 *
 * <p>code 租户内唯一（唯一性经仓储校验，由 {@link CustomerService} 把关；
 * 数据库 tenant_id+code 联合唯一键兜底）。
 */
public final class Customer {

    /** 默认币种：v1.0 仅 CNY（Q-4 决策），字段预留多币种扩展，不开放修改 */
    public static final String DEFAULT_CURRENCY = "CNY";

    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 200;
    private static final int CONTACT_MAX_LENGTH = 64;
    private static final int PHONE_MAX_LENGTH = 32;
    private static final int ADDRESS_MAX_LENGTH = 255;
    private static final int TAX_NO_MAX_LENGTH = 64;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 客户编码（租户内唯一；可手填或由编号规则 CUS-年月-序号 自动生成） */
    private String code;

    private String name;

    /** 联系人，可空 */
    private String contactPerson;

    /** 联系电话，可空 */
    private String contactPhone;

    /** 地址，可空 */
    private String address;

    /** 税号（纳税人识别号），可空 */
    private String taxNo;

    /** 结算方式（必填）：月结/现结/预付 */
    private SettlementMethod settlementMethod;

    /**
     * 信用额度（BigDecimal，可空表示不设限；不可为负）。
     *
     * <p>TODO（M3 销售订单落地后）：下单/发货时校验"应收余额 + 在途订单金额"
     * 是否超过信用额度，超限给出阻断或警告策略。本期仅做档案字段登记。
     */
    private BigDecimal creditLimit;

    /** 默认币种：恒为 CNY（v1.0 不做多币种，字段预留） */
    private final String currency;

    private ArchiveStatus status;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建客户，初始状态为启用（id 由仓储落库后回填） */
    public Customer(String code, String name, String contactPerson, String contactPhone,
                    String address, String taxNo, SettlementMethod settlementMethod,
                    BigDecimal creditLimit, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.contactPerson = validateOptional(contactPerson, CONTACT_MAX_LENGTH, "联系人");
        this.contactPhone = validateOptional(contactPhone, PHONE_MAX_LENGTH, "联系电话");
        this.address = validateOptional(address, ADDRESS_MAX_LENGTH, "地址");
        this.taxNo = validateOptional(taxNo, TAX_NO_MAX_LENGTH, "税号");
        this.settlementMethod = validateSettlementMethod(settlementMethod);
        this.creditLimit = validateCreditLimit(creditLimit);
        this.currency = DEFAULT_CURRENCY;
        this.status = ArchiveStatus.ENABLED;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private Customer(Long id, String code, String name, String contactPerson, String contactPhone,
                     String address, String taxNo, SettlementMethod settlementMethod,
                     BigDecimal creditLimit, String currency, ArchiveStatus status,
                     String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.contactPerson = contactPerson;
        this.contactPhone = contactPhone;
        this.address = address;
        this.taxNo = taxNo;
        this.settlementMethod = settlementMethod;
        this.creditLimit = creditLimit;
        this.currency = currency;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static Customer restore(long id, String code, String name, String contactPerson,
                                   String contactPhone, String address, String taxNo,
                                   SettlementMethod settlementMethod, BigDecimal creditLimit,
                                   String currency, ArchiveStatus status,
                                   String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        return new Customer(id, code, name, contactPerson, contactPhone, address, taxNo,
                settlementMethod, creditLimit, currency, status,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /**
     * 整体更新基础信息（code 唯一性由 {@link CustomerService} 经仓储校验）。
     * 停用客户也允许修正信息，但状态只能走 enable/disable。
     */
    public void update(String code, String name, String contactPerson, String contactPhone,
                       String address, String taxNo, SettlementMethod settlementMethod,
                       BigDecimal creditLimit, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.contactPerson = validateOptional(contactPerson, CONTACT_MAX_LENGTH, "联系人");
        this.contactPhone = validateOptional(contactPhone, PHONE_MAX_LENGTH, "联系电话");
        this.address = validateOptional(address, ADDRESS_MAX_LENGTH, "地址");
        this.taxNo = validateOptional(taxNo, TAX_NO_MAX_LENGTH, "税号");
        this.settlementMethod = validateSettlementMethod(settlementMethod);
        this.creditLimit = validateCreditLimit(creditLimit);
        touch(operator);
    }

    /** 启用：仅停用状态可启用（重复启用视为误操作，直接拒绝） */
    public void enable(String operator) {
        if (status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("客户[" + code + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用：仅启用状态可停用；停用后新单据不得引用，历史数据不受影响 */
    public void disable(String operator) {
        if (status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException("客户[" + code + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("客户 id 已分配，不可重复分配: " + this.id);
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
            throw new IllegalArgumentException("客户编码不能为空");
        }
        String trimmed = code.strip();
        if (trimmed.length() > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("客户编码不能超过 " + CODE_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("客户名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("客户名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
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

    private static SettlementMethod validateSettlementMethod(SettlementMethod settlementMethod) {
        if (settlementMethod == null) {
            throw new IllegalArgumentException("结算方式不能为空");
        }
        return settlementMethod;
    }

    /** 信用额度：可空表示不设限；金额一律 BigDecimal，不可为负 */
    private static BigDecimal validateCreditLimit(BigDecimal creditLimit) {
        if (creditLimit == null) {
            return null;
        }
        if (creditLimit.signum() < 0) {
            throw new IllegalArgumentException("信用额度不能为负数: " + creditLimit);
        }
        return creditLimit;
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

    public String getContactPerson() {
        return contactPerson;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getAddress() {
        return address;
    }

    public String getTaxNo() {
        return taxNo;
    }

    public SettlementMethod getSettlementMethod() {
        return settlementMethod;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public String getCurrency() {
        return currency;
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
