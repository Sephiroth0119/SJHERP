import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as receiptApi from '../api/purchaseReceiptApi.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<receiptApi.PurchaseReceiptStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

function today(): string {
  const value = new Date();
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function emptyForm(): receiptApi.PurchaseReceiptForm {
  return {
    purchaseOrderNo: '',
    warehouseId: null,
    receiptDate: today(),
    remark: '',
    lines: [],
  };
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}

function hasOutstanding(quantity: string): boolean {
  return !/^0(?:\.0+)?$/.test(quantity);
}

export function PurchaseReceiptWorkbench() {
  const [items, setItems] = useState<receiptApi.PurchaseReceipt[]>([]);
  const [selected, setSelected] = useState<receiptApi.PurchaseReceipt | null>(
    null,
  );
  const [warehouseFilterDraft, setWarehouseFilterDraft] = useState<
    number | null
  >(null);
  const [purchaseOrderFilterDraft, setPurchaseOrderFilterDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [warehouseFilter, setWarehouseFilter] = useState<number | null>(null);
  const [purchaseOrderFilter, setPurchaseOrderFilter] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<receiptApi.PurchaseReceiptForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(
    new Map(),
  );
  const [productNames, setProductNames] = useState<Map<number, string>>(
    new Map(),
  );
  const listVersion = useRef(0);
  const detailVersion = useRef(0);
  const mutationInFlight = useRef(false);

  const rememberWarehouses = (warehouses: masterDataApi.Warehouse[]) => {
    if (warehouses.length === 0) return;
    setWarehouseNames((current) => {
      const next = new Map(current);
      for (const warehouse of warehouses) {
        next.set(warehouse.id, `${warehouse.name}（${warehouse.code}）`);
      }
      return next;
    });
  };

  const rememberProducts = (products: catalogApi.Product[]) => {
    if (products.length === 0) return;
    setProductNames((current) => {
      const next = new Map(current);
      for (const product of products) {
        next.set(product.id, `${product.name}（${product.code}）`);
      }
      return next;
    });
  };

  const ensureWarehouseNames = (receipts: receiptApi.PurchaseReceipt[]) => {
    const ids = [
      ...new Set(
        receipts
          .map((receipt) => receipt.warehouseId)
          .filter((id) => !warehouseNames.has(id)),
      ),
    ];
    for (const id of ids) {
      void masterDataApi
        .getWarehouse(id)
        .then((warehouse) => rememberWarehouses([warehouse]))
        .catch(() => undefined);
    }
  };

  const ensureProductNames = (
    lines: Array<{ productId: number }>,
  ) => {
    const ids = [
      ...new Set(
        lines
          .map((line) => line.productId)
          .filter((id) => !productNames.has(id)),
      ),
    ];
    for (const id of ids) {
      void catalogApi
        .getProduct(id)
        .then((product) => rememberProducts([product]))
        .catch(() => undefined);
    }
  };

  useEffect(() => {
    const version = ++listVersion.current;
    setLoading(true);
    setError('');
    void receiptApi
      .searchPurchaseReceipts(
        warehouseFilter,
        purchaseOrderFilter,
        status,
        page,
        PAGE_SIZE,
      )
      .then((result) => {
        if (version !== listVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        ensureWarehouseNames(result.items);
      })
      .catch((cause: unknown) => {
        if (version === listVersion.current) {
          setError(errorMessage(cause, '采购入库单列表加载失败'));
        }
      })
      .finally(() => {
        if (version === listVersion.current) setLoading(false);
      });
  }, [warehouseFilter, purchaseOrderFilter, status, page, refreshKey]);

  const invalidateDetail = () => {
    detailVersion.current += 1;
  };

  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (receipt: receiptApi.PurchaseReceipt) => {
    const version = ++detailVersion.current;
    setSelected(receipt);
    setEditing(false);
    setError('');
    setNotice('');
    ensureWarehouseNames([receipt]);
    ensureProductNames(receipt.lines);
    void receiptApi
      .getPurchaseReceipt(receipt.docNo)
      .then((detail) => {
        if (version !== detailVersion.current) return;
        setSelected(detail);
        ensureWarehouseNames([detail]);
        ensureProductNames(detail.lines);
      })
      .catch((cause: unknown) => {
        if (version === detailVersion.current) {
          setError(errorMessage(cause, '采购入库单详情加载失败'));
        }
      });
  };

  const applySearch = () => {
    clearSelection();
    setWarehouseFilter(warehouseFilterDraft);
    setPurchaseOrderFilter(purchaseOrderFilterDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const changePage = (nextPage: number) => {
    clearSelection();
    setPage(nextPage);
  };

  const startNew = () => {
    clearSelection();
    setForm(emptyForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (mutationInFlight.current) return;
    mutationInFlight.current = true;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = receiptApi.validatePurchaseReceiptForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await receiptApi.createPurchaseReceipt(form);
      setSelected(result);
      setEditing(false);
      setNotice('采购入库单草稿已创建');
      ensureWarehouseNames([result]);
      ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '采购入库单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const transition = async (action: 'approve' | 'post') => {
    if (!selected || mutationInFlight.current) return;
    if (
      action === 'post' &&
      !window.confirm(
        '过账将增加库存、回写采购订单到货量并生成会计凭证。此操作不可直接撤销，确认继续过账吗？',
      )
    ) {
      return;
    }
    mutationInFlight.current = true;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result =
        action === 'approve'
          ? await receiptApi.approvePurchaseReceipt(selected.docNo)
          : await receiptApi.postPurchaseReceipt(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '采购入库单已审核' : '采购入库单已过账');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(
        errorMessage(
          cause,
          action === 'approve' ? '采购入库单审核失败' : '采购入库单过账失败',
        ),
      );
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const warehouseName = (id: number) =>
    warehouseNames.get(id) ?? `仓库 #${id}（名称不可用）`;
  const productName = (id: number) =>
    productNames.get(id) ?? `商品 #${id}（名称不可用）`;

  return (
    <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">采购 / 收货入库</p>
          <h1>采购入库</h1>
          <p>按已审核采购订单登记到货；过账后库存增加并自动生成凭证。</p>
        </div>
        <button
          type="button"
          className="memory-button memory-button-primary"
          disabled={saving}
          onClick={startNew}
        >
          新建采购入库单
        </button>
      </header>

      {error && (
        <div className="memory-error" role="alert" tabIndex={-1}>
          <span>{error}</span>
          <button
            type="button"
            disabled={loading || saving}
            onClick={() => {
              setError('');
              setRefreshKey((value) => value + 1);
            }}
          >
            重试
          </button>
        </div>
      )}
      {notice && (
        <div className="customer-success" role="status">
          {notice}
        </div>
      )}

      <div className="purchase-order-filters">
        <WarehousePicker
          label="按仓库筛选"
          value={warehouseFilterDraft}
          enabledOnly={false}
          allowClear
          disabled={loading || saving}
          onChange={setWarehouseFilterDraft}
          onOptions={rememberWarehouses}
        />
        <label>
          采购订单号
          <input
            aria-label="采购订单号"
            placeholder="精确筛选 PO 单号"
            disabled={loading || saving}
            value={purchaseOrderFilterDraft}
            onChange={(event) =>
              setPurchaseOrderFilterDraft(event.target.value)
            }
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                applySearch();
              }
            }}
          />
        </label>
        <label>
          入库单状态
          <select
            aria-label="入库单状态"
            disabled={loading || saving}
            value={statusDraft}
            onChange={(event) => setStatusDraft(event.target.value)}
          >
            <option value="">全部状态</option>
            {Object.entries(statusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="memory-button"
          disabled={loading || saving}
          onClick={applySearch}
        >
          查询
        </button>
      </div>

      <div className="customer-layout purchase-order-layout">
        <div className="customer-list-panel">
          {loading ? (
            <p className="memory-empty">正在加载采购入库单…</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">暂无采购入库单，请调整条件或创建草稿。</p>
          ) : (
            <div className="memory-table-wrap">
              <table className="memory-table">
                <thead>
                  <tr>
                    <th>单据号</th>
                    <th>采购订单</th>
                    <th>收货仓库</th>
                    <th>收货日期</th>
                    <th>总额</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((receipt) => (
                    <tr
                      key={receipt.docNo}
                      tabIndex={0}
                      aria-selected={selected?.docNo === receipt.docNo}
                      className={
                        selected?.docNo === receipt.docNo
                          ? 'memory-row-selected'
                          : ''
                      }
                      onClick={() => choose(receipt)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          choose(receipt);
                        }
                      }}
                    >
                      <td>{receipt.docNo}</td>
                      <td>{receipt.purchaseOrderNo}</td>
                      <td>{warehouseName(receipt.warehouseId)}</td>
                      <td>{receipt.receiptDate}</td>
                      <td>{receipt.totalAmount}</td>
                      <td>
                        <StatusBadge status={receipt.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {total > 0 && (
            <div className="memory-pagination">
              <span>
                共 {total} 条，第 {page} 页
              </span>
              <span>
                <button
                  type="button"
                  disabled={page <= 1 || loading || saving}
                  onClick={() => changePage(page - 1)}
                >
                  上一页
                </button>
                <button
                  type="button"
                  disabled={page * PAGE_SIZE >= total || loading || saving}
                  onClick={() => changePage(page + 1)}
                >
                  下一页
                </button>
              </span>
            </div>
          )}
        </div>

        <aside className="customer-detail purchase-order-detail">
          {editing ? (
            <PurchaseReceiptForm
              form={form}
              setForm={setForm}
              saving={saving}
              productNames={productNames}
              onProducts={rememberProducts}
              onWarehouses={rememberWarehouses}
              onSave={save}
              onCancel={() => setEditing(false)}
            />
          ) : selected ? (
            <PurchaseReceiptDetails
              receipt={selected}
              warehouseName={warehouseName}
              productName={productName}
              saving={saving}
              onApprove={() => void transition('approve')}
              onPost={() => void transition('post')}
            />
          ) : (
            <p className="memory-empty">选择左侧入库单查看详情。</p>
          )}
        </aside>
      </div>
    </section>
  );
}

function StatusBadge({
  status,
}: {
  status: receiptApi.PurchaseReceiptStatus;
}) {
  return (
    <span
      className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}
    >
      {statusLabels[status]}
    </span>
  );
}

interface WarehousePickerProps {
  label: string;
  value: number | null;
  enabledOnly: boolean;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Warehouse[]) => void;
}

function WarehousePicker({
  label,
  value,
  enabledOnly,
  allowClear = false,
  disabled,
  onChange,
  onOptions,
}: WarehousePickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<masterDataApi.Warehouse[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const warehouseVersion = useRef(0);

  useEffect(() => {
    const version = ++warehouseVersion.current;
    setLoading(true);
    setError('');
    void masterDataApi
      .searchWarehouses(keyword, enabledOnly ? 'ENABLED' : '', page)
      .then((result) => {
        if (version !== warehouseVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        onOptions(result.items);
      })
      .catch((cause: unknown) => {
        if (version === warehouseVersion.current) {
          setError(errorMessage(cause, '仓库加载失败'));
        }
      })
      .finally(() => {
        if (version === warehouseVersion.current) setLoading(false);
      });
  }, [enabledOnly, keyword, page, refreshKey]);

  const search = () => {
    setKeyword(draft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  return (
    <div className="reference-picker">
      <strong>{label}</strong>
      <div className="reference-picker-search">
        <input
          aria-label={`${label}关键词`}
          placeholder="输入仓库编码或名称"
          disabled={disabled || loading}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              search();
            }
          }}
        />
        <button
          type="button"
          className="memory-button"
          disabled={disabled || loading}
          onClick={search}
        >
          查找
        </button>
        {allowClear && value !== null && (
          <button
            type="button"
            className="memory-button"
            disabled={disabled}
            onClick={() => onChange(null)}
          >
            清除
          </button>
        )}
      </div>
      {error && (
        <small className="reference-picker-error" role="alert">
          {error}
        </small>
      )}
      <div className="reference-picker-results" role="listbox" aria-label={label}>
        {loading ? (
          <span>正在加载仓库…</span>
        ) : items.length === 0 ? (
          <span>未找到仓库</span>
        ) : (
          items.map((warehouse) => (
            <button
              type="button"
              role="option"
              aria-selected={value === warehouse.id}
              className={value === warehouse.id ? 'reference-picker-selected' : ''}
              disabled={disabled}
              key={warehouse.id}
              onClick={() => onChange(warehouse.id)}
            >
              {warehouse.name}
              <span>{warehouse.code}</span>
            </button>
          ))
        )}
      </div>
      {total > PAGE_SIZE && (
        <div className="reference-picker-pagination">
          <button
            type="button"
            disabled={disabled || loading || page <= 1}
            onClick={() => setPage((value) => value - 1)}
          >
            上一页
          </button>
          <span>第 {page} 页</span>
          <button
            type="button"
            disabled={disabled || loading || page * PAGE_SIZE >= total}
            onClick={() => setPage((value) => value + 1)}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
}

interface PurchaseOrderPickerProps {
  value: string;
  disabled: boolean;
  onSelect: (order: receiptApi.PurchaseReceiptOrderOption) => void;
}

function PurchaseOrderPicker({
  value,
  disabled,
  onSelect,
}: PurchaseOrderPickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<
    receiptApi.PurchaseReceiptOrderOption[]
  >([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const purchaseOrderVersion = useRef(0);

  useEffect(() => {
    const version = ++purchaseOrderVersion.current;
    setLoading(true);
    setError('');
    void receiptApi
      .searchPurchaseReceiptOrderOptions(page, PAGE_SIZE)
      .then((result) => {
        if (version !== purchaseOrderVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (version === purchaseOrderVersion.current) {
          setError(errorMessage(cause, '已审核采购订单加载失败'));
        }
      })
      .finally(() => {
        if (version === purchaseOrderVersion.current) setLoading(false);
      });
  }, [page, refreshKey]);

  const choose = (order: receiptApi.PurchaseReceiptOrderOption) => {
    const version = ++purchaseOrderVersion.current;
    setLoading(true);
    setError('');
    void receiptApi
      .getPurchaseReceiptOrderOption(order.docNo)
      .then((detail) => {
        if (version !== purchaseOrderVersion.current) return;
        if (detail.status !== 'APPROVED') {
          setError('该采购订单已不再处于已审核可收货状态，请重新选择');
          return;
        }
        onSelect(detail);
      })
      .catch((cause: unknown) => {
        if (version === purchaseOrderVersion.current) {
          setError(errorMessage(cause, '采购订单详情加载失败'));
        }
      })
      .finally(() => {
        if (version === purchaseOrderVersion.current) setLoading(false);
      });
  };

  return (
    <div className="reference-picker">
      <strong>已审核采购订单</strong>
      <div className="reference-picker-search">
        <button
          type="button"
          className="memory-button"
          disabled={disabled || loading}
          onClick={() => setRefreshKey((current) => current + 1)}
        >
          刷新订单
        </button>
      </div>
      {error && (
        <small className="reference-picker-error" role="alert">
          {error}
        </small>
      )}
      <div
        className="reference-picker-results"
        role="listbox"
        aria-label="已审核采购订单"
      >
        {loading ? (
          <span>正在加载已审核采购订单…</span>
        ) : items.length === 0 ? (
          <span>没有可收货的已审核采购订单</span>
        ) : (
          items.map((order) => (
            <button
              type="button"
              role="option"
              aria-selected={value === order.docNo}
              className={value === order.docNo ? 'reference-picker-selected' : ''}
              disabled={disabled}
              key={order.docNo}
              onClick={() => choose(order)}
            >
              {order.docNo}
              <span>
                {order.orderDate} · 总额 {order.totalAmount}
              </span>
            </button>
          ))
        )}
      </div>
      {total > PAGE_SIZE && (
        <div className="reference-picker-pagination">
          <button
            type="button"
            disabled={disabled || loading || page <= 1}
            onClick={() => setPage((current) => current - 1)}
          >
            上一页
          </button>
          <span>第 {page} 页</span>
          <button
            type="button"
            disabled={disabled || loading || page * PAGE_SIZE >= total}
            onClick={() => setPage((current) => current + 1)}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
}

interface PurchaseReceiptFormProps {
  form: receiptApi.PurchaseReceiptForm;
  setForm: (form: receiptApi.PurchaseReceiptForm) => void;
  saving: boolean;
  productNames: Map<number, string>;
  onProducts: (items: catalogApi.Product[]) => void;
  onWarehouses: (items: masterDataApi.Warehouse[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function PurchaseReceiptForm({
  form,
  setForm,
  saving,
  productNames,
  onProducts,
  onWarehouses,
  onSave,
  onCancel,
}: PurchaseReceiptFormProps) {
  const [order, setOrder] =
    useState<receiptApi.PurchaseReceiptOrderOption | null>(null);

  const selectOrder = (nextOrder: receiptApi.PurchaseReceiptOrderOption) => {
    setOrder(nextOrder);
    setForm({
      ...form,
      purchaseOrderNo: nextOrder.docNo,
      lines: [],
    });
    for (const line of nextOrder.lines) {
      void catalogApi
        .getProduct(line.productId)
        .then((product) => onProducts([product]))
        .catch(() => undefined);
    }
  };

  const addLine = (line: receiptApi.PurchaseReceiptOrderLineOption) => {
    if (form.lines.some((current) => current.poLineNo === line.poLineNo)) return;
    setForm({
      ...form,
      lines: [
        ...form.lines,
        {
          poLineNo: line.poLineNo,
          productId: line.productId,
          quantity: '',
          unitCost: line.unitPrice,
          outstandingQty: line.outstandingQty,
        },
      ],
    });
  };

  const setLine = (
    index: number,
    patch: Partial<receiptApi.PurchaseReceiptLineForm>,
  ) => {
    const lines = [...form.lines];
    const current = lines[index];
    if (!current) return;
    lines[index] = { ...current, ...patch };
    setForm({ ...form, lines });
  };

  const availableLines =
    order?.lines.filter((line) => hasOutstanding(line.outstandingQty)) ?? [];

  return (
    <form className="customer-form purchase-order-form" onSubmit={onSave}>
      <h2>新建采购入库单</h2>
      <PurchaseOrderPicker
        value={form.purchaseOrderNo}
        disabled={saving}
        onSelect={selectOrder}
      />
      <WarehousePicker
        label="收货仓库"
        value={form.warehouseId}
        enabledOnly
        disabled={saving}
        onChange={(warehouseId) => setForm({ ...form, warehouseId })}
        onOptions={onWarehouses}
      />
      <label>
        收货日期
        <input
          type="date"
          required
          disabled={saving}
          value={form.receiptDate}
          onChange={(event) =>
            setForm({ ...form, receiptDate: event.target.value })
          }
        />
      </label>
      <label>
        备注
        <textarea
          maxLength={255}
          disabled={saving}
          value={form.remark}
          onChange={(event) => setForm({ ...form, remark: event.target.value })}
        />
      </label>

      {order && (
        <div className="reference-picker">
          <strong>选择未收订单行</strong>
          <div
            className="reference-picker-results"
            role="listbox"
            aria-label="未收采购订单行"
          >
            {availableLines.length === 0 ? (
              <span>该订单已经没有未收数量</span>
            ) : (
              availableLines.map((line) => {
                const selected = form.lines.some(
                  (current) => current.poLineNo === line.poLineNo,
                );
                return (
                  <button
                    type="button"
                    role="option"
                    aria-selected={selected}
                    className={selected ? 'reference-picker-selected' : ''}
                    disabled={saving || selected}
                    key={line.poLineNo}
                    onClick={() => addLine(line)}
                  >
                    第 {line.poLineNo} 行 ·{' '}
                    {productNames.get(line.productId) ??
                      `商品 #${line.productId}（名称不可用）`}
                    <span>
                      订购 {line.quantity} · 已收 {line.receivedQty} · 未收{' '}
                      {line.outstandingQty}
                    </span>
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}

      <fieldset disabled={saving}>
        <legend>本次收货明细（至少一行）</legend>
        {form.lines.length === 0 ? (
          <p className="memory-empty">请先选择采购订单，再加入有未收量的订单行。</p>
        ) : (
          form.lines.map((line, index) => (
            <div className="purchase-order-line" key={line.poLineNo}>
              <strong>
                订单第 {line.poLineNo} 行 ·{' '}
                {productNames.get(line.productId) ??
                  `商品 #${line.productId}（名称不可用）`}
                <span>（未收 {line.outstandingQty}）</span>
              </strong>
              <label>
                本次收货数量
                <input
                  required
                  inputMode="decimal"
                  pattern={receiptApi.positiveDecimalPattern.source}
                  value={line.quantity}
                  onChange={(event) =>
                    setLine(index, { quantity: event.target.value })
                  }
                />
              </label>
              <label>
                收货单价
                <input
                  required
                  inputMode="decimal"
                  pattern={receiptApi.nonNegativeDecimalPattern.source}
                  value={line.unitCost}
                  onChange={(event) =>
                    setLine(index, { unitCost: event.target.value })
                  }
                />
              </label>
              <button
                type="button"
                className="memory-button"
                onClick={() =>
                  setForm({
                    ...form,
                    lines: form.lines.filter(
                      (_, currentIndex) => currentIndex !== index,
                    ),
                  })
                }
              >
                移除
              </button>
            </div>
          ))
        )}
      </fieldset>

      <div className="customer-actions">
        <button
          type="submit"
          className="memory-button memory-button-primary"
          disabled={saving || form.lines.length === 0}
        >
          {saving ? '创建中…' : '创建草稿'}
        </button>
        <button
          type="button"
          className="memory-button"
          disabled={saving}
          onClick={onCancel}
        >
          返回
        </button>
      </div>
    </form>
  );
}

interface PurchaseReceiptDetailsProps {
  receipt: receiptApi.PurchaseReceipt;
  warehouseName: (id: number) => string;
  productName: (id: number) => string;
  saving: boolean;
  onApprove: () => void;
  onPost: () => void;
}

function PurchaseReceiptDetails({
  receipt,
  warehouseName,
  productName,
  saving,
  onApprove,
  onPost,
}: PurchaseReceiptDetailsProps) {
  return (
    <>
      <StatusBadge status={receipt.status} />
      <h2>{receipt.docNo}</h2>
      <dl>
        <dt>采购订单</dt>
        <dd>{receipt.purchaseOrderNo}</dd>
        <dt>收货仓库</dt>
        <dd>{warehouseName(receipt.warehouseId)}</dd>
        <dt>收货日期</dt>
        <dd>{receipt.receiptDate}</dd>
        <dt>入库总额</dt>
        <dd>{receipt.totalAmount}</dd>
        <dt>备注</dt>
        <dd>{receipt.remark || '—'}</dd>
      </dl>
      <div className="memory-table-wrap purchase-order-lines">
        <table className="memory-table">
          <thead>
            <tr>
              <th>订单行</th>
              <th>商品</th>
              <th>收货数量</th>
              <th>收货单价</th>
              <th>金额</th>
            </tr>
          </thead>
          <tbody>
            {receipt.lines.map((line) => (
              <tr key={line.lineNo}>
                <td>{line.poLineNo}</td>
                <td>{productName(line.productId)}</td>
                <td>{line.quantity}</td>
                <td>{line.unitCost}</td>
                <td>{line.amount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {receipt.status === 'DRAFT' && (
        <div className="customer-actions">
          <button
            type="button"
            className="memory-button memory-button-primary"
            disabled={saving}
            onClick={onApprove}
          >
            {saving ? '处理中…' : '审核'}
          </button>
        </div>
      )}
      {receipt.status === 'APPROVED' && (
        <>
          <p className="reference-picker-error">
            过账将增加库存、回写采购订单到货量并生成会计凭证，完成后不能直接修改。
          </p>
          <div className="customer-actions">
            <button
              type="button"
              className="memory-button memory-button-primary"
              disabled={saving}
              onClick={onPost}
            >
              {saving ? '处理中…' : '过账'}
            </button>
          </div>
        </>
      )}
    </>
  );
}
