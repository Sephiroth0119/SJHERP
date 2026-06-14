package com.sjherp.app.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.sjherp.domain.receivable.ReceivableService;
import com.sjherp.domain.sales.ReceivablePostingPort;

/**
 * 销售发票挂应收端口的 app 实现（M3-T10）：把领域 {@link ReceivablePostingPort} 转调
 * 应收领域服务 {@link ReceivableService#open}（保持销售与应收两聚合解耦）。
 *
 * <p>事务边界由 {@code SalesInvoiceAppService} 写方法的外层事务保证：发票状态变更与应收挂账
 * 原子提交。应收挂账同来源发票号幂等（应收服务 findBySourceDocNo 兜底）。本适配器只做透传。
 */
public class ReceivablePostingAdapter implements ReceivablePostingPort {

    private final ReceivableService receivableService;

    public ReceivablePostingAdapter(ReceivableService receivableService) {
        this.receivableService = Objects.requireNonNull(receivableService, "receivableService 不能为空");
    }

    @Override
    public void open(long customerId, BigDecimal amount, String sourceDocNo, LocalDate dueDate,
                     String operator) {
        receivableService.open(customerId, amount, sourceDocNo, dueDate, operator);
    }

    @Override
    public void reverse(String sourceDocNo, String operator) {
        receivableService.reverse(sourceDocNo, operator);
    }
}
