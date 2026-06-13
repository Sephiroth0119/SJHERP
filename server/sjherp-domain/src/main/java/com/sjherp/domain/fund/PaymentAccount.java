package com.sjherp.domain.fund;

import java.time.Instant;
import java.util.Objects;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 资金账户档案聚合根（M4-T04a，模式样板：仓库档案 {@code domain/warehouse/Warehouse}）。
 *
 * <p>资金账户是收付款的"钱从哪进/出哪个账户"主数据：现金/银行/其他货币资金账户，
 * 各映射到一个 GL 货币科目（{@link #glAccountCode}，如 1001/1002/1012）——收/付款单
 * 过账时据此生成现金侧凭证（借/贷该科目）。
 *
 * <p>档案不是单据：没有"草稿→审核→执行"状态机，只有启用/停用两态（{@link ArchiveStatus}）。
 * 档案**不可物理删除**（历史收付款单引用必须永远可追溯），下线即停用。
 *
 * <p>{@link #glAccountCode} 的合法性（必须是已存在、启用、末级的 GL 科目）由
 * {@link PaymentAccountService} 经 GL 科目仓储校验——本聚合只做字段级格式校验。
 */
public final class PaymentAccount implements AuditTarget {

    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 200;
    private static final int GL_ACCOUNT_CODE_MAX_LENGTH = 32;
    private static final int BANK_NAME_MAX_LENGTH = 200;
    private static final int ACCOUNT_NO_MAX_LENGTH = 64;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 资金账户编码（租户内唯一；不填则按 FA-年月-序号 自动编号） */
    private String code;

    private String name;

    /** 账户类别：现金 / 银行 / 其他货币资金 */
    private PaymentAccountType accountType;

    /**
     * 映射的 GL 货币科目编码（如 1001/1002/1012）。
     *
     * <p>收/付款单过账时现金侧凭证借/贷此科目；其合法性（已存在、启用、末级）
     * 由 {@link PaymentAccountService} 经 GL 科目仓储校验。
     */
    private String glAccountCode;

    /** 开户行名称，可空（BANK 账户用） */
    private String bankName;

    /** 银行账号，可空（BANK 账户用） */
    private String accountNo;

    private ArchiveStatus status;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建资金账户，初始状态为启用（id 由仓储落库后回填） */
    public PaymentAccount(String code, String name, PaymentAccountType accountType, String glAccountCode,
                          String bankName, String accountNo, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.accountType = Objects.requireNonNull(accountType, "账户类别不能为空");
        this.glAccountCode = validateGlAccountCode(glAccountCode);
        this.bankName = validateOptional(bankName, BANK_NAME_MAX_LENGTH, "开户行");
        this.accountNo = validateOptional(accountNo, ACCOUNT_NO_MAX_LENGTH, "银行账号");
        this.status = ArchiveStatus.ENABLED;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private PaymentAccount(Long id, String code, String name, PaymentAccountType accountType,
                          String glAccountCode, String bankName, String accountNo, ArchiveStatus status,
                          String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.accountType = accountType;
        this.glAccountCode = glAccountCode;
        this.bankName = bankName;
        this.accountNo = accountNo;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static PaymentAccount restore(long id, String code, String name, PaymentAccountType accountType,
                                         String glAccountCode, String bankName, String accountNo,
                                         ArchiveStatus status, String createdBy, Instant createdAt,
                                         String updatedBy, Instant updatedAt) {
        return new PaymentAccount(id, code, name, accountType, glAccountCode, bankName, accountNo, status,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /**
     * 整体更新基础信息（code 唯一性、glAccountCode 合法性由 {@link PaymentAccountService} 把关）。
     * 停用账户也允许修正信息，但状态只能走 enable/disable。
     */
    public void update(String code, String name, PaymentAccountType accountType, String glAccountCode,
                       String bankName, String accountNo, String operator) {
        this.code = validateCode(code);
        this.name = validateName(name);
        this.accountType = Objects.requireNonNull(accountType, "账户类别不能为空");
        this.glAccountCode = validateGlAccountCode(glAccountCode);
        this.bankName = validateOptional(bankName, BANK_NAME_MAX_LENGTH, "开户行");
        this.accountNo = validateOptional(accountNo, ACCOUNT_NO_MAX_LENGTH, "银行账号");
        touch(operator);
    }

    /** 启用：仅停用状态可启用（重复启用视为误操作，直接拒绝） */
    public void enable(String operator) {
        if (status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("资金账户[" + code + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用：仅启用状态可停用；停用后新单据不得引用，历史数据不受影响 */
    public void disable(String operator) {
        if (status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException("资金账户[" + code + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("资金账户 id 已分配，不可重复分配: " + this.id);
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
            throw new IllegalArgumentException("资金账户编码不能为空");
        }
        String trimmed = code.strip();
        if (trimmed.length() > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("资金账户编码不能超过 " + CODE_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("资金账户名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("资金账户名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /** GL 科目编码字段级校验（非空 + 长度）；语义合法性（已存在/启用/末级）在 Service 层 */
    private static String validateGlAccountCode(String glAccountCode) {
        if (glAccountCode == null || glAccountCode.isBlank()) {
            throw new IllegalArgumentException("映射的 GL 科目编码不能为空");
        }
        String trimmed = glAccountCode.strip();
        if (trimmed.length() > GL_ACCOUNT_CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("GL 科目编码不能超过 " + GL_ACCOUNT_CODE_MAX_LENGTH + " 个字符");
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

    public PaymentAccountType getAccountType() {
        return accountType;
    }

    public String getGlAccountCode() {
        return glAccountCode;
    }

    public String getBankName() {
        return bankName;
    }

    public String getAccountNo() {
        return accountNo;
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
                + ", 类别=" + accountType.label() + ", GL科目=" + AuditTarget.text(glAccountCode)
                + ", 开户行=" + AuditTarget.text(bankName) + ", 账号=" + AuditTarget.text(accountNo)
                + ", 状态=" + status.label();
    }
}
