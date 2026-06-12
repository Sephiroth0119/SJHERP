package com.sjherp.app.catalog;

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

import com.sjherp.app.catalog.CatalogDtos.PageResponse;
import com.sjherp.app.catalog.CatalogDtos.ProductRequest;
import com.sjherp.app.catalog.CatalogDtos.ProductResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;

import jakarta.validation.Valid;

/**
 * 商品档案 API：
 * <ul>
 *   <li>POST   /api/catalog/products → 201 商品（code 留空自动编号 SKU-年月-序号）</li>
 *   <li>PUT    /api/catalog/products/{id} → 200 商品</li>
 *   <li>POST   /api/catalog/products/{id}/enable|disable → 200 商品（启停）</li>
 *   <li>GET    /api/catalog/products?keyword=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/catalog/products/{id} → 200 商品，不存在 404 {"error"}</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link CatalogExceptionHandler}）。
 */
@RestController
@RequestMapping("/api/catalog/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** 创建商品（code 留空自动编号） */
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse body = ProductResponse.from(
                productService.create(request.toCommand(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新商品（整体更新，含换算表替换；更新时编码必填） */
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(
                productService.update(id, request.toCommand(), CurrentUser.operator()));
    }

    /** 启用商品 */
    @PostMapping("/{id}/enable")
    public ProductResponse enable(@PathVariable long id) {
        return ProductResponse.from(productService.enable(id, CurrentUser.operator()));
    }

    /** 停用商品（停用后新单据不得引用，历史数据不受影响） */
    @PostMapping("/{id}/disable")
    public ProductResponse disable(@PathVariable long id) {
        return ProductResponse.from(productService.disable(id, CurrentUser.operator()));
    }

    /** 分页列表（keyword 模糊匹配编码/名称/条码；status 可选 ENABLED/DISABLED） */
    @GetMapping
    public PageResponse<ProductResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromProducts(
                productService.search(new ProductQuery(keyword, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable long id) {
        return ProductResponse.from(productService.get(id));
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
