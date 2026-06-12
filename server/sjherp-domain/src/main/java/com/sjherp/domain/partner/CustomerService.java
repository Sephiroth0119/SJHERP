package com.sjherp.domain.partner;

import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 客户档案领域服务（所有客户写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>编码唯一：手填编码经仓储查重；不填则按 CUS-年月-序号 自动编号
 *       （复用 M2-T01 的 {@link DocumentNumberGenerator}，序号供给为数据库实现，重启不重号）；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（见 {@link Customer#enable}/{@link Customer#disable}）。</li>
 * </ul>
 */
public class CustomerService {

    /** 客户自动编号规则：CUS-202606-0001 */
    static final DocumentNumberRule CUS_RULE = DocumentNumberRule.of("CUS");

    private final CustomerRepository customerRepository;
    private final DocumentNumberGenerator numberGenerator;

    public CustomerService(CustomerRepository customerRepository, DocumentNumberGenerator numberGenerator) {
        this.customerRepository = Objects.requireNonNull(customerRepository);
        this.numberGenerator = Objects.requireNonNull(numberGenerator);
    }

    /** 创建客户：编码为空则自动编号；落库后回填 id */
    @Audited(action = "customer.create", targetType = "customer")
    public Customer create(CustomerCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");

        String code = (command.code() == null || command.code().isBlank())
                ? numberGenerator.generate(CUS_RULE)
                : command.code().strip();
        if (customerRepository.existsByCode(code)) {
            throw new IllegalArgumentException("客户编码已存在: " + code);
        }

        Customer customer = new Customer(code, command.name(), command.contactPerson(),
                command.contactPhone(), command.address(), command.taxNo(),
                command.settlementMethod(), command.creditLimit(), operator);
        customerRepository.save(customer);
        return customer;
    }

    /** 更新客户：编码可改但仍须唯一（更新时编码必填，不触发自动编号） */
    @Audited(action = "customer.update", targetType = "customer")
    public Customer update(long id, CustomerCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        Customer customer = get(id);

        if (command.code() == null || command.code().isBlank()) {
            throw new IllegalArgumentException("更新客户时编码不能为空");
        }
        String code = command.code().strip();
        if (!code.equals(customer.getCode()) && customerRepository.existsByCode(code)) {
            throw new IllegalArgumentException("客户编码已存在: " + code);
        }

        customer.update(code, command.name(), command.contactPerson(), command.contactPhone(),
                command.address(), command.taxNo(), command.settlementMethod(),
                command.creditLimit(), operator);
        customerRepository.save(customer);
        return customer;
    }

    /** 启用客户 */
    @Audited(action = "customer.enable", targetType = "customer")
    public Customer enable(long id, String operator) {
        Customer customer = get(id);
        customer.enable(operator);
        customerRepository.save(customer);
        return customer;
    }

    /**
     * 停用客户。
     *
     * <p>TODO（M3 引入单据后补齐）：停用前的引用约束检查——存在未完结单据
     * （在途销售订单、未核销应收等）时应给出阻断或警告策略；本期仅做状态
     * 切换，新单据不得引用停用客户的约束由各单据领域服务校验。
     */
    @Audited(action = "customer.disable", targetType = "customer")
    public Customer disable(long id, String operator) {
        Customer customer = get(id);
        customer.disable(operator);
        customerRepository.save(customer);
        return customer;
    }

    /** 按 id 查询（不存在抛 PartnerNotFoundException → API 404） */
    public Customer get(long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> PartnerNotFoundException.customer(id));
    }

    /** 分页查询（关键字模糊匹配编码/名称/联系人/电话） */
    public PageResult<Customer> search(CustomerQuery query) {
        return customerRepository.search(Objects.requireNonNull(query, "query 不能为空"));
    }
}
