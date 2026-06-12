package com.sjherp.app.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerRepository;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 审计切面单测（M2-T07）：以 AspectJProxyFactory 给真实领域 Service 套切面
 * （与 Spring 容器内自动代理同一套 AspectJ 匹配语义），audit 仓储用 mock。
 *
 * <p>覆盖：@Audited 方法触发审计记录（operator/action/目标/摘要正确）、
 * 未标注方法不触发、审计写入失败不影响业务返回（WARN + 计数器可见）、
 * 更新类操作摘要含「变更前 → 变更后」、Agent 会话上下文落 session_id。
 */
class AuditAspectTest {

    private CustomerRepository customerRepository;
    private AuditLogRepository auditRepository;
    private AuditMetrics metrics;
    private CustomerService proxiedService;

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        auditRepository = mock(AuditLogRepository.class);
        metrics = new AuditMetrics();

        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        when(numberGenerator.generate(any())).thenReturn("CUS-202606-0001");

        CustomerService target = new CustomerService(customerRepository, numberGenerator);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new AuditAspect(auditRepository, metrics));
        proxiedService = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    private static CustomerCommand command(String name) {
        return new CustomerCommand(null, name, "张三", "13800000000", null, null,
                SettlementMethod.MONTHLY, null);
    }

    private AuditLogEntry capturedEntry() {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void 标注方法触发审计记录_动作目标操作人正确() {
        Customer created = proxiedService.create(command("测试客户"), "admin");
        assertNotNull(created);

        AuditLogEntry entry = capturedEntry();
        assertEquals("customer.create", entry.action());
        assertEquals("customer", entry.targetType());
        assertEquals("admin", entry.operator());
        assertEquals("CUS-202606-0001", entry.targetCode());
        assertTrue(entry.summary().contains("名称=测试客户"), "摘要应含关键字段: " + entry.summary());
        assertNull(entry.sessionId(), "人工路径无会话上下文，session_id 应为空");
    }

    @Test
    void 未标注的查询方法不触发审计() {
        Customer existing = new Customer("CUS-1", "老客户", null, null, null, null,
                SettlementMethod.CASH, null, "admin");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));

        proxiedService.get(1L);
        verifyNoInteractions(auditRepository);
    }

    @Test
    void 审计写入失败不影响业务返回_但计数器可见() {
        doThrow(new RuntimeException("数据库连不上")).when(auditRepository).insert(any());

        Customer created = proxiedService.create(command("测试客户"), "admin");

        assertNotNull(created, "审计失败不得阻塞业务（与 invocation listener 同哲学）");
        assertEquals("测试客户", created.getName());
        assertEquals(1, metrics.failureCount(), "审计失败必须计数可发现");
    }

    @Test
    void 业务方法抛异常时不记审计() {
        when(customerRepository.existsByCode(any())).thenReturn(true);
        try {
            proxiedService.create(new CustomerCommand("CUS-X", "重复编码", null, null, null, null,
                    SettlementMethod.MONTHLY, null), "admin");
        } catch (IllegalArgumentException expected) {
            // 编码冲突被领域层拒绝——写操作未发生
        }
        verifyNoInteractions(auditRepository);
    }

    @Test
    void 更新类操作摘要含变更前后() {
        Customer existing = new Customer("CUS-1", "老客户", null, null, null, null,
                SettlementMethod.CASH, null, "admin");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));

        proxiedService.disable(1L, "admin");

        AuditLogEntry entry = capturedEntry();
        assertEquals("customer.disable", entry.action());
        assertTrue(entry.summary().contains("变更前["), "摘要应含变更前快照: " + entry.summary());
        assertTrue(entry.summary().contains("状态=启用"), "变更前状态应为启用: " + entry.summary());
        assertTrue(entry.summary().contains("状态=停用"), "变更后状态应为停用: " + entry.summary());
    }

    @Test
    void Agent会话上下文落session_id_操作人带agent前缀() {
        AuditContext.setSessionId("sess-001");
        proxiedService.create(command("Agent建的客户"), "agent:1");

        AuditLogEntry entry = capturedEntry();
        assertEquals("agent:1", entry.operator());
        assertEquals("sess-001", entry.sessionId());
    }
}
