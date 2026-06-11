package com.sjherp.infra.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sjherp.agent.tool.ToolArgumentValidator;

/**
 * {@link ToolArgumentValidator} 的 Jackson 实现（M1-T03 安全壳的完整参数校验）。
 *
 * <p>按工具声明的 JSON Schema 对模型给出的调用参数做<b>基础校验</b>：
 * <ul>
 *   <li>required：顶层必填字段存在性；</li>
 *   <li>type：顶层字段类型（string / number / integer / boolean / object / array）；</li>
 *   <li>enum：顶层字段枚举值约束；</li>
 *   <li>additionalProperties=false 时拒绝未声明的多余字段。</li>
 * </ul>
 * 刻意不引 JSON Schema 校验库（CLAUDE.md：不引入未登记依赖）——手写覆盖
 * 工具参数场景足够的子集；嵌套对象内部的深层校验由工具实现自行负责。
 *
 * <p>错误消息面向 LLM（中文描述），执行循环把非空结果回灌给模型自行修正参数。
 */
public final class JsonSchemaToolArgumentValidator implements ToolArgumentValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<String> validate(String parameterSchemaJson, Map<String, Object> arguments) {
        if (parameterSchemaJson == null || parameterSchemaJson.isBlank()) {
            return List.of(); // 无 schema = 无约束
        }
        JsonNode schema;
        try {
            schema = mapper.readTree(parameterSchemaJson);
        } catch (JsonProcessingException e) {
            // 工具声明本身有错（装配期问题）：作为校验错误暴露，不让循环崩溃
            return List.of("工具参数 schema 定义不是合法 JSON，无法校验参数（请联系开发者修复工具声明）");
        }
        if (!schema.isObject()) {
            return List.of();
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        List<String> errors = new ArrayList<>();

        // 1) required：顶层必填字段存在性
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                if (args.get(name.asText()) == null) {
                    errors.add("缺少必填参数: " + name.asText());
                }
            }
        }

        // 2) type / enum：逐个校验已声明且实际传入的字段
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                JsonNode property = properties.path(entry.getKey());
                if (property.isMissingNode() || entry.getValue() == null) {
                    continue;
                }
                validateType(entry.getKey(), entry.getValue(), property.path("type"), errors);
                validateEnum(entry.getKey(), entry.getValue(), property.path("enum"), errors);
            }
        }

        // 3) additionalProperties=false：拒绝未声明字段
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isBoolean() && !additional.asBoolean() && properties.isObject()) {
            for (String key : args.keySet()) {
                if (!properties.has(key)) {
                    errors.add("不允许的多余参数: " + key);
                }
            }
        }
        return errors;
    }

    /** 顶层字段类型校验（schema type 可为字符串或字符串数组） */
    private static void validateType(String name, Object value, JsonNode typeNode, List<String> errors) {
        if (typeNode.isMissingNode() || typeNode.isNull()) {
            return;
        }
        List<String> allowedTypes = new ArrayList<>();
        if (typeNode.isTextual()) {
            allowedTypes.add(typeNode.asText());
        } else if (typeNode.isArray()) {
            typeNode.forEach(t -> allowedTypes.add(t.asText()));
        }
        if (allowedTypes.isEmpty()) {
            return;
        }
        for (String type : allowedTypes) {
            if (matchesType(value, type)) {
                return;
            }
        }
        errors.add("参数 " + name + " 类型错误: 期望 " + String.join("/", allowedTypes)
                + "，实际为 " + actualTypeName(value));
    }

    /** Java 值（Jackson 解析产物）是否匹配 JSON Schema 类型名 */
    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            // integer：接受整型，以及无小数部分的浮点表示（模型偶尔输出 3.0）
            case "integer" -> isIntegralNumber(value);
            case "number" -> value instanceof Number;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "null" -> value == null;
            default -> true; // 未知类型名不拦截（宽进严出，避免误杀）
        };
    }

    private static boolean isIntegralNumber(Object value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof Short || value instanceof Byte) {
            return true;
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d);
        }
        return false;
    }

    /** 枚举值约束（enum 元素可为字符串 / 数字 / 布尔） */
    private static void validateEnum(String name, Object value, JsonNode enumNode, List<String> errors) {
        if (!enumNode.isArray() || enumNode.isEmpty()) {
            return;
        }
        List<String> allowed = new ArrayList<>();
        for (JsonNode candidate : enumNode) {
            allowed.add(candidate.asText());
            if (equalsJsonValue(value, candidate)) {
                return;
            }
        }
        errors.add("参数 " + name + " 取值非法: 必须是 [" + String.join(", ", allowed) + "] 之一，实际为 " + value);
    }

    private static boolean equalsJsonValue(Object value, JsonNode candidate) {
        if (candidate.isTextual()) {
            return candidate.asText().equals(value);
        }
        if (candidate.isBoolean()) {
            return value instanceof Boolean b && b == candidate.asBoolean();
        }
        if (candidate.isNumber()) {
            return value instanceof Number n && candidate.decimalValue().compareTo(
                    new java.math.BigDecimal(n.toString())) == 0;
        }
        return false;
    }

    private static String actualTypeName(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return isIntegralNumber(value) ? "integer" : "number";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
