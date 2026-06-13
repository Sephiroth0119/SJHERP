package com.sjherp.app.dataimport;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.sjherp.app.dataimport.ImportDtos.ImportResult;
import com.sjherp.app.security.CurrentUser;

/**
 * 期初数据导入 REST 控制器（M2-T09）：
 *
 * <ul>
 *   <li>POST /api/import/products        → 商品档案批量导入</li>
 *   <li>POST /api/import/customers       → 客户档案批量导入</li>
 *   <li>POST /api/import/suppliers       → 供应商档案批量导入</li>
 *   <li>POST /api/import/opening-stock   → 期初库存批量导入（OPENING，唯一合法写入口）</li>
 *   <li>GET  /api/import/templates/{type} → 模板下载（登录即可，无额外权限点）</li>
 * </ul>
 *
 * <p>写端点需要 {@code data:import} 权限（ADMIN / BOSS）；
 * 模板下载为只读，登录即可（无权限点，与查询接口通则一致）。
 *
 * <p>错误契约见 {@link ImportExceptionHandler}：
 * 文件级错误 400 {"error": "..."}；行级校验失败 200 + 结构化 {@link ImportResult}。
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    // ----------------------------------------------------------------
    // 写端点（需要 data:import 权限）
    // ----------------------------------------------------------------

    /**
     * 商品档案批量导入。
     * <p>上传 multipart/form-data，字段名 {@code file}（.xlsx）。
     * 返回 200 {@link ImportResult}（{@code success=true/false + failures[]}）。
     */
    @PreAuthorize("@perm.has('data:import')")
    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importProducts(@RequestParam("file") MultipartFile file) {
        return importService.importProducts(file, CurrentUser.operator());
    }

    /** 客户档案批量导入（同上） */
    @PreAuthorize("@perm.has('data:import')")
    @PostMapping(value = "/customers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importCustomers(@RequestParam("file") MultipartFile file) {
        return importService.importCustomers(file, CurrentUser.operator());
    }

    /** 供应商档案批量导入（同上） */
    @PreAuthorize("@perm.has('data:import')")
    @PostMapping(value = "/suppliers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importSuppliers(@RequestParam("file") MultipartFile file) {
        return importService.importSuppliers(file, CurrentUser.operator());
    }

    /**
     * 期初库存批量导入。
     * <p>写入口：{@link com.sjherp.app.inventory.InventoryAdjustmentService#opening}（OPENING 类型）。
     * 期初应收/应付按规划延至 M4，本端点仅处理库存。
     */
    @PreAuthorize("@perm.has('data:import')")
    @PostMapping(value = "/opening-stock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importOpeningStock(@RequestParam("file") MultipartFile file) {
        return importService.importOpeningStock(file, CurrentUser.operator());
    }

    // ----------------------------------------------------------------
    // 模板下载（登录即可，无权限点）
    // ----------------------------------------------------------------

    /**
     * 模板下载（GET /api/import/templates/{type}）。
     * <p>type：products / customers / suppliers / opening-stock
     * 返回 .xlsx 字节流（Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet）。
     */
    @GetMapping("/templates/{type}")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String type) {
        byte[] bytes = switch (type) {
            case "products" -> ExcelTemplateWriter.products();
            case "customers" -> ExcelTemplateWriter.customers();
            case "suppliers" -> ExcelTemplateWriter.suppliers();
            case "opening-stock" -> ExcelTemplateWriter.openingStock();
            default -> throw new IllegalArgumentException(
                    "不支持的模板类型：" + type + "（支持：products / customers / suppliers / opening-stock）");
        };
        String filename = "import-template-" + type + ".xlsx";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
