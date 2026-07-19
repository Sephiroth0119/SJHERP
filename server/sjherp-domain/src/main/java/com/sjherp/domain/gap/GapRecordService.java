package com.sjherp.domain.gap;

import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 流程缺口领域服务（所有缺口写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>创建：必填校验（标题/场景/期望/缺失能力/模块/严重度/提出人）由
 *       {@link GapRecord} 构造执行；缺口编号 GAP-年月-序号 自动生成
 *       （复用 M2-T01 的 {@link DocumentNumberGenerator}，重启不重号）；</li>
 *   <li>状态流转：合法性按 {@link GapStatus} 流转表检查，非法流转拒绝；</li>
 *   <li>查询：按状态/模块分页（开发侧 triage 与 M6-T08 Issue 化的数据源）。</li>
 * </ul>
 */
public class GapRecordService {

    /** 缺口编号规则：GAP-202606-0001 */
    static final DocumentNumberRule GAP_RULE = DocumentNumberRule.of("GAP");

    private final GapRecordRepository repository;
    private final DocumentNumberGenerator numberGenerator;

    public GapRecordService(GapRecordRepository repository, DocumentNumberGenerator numberGenerator) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /** 创建缺口记录：自动编号 GAP-年月-序号，初始状态 NEW，落库后回填 id */
    @Audited(action = "gap.create", targetType = "gap")
    public GapRecord create(GapRecordCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        String gapNo = numberGenerator.generate(GAP_RULE);
        GapRecord record = new GapRecord(gapNo, command.sessionId(), command.title(),
                command.scenario(), command.expectedBehavior(), command.missingCapability(),
                command.businessModule(), command.severity(), command.reporter(), operator);
        repository.save(record);
        return record;
    }

    /** 状态流转（开发侧 triage / 进入开发 / 解决 / 驳回；非法流转抛 IllegalArgumentException） */
    @Audited(action = "gap.change_status", targetType = "gap")
    public GapRecord transitionStatus(long id, GapStatus target, String operator) {
        GapRecord record = get(id);
        record.transitionTo(target, operator);
        repository.save(record);
        return record;
    }

    /** 按 id 查询（不存在抛 GapRecordNotFoundException → API 404） */
    public GapRecord get(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new GapRecordNotFoundException(id));
    }
    @Audited(action = "gap.change_status", targetType = "gap")
    public GapRecord transitionStatusByGapNo(String gapNo, GapStatus target, String operator) {
        GapRecord record = repository.findByGapNo(gapNo)
                .orElseThrow(() -> new GapRecordNotFoundException(gapNo));
        record.transitionTo(target, operator);
        repository.save(record);
        return record;
    }

    /** 分页查询（按状态/模块过滤） */
    public PageResult<GapRecord> search(GapRecordQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }
}
