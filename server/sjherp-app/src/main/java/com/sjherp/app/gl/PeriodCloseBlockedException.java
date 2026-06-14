package com.sjherp.app.gl;

import java.util.List;
import java.util.Objects;

/**
 * 月末关账被闸门拒绝（M4-T05）：在 {@link PeriodCloseService#close} 的前置校验阶段抛出，
 * 携带<b>不能关账的原因清单</b>（账期非 OPEN / 已存在结转凭证 / 结转前一致性 ERROR break 等），
 * 便于向导（前端）与 Agent 复述"为什么这个账期现在不能关"。
 *
 * <p>继承 {@link IllegalStateException}（关账是状态约束类拒绝）；映射 HTTP 409
 * （{@link GlExceptionHandler}，体含 {@code reasons} 列表）。语义上区别于
 * {@link IllegalStateException}（账期重复关账/重开）——本异常专用于关账编排的可恢复前置闸门，
 * 用户/Agent 据 reasons 先治理脏数据/先冲销再重试。
 */
public class PeriodCloseBlockedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** 不能关账的原因清单（≥1 条，与 message 同步；不可变） */
    private final List<String> reasons;

    public PeriodCloseBlockedException(String message, List<String> reasons) {
        super(message);
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** 单原因便捷构造（message 即唯一原因） */
    public PeriodCloseBlockedException(String message) {
        this(Objects.requireNonNull(message, "message 不能为空"), List.of(message));
    }

    /** 不能关账的原因清单（不可变，至少 1 条） */
    public List<String> getReasons() {
        return reasons;
    }
}
