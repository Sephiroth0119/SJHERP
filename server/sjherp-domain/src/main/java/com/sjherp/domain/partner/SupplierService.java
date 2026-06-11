package com.sjherp.domain.partner;

import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 供应商档案领域服务（所有供应商写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>编码唯一：手填编码经仓储查重；不填则按 SUP-年月-序号 自动编号
 *       （复用 M2-T01 的 {@link DocumentNumberGenerator}，序号供给为数据库实现，重启不重号）；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（见 {@link Supplier#enable}/{@link Supplier#disable}）。</li>
 * </ul>
 */
public class SupplierService {

    /** 供应商自动编号规则：SUP-202606-0001 */
    static final DocumentNumberRule SUP_RULE = DocumentNumberRule.of("SUP");

    private final SupplierRepository supplierRepository;
    private final DocumentNumberGenerator numberGenerator;

    public SupplierService(SupplierRepository supplierRepository, DocumentNumberGenerator numberGenerator) {
        this.supplierRepository = Objects.requireNonNull(supplierRepository);
        this.numberGenerator = Objects.requireNonNull(numberGenerator);
    }

    /** 创建供应商：编码为空则自动编号；落库后回填 id */
    public Supplier create(SupplierCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");

        String code = (command.code() == null || command.code().isBlank())
                ? numberGenerator.generate(SUP_RULE)
                : command.code().strip();
        if (supplierRepository.existsByCode(code)) {
            throw new IllegalArgumentException("供应商编码已存在: " + code);
        }

        Supplier supplier = new Supplier(code, command.name(), command.contactPerson(),
                command.contactPhone(), command.address(), command.taxNo(),
                command.settlementMethod(), operator);
        supplierRepository.save(supplier);
        return supplier;
    }

    /** 更新供应商：编码可改但仍须唯一（更新时编码必填，不触发自动编号） */
    public Supplier update(long id, SupplierCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        Supplier supplier = get(id);

        if (command.code() == null || command.code().isBlank()) {
            throw new IllegalArgumentException("更新供应商时编码不能为空");
        }
        String code = command.code().strip();
        if (!code.equals(supplier.getCode()) && supplierRepository.existsByCode(code)) {
            throw new IllegalArgumentException("供应商编码已存在: " + code);
        }

        supplier.update(code, command.name(), command.contactPerson(), command.contactPhone(),
                command.address(), command.taxNo(), command.settlementMethod(), operator);
        supplierRepository.save(supplier);
        return supplier;
    }

    /** 启用供应商 */
    public Supplier enable(long id, String operator) {
        Supplier supplier = get(id);
        supplier.enable(operator);
        supplierRepository.save(supplier);
        return supplier;
    }

    /**
     * 停用供应商。
     *
     * <p>TODO（M3 引入单据后补齐）：停用前的引用约束检查——存在未完结单据
     * （在途采购订单、未核销应付等）时应给出阻断或警告策略；本期仅做状态
     * 切换，新单据不得引用停用供应商的约束由各单据领域服务校验。
     */
    public Supplier disable(long id, String operator) {
        Supplier supplier = get(id);
        supplier.disable(operator);
        supplierRepository.save(supplier);
        return supplier;
    }

    /** 按 id 查询（不存在抛 PartnerNotFoundException → API 404） */
    public Supplier get(long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> PartnerNotFoundException.supplier(id));
    }

    /** 分页查询（关键字模糊匹配编码/名称/联系人/电话） */
    public PageResult<Supplier> search(SupplierQuery query) {
        return supplierRepository.search(Objects.requireNonNull(query, "query 不能为空"));
    }
}
