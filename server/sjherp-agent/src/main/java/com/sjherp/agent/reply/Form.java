package com.sjherp.agent.reply;

import java.util.List;
import java.util.Objects;

/**
 * 表单：Agent 需要用户一次性补充多个字段时返回（选项返回协议 v0.1）。
 *
 * <p>回传机制：用户提交后前端回传
 * {@code { "formId": id, "values": { name: 字符串值 } }}，所有值均为字符串，
 * 金额/数量由后端以 BigDecimal 解析。
 *
 * @param id           表单唯一标识，用户提交时随表单值一并回传，便于 Agent 关联上下文
 * @param title        表单标题（中文），可为 null
 * @param fields       字段定义
 * @param submitLabel  提交按钮文案，可为 null（前端缺省显示「提交」）
 * @param submitAction 表单提交后后端要执行的动作（必填）
 */
public record Form(String id, String title, List<FormField> fields,
                   String submitLabel, Action submitAction) {

    public Form {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(submitAction, "submitAction 不能为空");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /**
     * 表单字段。
     *
     * @param name         字段名（英文标识符，回传键名）
     * @param label        展示标签（中文）
     * @param type         字段类型
     * @param required     是否必填
     * @param placeholder  输入提示文案，可为 null
     * @param defaultValue 默认值（字符串形式，可为 null；金额/数量类字段
     *                     解析时一律转 BigDecimal，禁止 float/double）
     * @param options      type 为 SELECT 时必填的候选项，其余类型为空列表
     */
    public record FormField(String name, String label, FieldType type,
                            boolean required, String placeholder, String defaultValue,
                            List<SelectOption> options) {

        public FormField {
            Objects.requireNonNull(name, "name 不能为空");
            Objects.requireNonNull(label, "label 不能为空");
            Objects.requireNonNull(type, "type 不能为空");
            options = options == null ? List.of() : List.copyOf(options);
            if (type == FieldType.SELECT && options.isEmpty()) {
                throw new IllegalArgumentException("SELECT 字段必须提供候选项 options");
            }
        }
    }

    /**
     * SELECT 字段的候选项。
     *
     * @param value 选中后回传的值
     * @param label 展示文案（中文）
     */
    public record SelectOption(String value, String label) {

        public SelectOption {
            Objects.requireNonNull(value, "value 不能为空");
            Objects.requireNonNull(label, "label 不能为空");
        }
    }

    /**
     * 字段类型（前端据此选择控件）。
     *
     * <p>JSON 映射规则（协议 v0.1）：JSON 值 = 枚举名小写
     * （text/decimal/integer/date/select），反序列化时大小写不敏感。
     * 协议中不存在 number 类型——金额/数量必须用 decimal，字符串传输。
     */
    public enum FieldType {
        /** 单行文本 */
        TEXT,
        /** 数量/金额等精确小数（前端按字符串提交，后端用 BigDecimal 解析） */
        DECIMAL,
        /** 整数 */
        INTEGER,
        /** 日期（yyyy-MM-dd） */
        DATE,
        /** 下拉单选（须带 options 候选项） */
        SELECT;

        /** 协议 JSON 值（小写） */
        public String json() {
            return name().toLowerCase();
        }

        /** 从协议 JSON 值解析（大小写不敏感） */
        public static FieldType fromJson(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}
