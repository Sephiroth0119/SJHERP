/**
 * Mock Agent：纯前端假数据，演示选项返回协议 v0.1 的各种形态。
 * 真实后端就绪后，本模块由 /api 的 Agent 会话接口替换。
 */
import { AGENT_PROTOCOL_VERSION, type AgentReply } from '../types/agent';

/** 会话开场白：文本 + 业务入口选项（无 action，仅语义化回答） */
export const GREETING_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text: '你好，我是 SJHERP 业务助手。直接告诉我你要做什么，或从下面的常用操作开始：',
  options: [
    {
      id: 'opt-create-po',
      label: '新建采购订单',
      description: '录入供应商、物料与数量，走草稿 → 审核流程',
    },
    {
      id: 'opt-query-stock',
      label: '查询库存',
      description: '按物料查询当前库存台账',
    },
    {
      id: 'opt-sales-summary',
      label: '本月销售汇总',
      description: '按客户/物料统计本月销售出库',
    },
  ],
};

/** 采购订单表单：演示 form 字段（decimal 数量字符串传输，后端 BigDecimal 解析） */
const PURCHASE_ORDER_FORM_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text: '好的，新建采购订单。请补充以下信息（创建后单据为「草稿」状态，需审核后才会执行）：',
  form: {
    id: 'form-create-po',
    title: '采购订单',
    submitLabel: '创建草稿',
    fields: [
      {
        name: 'supplier',
        label: '供应商',
        type: 'select',
        required: true,
        options: [
          { label: '华东金属材料有限公司', value: 'SUP-001' },
          { label: '宁波精密五金厂', value: 'SUP-002' },
          { label: '苏州电子元件商行', value: 'SUP-003' },
        ],
      },
      { name: 'material', label: '物料编码', type: 'text', required: true, placeholder: '如 MAT-0102 不锈钢板' },
      { name: 'quantity', label: '数量', type: 'decimal', required: true, placeholder: '采购数量' },
      { name: 'expectedDate', label: '期望到货日期', type: 'date', required: true },
    ],
    submitAction: { type: 'CREATE_PURCHASE_ORDER_DRAFT' },
  },
};

/** 高风险确认：演示 Human-in-the-loop（回复级 requiresConfirmation + 选项级 risk=high） */
const PURCHASE_ORDER_CONFIRM_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text: '采购订单草稿 PO-20260611-003 已创建。检测到你有审核权限，是否立即提交审核？审核通过后将进入执行状态并产生应付。',
  requiresConfirmation: true,
  options: [
    {
      id: 'opt-po-approve',
      label: '提交审核',
      description: '高风险操作：审核后单据进入执行，不可直接修改，只可冲销',
      risk: 'high',
      action: { type: 'SUBMIT_PURCHASE_ORDER_AUDIT', params: { orderId: 'PO-20260611-003' } },
    },
    {
      id: 'opt-po-keep-draft',
      label: '暂存草稿',
      description: '保留草稿，稍后再处理',
    },
  ],
};

/** 库存查询：演示纯文本（mock 数据表格用文本排版代替） */
const STOCK_QUERY_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text:
    '当前库存台账（mock 数据）：\n' +
    '· MAT-0102 不锈钢板 2mm — 现存 1,250 张，可用 1,180 张（70 张已被工单 WO-0021 预留）\n' +
    '· MAT-0205 六角螺栓 M8 — 现存 48,000 件，可用 48,000 件\n' +
    '· FG-3001 不锈钢置物架（成品）— 现存 312 台，可用 290 台\n' +
    '需要看某个物料的出入库明细吗？',
  options: [
    { id: 'opt-stock-detail', label: '查看 MAT-0102 出入库明细' },
    { id: 'opt-stock-count', label: '发起盘点', description: '生成盘点单（草稿）' },
  ],
};

/** 销售汇总：演示文本 + 后续动作选项 */
const SALES_SUMMARY_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text:
    '2026 年 6 月（截至 6 月 11 日）销售汇总（mock 数据）：\n' +
    '· 销售出库 23 单，含税金额 ¥486,520.00\n' +
    '· Top 客户：杭州家居连锁（¥182,300.00）、上海建材城（¥96,750.00）\n' +
    '· 应收余额：¥213,400.00，其中逾期 ¥35,000.00',
  options: [
    { id: 'opt-overdue', label: '查看逾期应收明细' },
    { id: 'opt-export', label: '导出本月销售报表' },
  ],
};

/** 流程缺口通道：演示能力不足时的缺口记录选项（对应 CLAUDE.md 自进化闭环） */
const GAP_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text: '这个需求我目前还做不到。我可以把这个流程缺口记录下来，提交给开发者 Agent 补齐能力，完成后会进入系统大记忆供之后复用。',
  options: [
    {
      id: 'opt-record-gap',
      label: '记录流程缺口',
      description: '结构化记录场景与期望，生成开发 issue',
      action: { type: 'RECORD_CAPABILITY_GAP' },
    },
    { id: 'opt-back', label: '返回常用操作' },
  ],
};

/** 简单收尾回复 */
const ACK_REPLY: AgentReply = {
  version: AGENT_PROTOCOL_VERSION,
  text: '好的，已记录（mock）。还需要我做什么？可以直接输入，或回到常用操作。',
  options: [{ id: 'opt-home', label: '返回常用操作' }],
};

/**
 * 根据用户输入返回 mock 回复。
 * 仅做关键词路由（点击选项后以 label 作为输入），用于演示协议渲染；
 * 不包含任何真实业务规则。
 */
export function getMockReply(input: string): AgentReply {
  if (input.includes('返回常用操作')) {
    return GREETING_REPLY;
  }
  if (input.includes('表单提交')) {
    return PURCHASE_ORDER_CONFIRM_REPLY;
  }
  if (input.includes('采购订单')) {
    return PURCHASE_ORDER_FORM_REPLY;
  }
  if (input.includes('提交审核')) {
    return {
      version: AGENT_PROTOCOL_VERSION,
      text: '已提交审核（mock）。审核记录与操作人已写入审计日志。后续状态变化我会在这里通知你。',
      options: [{ id: 'opt-home-2', label: '返回常用操作' }],
    };
  }
  if (input.includes('库存') || input.includes('明细') || input.includes('盘点')) {
    return STOCK_QUERY_REPLY;
  }
  if (input.includes('销售') || input.includes('应收') || input.includes('报表')) {
    return SALES_SUMMARY_REPLY;
  }
  if (input.includes('草稿') || input.includes('记录')) {
    return ACK_REPLY;
  }
  return GAP_REPLY;
}
