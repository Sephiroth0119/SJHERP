# M6-T05 Check Agent Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不重写现有 17 条一致性规则的前提下，交付可注册、可调度、报告可追溯并带独立站内通知的检查 Agent 框架。

**Architecture:** 现有 `ConsistencyCheckService` 由应用层适配为首个 SQL 断言规则，统一运行器按规则类型和顺序执行，并把运行聚合经领域仓储端口持久化。站内通知与报告在同一短写事务生成，LLM 分析规则只有契约且默认无生产实现，失败时保留确定性结果。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring JDBC、Flyway、MySQL 8.4、JUnit 5、Mockito、AssertJ、React 18、TypeScript、Vite。

## Global Constraints

- 必须使用 Java 21；本机 `JAVA_HOME` 为 `C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10`。
- 保留 `ConsistencyCheckService`、`ConsistencyCheckDao` 与现有 17 条规则计算，不在 T05 拆写规则公式。
- 关账继续直接调用纯 `ConsistencyCheckService.check()`，不得产生运行报告或通知。
- SQL 断言规则先于 LLM 分析规则；T05 默认无生产 LLM 规则、无模型调用，LLM 失败必须 fail-open。
- 每次 `SCHEDULED`、`MANUAL_API`、`AGENT` 显式运行都保存摘要，仅存在差异时保存明细。
- 干净运行不通知；差异或运行失败只通知启用中的 `ADMIN/BOSS`。
- 金额和数量在 Java 使用 `BigDecimal`，数据库使用 `DECIMAL(24,6)`，JSON 继续使用字符串承载。
- 所有报告、差异与通知禁止物理删除；不提供 `DELETE` API、删除仓储方法或删除 SQL。
- 不实现 T06 新规则、T07 报表对话召回或主动会话推送。
- 保留工作树中用户已有的 `README.md`、`docs/业务-进销存报表.md`、`docs/工程-自驱开发循环.md`、`docs/领域模型概览.md` 改动，不暂存、不覆盖。

---

## File map

### Domain model and ports

- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyFinding.java`: 持久化差异值对象。
- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyCheckRun.java`: 运行聚合、状态、计数与审计摘要。
- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyRunQuery.java`: 报告分页参数。
- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyCheckRunRepository.java`: 运行仓储端口。
- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/notification/SystemNotification.java`: 站内通知聚合及幂等已读行为。
- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/notification/SystemNotificationQuery.java`: 个人通知分页参数。
- Create `server/sjherp-domain/src/main/java/com/sjherp/domain/notification/SystemNotificationRepository.java`: 通知仓储端口。

### Infrastructure

- Create `server/sjherp-infra/src/main/resources/db/migration/V33__consistency_check_framework.sql`: 三张新表、索引、唯一键与外键。
- Create `server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/consistency/JdbcConsistencyCheckRunRepository.java`: 运行与差异原子写入、分页与详情查询。
- Create `server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/notification/JdbcSystemNotificationRepository.java`: 通知写入、个人分页、未读计数和本人详情查询。

### Application framework

- Create `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRule.java`: 规则契约、类型、上下文与结果。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRuleRegistry.java`: 唯一性校验、稳定排序和按类型查询。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/consistency/CoreSqlAssertionRule.java`: 现有 17 条规则的零重写适配器。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRunPersistenceService.java`: 独立短事务保存报告并生成站内通知。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyCheckRunner.java`: 三来源统一执行、失败摘要和 LLM fail-open。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyReportService.java`: 管理报告只读查询服务。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationChannel.java`: 可扩展通知通道契约。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/notification/InAppNotificationChannel.java`: ADMIN/BOSS 站内投递与幂等。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationService.java`: 本人通知查询和已读应用服务。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationController.java`: 本人通知 REST API。
- Create `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationExceptionHandler.java`: 404/400 安全错误映射。
- Modify `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyController.java`: 保留预览并增加运行与报告接口。
- Modify `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyScheduledChecker.java`: 调用统一运行器。
- Modify `server/sjherp-app/src/main/java/com/sjherp/app/tool/consistency/RunConsistencyCheckTool.java`: 调用 AGENT 来源运行器并返回运行编号。
- Modify `server/sjherp-app/src/main/java/com/sjherp/app/config/DomainToolConfig.java`: 注入新运行器。

### Frontend and documentation

- Create `web/src/api/notificationApi.ts`: 通知 DTO 与 API。
- Create `web/src/components/NotificationBell.tsx`: 铃铛、未读数、列表与标记已读。
- Modify `web/src/App.tsx`: 顶栏挂载通知入口。
- Modify `web/src/styles/global.css`: 小型通知浮层样式和窄屏适配。
- Modify `docs/业务-数据一致性校验.md`, `docs/权限矩阵.md`, `docs/产品路线图-0到1.md`, `CLAUDE.md`: 交付边界和测试基线。

---

### Task 1: V33 migration and domain aggregates

**Files:**
- Create: `server/sjherp-infra/src/main/resources/db/migration/V33__consistency_check_framework.sql`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyFinding.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyCheckRun.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyRunQuery.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/consistency/ConsistencyCheckRunRepository.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/notification/SystemNotification.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/notification/SystemNotificationQuery.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/notification/SystemNotificationRepository.java`
- Test: `server/sjherp-domain/src/test/java/com/sjherp/domain/consistency/ConsistencyCheckRunTest.java`
- Test: `server/sjherp-domain/src/test/java/com/sjherp/domain/notification/SystemNotificationTest.java`

**Interfaces:**
- Produces: `ConsistencyCheckRun.completed`, `ConsistencyCheckRun.failed`, `ConsistencyCheckRun.restore`, `assignId(long)`, `findings()`, `ConsistencyCheckRunRepository.save/search/findByRunNo`。
- Produces: `SystemNotification.create`, `restore`, `markRead(Instant)`, `SystemNotificationRepository.save/searchForRecipient/countUnread/findByIdAndRecipient/existsBySource`。

- [ ] **Step 1: Write failing aggregate tests**

```java
@Test
void completedRunCopiesFindingsAndDerivesCounts() {
    ConsistencyFinding finding = new ConsistencyFinding(1, "CORE_SQL_ASSERTIONS",
            "LEDGER_COST", "warehouse=1,product=2", new BigDecimal("10.000000"),
            new BigDecimal("9.000000"), ConsistencyFinding.Severity.ERROR, "库存金额不平");
    ConsistencyCheckRun run = ConsistencyCheckRun.completed(0, "CHK-202607-0001",
            ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", INSTANT, INSTANT,
            ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of(finding));

    assertThat(run.clean()).isFalse();
    assertThat(run.errorCount()).isEqualTo(1);
    assertThat(run.findings()).containsExactly(finding);
    assertThatThrownBy(() -> run.findings().add(finding))
            .isInstanceOf(UnsupportedOperationException.class);
}

@Test
void markReadIsIdempotentAndCannotChangeRecipient() {
    SystemNotification notification = SystemNotification.create(0, 7,
            SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
            "一致性检查异常", "运行 CHK-202607-0001 发现 1 项错误",
            SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-202607-0001", INSTANT);
    notification.markRead(INSTANT.plusSeconds(1));
    notification.markRead(INSTANT.plusSeconds(2));
    assertThat(notification.readAt()).isEqualTo(INSTANT.plusSeconds(1));
    assertThat(notification.recipientUserId()).isEqualTo(7);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl sjherp-domain '-Dtest=ConsistencyCheckRunTest,SystemNotificationTest' test
```

Expected: compilation fails because the new domain types do not exist.

- [ ] **Step 3: Implement immutable aggregates and repository ports**

Use these exact public contracts:

```java
public record ConsistencyFinding(int sequenceNo, String ruleCode, String checkType,
        String objectKey, BigDecimal expectedValue, BigDecimal actualValue,
        Severity severity, String message) {
    public enum Severity { ERROR, WARN, INFO }
}

public interface ConsistencyCheckRunRepository {
    void save(ConsistencyCheckRun run);
    Optional<ConsistencyCheckRun> findByRunNo(long tenantId, String runNo);
    PageResult<ConsistencyCheckRun> search(long tenantId, ConsistencyRunQuery query);
}

public record ConsistencyRunQuery(int page, int size) {
    public ConsistencyRunQuery {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数不合法");
    }
}

public interface SystemNotificationRepository {
    void save(SystemNotification notification);
    PageResult<SystemNotification> searchForRecipient(long tenantId, long recipientUserId,
                                                       SystemNotificationQuery query);
    long countUnread(long tenantId, long recipientUserId);
    Optional<SystemNotification> findByIdAndRecipient(long tenantId, long id, long recipientUserId);
    boolean existsBySource(long tenantId, long recipientUserId,
                           SystemNotification.SourceType sourceType, String sourceRef);
}
```

`ConsistencyCheckRun` implements `AuditTarget`; `auditTargetCode()` returns `runNo`, and `auditSummary()` contains only trigger/status/counts. Validate identifiers, maximum lengths, nonnegative counts, exactly-once `assignId`, and defensive copies. `failed` stores `failureType` only and has no findings. `SystemNotification` also implements `AuditTarget`, validates title/content/source lengths, and exposes no delete transition.

- [ ] **Step 4: Add V33 schema**

```sql
CREATE TABLE consistency_check_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    run_no VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    clean BOOLEAN NOT NULL,
    total_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    warn_count BIGINT NOT NULL,
    info_count BIGINT NOT NULL,
    analysis_status VARCHAR(16) NOT NULL,
    analysis_summary VARCHAR(1000) NULL,
    failure_type VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consistency_check_run_no (tenant_id, run_no),
    KEY idx_consistency_check_run_time (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE consistency_check_break (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    check_type VARCHAR(64) NOT NULL,
    object_key VARCHAR(256) NULL,
    expected_value DECIMAL(24,6) NULL,
    actual_value DECIMAL(24,6) NULL,
    severity VARCHAR(16) NOT NULL,
    message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consistency_check_break_sequence (tenant_id, run_id, sequence_no),
    CONSTRAINT fk_consistency_check_break_run FOREIGN KEY (run_id)
        REFERENCES consistency_check_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    recipient_user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(128) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_notification_source
        (tenant_id, recipient_user_id, source_type, source_ref),
    KEY idx_system_notification_inbox (tenant_id, recipient_user_id, read_at, id),
    CONSTRAINT fk_system_notification_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

The full columns and indexes must match design §5. Do not add cascade delete clauses.

- [ ] **Step 5: Run domain tests and migration static checks**

Run the Task 1 Maven command again, then:

```powershell
rg -n "DELETE|ON DELETE CASCADE|FLOAT|DOUBLE" server/sjherp-infra/src/main/resources/db/migration/V33__consistency_check_framework.sql
```

Expected: tests pass; the scan returns no matches.

- [ ] **Step 6: Commit Task 1**

```powershell
git add -- server/sjherp-domain/src/main/java/com/sjherp/domain/consistency server/sjherp-domain/src/main/java/com/sjherp/domain/notification server/sjherp-domain/src/test/java/com/sjherp/domain/consistency server/sjherp-domain/src/test/java/com/sjherp/domain/notification server/sjherp-infra/src/main/resources/db/migration/V33__consistency_check_framework.sql
git commit -m "feat: 建立检查报告与站内通知模型"
```

### Task 2: JDBC repositories and MySQL round trips

**Files:**
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/consistency/JdbcConsistencyCheckRunRepository.java`
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/notification/JdbcSystemNotificationRepository.java`
- Create: `server/sjherp-infra/src/test/java/com/sjherp/infra/persistence/consistency/JdbcConsistencyCheckRunRepositoryIntegrationTest.java`
- Create: `server/sjherp-infra/src/test/java/com/sjherp/infra/persistence/notification/JdbcSystemNotificationRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 repository ports and aggregate restore factories.
- Produces: Spring `@Repository` adapters discovered by the app component scan.

- [ ] **Step 1: Write failing Testcontainers repository tests**

```java
@Tag("integration-db")
@Test
void savesRunWithDecimalFindingsAndReadsPageAndDetail() {
    repository.save(runWithFinding("CHK-202607-0001", new BigDecimal("10.123456")));
    assertThat(repository.findByRunNo(0, "CHK-202607-0001")).get()
            .extracting(ConsistencyCheckRun::errorCount).isEqualTo(1L);
    assertThat(repository.search(0, new ConsistencyRunQuery(1, 20)).items()).hasSize(1);
}

@Tag("integration-db")
@Test
void isolatesRecipientAndPersistsIdempotentReadTimestamp() {
    repository.save(notificationFor(adminId));
    assertThat(repository.countUnread(0, adminId)).isEqualTo(1);
    assertThat(repository.searchForRecipient(0, otherId,
            new SystemNotificationQuery(1, 20)).items()).isEmpty();
}
```

Use the existing MySQL 8.4 container/Flyway fixture style from `JdbcUserRepositoryIntegrationTest`; insert required `sys_user` recipients through SQL and clean only test rows in setup.

- [ ] **Step 2: Run integration tests and verify RED when Docker is available**

```powershell
$env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl sjherp-infra '-Dgroups=integration-db' '-DexcludedGroups=none' '-Dtest=JdbcConsistencyCheckRunRepositoryIntegrationTest,JdbcSystemNotificationRepositoryIntegrationTest' test
```

Expected locally: Docker-unavailable infrastructure failure is acceptable and must be recorded; in CI the initial implementation attempt must fail until adapters exist.

- [ ] **Step 3: Implement JDBC run repository**

Use `GeneratedKeyHolder` to insert `consistency_check_run`, call `run.assignId(id)`, then batch insert findings in sequence order. `findByRunNo` loads one head plus ordered findings; `search` loads only heads with `ORDER BY id DESC LIMIT ? OFFSET ?`. Every SQL predicate includes `tenant_id`.

```java
@Repository
public final class JdbcConsistencyCheckRunRepository implements ConsistencyCheckRunRepository {
    @Override
    public void save(ConsistencyCheckRun run) {
        if (run.id() != null) throw new IllegalArgumentException("运行报告不可重复保存");
        long id = insertHead(run);
        run.assignId(id);
        if (!run.findings().isEmpty()) insertFindings(run);
    }

    @Override
    public Optional<ConsistencyCheckRun> findByRunNo(long tenantId, String runNo) {
        return findHead(tenantId, runNo).map(head -> ConsistencyCheckRun.restore(
                head.id(), head.tenantId(), head.runNo(), head.triggerType(), head.requestedBy(),
                head.startedAt(), head.completedAt(), head.status(), head.clean(), head.totalCount(),
                head.errorCount(), head.warnCount(), head.infoCount(), head.analysisStatus(),
                head.analysisSummary(), head.failureType(), head.createdAt(),
                findFindings(tenantId, head.id())));
    }

    @Override
    public PageResult<ConsistencyCheckRun> search(long tenantId, ConsistencyRunQuery query) {
        long total = countHeads(tenantId);
        List<ConsistencyCheckRun> heads = findHeads(tenantId, query.size(), (query.page() - 1) * query.size());
        return new PageResult<>(heads, total, query.page(), query.size());
    }
}
```

Map `DECIMAL` using `ResultSet.getBigDecimal`; never convert through `double`.

- [ ] **Step 4: Implement JDBC notification repository**

```java
@Repository
public final class JdbcSystemNotificationRepository implements SystemNotificationRepository {
    @Override
    public void save(SystemNotification notification) {
        if (notification.id() == null) {
            notification.assignId(insert(notification));
        } else {
            updateReadAt(notification.tenantId(), notification.id(), notification.recipientUserId(),
                    notification.readAt());
        }
    }

    @Override
    public PageResult<SystemNotification> searchForRecipient(long tenantId, long userId,
            SystemNotificationQuery query) {
        return new PageResult<>(findInbox(tenantId, userId, query), countInbox(tenantId, userId),
                query.page(), query.size());
    }

    @Override public long countUnread(long tenantId, long userId) { return queryUnread(tenantId, userId); }
    @Override public Optional<SystemNotification> findByIdAndRecipient(long tenantId, long id, long userId) {
        return findOwned(tenantId, id, userId);
    }
    @Override public boolean existsBySource(long tenantId, long userId,
            SystemNotification.SourceType type, String ref) {
        return countBySource(tenantId, userId, type, ref) > 0;
    }
}
```

An assigned notification ID updates only `read_at`; recipient, source, content and severity are immutable.

- [ ] **Step 5: Run infra unit suite and CI-gated integration tests**

```powershell
mvn -pl sjherp-infra test
```

Expected: all non-container infra tests pass. The integration command is rerun in GitHub CI against MySQL 8.4.

- [ ] **Step 6: Commit Task 2**

```powershell
git add -- server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/consistency server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/notification server/sjherp-infra/src/test/java/com/sjherp/infra/persistence/consistency server/sjherp-infra/src/test/java/com/sjherp/infra/persistence/notification
git commit -m "feat: 持久化检查报告与站内通知"
```

### Task 3: Registrable deterministic and LLM rule contracts

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRule.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRuleRegistry.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/CoreSqlAssertionRule.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyRuleRegistryTest.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/CoreSqlAssertionRuleTest.java`

**Interfaces:**
- Produces: `ConsistencyRule.Kind`, `Context`, `Result`, `ConsistencyRuleRegistry.sqlRules()`, `llmRules()`.
- Consumes: existing `ConsistencyCheckService` without modifying its rule methods.

- [ ] **Step 1: Write registry and adapter tests**

```java
@Test
void ordersByOrderThenCodeAndSeparatesKinds() {
    ConsistencyRuleRegistry registry = new ConsistencyRuleRegistry(List.of(
            fake("LLM_B", 20, Kind.LLM_ANALYSIS), fake("SQL_B", 10, Kind.SQL_ASSERTION),
            fake("SQL_A", 10, Kind.SQL_ASSERTION)));
    assertThat(registry.sqlRules()).extracting(ConsistencyRule::code)
            .containsExactly("SQL_A", "SQL_B");
    assertThat(registry.llmRules()).extracting(ConsistencyRule::code).containsExactly("LLM_B");
}

@Test
void rejectsDuplicateRuleCodes() {
    assertThatThrownBy(() -> new ConsistencyRuleRegistry(List.of(
            fake("DUP", 1, Kind.SQL_ASSERTION), fake("DUP", 2, Kind.LLM_ANALYSIS))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("DUP");
}

@Test
void coreAdapterReturnsExistingReportWithoutRecomputingRules() {
    when(service.check()).thenReturn(new ConsistencyReport(INSTANT, List.of(BREAK)));
    assertThat(new CoreSqlAssertionRule(service).evaluate(CONTEXT).breaks()).containsExactly(BREAK);
    verify(service).check();
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl sjherp-app -am '-Dtest=ConsistencyRuleRegistryTest,CoreSqlAssertionRuleTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation fails because the contracts do not exist.

- [ ] **Step 3: Implement the compact rule contract**

```java
public interface ConsistencyRule {
    enum Kind { SQL_ASSERTION, LLM_ANALYSIS }
    record Context(long tenantId, String runNo, ConsistencyCheckRun.TriggerType triggerType,
                   String requestedBy) {}
    record Result(List<ConsistencyBreak> breaks, String analysisSummary) {
        public Result { breaks = breaks == null ? List.of() : List.copyOf(breaks); }
        public static Result deterministic(List<ConsistencyBreak> breaks) { return new Result(breaks, null); }
        public static Result analysis(String summary) { return new Result(List.of(), summary); }
    }
    String code();
    int order();
    Kind kind();
    Result evaluate(Context context);
}
```

`ConsistencyRuleRegistry` takes `List<ConsistencyRule>`, rejects blank/duplicate codes, stores an immutable stable sort by `order` then `code`, and returns immutable type-filtered lists. `CoreSqlAssertionRule` is a `@Component`, code `CORE_SQL_ASSERTIONS`, order `100`, kind `SQL_ASSERTION`, and only delegates to `ConsistencyCheckService.check()`.

- [ ] **Step 4: Run focused tests plus existing consistency engine tests**

```powershell
mvn -pl sjherp-app -am '-Dtest=ConsistencyRuleRegistryTest,CoreSqlAssertionRuleTest,ConsistencyCheckServiceTest,ConsistencyCheckDaoTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: all selected tests pass; existing 17-rule tests are unchanged.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRule.java server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRuleRegistry.java server/sjherp-app/src/main/java/com/sjherp/app/consistency/CoreSqlAssertionRule.java server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyRuleRegistryTest.java server/sjherp-app/src/test/java/com/sjherp/app/consistency/CoreSqlAssertionRuleTest.java
git commit -m "feat: 建立一致性规则注册表"
```

### Task 4: Unified runner, persistence transaction, and in-app notification

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyRunPersistenceService.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyCheckRunner.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyReportService.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationChannel.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/notification/InAppNotificationChannel.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationService.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyCheckRunnerTest.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyRunPersistenceServiceTest.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/notification/InAppNotificationChannelTest.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/notification/NotificationServiceTest.java`

**Interfaces:**
- Produces: `runManual(operator)`, `runAgent(userId)`, `runScheduled()`, report search/detail, own notification read methods.
- Consumes: Task 1 ports, Task 3 registry, existing `DocumentNumberGenerator`, `UserRepository`.

- [ ] **Step 1: Write runner tests for success, LLM fail-open, and deterministic failure**

```java
@Test
void persistsEverySuccessfulRunAndSkipsLlmWhenNoneRegistered() {
    when(numberGenerator.generate(DocumentNumberRule.of("CHK"))).thenReturn("CHK-202607-0001");
    ConsistencyCheckRun run = runner.runManual("admin");
    assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.SKIPPED);
    verify(persistence).persist(run);
}

@Test
void llmFailureDoesNotDiscardDeterministicFindings() {
    registry = registry(sqlRuleReturning(BREAK), llmRuleThrowing(new IllegalStateException("secret")));
    ConsistencyCheckRun run = runnerWith(registry).runAgent("7");
    assertThat(run.findings()).hasSize(1);
    assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(run.failureType()).isNull();
}

@Test
void deterministicFailurePersistsSafeFailedRunThenRethrows() {
    registry = registry(sqlRuleThrowing(new IllegalStateException("database password")));
    assertThatThrownBy(() -> runnerWith(registry).runScheduled())
            .isInstanceOf(IllegalStateException.class);
    ArgumentCaptor<ConsistencyCheckRun> saved = ArgumentCaptor.forClass(ConsistencyCheckRun.class);
    verify(persistence).persist(saved.capture());
    assertThat(saved.getValue().failureType()).isEqualTo("IllegalStateException");
    assertThat(saved.getValue().auditSummary()).doesNotContain("database password");
}
```

- [ ] **Step 2: Write notification tests**

```java
@Test
void sendsOnlyToEnabledAdminsAndBossesAndIsIdempotent() {
    when(users.findAll()).thenReturn(List.of(enabledAdmin(1), enabledBoss(2),
            enabledSales(3), disabledAdmin(4)));
    when(notifications.existsBySource(anyLong(), anyLong(), any(), anyString()))
            .thenReturn(false, false, true, true);
    channel.send(nonCleanRun());
    channel.send(nonCleanRun());
    verify(notifications, times(2)).save(any(SystemNotification.class));
}

@Test
void cleanRunCreatesNoNotification() {
    channel.send(cleanRun());
    verifyNoInteractions(users, notifications);
}
```

- [ ] **Step 3: Run tests and verify RED**

```powershell
mvn -pl sjherp-app -am '-Dtest=ConsistencyCheckRunnerTest,ConsistencyRunPersistenceServiceTest,InAppNotificationChannelTest,NotificationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation fails because orchestration services are absent.

- [ ] **Step 4: Implement runner and safe mapping**

```java
@Service
public class ConsistencyCheckRunner {
    @Audited(action = "consistency.run", targetType = "consistency_report")
    public ConsistencyCheckRun runManual(String operator) { return run(TriggerType.MANUAL_API, operator); }
    public ConsistencyCheckRun runAgent(String userId) { return run(TriggerType.AGENT, "agent:" + userId); }
    public ConsistencyCheckRun runScheduled() { return run(TriggerType.SCHEDULED, "system:consistency-scheduler"); }
}
```

The private run method generates `CHK` number, executes all SQL rules first, maps each `ConsistencyBreak.expected/actual` using `new BigDecimal(value)` when non-null, then executes LLM rules. Any LLM runtime exception sets analysis status `FAILED` without logging message or changing findings. Any SQL exception builds and persists a failed run with `e.getClass().getSimpleName()` only, then rethrows.

- [ ] **Step 5: Implement transactional persistence and notification services**

```java
@Service
public class ConsistencyRunPersistenceService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(ConsistencyCheckRun run) {
        repository.save(run);
        inAppNotificationChannel.send(run);
    }
}

public interface NotificationChannel {
    void send(ConsistencyCheckRun run);
}
```

`InAppNotificationChannel` returns immediately for completed clean runs, selects enabled users containing `Role.ADMIN` or `Role.BOSS`, checks `existsBySource`, and saves one safe summary notification per recipient. `NotificationService.markRead(userId,id)` loads by tenant+recipient, throws a dedicated not-found exception when absent, calls the aggregate `markRead`, then saves. Query services validate page 1..N and size 1..100.

- [ ] **Step 6: Run focused tests**

Run the Task 4 Maven command again.

Expected: all orchestration, fail-open, recipient and ownership tests pass.

- [ ] **Step 7: Commit Task 4**

```powershell
git add -- server/sjherp-app/src/main/java/com/sjherp/app/consistency server/sjherp-app/src/main/java/com/sjherp/app/notification server/sjherp-app/src/test/java/com/sjherp/app/consistency server/sjherp-app/src/test/java/com/sjherp/app/notification
git commit -m "feat: 编排检查运行与站内通知"
```

### Task 5: REST, scheduler, Agent tool, permissions, and audit

**Files:**
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyController.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/consistency/ConsistencyScheduledChecker.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/tool/consistency/RunConsistencyCheckTool.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/config/DomainToolConfig.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationController.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/notification/NotificationExceptionHandler.java`
- Modify/Test: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyControllerPermissionTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyControllerTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/consistency/ConsistencyScheduledCheckerTest.java`
- Modify: `server/sjherp-app/src/test/java/com/sjherp/app/tool/consistency/RunConsistencyCheckToolTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/notification/NotificationControllerPermissionTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/notification/NotificationControllerTest.java`
- Modify: `server/sjherp-app/src/test/java/com/sjherp/app/gl/PeriodCloseServiceTest.java`
- Modify: `server/sjherp-app/src/test/java/com/sjherp/app/audit/AuditWriteCoverageTest.java`

**Interfaces:**
- Produces: `POST /api/consistency/runs`, `GET /api/consistency/reports`, `GET /api/consistency/reports/{runNo}`.
- Produces: `GET /api/notifications`, `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`.

- [ ] **Step 1: Write failing API and permission tests**

```java
mockMvc.perform(post("/api/consistency/runs").with(adminJwt()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.runNo").value("CHK-202607-0001"));
mockMvc.perform(get("/api/consistency/reports").with(salesJwt()))
        .andExpect(status().isForbidden());
mockMvc.perform(get("/api/notifications").with(salesJwt()))
        .andExpect(status().isOk());
mockMvc.perform(post("/api/notifications/99/read").with(userJwt(7)))
        .andExpect(status().isNotFound());
```

Also assert unauthenticated requests are 401 and `BOSS` has report access.

- [ ] **Step 2: Write failing scheduler, tool, close-regression, and audit tests**

```java
scheduledChecker.runScheduledCheck();
verify(runner).runScheduled();

ToolResult result = tool.execute(Map.of(), new ToolContext("s1", "7", "核一下账"));
verify(runner).runAgent("7");
assertThat(result.data()).containsEntry("runNo", "CHK-202607-0001");

periodCloseService.precheck(PERIOD);
verify(consistencyCheckService).check();
```

Audit coverage must assert manual API run writes action `consistency.run` with report number and no finding message.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
mvn -pl sjherp-app -am '-Dtest=ConsistencyControllerTest,ConsistencyControllerPermissionTest,ConsistencyScheduledCheckerTest,RunConsistencyCheckToolTest,NotificationControllerTest,NotificationControllerPermissionTest,PeriodCloseServiceTest,AuditWriteCoverageTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: endpoint, constructor and behavior assertions fail before entry-point changes.

- [ ] **Step 4: Implement REST DTOs and ownership checks**

Keep `GET /api/consistency/check` unchanged and non-persisting. Add POST and report GET methods with `@PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")`; map all `BigDecimal` fields with `toPlainString()`. Notification endpoints require only authentication and derive recipient with `Long.parseLong(CurrentUser.userId())`; never accept a recipient request parameter.

```java
@PostMapping("/runs")
@PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
public RunResponse run() {
    return RunResponse.from(runner.runManual(CurrentUser.operator()));
}
```

- [ ] **Step 5: Switch scheduler and Agent tool to the runner**

The scheduler keeps its current conditional property and cron annotation, logs run number plus counts, and never logs full finding messages. The tool keeps `requiredPermission()==null`, invokes `runner.runAgent(context.userId())`, fixes its description to “17 类确定性规则”, returns run number and at most `ArchiveToolSupport.MAX_ITEMS` findings, and uses a generic failure message without `e.getMessage()`.

- [ ] **Step 6: Run focused tests and security regressions**

Run the Task 5 command again, then:

```powershell
mvn -pl sjherp-app -am '-Dtest=*PermissionTest,*SecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 5**

```powershell
git add -- server/sjherp-app/src/main/java/com/sjherp/app/consistency server/sjherp-app/src/main/java/com/sjherp/app/notification server/sjherp-app/src/main/java/com/sjherp/app/tool/consistency/RunConsistencyCheckTool.java server/sjherp-app/src/main/java/com/sjherp/app/config/DomainToolConfig.java server/sjherp-app/src/test/java/com/sjherp/app/consistency server/sjherp-app/src/test/java/com/sjherp/app/notification server/sjherp-app/src/test/java/com/sjherp/app/tool/consistency/RunConsistencyCheckToolTest.java server/sjherp-app/src/test/java/com/sjherp/app/gl/PeriodCloseServiceTest.java server/sjherp-app/src/test/java/com/sjherp/app/audit/AuditWriteCoverageTest.java
git commit -m "feat: 接入检查运行与通知 API"
```

### Task 6: Lightweight notification bell

**Files:**
- Create: `web/src/api/notificationApi.ts`
- Create: `web/src/components/NotificationBell.tsx`
- Modify: `web/src/App.tsx`
- Modify: `web/src/styles/global.css`

**Interfaces:**
- Consumes: Task 5 notification endpoints.
- Produces: all-authenticated-user topbar notification bell with own unread count and list.

- [ ] **Step 1: Add typed API client**

```ts
export interface SystemNotification {
  id: number;
  severity: 'ERROR' | 'WARN' | 'INFO';
  title: string;
  content: string;
  sourceRef: string;
  readAt: string | null;
  createdAt: string;
}
export const fetchUnreadCount = () => request<{ unreadCount: number }>('/api/notifications/unread-count');
export const fetchNotifications = () => request<NotificationPage>('/api/notifications?page=1&size=20');
export const markNotificationRead = (id: number) =>
  request<SystemNotification>(`/api/notifications/${id}/read`, { method: 'POST' });
```

- [ ] **Step 2: Implement the focused component**

`NotificationBell` loads unread count on mount, loads the first 20 notifications when opened, closes on its explicit close button, disables repeated mark-read requests, and shows loading/empty/error states. It renders titles and safe summaries only.

```tsx
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<SystemNotification[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [markingId, setMarkingId] = useState<number | null>(null);
  useEffect(() => { void fetchUnreadCount().then((r) => setUnread(r.unreadCount)); }, []);
  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (next) {
      try { setItems((await fetchNotifications()).items); setError(null); }
      catch (e) { setError(e instanceof Error ? e.message : '通知加载失败'); }
    }
  };
  const markRead = async (item: SystemNotification) => {
    if (item.readAt || markingId !== null) return;
    setMarkingId(item.id);
    try {
      const updated = await markNotificationRead(item.id);
      setItems((current) => current.map((row) => row.id === item.id ? updated : row));
      setUnread((current) => Math.max(0, current - 1));
    } catch (e) {
      setError(e instanceof Error ? e.message : '标记已读失败');
    } finally {
      setMarkingId(null);
    }
  };
  return (
    <div className="notification-bell">
      <button type="button" aria-label="通知" onClick={() => void toggle()}>通知{unread > 0 && <b>{unread}</b>}</button>
      {open && <section className="notification-popover">
        <header><strong>通知中心</strong><button type="button" onClick={() => setOpen(false)}>关闭</button></header>
        {error && <p role="alert">{error}</p>}
        {!error && items.length === 0 && <p>暂无通知</p>}
        {items.map((item) => <article key={item.id} className={item.readAt ? '' : 'notification-unread'}>
          <strong>{item.title}</strong><p>{item.content}</p>
          {!item.readAt && <button type="button" disabled={markingId === item.id}
            onClick={() => void markRead(item)}>标记已读</button>}
        </article>)}
      </section>}
    </div>
  );
}
```

- [ ] **Step 3: Mount in the topbar and style without dependencies**

Place `<NotificationBell />` before the user name in `App.tsx`. Add only normal CSS: relative bell wrapper, absolute right-aligned popover, severity border, unread dot, visible focus states, and a max-width 640px media rule that fits the panel inside the viewport.

- [ ] **Step 4: Run production build**

```powershell
npm run build
```

Working directory: `web`.

Expected: TypeScript and Vite production build pass without adding packages.

- [ ] **Step 5: Commit Task 6**

```powershell
git add -- web/src/api/notificationApi.ts web/src/components/NotificationBell.tsx web/src/App.tsx web/src/styles/global.css
git commit -m "feat: 增加站内通知入口"
```

### Task 7: Documentation, full verification, stacked PR, and CI

**Files:**
- Modify: `docs/业务-数据一致性校验.md`
- Modify: `docs/权限矩阵.md`
- Modify: `docs/产品路线图-0到1.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: completed Tasks 1–6 and their verified test counts.
- Produces: auditable release documentation and a stacked PR based on `codex/m6-t04-memory-governance`.

- [ ] **Step 1: Update business and permission documentation**

Document the three run sources, SQL-first/LLM-fail-open registry, “every run summary / differences only details”, clean-run silence, ADMIN/BOSS notification recipients, personal notification ownership, report permissions, no physical deletion, and T06/T07 exclusions.

- [ ] **Step 2: Run targeted backend tests**

```powershell
$env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl sjherp-domain '-Dtest=ConsistencyCheckRunTest,SystemNotificationTest' test
mvn -pl sjherp-app -am '-Dtest=ConsistencyRuleRegistryTest,CoreSqlAssertionRuleTest,ConsistencyCheckRunnerTest,ConsistencyRunPersistenceServiceTest,InAppNotificationChannelTest,NotificationServiceTest,ConsistencyControllerTest,ConsistencyControllerPermissionTest,ConsistencyScheduledCheckerTest,RunConsistencyCheckToolTest,NotificationControllerTest,NotificationControllerPermissionTest,PeriodCloseServiceTest,AuditWriteCoverageTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: all selected tests pass.

- [ ] **Step 3: Run full local backend and frontend verification**

```powershell
mvn test
```

Working directory: `server`.

```powershell
npm run build
```

Working directory: `web`.

Expected: backend has zero failures/errors; frontend production build passes. Record exact test count in `CLAUDE.md` and roadmap.

- [ ] **Step 4: Run repository hygiene checks**

```powershell
git diff --check
rg -n "DELETE FROM (consistency_check|system_notification)|ON DELETE CASCADE|Float|Double|e\.getMessage\(\)" server/sjherp-app/src/main/java server/sjherp-domain/src/main/java server/sjherp-infra/src/main/java server/sjherp-infra/src/main/resources/db/migration/V33__consistency_check_framework.sql
```

Expected: `git diff --check` is clean; scoped red-line scan has no unsafe match. Review any existing unrelated match before concluding.

- [ ] **Step 5: Update roadmap and CLAUDE baseline, then commit docs**

Mark only M6-T05 complete. Keep M6-T06/T07 pending. Include V33, actual backend test count, frontend build, local Docker limitation and GitHub MySQL 8.4 gate.

```powershell
git add -- docs/业务-数据一致性校验.md docs/权限矩阵.md docs/产品路线图-0到1.md CLAUDE.md
git commit -m "docs: 完成 M6-T05 检查 Agent 框架"
```

- [ ] **Step 6: Self-review the complete diff**

```powershell
git diff --stat codex/m6-t04-memory-governance...HEAD
git diff --check codex/m6-t04-memory-governance...HEAD
git status --short
```

Expected: only T05 files plus the four preserved user modifications appear; no T06/T07 functionality and no generated build output is staged.

- [ ] **Step 7: Push and create stacked PR**

```powershell
git push -u origin codex/m6-t05-check-agent-framework
gh pr create --base codex/m6-t04-memory-governance --head codex/m6-t05-check-agent-framework --title "feat: M6-T05 检查 Agent 框架" --body "M6-T05：保留现有17条规则，新增规则注册、三来源运行落库、LLM fail-open、ADMIN/BOSS站内通知与轻量通知入口；V33迁移；本地全量测试和前端构建通过，MySQL 8.4 真库由 CI 验收。本 PR 堆叠于 PR #9。"
```

The PR body must list boundaries, permissions, migration, tests, local Docker limitation, and that it is stacked on PR #9. Do not merge or close PR #6–#9.

- [ ] **Step 8: Wait for and repair CI until green**

```powershell
gh pr checks <new-pr-number> --watch --interval 20
```

Expected: frontend, backend, and MySQL 8.4 + Qdrant integration jobs pass. If CI exposes a real defect, reproduce with the smallest targeted test, add a regression test, fix, rerun relevant local suites, commit, push, and wait again.
