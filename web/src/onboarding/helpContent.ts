import type { ModuleKey } from '../types/navigation.ts';

export interface HelpTopic {
  key: ModuleKey | 'archives';
  title: string;
  hash: string;
  summary: string;
  steps: string[];
}

export const HELP_TOPICS: readonly HelpTopic[] = [
  {
    key: 'archives',
    title: '基础档案',
    hash: '#/inventory/product',
    summary: '先有单位、商品、仓库、客户和供应商，后面的进销存才站得住。',
    steps: [
      '库存 → 商品档案：建单位、类目和商品（数量金额用字符串，不要手算浮点）。',
      '库存 → 仓库档案：至少一个启用仓库，期初和收发货都要用到。',
      '销售 → 客户档案、采购 → 供应商档案：名称和结算方式必填。',
    ],
  },
  {
    key: 'purchase',
    title: '采购',
    hash: '#/purchase/order',
    summary: '采购订单是承诺，入库才加库存，发票过账才产生应付。',
    steps: [
      '采购 → 采购订单：选供应商、填行、保存草稿后审核。下单不动库存。',
      '采购 → 采购入库：挂已审核订单和收货仓，过账后库存增加。',
      '采购 → 采购发票：挂已过账入库单，过账生成应付；付款在采购 → 付款。',
    ],
  },
  {
    key: 'sales',
    title: '销售',
    hash: '#/sales/order',
    summary: '销售订单是约定，出库才扣库存并记成本，发票过账才产生应收。',
    steps: [
      '销售 → 销售订单：选客户、填行后审核。下单不扣库存。',
      '销售 → 销售出库：挂已审核订单，过账扣库存并记移动加权成本。',
      '销售 → 销售发票：挂已完成出库单，过账生成应收；收款在销售 → 收款。',
    ],
  },
  {
    key: 'inventory',
    title: '库存',
    hash: '#/inventory/ledger',
    summary: '台账是库存真源。期初、盘点、调拨都走同一套出入库入口。',
    steps: [
      '库存 → 台账：查看余额与流水；有 inventory:adjust 时可做期初建账。',
      '库存 → 盘点：录实盘后审核过账，盘盈盘亏写入库存。',
      '库存 → 调拨：两仓对开，金额守恒；已过账单据不能删除，只能冲销。',
    ],
  },
  {
    key: 'production',
    title: '生产',
    hash: '#/production',
    summary: 'BOM 展开、MRP 建议、工单领料报工，料费与财务勾稽。',
    steps: [
      '先维护 BOM 和工艺路线，再跑需求计划 / MRP。',
      '工单下达开工后才能领料；报工完工入库把产成品送进库存。',
      '月末成本结转归集料工费，关账前不要跳过。',
    ],
  },
  {
    key: 'finance',
    title: '财务',
    hash: '#/finance',
    summary: '凭证借贷必须平衡；财务记录只可冲销，不可改数或删除。',
    steps: [
      '业务过账会自动生成凭证。不要绕过单据直接改账。',
      '收付款单过账才会核销应收应付，并出现金侧凭证。',
      '月末先看关账预检，有 ERROR 必须先处理再关账。',
    ],
  },
  {
    key: 'reports',
    title: '报表',
    hash: '#/reports',
    summary: '只读查询与导出。金额数量按字符串展示，页签按权限隐藏。',
    steps: [
      '库存余额、收发存汇总登录即可看。',
      '采购/销售明细、账龄、资产负债表和利润表按对应权限打开。',
      '需要带走时用导出 Excel，不要截屏抄数字。',
    ],
  },
  {
    key: 'agent',
    title: 'Agent 聊天',
    hash: '#/agent',
    summary: '主入口：用对话做业务。资金、过账、关账会弹出确认卡片。',
    steps: [
      '左侧可切换自己的会话；不要把账号借给别人连聊。',
      '高风险操作要点确认卡片，不要在聊天里要求系统跳过审核。',
      '系统做不到的事走缺口通道，不要让 Agent 绕过领域模型硬做。',
    ],
  },
];

export const GETTING_STARTED_STEPS = [
  '建档案：单位、商品、仓库，以及供应商（做第一张采购单）和可选客户。',
  '期初：给仓库里已有的货补一条期初数量和单价，走库存唯一写入口。',
  '第一张单：创建一张采购订单草稿。审核、入库、开票仍在工作台完成。',
] as const;
