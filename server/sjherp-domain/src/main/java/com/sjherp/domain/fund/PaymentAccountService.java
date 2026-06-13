package com.sjherp.domain.fund;

import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountRepository;

/**
 * 资金账户档案领域服务（所有资金账户写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则（照仓库档案 {@code WarehouseService}）：
 * <ul>
 *   <li>编码唯一：手填编码经仓储查重；不填则按 FA-年月-序号 自动编号
 *       （复用 M2-T01 的 {@link DocumentNumberGenerator}）；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（见 {@link PaymentAccount#enable}/{@link PaymentAccount#disable}）。</li>
 * </ul>
 *
 * <h2>唯一新逻辑：glAccountCode 校验（与 warehouse 唯一不同处）</h2>
 * create/update 时校验 {@code glAccountCode} 必须是 GL 科目表中**已存在、启用、末级**的科目
 * （现金侧凭证须挂在末级科目上，且停用科目不得引用）。资金账户在会计上应映射到货币资金类科目
 * （1001 库存现金 / 1002 银行存款 / 1012 其他货币资金）——此为**软约定**（不硬校验科目类别，
 * 避免与 GL 科目类别语义过度耦合），硬校验只到"末级 + 启用"。非法 glAccountCode → IllegalArgumentException（→400）。
 */
public class PaymentAccountService {

    /** 资金账户自动编号规则：FA-202606-0001（Fund Account） */
    static final DocumentNumberRule FA_RULE = DocumentNumberRule.of("FA");

    private final PaymentAccountRepository paymentAccountRepository;
    private final DocumentNumberGenerator numberGenerator;

    /** GL 科目仓储端口（M4-T01）：glAccountCode 必须是已存在/启用/末级科目 */
    private final AccountRepository accountRepository;

    public PaymentAccountService(PaymentAccountRepository paymentAccountRepository,
                                 DocumentNumberGenerator numberGenerator,
                                 AccountRepository accountRepository) {
        this.paymentAccountRepository = Objects.requireNonNull(paymentAccountRepository);
        this.numberGenerator = Objects.requireNonNull(numberGenerator);
        this.accountRepository = Objects.requireNonNull(accountRepository);
    }

    /** 创建资金账户：编码为空则自动编号；校验 glAccountCode 末级启用；落库后回填 id */
    @Audited(action = "payment_account.create", targetType = "payment_account")
    public PaymentAccount create(PaymentAccountCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");

        String glAccountCode = validateGlAccountCode(command.glAccountCode());

        String code = (command.code() == null || command.code().isBlank())
                ? numberGenerator.generate(FA_RULE)
                : command.code().strip();
        if (paymentAccountRepository.existsByCode(code)) {
            throw new IllegalArgumentException("资金账户编码已存在: " + code);
        }

        PaymentAccount account = new PaymentAccount(code, command.name(), command.accountType(),
                glAccountCode, command.bankName(), command.accountNo(), operator);
        paymentAccountRepository.save(account);
        return account;
    }

    /** 更新资金账户：编码可改但仍须唯一（更新时编码必填，不触发自动编号）；校验 glAccountCode */
    @Audited(action = "payment_account.update", targetType = "payment_account")
    public PaymentAccount update(long id, PaymentAccountCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        PaymentAccount account = get(id);

        String glAccountCode = validateGlAccountCode(command.glAccountCode());

        if (command.code() == null || command.code().isBlank()) {
            throw new IllegalArgumentException("更新资金账户时编码不能为空");
        }
        String code = command.code().strip();
        if (!code.equals(account.getCode()) && paymentAccountRepository.existsByCode(code)) {
            throw new IllegalArgumentException("资金账户编码已存在: " + code);
        }

        account.update(code, command.name(), command.accountType(), glAccountCode,
                command.bankName(), command.accountNo(), operator);
        paymentAccountRepository.save(account);
        return account;
    }

    /** 启用资金账户 */
    @Audited(action = "payment_account.enable", targetType = "payment_account")
    public PaymentAccount enable(long id, String operator) {
        PaymentAccount account = get(id);
        account.enable(operator);
        paymentAccountRepository.save(account);
        return account;
    }

    /** 停用资金账户（停用后新单据不得引用，历史数据不受影响） */
    @Audited(action = "payment_account.disable", targetType = "payment_account")
    public PaymentAccount disable(long id, String operator) {
        PaymentAccount account = get(id);
        account.disable(operator);
        paymentAccountRepository.save(account);
        return account;
    }

    /** 按 id 查询（不存在抛 PaymentAccountNotFoundException → API 404） */
    public PaymentAccount get(long id) {
        return paymentAccountRepository.findById(id)
                .orElseThrow(() -> PaymentAccountNotFoundException.account(id));
    }

    /** 分页查询（关键字模糊匹配编码/名称/开户行） */
    public PageResult<PaymentAccount> search(PaymentAccountQuery query) {
        return paymentAccountRepository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    /**
     * 校验 glAccountCode 为已存在、启用、末级的 GL 科目，返回规范化后的编码。
     *
     * <p>软约定：应为货币资金类科目（1001/1002/1012）；硬校验只到"末级 + 启用"，
     * 不耦合科目类别语义（CLAUDE.md：宁可拒绝过度耦合，不破坏数据模型）。
     */
    private String validateGlAccountCode(String glAccountCode) {
        if (glAccountCode == null || glAccountCode.isBlank()) {
            throw new IllegalArgumentException("映射的 GL 科目编码不能为空");
        }
        String code = glAccountCode.strip();
        Account account = accountRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("GL 科目不存在: " + code));
        if (!account.isEnabled()) {
            throw new IllegalArgumentException("GL 科目已停用，不能用于资金账户: " + code);
        }
        if (!account.isLeaf()) {
            throw new IllegalArgumentException("GL 科目不是末级科目，不能用于资金账户挂账: " + code);
        }
        return code;
    }
}
