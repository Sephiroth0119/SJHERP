/**
 * 选项返回协议 v0.1 —— 前后端核心契约的前端侧定义。
 *
 * 对应 docs/选项返回协议.md 与后端 server/sjherp-agent/.../reply/，
 * 本文件须与之保持严格一致；字段变更必须升协议版本并三处同步修订。
 */

/** 协议版本号：与后端 AgentReply.PROTOCOL_VERSION、docs/ 协议文档保持一致 */
export const AGENT_PROTOCOL_VERSION = '0.1';

/**
 * 选项级风险标记（Human-in-the-loop）：
 * - normal：普通操作（默认）
 * - high：高风险操作（资金、过账、关账等），前端用醒目样式渲染；
 *   只允许出现在 requiresConfirmation=true 的回复中
 *
 * JSON 映射规则：JSON 小写 ↔ 后端枚举 RiskLevel.NORMAL/HIGH（枚举名小写）。
 */
export type AgentRiskLevel = 'normal' | 'high';

/**
 * 动作：选项被点中 / 表单被提交后，后端要执行的操作。
 * 前端不解析、不回传——点击选项只回传 optionId，action 由后端按 id 还原执行。
 */
export interface AgentAction {
  /** 动作类型（英文标识符，如 CREATE_PURCHASE_ORDER） */
  type: string;
  /** 动作参数；金额/数量一律字符串传输，后端以 BigDecimal 解析 */
  params?: Record<string, string>;
}

/** 单个可点击选项卡片 */
export interface AgentOption {
  /** 选项唯一标识（一条回复内不重复）。点击后回传给后端的就是它 */
  id: string;
  /** 卡片主标题（用户可见，中文） */
  label: string;
  /** 卡片补充说明（可选） */
  description?: string;
  /** 选项级风险标记，默认 normal */
  risk?: AgentRiskLevel;
  /** 点中后后端要执行的动作；缺省表示该选项只是语义化回答（继续对话） */
  action?: AgentAction;
}

/**
 * 表单字段类型。JSON 映射规则：JSON 小写 ↔ 后端枚举 FieldType（枚举名小写）。
 * 协议中不存在 number——金额/数量必须用 decimal（字符串传输，后端 BigDecimal 解析）。
 */
export type AgentFormFieldType = 'text' | 'decimal' | 'integer' | 'date' | 'select';

/** select 字段的可选项 */
export interface AgentFormFieldOption {
  label: string;
  value: string;
}

/** 表单字段定义 */
export interface AgentFormField {
  /** 字段名（英文标识符，提交时作为 key） */
  name: string;
  /** 字段标签（用户可见，中文） */
  label: string;
  type: AgentFormFieldType;
  /** 是否必填，默认 false */
  required?: boolean;
  /** 输入提示文案 */
  placeholder?: string;
  /** type 为 select 时必填的候选项 */
  options?: AgentFormFieldOption[];
  /** 默认值（统一为字符串，金额/数量等精度敏感值不在前端做数值运算） */
  defaultValue?: string;
}

/** Agent 要求用户补充结构化信息时返回的表单 */
export interface AgentForm {
  /** 表单唯一标识，提交时随表单值一并回传 */
  id: string;
  title?: string;
  fields: AgentFormField[];
  /** 提交按钮文案，默认「提交」 */
  submitLabel?: string;
  /** 表单提交后后端要执行的动作（必填） */
  submitAction: AgentAction;
}

/**
 * Agent 回复 —— 协议核心结构。
 * version、text 必有；options / form 按需出现。
 */
export interface AgentReply {
  /** 协议版本，每条回复必带，当前 "0.1" */
  version: string;
  /** 回复正文（markdown 文本） */
  text: string;
  /** 可点击选项卡片列表 */
  options?: AgentOption[];
  /** 需要用户填写的表单 */
  form?: AgentForm;
  /**
   * Human-in-the-loop 标记（回复级），默认 false。
   * true 时 options 必含「确认执行」（risk=high）与「取消」两项，
   * 后端只准备动作，由人点击确认后才执行。
   */
  requiresConfirmation?: boolean;
}

/** 表单提交载荷：字段名 → 字符串值 */
export type AgentFormValues = Record<string, string>;

/** 消息角色 */
export type ChatRole = 'user' | 'agent';

/** 用户消息 */
export interface UserMessage {
  id: string;
  role: 'user';
  /** ISO 8601 时间戳 */
  createdAt: string;
  /** 用户输入文本，或点击选项/提交表单后生成的语义化文本 */
  text: string;
}

/** Agent 消息：内容即一条结构化回复 */
export interface AgentMessage {
  id: string;
  role: 'agent';
  createdAt: string;
  reply: AgentReply;
}

/** 聊天消息（可辨识联合，按 role 区分） */
export type ChatMessage = UserMessage | AgentMessage;
