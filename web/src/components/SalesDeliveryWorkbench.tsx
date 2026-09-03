import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as deliveryApi from '../api/salesDeliveryApi.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<deliveryApi.SalesDeliveryStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

function emptyForm(): deliveryApi.SalesDeliveryForm {
  return {
    salesOrderNo: '',
    warehouseId: null,
    remark: '',
    lines: [],
  };
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}

export function SalesDeliveryWorkbench() {
  const [items, setItems] = useState<deliveryApi.SalesDelivery[]>([]);
  const [selected, setSelected] =
    useState<deliveryApi.SalesDelivery | null>(null);
  const [warehouseFilterDraft, setWarehouseFilterDraft] =
    useState<number | null>(null);
  const [salesOrderFilterDraft, setSalesOrderFilterDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [warehouseFilter, setWarehouseFilter] = useState<number | null>(null);
  const [salesOrderFilter, setSalesOrderFilter] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<deliveryApi.SalesDeliveryForm>(emptyForm);
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
  const warehouseNameVersion = useRef(0);
  const productVersion = useRef(0);
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

  const ensureWarehouseNames = (deliveries: deliveryApi.SalesDelivery[]) => {
    const ids = [
      ...new Set(
        deliveries
          .map((delivery) => delivery.warehouseId)
          .filter((id) => !warehouseNames.has(id)),
      ),
    ];
    if (ids.length === 0) return;
    const version = ++warehouseNameVersion.current;
    void Promise.all(
      ids.map((id) => masterDataApi.getWarehouse(id).catch(() => null)),
    ).then((warehouses) => {
      if (version !== warehouseNameVersion.current) return;
      rememberWarehouses(
        warehouses.filter(
          (warehouse): warehouse is masterDataApi.Warehouse =>
            warehouse !== null,
        ),
      );
    });
  };

  const ensureProductNames = (lines: Array<{ productId: number }>) => {
    const ids = [
      ...new Set(
        lines
          .map((line) => line.productId)
          .filter((id) => !productNames.has(id)),
      ),
    ];
    if (ids.length === 0) return;
    const version = ++productVersion.current;
    void Promise.all(
      ids.map((id) => catalogApi.getProduct(id).catch(() => null)),
    ).then((products) => {
      if (version !== productVersion.current) return;
      rememberProducts(
        products.filter(
          (product): product is catalogApi.Product => product !== null,
        ),
      );
    });
  };

  useEffect(() => {
    const version = ++listVersion.current;
    setLoading(true);
    setError('');
    void deliveryApi
      .searchSalesDeliveries(
        warehouseFilter,
        salesOrderFilter,
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
          setError(errorMessage(cause, '销售出库单列表加载失败'));
        }
      })
      .finally(() => {
        if (version === listVersion.current) setLoading(false);
      });
  }, [warehouseFilter, salesOrderFilter, status, page, refreshKey]);

  const invalidateDetail = () => {
    detailVersion.current += 1;
  };

  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (delivery: deliveryApi.SalesDelivery) => {
    const version = ++detailVersion.current;
    setSelected(delivery);
    setEditing(false);
    setError('');
    setNotice('');
    ensureWarehouseNames([delivery]);
    ensureProductNames(delivery.lines);
    void deliveryApi
      .getSalesDelivery(delivery.docNo)
      .then((detail) => {
        if (version !== detailVersion.current) return;
        setSelected(detail);
        ensureWarehouseNames([detail]);
        ensureProductNames(detail.lines);
      })
      .catch((cause: unknown) => {
        if (version === detailVersion.current) {
          setError(errorMessage(cause, '销售出库单详情加载失败'));
        }
      });
  };

  const applySearch = () => {
    clearSelection();
    setWarehouseFilter(warehouseFilterDraft);
    setSalesOrderFilter(salesOrderFilterDraft);
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

  const selectOrder = (order: deliveryApi.SalesDeliveryOrderOption) => {
    ensureProductNames(order.lines);
    setForm((current) => ({
      ...current,
      salesOrderNo: order.docNo,
      lines: order.lines.map((line) => ({
        soLineNo: line.soLineNo,
        productId: line.productId,
        quantity: '',
        remainingQty: line.remainingQty,
      })),
    }));
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (mutationInFlight.current) return;
    mutationInFlight.current = true;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = deliveryApi.validateSalesDeliveryForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await deliveryApi.createSalesDelivery(form);
      setSelected(result);
      setEditing(false);
      setNotice('销售出库单草稿已创建');
      ensureWarehouseNames([result]);
      ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '销售出库单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const transition = async (action: 'approve' | 'post' | 'cancel') => {
    if (!selected || mutationInFlight.current) return;
    if (
      action === 'post' &&
      !window.confirm(
        '过账将真实扣减库存，按移动加权固化 COGS，回写销售订单发货量并生成自动凭证。若库存不足，整张出库单会失败并全部回滚。此操作不可直接撤销，确认继续过账吗？',
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
          ? await deliveryApi.approveSalesDelivery(selected.docNo)
          : action === 'post'
            ? await deliveryApi.postSalesDelivery(selected.docNo)
            : await deliveryApi.cancelSalesDelivery(selected.docNo);
      setSelected(result);
      setNotice(
        action === 'approve'
          ? '销售出库单已审核'
          : action === 'post'
            ? '销售出库单已过账'
            : '销售出库单草稿已作废',
      );
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(
        errorMessage(
          cause,
          action === 'approve'
            ? '销售出库单审核失败'
            : action === 'post'
              ? '销售出库单过账失败'
              : '销售出库单作废失败',
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
          <p className="page-kicker">销售 / 发货出库</p>
          <h1>销售出库</h1>
          <p>
            按已审核或执行中的销售订单登记发货；过账后真实扣库存、固化 COGS
            并自动生成凭证。
          </p>
        </div>
        <button
          type="button"
          className="memory-button memory-button-primary"
          disabled={saving}
          onClick={startNew}
        >
          新建销售出库单
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
          销售订单号
          <input
            aria-label="销售订单号"
            placeholder="精确筛选 SO 单号"
            disabled={loading || saving}
            value={salesOrderFilterDraft}
            onChange={(event) => setSalesOrderFilterDraft(event.target.value)}
          />
        </label>
        <label>
          状态
          <select
            disabled={loading || saving}
            value={statusDraft}
            onChange={(event) => setStatusDraft(event.target.value)}
          >
            <option value="">全部状态</option>
            {Object.entries(statusLabels).map(([value, label]) => (
              <option value={value} key={value}>
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

      <div className="customer-workspace">
        <div className="customer-list-panel">
          <div className="memory-table-wrap">
            <table className="memory-table">
              <thead>
                <tr>
                  <th>出库单号</th>
                  <th>销售订单</th>
                  <th>仓库</th>
                  <th>状态</th>
                  <th>COGS</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={5}>正在加载销售出库单…</td>
                  </tr>
                ) : items.length === 0 ? (
                  <tr>
                    <td colSpan={5}>暂无销售出库单</td>
                  </tr>
                ) : (
                  items.map((delivery) => (
                    <tr key={delivery.docNo}>
                      <td>
                        <button
                          type="button"
                          className="table-link-button"
                          onClick={() => choose(delivery)}
                        >
                          {delivery.docNo}
                        </button>
                      </td>
                      <td>{delivery.salesOrderNo}</td>
                      <td>{warehouseName(delivery.warehouseId)}</td>
                      <td>{statusLabels[delivery.status]}</td>
                      <td>{delivery.totalCogs}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          <div className="customer-pagination">
            <button
              type="button"
              disabled={loading || saving || page <= 1}
              onClick={() => changePage(page - 1)}
            >
              上一页
            </button>
            <span>
              第 {page} 页，共 {total} 条
            </span>
            <button
              type="button"
              disabled={loading || saving || page * PAGE_SIZE >= total}
              onClick={() => changePage(page + 1)}
            >
              下一页
            </button>
          </div>
        </div>

        <aside className="customer-detail-panel">
          {editing ? (
            <SalesDeliveryForm
              form={form}
              setForm={setForm}
              saving={saving}
              productName={productName}
              onOrder={selectOrder}
              onWarehouses={rememberWarehouses}
              onSave={save}
              onCancel={() => {
                setEditing(false);
                setForm(emptyForm());
              }}
            />
          ) : selected ? (
            <SalesDeliveryDetails
              delivery={selected}
              warehouseName={warehouseName}
              productName={productName}
              saving={saving}
              onApprove={() => void transition('approve')}
              onPost={() => void transition('post')}
              onCancel={() => void transition('cancel')}
            />
          ) : (
            <p className="memory-empty">选择一张销售出库单查看详情，或新建草稿。</p>
          )}
        </aside>
      </div>
    </section>
  );
}

interface SalesDeliveryFormProps {
  form: deliveryApi.SalesDeliveryForm;
  setForm: (form: deliveryApi.SalesDeliveryForm) => void;
  saving: boolean;
  productName: (id: number) => string;
  onOrder: (order: deliveryApi.SalesDeliveryOrderOption) => void;
  onWarehouses: (items: masterDataApi.Warehouse[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function SalesDeliveryForm({
  form,
  setForm,
  saving,
  productName,
  onOrder,
  onWarehouses,
  onSave,
  onCancel,
}: SalesDeliveryFormProps) {
  const setLine = (
    index: number,
    patch: Partial<deliveryApi.SalesDeliveryLineForm>,
  ) => {
    const lines = [...form.lines];
    const current = lines[index];
    if (!current) return;
    lines[index] = { ...current, ...patch };
    setForm({ ...form, lines });
  };

  return (
    <form className="customer-form purchase-order-form" onSubmit={onSave}>
      <h2>新建销售出库单</h2>
      <SalesOrderPicker
        disabled={saving}
        selectedDocNo={form.salesOrderNo}
        onSelect={onOrder}
      />
      <WarehousePicker
        label="出库仓库"
        value={form.warehouseId}
        enabledOnly
        allowClear={false}
        disabled={saving}
        onChange={(warehouseId) => setForm({ ...form, warehouseId })}
        onOptions={onWarehouses}
      />
      <label>
        备注
        <textarea
          maxLength={255}
          disabled={saving}
          value={form.remark}
          onChange={(event) => setForm({ ...form, remark: event.target.value })}
        />
      </label>

      <fieldset disabled={saving}>
        <legend>发货明细（可移除本次不发的行）</legend>
        {form.lines.length === 0 ? (
          <p className="memory-empty">请先选择仍有未发数量的销售订单。</p>
        ) : (
          form.lines.map((line, index) => (
            <div className="purchase-order-line" key={line.soLineNo}>
              <strong>{productName(line.productId)}</strong>
              <span>剩余可发：{line.remainingQty}</span>
              <label>
                本次发货数量
                <input
                  required
                  inputMode="decimal"
                  pattern={deliveryApi.positiveDecimalPattern.source}
                  value={line.quantity}
                  onChange={(event) =>
                    setLine(index, { quantity: event.target.value })
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
                本次不发
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

function SalesOrderPicker({
  disabled,
  selectedDocNo,
  onSelect,
}: {
  disabled: boolean;
  selectedDocNo: string;
  onSelect: (order: deliveryApi.SalesDeliveryOrderOption) => void;
}) {
  const [items, setItems] = useState<
    deliveryApi.SalesDeliveryOrderOption[]
  >([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const salesOrderVersion = useRef(0);

  useEffect(() => {
    const version = ++salesOrderVersion.current;
    setLoading(true);
    setError('');
    void deliveryApi
      .searchSalesDeliveryOrderOptions(page, PAGE_SIZE)
      .then((result) => {
        if (version !== salesOrderVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (version === salesOrderVersion.current) {
          setError(errorMessage(cause, '可发货销售订单加载失败'));
        }
      })
      .finally(() => {
        if (version === salesOrderVersion.current) setLoading(false);
      });
  }, [page, refreshKey]);

  const choose = (order: deliveryApi.SalesDeliveryOrderOption) => {
    const version = ++salesOrderVersion.current;
    setLoading(true);
    setError('');
    void deliveryApi
      .getSalesDeliveryOrderOption(order.docNo)
      .then((detail) => {
        if (version === salesOrderVersion.current) onSelect(detail);
      })
      .catch((cause: unknown) => {
        if (version === salesOrderVersion.current) {
          setError(errorMessage(cause, '销售订单已不可发货，请刷新候选'));
        }
      })
      .finally(() => {
        if (version === salesOrderVersion.current) setLoading(false);
      });
  };

  return (
    <div className="reference-picker">
      <div className="reference-picker-heading">
        <strong>可发货销售订单</strong>
        <button
          type="button"
          className="memory-button"
          disabled={disabled || loading}
          onClick={() => setRefreshKey((value) => value + 1)}
        >
          刷新候选
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
        aria-label="销售订单选择"
      >
        {loading ? (
          <span>正在加载可发货销售订单…</span>
        ) : items.length === 0 ? (
          <span>暂无已审核或执行中且仍有未发数量的销售订单</span>
        ) : (
          items.map((order) => (
            <button
              type="button"
              role="option"
              aria-selected={selectedDocNo === order.docNo}
              className={
                selectedDocNo === order.docNo
                  ? 'reference-picker-selected'
                  : ''
              }
              disabled={disabled}
              key={order.docNo}
              onClick={() => choose(order)}
            >
              {order.docNo}
              <span>
                客户 #{order.customerId} · {order.status === 'APPROVED' ? '已审核' : '执行中'} ·
                未发完行 {order.lines.length}
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

interface WarehousePickerProps {
  label: string;
  value: number | null;
  enabledOnly: boolean;
  allowClear: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Warehouse[]) => void;
}

function WarehousePicker({
  label,
  value,
  enabledOnly,
  allowClear,
  disabled,
  onChange,
  onOptions,
}: WarehousePickerProps) {
  const [items, setItems] = useState<masterDataApi.Warehouse[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const warehouseVersion = useRef(0);

  useEffect(() => {
    const version = ++warehouseVersion.current;
    setLoading(true);
    setError('');
    void masterDataApi
      .searchWarehouses('', enabledOnly ? 'ENABLED' : '', page)
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
  }, [enabledOnly, page, refreshKey]);

  return (
    <div className="reference-picker">
      <div className="reference-picker-heading">
        <strong>{label}</strong>
        <button
          type="button"
          className="memory-button"
          disabled={disabled || loading}
          onClick={() => setRefreshKey((current) => current + 1)}
        >
          刷新仓库
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
        aria-label={label}
      >
        {allowClear && (
          <button
            type="button"
            role="option"
            aria-selected={value === null}
            disabled={disabled || loading}
            onClick={() => onChange(null)}
          >
            全部仓库
          </button>
        )}
        {loading ? (
          <span>正在加载仓库…</span>
        ) : items.length === 0 ? (
          <span>{enabledOnly ? '暂无启用中的仓库' : '暂无仓库'}</span>
        ) : (
          items.map((warehouse) => (
            <button
              type="button"
              role="option"
              aria-selected={value === warehouse.id}
              className={
                value === warehouse.id ? 'reference-picker-selected' : ''
              }
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

function SalesDeliveryDetails({
  delivery,
  warehouseName,
  productName,
  saving,
  onApprove,
  onPost,
  onCancel,
}: {
  delivery: deliveryApi.SalesDelivery;
  warehouseName: (id: number) => string;
  productName: (id: number) => string;
  saving: boolean;
  onApprove: () => void;
  onPost: () => void;
  onCancel: () => void;
}) {
  return (
    <>
      <span className={`status-badge status-${delivery.status.toLowerCase()}`}>
        {statusLabels[delivery.status]}
      </span>
      <h2>{delivery.docNo}</h2>
      <dl>
        <dt>销售订单</dt>
        <dd>{delivery.salesOrderNo}</dd>
        <dt>出库仓库</dt>
        <dd>{warehouseName(delivery.warehouseId)}</dd>
        <dt>出库总成本 COGS</dt>
        <dd>{delivery.totalCogs}</dd>
        <dt>备注</dt>
        <dd>{delivery.remark || '—'}</dd>
      </dl>
      <div className="memory-table-wrap purchase-order-lines">
        <table className="memory-table">
          <thead>
            <tr>
              <th>商品</th>
              <th>订单行</th>
              <th>发货数量</th>
              <th>COGS</th>
            </tr>
          </thead>
          <tbody>
            {delivery.lines.map((line) => (
              <tr key={line.lineNo}>
                <td>{productName(line.productId)}</td>
                <td>{line.soLineNo}</td>
                <td>{line.quantity}</td>
                <td>{line.cogsAmount ?? '过账后固化'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {delivery.status === 'DRAFT' && (
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
            作废
          </button>
        </div>
      )}
      {delivery.status === 'APPROVED' && (
        <>
          <p className="memory-warning">
            过账将真实扣减库存，按移动加权固化 COGS，回写销售订单发货量并生成自动凭证。
            库存不足时整张出库单失败并全部回滚。
          </p>
          <div className="customer-actions">
            <button
              type="button"
              className="memory-button memory-button-primary"
              disabled={saving}
              onClick={onPost}
            >
              {saving ? '过账中…' : '过账'}
            </button>
          </div>
        </>
      )}
    </>
  );
}
