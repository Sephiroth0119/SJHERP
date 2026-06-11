package com.sjherp.app.catalog;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.catalog.Category;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.common.PageResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * catalog API 的请求/响应 DTO（Bean Validation 做字段级格式校验，
 * 业务规则校验仍在领域层——校验重复无害，绕过有害）。
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    // ---------------- 商品 ----------------

    /** 商品创建/更新请求（创建时 code 为空表示自动编号 SKU-年月-序号） */
    public record ProductRequest(
            @Size(max = 50, message = "商品编码不能超过 50 个字符") String code,
            @NotBlank(message = "商品名称不能为空") @Size(max = 200, message = "商品名称不能超过 200 个字符") String name,
            @Size(max = 200, message = "规格不能超过 200 个字符") String spec,
            Long categoryId,
            @NotNull(message = "基本单位不能为空") Long baseUnitId,
            @Size(max = 64, message = "条码不能超过 64 个字符") String barcode,
            @Size(max = 500, message = "备注不能超过 500 个字符") String remark,
            @Valid List<ConversionItem> unitConversions) {

        ProductCommand toCommand() {
            List<UnitConversion> conversions = unitConversions == null ? List.of()
                    : unitConversions.stream()
                            .map(item -> new UnitConversion(item.unitId(), item.rate()))
                            .toList();
            return new ProductCommand(code, name, spec, categoryId, baseUnitId, barcode, remark, conversions);
        }
    }

    /** 多单位换算项：1 换算单位 = rate 基本单位（换算率 BigDecimal，最多 6 位小数） */
    public record ConversionItem(
            @NotNull(message = "换算单位不能为空") Long unitId,
            @NotNull(message = "换算率不能为空")
            @DecimalMin(value = "0", inclusive = false, message = "换算率必须大于 0")
            @Digits(integer = 12, fraction = 6, message = "换算率整数最多 12 位、小数最多 6 位")
            BigDecimal rate) {
    }

    public record ProductResponse(long id, String code, String name, String spec, Long categoryId,
                           long baseUnitId, String barcode, String status, String remark,
                           List<ConversionItem> unitConversions,
                           String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.getId(),
                    product.getCode(),
                    product.getName(),
                    product.getSpec(),
                    product.getCategoryId(),
                    product.getBaseUnitId(),
                    product.getBarcode(),
                    product.getStatus().name(),
                    product.getRemark(),
                    product.getUnitConversions().stream()
                            .map(c -> new ConversionItem(c.unitId(), c.rate()))
                            .toList(),
                    product.getCreatedBy(),
                    product.getCreatedAt().toString(),
                    product.getUpdatedBy(),
                    product.getUpdatedAt().toString());
        }
    }

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<ProductResponse> fromProducts(PageResult<Product> result) {
            return new PageResponse<>(
                    result.items().stream().map(ProductResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    // ---------------- 类目 ----------------

    /** 类目创建请求（parentId 为空表示根类目；最多 3 层在领域层校验） */
    public record CategoryCreateRequest(
            @NotBlank(message = "类目名称不能为空") @Size(max = 100, message = "类目名称不能超过 100 个字符") String name,
            Long parentId) {
    }

    /** 类目更新请求（仅支持重命名；父类目与层级创建时固化） */
    public record CategoryRenameRequest(
            @NotBlank(message = "类目名称不能为空") @Size(max = 100, message = "类目名称不能超过 100 个字符") String name) {
    }

    public record CategoryResponse(long id, String name, Long parentId, int level,
                            String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static CategoryResponse from(Category category) {
            return new CategoryResponse(category.getId(), category.getName(), category.getParentId(),
                    category.getLevel(), category.getCreatedBy(), category.getCreatedAt().toString(),
                    category.getUpdatedBy(), category.getUpdatedAt().toString());
        }
    }

    // ---------------- 计量单位 ----------------

    /** 单位创建/更新请求（precision = 以该单位计量时数量保留的小数位数 0-6） */
    public record UnitRequest(
            @NotBlank(message = "单位名称不能为空") @Size(max = 50, message = "单位名称不能超过 50 个字符") String name,
            @NotNull(message = "单位精度不能为空")
            @Min(value = 0, message = "单位精度必须在 0-6 之间")
            @Max(value = 6, message = "单位精度必须在 0-6 之间")
            Integer precision) {
    }

    public record UnitResponse(long id, String name, int precision,
                        String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static UnitResponse from(Unit unit) {
            return new UnitResponse(unit.getId(), unit.getName(), unit.getPrecision(),
                    unit.getCreatedBy(), unit.getCreatedAt().toString(),
                    unit.getUpdatedBy(), unit.getUpdatedAt().toString());
        }
    }
}
