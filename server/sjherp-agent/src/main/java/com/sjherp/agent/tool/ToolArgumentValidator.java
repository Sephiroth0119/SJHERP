package com.sjherp.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具参数校验接口（M1-T03 安全壳：执行前按工具 JSON Schema 校验参数）。
 *
 * <p>sjherp-agent 模块零依赖，这里只定义接口并提供极简默认实现
 * {@link RequiredFieldsToolArgumentValidator}（必填字段存在性检查，朴素解析
 * schema 中的 required 列表）；完整的 JSON Schema 基础校验（type / required / enum）
 * 由 sjherp-infra 的 JsonSchemaToolArgumentValidator 提供（Jackson 手写，不引新库）。
 */
public interface ToolArgumentValidator {

    /**
     * 校验调用参数是否满足工具的参数 schema。
     *
     * @param parameterSchemaJson 工具声明的 JSON Schema 字符串（可为 null / 空白 = 无约束）
     * @param arguments           已解析的调用参数键值对
     * @return 校验错误列表（面向 LLM 的中文描述）；空列表表示校验通过。
     *         执行循环对非空结果不执行工具，把错误回灌给模型自行调整。
     */
    List<String> validate(String parameterSchemaJson, Map<String, Object> arguments);
}
