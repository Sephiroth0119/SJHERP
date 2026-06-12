package com.sjherp.app.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.catalog.CatalogDtos.CategoryCreateRequest;
import com.sjherp.app.catalog.CatalogDtos.CategoryRenameRequest;
import com.sjherp.app.catalog.CatalogDtos.CategoryResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.catalog.CategoryService;

import jakarta.validation.Valid;

/**
 * 商品类目 API（树形档案，最多 3 层在领域层校验）：
 * <ul>
 *   <li>POST   /api/catalog/categories → 201 类目</li>
 *   <li>PUT    /api/catalog/categories/{id} → 200 类目（仅重命名，父类目/层级固化）</li>
 *   <li>DELETE /api/catalog/categories/{id} → 204（有子类目或被商品引用则 400）</li>
 *   <li>GET    /api/catalog/categories → 200 全量列表（树由前端按 parentId 组装）</li>
 *   <li>GET    /api/catalog/categories/{id} → 200 类目，不存在 404 {"error"}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/catalog/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 创建类目（parentId 留空为根类目）；写操作须 catalog:write（M2-T06） */
    @PreAuthorize("@perm.has('catalog:write')")
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        CategoryResponse body = CategoryResponse.from(
                categoryService.create(request.name(), request.parentId(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 重命名类目 */
    @PreAuthorize("@perm.has('catalog:write')")
    @PutMapping("/{id}")
    public CategoryResponse rename(@PathVariable long id, @Valid @RequestBody CategoryRenameRequest request) {
        return CategoryResponse.from(categoryService.rename(id, request.name(), CurrentUser.operator()));
    }

    /** 删除类目（有子类目或被商品引用则拒绝） */
    @PreAuthorize("@perm.has('catalog:write')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 全量列表（按层级+id 排序，父先于子，便于前端组树） */
    @GetMapping
    public List<CategoryResponse> findAll() {
        return categoryService.findAll().stream().map(CategoryResponse::from).toList();
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public CategoryResponse get(@PathVariable long id) {
        return CategoryResponse.from(categoryService.get(id));
    }
}
