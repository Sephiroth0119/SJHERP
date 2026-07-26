import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as orderApi from '../api/purchaseOrderApi.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<orderApi.PurchaseOrderStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '执行中',
  COMPLETED: '已关闭',
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

function emptyForm(): orderApi.PurchaseOrderForm {
  return {
    supplierId: null,
    orderDate: today(),
    remark: '',
    lines: [],
  };
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}

export function PurchaseOrderWorkbench() {
  const [items, setItems] = useState<orderApi.PurchaseOrder[]>([]);
  const [selected, setSelected] = useState<orderApi.PurchaseOrder | null>(null);
  const [supplierFilterDraft, setSupplierFilterDraft] = useState<number | null>(
    null,
  );
  const [statusDraft, setStatusDraft] = useState('');
  const [supplierFilter, setSupplierFilter] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<orderApi.PurchaseOrderForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [supplierNames, setSupplierNames] = useState<Map<number, string>>(
    new Map(),
  );
  const [productNames, setProductNames] = useState<Map<number, string>>(
    new Map(),
  );
  const listVersion = useRef(0);
  const detailVersion = useRef(0);
  const mutationInFlight = useRef(false);

  const rememberSuppliers = (suppliers: masterDataApi.Supplier[]) => {
    if (suppliers.length === 0) return;
    setSupplierNames((current) => {
      const next = new Map(current);
      for (const supplier of suppliers) {
        next.set(supplier.id, `${supplier.name}（${supplier.code}）`);
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

  const ensureSupplierNames = (orders: orderApi.PurchaseOrder[]) => {
    const ids = [
      ...new Set(
        orders
          .map((order) => order.supplierId)
          .filter((id) => !supplierNames.has(id)),
      ),
    ];
    for (const id of ids) {
      void masterDataApi
        .getSupplier(id)
        .then((supplier) => rememberSuppliers([supplier]))
        .catch(() => undefined);
    }
  };

  const ensureProductNames = (order: orderApi.PurchaseOrder) => {
    const ids = [
      ...new Set(
        order.lines
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
    void orderApi
      .searchPurchaseOrders(supplierFilter, status, page, PAGE_SIZE)
      .then((result) => {
        if (version !== listVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        ensureSupplierNames(result.items);
      })
      .catch((cause: unknown) => {
        if (version === listVersion.current) {
          setError(errorMessage(cause, '采购订单列表加载失败'));
        }
      })
      .finally(() => {
        if (version === listVersion.current) setLoading(false);
      });
  }, [supplierFilter, status, page, refreshKey]);

  const invalidateDetail = () => {
    detailVersion.current += 1;
  };

  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (order: orderApi.PurchaseOrder) => {
    const version = ++detailVersion.current;
    setSelected(order);
    setEditing(false);
    setError('');
    setNotice('');
    ensureSupplierNames([order]);
    ensureProductNames(order);
    void orderApi
      .getPurchaseOrder(order.docNo)
      .then((detail) => {
        if (version !== detailVersion.current) return;
        setSelected(detail);
        ensureSupplierNames([detail]);
        ensureProductNames(detail);
      })
      .catch((cause: unknown) => {
        if (version === detailVersion.current) {
          setError(errorMessage(cause, '采购订单详情加载失败'));
        }
      });
  };

  const applySearch = () => {
    clearSelection();
    setSupplierFilter(supplierFilterDraft);
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
      const validationError = orderApi.validatePurchaseOrderForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await orderApi.createPurchaseOrder(form);
      setSelected(result);
      setEditing(false);
      setNotice('采购订单草稿已创建');
      ensureSupplierNames([result]);
      ensureProductNames(result);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '采购订单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const transition = async (action: 'approve' | 'close') => {
    if (!selected || mutationInFlight.current) return;
    mutationInFlight.current = true;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result =
        action === 'approve'
          ? await orderApi.approvePurchaseOrder(selected.docNo)
          : await orderApi.closePurchaseOrder(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '采购订单已审核' : '采购订单已关闭');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(
        errorMessage(
          cause,
          action === 'approve' ? '采购订单审核失败' : '采购订单关闭失败',
        ),
      );
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const supplierName = (id: number) =>
    supplierNames.get(id) ?? `供应商 #${id}（名称不可用）`;
  const productName = (id: number) =>
    productNames.get(id) ?? `商品 #${id}（名称不可用）`;

  return (
    <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">采购 / 采购执行</p>
          <h1>采购订单</h1>
          <p>创建采购承诺，审核后等待收货，业务结束时关闭。</p>
        </div>
        <button
          type="button"
          className="memory-button memory-button-primary"
          disabled={saving}
          onClick={startNew}
        >
          新建采购订单
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
        <SupplierPicker
          label="按供应商筛选"
          value={supplierFilterDraft}
          enabledOnly={false}
          allowClear
          disabled={loading || saving}
          onChange={setSupplierFilterDraft}
          onOptions={rememberSuppliers}
        />
        <label>
          订单状态
          <select
            aria-label="订单状态"
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
            <p className="memory-empty">正在加载采购订单…</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">暂无采购订单，请调整条件或创建草稿。</p>
          ) : (
            <div className="memory-table-wrap">
              <table className="memory-table">
                <thead>
                  <tr>
                    <th>单据号</th>
                    <th>供应商</th>
                    <th>下单日期</th>
                    <th>总额</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((order) => (
                    <tr
                      key={order.docNo}
                      tabIndex={0}
                      aria-selected={selected?.docNo === order.docNo}
                      className={
                        selected?.docNo === order.docNo
                          ? 'memory-row-selected'
                          : ''
                      }
                      onClick={() => choose(order)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          choose(order);
                        }
                      }}
                    >
                      <td>{order.docNo}</td>
                      <td>{supplierName(order.supplierId)}</td>
                      <td>{order.orderDate}</td>
                      <td>{order.totalAmount}</td>
                      <td>
                        <StatusBadge status={order.status} />
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
            <PurchaseOrderForm
              form={form}
              setForm={setForm}
              saving={saving}
              productNames={productNames}
              onProducts={rememberProducts}
              onSuppliers={rememberSuppliers}
              onSave={save}
              onCancel={() => setEditing(false)}
            />
          ) : selected ? (
            <PurchaseOrderDetails
              order={selected}
              supplierName={supplierName}
              productName={productName}
              saving={saving}
              onApprove={() => void transition('approve')}
              onClose={() => void transition('close')}
            />
          ) : (
            <p className="memory-empty">选择左侧订单查看详情。</p>
          )}
        </aside>
      </div>
    </section>
  );
}

function StatusBadge({ status }: { status: orderApi.PurchaseOrderStatus }) {
  return (
    <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
      {statusLabels[status]}
    </span>
  );
}

interface SupplierPickerProps {
  label: string;
  value: number | null;
  enabledOnly: boolean;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Supplier[]) => void;
}

function SupplierPicker({
  label,
  value,
  enabledOnly,
  allowClear = false,
  disabled,
  onChange,
  onOptions,
}: SupplierPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<masterDataApi.Supplier[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const version = useRef(0);

  useEffect(() => {
    const current = ++version.current;
    setLoading(true);
    setError('');
    void masterDataApi
      .searchSuppliers(keyword, enabledOnly ? 'ENABLED' : '', page)
      .then((result) => {
        if (current !== version.current) return;
        setItems(result.items);
        setTotal(result.total);
        onOptions(result.items);
      })
      .catch((cause: unknown) => {
        if (current === version.current) {
          setError(errorMessage(cause, '供应商加载失败'));
        }
      })
      .finally(() => {
        if (current === version.current) setLoading(false);
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
          placeholder="输入供应商编码或名称"
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
          <span>正在加载供应商…</span>
        ) : items.length === 0 ? (
          <span>未找到供应商</span>
        ) : (
          items.map((supplier) => (
            <button
              type="button"
              role="option"
              aria-selected={value === supplier.id}
              className={value === supplier.id ? 'reference-picker-selected' : ''}
              disabled={disabled}
              key={supplier.id}
              onClick={() => onChange(supplier.id)}
            >
              {supplier.name}
              <span>{supplier.code}</span>
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

interface PurchaseOrderFormProps {
  form: orderApi.PurchaseOrderForm;
  setForm: (form: orderApi.PurchaseOrderForm) => void;
  saving: boolean;
  productNames: Map<number, string>;
  onProducts: (items: catalogApi.Product[]) => void;
  onSuppliers: (items: masterDataApi.Supplier[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function PurchaseOrderForm({
  form,
  setForm,
  saving,
  productNames,
  onProducts,
  onSuppliers,
  onSave,
  onCancel,
}: PurchaseOrderFormProps) {
  const setLine = (
    index: number,
    patch: Partial<orderApi.PurchaseOrderLineForm>,
  ) => {
    const lines = [...form.lines];
    const current = lines[index];
    if (!current) return;
    lines[index] = { ...current, ...patch };
    setForm({ ...form, lines });
  };

  const addProduct = (product: catalogApi.Product) => {
    onProducts([product]);
    if (form.lines.some((line) => line.productId === product.id)) return;
    setForm({
      ...form,
      lines: [
        ...form.lines,
        { productId: product.id, quantity: '', unitPrice: '' },
      ],
    });
  };

  return (
    <form className="customer-form purchase-order-form" onSubmit={onSave}>
      <h2>新建采购订单</h2>
      <SupplierPicker
        label="供应商"
        value={form.supplierId}
        enabledOnly
        disabled={saving}
        onChange={(supplierId) => setForm({ ...form, supplierId })}
        onOptions={onSuppliers}
      />
      <label>
        下单日期
        <input
          type="date"
          required
          disabled={saving}
          value={form.orderDate}
          onChange={(event) =>
            setForm({ ...form, orderDate: event.target.value })
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

      <ProductPicker
        disabled={saving}
        selectedIds={new Set(form.lines.map((line) => line.productId))}
        onAdd={addProduct}
        onOptions={onProducts}
      />

      <fieldset disabled={saving}>
        <legend>采购明细（至少一行）</legend>
        {form.lines.length === 0 ? (
          <p className="memory-empty">请先从商品选择器加入商品。</p>
        ) : (
          form.lines.map((line, index) => (
            <div className="purchase-order-line" key={line.productId}>
              <strong>
                {line.productId === null
                  ? '请选择商品'
                  : productNames.get(line.productId) ??
                    `商品 #${line.productId}（名称不可用）`}
              </strong>
              <label>
                数量
                <input
                  required
                  inputMode="decimal"
                  pattern={orderApi.positiveDecimalPattern.source}
                  value={line.quantity}
                  onChange={(event) =>
                    setLine(index, { quantity: event.target.value })
                  }
                />
              </label>
              <label>
                单价
                <input
                  required
                  inputMode="decimal"
                  pattern={orderApi.nonNegativeDecimalPattern.source}
                  value={line.unitPrice}
                  onChange={(event) =>
                    setLine(index, { unitPrice: event.target.value })
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
          取消
        </button>
      </div>
    </form>
  );
}

interface ProductPickerProps {
  disabled: boolean;
  selectedIds: Set<number | null>;
  onAdd: (product: catalogApi.Product) => void;
  onOptions: (items: catalogApi.Product[]) => void;
}

function ProductPicker({
  disabled,
  selectedIds,
  onAdd,
  onOptions,
}: ProductPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<catalogApi.Product[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const version = useRef(0);

  useEffect(() => {
    const current = ++version.current;
    setLoading(true);
    setError('');
    void catalogApi
      .searchProducts(keyword, 'ENABLED', page)
      .then((result) => {
        if (current !== version.current) return;
        setItems(result.items);
        setTotal(result.total);
        onOptions(result.items);
      })
      .catch((cause: unknown) => {
        if (current === version.current) {
          setError(errorMessage(cause, '商品加载失败'));
        }
      })
      .finally(() => {
        if (current === version.current) setLoading(false);
      });
  }, [keyword, page, refreshKey]);

  const search = () => {
    setKeyword(draft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  return (
    <div className="reference-picker">
      <strong>添加商品</strong>
      <div className="reference-picker-search">
        <input
          aria-label="商品关键词"
          placeholder="输入商品编码、名称或条码"
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
      </div>
      {error && (
        <small className="reference-picker-error" role="alert">
          {error}
        </small>
      )}
      <div
        className="reference-picker-results"
        role="listbox"
        aria-label="商品选择"
      >
        {loading ? (
          <span>正在加载商品…</span>
        ) : items.length === 0 ? (
          <span>未找到启用中的商品</span>
        ) : (
          items.map((product) => {
            const selected = selectedIds.has(product.id);
            return (
              <button
                type="button"
                role="option"
                aria-selected={selected}
                className={selected ? 'reference-picker-selected' : ''}
                disabled={disabled || selected}
                key={product.id}
                onClick={() => onAdd(product)}
              >
                {product.name}
                <span>{product.code}</span>
              </button>
            );
          })
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

interface PurchaseOrderDetailsProps {
  order: orderApi.PurchaseOrder;
  supplierName: (id: number) => string;
  productName: (id: number) => string;
  saving: boolean;
  onApprove: () => void;
  onClose: () => void;
}

function PurchaseOrderDetails({
  order,
  supplierName,
  productName,
  saving,
  onApprove,
  onClose,
}: PurchaseOrderDetailsProps) {
  return (
    <>
      <StatusBadge status={order.status} />
      <h2>{order.docNo}</h2>
      <dl>
        <dt>供应商</dt>
        <dd>{supplierName(order.supplierId)}</dd>
        <dt>下单日期</dt>
        <dd>{order.orderDate}</dd>
        <dt>总额</dt>
        <dd>{order.totalAmount}</dd>
        <dt>备注</dt>
        <dd>{order.remark || '—'}</dd>
      </dl>
      <div className="memory-table-wrap purchase-order-lines">
        <table className="memory-table">
          <thead>
            <tr>
              <th>商品</th>
              <th>数量</th>
              <th>单价</th>
              <th>金额</th>
              <th>已到 / 未到</th>
            </tr>
          </thead>
          <tbody>
            {order.lines.map((line) => (
              <tr key={line.lineNo}>
                <td>{productName(line.productId)}</td>
                <td>{line.quantity}</td>
                <td>{line.unitPrice}</td>
                <td>{line.amount}</td>
                <td>
                  {line.receivedQty} / {line.outstandingQty}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {order.status === 'DRAFT' && (
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
      {order.status === 'APPROVED' && (
        <div className="customer-actions">
          <button
            type="button"
            className="memory-button memory-button-primary"
            disabled={saving}
            onClick={onClose}
          >
            {saving ? '处理中…' : '关闭订单'}
          </button>
        </div>
      )}
    </>
  );
}
