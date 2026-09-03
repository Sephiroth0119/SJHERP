import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as invoiceApi from '../api/purchaseInvoiceApi.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<invoiceApi.PurchaseInvoiceStatus, string> = {
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

function emptyForm(): invoiceApi.PurchaseInvoiceForm {
  return {
    purchaseReceiptNo: '',
    invoiceDate: today(),
    supplierInvoiceNo: '',
    remark: '',
    lines: [],
  };
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}

export function PurchaseInvoiceWorkbench() {
  const [items, setItems] = useState<invoiceApi.PurchaseInvoice[]>([]);
  const [selected, setSelected] = useState<invoiceApi.PurchaseInvoice | null>(
    null,
  );
  const [supplierFilterDraft, setSupplierFilterDraft] = useState<number | null>(
    null,
  );
  const [receiptFilterDraft, setReceiptFilterDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [supplierFilter, setSupplierFilter] = useState<number | null>(null);
  const [receiptFilter, setReceiptFilter] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<invoiceApi.PurchaseInvoiceForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [supplierNames, setSupplierNames] = useState<Map<number, string>>(
    new Map(),
  );
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(
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

  const ensureSupplierNames = (invoices: invoiceApi.PurchaseInvoice[]) => {
    const ids = [
      ...new Set(
        invoices
          .map((invoice) => invoice.supplierId)
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

  const ensureWarehouseNames = (
    receipts: invoiceApi.PurchaseInvoiceReceiptOption[],
  ) => {
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

  const ensureProductNames = (lines: Array<{ productId: number }>) => {
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
    void invoiceApi
      .searchPurchaseInvoices(
        supplierFilter,
        receiptFilter,
        status,
        page,
        PAGE_SIZE,
      )
      .then((result) => {
        if (version !== listVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        ensureSupplierNames(result.items);
      })
      .catch((cause: unknown) => {
        if (version === listVersion.current) {
          setError(errorMessage(cause, '采购发票列表加载失败'));
        }
      })
      .finally(() => {
        if (version === listVersion.current) setLoading(false);
      });
  }, [supplierFilter, receiptFilter, status, page, refreshKey]);

  const invalidateDetail = () => {
    detailVersion.current += 1;
  };

  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (invoice: invoiceApi.PurchaseInvoice) => {
    const version = ++detailVersion.current;
    setSelected(invoice);
    setEditing(false);
    setError('');
    setNotice('');
    ensureSupplierNames([invoice]);
    ensureProductNames(invoice.lines);
    void invoiceApi
      .getPurchaseInvoice(invoice.docNo)
      .then((detail) => {
        if (version !== detailVersion.current) return;
        setSelected(detail);
        ensureSupplierNames([detail]);
        ensureProductNames(detail.lines);
      })
      .catch((cause: unknown) => {
        if (version === detailVersion.current) {
          setError(errorMessage(cause, '采购发票详情加载失败'));
        }
      });
  };

  const applySearch = () => {
    clearSelection();
    setSupplierFilter(supplierFilterDraft);
    setReceiptFilter(receiptFilterDraft);
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
      const validationError = invoiceApi.validatePurchaseInvoiceForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await invoiceApi.createPurchaseInvoice(form);
      setSelected(result);
      setEditing(false);
      setNotice('采购发票草稿已创建');
      ensureSupplierNames([result]);
      ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '采购发票创建失败，请检查输入'));
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
        '过账将回写入库行已开票量、生成应付账款与会计凭证。此操作不可直接撤销，确认继续过账吗？',
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
          ? await invoiceApi.approvePurchaseInvoice(selected.docNo)
          : await invoiceApi.postPurchaseInvoice(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '采购发票已审核' : '采购发票已过账');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(
        errorMessage(
          cause,
          action === 'approve' ? '采购发票审核失败' : '采购发票过账失败',
        ),
      );
    } finally {
      setSaving(false);
      mutationInFlight.current = false;
    }
  };

  const supplierName = (id: number) =>
    supplierNames.get(id) ?? `供应商 #${id}（名称不可用）`;
  const warehouseName = (id: number) =>
    warehouseNames.get(id) ?? `仓库 #${id}（名称不可用）`;
  const productName = (id: number) =>
    productNames.get(id) ?? `商品 #${id}（名称不可用）`;

  return (
    <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">采购 / 发票应付</p>
          <h1>采购发票</h1>
          <p>按已过账采购入库单登记发票；过账后生成应付与会计凭证。</p>
        </div>
        <button
          type="button"
          className="memory-button memory-button-primary"
          disabled={saving}
          onClick={startNew}
        >
          新建采购发票
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
          allowClear
          disabled={loading || saving}
          onChange={setSupplierFilterDraft}
          onOptions={rememberSuppliers}
        />
        <label>
          采购入库单号
          <input
            aria-label="采购入库单号"
            placeholder="精确筛选 PR 单号"
            disabled={loading || saving}
            value={receiptFilterDraft}
            onChange={(event) => setReceiptFilterDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                applySearch();
              }
            }}
          />
        </label>
        <label>
          发票状态
          <select
            aria-label="发票状态"
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
            <p className="memory-empty">正在加载采购发票…</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">暂无采购发票，请调整条件或创建草稿。</p>
          ) : (
            <div className="memory-table-wrap">
              <table className="memory-table">
                <thead>
                  <tr>
                    <th>发票号</th>
                    <th>供应商</th>
                    <th>采购入库单</th>
                    <th>发票日期</th>
                    <th>发票总额</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((invoice) => (
                    <tr
                      key={invoice.docNo}
                      tabIndex={0}
                      aria-selected={selected?.docNo === invoice.docNo}
                      className={
                        selected?.docNo === invoice.docNo
                          ? 'memory-row-selected'
                          : ''
                      }
                      onClick={() => choose(invoice)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          choose(invoice);
                        }
                      }}
                    >
                      <td>{invoice.docNo}</td>
                      <td>{supplierName(invoice.supplierId)}</td>
                      <td>{invoice.purchaseReceiptNo}</td>
                      <td>{invoice.invoiceDate}</td>
                      <td>{invoice.totalAmount}</td>
                      <td>
                        <StatusBadge status={invoice.status} />
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
            <PurchaseInvoiceForm
              form={form}
              setForm={setForm}
              saving={saving}
              warehouseName={warehouseName}
              productNames={productNames}
              onProducts={rememberProducts}
              onReceipts={ensureWarehouseNames}
              onSave={save}
              onCancel={() => setEditing(false)}
            />
          ) : selected ? (
            <PurchaseInvoiceDetails
              invoice={selected}
              supplierName={supplierName}
              productName={productName}
              saving={saving}
              onApprove={() => void transition('approve')}
              onPost={() => void transition('post')}
            />
          ) : (
            <p className="memory-empty">选择左侧采购发票查看详情。</p>
          )}
        </aside>
      </div>
    </section>
  );
}

function StatusBadge({
  status,
}: {
  status: invoiceApi.PurchaseInvoiceStatus;
}) {
  return (
    <span
      className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}
    >
      {statusLabels[status]}
    </span>
  );
}

interface SupplierPickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Supplier[]) => void;
}

function SupplierPicker({
  label,
  value,
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
  const supplierVersion = useRef(0);

  useEffect(() => {
    const version = ++supplierVersion.current;
    setLoading(true);
    setError('');
    void masterDataApi
      .searchSuppliers(keyword, '', page)
      .then((result) => {
        if (version !== supplierVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        onOptions(result.items);
      })
      .catch((cause: unknown) => {
        if (version === supplierVersion.current) {
          setError(errorMessage(cause, '供应商加载失败'));
        }
      })
      .finally(() => {
        if (version === supplierVersion.current) setLoading(false);
      });
  }, [keyword, page, refreshKey]);

  const search = () => {
    setKeyword(draft);
    setPage(1);
    setRefreshKey((current) => current + 1);
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

interface ReceiptPickerProps {
  value: string;
  disabled: boolean;
  warehouseName: (id: number) => string;
  onReceipts: (items: invoiceApi.PurchaseInvoiceReceiptOption[]) => void;
  onSelect: (receipt: invoiceApi.PurchaseInvoiceReceiptOption) => void;
}

function ReceiptPicker({
  value,
  disabled,
  warehouseName,
  onReceipts,
  onSelect,
}: ReceiptPickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<
    invoiceApi.PurchaseInvoiceReceiptOption[]
  >([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const receiptVersion = useRef(0);

  useEffect(() => {
    const version = ++receiptVersion.current;
    setLoading(true);
    setError('');
    void invoiceApi
      .searchPurchaseInvoiceReceiptOptions(page, PAGE_SIZE)
      .then((result) => {
        if (version !== receiptVersion.current) return;
        setItems(result.items);
        setTotal(result.total);
        onReceipts(result.items);
      })
      .catch((cause: unknown) => {
        if (version === receiptVersion.current) {
          setError(errorMessage(cause, '可开票采购入库单加载失败'));
        }
      })
      .finally(() => {
        if (version === receiptVersion.current) setLoading(false);
      });
  }, [page, refreshKey]);

  const choose = (receipt: invoiceApi.PurchaseInvoiceReceiptOption) => {
    const version = ++receiptVersion.current;
    setLoading(true);
    setError('');
    void invoiceApi
      .getPurchaseInvoiceReceiptOption(receipt.docNo)
      .then((detail) => {
        if (version !== receiptVersion.current) return;
        if (detail.status !== 'COMPLETED' || detail.lines.length === 0) {
          setError('该采购入库单已不再处于可开票状态，请重新选择');
          return;
        }
        onReceipts([detail]);
        onSelect(detail);
      })
      .catch((cause: unknown) => {
        if (version === receiptVersion.current) {
          setError(errorMessage(cause, '采购入库单详情加载失败'));
        }
      })
      .finally(() => {
        if (version === receiptVersion.current) setLoading(false);
      });
  };

  return (
    <div className="reference-picker">
      <strong>已过账可开票入库单</strong>
      <div className="reference-picker-search">
        <button
          type="button"
          className="memory-button"
          disabled={disabled || loading}
          onClick={() => setRefreshKey((current) => current + 1)}
        >
          刷新入库单
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
        aria-label="已过账可开票入库单"
      >
        {loading ? (
          <span>正在加载可开票采购入库单…</span>
        ) : items.length === 0 ? (
          <span>没有仍可开票的已过账采购入库单</span>
        ) : (
          items.map((receipt) => (
            <button
              type="button"
              role="option"
              aria-selected={value === receipt.docNo}
              className={value === receipt.docNo ? 'reference-picker-selected' : ''}
              disabled={disabled}
              key={receipt.docNo}
              onClick={() => choose(receipt)}
            >
              {receipt.docNo}
              <span>
                {receipt.receiptDate} · {warehouseName(receipt.warehouseId)}
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

interface PurchaseInvoiceFormProps {
  form: invoiceApi.PurchaseInvoiceForm;
  setForm: (form: invoiceApi.PurchaseInvoiceForm) => void;
  saving: boolean;
  warehouseName: (id: number) => string;
  productNames: Map<number, string>;
  onProducts: (items: catalogApi.Product[]) => void;
  onReceipts: (items: invoiceApi.PurchaseInvoiceReceiptOption[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function PurchaseInvoiceForm({
  form,
  setForm,
  saving,
  warehouseName,
  productNames,
  onProducts,
  onReceipts,
  onSave,
  onCancel,
}: PurchaseInvoiceFormProps) {
  const [receipt, setReceipt] =
    useState<invoiceApi.PurchaseInvoiceReceiptOption | null>(null);

  const selectReceipt = (
    nextReceipt: invoiceApi.PurchaseInvoiceReceiptOption,
  ) => {
    setReceipt(nextReceipt);
    setForm({
      ...form,
      purchaseReceiptNo: nextReceipt.docNo,
      lines: [],
    });
    for (const line of nextReceipt.lines) {
      void catalogApi
        .getProduct(line.productId)
        .then((product) => onProducts([product]))
        .catch(() => undefined);
    }
  };

  const addLine = (line: invoiceApi.PurchaseInvoiceReceiptLineOption) => {
    if (
      form.lines.some(
        (current) => current.receiptLineNo === line.receiptLineNo,
      )
    ) {
      return;
    }
    setForm({
      ...form,
      lines: [
        ...form.lines,
        {
          receiptLineNo: line.receiptLineNo,
          productId: line.productId,
          quantity: '',
          amount: '',
          outstandingInvoiceableQty: line.outstandingInvoiceableQty,
        },
      ],
    });
  };

  const setLine = (
    index: number,
    patch: Partial<invoiceApi.PurchaseInvoiceLineForm>,
  ) => {
    const lines = [...form.lines];
    const current = lines[index];
    if (!current) return;
    lines[index] = { ...current, ...patch };
    setForm({ ...form, lines });
  };

  return (
    <form className="customer-form purchase-order-form" onSubmit={onSave}>
      <h2>新建采购发票</h2>
      <ReceiptPicker
        value={form.purchaseReceiptNo}
        disabled={saving}
        warehouseName={warehouseName}
        onReceipts={onReceipts}
        onSelect={selectReceipt}
      />
      <label>
        发票日期
        <input
          type="date"
          required
          disabled={saving}
          value={form.invoiceDate}
          onChange={(event) =>
            setForm({ ...form, invoiceDate: event.target.value })
          }
        />
      </label>
      <label>
        供应商发票号
        <input
          maxLength={64}
          disabled={saving}
          value={form.supplierInvoiceNo}
          onChange={(event) =>
            setForm({ ...form, supplierInvoiceNo: event.target.value })
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

      {receipt && (
        <div className="reference-picker">
          <strong>选择未开完入库行</strong>
          <div
            className="reference-picker-results"
            role="listbox"
            aria-label="未开完采购入库行"
          >
            {receipt.lines.map((line) => {
              const lineSelected = form.lines.some(
                (current) => current.receiptLineNo === line.receiptLineNo,
              );
              return (
                <button
                  type="button"
                  role="option"
                  aria-selected={lineSelected}
                  className={lineSelected ? 'reference-picker-selected' : ''}
                  disabled={saving || lineSelected}
                  key={line.receiptLineNo}
                  onClick={() => addLine(line)}
                >
                  第 {line.receiptLineNo} 行 ·{' '}
                  {productNames.get(line.productId) ??
                    `商品 #${line.productId}（名称不可用）`}
                  <span>
                    收货 {line.quantity} · 已开 {line.invoicedQty} · 剩余可开{' '}
                    {line.outstandingInvoiceableQty}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      )}

      <fieldset disabled={saving}>
        <legend>本次开票明细（至少一行）</legend>
        {form.lines.length === 0 ? (
          <p className="memory-empty">
            请先选择采购入库单，再加入仍有可开票量的入库行。
          </p>
        ) : (
          form.lines.map((line, index) => (
            <div className="purchase-order-line" key={line.receiptLineNo}>
              <strong>
                入库第 {line.receiptLineNo} 行 ·{' '}
                {productNames.get(line.productId) ??
                  `商品 #${line.productId}（名称不可用）`}
                <span>（剩余可开 {line.outstandingInvoiceableQty}）</span>
              </strong>
              <label>
                本次开票数量
                <input
                  required
                  inputMode="decimal"
                  pattern={invoiceApi.positiveQuantityPattern.source}
                  value={line.quantity}
                  onChange={(event) =>
                    setLine(index, { quantity: event.target.value })
                  }
                />
              </label>
              <label>
                本次开票金额
                <input
                  required
                  inputMode="decimal"
                  pattern={invoiceApi.nonNegativeAmountPattern.source}
                  value={line.amount}
                  onChange={(event) =>
                    setLine(index, { amount: event.target.value })
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

interface PurchaseInvoiceDetailsProps {
  invoice: invoiceApi.PurchaseInvoice;
  supplierName: (id: number) => string;
  productName: (id: number) => string;
  saving: boolean;
  onApprove: () => void;
  onPost: () => void;
}

function PurchaseInvoiceDetails({
  invoice,
  supplierName,
  productName,
  saving,
  onApprove,
  onPost,
}: PurchaseInvoiceDetailsProps) {
  return (
    <>
      <StatusBadge status={invoice.status} />
      <h2>{invoice.docNo}</h2>
      <dl>
        <dt>采购入库单</dt>
        <dd>{invoice.purchaseReceiptNo}</dd>
        <dt>供应商</dt>
        <dd>{supplierName(invoice.supplierId)}</dd>
        <dt>发票日期</dt>
        <dd>{invoice.invoiceDate}</dd>
        <dt>供应商发票号</dt>
        <dd>{invoice.supplierInvoiceNo || '—'}</dd>
        <dt>发票总额</dt>
        <dd>{invoice.totalAmount}</dd>
        <dt>备注</dt>
        <dd>{invoice.remark || '—'}</dd>
      </dl>
      <div className="memory-table-wrap purchase-order-lines">
        <table className="memory-table">
          <thead>
            <tr>
              <th>入库行</th>
              <th>商品</th>
              <th>开票数量</th>
              <th>开票金额</th>
            </tr>
          </thead>
          <tbody>
            {invoice.lines.map((line) => (
              <tr key={line.lineNo}>
                <td>{line.receiptLineNo}</td>
                <td>{productName(line.productId)}</td>
                <td>{line.quantity}</td>
                <td>{line.amount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {invoice.status === 'DRAFT' && (
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
      {invoice.status === 'APPROVED' && (
        <>
          <p className="reference-picker-error">
            过账将回写入库行已开票量、生成应付账款与会计凭证，完成后不能直接修改。
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
