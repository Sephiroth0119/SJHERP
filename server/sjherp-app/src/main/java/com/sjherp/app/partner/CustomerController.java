package com.sjherp.app.partner;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.partner.PartnerDtos.CustomerRequest;
import com.sjherp.app.partner.PartnerDtos.CustomerResponse;
import com.sjherp.app.partner.PartnerDtos.PageResponse;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.partner.CustomerQuery;
import com.sjherp.domain.partner.CustomerService;

import jakarta.validation.Valid;

/**
 * 客户档案 API：
 * <ul>
 *   <li>POST   /api/partner/customers → 201 客户（code 留空自动编号 CUS-年月-序号）</li>
 *   <li>PUT    /api/partner/customers/{id} → 200 客户</li>
 *   <li>POST   /api/partner/customers/{id}/enable|disable → 200 客户（启停）</li>
 *   <li>GET    /api/partner/customers?keyword=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/partner/customers/{id} → 200 客户，不存在 404 {"error"}</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link PartnerExceptionHandler}）。
 */
@RestController
@RequestMapping("/api/partner/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** 创建客户（code 留空自动编号） */
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse body = CustomerResponse.from(
                customerService.create(request.toCommand(), PartnerApiSupport.OPERATOR));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新客户（整体更新；更新时编码必填） */
    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable long id, @Valid @RequestBody CustomerRequest request) {
        return CustomerResponse.from(
                customerService.update(id, request.toCommand(), PartnerApiSupport.OPERATOR));
    }

    /** 启用客户 */
    @PostMapping("/{id}/enable")
    public CustomerResponse enable(@PathVariable long id) {
        return CustomerResponse.from(customerService.enable(id, PartnerApiSupport.OPERATOR));
    }

    /** 停用客户（停用后新单据不得引用，历史数据不受影响） */
    @PostMapping("/{id}/disable")
    public CustomerResponse disable(@PathVariable long id) {
        return CustomerResponse.from(customerService.disable(id, PartnerApiSupport.OPERATOR));
    }

    /** 分页列表（keyword 模糊匹配编码/名称/联系人/电话；status 可选 ENABLED/DISABLED） */
    @GetMapping
    public PageResponse<CustomerResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromCustomers(
                customerService.search(new CustomerQuery(keyword, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable long id) {
        return CustomerResponse.from(customerService.get(id));
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
