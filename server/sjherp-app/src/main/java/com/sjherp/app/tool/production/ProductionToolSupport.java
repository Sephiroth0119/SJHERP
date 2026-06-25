package com.sjherp.app.tool.production;

import java.math.BigDecimal;

/**
 * 生产类 Agent 工具公共助手（M5-T07）：工单/领料/退料/报工/成本结转各工具的
 * 参数解析公共方法，包私有不对外暴露。
 *
 * <p>设计原则：方法保持纯函数（无副作用），异常消息面向 LLM 回灌。
 */
final class ProductionToolSupport {

    private ProductionToolSupport() {
    }

    /**
     * 参数值 → BigDecimal（协议约定 decimal 用字符串承载；
     * 空返回 null，非法抛 IllegalArgumentException）。
     */
    static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值格式不合法: " + text);
        }
    }

    /**
     * 参数值 → long（ID 字段，Number 或字符串均可；null 或空抛 IllegalArgumentException）。
     */
    static long longId(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " 必须是整数 ID：" + text);
        }
    }

    /**
     * 参数值 → Integer（可选整数字段；null 或空返回 null）。
     */
    static Integer intVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("整数格式不合法: " + text);
        }
    }
}
