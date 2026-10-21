import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as materialIssueApi from '../api/materialIssueApi.ts';
import * as workOrderApi from '../api/workOrderApi.ts';
import { createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;

export const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

interface ProductPickerProps {
  disabled: boolean;
  selectedIds: Array<number | null>;
  heading: string;
  onSelect: (product: catalogApi.Product) => void;
  onOptions: (items: catalogApi.Product[]) => void;
}

export function ProductPicker({
  disabled, selectedIds, heading, onSelect, onOptions,
}: ProductPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<catalogApi.Product[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const version = useRef(createRequestGate());

  useEffect(() => {
    const current = version.current.next();
    setLoading(true);
    setError('');
    void catalogApi.searchProducts(keyword, 'ENABLED', page).then((result) => {
      if (!version.current.isCurrent(current)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (version.current.isCurrent(current)) {
        setError(errorMessage(cause, '商品加载失败'));
      }
    }).finally(() => {
      if (version.current.isCurrent(current)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>{heading}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${heading}关键词`} placeholder="输入商品编码、名称或条码"
        disabled={disabled || loading} value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            setKeyword(draft);
            setPage(1);
            setRefreshKey((value) => value + 1);
          }
        }} />
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => { setKeyword(draft); setPage(1); setRefreshKey((value) => value + 1); }}>查找</button>
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={heading}>
      {loading ? <span>正在加载商品…</span>
        : items.length === 0 ? <span>未找到启用中的商品</span>
          : items.map((product) => {
            const selected = selectedIds.includes(product.id);
            return <button type="button" role="option" aria-selected={selected}
              className={selected ? 'reference-picker-selected' : ''}
              disabled={disabled} key={product.id} onClick={() => onSelect(product)}>
              {product.name}<span>{product.code}</span>
            </button>;
          })}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={disabled || loading || page <= 1}
        onClick={() => setPage((value) => value - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((value) => value + 1)}>下一页</button>
    </div>}
  </div>;
}

interface WarehousePickerProps {
  label: string;
  value: number | null;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Warehouse[]) => void;
}

export function WarehousePicker({ label, value, disabled, onChange, onOptions }: WarehousePickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<masterDataApi.Warehouse[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const warehouseVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = warehouseVersion.current.next();
    setLoading(true);
    setError('');
    void masterDataApi.searchWarehouses(keyword, 'ENABLED', page).then((result) => {
      if (!warehouseVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (warehouseVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '仓库加载失败'));
      }
    }).finally(() => {
      if (warehouseVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>{label}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${label}关键词`} placeholder="输入仓库编码或名称"
        disabled={disabled || loading} value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            setKeyword(draft);
            setPage(1);
            setRefreshKey((current) => current + 1);
          }
        }} />
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => { setKeyword(draft); setPage(1); setRefreshKey((current) => current + 1); }}>查找</button>
      {value !== null && <button type="button" className="memory-button" disabled={disabled}
        onClick={() => onChange(null)}>清除仓库</button>}
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={label}>
      {loading ? <span>正在加载仓库…</span>
        : items.length === 0 ? <span>未找到启用中的仓库</span>
          : items.map((warehouse) => <button type="button" role="option" key={warehouse.id}
            aria-selected={value === warehouse.id}
            className={value === warehouse.id ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => onChange(warehouse.id)}>
            {warehouse.name}<span>{warehouse.code}</span>
          </button>)}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={disabled || loading || page <= 1}
        onClick={() => setPage((current) => current - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((current) => current + 1)}>下一页</button>
    </div>}
  </div>;
}

interface ExecutingWorkOrderPickerProps {
  canUseWo: boolean;
  disabled: boolean;
  value: string;
  heading: string;
  onChange: (docNo: string, order: workOrderApi.WorkOrder | null) => void;
  onProducts: (products: catalogApi.Product[]) => void;
  onWarehouses: (warehouses: masterDataApi.Warehouse[]) => void;
}

export function ExecutingWorkOrderPicker({
  canUseWo, disabled, value, heading, onChange, onProducts, onWarehouses,
}: ExecutingWorkOrderPickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<workOrderApi.WorkOrderSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(canUseWo);
  const [error, setError] = useState('');
  const listGate = useRef(createRequestGate());
  const detailGate = useRef(createRequestGate());

  useEffect(() => {
    if (!canUseWo) {
      listGate.current.next();
      setLoading(false);
      setItems([]);
      setTotal(0);
      return;
    }
    const version = listGate.current.next();
    setLoading(true);
    setError('');
    void workOrderApi.searchWorkOrders(null, 'EXECUTING', page, PAGE_SIZE).then((result) => {
      if (!listGate.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (listGate.current.isCurrent(version)) {
        setError(errorMessage(cause, '开工工单加载失败'));
      }
    }).finally(() => {
      if (listGate.current.isCurrent(version)) setLoading(false);
    });
  }, [canUseWo, page, refreshKey]);

  const choose = (order: workOrderApi.WorkOrderSummary) => {
    if (disabled) return;
    const version = detailGate.current.next();
    onChange(order.docNo, null);
    void workOrderApi.getWorkOrder(order.docNo).then((detail) => {
      if (!detailGate.current.isCurrent(version)) return;
      if (detail.status !== 'EXECUTING') {
        setError(`工单 ${detail.docNo} 当前不是开工状态，不可领料或报工`);
        onChange('', null);
        return;
      }
      onChange(detail.docNo, detail);
      void catalogApi.getProduct(detail.productId).then((product) => onProducts([product])).catch(() => undefined);
      if (detail.warehouseId) {
        void masterDataApi.getWarehouse(detail.warehouseId)
          .then((warehouse) => onWarehouses([warehouse])).catch(() => undefined);
      }
    }).catch((cause: unknown) => {
      if (detailGate.current.isCurrent(version)) {
        setError(errorMessage(cause, '工单详情加载失败'));
      }
    });
  };

  return <div className="reference-picker">
    <strong>{heading}</strong>
    <p>工单须已开工（生产中 / EXECUTING）。未开工的工单后端会拒绝，请先到工单页开工。</p>
    <label>工单号
      <input aria-label="工单号" disabled={disabled} value={value}
        placeholder="WO-…"
        onChange={(event) => onChange(event.target.value, null)} /></label>
    {canUseWo && <>
      <div className="reference-picker-search">
        <button type="button" className="memory-button" disabled={disabled || loading}
          onClick={() => { setPage(1); setRefreshKey((current) => current + 1); }}>刷新开工工单</button>
      </div>
      {error && <small className="reference-picker-error" role="alert">{error}</small>}
      <div className="reference-picker-results" role="listbox" aria-label="已开工工单">
        {loading ? <span>正在加载开工工单…</span>
          : items.length === 0 ? <span>暂无生产中的工单。请先到工单页下达并开工。</span>
            : items.map((order) => <button type="button" role="option" key={order.docNo}
              aria-selected={value === order.docNo}
              className={value === order.docNo ? 'reference-picker-selected' : ''}
              disabled={disabled} onClick={() => choose(order)}>
              {order.docNo}<span>{workOrderApi.formatQuantityDisplay(order.plannedQty)}</span>
            </button>)}
      </div>
      {total > PAGE_SIZE && <div className="reference-picker-pagination">
        <button type="button" disabled={disabled || loading || page <= 1}
          onClick={() => setPage((current) => current - 1)}>上一页</button>
        <span>第 {page} 页</span>
        <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
          onClick={() => setPage((current) => current + 1)}>下一页</button>
      </div>}
    </>}
    {!canUseWo && <p className="memory-empty">无 production:wo 时请手工填写工单号，不查询工单列表。</p>}
  </div>;
}

interface CompletedIssuePickerProps {
  disabled: boolean;
  value: string;
  onSelect: (issue: materialIssueApi.MaterialIssue) => void;
}

export function CompletedMaterialIssuePicker({ disabled, value, onSelect }: CompletedIssuePickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<materialIssueApi.MaterialIssue[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const listGate = useRef(createRequestGate());
  const detailGate = useRef(createRequestGate());

  useEffect(() => {
    const version = listGate.current.next();
    setLoading(true);
    setError('');
    void materialIssueApi.searchMaterialIssues('', 'COMPLETED', page, PAGE_SIZE).then((result) => {
      if (!listGate.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (listGate.current.isCurrent(version)) {
        setError(errorMessage(cause, '已过账领料单加载失败'));
      }
    }).finally(() => {
      if (listGate.current.isCurrent(version)) setLoading(false);
    });
  }, [page, refreshKey]);

  const choose = (issue: materialIssueApi.MaterialIssue) => {
    if (disabled) return;
    const version = detailGate.current.next();
    void materialIssueApi.getMaterialIssue(issue.docNo).then((detail) => {
      if (!detailGate.current.isCurrent(version)) return;
      if (detail.status !== 'COMPLETED') {
        setError(`领料单 ${detail.docNo} 尚未过账，不可退料`);
        return;
      }
      onSelect(detail);
    }).catch((cause: unknown) => {
      if (detailGate.current.isCurrent(version)) {
        setError(errorMessage(cause, '领料单详情加载失败'));
      }
    });
  };

  return <div className="reference-picker">
    <strong>已过账领料单（GET /api/production/material-issues?status=COMPLETED）</strong>
    <p>退料按原领料成本退回，界面不填写、不编造单价。</p>
    <div className="reference-picker-search">
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => { setPage(1); setRefreshKey((current) => current + 1); }}>刷新领料单</button>
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label="已过账领料单">
      {loading ? <span>正在加载已过账领料单…</span>
        : items.length === 0 ? <span>暂无已过账领料单。</span>
          : items.map((issue) => <button type="button" role="option" key={issue.docNo}
            aria-selected={value === issue.docNo}
            className={value === issue.docNo ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => choose(issue)}>
            {issue.docNo}<span>{issue.workOrderDocNo}</span>
          </button>)}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={disabled || loading || page <= 1}
        onClick={() => setPage((current) => current - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((current) => current + 1)}>下一页</button>
    </div>}
  </div>;
}

type CostSettlementWorkOrderStatus = 'EXECUTING' | 'COMPLETED';

interface CostSettlementWorkOrderPickerProps {
  canUseWo: boolean;
  disabled: boolean;
  selectedDocNos: string[];
  onPick: (docNo: string) => void;
}

export function CostSettlementWorkOrderPicker({
  canUseWo, disabled, selectedDocNos, onPick,
}: CostSettlementWorkOrderPickerProps) {
  const [status, setStatus] = useState<CostSettlementWorkOrderStatus>('EXECUTING');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<workOrderApi.WorkOrderSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(canUseWo);
  const [error, setError] = useState('');
  const listGate = useRef(createRequestGate());
  const detailGate = useRef(createRequestGate());

  useEffect(() => {
    if (!canUseWo) {
      listGate.current.next();
      setLoading(false);
      setItems([]);
      setTotal(0);
      return;
    }
    const version = listGate.current.next();
    setLoading(true);
    setError('');
    void workOrderApi.searchWorkOrders(null, status, page, PAGE_SIZE).then((result) => {
      if (!listGate.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (listGate.current.isCurrent(version)) {
        setError(errorMessage(cause, '工单列表加载失败'));
      }
    }).finally(() => {
      if (listGate.current.isCurrent(version)) setLoading(false);
    });
  }, [canUseWo, status, page, refreshKey]);

  const choose = (order: workOrderApi.WorkOrderSummary) => {
    if (disabled) return;
    const version = detailGate.current.next();
    void workOrderApi.getWorkOrder(order.docNo).then((detail) => {
      if (!detailGate.current.isCurrent(version)) return;
      if (detail.status !== 'EXECUTING' && detail.status !== 'COMPLETED') {
        setError(`工单 ${detail.docNo} 当前不是开工或已完工状态，不可成本结转`);
        return;
      }
      onPick(detail.docNo);
    }).catch((cause: unknown) => {
      if (detailGate.current.isCurrent(version)) {
        setError(errorMessage(cause, '工单详情加载失败'));
      }
    });
  };

  const heading = '选择开工或已完工工单（GET /api/production/work-orders?status=EXECUTING|COMPLETED）';
  return <div className="reference-picker">
    <strong>{heading}</strong>
    <p>仅开工（EXECUTING）或已完工（COMPLETED）工单可结转。全部完工行请把在产数量和完工程度填 0。无 production:wo 时只手填工单号。</p>
    {canUseWo && <>
      <label>工单状态
        <select aria-label="成本结转工单状态" disabled={disabled || loading} value={status}
          onChange={(event) => {
            const next = event.target.value === 'COMPLETED' ? 'COMPLETED' : 'EXECUTING';
            setStatus(next);
            setPage(1);
          }}>
          <option value="EXECUTING">开工中</option>
          <option value="COMPLETED">已完工</option>
        </select></label>
      <div className="reference-picker-search">
        <button type="button" className="memory-button" disabled={disabled || loading}
          onClick={() => { setPage(1); setRefreshKey((current) => current + 1); }}>刷新工单</button>
      </div>
      {error && <small className="reference-picker-error" role="alert">{error}</small>}
      <div className="reference-picker-results" role="listbox" aria-label="可结转工单">
        {loading ? <span>正在加载工单…</span>
          : items.length === 0 ? <span>暂无该状态工单。请先到工单页开工或报工完工。</span>
            : items.map((order) => {
              const selected = selectedDocNos.includes(order.docNo);
              return <button type="button" role="option" key={order.docNo}
                aria-selected={selected}
                className={selected ? 'reference-picker-selected' : ''}
                disabled={disabled} onClick={() => choose(order)}>
                {order.docNo}<span>{workOrderApi.formatQuantityDisplay(order.plannedQty)}</span>
              </button>;
            })}
      </div>
      {total > PAGE_SIZE && <div className="reference-picker-pagination">
        <button type="button" disabled={disabled || loading || page <= 1}
          onClick={() => setPage((current) => current - 1)}>上一页</button>
        <span>第 {page} 页</span>
        <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
          onClick={() => setPage((current) => current + 1)}>下一页</button>
      </div>}
    </>}
    {!canUseWo && <p className="memory-empty">无 production:wo 时请在行内手工填写工单号，不查询工单列表。</p>}
  </div>;
}
