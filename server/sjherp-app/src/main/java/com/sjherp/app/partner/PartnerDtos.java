package com.sjherp.app.partner;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierCommand;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * partner API 的请求/响应 DTO（Bean Validation 做字段级格式校验，
 * 业务规则校验仍在领域层——校验重复无害，绕过有害）。
 */
public final class PartnerDtos {

    private PartnerDtos() {
    }

    // ---------------- 客户 ----------------

    /** 客户创建/更新请求（创建时 code 为空表示自动编号 CUS-年月-序号） */
    public record CustomerRequest(
            @Size(max = 50, message = "客户编码不能超过 50 个字符") String code,
            @NotBlank(message = "客户名称不能为空") @Size(max = 200, message = "客户名称不能超过 200 个字符") String name,
            @Size(max = 64, message = "联系人不能超过 64 个字符") String contactPerson,
            @Size(max = 32, message = "联系电话不能超过 32 个字符") String contactPhone,
            @Size(max = 255, message = "地址不能超过 255 个字符") String address,
            @Size(max = 64, message = "税号不能超过 64 个字符") String taxNo,
            @NotBlank(message = "结算方式不能为空（MONTHLY / CASH / PREPAID）") String settlementMethod,
            @DecimalMin(value = "0", message = "信用额度不能为负数")
            @Digits(integer = 16, fraction = 2, message = "信用额度整数最多 16 位、小数最多 2 位")
            BigDecimal creditLimit) {

        CustomerCommand toCommand() {
            return new CustomerCommand(code, name, contactPerson, contactPhone, address, taxNo,
                    PartnerApiSupport.parseSettlementMethod(settlementMethod), creditLimit);
        }
    }

    public record CustomerResponse(long id, String code, String name, String contactPerson,
                                   String contactPhone, String address, String taxNo,
                                   String settlementMethod, BigDecimal creditLimit, String currency,
                                   String status,
                                   String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static CustomerResponse from(Customer customer) {
            return new CustomerResponse(
                    customer.getId(),
                    customer.getCode(),
                    customer.getName(),
                    customer.getContactPerson(),
                    customer.getContactPhone(),
                    customer.getAddress(),
                    customer.getTaxNo(),
                    customer.getSettlementMethod().name(),
                    customer.getCreditLimit(),
                    customer.getCurrency(),
                    customer.getStatus().name(),
                    customer.getCreatedBy(),
                    customer.getCreatedAt().toString(),
                    customer.getUpdatedBy(),
                    customer.getUpdatedAt().toString());
        }
    }

    // ---------------- 供应商 ----------------

    /** 供应商创建/更新请求（创建时 code 为空表示自动编号 SUP-年月-序号） */
    public record SupplierRequest(
            @Size(max = 50, message = "供应商编码不能超过 50 个字符") String code,
            @NotBlank(message = "供应商名称不能为空") @Size(max = 200, message = "供应商名称不能超过 200 个字符") String name,
            @Size(max = 64, message = "联系人不能超过 64 个字符") String contactPerson,
            @Size(max = 32, message = "联系电话不能超过 32 个字符") String contactPhone,
            @Size(max = 255, message = "地址不能超过 255 个字符") String address,
            @Size(max = 64, message = "税号不能超过 64 个字符") String taxNo,
            @NotBlank(message = "结算方式不能为空（MONTHLY / CASH / PREPAID）") String settlementMethod) {

        SupplierCommand toCommand() {
            return new SupplierCommand(code, name, contactPerson, contactPhone, address, taxNo,
                    PartnerApiSupport.parseSettlementMethod(settlementMethod));
        }
    }

    public record SupplierResponse(long id, String code, String name, String contactPerson,
                                   String contactPhone, String address, String taxNo,
                                   String settlementMethod, String status,
                                   String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static SupplierResponse from(Supplier supplier) {
            return new SupplierResponse(
                    supplier.getId(),
                    supplier.getCode(),
                    supplier.getName(),
                    supplier.getContactPerson(),
                    supplier.getContactPhone(),
                    supplier.getAddress(),
                    supplier.getTaxNo(),
                    supplier.getSettlementMethod().name(),
                    supplier.getStatus().name(),
                    supplier.getCreatedBy(),
                    supplier.getCreatedAt().toString(),
                    supplier.getUpdatedBy(),
                    supplier.getUpdatedAt().toString());
        }
    }

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<CustomerResponse> fromCustomers(PageResult<Customer> result) {
            return new PageResponse<>(
                    result.items().stream().map(CustomerResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        static PageResponse<SupplierResponse> fromSuppliers(PageResult<Supplier> result) {
            return new PageResponse<>(
                    result.items().stream().map(SupplierResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}
