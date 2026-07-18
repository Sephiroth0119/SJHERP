# M6-T04 系统大记忆治理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在不改变 T01～T03 边界的前提下，为 ADMIN/BOSS 提供确定性重复/冲突候选、人工冲突治理和轻量管理页面。

**Architecture:** MySQL memory_entry 继续作为唯一真源，Jdbc 仓储只读查询确定性候选，MemoryService 负责所有状态写入并发布提交后索引事件。React 页面复用现有 REST、登录态和普通 CSS，不引入新依赖；后端 memory:manage 始终是最终权限边界。

**Tech Stack:** Java 21、Spring Boot、Spring JDBC、JUnit 5、Mockito、MySQL 8.4、Qdrant 1.13.4、React 18、TypeScript 5.6、Vite 5。

## Global Constraints

- 每次 Maven 命令前设置 JAVA_HOME=C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10，并把其 bin 放在 Path 首位。
- 系统只识别候选，不自动合并、自动失效或自动裁决冲突。
- 仅 ADMIN、BOSS 可写入和治理；所有已登录聊天用户仍按 T03 只读召回。
- MySQL 是唯一真源；Qdrant 仅是派生索引。
- 所有状态写必须经过 MemoryEntry 和 MemoryService，事务化并使用 @Audited。
- 编辑创建新版本；删除只做逻辑失效；禁止物理删除。
- 不新增迁移、依赖、语义去重、治理工单、自动任务、召回 API 或 Agent 治理工具。
- 日志、异常和批量审计摘要不得包含原文、来源编号、URL、请求体或哈希。
- 保留进入任务前已有的用户文档改动；每次提交只暂存任务文件。

---

## File Map

- Modify server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntry.java：冲突状态机。
- Modify server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryRepository.java：候选和加锁读取端口。
- Modify server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory/JdbcMemoryEntryRepository.java：MySQL 查询。
- Create server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryGovernanceService.java：候选分组。
- Create server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryConflictResult.java：批量审计目标。
- Modify server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryService.java：治理写入口。
- Modify server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java：服务装配。
- Modify server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryDtos.java：API DTO。
- Modify server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryController.java：治理端点。
- Create web/src/api/memoryApi.ts：前端 API。
- Create web/src/components/memory/MemoryGovernancePage.tsx：管理页面。
- Modify web/src/types/navigation.ts、web/src/components/Sidebar.tsx、web/src/App.tsx、web/src/styles/global.css：入口、角色守卫和样式。
- Modify MemoryEntryTest、MemoryGovernanceServiceTest、MemoryServiceTest、MemoryControllerTest、MemoryApiPermissionTest、AuditWriteCoverageTest、MemoryFoundationIntegrationTest：测试。
- Modify docs/业务-系统大记忆.md、docs/权限矩阵.md、docs/产品路线图-0到1.md、CLAUDE.md：交付文档。

---

### Task 1: 领域冲突状态机

**Files:**
- Modify: server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntry.java
- Test: server/sjherp-domain/src/test/java/com/sjherp/domain/memory/MemoryEntryTest.java

**Interfaces:**
- Consumes: T01 MemoryEntry 状态和索引字段。
- Produces: markConflict(String, Instant)、activate(String, Instant)，以及支持 CONFLICT 的 expire(String, Instant)。

- [ ] **Step 1: 写失败测试**

    @Test
    void 冲突可恢复或失效且恢复保留有效期() {
        Instant validTo = NOW.plusSeconds(3600);
        MemoryEntry entry = MemoryEntry.create("MEM-2", "MEM-2", 1,
                MemoryType.BUSINESS_TERM, "口径", "正文", MemorySourceType.USER_INPUT,
                "session-1", NOW, validTo, "user:1", NOW);
        entry.markIndexed("memory-v1", "model", 1024, "system:indexer", NOW.plusSeconds(1));
        entry.markConflict("user:1", NOW.plusSeconds(2));
        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.CONFLICT);
        assertThatThrownBy(() -> entry.markPending("user:1", NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);

        entry.activate("user:1", NOW.plusSeconds(4));
        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(entry.getValidTo()).isEqualTo(validTo);
        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
    }

    @Test
    void 已过有效期或非冲突记忆不可恢复() {
        MemoryEntry active = fixture();
        assertThatThrownBy(() -> active.activate("user:1", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        MemoryEntry ended = MemoryEntry.create("MEM-2", "MEM-2", 1,
                MemoryType.BUSINESS_TERM, "口径", "正文", MemorySourceType.USER_INPUT,
                "session-1", NOW, NOW.plusSeconds(1), "user:1", NOW);
        ended.markConflict("user:1", NOW.plusMillis(500));
        assertThatThrownBy(() -> ended.activate("user:1", NOW.plusSeconds(1)))
                .hasMessageContaining("有效期");
    }

    @Test
    void 冲突记忆可逻辑失效但终态不可恢复() {
        MemoryEntry entry = fixture();
        entry.markConflict("user:1", NOW.plusSeconds(1));
        entry.expire("user:1", NOW.plusSeconds(2));
        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.EXPIRED);
        assertThatThrownBy(() -> entry.activate("user:1", NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

- [ ] **Step 2: 运行并确认 RED**

    $env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
    $env:Path="$env:JAVA_HOME\bin;$env:Path"
    mvn -pl sjherp-domain -Dtest=MemoryEntryTest test

Expected: FAIL，markConflict/activate 不存在或冲突状态不能失效。

- [ ] **Step 3: 实现最小领域流转**

    public void markConflict(String operator, Instant now) {
        requireActive("标记冲突");
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Objects.requireNonNull(now, "当前时间不能为空");
        this.status = MemoryStatus.CONFLICT;
        touch(operator, now);
    }

    public void activate(String operator, Instant now) {
        if (status != MemoryStatus.CONFLICT) {
            throw new IllegalStateException("仅冲突记忆可恢复活动，当前状态: " + status);
        }
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Instant checkedNow = Objects.requireNonNull(now, "当前时间不能为空");
        if (validTo != null && !validTo.isAfter(checkedNow)) {
            throw new IllegalStateException("记忆有效期已结束，不能恢复活动");
        }
        this.status = MemoryStatus.ACTIVE;
        this.indexStatus = MemoryIndexStatus.PENDING;
        this.indexedCollection = null;
        this.embeddingModel = null;
        this.embeddingDimension = null;
        this.retryCount = 0;
        this.nextRetryAt = null;
        this.lastIndexError = null;
        touch(operator, checkedNow);
    }

expire 仅允许 ACTIVE 或 CONFLICT，统一设置 EXPIRED 和 validTo=now；其他状态在修改字段前抛 IllegalStateException。

- [ ] **Step 4: 运行 GREEN 并提交**

Run: 与 Step 2 相同。Expected: PASS。

    git add -- server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntry.java server/sjherp-domain/src/test/java/com/sjherp/domain/memory/MemoryEntryTest.java
    git commit -m "feat: 增加记忆冲突状态治理"

---

### Task 2: 确定性候选查询

**Files:**
- Modify: server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryRepository.java
- Modify: server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory/JdbcMemoryEntryRepository.java
- Create: server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryGovernanceService.java
- Test: server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryGovernanceServiceTest.java
- Modify: server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java

**Interfaces:**
- Produces: findDuplicateCandidates(long,int)、findConflictCandidates(long,int)、findByMemoryNosForUpdate(List<String>) 和 MemoryGovernanceService.findCandidates(int)。

- [ ] **Step 1: 写失败测试**

    @Test
    void 按类型哈希组成重复组_按类型精确标题组成冲突组() {
        when(repository.findDuplicateCandidates(0L, 50))
                .thenReturn(List.of(duplicateB, duplicateA));
        when(repository.findConflictCandidates(0L, 50))
                .thenReturn(List.of(conflict, duplicateA));

        MemoryGovernanceService.Candidates result =
                new MemoryGovernanceService(repository).findCandidates(50);

        assertThat(result.duplicateGroups()).singleElement()
                .satisfies(group -> assertThat(group.entries())
                        .containsExactly(duplicateB, duplicateA));
        assertThat(result.conflictGroups()).singleElement()
                .satisfies(group -> {
                    assertThat(group.title()).isEqualTo("客户口径");
                    assertThat(group.entries()).containsExactly(conflict, duplicateA);
                });
    }

    @Test
    void 候选组上限必须在一到一百之间() {
        MemoryGovernanceService service = new MemoryGovernanceService(repository);
        assertThatThrownBy(() -> service.findCandidates(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findCandidates(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

- [ ] **Step 2: 运行并确认 RED**

    $env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
    $env:Path="$env:JAVA_HOME\bin;$env:Path"
    mvn -pl sjherp-app -am -Dtest=MemoryGovernanceServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL，治理查询服务尚不存在。

- [ ] **Step 3: 增加端口和只读服务**

    List<MemoryEntry> findDuplicateCandidates(long tenantId, int groupLimit);
    List<MemoryEntry> findConflictCandidates(long tenantId, int groupLimit);
    List<MemoryEntry> findByMemoryNosForUpdate(List<String> memoryNos);

MemoryGovernanceService 定义不可变 Candidates、DuplicateGroup、ConflictGroup records；findCandidates 校验 limit=1..100，调用两个仓储方法，并用 LinkedHashMap 按 (MemoryType, contentHash) 与 (MemoryType, title) 分组，所有列表 List.copyOf。

- [ ] **Step 4: 实现 Jdbc 查询**

重复候选使用以下分组核心：

    SELECT memory_type, content_hash, MAX(id) AS newest_id
      FROM memory_entry
     WHERE tenant_id = ? AND status = 'ACTIVE'
     GROUP BY memory_type, content_hash
    HAVING COUNT(*) > 1
     ORDER BY newest_id DESC
     LIMIT ?

冲突候选使用 BINARY title 做精确分组，HAVING COUNT(DISTINCT content_hash) > 1。两个派生表都回连 SELECT_COLUMNS，显式再次过滤同租户 ACTIVE，并按 newest_id DESC、id DESC 排序。findByMemoryNosForUpdate 校验 1..50 个编号，使用占位符 IN (...) FOR UPDATE，禁止字符串拼接用户值。

- [ ] **Step 5: 装配、运行 GREEN 并提交**

MemoryInfraConfig 新增：

    @Bean
    MemoryGovernanceService memoryGovernanceService(MemoryEntryRepository repository) {
        return new MemoryGovernanceService(repository);
    }

Run:

    mvn -pl sjherp-app -am -Dtest=MemoryGovernanceServiceTest,MemoryInfraConfigTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS。

    git add -- server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryRepository.java server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory/JdbcMemoryEntryRepository.java server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryGovernanceService.java server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryGovernanceServiceTest.java server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java
    git commit -m "feat: 查询记忆治理候选"

---

### Task 3: 冲突治理应用写入口

**Files:**
- Create: server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryConflictResult.java
- Modify: server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryService.java
- Modify: server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryServiceTest.java

**Interfaces:**
- Consumes: Task 1 状态流转和 Task 2 加锁读取。
- Produces: markConflict(List<String>,String) 和 activate(String,String)。

- [ ] **Step 1: 写失败测试**

    @Test
    void 整组冲突完整校验后保存并发布删除事件() {
        when(repository.findByMemoryNosForUpdate(List.of(firstNo, secondNo)))
                .thenReturn(List.of(first, second));
        MemoryConflictResult result =
                service.markConflict(List.of(firstNo, secondNo), "user:1");
        assertThat(result.entries()).allMatch(e -> e.getStatus() == MemoryStatus.CONFLICT);
        verify(repository).save(first);
        verify(repository).save(second);
        verify(events).publishEvent(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.DELETE, firstNo, first.getId()));
        assertThat(result.auditSummary())
                .doesNotContain(first.getContent())
                .doesNotContain(first.getContentHash());
    }

    @Test
    void 不满足同类型同标题不同内容时不做部分写入() {
        when(repository.findByMemoryNosForUpdate(any())).thenReturn(List.of(first, otherTitle));
        assertThatThrownBy(() -> service.markConflict(List.of(firstNo, secondNo), "user:1"))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void 恢复冲突先保存待索引真源再发布上载事件() {
        first.markConflict("user:1", NOW.minusSeconds(1));
        when(repository.findByMemoryNosForUpdate(List.of(firstNo))).thenReturn(List.of(first));
        MemoryEntry result = service.activate(firstNo, "user:1");
        assertThat(result.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        InOrder order = inOrder(repository, events);
        order.verify(repository).save(first);
        order.verify(events).publishEvent(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.UPSERT, firstNo, first.getId()));
    }

- [ ] **Step 2: 运行并确认 RED**

    mvn -pl sjherp-app -am -Dtest=MemoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL，治理写方法不存在。

- [ ] **Step 3: 实现批量审计结果**

MemoryConflictResult 是实现 AuditTarget 的 record，构造器要求至少两条并 List.copyOf；auditTargetId 返回 null，auditTargetCode 返回首条 memoryNo，auditSummary 只返回动作、数量和排序后的 memoryNo，不包含标题、正文、来源或哈希。

- [ ] **Step 4: 实现事务写入口**

markConflict 必须：

1. strip 并校验 2..50 个非空、不重复编号。
2. findByMemoryNosForUpdate 一次锁定；缺失任一编号抛 MemoryEntryNotFoundException。
3. 验证全部 ACTIVE、同 tenant、同 MemoryType、标题 String.equals，且哈希 distinct count >= 2。
4. 使用同一 now 调用 markConflict，全部校验成功后才 save。
5. 每条发布 DELETE 事件。
6. 标注 @Transactional 与 @Audited(action="memory.mark_conflict", targetType="memory")。

activate 必须加锁读单条、调用聚合 activate、save、发布 UPSERT，并标注 memory.activate 审计。

- [ ] **Step 5: 运行 GREEN 并提交**

    mvn -pl sjherp-app -am -Dtest=MemoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS。

    git add -- server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryConflictResult.java server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryService.java server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryServiceTest.java
    git commit -m "feat: 增加记忆冲突治理写入口"

---

### Task 4: 受控治理 REST、权限和审计

**Files:**
- Modify: MemoryDtos.java、MemoryController.java、MemoryControllerTest.java、MemoryApiPermissionTest.java、AuditWriteCoverageTest.java under server/sjherp-app/src。

**Interfaces:**
- Produces: GET /api/memories/governance/candidates、POST /api/memories/governance/conflicts、POST /api/memories/{memoryNo}/activate。

- [ ] **Step 1: 写 Controller 和权限失败测试**

ControllerTest 断言默认 limit=50、候选 JSON 分组、批量 memoryNos 映射、CurrentUser.operator 传递和恢复响应。PermissionTest 对三个新端点断言 ADMIN/BOSS 成功，ACCOUNTANT/PURCHASER/SALES/WAREHOUSE 为 403，未登录为 401。

- [ ] **Step 2: 运行并确认 RED**

    mvn -pl sjherp-app -am -Dtest=MemoryControllerTest,MemoryApiPermissionTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL，新 DTO/端点不存在。

- [ ] **Step 3: 增加 DTO 与端点**

DTO 精确契约：

    record MarkConflictRequest(
        @NotNull @Size(min=2,max=50)
        List<@NotBlank String> memoryNos) {}
    record DuplicateGroupResponse(String type, List<MemoryResponse> entries) {}
    record ConflictGroupResponse(String type, String title, List<MemoryResponse> entries) {}
    record GovernanceCandidatesResponse(
        List<DuplicateGroupResponse> duplicateGroups,
        List<ConflictGroupResponse> conflictGroups) {}
    record ConflictResultResponse(List<MemoryResponse> entries) {}

MemoryController 注入 MemoryGovernanceService，增加三个端点；保留类级 memory:manage 和 memory.enabled 条件。非法列表为 400，MemoryEntryNotFoundException 为 404，非法状态/陈旧候选为 409。

- [ ] **Step 4: 写审计失败测试并实现覆盖**

AuditWriteCoverageTest 通过真实 AuditAspect 代理分别调用 markConflict、activate、从 CONFLICT expire；断言 action 为 memory.mark_conflict、memory.activate、memory.expire，摘要不含正文、来源编号或 contentHash。

- [ ] **Step 5: 运行 GREEN 并提交**

    mvn -pl sjherp-app -am -Dtest=MemoryControllerTest,MemoryApiPermissionTest,AuditWriteCoverageTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS。

    git add -- server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryDtos.java server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryController.java server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryControllerTest.java server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryApiPermissionTest.java server/sjherp-app/src/test/java/com/sjherp/app/audit/AuditWriteCoverageTest.java
    git commit -m "feat: 暴露受控记忆治理 API"

---

### Task 5: 前端管理 API

**Files:**
- Create: web/src/api/memoryApi.ts

**Interfaces:**
- Produces: MemoryEntry/MemoryPage/GovernanceCandidates 类型；searchMemories、fetchGovernanceCandidates、replaceMemory、expireMemory、retryMemoryIndex、markMemoryConflict、activateMemory。

- [ ] **Step 1: 创建严格类型和请求函数**

类型枚举使用后端字符串：MemoryType 四值、MemoryStatus 四值、MemoryIndexStatus 三值、MemorySourceType 四值。MemoryEntry 包含 MemoryResponse 全部字段；MemoryForm 包含 type/title/content/sourceType/sourceRef/validFrom/validTo。

searchMemories 使用 URLSearchParams 只加入非空 type/status/indexStatus 和 page/size。路径中的 memoryNo 必须 encodeURIComponent。所有写函数复用 request，方法为 PUT 或 POST，禁止直接 fetch。

- [ ] **Step 2: 运行生产构建并提交**

    npm run build

Workdir: web。Expected: TypeScript 和 Vite build PASS。

    git add -- web/src/api/memoryApi.ts
    git commit -m "feat: 增加记忆治理前端接口"

---

### Task 6: 角色受控管理页面

**Files:**
- Create: web/src/components/memory/MemoryGovernancePage.tsx
- Modify: web/src/types/navigation.ts、web/src/components/Sidebar.tsx、web/src/App.tsx、web/src/styles/global.css

**Interfaces:**
- Consumes: Task 5 API 和 AuthUser.roles。
- Produces: ADMIN/BOSS 可见的记忆列表、详情、编辑、失效、重试和候选治理页面。

- [ ] **Step 1: 增加导航角色契约**

ModuleKey 增加 memory；ModuleNavItem 增加 requiredRoles?: string[]；新增“记忆治理”条目 requiredRoles=['ADMIN','BOSS']。SidebarProps 增加 roles，并只渲染无角色要求或角色命中的条目。

- [ ] **Step 2: 实现页面加载与错误状态**

MemoryGovernancePage 状态包含 tab、filters、page、candidates、selected、editing、loading、submitting、error。reload 使用 Promise.all 同时加载列表和候选。404 显示“大记忆功能未启用或暂不可用”；409 显示“记忆状态已变化，请刷新后重试”；其他 ApiError 显示中文 message。提交中禁用重复操作。

- [ ] **Step 3: 实现列表与详情写操作**

列表支持类型、状态、索引状态和分页；详情展示正文、来源、有效期、版本链、索引元数据。ACTIVE 可编辑/失效，CONFLICT 可恢复/失效，ACTIVE+FAILED 可重试。编辑预填现有字段并调用 replaceMemory。失效确认固定文案：

    确认将该记忆设为失效？记录、版本历史和审计日志仍会保留。

成功写入后关闭编辑并 reload。

- [ ] **Step 4: 实现候选治理**

重复组显示所有成员且只提供逐条失效，不提供自动保留。冲突组“整组标记冲突”提交全部 memoryNo，确认文案明确整组暂停召回且系统不选择正确口径。空态分别说明无完全重复候选、无精确同标题不同内容候选。

- [ ] **Step 5: 在 App 增加双重守卫**

App 把 user.roles 传给 Sidebar。只有 roles 包含 ADMIN 或 BOSS 时渲染 MemoryGovernancePage；后台刷新角色后若当前为 memory 且权限消失，useEffect 切回 agent。后端权限仍是最终边界。

- [ ] **Step 6: 添加普通 CSS、构建并提交**

CSS 使用现有变量，覆盖 memory-page、toolbar、tabs、layout、table、detail、group、actions、error、empty，并在窄屏改为单列。

    npm run build

Workdir: web。Expected: PASS。

    git add -- web/src/components/memory/MemoryGovernancePage.tsx web/src/types/navigation.ts web/src/components/Sidebar.tsx web/src/App.tsx web/src/styles/global.css
    git commit -m "feat: 增加记忆治理管理页面"

---

### Task 7: 真库、文档和最终验收

**Files:**
- Modify: server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryFoundationIntegrationTest.java
- Modify: docs/业务-系统大记忆.md、docs/权限矩阵.md、docs/产品路线图-0到1.md、CLAUDE.md

**Interfaces:**
- Consumes: Tasks 1～6。
- Produces: MySQL 8.4 + Qdrant 验收证据和完成文档。

- [ ] **Step 1: 扩展真库测试**

在现有 Testcontainers 装配取得 MemoryGovernanceService。测试创建同标题不同正文两条记忆并等待索引，断言冲突候选；整组标记后断言 MySQL 都为 CONFLICT 且两个 point 删除；失效其中一条、恢复另一条，断言恢复先 PENDING 后 ACTIVE/INDEXED 且 point 重建。再创建同类型相同正文不同标题两条记忆，断言重复候选；失效一条后 memory_entry 总数不减少且 point 删除。

- [ ] **Step 2: 运行非容器定向回归**

    $env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
    $env:Path="$env:JAVA_HOME\bin;$env:Path"
    mvn -pl sjherp-app -am -Dtest=MemoryEntryTest,MemoryGovernanceServiceTest,MemoryServiceTest,MemoryControllerTest,MemoryApiPermissionTest,AuditWriteCoverageTest,MemoryRecallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS。

- [ ] **Step 3: 同步文档**

业务文档补候选规则、人工治理和状态流转；权限矩阵补 T04 端点/页面且角色不变；路线图 M6-T04 标完成并记录“只检测、人工治理、无迁移、无自动裁决”；CLAUDE.md 只在 M6 状态处最小合并并更新最终测试数，不覆盖已有用户改动。

- [ ] **Step 4: 前后端全量验证**

Frontend workdir web:

    npm run build

Backend workdir server:

    $env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
    $env:Path="$env:JAVA_HOME\bin;$env:Path"
    mvn test

Expected: 0 failures/errors。

- [ ] **Step 5: 真库验证**

    mvn -pl sjherp-app -am -Dtest=MemoryFoundationIntegrationTest -Dsjherp.integration.memory=true -Dsurefire.failIfNoSpecifiedTests=false test

Docker 可用时必须 PASS；本地 daemon 不可用时记录客观原因，并由 GitHub integration-db 执行 MySQL 8.4 + Qdrant 1.13.4 真库用例。

- [ ] **Step 6: 提交验收**

    git add -- server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryFoundationIntegrationTest.java docs/业务-系统大记忆.md docs/权限矩阵.md docs/产品路线图-0到1.md CLAUDE.md
    git commit -m "test: 验收 M6-T04 记忆治理"

- [ ] **Step 7: 推送堆叠 PR 并等待 CI**

推送 codex/m6-t04-memory-governance，PR base 必须是 codex/m6-t03-memory-recall，保持非 Draft。等待 frontend、backend verify、integration-db 全绿；不得合并或关闭 PR #6、#7、#8。

---

## Final Verification Checklist

- [ ] 工作树只保留任务前已有且未被本任务暂存的用户改动。
- [ ] T04 diff 不包含迁移、召回算法或 Agent 工具扩展。
- [ ] ADMIN/BOSS 可治理；其他角色 403 且无前端入口；未登录 401。
- [ ] 重复和冲突只生成候选，状态变化均由管理员明确操作。
- [ ] 冲突整组事务写入，恢复保留有效期，失效不物删。
- [ ] CONFLICT/EXPIRED 无法通过 T03 MySQL 真源门禁。
- [ ] 审计与日志不含正文、来源、URL、请求体或哈希。
- [ ] 前端构建、后端全量测试和 GitHub 三类 CI 全部通过。
