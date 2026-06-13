package com.sjherp.app.gl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountType;
import com.sjherp.domain.gl.BalanceDirection;

/**
 * 会计科目应用服务（M4-T01）：REST {@code GlAccountController} 的公共入口。
 *
 * <p>职责：解析请求枚举字符串（类别/方向）→ 委托领域 {@link AccountService}（编码唯一、上级存在且非末级、
 * 层级由上级推算等业务规则在领域层）；写方法标 {@code @Transactional}（与 @Audited 写方法同一外层事务），
 * 查询 {@code @Transactional(readOnly = true)}。科目表/末级科目查询照例不设权限点（登录即可）。
 */
@Service
public class AccountAppService {

    private final AccountService accountService;

    public AccountAppService(AccountService accountService) {
        this.accountService = Objects.requireNonNull(accountService, "accountService 不能为空");
    }

    /**
     * 新建科目（编码唯一、上级存在且非末级、层级由上级推算）。
     *
     * @param code       科目编码
     * @param name       科目名称
     * @param type       科目类别字符串（ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS）
     * @param balanceDir 余额方向字符串（DEBIT/CREDIT）
     * @param parentCode 上级科目编码（一级科目传 null/空）
     * @param isLeaf     是否末级（仅末级可挂凭证行）
     * @param operator   操作人
     */
    @Transactional
    public Account create(String code, String name, String type, String balanceDir, String parentCode,
                          boolean isLeaf, String operator) {
        return accountService.create(code, name, parseType(type), parseDirection(balanceDir),
                parentCode, isLeaf, operator);
    }

    /** 停用科目（预置科目禁停用，由领域层守门 → 400） */
    @Transactional
    public Account disable(String code, String operator) {
        return accountService.disable(code, operator);
    }

    /** 启用科目 */
    @Transactional
    public Account enable(String code, String operator) {
        return accountService.enable(code, operator);
    }

    /** 按编码查（不存在抛 AccountNotFoundException → 404） */
    @Transactional(readOnly = true)
    public Account get(String code) {
        return accountService.get(code);
    }

    /** 全部科目（按编码升序） */
    @Transactional(readOnly = true)
    public List<Account> listAll() {
        return accountService.listAll();
    }

    /** 全部末级科目（仅末级可挂账，供凭证录入） */
    @Transactional(readOnly = true)
    public List<Account> listLeaf() {
        return accountService.listLeaf();
    }

    private static AccountType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("科目类别不能为空（ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS）");
        }
        try {
            return AccountType.valueOf(type.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("科目类别非法（ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS）: "
                    + type);
        }
    }

    private static BalanceDirection parseDirection(String balanceDir) {
        if (balanceDir == null || balanceDir.isBlank()) {
            throw new IllegalArgumentException("余额方向不能为空（DEBIT/CREDIT）");
        }
        try {
            return BalanceDirection.valueOf(balanceDir.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("余额方向非法（DEBIT/CREDIT）: " + balanceDir);
        }
    }
}
