package com.sjherp.app.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import com.sjherp.domain.catalog.CategoryRepository;
import com.sjherp.domain.catalog.CategoryService;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitRepository;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.app.memory.MemoryIndexStateService;
import com.sjherp.app.memory.MemoryIndexingService;
import com.sjherp.app.memory.MemoryProperties;
import com.sjherp.app.memory.MemoryService;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecordCommand;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.identity.PasswordHasher;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;
import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.EmbeddingPurpose;
import com.sjherp.domain.memory.EmbeddingVector;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerRepository;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.SupplierCommand;
import com.sjherp.domain.partner.SupplierRepository;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.WarehouseCommand;
import com.sjherp.domain.warehouse.WarehouseRepository;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 审计覆盖测试（M2-T07 验收：每笔业务写操作必产生审计记录）：
 * <ul>
 *   <li>每个已标注 @Audited 的领域 Service 至少一条「写操作 → 审计记录」用例
 *       （仓储全 mock，经 AspectJProxyFactory 套真实切面）；</li>
 *   <li>反射完整性兜底：服务类上所有带 operator 参数的公有方法都必须标注
 *       @Audited——新增写方法漏标注时本测试失败（防覆盖漂移）。被检查的类来自
 *       classpath 扫描 com.sjherp.domain 下全部 *Service（非硬编码名单，
 *       新增领域 Service 自动纳入，D-8 同批 P2）。</li>
 * </ul>
 */
class AuditWriteCoverageTest {

    private static final String OPERATOR = "tester";

    private final AuditLogRepository auditRepository = mock(AuditLogRepository.class);

    /** 真实切面套在真实 Service 上（与容器内自动代理同一套匹配语义） */
    private <T> T proxied(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        AuditMetrics metrics = new AuditMetrics();
        factory.addAspect(new AuditAspect(
                new TransactionAwareAuditWriter(auditRepository, metrics), metrics));
        return factory.getProxy();
    }

    private AuditLogEntry capturedEntry() {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).insert(captor.capture());
        return captor.getValue();
    }

    private void assertAudit(String expectedAction, String expectedTargetType) {
        AuditLogEntry entry = capturedEntry();
        assertEquals(expectedAction, entry.action());
        assertEquals(expectedTargetType, entry.targetType());
        assertEquals(OPERATOR, entry.operator());
    }

    private static DocumentNumberGenerator numberGenerator(String fixed) {
        DocumentNumberGenerator generator = mock(DocumentNumberGenerator.class);
        when(generator.generate(any())).thenReturn(fixed);
        return generator;
    }

    // ---------------------------------------------------------------
    // 各 Service 至少一条「写操作产生审计记录」
    // ---------------------------------------------------------------

    @Test
    void 商品创建产生审计记录() {
        ProductRepository productRepository = mock(ProductRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        UnitRepository unitRepository = mock(UnitRepository.class);
        when(unitRepository.findById(1L)).thenReturn(Optional.of(new Unit("个", 0, OPERATOR)));

        ProductService service = proxied(new ProductService(productRepository, categoryRepository,
                unitRepository, numberGenerator("SKU-202606-0001")));
        service.create(new ProductCommand(null, "可乐", null, null, 1L, null, null, null,
                com.sjherp.domain.catalog.InventoryCategory.MERCHANDISE), OPERATOR);

        assertAudit("product.create", "product");
    }

    @Test
    void 类目创建产生审计记录() {
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        when(categoryRepository.findByName(any())).thenReturn(Optional.empty());

        CategoryService service = proxied(
                new CategoryService(categoryRepository, mock(ProductRepository.class)));
        service.create("饮料", null, OPERATOR);

        assertAudit("category.create", "category");
    }

    @Test
    void 单位创建产生审计记录() {
        UnitRepository unitRepository = mock(UnitRepository.class);
        when(unitRepository.findByName(any())).thenReturn(Optional.empty());

        UnitService service = proxied(new UnitService(unitRepository, mock(ProductRepository.class)));
        service.create("箱", 0, OPERATOR);

        assertAudit("unit.create", "unit");
    }

    @Test
    void 客户创建产生审计记录() {
        CustomerService service = proxied(new CustomerService(mock(CustomerRepository.class),
                numberGenerator("CUS-202606-0001")));
        service.create(new CustomerCommand(null, "测试客户", null, null, null, null,
                SettlementMethod.MONTHLY, null), OPERATOR);

        assertAudit("customer.create", "customer");
    }

    @Test
    void 供应商创建产生审计记录() {
        SupplierService service = proxied(new SupplierService(mock(SupplierRepository.class),
                numberGenerator("SUP-202606-0001")));
        service.create(new SupplierCommand(null, "测试供应商", null, null, null, null,
                SettlementMethod.MONTHLY), OPERATOR);

        assertAudit("supplier.create", "supplier");
    }

    @Test
    void 仓库创建产生审计记录() {
        WarehouseService service = proxied(new WarehouseService(mock(WarehouseRepository.class),
                numberGenerator("WH-202606-0001")));
        service.create(new WarehouseCommand(null, "测试仓库", null, null, null), OPERATOR);

        assertAudit("warehouse.create", "warehouse");
    }

    @Test
    void 用户创建产生审计记录_摘要不含密码() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(passwordHasher.hash(any())).thenReturn("$bcrypt-hash");

        UserService service = proxied(new UserService(userRepository, passwordHasher));
        service.create("zhangsan", "张三", "abc12345", Set.of(Role.SALES), OPERATOR);

        AuditLogEntry entry = capturedEntry();
        assertEquals("user.create", entry.action());
        assertEquals("user", entry.targetType());
        assertEquals(OPERATOR, entry.operator());
        assertEquals("zhangsan", entry.targetCode());
        assertTrue(!entry.summary().contains("abc12345") && !entry.summary().contains("$bcrypt-hash"),
                "审计摘要绝不允许包含密码或哈希: " + entry.summary());
    }

    @Test
    void 缺口创建产生审计记录() {
        GapRecordService service = proxied(new GapRecordService(mock(GapRecordRepository.class),
                numberGenerator("GAP-202606-0001")));
        service.create(new GapRecordCommand("sess-1", "缺批量导入", "用户想批量导客户", "支持 Excel 导入",
                "缺导入能力", BusinessModule.GENERAL, GapSeverity.MEDIUM, "7"), OPERATOR);

        assertAudit("gap.create", "gap");
    }

    @Test
    void 大记忆创建产生审计记录且摘要不含原文() {
        MemoryEntryRepository repository = mock(MemoryEntryRepository.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            MemoryEntry entry = invocation.getArgument(0);
            entry.assignId(11L);
            return null;
        }).when(repository).save(any(MemoryEntry.class));
        MemoryService service = proxied(new MemoryService(repository,
                numberGenerator("MEM-202607-0001"), mock(ApplicationEventPublisher.class)));

        service.create(memoryCommand("大客户口径", "年采购金额超过50万元"), OPERATOR);

        AuditLogEntry entry = capturedEntry();
        assertEquals("memory.create", entry.action());
        assertEquals("memory", entry.targetType());
        assertEquals(OPERATOR, entry.operator());
        assertTrue(!entry.summary().contains("年采购金额超过50万元"),
                "大记忆审计摘要不得包含原文: " + entry.summary());
    }

    @Test
    void 大记忆替代产生审计记录() {
        MemoryEntryRepository repository = mock(MemoryEntryRepository.class);
        MemoryEntry previous = memoryEntry();
        when(repository.findByMemoryNo(previous.getMemoryNo())).thenReturn(Optional.of(previous));
        org.mockito.Mockito.doAnswer(invocation -> {
            MemoryEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                entry.assignId(12L);
            }
            return null;
        }).when(repository).save(any(MemoryEntry.class));
        MemoryService service = proxied(new MemoryService(repository,
                numberGenerator("MEM-202607-0002"), mock(ApplicationEventPublisher.class)));

        service.replace(previous.getMemoryNo(), memoryCommand("大客户口径V2", "新口径"), OPERATOR);

        assertAudit("memory.replace", "memory");
    }

    @Test
    void 大记忆失效产生审计记录() {
        MemoryEntryRepository repository = mock(MemoryEntryRepository.class);
        MemoryEntry persisted = memoryEntry();
        when(repository.findByMemoryNo(persisted.getMemoryNo())).thenReturn(Optional.of(persisted));
        MemoryService service = proxied(new MemoryService(repository,
                numberGenerator("MEM-unused"), mock(ApplicationEventPublisher.class)));

        service.expire(persisted.getMemoryNo(), OPERATOR);

        assertAudit("memory.expire", "memory");
    }

    @Test
    void 大记忆手工重试产生审计记录() {
        MemoryEntryRepository repository = mock(MemoryEntryRepository.class);
        MemoryEntry persisted = memoryEntry();
        when(repository.findByMemoryNo(persisted.getMemoryNo())).thenReturn(Optional.of(persisted));
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embed(persisted.getContent(), EmbeddingPurpose.DOCUMENT))
                .thenReturn(new EmbeddingVector("qwen3-embedding:0.6b", 1024,
                        Collections.nCopies(1024, 0.1f)));
        MemoryIndexingService service = proxied(new MemoryIndexingService(repository, embedding,
                mock(VectorIndex.class), mock(MemoryIndexStateService.class), memoryProperties()));

        service.retryIndex(persisted.getMemoryNo(), OPERATOR);

        assertAudit("memory.retry_index", "memory_index");
    }

    @Test
    void 大记忆全量重建产生审计记录() {
        MemoryEntryRepository repository = mock(MemoryEntryRepository.class);
        when(repository.findActiveAfterId(0, 50)).thenReturn(List.of());
        MemoryIndexingService service = proxied(new MemoryIndexingService(repository,
                mock(EmbeddingClient.class), mock(VectorIndex.class),
                mock(MemoryIndexStateService.class), memoryProperties()));

        service.rebuildIndex(OPERATOR);

        assertAudit("memory.rebuild_index", "memory_index");
    }

    private static MemoryEntryCommand memoryCommand(String title, String content) {
        return new MemoryEntryCommand(MemoryType.BUSINESS_TERM, title, content,
                MemorySourceType.USER_INPUT, "session-1", null, null);
    }

    private static MemoryEntry memoryEntry() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        MemoryEntry entry = MemoryEntry.create("MEM-202607-0001", "MEM-202607-0001", 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", now, null, OPERATOR, now);
        entry.assignId(11L);
        return entry;
    }

    private static MemoryProperties memoryProperties() {
        return new MemoryProperties(true,
                new MemoryProperties.Embedding("ollama", URI.create("http://localhost:11434"),
                        "qwen3-embedding:0.6b", 1024, 60),
                new MemoryProperties.Vector("qdrant", URI.create("http://localhost:6333"),
                        "sjherp-memory-qwen3-0_6b-1024-v1", "COSINE"),
                new MemoryProperties.Indexing(30, 50, 8));
    }

    // ---------------------------------------------------------------
    // 反射完整性兜底：带 operator 参数的公有方法必须 @Audited
    // ---------------------------------------------------------------

    /**
     * classpath 扫描 com.sjherp.domain 下全部具体 *Service 类（D-8 同批 P2：
     * 替换硬编码名单）——M3 起新增的领域 Service <b>自动</b>纳入防漂移断言，
     * 不再依赖人工登记。扫描在测试侧用 Spring 类路径工具（main 不加任何依赖）。
     */
    static Stream<Class<?>> auditedServiceClasses() {
        return scanDomainServiceClasses().stream();
    }

    private static List<Class<?>> scanDomainServiceClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".+Service")));
        return scanner.findCandidateComponents("com.sjherp.domain").stream()
                .map(BeanDefinition::getBeanClassName)
                .sorted()
                .map(AuditWriteCoverageTest::loadClass)
                .collect(Collectors.toList());
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("加载领域 Service 类失败: " + className, e);
        }
    }

    @Test
    void 类路径扫描能发现全部已知领域Service_防扫描静默失效() {
        List<Class<?>> scanned = scanDomainServiceClasses();
        assertTrue(scanned.containsAll(List.of(ProductService.class, CategoryService.class,
                        UnitService.class, CustomerService.class, SupplierService.class,
                        WarehouseService.class, UserService.class, GapRecordService.class)),
                "扫描结果应至少包含 M2 已知的 8 个领域 Service（扫描失效会让防漂移断言变空转）: "
                        + scanned);
    }

    @ParameterizedTest
    @MethodSource("auditedServiceClasses")
    void 所有带operator参数的公有写方法都已标注Audited(Class<?> serviceClass) {
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            boolean takesOperator = Arrays.stream(method.getParameters())
                    .anyMatch(AuditWriteCoverageTest::isOperatorParameter);
            if (takesOperator) {
                assertTrue(method.isAnnotationPresent(Audited.class),
                        serviceClass.getSimpleName() + "#" + method.getName()
                                + " 带 operator 参数（写操作）但未标注 @Audited——每笔写操作必有审计记录");
            }
        }
    }

    private static boolean isOperatorParameter(Parameter parameter) {
        return parameter.getType() == String.class && "operator".equals(parameter.getName());
    }
}
