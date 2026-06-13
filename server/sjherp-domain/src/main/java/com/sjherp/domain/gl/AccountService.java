package com.sjherp.domain.gl;

import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.audit.Audited;

/**
 * 会计科目领域服务（所有科目写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>纯 Java 零依赖：仅依赖科目仓储端口 {@link AccountRepository}，由 app 层装配。
 * 业务规则：
 * <ul>
 *   <li>编码唯一：手填编码经仓储查重（数据库唯一键兜底）；</li>
 *   <li>树形完整性：上级科目（若填）必须存在且<b>非末级</b>（末级不可再挂子科目，
 *       否则破坏"仅末级可挂账"的口径）；层级由上级推算（一级=1，有上级则 parent.level+1）；</li>
 *   <li>启停规则：重复启用/停用拒绝；预置科目禁停用（见 {@link Account#disable}）。</li>
 * </ul>
 */
public class AccountService {

    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /**
     * 新建科目：编码唯一、上级（若填）存在且非末级、层级由上级推算。
     *
     * @param code       科目编码
     * @param name       科目名称
     * @param type       科目类别
     * @param balanceDir 余额方向
     * @param parentCode 上级科目编码（一级科目传 null/空）
     * @param isLeaf     是否末级（仅末级可挂凭证行）
     * @param operator   操作人
     */
    @Audited(action = "account.create", targetType = "account")
    public Account create(String code, String name, AccountType type, BalanceDirection balanceDir,
                          String parentCode, boolean isLeaf, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(code, "科目编码不能为空");
        if (repository.existsByCode(code.strip())) {
            throw new IllegalArgumentException("科目编码已存在: " + code.strip());
        }

        int level = 1;
        String normalizedParent = (parentCode == null || parentCode.isBlank()) ? null : parentCode.strip();
        if (normalizedParent != null) {
            Account parent = get(normalizedParent);
            if (parent.isLeaf()) {
                throw new IllegalArgumentException("上级科目[" + normalizedParent
                        + "] 是末级科目，不可再挂子科目");
            }
            if (!code.strip().startsWith(normalizedParent)) {
                throw new IllegalArgumentException("子科目编码[" + code.strip()
                        + "] 必须以上级科目编码[" + normalizedParent + "] 为前缀（会计科目编码隶属约定）");
            }
            level = parent.getLevel() + 1;
        }

        Account account = Account.create(code, name, type, balanceDir, normalizedParent, level, isLeaf,
                operator);
        repository.save(account);
        return account;
    }

    /** 停用科目（预置科目禁停用，由 {@link Account#disable} 守门） */
    @Audited(action = "account.disable", targetType = "account")
    public Account disable(String code, String operator) {
        requireOperator(operator);
        Account account = get(code);
        account.disable(operator);
        repository.save(account);
        return account;
    }

    /** 启用科目 */
    @Audited(action = "account.enable", targetType = "account")
    public Account enable(String code, String operator) {
        requireOperator(operator);
        Account account = get(code);
        account.enable(operator);
        repository.save(account);
        return account;
    }

    /** 按编码查（不存在抛 {@link AccountNotFoundException} → API 404） */
    public Account get(String code) {
        return repository.findByCode(Objects.requireNonNull(code, "科目编码不能为空").strip())
                .orElseThrow(() -> new AccountNotFoundException(code));
    }

    /** 全部科目（按编码升序） */
    public List<Account> listAll() {
        return repository.findAll();
    }

    /** 全部末级科目（仅末级可挂账，供凭证录入） */
    public List<Account> listLeaf() {
        return repository.findLeaf();
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
