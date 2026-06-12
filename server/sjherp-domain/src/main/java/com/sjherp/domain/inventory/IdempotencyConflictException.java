package com.sjherp.domain.inventory;

import java.util.Objects;

/**
 * 幂等键冲突领域异常（拆解 §1.3）：同 idempotencyKey 但参数不一致时抛出，
 * 防止幂等键误用导致静默吞单（同键同参才返回首次结果，见 {@link InventoryService}）。
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey, String detail) {
        super("幂等键冲突：同键不同参，拒绝过账以防吞单。key=" + idempotencyKey + "，差异：" + detail);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey 不能为空");
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
