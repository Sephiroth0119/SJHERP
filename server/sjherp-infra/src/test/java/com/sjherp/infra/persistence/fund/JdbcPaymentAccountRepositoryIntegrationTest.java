package com.sjherp.infra.persistence.fund;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 资金账户仓储真实 MySQL 往返测试（M4-T04a）：insert → findById/findByCode → existsByCode → search → update。
 *
 * <p>注意：{@link JdbcPaymentAccountRepository} 本身不校验 glAccountCode（校验在 Service 层），
 * 故本切片测试可直接落任意 GL 科目编码；这里仍用 V19 预置的真实科目 {@code 1002} 以贴近实情。
 */
class JdbcPaymentAccountRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcPaymentAccountRepository repository = new JdbcPaymentAccountRepository(jdbc);

    @Test
    void 资金账户_保存后读回并可按编码搜索() {
        String code = "FA" + uniqueSuffix();
        PaymentAccount account = new PaymentAccount(code, "公司基本户", PaymentAccountType.BANK,
                "1002", "工商银行嘉定支行", "6222021234567890", "tester");

        repository.save(account);

        assertThat(account.getId()).isNotNull();

        Optional<PaymentAccount> byId = repository.findById(account.getId());
        assertThat(byId).isPresent();
        assertThat(byId.get().getCode()).isEqualTo(code);
        assertThat(byId.get().getName()).isEqualTo("公司基本户");
        assertThat(byId.get().getAccountType()).isEqualTo(PaymentAccountType.BANK);
        assertThat(byId.get().getGlAccountCode()).isEqualTo("1002");
        assertThat(byId.get().getBankName()).isEqualTo("工商银行嘉定支行");
        assertThat(byId.get().getAccountNo()).isEqualTo("6222021234567890");
        assertThat(byId.get().getStatus()).isEqualTo(ArchiveStatus.ENABLED);

        Optional<PaymentAccount> byCode = repository.findByCode(code);
        assertThat(byCode).isPresent();
        assertThat(byCode.get().getId()).isEqualTo(account.getId());

        assertThat(repository.existsByCode(code)).isTrue();
        assertThat(repository.existsByCode("FA-NOPE-" + uniqueSuffix())).isFalse();

        PageResult<PaymentAccount> page = repository.search(new PaymentAccountQuery(code, null, 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).getCode()).isEqualTo(code);
    }

    @Test
    void 资金账户_更新落库可重读_停用状态持久化() {
        String code = "FA" + uniqueSuffix();
        PaymentAccount account = new PaymentAccount(code, "一般户", PaymentAccountType.CASH,
                "1001", null, null, "tester");
        repository.save(account);
        long id = account.getId();

        // 改名 + 改类别 + 停用，再次 save 走 update 分支
        account.update(code, "现金账户", PaymentAccountType.OTHER, "1012", "招商银行", "888", "boss");
        account.disable("boss");
        repository.save(account);

        PaymentAccount reread = repository.findById(id).orElseThrow();
        assertThat(reread.getName()).isEqualTo("现金账户");
        assertThat(reread.getAccountType()).isEqualTo(PaymentAccountType.OTHER);
        assertThat(reread.getGlAccountCode()).isEqualTo("1012");
        assertThat(reread.getBankName()).isEqualTo("招商银行");
        assertThat(reread.getStatus()).isEqualTo(ArchiveStatus.DISABLED);
        assertThat(reread.getUpdatedBy()).isEqualTo("boss");
        // 创建审计字段不可变
        assertThat(reread.getCreatedBy()).isEqualTo("tester");
    }

    @Test
    void 资金账户_按状态过滤与关键字匹配开户行() {
        String suffix = uniqueSuffix();
        PaymentAccount enabled = new PaymentAccount("FA-EN-" + suffix, "基本户" + suffix,
                PaymentAccountType.BANK, "1002", "中国银行" + suffix, "111", "tester");
        PaymentAccount disabled = new PaymentAccount("FA-DI-" + suffix, "一般户" + suffix,
                PaymentAccountType.BANK, "1002", "中国银行" + suffix, "222", "tester");
        repository.save(enabled);
        repository.save(disabled);
        disabled.disable("tester");
        repository.save(disabled);

        // 关键字匹配开户行 → 命中两条
        PageResult<PaymentAccount> byBank = repository.search(
                new PaymentAccountQuery("中国银行" + suffix, null, 1, 20));
        assertThat(byBank.total()).isEqualTo(2);

        // 关键字 + 启用状态过滤 → 仅命中启用那条
        PageResult<PaymentAccount> onlyEnabled = repository.search(
                new PaymentAccountQuery("中国银行" + suffix, ArchiveStatus.ENABLED, 1, 20));
        assertThat(onlyEnabled.total()).isEqualTo(1);
        assertThat(onlyEnabled.items().get(0).getCode()).isEqualTo("FA-EN-" + suffix);
    }
}
