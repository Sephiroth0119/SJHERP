package com.sjherp.domain.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptRepository;
import com.sjherp.domain.collection.CollectionReceiptService;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherRepository;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderRepository;
import com.sjherp.domain.production.WorkOrderService;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementRepository;
import com.sjherp.domain.payment.PaymentDisbursementService;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceRepository;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderRepository;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptRepository;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryRepository;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceRepository;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderRepository;
import com.sjherp.domain.sales.SalesOrderService;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountRepository;
import com.sjherp.domain.stocktake.StockCountService;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferRepository;
import com.sjherp.domain.transfer.TransferService;

/**
 * 统一防删架构守卫测试（M4-T07a，设计真源 §3，路线图验收核心：「尝试删除已过账单据的所有路径都被拒」）。
 *
 * <p>财务记录只可冲销、不可物理修改/删除（CLAUDE.md 原则 2）。本测试用反射把这条不可妥协原则固化为
 * 编译期外的硬约束，覆盖全 9 单据类型（采购订单/入库/发票、销售订单/出库/发票、收款、付款、调拨、盘点）
 * 与会计凭证：
 * <ol>
 *   <li>各 {@link BusinessDocument} 子聚合无 public delete/remove 前缀方法（聚合自身不暴露物删）；</li>
 *   <li>各领域服务无 public delete/remove 前缀方法（写入口不提供物删动作）；</li>
 *   <li>各仓储接口无 delete/remove 前缀方法签名（持久层端口不暴露物删）；</li>
 *   <li>{@link DocumentStatus} 流转表：{@code COMPLETED} 仅可 →{@code REVERSED}、{@code REVERSED} 为终态
 *       （已过账/完成单据的唯一退出是冲销，无任何下游可绕开冲销改/删）。</li>
 * </ol>
 *
 * <p>采用「显式登记类清单」而非 classpath 扫描——domain 模块仅依赖 JUnit5（无 Reflections/ClassGraph），
 * 沿用本模块手写 Fake 的零额外依赖约定；新增单据/服务/仓储须同步登记到本清单（清单即验收边界）。
 * 非财务的 catalog（unit/category）允许物删，刻意不在覆盖范围内。
 */
class NoPhysicalDeleteArchitectureTest {

    /** 全 9 单据类型聚合 + 会计凭证（BusinessDocument 子类） */
    private static final List<Class<?>> AGGREGATES = List.of(
            PurchaseOrder.class, PurchaseReceipt.class, PurchaseInvoice.class,
            SalesOrder.class, SalesDelivery.class, SalesInvoice.class,
            CollectionReceipt.class, PaymentDisbursement.class,
            TransferDocument.class, StockCountDocument.class,
            Voucher.class, WorkOrder.class);

    /** 对应领域服务（唯一写入口） */
    private static final List<Class<?>> SERVICES = List.of(
            PurchaseOrderService.class, PurchaseReceiptService.class, PurchaseInvoiceService.class,
            SalesOrderService.class, SalesDeliveryService.class, SalesInvoiceService.class,
            CollectionReceiptService.class, PaymentDisbursementService.class,
            TransferService.class, StockCountService.class,
            VoucherService.class, WorkOrderService.class);

    /** 对应仓储接口（持久层端口） */
    private static final List<Class<?>> REPOSITORIES = List.of(
            PurchaseOrderRepository.class, PurchaseReceiptRepository.class, PurchaseInvoiceRepository.class,
            SalesOrderRepository.class, SalesDeliveryRepository.class, SalesInvoiceRepository.class,
            CollectionReceiptRepository.class, PaymentDisbursementRepository.class,
            TransferRepository.class, StockCountRepository.class,
            VoucherRepository.class, WorkOrderRepository.class);

    // ----------------------------------------------------- ① 聚合无物删

    @Test
    void 全单据聚合_无public删除方法() {
        AGGREGATES.forEach(NoPhysicalDeleteArchitectureTest::assertNoDeleteMethod);
    }

    // ----------------------------------------------------- ② 领域服务无物删

    @Test
    void 全领域服务_无public删除方法() {
        SERVICES.forEach(NoPhysicalDeleteArchitectureTest::assertNoDeleteMethod);
    }

    // ----------------------------------------------------- ③ 仓储接口无物删签名

    @Test
    void 全仓储接口_无删除方法签名() {
        REPOSITORIES.forEach(NoPhysicalDeleteArchitectureTest::assertNoDeleteMethod);
    }

    // ----------------------------------------------------- ④ DocumentStatus 流转表收口

    @Test
    void 状态机_COMPLETED仅可冲销() {
        // 已完成（过账）单据唯一退出是冲销——不可改、不可删、不可回退
        assertEquals(Set.of(DocumentStatus.REVERSED), DocumentStatus.COMPLETED.allowedTargets(),
                "COMPLETED 的合法下游必须恰为 {REVERSED}");
        assertTrue(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.REVERSED));
        assertFalse(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.DRAFT));
        assertFalse(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.APPROVED));
        assertFalse(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.CANCELLED));
    }

    @Test
    void 状态机_REVERSED为终态_无下游() {
        assertTrue(DocumentStatus.REVERSED.allowedTargets().isEmpty(),
                "REVERSED 必须为终态（无任何下游流转）");
        assertTrue(DocumentStatus.REVERSED.isTerminal());
    }

    @Test
    void 状态机_APPROVED与EXECUTING也只能冲销退出_无作废回退() {
        // 已审核/执行中（已产生业务影响）也只能冲销退出，不能作废（CANCELLED 仅限 DRAFT）
        assertFalse(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.CANCELLED));
        assertTrue(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.REVERSED));
        assertFalse(DocumentStatus.EXECUTING.canTransitionTo(DocumentStatus.CANCELLED));
        assertTrue(DocumentStatus.EXECUTING.canTransitionTo(DocumentStatus.REVERSED));
    }

    // ----------------------------------------------------- 工具

    /**
     * 断言某类（及其继承的 public 方法）无 delete/remove 前缀方法。
     * 用 {@link Class#getMethods()}（含继承）兜底——基类 {@link BusinessDocument} 若引入物删也会被本断言捕获。
     */
    private static void assertNoDeleteMethod(Class<?> type) {
        // getMethods 含继承链——只把声明在 com.sjherp 包内的 delete/remove 前缀方法视为违例
        // （排除 JDK / 集合等同名方法；基类 BusinessDocument 若引入物删也在 com.sjherp 内会被捕获）
        List<String> offenders = java.util.Arrays.stream(type.getMethods())
                .filter(m -> m.getName().startsWith("delete") || m.getName().startsWith("remove"))
                .filter(m -> m.getDeclaringClass().getName().startsWith("com.sjherp"))
                .map(Method::getName)
                .distinct()
                .collect(Collectors.toList());
        assertTrue(offenders.isEmpty(),
                type.getName() + " 不得暴露物理删除方法（财务记录只可冲销不可删，CLAUDE.md 原则 2），"
                        + "发现: " + offenders);
    }
}
