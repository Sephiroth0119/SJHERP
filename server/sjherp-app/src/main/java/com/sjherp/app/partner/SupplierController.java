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

import com.sjherp.app.partner.PartnerDtos.PageResponse;
import com.sjherp.app.partner.PartnerDtos.SupplierRequest;
import com.sjherp.app.partner.PartnerDtos.SupplierResponse;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.domain.partner.SupplierService;

import jakarta.validation.Valid;

/**
 * 供应商档案 API：
 * <ul>
 *   <li>POST   /api/partner/suppliers → 201 供应商（code 留空自动编号 SUP-年月-序号）</li>
 *   <li>PUT    /api/partner/suppliers/{id} → 200 供应商</li>
 *   <li>POST   /api/partner/suppliers/{id}/enable|disable → 200 供应商（启停）</li>
 *   <li>GET    /api/partner/suppliers?keyword=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/partner/suppliers/{id} → 200 供应商，不存在 404 {"error"}</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link PartnerExceptionHandler}）。
 */
@RestController
@RequestMapping("/api/partner/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    /** 创建供应商（code 留空自动编号） */
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        SupplierResponse body = SupplierResponse.from(
                supplierService.create(request.toCommand(), PartnerApiSupport.OPERATOR));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新供应商（整体更新；更新时编码必填） */
    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable long id, @Valid @RequestBody SupplierRequest request) {
        return SupplierResponse.from(
                supplierService.update(id, request.toCommand(), PartnerApiSupport.OPERATOR));
    }

    /** 启用供应商 */
    @PostMapping("/{id}/enable")
    public SupplierResponse enable(@PathVariable long id) {
        return SupplierResponse.from(supplierService.enable(id, PartnerApiSupport.OPERATOR));
    }

    /** 停用供应商（停用后新单据不得引用，历史数据不受影响） */
    @PostMapping("/{id}/disable")
    public SupplierResponse disable(@PathVariable long id) {
        return SupplierResponse.from(supplierService.disable(id, PartnerApiSupport.OPERATOR));
    }

    /** 分页列表（keyword 模糊匹配编码/名称/联系人/电话；status 可选 ENABLED/DISABLED） */
    @GetMapping
    public PageResponse<SupplierResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromSuppliers(
                supplierService.search(new SupplierQuery(keyword, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable long id) {
        return SupplierResponse.from(supplierService.get(id));
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
