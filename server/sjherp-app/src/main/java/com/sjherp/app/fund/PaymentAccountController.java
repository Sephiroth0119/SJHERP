package com.sjherp.app.fund;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.fund.PaymentAccountDtos.PageResponse;
import com.sjherp.app.fund.PaymentAccountDtos.PaymentAccountRequest;
import com.sjherp.app.fund.PaymentAccountDtos.PaymentAccountResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountService;

import jakarta.validation.Valid;

/**
 * 资金账户档案 API（M4-T04a，照 {@link com.sjherp.app.warehouse.WarehouseController}）：
 * <ul>
 *   <li>POST   /api/fund/accounts → 201 资金账户（code 留空自动编号 FA-年月-序号）</li>
 *   <li>PUT    /api/fund/accounts/{id} → 200 资金账户</li>
 *   <li>POST   /api/fund/accounts/{id}/enable|disable → 200 资金账户（启停）</li>
 *   <li>GET    /api/fund/accounts?keyword=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/fund/accounts/{id} → 200 资金账户，不存在 404 {"error"}</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link PaymentAccountExceptionHandler}）。
 *
 * <p>权限（M4-T04a，矩阵见 docs/权限矩阵.md）：所有<b>写</b>操作（创建/更新/启停）须
 * finance:payment_account；查询登录即可。glAccountCode 必须是已存在/启用/末级 GL 科目，
 * 否则领域服务抛 IllegalArgumentException → 400。
 */
@RestController
@RequestMapping("/api/fund/accounts")
public class PaymentAccountController {

    private final PaymentAccountService paymentAccountService;

    public PaymentAccountController(PaymentAccountService paymentAccountService) {
        this.paymentAccountService = paymentAccountService;
    }

    /** 创建资金账户（code 留空自动编号） */
    @PreAuthorize("@perm.has('finance:payment_account')")
    @PostMapping
    public ResponseEntity<PaymentAccountResponse> create(@Valid @RequestBody PaymentAccountRequest request) {
        PaymentAccountResponse body = PaymentAccountResponse.from(
                paymentAccountService.create(request.toCommand(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新资金账户（整体更新；更新时编码必填） */
    @PreAuthorize("@perm.has('finance:payment_account')")
    @PutMapping("/{id}")
    public PaymentAccountResponse update(@PathVariable long id,
                                         @Valid @RequestBody PaymentAccountRequest request) {
        return PaymentAccountResponse.from(
                paymentAccountService.update(id, request.toCommand(), CurrentUser.operator()));
    }

    /** 启用资金账户 */
    @PreAuthorize("@perm.has('finance:payment_account')")
    @PostMapping("/{id}/enable")
    public PaymentAccountResponse enable(@PathVariable long id) {
        return PaymentAccountResponse.from(paymentAccountService.enable(id, CurrentUser.operator()));
    }

    /** 停用资金账户（停用后新单据不得引用，历史数据不受影响） */
    @PreAuthorize("@perm.has('finance:payment_account')")
    @PostMapping("/{id}/disable")
    public PaymentAccountResponse disable(@PathVariable long id) {
        return PaymentAccountResponse.from(paymentAccountService.disable(id, CurrentUser.operator()));
    }

    /** 分页列表（keyword 模糊匹配编码/名称/开户行；status 可选 ENABLED/DISABLED；查询登录即可） */
    @GetMapping
    public PageResponse<PaymentAccountResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromAccounts(
                paymentAccountService.search(new PaymentAccountQuery(keyword, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404；查询登录即可） */
    @GetMapping("/{id}")
    public PaymentAccountResponse get(@PathVariable long id) {
        return PaymentAccountResponse.from(paymentAccountService.get(id));
    }

    /** 状态过滤参数解析（非法值给出友好 400 信息，不透出枚举内部异常） */
    private static ArchiveStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ArchiveStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 仅支持 ENABLED / DISABLED: " + status);
        }
    }
}
