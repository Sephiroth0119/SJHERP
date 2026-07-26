import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import * as customerApi from '../api/customerApi.ts';
import { ApiError } from '../api/http.ts';
import * as orderApi from '../api/salesOrderApi.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<orderApi.SalesOrderStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '执行中',
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

function emptyForm(): orderApi.SalesOrderForm {
  return {
    customerId: null,
    orderDate: today(),
    remark: '',
    lines: [],
  };
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}

export function SalesOrderWorkbench() {
  const [items, setItems] = useState<orderApi.SalesOrder[]>([]);
  const [selected, setSelected] = useState<orderApi.SalesOrder | null>(null);
  const [customerFilterDraft, setCustomerFilterDraft] = useState<number | null>(
    null,
  );
  const [statusDraft, setStatusDraft] = useState('');
  const [customerFilter, setCustomerFilter] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<orderApi.SalesOrderForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [customerNames, setCustomerNames] = useState<Map<number, string>>(
    new Map(),
  );
  const [productNames, setProductNames] = useState<Map<number, string>>(
    new Map(),
  );
  const listVersion = useRef(0);
  const detailVersion = useRef(0);
  const mutationInFlight = useRef(false);

  const rememberCustomers = (customers: customerApi.Customer[]) => {
    if (customers.length === 0) return;
    setCustomerNames((current) => {
      const next = new Map(current);
      for (const customer of customers) {
        next.set(customer.id, `${customer.name}（${customer.code}）`);
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

  const ensureCustomerNames = (orders: orderApi.SalesOrder[]) => {
    const ids = [
      ...new Set(
        orders
          .map((order) => order.customerId)
          .filter((id) => !customerNames.has(id)),
      ),
    ];
    for (const id of ids) {
      void customerApi
        .getCustomer(id)
        .then((customer) => rememberCustomers([customer]))
        .catch(() => undefined);
    }
  };

  const ensureProductNames = (order: orderApi.SalesOrder) => {
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
      .searchSalesOrders(customerFilter, status, page, PAGE_SIZE)
      .then((result) => {
        if (version !== listVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        ensureCustomerNames(result.items);
      })
      .catch((cause: unknown) => {
        if (version === listVersion.current) {
          setError(errorMessage(cause, '销售订单列表加载失败'));
        }
      })
      .finally(() => {
        if (version === listVersion.current) setLoading(false);
      });
  }, [customerFilter, status, page, refreshKey]);

  const invalidateDetail = () => {
    detailVersion.current += 1;
  };

  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (order: orderApi.SalesOrder) => {
    const version = ++detailVersion.current;
    setSelected(order);
    setEditing(false);
    setError('');
    setNotice('');
    ensureCustomerNames([order]);
    ensureProductNames(order);
    void orderApi
      .getSalesOrder(order.docNo)
      .then((detail) => {
        if (version !== detailVersion.current) return;
        setSelected(detail);
        ensureCustomerNames([detail]);
        ensureProductNames(detail);
      })
      .catch((cause: unknown) => {
        if (version === detailVersion.current) {
          setError(errorMessage(cause, '销售订单详情加载失败'));
        }
      });
  };

  const applySearch = () => {
    clearSelection();
    setCustomerFilter(customerFilterDraft);
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
      const validationError = orderApi.validateSalesOrderForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await orderApi.createSalesOrder(form);
      setSelected(result.order);
      setEditing(false);
      setNotice(
        result.warnings.length === 0
          ? '销售订单草稿已创建'
          : `销售订单草稿已创建；${result.warnings.join('；')}`,
      );
      ensureCustomerNames([result.order]);
      ensureProductNames(result.order);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '销售订单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const transition = async (action: 'approve' | 'cancel') => {
    if (!selected || mutationInFlight.current) return;
    mutationInFlight.current = true;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result =
        action === 'approve'
          ? await orderApi.approveSalesOrder(selected.docNo)
          : await orderApi.cancelSalesOrder(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '销售订单已审核' : '销售订单已作废');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(
        errorMessage(
          cause,
          action === 'approve' ? '销售订单审核失败' : '销售订单作废失败',
        ),
      );
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const customerName = (id: number) =>
    customerNames.get(id) ?? `客户 #${id}（名称不可用）`;
  const productName = (id: number) =>
    productNames.get(id) ?? `商品 #${id}（名称不可用）`;

  return (
    <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">销售 / 销售执行</p>
          <h1>销售订单</h1>
          <p>记录客户订货约定，审核后可据此安排销售出库。</p>
        </div>
        <button
          type="button"
          className="memory-button memory-button-primary"
          disabled={saving}
          onClick={startNew}
        >
          新建销售订单
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
        <CustomerPicker
          label="按客户筛选"
          value={customerFilterDraft}
          enabledOnly={false}
          allowClear
          disabled={loading || saving}
          onChange={setCustomerFilterDraft}
          onOptions={rememberCustomers}
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
            <p className="memory-empty">正在加载销售订单…</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">暂无销售订单，请调整条件或创建草稿。</p>
          ) : (
            <div className="memory-table-wrap">
              <table className="memory-table">
                <thead>
                  <tr>
                    <th>单据号</th>
                    <th>客户</th>
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
                      <td>{customerName(order.customerId)}</td>
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
            <SalesOrderForm
              form={form}
              setForm={setForm}
              saving={saving}
              productNames={productNames}
              onProducts={rememberProducts}
              onCustomers={rememberCustomers}
              onSave={save}
              onCancel={() => setEditing(false)}
            />
          ) : selected ? (
            <SalesOrderDetails
              order={selected}
              customerName={customerName}
              productName={productName}
              saving={saving}
              onApprove={() => void transition('approve')}
              onCancel={() => void transition('cancel')}
            />
          ) : (
            <p className="memory-empty">选择左侧订单查看详情。</p>
          )}
        </aside>
      </div>
    </section>
  );
}

function StatusBadge({ status }: { status: orderApi.SalesOrderStatus }) {
  return (
    <span
      className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}
    >
      {statusLabels[status]}
    </span>
  );
}

interface CustomerPickerProps {
  label: string;
  value: number | null;
  enabledOnly: boolean;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: customerApi.Customer[]) => void;
}

function CustomerPicker({
  label,
  value,
  enabledOnly,
  allowClear = false,
  disabled,
  onChange,
  onOptions,
}: CustomerPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<customerApi.Customer[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const version = useRef(0);

  useEffect(() => {
    const current = ++version.current;
    setLoading(true);
    setError('');
    void customerApi
      .searchCustomers(keyword, enabledOnly ? 'ENABLED' : '', page, PAGE_SIZE)
      .then((result) => {
        if (current !== version.current) return;
        setItems(result.items);
        setTotal(result.total);
        onOptions(result.items);
      })
      .catch((cause: unknown) => {
        if (current === version.current) {
          setError(errorMessage(cause, '客户加载失败'));
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
          placeholder="输入客户编码或名称"
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
          <span>正在加载客户…</span>
        ) : items.length === 0 ? (
          <span>未找到客户</span>
        ) : (
          items.map((customer) => (
            <button
              type="button"
              role="option"
              aria-selected={value === customer.id}
              className={value === customer.id ? 'reference-picker-selected' : ''}
              disabled={disabled}
              key={customer.id}
              onClick={() => onChange(customer.id)}
            >
              {customer.name}
              <span>{customer.code}</span>
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

interface SalesOrderFormProps {
  form: orderApi.SalesOrderForm;
  setForm: (form: orderApi.SalesOrderForm) => void;
  saving: boolean;
  productNames: Map<number, string>;
  onProducts: (items: catalogApi.Product[]) => void;
  onCustomers: (items: customerApi.Customer[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function SalesOrderForm({
  form,
  setForm,
  saving,
  productNames,
  onProducts,
  onCustomers,
  onSave,
  onCancel,
}: SalesOrderFormProps) {
  const setLine = (
    index: number,
    patch: Partial<orderApi.SalesOrderLineForm>,
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
      <h2>新建销售订单</h2>
      <CustomerPicker
        label="客户"
        value={form.customerId}
        enabledOnly
        disabled={saving}
        onChange={(customerId) => setForm({ ...form, customerId })}
        onOptions={onCustomers}
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
        <legend>销售明细（至少一行）</legend>
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

interface SalesOrderDetailsProps {
  order: orderApi.SalesOrder;
  customerName: (id: number) => string;
  productName: (id: number) => string;
  saving: boolean;
  onApprove: () => void;
  onCancel: () => void;
}

function SalesOrderDetails({
  order,
  customerName,
  productName,
  saving,
  onApprove,
  onCancel,
}: SalesOrderDetailsProps) {
  return (
    <>
      <StatusBadge status={order.status} />
      <h2>{order.docNo}</h2>
      <dl>
        <dt>客户</dt>
        <dd>{customerName(order.customerId)}</dd>
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
              <th>已发 / 未发</th>
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
                  {line.deliveredQty} / {line.remainingQty}
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
          <button
            type="button"
            className="memory-button"
            disabled={saving}
            onClick={onCancel}
          >
            {saving ? '处理中…' : '作废'}
          </button>
        </div>
      )}
    </>
  );
}
