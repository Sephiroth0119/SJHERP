package com.sjherp.agent.reply;

import java.util.Map;
import java.util.Objects;

/**
 * 动作：选项被点中 / 表单被提交后，后端要执行的操作（选项返回协议 v0.1）。
 *
 * <p>action 由后端声明并随回复下发，但前端不解析、不回传——点击选项时
 * 前端只回传 optionId，后端凭会话中最近一条回复按 id 还原 action 执行，
 * 防止前端伪造动作参数。
 *
 * @param type   动作类型（英文标识符，如 CREATE_PURCHASE_ORDER）
 * @param params 动作参数。金额/数量一律字符串传输，后端以 BigDecimal 解析
 *               （CLAUDE.md 原则：禁止 float/double 参与金额运算）
 */
public record Action(String type, Map<String, String> params) {

    public Action {
        Objects.requireNonNull(type, "type 不能为空");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 无参数动作 */
    public static Action of(String type) {
        return new Action(type, Map.of());
    }
}
