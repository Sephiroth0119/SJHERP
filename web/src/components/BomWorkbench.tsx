import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as bomApi from '../api/productionBomApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<bomApi.ArchiveStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function BomWorkbench({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<bomApi.Bom[]>([]);
  const [selected, setSelected] = useState<bomApi.Bom | null>(null);
  const [productFilterDraft, setProductFilterDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [productFilter, setProductFilter] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<bomApi.BomForm>(bomApi.emptyBomForm());
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [units, setUnits] = useState<catalogApi.Unit[]>([]);
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const [explosion, setExplosion] = useState<bomApi.BomExplosion | null>(null);
  const [explodeQty, setExplodeQty] = useState('1');
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const supportVersion = useRef(createRequestGate());
  const explodeVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

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

  const ensureProductNames = (boms: bomApi.Bom[]) => {
    const ids = new Set<number>();
    for (const bom of boms) {
      ids.add(bom.productId);
      for (const line of bom.lines) ids.add(line.childProductId);
    }
    for (const id of ids) {
      if (productNames.has(id)) continue;
      void catalogApi.getProduct(id).then((product) => rememberProducts([product])).catch(() => undefined);
    }
  };

  useEffect(() => {
    const version = supportVersion.current.next();
    void catalogApi.listUnits().then((nextUnits) => {
      if (!supportVersion.current.isCurrent(version)) return;
      setUnits(nextUnits);
    }).catch((cause: unknown) => {
      if (supportVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '计量单位加载失败'));
      }
    });
  }, []);

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void bomApi.searchBoms(productFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureProductNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, 'BOM 列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [productFilter, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    explodeVersion.current.next();
    setSelected(null);
    setEditing(false);
    setExplosion(null);
  };

  const choose = (bom: bomApi.Bom) => {
    if (saving) return;
    const version = detailVersion.current.next();
    explodeVersion.current.next();
    setSelected(bom);
    setEditing(false);
    setExplosion(null);
    setExplodeQty('1');
    setError('');
    setNotice('');
    ensureProductNames([bom]);
    void bomApi.getBom(bom.id).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureProductNames([detail]);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, 'BOM 详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setProductFilter(productFilterDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canWrite || saving) return;
    clearSelection();
    setForm(bomApi.emptyBomForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const startEdit = () => {
    if (!canWrite || !selected || saving) return;
    invalidateDetail();
    explodeVersion.current.next();
    setExplosion(null);
    setForm(bomApi.toBomForm(selected));
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canWrite || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = bomApi.validateBomForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = selected
        ? await bomApi.updateBom(selected.id, form)
        : await bomApi.createBom(form);
      setSelected(result);
      setEditing(false);
      setNotice(selected ? 'BOM 行已整体更新' : 'BOM 已创建（默认启用，同产品其他启用版本已停用）');
      ensureProductNames([result]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, 'BOM 保存失败，请检查父件、子件、用量和损耗率'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const toggle = async () => {
    if (!canWrite || !selected || mutationInFlight.current.isLocked()) return;
    const disabling = selected.status === 'ENABLED';
    const confirmText = disabling
      ? '停用后 MRP 不再使用该版本。确认停用吗？'
      : '启用此版本将停用同产品其他启用中的 BOM。确认启用吗？';
    if (!window.confirm(confirmText)) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = disabling
        ? await bomApi.disableBom(selected.id)
        : await bomApi.enableBom(selected.id);
      setSelected(result);
      setNotice(result.status === 'ENABLED' ? 'BOM 已启用' : 'BOM 已停用');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, disabling ? 'BOM 停用失败' : 'BOM 启用失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const explode = async () => {
    if (!selected) return;
    const quantityError = bomApi.validateExplodeQuantity(explodeQty);
    if (quantityError) {
      setError(quantityError);
      return;
    }
    const version = explodeVersion.current.next();
    setError('');
    void bomApi.explodeBom(selected.productId, explodeQty.trim()).then((result) => {
      if (!explodeVersion.current.isCurrent(version)) return;
      setExplosion(result);
      ensureProductNames([{
        ...selected,
        lines: result.nodes.flatMap(function collect(node: bomApi.BomExplosionNode): bomApi.BomLine[] {
          return [
            { childProductId: node.productId, quantity: node.quantity, scrapRate: '0', unitId: node.unitId },
            ...node.children.flatMap(collect),
          ];
        }),
      }]);
    }).catch((cause: unknown) => {
      if (explodeVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, 'BOM 展开失败'));
      }
    });
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 基础数据</p>
        <h1>物料清单（BOM）</h1>
        <p>按产品维护带版本的单层 BOM。查询登录即可；新建、整体改行、启停需要 production:bom。同产品至多一个启用版本。</p>
      </div>
      {canWrite && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startNew}>新建 BOM</button>}
    </header>
    <div className="purchase-order-filters">
      <label>状态<select aria-label="BOM 状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="ENABLED">启用</option>
        <option value="DISABLED">停用</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
      {productFilterDraft !== null && <button type="button" className="memory-button" disabled={saving}
        onClick={() => setProductFilterDraft(null)}>清除商品筛选</button>}
    </div>
    <ProductPicker disabled={saving} selectedIds={productFilterDraft === null ? [] : [productFilterDraft]}
      heading="按父件筛选（GET /api/catalog/products，查询时带 productId）"
      onSelect={(product) => { rememberProducts([product]); setProductFilterDraft(product.id); }}
      onOptions={rememberProducts} />
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>父件</th><th>版本</th><th>状态</th><th>子件数</th>
        </tr></thead><tbody>
          {items.map((bom) => <tr key={bom.id}
            className={selected?.id === bom.id ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(bom)}>{productName(bom.productId)}</button></td>
            <td>v{bom.version}</td>
            <td><StatusBadge status={bom.status} /></td>
            <td>{bom.lines.length}</td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载 BOM…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无 BOM。{canWrite ? '可从右上角新建。' : '请调整筛选条件。'}
        </p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <BomFormView form={form} setForm={setForm} saving={saving}
          updating={selected !== null} units={units} productName={productName}
          rememberProducts={rememberProducts} onSave={save} onCancel={clearSelection} />
          : selected ? <BomDetails bom={selected} canWrite={canWrite} saving={saving}
            productName={productName} unitName={unitName} explodeQty={explodeQty}
            explosion={explosion} onExplodeQty={setExplodeQty}
            onExplode={() => void explode()} onEdit={startEdit} onToggle={() => void toggle()} />
            : <p className="memory-empty">选择左侧 BOM 查看详情{canWrite ? '，或新建。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: bomApi.ArchiveStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface FormProps {
  form: bomApi.BomForm;
  setForm: (form: bomApi.BomForm) => void;
  saving: boolean;
  updating: boolean;
  units: catalogApi.Unit[];
  productName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function BomFormView({
  form, setForm, saving, updating, units, productName, rememberProducts, onSave, onCancel,
}: FormProps) {
  const selectedChildIds = new Set(form.lines.map((line) => line.childProductId));
  const addChild = (product: catalogApi.Product) => {
    rememberProducts([product]);
    if (product.id === form.productId) return;
    if (selectedChildIds.has(product.id)) return;
    setForm({
      ...form,
      lines: [
        ...form.lines,
        {
          childProductId: product.id,
          quantity: '1',
          scrapRate: '0',
          unitId: product.baseUnitId,
        },
      ],
    });
  };
  const setLine = (index: number, patch: Partial<bomApi.BomLineForm>) => {
    const lines = form.lines.map((line, current) => current === index ? { ...line, ...patch } : line);
    setForm({ ...form, lines });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>{updating ? '整体更新 BOM 行' : '新建 BOM'}</h2>
    <label>版本{updating ? '（创建后不可改）' : ''}
      <input required inputMode="numeric" pattern={bomApi.versionPattern.source}
        disabled={saving || updating} value={form.version} aria-label="BOM 版本"
        onChange={(event) => setForm({ ...form, version: event.target.value })} /></label>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    {updating ? <p>父件：{form.productId === null ? '—' : productName(form.productId)}（更新时不可改）</p>
      : <ProductPicker disabled={saving}
        selectedIds={form.productId === null ? [] : [form.productId]}
        heading="选择父件商品"
        onSelect={(product) => {
          rememberProducts([product]);
          setForm({
            ...form,
            productId: product.id,
            lines: form.lines.filter((line) => line.childProductId !== product.id),
          });
        }}
        onOptions={rememberProducts} />}
    <fieldset disabled={saving}>
      <legend>子件行（整体替换）</legend>
      {form.lines.length === 0 ? <p className="memory-empty">请从下方添加子件。</p>
        : form.lines.map((line, index) => <div className="customer-toolbar" key={`${line.childProductId}-${index}`}>
          <strong>{line.childProductId === null ? '未选子件' : productName(line.childProductId)}</strong>
          <label>用量<input aria-label={`用量 ${index + 1}`} required inputMode="decimal"
            pattern={bomApi.positiveDecimalPattern.source} value={line.quantity}
            onChange={(event) => setLine(index, { quantity: event.target.value })} /></label>
          <label>损耗率<input aria-label={`损耗率 ${index + 1}`} inputMode="decimal"
            placeholder="空则 0" value={line.scrapRate}
            onChange={(event) => setLine(index, { scrapRate: event.target.value })} /></label>
          <label>单位<select aria-label={`单位 ${index + 1}`} required value={line.unitId ?? ''}
            onChange={(event) => setLine(index, {
              unitId: event.target.value ? Number.parseInt(event.target.value, 10) : null,
            })}>
            <option value="">请选择</option>
            {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.name}</option>)}
          </select></label>
          <button type="button" className="memory-button" onClick={() => setForm({
            ...form,
            lines: form.lines.filter((_, current) => current !== index),
          })}>移除</button>
        </div>)}
    </fieldset>
    <ProductPicker disabled={saving} selectedIds={[form.productId, ...selectedChildIds]}
      heading="添加子件（不可与父件相同，不可重复）"
      onSelect={addChild} onOptions={rememberProducts} />
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '保存中…' : updating ? '保存行列表' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  bom: bomApi.Bom;
  canWrite: boolean;
  saving: boolean;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  explodeQty: string;
  explosion: bomApi.BomExplosion | null;
  onExplodeQty: (value: string) => void;
  onExplode: () => void;
  onEdit: () => void;
  onToggle: () => void;
}

function BomDetails({
  bom, canWrite, saving, productName, unitName, explodeQty, explosion,
  onExplodeQty, onExplode, onEdit, onToggle,
}: DetailsProps) {
  return <>
    <StatusBadge status={bom.status} />
    <h2>{productName(bom.productId)}</h2>
    <p>v{bom.version}</p>
    <dl>
      <dt>备注</dt><dd>{bom.remark || '—'}</dd>
      <dt>创建</dt><dd>{bom.createdBy || '—'} · {bom.createdAt || '—'}</dd>
      <dt>更新</dt><dd>{bom.updatedBy || '—'} · {bom.updatedAt || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>子件</th><th>用量</th><th>损耗率</th><th>单位</th>
      </tr></thead><tbody>
        {bom.lines.map((line) => <tr key={line.childProductId}>
          <td>{productName(line.childProductId)}</td>
          <td>{line.quantity}</td>
          <td>{line.scrapRate}</td>
          <td>{unitName(line.unitId)}</td>
        </tr>)}
      </tbody></table>
    </div>
    {canWrite && <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving} onClick={onEdit}>编辑行</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onToggle}>
        {saving ? '处理中…' : bom.status === 'ENABLED' ? '停用' : '启用'}
      </button>
    </div>}
    <div className="purchase-order-filters">
      <label>展开数量（按父件 productId 调 explode）
        <input aria-label="BOM 展开数量" inputMode="decimal" disabled={saving}
          value={explodeQty} onChange={(event) => onExplodeQty(event.target.value)} /></label>
      <button type="button" className="memory-button" disabled={saving} onClick={onExplode}>展开</button>
    </div>
    {explosion && <ExplosionTree explosion={explosion} productName={productName} unitName={unitName} />}
  </>;
}

function ExplosionTree({
  explosion, productName, unitName,
}: {
  explosion: bomApi.BomExplosion;
  productName: (id: number) => string;
  unitName: (id: number) => string;
}) {
  return <section className="memory-group">
    <header>
      <strong>展开结果</strong>
      <span>根件 {productName(explosion.rootProductId)} × {explosion.rootQuantity}</span>
    </header>
    {explosion.nodes.length === 0
      ? <p className="memory-empty">无启用中的 BOM 可展开（叶节点）。</p>
      : <ExplosionNodes nodes={explosion.nodes} productName={productName} unitName={unitName} />}
  </section>;
}

function ExplosionNodes({
  nodes, productName, unitName,
}: {
  nodes: bomApi.BomExplosionNode[];
  productName: (id: number) => string;
  unitName: (id: number) => string;
}) {
  return <ul>
    {nodes.map((node) => <li key={`${node.level}-${node.productId}-${node.quantity}`}>
      L{node.level} {productName(node.productId)} × {node.quantity}（{unitName(node.unitId)}）
      {node.children.length > 0 && <ExplosionNodes nodes={node.children}
        productName={productName} unitName={unitName} />}
    </li>)}
  </ul>;
}

interface ProductPickerProps {
  disabled: boolean;
  selectedIds: Array<number | null>;
  heading: string;
  onSelect: (product: catalogApi.Product) => void;
  onOptions: (items: catalogApi.Product[]) => void;
}

function ProductPicker({ disabled, selectedIds, heading, onSelect, onOptions }: ProductPickerProps) {
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
              disabled={disabled || selected} key={product.id} onClick={() => onSelect(product)}>
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
