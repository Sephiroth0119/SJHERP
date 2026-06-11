package com.sjherp.domain.warehouse;

import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 仓库档案领域服务（所有仓库写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>编码唯一：手填编码经仓储查重；不填则按 WH-年月-序号 自动编号
 *       （复用 M2-T01 的 {@link DocumentNumberGenerator}，序号供给为数据库实现，重启不重号）；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（见 {@link Warehouse#enable}/{@link Warehouse#disable}）。</li>
 * </ul>
 */
public class WarehouseService {

    /** 仓库自动编号规则：WH-202606-0001 */
    static final DocumentNumberRule WH_RULE = DocumentNumberRule.of("WH");

    private final WarehouseRepository warehouseRepository;
    private final DocumentNumberGenerator numberGenerator;

    public WarehouseService(WarehouseRepository warehouseRepository, DocumentNumberGenerator numberGenerator) {
        this.warehouseRepository = Objects.requireNonNull(warehouseRepository);
        this.numberGenerator = Objects.requireNonNull(numberGenerator);
    }

    /** 创建仓库：编码为空则自动编号；落库后回填 id */
    public Warehouse create(WarehouseCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");

        String code = (command.code() == null || command.code().isBlank())
                ? numberGenerator.generate(WH_RULE)
                : command.code().strip();
        if (warehouseRepository.existsByCode(code)) {
            throw new IllegalArgumentException("仓库编码已存在: " + code);
        }

        Warehouse warehouse = new Warehouse(code, command.name(), command.address(),
                command.manager(), command.locationEnabledOrDefault(), operator);
        warehouseRepository.save(warehouse);
        return warehouse;
    }

    /** 更新仓库：编码可改但仍须唯一（更新时编码必填，不触发自动编号） */
    public Warehouse update(long id, WarehouseCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        Warehouse warehouse = get(id);

        if (command.code() == null || command.code().isBlank()) {
            throw new IllegalArgumentException("更新仓库时编码不能为空");
        }
        String code = command.code().strip();
        if (!code.equals(warehouse.getCode()) && warehouseRepository.existsByCode(code)) {
            throw new IllegalArgumentException("仓库编码已存在: " + code);
        }

        warehouse.update(code, command.name(), command.address(), command.manager(),
                command.locationEnabledOrDefault(), operator);
        warehouseRepository.save(warehouse);
        return warehouse;
    }

    /** 启用仓库 */
    public Warehouse enable(long id, String operator) {
        Warehouse warehouse = get(id);
        warehouse.enable(operator);
        warehouseRepository.save(warehouse);
        return warehouse;
    }

    /**
     * 停用仓库。
     *
     * <p>TODO（M3 引入库存后补齐）：停用前的引用约束检查——存在非零库存
     * 余额或在途出入库单据时应给出阻断或警告策略；本期仅做状态切换，
     * 新单据不得引用停用仓库的约束由各单据领域服务校验。
     */
    public Warehouse disable(long id, String operator) {
        Warehouse warehouse = get(id);
        warehouse.disable(operator);
        warehouseRepository.save(warehouse);
        return warehouse;
    }

    /** 按 id 查询（不存在抛 WarehouseNotFoundException → API 404） */
    public Warehouse get(long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> WarehouseNotFoundException.warehouse(id));
    }

    /** 分页查询（关键字模糊匹配编码/名称/负责人） */
    public PageResult<Warehouse> search(WarehouseQuery query) {
        return warehouseRepository.search(Objects.requireNonNull(query, "query 不能为空"));
    }
}
