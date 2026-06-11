package com.sjherp.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ToolArgumentValidator} 的极简默认实现：只做必填字段存在性检查。
 *
 * <p>零依赖约束下不引 JSON 库，对 schema 字符串做朴素解析：取第一个
 * {@code "required"} 后面的 {@code [...]} 数组中的字符串字面量作为必填字段名。
 * 已知局限（接受）：嵌套对象自带的 required、或 description 文本里恰好出现
 * "required" 时可能误判——完整校验请用 infra 的 JsonSchemaToolArgumentValidator。
 */
public final class RequiredFieldsToolArgumentValidator implements ToolArgumentValidator {

    /** JSON 字符串字面量（含转义） */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Override
    public List<String> validate(String parameterSchemaJson, Map<String, Object> arguments) {
        List<String> errors = new ArrayList<>();
        for (String name : extractRequiredNames(parameterSchemaJson)) {
            Object value = arguments == null ? null : arguments.get(name);
            if (value == null) {
                errors.add("缺少必填参数: " + name);
            }
        }
        return errors;
    }

    /** 朴素提取 schema 顶层 required 数组中的字段名 */
    static List<String> extractRequiredNames(String schema) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        int keyIndex = schema.indexOf("\"required\"");
        if (keyIndex < 0) {
            return List.of();
        }
        int open = schema.indexOf('[', keyIndex);
        int close = open < 0 ? -1 : schema.indexOf(']', open);
        if (open < 0 || close < 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(schema.substring(open + 1, close));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
