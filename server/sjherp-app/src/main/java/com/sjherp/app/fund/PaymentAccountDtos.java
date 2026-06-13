package com.sjherp.app.fund;

import java.util.List;
import java.util.Locale;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * payment_account API 的请求/响应 DTO（Bean Validation 做字段级格式校验，
 * 业务规则校验仍在领域层——校验重复无害，绕过有害）。
 *
 * <p>口径同 warehouse：字段以字符串呈现，状态/类别用英文枚举值传入并解析为枚举。
 */
public final class PaymentAccountDtos {

    private PaymentAccountDtos() {
    }

    /**
     * 资金账户创建/更新请求（创建时 code 为空表示自动编号 FA-年月-序号）。
     * accountType 取 CASH/BANK/OTHER；glAccountCode 必填且须为已存在/启用/末级 GL 科目。
     */
    public record PaymentAccountRequest(
            @Size(max = 50, message = "资金账户编码不能超过 50 个字符") String code,
            @NotBlank(message = "资金账户名称不能为空") @Size(max = 200, message = "资金账户名称不能超过 200 个字符") String name,
            @NotNull(message = "账户类别不能为空") String accountType,
            @NotBlank(message = "映射的 GL 科目编码不能为空") @Size(max = 32, message = "GL 科目编码不能超过 32 个字符") String glAccountCode,
            @Size(max = 200, message = "开户行不能超过 200 个字符") String bankName,
            @Size(max = 64, message = "银行账号不能超过 64 个字符") String accountNo) {

        PaymentAccountCommand toCommand() {
            return new PaymentAccountCommand(code, name, parseType(accountType), glAccountCode,
                    bankName, accountNo);
        }

        /** 类别解析（非法值给出友好 400 信息，不透出枚举内部异常） */
        private static PaymentAccountType parseType(String accountType) {
            if (accountType == null || accountType.isBlank()) {
                throw new IllegalArgumentException("账户类别不能为空");
            }
            try {
                return PaymentAccountType.valueOf(accountType.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("账户类别仅支持 CASH / BANK / OTHER: " + accountType);
            }
        }
    }

    public record PaymentAccountResponse(long id, String code, String name, String accountType,
                                         String accountTypeLabel, String glAccountCode, String bankName,
                                         String accountNo, String status,
                                         String createdBy, String createdAt, String updatedBy,
                                         String updatedAt) {

        static PaymentAccountResponse from(PaymentAccount account) {
            return new PaymentAccountResponse(
                    account.getId(),
                    account.getCode(),
                    account.getName(),
                    account.getAccountType().name(),
                    account.getAccountType().label(),
                    account.getGlAccountCode(),
                    account.getBankName(),
                    account.getAccountNo(),
                    account.getStatus().name(),
                    account.getCreatedBy(),
                    account.getCreatedAt().toString(),
                    account.getUpdatedBy(),
                    account.getUpdatedAt().toString());
        }
    }

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<PaymentAccountResponse> fromAccounts(PageResult<PaymentAccount> result) {
            return new PageResponse<>(
                    result.items().stream().map(PaymentAccountResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}
