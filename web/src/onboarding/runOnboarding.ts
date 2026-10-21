import {
  createProduct,
  createUnit,
  type InventoryCategory,
} from '../api/catalogApi.ts';
import { createCustomer } from '../api/customerApi.ts';
import {
  createAdjustment,
  validateAdjustmentForm,
  type InventoryAdjustment,
} from '../api/inventoryApi.ts';
import { createSupplier, createWarehouse } from '../api/masterDataApi.ts';
import {
  createPurchaseOrder,
  validatePurchaseOrderForm,
  type PurchaseOrder,
} from '../api/purchaseOrderApi.ts';
import {
  canRunOnboardingStep,
  parseChoiceId,
  type OnboardingContext,
} from './onboarding.ts';

export type SettlementMethod = 'MONTHLY' | 'CASH' | 'PREPAID';

export interface ArchiveDraft {
  unitChoice: string;
  unitName: string;
  productChoice: string;
  productName: string;
  inventoryCategory: InventoryCategory;
  warehouseChoice: string;
  warehouseName: string;
  supplierChoice: string;
  supplierName: string;
  supplierSettlement: SettlementMethod;
  customerChoice: string;
  customerName: string;
  customerSettlement: SettlementMethod;
}

export interface OpeningDraft {
  quantity: string;
  unitCost: string;
}

export interface FirstOrderDraft {
  orderDate: string;
  quantity: string;
  unitPrice: string;
}

function requirePermission(
  permissions: readonly string[],
  permission: string,
  action: string,
): void {
  if (!permissions.includes(permission)) {
    throw new Error(`当前账号没有${action}权限（${permission}）`);
  }
}

function requireNewName(name: string, label: string): string {
  const trimmed = name.trim();
  if (!trimmed) {
    throw new Error(`请填写${label}，或改选已有档案。向导不会自动编造测试数据。`);
  }
  return trimmed;
}

async function resolveOrCreate(options: {
  choice: string;
  label: string;
  create: () => Promise<number>;
}): Promise<number> {
  const parsed = parseChoiceId(options.choice);
  if (parsed === 'new') return options.create();
  if (typeof parsed === 'number') return parsed;
  throw new Error(`请选择或新建${options.label}`);
}

export async function applyArchiveStep(
  draft: ArchiveDraft,
  permissions: readonly string[],
  current: OnboardingContext,
): Promise<OnboardingContext> {
  if (!canRunOnboardingStep('archives', permissions)) {
    throw new Error('当前账号不能新建档案，请联系管理员完成建账。');
  }

  const next: OnboardingContext = { ...current };

  next.unitId = await resolveOrCreate({
    choice: draft.unitChoice,
    label: '单位',
    create: async () => {
      requirePermission(permissions, 'catalog:write', '新建单位');
      const unit = await createUnit({
        name: requireNewName(draft.unitName, '单位名称'),
        precision: 0,
      });
      return unit.id;
    },
  });

  next.warehouseId = await resolveOrCreate({
    choice: draft.warehouseChoice,
    label: '仓库',
    create: async () => {
      requirePermission(permissions, 'warehouse:create_warehouse', '新建仓库');
      const warehouse = await createWarehouse({
        code: '',
        name: requireNewName(draft.warehouseName, '仓库名称'),
        address: '',
        manager: '',
        locationEnabled: false,
      });
      return warehouse.id;
    },
  });

  next.productId = await resolveOrCreate({
    choice: draft.productChoice,
    label: '商品',
    create: async () => {
      requirePermission(permissions, 'catalog:create_product', '新建商品');
      if (!next.unitId) {
        throw new Error('新建商品前需要先有基本单位');
      }
      const product = await createProduct({
        code: '',
        name: requireNewName(draft.productName, '商品名称'),
        spec: '',
        categoryId: null,
        inventoryCategory: draft.inventoryCategory,
        baseUnitId: next.unitId,
        barcode: '',
        remark: '',
        unitConversions: [],
      });
      return product.id;
    },
  });

  next.supplierId = await resolveOrCreate({
    choice: draft.supplierChoice,
    label: '供应商',
    create: async () => {
      requirePermission(permissions, 'partner:create_supplier', '新建供应商');
      const supplier = await createSupplier({
        code: '',
        name: requireNewName(draft.supplierName, '供应商名称'),
        contactPerson: '',
        contactPhone: '',
        address: '',
        taxNo: '',
        settlementMethod: draft.supplierSettlement,
      });
      return supplier.id;
    },
  });

  const customerChoice = parseChoiceId(draft.customerChoice);
  const skipCustomer =
    customerChoice === null
    || (customerChoice === 'new' && draft.customerName.trim() === '');
  if (skipCustomer) {
    next.customerId = current.customerId;
  } else {
    next.customerId = await resolveOrCreate({
      choice: draft.customerChoice,
      label: '客户',
      create: async () => {
        requirePermission(permissions, 'partner:create_customer', '新建客户');
        const customer = await createCustomer({
          code: '',
          name: requireNewName(draft.customerName, '客户名称'),
          contactPerson: '',
          contactPhone: '',
          address: '',
          taxNo: '',
          settlementMethod: draft.customerSettlement,
          creditLimit: '',
        });
        return customer.id;
      },
    });
  }

  return next;
}

export async function applyOpeningStep(
  draft: OpeningDraft,
  permissions: readonly string[],
  context: OnboardingContext,
): Promise<InventoryAdjustment> {
  if (!canRunOnboardingStep('opening', permissions)) {
    throw new Error('当前账号不能做期初建账（需要 inventory:adjust）。');
  }
  const form = {
    type: 'OPENING' as const,
    warehouseId: context.warehouseId,
    productId: context.productId,
    quantity: draft.quantity.trim(),
    unitCost: draft.unitCost.trim(),
    adjustAmount: '',
  };
  const invalid = validateAdjustmentForm(form);
  if (invalid) throw new Error(invalid);
  return createAdjustment(form);
}

export async function applyFirstOrderStep(
  draft: FirstOrderDraft,
  permissions: readonly string[],
  context: OnboardingContext,
): Promise<PurchaseOrder> {
  if (!canRunOnboardingStep('first-order', permissions)) {
    throw new Error('当前账号不能创建采购订单（需要 purchase:order）。');
  }
  const form = {
    supplierId: context.supplierId,
    orderDate: draft.orderDate.trim(),
    remark: '',
    lines: [
      {
        productId: context.productId,
        quantity: draft.quantity.trim(),
        unitPrice: draft.unitPrice.trim(),
      },
    ],
  };
  const invalid = validatePurchaseOrderForm(form);
  if (invalid) throw new Error(invalid);
  return createPurchaseOrder(form);
}
