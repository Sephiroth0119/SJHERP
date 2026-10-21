import { Button, Input, Select, Steps } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  listUnits,
  searchProducts,
  type InventoryCategory,
  type Product,
  type Unit,
} from '../api/catalogApi.ts';
import { searchCustomers, type Customer } from '../api/customerApi.ts';
import { ApiError } from '../api/http.ts';
import {
  searchSuppliers,
  searchWarehouses,
  type Supplier,
  type Warehouse,
} from '../api/masterDataApi.ts';
import {
  GETTING_STARTED_STEPS,
} from '../onboarding/helpContent.ts';
import {
  emptyOnboardingContext,
  visibleOnboardingSteps,
  type OnboardingContext,
  type OnboardingStepId,
} from '../onboarding/onboarding.ts';
import {
  applyArchiveStep,
  applyFirstOrderStep,
  applyOpeningStep,
  type ArchiveDraft,
  type FirstOrderDraft,
  type OpeningDraft,
  type SettlementMethod,
} from '../onboarding/runOnboarding.ts';
import { createInFlightGuard } from '../security/workbenchControl.ts';
import { AppModal, displayApiError } from '../ui/index.ts';

const INVENTORY_CATEGORY_OPTIONS: Array<{ value: InventoryCategory; label: string }> = [
  { value: 'MERCHANDISE', label: '商品' },
  { value: 'RAW_MATERIAL', label: '原材料' },
  { value: 'SEMI_FINISHED', label: '半成品' },
  { value: 'FINISHED_GOOD', label: '产成品' },
];

const SETTLEMENT_OPTIONS: Array<{ value: SettlementMethod; label: string }> = [
  { value: 'MONTHLY', label: '月结' },
  { value: 'CASH', label: '现结' },
  { value: 'PREPAID', label: '预付' },
];

function todayIsoDate(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function emptyArchiveDraft(): ArchiveDraft {
  return {
    unitChoice: 'new',
    unitName: '',
    productChoice: 'new',
    productName: '',
    inventoryCategory: 'MERCHANDISE',
    warehouseChoice: 'new',
    warehouseName: '',
    supplierChoice: 'new',
    supplierName: '',
    supplierSettlement: 'MONTHLY',
    customerChoice: 'new',
    customerName: '',
    customerSettlement: 'MONTHLY',
  };
}

function firstChoice(id: number | undefined): string {
  return id ? String(id) : 'new';
}

function optionLabel(name: string, code?: string): string {
  return code ? `${name}（${code}）` : name;
}

export function OnboardingWizard({
  open,
  permissions,
  onDismiss,
  onComplete,
  onOpenHelp,
  onOpenFirstOrder,
}: {
  open: boolean;
  permissions: readonly string[];
  onDismiss: () => void;
  onComplete: () => void;
  onOpenHelp: () => void;
  onOpenFirstOrder: (docNo: string) => void;
}) {
  const steps = visibleOnboardingSteps(permissions);
  const [stepIndex, setStepIndex] = useState(0);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const [context, setContext] = useState<OnboardingContext>(emptyOnboardingContext);
  const [archive, setArchive] = useState<ArchiveDraft>(emptyArchiveDraft);
  const [opening, setOpening] = useState<OpeningDraft>({ quantity: '', unitCost: '' });
  const [firstOrder, setFirstOrder] = useState<FirstOrderDraft>({
    orderDate: todayIsoDate(),
    quantity: '',
    unitPrice: '',
  });
  const [units, setUnits] = useState<Unit[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [firstOrderDocNo, setFirstOrderDocNo] = useState('');
  const mutationInFlight = useRef(createInFlightGuard());
  const currentStep = steps[stepIndex] ?? 'welcome';

  useEffect(() => {
    if (!open) return;
    setStepIndex(0);
    setError('');
    setNotice('');
    setSaving(false);
    setContext(emptyOnboardingContext());
    setArchive(emptyArchiveDraft());
    setOpening({ quantity: '', unitCost: '' });
    setFirstOrder({ orderDate: todayIsoDate(), quantity: '', unitPrice: '' });
    setFirstOrderDocNo('');
  }, [open]);

  useEffect(() => {
    if (!open || currentStep !== 'archives') return;
    let cancelled = false;
    void Promise.all([
      listUnits(),
      searchProducts('', 'ENABLED', 1),
      searchWarehouses('', 'ENABLED', 1),
      searchSuppliers('', 'ENABLED', 1),
      searchCustomers('', 'ENABLED', 1),
    ]).then(([unitPage, productPage, warehousePage, supplierPage, customerPage]) => {
      if (cancelled) return;
      setUnits(unitPage);
      setProducts(productPage.items);
      setWarehouses(warehousePage.items);
      setSuppliers(supplierPage.items);
      setCustomers(customerPage.items);
      setArchive((current) => ({
        ...current,
        unitChoice: current.unitChoice === 'new' ? firstChoice(unitPage[0]?.id) : current.unitChoice,
        productChoice: current.productChoice === 'new'
          ? firstChoice(productPage.items[0]?.id)
          : current.productChoice,
        warehouseChoice: current.warehouseChoice === 'new'
          ? firstChoice(warehousePage.items[0]?.id)
          : current.warehouseChoice,
        supplierChoice: current.supplierChoice === 'new'
          ? firstChoice(supplierPage.items[0]?.id)
          : current.supplierChoice,
        customerChoice: current.customerChoice === 'new'
          ? firstChoice(customerPage.items[0]?.id)
          : current.customerChoice,
      }));
    }).catch((cause: unknown) => {
      if (!cancelled) setError(displayApiError(cause, '档案列表加载失败'));
    });
    return () => { cancelled = true; };
  }, [open, currentStep]);

  const goNext = () => {
    setError('');
    setStepIndex((index) => Math.min(index + 1, steps.length - 1));
  };

  const goPrev = () => {
    setError('');
    setNotice('');
    setStepIndex((index) => Math.max(index - 1, 0));
  };

  const runWrite = async (task: () => Promise<void>) => {
    if (!mutationInFlight.current.tryAcquire()) return;
    setSaving(true);
    setError('');
    try {
      await task();
      goNext();
    } catch (cause: unknown) {
      setError(cause instanceof Error && !(cause instanceof ApiError)
        ? cause.message
        : displayApiError(cause, '保存失败，请检查输入后重试'));
    } finally {
      mutationInFlight.current.release();
      setSaving(false);
    }
  };

  const submitCurrent = () => {
    switch (currentStep) {
      case 'welcome':
        goNext();
        return;
      case 'archives':
        void runWrite(async () => {
          const next = await applyArchiveStep(archive, permissions, context);
          setContext(next);
          setNotice('档案已就绪。下一步登记期初库存（没有存货可以跳过）。');
        });
        return;
      case 'opening':
        void runWrite(async () => {
          const result = await applyOpeningStep(opening, permissions, context);
          setNotice(`期初已入账，单号 ${result.srcDocNo ?? result.transactionId}。`);
        });
        return;
      case 'first-order':
        void runWrite(async () => {
          const order = await applyFirstOrderStep(firstOrder, permissions, context);
          setFirstOrderDocNo(order.docNo);
          setNotice(`第一张采购订单已保存为草稿 ${order.docNo}，请到采购工作台审核。`);
        });
        return;
      case 'done':
        onComplete();
        return;
      default: {
        const exhaustive: never = currentStep;
        return exhaustive;
      }
    }
  };

  const skipWriteStep = () => {
    if (currentStep === 'opening' || currentStep === 'first-order' || currentStep === 'archives') {
      goNext();
    }
  };

  const footer = (
    <div className="onboarding-footer">
      <Button onClick={onDismiss} disabled={saving}>稍后再说</Button>
      <div className="onboarding-footer-end">
        {stepIndex > 0 && (
          <Button onClick={goPrev} disabled={saving}>上一步</Button>
        )}
        {(currentStep === 'archives' || currentStep === 'opening' || currentStep === 'first-order') && (
          <Button onClick={skipWriteStep} disabled={saving}>跳过本步</Button>
        )}
        <Button type="primary" onClick={submitCurrent} loading={saving}>
          {currentStep === 'done' ? '完成' : currentStep === 'welcome' ? '开始建账' : '保存并继续'}
        </Button>
      </div>
    </div>
  );

  return (
    <AppModal
      open={open}
      title="新手引导：建档案 → 期初 → 第一张单"
      onCancel={onDismiss}
      footer={footer}
      width={720}
      confirmLoading={saving}
    >
      <Steps
        size="small"
        current={stepIndex}
        items={steps.map((step) => ({ title: stepTitle(step) }))}
      />
      <div className="onboarding-body">
        {error && <p className="onboarding-error" role="alert">{error}</p>}
        {notice && <p className="onboarding-notice">{notice}</p>}
        {renderStep({
          step: currentStep,
          archive,
          setArchive,
          opening,
          setOpening,
          firstOrder,
          setFirstOrder,
          units,
          products,
          warehouses,
          suppliers,
          customers,
          firstOrderDocNo,
          onOpenHelp,
          onOpenFirstOrder,
        })}
      </div>
    </AppModal>
  );
}

function stepTitle(step: OnboardingStepId): string {
  switch (step) {
    case 'welcome': return '欢迎';
    case 'archives': return '建档案';
    case 'opening': return '期初';
    case 'first-order': return '第一张单';
    case 'done': return '完成';
    default: {
      const exhaustive: never = step;
      return exhaustive;
    }
  }
}

function ChoiceSelect({
  value,
  onChange,
  newLabel,
  items,
}: {
  value: string;
  onChange: (value: string) => void;
  newLabel: string;
  items: Array<{ id: number; label: string }>;
}) {
  return (
    <Select
      value={value}
      onChange={onChange}
      options={[
        { value: 'new', label: newLabel },
        ...items.map((item) => ({ value: String(item.id), label: item.label })),
      ]}
    />
  );
}

function renderStep({
  step,
  archive,
  setArchive,
  opening,
  setOpening,
  firstOrder,
  setFirstOrder,
  units,
  products,
  warehouses,
  suppliers,
  customers,
  firstOrderDocNo,
  onOpenHelp,
  onOpenFirstOrder,
}: {
  step: OnboardingStepId;
  archive: ArchiveDraft;
  setArchive: (draft: ArchiveDraft) => void;
  opening: OpeningDraft;
  setOpening: (draft: OpeningDraft) => void;
  firstOrder: FirstOrderDraft;
  setFirstOrder: (draft: FirstOrderDraft) => void;
  units: Unit[];
  products: Product[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  customers: Customer[];
  firstOrderDocNo: string;
  onOpenHelp: () => void;
  onOpenFirstOrder: (docNo: string) => void;
}) {
  switch (step) {
    case 'welcome':
      return (
        <div>
          <p>第一次登录先把账建起来。向导只调用现有接口，<strong>不会写入演示假数据</strong>，请填你们自己的名称和数量。</p>
          <ol className="onboarding-list">
            {GETTING_STARTED_STEPS.map((item) => <li key={item}>{item}</li>)}
          </ol>
          <p>没有对应权限的步骤会自动隐藏。随时可以点「稍后再说」，之后可在「帮助」里重新打开。</p>
        </div>
      );
    case 'archives':
      return (
        <form className="customer-form onboarding-form" onSubmit={(event) => event.preventDefault()}>
          <label>单位
            <ChoiceSelect
              value={archive.unitChoice}
              onChange={(unitChoice) => setArchive({ ...archive, unitChoice })}
              newLabel="新建单位"
              items={units.map((unit) => ({ id: unit.id, label: unit.name }))}
            />
          </label>
          {archive.unitChoice === 'new' && (
            <label>单位名称
              <Input
                value={archive.unitName}
                maxLength={50}
                placeholder="例如：个"
                onChange={(event) => setArchive({ ...archive, unitName: event.target.value })}
              />
            </label>
          )}
          <label>仓库
            <ChoiceSelect
              value={archive.warehouseChoice}
              onChange={(warehouseChoice) => setArchive({ ...archive, warehouseChoice })}
              newLabel="新建仓库"
              items={warehouses.map((row) => ({ id: row.id, label: optionLabel(row.name, row.code) }))}
            />
          </label>
          {archive.warehouseChoice === 'new' && (
            <label>仓库名称
              <Input
                value={archive.warehouseName}
                maxLength={200}
                onChange={(event) => setArchive({ ...archive, warehouseName: event.target.value })}
              />
            </label>
          )}
          <label>商品
            <ChoiceSelect
              value={archive.productChoice}
              onChange={(productChoice) => setArchive({ ...archive, productChoice })}
              newLabel="新建商品"
              items={products.map((row) => ({ id: row.id, label: optionLabel(row.name, row.code) }))}
            />
          </label>
          {archive.productChoice === 'new' && (
            <>
              <label>商品名称
                <Input
                  value={archive.productName}
                  maxLength={200}
                  onChange={(event) => setArchive({ ...archive, productName: event.target.value })}
                />
              </label>
              <label>存货分类
                <Select
                  value={archive.inventoryCategory}
                  onChange={(inventoryCategory) => setArchive({ ...archive, inventoryCategory })}
                  options={INVENTORY_CATEGORY_OPTIONS}
                />
              </label>
            </>
          )}
          <label>供应商
            <ChoiceSelect
              value={archive.supplierChoice}
              onChange={(supplierChoice) => setArchive({ ...archive, supplierChoice })}
              newLabel="新建供应商"
              items={suppliers.map((row) => ({ id: row.id, label: optionLabel(row.name, row.code) }))}
            />
          </label>
          {archive.supplierChoice === 'new' && (
            <>
              <label>供应商名称
                <Input
                  value={archive.supplierName}
                  maxLength={200}
                  onChange={(event) => setArchive({ ...archive, supplierName: event.target.value })}
                />
              </label>
              <label>结算方式
                <Select
                  value={archive.supplierSettlement}
                  onChange={(supplierSettlement) => setArchive({ ...archive, supplierSettlement })}
                  options={SETTLEMENT_OPTIONS}
                />
              </label>
            </>
          )}
          <label>客户（可选）
            <ChoiceSelect
              value={archive.customerChoice}
              onChange={(customerChoice) => setArchive({ ...archive, customerChoice })}
              newLabel="暂不新建 / 新建客户"
              items={customers.map((row) => ({ id: row.id, label: optionLabel(row.name, row.code) }))}
            />
          </label>
          {archive.customerChoice === 'new' && (
            <>
              <label>客户名称
                <Input
                  value={archive.customerName}
                  maxLength={200}
                  placeholder="可留空，稍后再建"
                  onChange={(event) => setArchive({ ...archive, customerName: event.target.value })}
                />
              </label>
              <label>结算方式
                <Select
                  value={archive.customerSettlement}
                  onChange={(customerSettlement) => setArchive({ ...archive, customerSettlement })}
                  options={SETTLEMENT_OPTIONS}
                />
              </label>
            </>
          )}
        </form>
      );
    case 'opening':
      return (
        <form className="customer-form onboarding-form" onSubmit={(event) => event.preventDefault()}>
          <p>期初走库存台账同一入口（OPENING）。数量必须大于 0，单价可以是 0。没有存货请跳过本步。</p>
          <label>期初数量
            <Input
              value={opening.quantity}
              inputMode="decimal"
              onChange={(event) => setOpening({ ...opening, quantity: event.target.value })}
            />
          </label>
          <label>期初单价
            <Input
              value={opening.unitCost}
              inputMode="decimal"
              onChange={(event) => setOpening({ ...opening, unitCost: event.target.value })}
            />
          </label>
        </form>
      );
    case 'first-order':
      return (
        <form className="customer-form onboarding-form" onSubmit={(event) => event.preventDefault()}>
          <p>创建一张<strong>采购订单草稿</strong>。向导不会自动审核或过账。审核、入库、开票请到采购工作台继续。</p>
          <label>下单日期
            <Input
              type="date"
              value={firstOrder.orderDate}
              onChange={(event) => setFirstOrder({ ...firstOrder, orderDate: event.target.value })}
            />
          </label>
          <label>订购数量
            <Input
              value={firstOrder.quantity}
              inputMode="decimal"
              onChange={(event) => setFirstOrder({ ...firstOrder, quantity: event.target.value })}
            />
          </label>
          <label>采购单价
            <Input
              value={firstOrder.unitPrice}
              inputMode="decimal"
              onChange={(event) => setFirstOrder({ ...firstOrder, unitPrice: event.target.value })}
            />
          </label>
        </form>
      );
    case 'done':
      return (
        <div>
          <p>建账路径已经走完。日常可以从左侧菜单进工作台，也可以继续用 Agent 助手。</p>
          {firstOrderDocNo && (
            <p>
              第一张采购订单：
              <Button type="link" onClick={() => onOpenFirstOrder(firstOrderDocNo)}>
                {firstOrderDocNo}
              </Button>
            </p>
          )}
          <Button type="link" onClick={onOpenHelp}>打开帮助手册</Button>
        </div>
      );
    default: {
      const exhaustive: never = step;
      return exhaustive;
    }
  }
}
