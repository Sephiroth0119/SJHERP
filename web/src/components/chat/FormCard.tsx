/**
 * FormCard：选项返回协议中 form 的渲染组件。
 * Agent 需要结构化输入时返回表单，前端渲染为内联表单卡片。
 * 值统一按字符串提交，金额/数量等精度敏感数据不在前端做数值运算。
 */
import { useState, type FormEvent } from 'react';
import type {
  AgentForm,
  AgentFormField,
  AgentFormFieldType,
  AgentFormValues,
} from '../../types/agent';

interface FormCardProps {
  form: AgentForm;
  /** 是否可交互：历史消息中的表单只读 */
  interactive: boolean;
  /** 提交回调：上层将其转为用户消息发送 */
  onSubmit: (form: AgentForm, values: AgentFormValues) => void;
}

/**
 * 协议字段类型 → input 属性映射（协议 v0.1：text/decimal/integer/date）。
 * decimal/integer 仍按字符串提交，前端不做数值运算（金额精度由后端 BigDecimal 保证）。
 */
function inputProps(type: AgentFormFieldType): {
  type: string;
  step?: string;
  inputMode?: 'decimal' | 'numeric';
} {
  switch (type) {
    case 'decimal':
      return { type: 'number', step: 'any', inputMode: 'decimal' };
    case 'integer':
      return { type: 'number', step: '1', inputMode: 'numeric' };
    case 'date':
      return { type: 'date' };
    default:
      return { type: 'text' };
  }
}

function initialValues(fields: AgentFormField[]): AgentFormValues {
  const values: AgentFormValues = {};
  for (const field of fields) {
    values[field.name] = field.defaultValue ?? '';
  }
  return values;
}

export function FormCard({ form, interactive, onSubmit }: FormCardProps) {
  const [values, setValues] = useState<AgentFormValues>(() => initialValues(form.fields));
  const [submitted, setSubmitted] = useState(false);

  const disabled = !interactive || submitted;

  const setField = (name: string, value: string) => {
    setValues((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (disabled) return;
    setSubmitted(true);
    onSubmit(form, values);
  };

  return (
    <form className="form-card" onSubmit={handleSubmit}>
      {form.title && <div className="form-card-title">{form.title}</div>}
      {form.fields.map((field) => (
        <label key={field.name} className="form-card-field">
          <span className="form-card-label">
            {field.label}
            {field.required && <span className="form-card-required">*</span>}
          </span>
          {field.type === 'select' ? (
            <select
              value={values[field.name] ?? ''}
              required={field.required}
              disabled={disabled}
              onChange={(e) => setField(field.name, e.target.value)}
            >
              <option value="" disabled>
                请选择
              </option>
              {(field.options ?? []).map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          ) : (
            <input
              {...inputProps(field.type)}
              value={values[field.name] ?? ''}
              required={field.required}
              placeholder={field.placeholder}
              disabled={disabled}
              onChange={(e) => setField(field.name, e.target.value)}
            />
          )}
        </label>
      ))}
      <button type="submit" className="form-card-submit" disabled={disabled}>
        {submitted ? '已提交' : (form.submitLabel ?? '提交')}
      </button>
    </form>
  );
}
