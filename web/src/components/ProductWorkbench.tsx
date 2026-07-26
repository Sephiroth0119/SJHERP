import { useEffect, useRef, useState } from 'react';
import * as api from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';

const categoryLabels: Record<api.InventoryCategory, string> = {
  RAW_MATERIAL: '原材料',
  SEMI_FINISHED: '半成品',
  FINISHED_GOOD: '产成品',
  MERCHANDISE: '商品',
};

function emptyProductForm(): api.ProductForm {
  return {
    code: '',
    name: '',
    spec: '',
    categoryId: null,
    inventoryCategory: 'RAW_MATERIAL',
    baseUnitId: null,
    barcode: '',
    remark: '',
    unitConversions: [],
  };
}

function formFromProduct(product: api.Product): api.ProductForm {
  return {
    code: product.code,
    name: product.name,
    spec: product.spec ?? '',
    categoryId: product.categoryId,
    inventoryCategory: product.inventoryCategory,
    baseUnitId: product.baseUnitId,
    barcode: product.barcode ?? '',
    remark: product.remark ?? '',
    unitConversions: product.unitConversions.map((conversion) => ({
      unitId: conversion.unitId,
      rate: conversion.rate,
    })),
  };
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}

export function ProductWorkbench({
  permissions,
}: {
  permissions: string[];
}) {
  const canCreate = permissions.includes('catalog:create_product');
  const canWrite = permissions.includes('catalog:write');
  const [items, setItems] = useState<api.Product[]>([]);
  const [selected, setSelected] = useState<api.Product | null>(null);
  const [form, setForm] = useState<api.ProductForm>(emptyProductForm);
  const [categories, setCategories] = useState<api.Category[]>([]);
  const [units, setUnits] = useState<api.Unit[]>([]);
  const [keywordDraft, setKeywordDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [productRefreshKey, setProductRefreshKey] = useState(0);
  const [catalogRefreshKey, setCatalogRefreshKey] = useState(0);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const listVersion = useRef(0);
  const detailVersion = useRef(0);

  const invalidateDetail = () => {
    detailVersion.current += 1;
  };

  const refreshProducts = () => {
    setProductRefreshKey((value) => value + 1);
  };

  const refreshCatalog = () => {
    setCatalogRefreshKey((value) => value + 1);
  };

  useEffect(() => {
    const version = ++listVersion.current;
    setLoading(true);

    void api
      .searchProducts(keyword, status, page)
      .then((result) => {
        if (version !== listVersion.current) {
          return;
        }
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (version === listVersion.current) {
          setError(errorMessage(cause, '列表加载失败'));
        }
      })
      .finally(() => {
        if (version === listVersion.current) {
          setLoading(false);
        }
      });
  }, [keyword, status, page, productRefreshKey]);

  useEffect(() => {
    let live = true;

    void Promise.all([api.listCategories(), api.listUnits()])
      .then(([nextCategories, nextUnits]) => {
        if (!live) {
          return;
        }
        setCategories(nextCategories);
        setUnits(nextUnits);
      })
      .catch((cause: unknown) => {
        if (live) {
          setError(errorMessage(cause, '基础资料加载失败'));
        }
      });

    return () => {
      live = false;
    };
  }, [catalogRefreshKey]);

  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (item: api.Product) => {
    const version = ++detailVersion.current;
    setSelected(item);
    setEditing(false);
    setError('');
    setNotice('');

    void api
      .getProduct(item.id)
      .then((result) => {
        if (version === detailVersion.current) {
          setSelected(result);
        }
      })
      .catch((cause: unknown) => {
        if (version === detailVersion.current) {
          setError(errorMessage(cause, '详情加载失败，请重试'));
        }
      });
  };

  const applySearch = () => {
    clearSelection();
    setError('');
    setNotice('');
    setKeyword(keywordDraft);
    setStatus(statusDraft);
    setPage(1);
    refreshProducts();
  };

  const changePage = (nextPage: number) => {
    clearSelection();
    setError('');
    setNotice('');
    setPage(nextPage);
  };

  const startNew = () => {
    clearSelection();
    setForm(emptyProductForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const startEdit = () => {
    if (!selected) {
      return;
    }
    invalidateDetail();
    setForm(formFromProduct(selected));
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    invalidateDetail();
    setError('');
    setNotice('');

    const conversionError = api.validateConversions(
      form.baseUnitId,
      form.unitConversions,
    );
    if (conversionError) {
      setError(conversionError);
      return;
    }

    setSaving(true);
    try {
      const result = selected
        ? await api.updateProduct(selected.id, form)
        : await api.createProduct(form);
      setSelected(result);
      setEditing(false);
      setNotice('商品档案已保存');
      refreshProducts();
    } catch (cause: unknown) {
      setError(errorMessage(cause, '保存失败，请检查输入'));
    } finally {
      setSaving(false);
    }
  };

  const toggle = async () => {
    if (!selected) {
      return;
    }

    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const nextStatus =
        selected.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
      const result = await api.setProductStatus(selected.id, nextStatus);
      setSelected(result);
      setNotice(result.status === 'ENABLED' ? '商品已启用' : '商品已停用');
      refreshProducts();
    } catch (cause: unknown) {
      setError(errorMessage(cause, '状态更新失败'));
    } finally {
      setSaving(false);
    }
  };

  const categoryName = (id: number | null) =>
    categories.find((item) => item.id === id)?.name ?? '未分类';
  const unitName = (id: number) =>
    units.find((item) => item.id === id)?.name ?? `单位 ${id}`;

  return (
    <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">库存 / 基础档案</p>
          <h1>商品档案</h1>
          <p>维护商品、类目与计量单位引用。</p>
        </div>
        {canCreate && (
          <button
            type="button"
            className="memory-button memory-button-primary"
            onClick={startNew}
          >
            新建商品
          </button>
        )}
      </header>

      {error && (
        <div className="memory-error" role="alert">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => {
              setError('');
              refreshProducts();
              refreshCatalog();
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

      <div className="customer-toolbar">
        <input
          aria-label="搜索商品"
          placeholder="编码、名称或条码"
          value={keywordDraft}
          onChange={(event) => setKeywordDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              applySearch();
            }
          }}
        />
        <select
          aria-label="商品状态"
          value={statusDraft}
          onChange={(event) => setStatusDraft(event.target.value)}
        >
          <option value="">全部状态</option>
          <option value="ENABLED">启用</option>
          <option value="DISABLED">停用</option>
        </select>
        <button type="button" className="memory-button" onClick={applySearch}>
          查询
        </button>
      </div>

      <div className="customer-layout">
        <div className="customer-list-panel">
          {loading ? (
            <p className="memory-empty">正在加载…</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">
              暂无商品档案，请调整筛选条件或新建商品。
            </p>
          ) : (
            <table className="memory-table">
              <thead>
                <tr>
                  <th>编码</th>
                  <th>名称</th>
                  <th>类目</th>
                  <th>单位</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr
                    key={item.id}
                    tabIndex={0}
                    aria-selected={selected?.id === item.id}
                    className={
                      selected?.id === item.id ? 'memory-row-selected' : ''
                    }
                    onClick={() => choose(item)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        choose(item);
                      }
                    }}
                  >
                    <td>{item.code}</td>
                    <td>{item.name}</td>
                    <td>{categoryName(item.categoryId)}</td>
                    <td>{unitName(item.baseUnitId)}</td>
                    <td>
                      <span
                        className={`customer-status customer-status-${item.status.toLowerCase()}`}
                      >
                        {item.status === 'ENABLED' ? '启用' : '停用'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {total > 0 && (
            <div className="memory-pagination">
              <span>
                共 {total} 条，第 {page} 页
              </span>
              <span>
                <button
                  type="button"
                  disabled={page <= 1 || loading}
                  onClick={() => changePage(page - 1)}
                >
                  上一页
                </button>
                <button
                  type="button"
                  disabled={page * 20 >= total || loading}
                  onClick={() => changePage(page + 1)}
                >
                  下一页
                </button>
              </span>
            </div>
          )}
        </div>

        <aside className="customer-detail">
          {editing ? (
            <ProductForm
              form={form}
              setForm={setForm}
              categories={categories}
              units={units}
              saving={saving}
              selected={selected}
              onSave={save}
              onCancel={() => setEditing(false)}
            />
          ) : selected ? (
            <ProductDetails
              product={selected}
              categoryName={categoryName}
              unitName={unitName}
              canWrite={canWrite}
              saving={saving}
              onEdit={startEdit}
              onToggle={() => void toggle()}
            />
          ) : (
            <p className="memory-empty">选择左侧商品查看详情。</p>
          )}
        </aside>
      </div>

      <CatalogSupport
        categories={categories}
        units={units}
        permissions={permissions}
        onChanged={refreshCatalog}
      />
    </section>
  );
}

interface ProductFormProps {
  form: api.ProductForm;
  setForm: (value: api.ProductForm) => void;
  categories: api.Category[];
  units: api.Unit[];
  saving: boolean;
  selected: api.Product | null;
  onSave: (event: React.FormEvent) => void;
  onCancel: () => void;
}

function ProductForm({
  form,
  setForm,
  categories,
  units,
  saving,
  selected,
  onSave,
  onCancel,
}: ProductFormProps) {
  const set = (patch: Partial<api.ProductForm>) => {
    setForm({ ...form, ...patch });
  };
  const usedUnitIds = new Set(
    form.unitConversions.map((conversion) => conversion.unitId),
  );
  const availableUnits = units.filter(
    (unit) =>
      unit.id !== form.baseUnitId && !usedUnitIds.has(unit.id),
  );

  const addConversion = () => {
    const unit = availableUnits[0];
    if (!unit) {
      return;
    }
    set({
      unitConversions: [
        ...form.unitConversions,
        { unitId: unit.id, rate: '' },
      ],
    });
  };

  return (
    <form className="customer-form" onSubmit={onSave}>
      <h2>{selected ? '编辑商品' : '新建商品'}</h2>
      <label>
        名称
        <input
          required
          maxLength={200}
          disabled={saving}
          value={form.name}
          onChange={(event) => set({ name: event.target.value })}
        />
      </label>
      <label>
        编码
        <input
          maxLength={50}
          required={Boolean(selected)}
          disabled={saving}
          value={form.code}
          onChange={(event) => set({ code: event.target.value })}
        />
      </label>
      <label>
        规格
        <input
          maxLength={200}
          disabled={saving}
          value={form.spec}
          onChange={(event) => set({ spec: event.target.value })}
        />
      </label>
      <label>
        类目
        <select
          disabled={saving}
          value={form.categoryId ?? ''}
          onChange={(event) =>
            set({
              categoryId: event.target.value
                ? Number(event.target.value)
                : null,
            })
          }
        >
          <option value="">未分类</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        库存分类
        <select
          disabled={saving}
          value={form.inventoryCategory}
          onChange={(event) =>
            set({
              inventoryCategory: event.target
                .value as api.InventoryCategory,
            })
          }
        >
          {Object.entries(categoryLabels).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label>
        基本单位
        <select
          required
          disabled={saving}
          value={form.baseUnitId ?? ''}
          onChange={(event) =>
            set({
              baseUnitId: event.target.value
                ? Number(event.target.value)
                : null,
            })
          }
        >
          <option value="">请选择</option>
          {units.map((unit) => (
            <option key={unit.id} value={unit.id}>
              {unit.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        条码
        <input
          maxLength={64}
          disabled={saving}
          value={form.barcode}
          onChange={(event) => set({ barcode: event.target.value })}
        />
      </label>
      <label>
        备注
        <textarea
          maxLength={500}
          disabled={saving}
          value={form.remark}
          onChange={(event) => set({ remark: event.target.value })}
        />
      </label>

      <fieldset disabled={saving}>
        <legend>换算单位</legend>
        {form.unitConversions.map((conversion, index) => {
          const selectableUnits = units.filter(
            (unit) =>
              unit.id === conversion.unitId ||
              (unit.id !== form.baseUnitId && !usedUnitIds.has(unit.id)),
          );
          return (
            <div
              className="customer-toolbar"
              key={`${index}-${conversion.unitId}`}
            >
              <select
                aria-label={`换算单位 ${index + 1}`}
                value={conversion.unitId}
                onChange={(event) => {
                  const next = [...form.unitConversions];
                  next[index] = {
                    ...conversion,
                    unitId: Number(event.target.value),
                  };
                  set({ unitConversions: next });
                }}
              >
                {!units.some((unit) => unit.id === conversion.unitId) && (
                  <option value={conversion.unitId}>
                    单位 {conversion.unitId}（已不存在）
                  </option>
                )}
                {selectableUnits.map((unit) => (
                  <option key={unit.id} value={unit.id}>
                    {unit.name}
                  </option>
                ))}
              </select>
              <input
                aria-label={`换算率 ${index + 1}`}
                required
                inputMode="decimal"
                pattern={api.ratePattern.source}
                value={conversion.rate}
                onChange={(event) => {
                  const next = [...form.unitConversions];
                  next[index] = {
                    ...conversion,
                    rate: event.target.value,
                  };
                  set({ unitConversions: next });
                }}
              />
              <button
                type="button"
                className="memory-button"
                onClick={() =>
                  set({
                    unitConversions: form.unitConversions.filter(
                      (_, current) => current !== index,
                    ),
                  })
                }
              >
                移除
              </button>
              {conversion.rate !== '' &&
                !api.ratePattern.test(conversion.rate) && (
                  <small>
                    请输入大于 0 的数，整数最多 12 位、小数最多 6 位
                  </small>
                )}
            </div>
          );
        })}
        <button
          type="button"
          className="memory-button"
          disabled={availableUnits.length === 0}
          onClick={addConversion}
        >
          新增换算单位
        </button>
      </fieldset>

      <div className="customer-actions">
        <button
          type="submit"
          className="memory-button memory-button-primary"
          disabled={saving || form.baseUnitId === null}
        >
          保存
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

interface ProductDetailsProps {
  product: api.Product;
  categoryName: (id: number | null) => string;
  unitName: (id: number) => string;
  canWrite: boolean;
  saving: boolean;
  onEdit: () => void;
  onToggle: () => void;
}

function ProductDetails({
  product,
  categoryName,
  unitName,
  canWrite,
  saving,
  onEdit,
  onToggle,
}: ProductDetailsProps) {
  return (
    <>
      <h2>{product.name}</h2>
      <p>{product.code}</p>
      <dl>
        <dt>规格</dt>
        <dd>{product.spec || '—'}</dd>
        <dt>类目</dt>
        <dd>{categoryName(product.categoryId)}</dd>
        <dt>库存分类</dt>
        <dd>{categoryLabels[product.inventoryCategory]}</dd>
        <dt>基本单位</dt>
        <dd>{unitName(product.baseUnitId)}</dd>
        <dt>条码</dt>
        <dd>{product.barcode || '—'}</dd>
        <dt>备注</dt>
        <dd>{product.remark || '—'}</dd>
        <dt>换算单位</dt>
        <dd>
          {product.unitConversions.length
            ? product.unitConversions
                .map(
                  (conversion) =>
                    `${unitName(conversion.unitId)} = ${conversion.rate}`,
                )
                .join('；')
            : '—'}
        </dd>
      </dl>
      {canWrite && (
        <div className="customer-actions">
          <button
            type="button"
            className="memory-button"
            disabled={saving}
            onClick={onEdit}
          >
            编辑
          </button>
          <button
            type="button"
            className="memory-button"
            disabled={saving}
            onClick={onToggle}
          >
            {product.status === 'ENABLED' ? '停用' : '启用'}
          </button>
        </div>
      )}
    </>
  );
}

interface CatalogSupportProps {
  categories: api.Category[];
  units: api.Unit[];
  permissions: string[];
  onChanged: () => void;
}

type SavingTarget = 'category' | 'unit' | null;

function CatalogSupport({
  categories,
  units,
  permissions,
  onChanged,
}: CatalogSupportProps) {
  const canWrite = permissions.includes('catalog:write');
  const [category, setCategory] = useState({
    name: '',
    parentId: null as number | null,
  });
  const [unit, setUnit] = useState({ name: '', precision: '0' });
  const [editingCategory, setEditingCategory] = useState<number | null>(null);
  const [editingUnit, setEditingUnit] = useState<number | null>(null);
  const [saving, setSaving] = useState<SavingTarget>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const resetCategory = () => {
    setCategory({ name: '', parentId: null });
    setEditingCategory(null);
  };

  const resetUnit = () => {
    setUnit({ name: '', precision: '0' });
    setEditingUnit(null);
  };

  const saveCategory = async () => {
    const name = category.name.trim();
    if (!name) {
      setError('类目名称不能为空');
      return;
    }

    setSaving('category');
    setError('');
    setNotice('');
    try {
      if (editingCategory === null) {
        await api.createCategory({ name, parentId: category.parentId });
        setNotice('类目已新增');
      } else {
        await api.renameCategory(editingCategory, name);
        setNotice('类目名称已更新');
      }
      resetCategory();
      onChanged();
    } catch (cause: unknown) {
      setError(errorMessage(cause, '类目保存失败'));
    } finally {
      setSaving(null);
    }
  };

  const saveUnit = async () => {
    const name = unit.name.trim();
    if (!name) {
      setError('单位名称不能为空');
      return;
    }
    if (!/^[0-6]$/.test(unit.precision)) {
      setError('单位精度必须是 0 到 6 的整数');
      return;
    }

    setSaving('unit');
    setError('');
    setNotice('');
    try {
      const request: api.UnitForm = {
        name,
        precision: Number(unit.precision),
      };
      if (editingUnit === null) {
        await api.createUnit(request);
        setNotice('单位已新增');
      } else {
        await api.updateUnit(editingUnit, request);
        setNotice('单位已更新');
      }
      resetUnit();
      onChanged();
    } catch (cause: unknown) {
      setError(errorMessage(cause, '单位保存失败'));
    } finally {
      setSaving(null);
    }
  };

  const parentName = (parentId: number | null) => {
    if (parentId === null) {
      return '根类目';
    }
    return (
      categories.find((candidate) => candidate.id === parentId)?.name ??
      `父类目 ${parentId}`
    );
  };

  const fieldsDisabled = saving !== null;

  return (
    <section className="memory-group">
      <header>
        <strong>类目与单位维护</strong>
        <span>仅提供新增与 PUT 编辑，不提供删除</span>
      </header>

      {error && (
        <p className="memory-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="customer-success" role="status">
          {notice}
        </p>
      )}

      <div className="customer-toolbar">
        <strong>类目 {categories.length}</strong>
        {canWrite && (
          <>
            <input
              aria-label="类目名称"
              maxLength={100}
              disabled={fieldsDisabled}
              value={category.name}
              onChange={(event) =>
                setCategory({ ...category, name: event.target.value })
              }
              placeholder="类目名称"
            />
            <select
              aria-label="父类目"
              disabled={fieldsDisabled || editingCategory !== null}
              value={category.parentId ?? ''}
              onChange={(event) =>
                setCategory({
                  ...category,
                  parentId: event.target.value
                    ? Number(event.target.value)
                    : null,
                })
              }
            >
              <option value="">无父级</option>
              {categories.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="memory-button"
              disabled={fieldsDisabled}
              onClick={() => void saveCategory()}
            >
              {editingCategory === null ? '新增类目' : '保存类目'}
            </button>
            {editingCategory !== null && (
              <button
                type="button"
                className="memory-button"
                disabled={fieldsDisabled}
                onClick={resetCategory}
              >
                取消编辑
              </button>
            )}
          </>
        )}
      </div>
      {editingCategory !== null && (
        <small>类目父级在创建后固化，编辑时只允许改名。</small>
      )}
      {categories.map((item) => (
        <div className="customer-toolbar" key={item.id}>
          <span>{item.name}</span>
          <span>{parentName(item.parentId)}</span>
          {canWrite && (
            <button
              type="button"
              className="memory-button"
              disabled={fieldsDisabled}
              onClick={() => {
                setError('');
                setNotice('');
                setEditingCategory(item.id);
                setCategory({
                  name: item.name,
                  parentId: item.parentId,
                });
              }}
            >
              编辑
            </button>
          )}
        </div>
      ))}

      <div className="customer-toolbar">
        <strong>单位 {units.length}</strong>
        {canWrite && (
          <>
            <input
              aria-label="单位名称"
              maxLength={50}
              disabled={fieldsDisabled}
              value={unit.name}
              onChange={(event) =>
                setUnit({ ...unit, name: event.target.value })
              }
              placeholder="单位名称"
            />
            <input
              aria-label="单位精度"
              type="number"
              min={0}
              max={6}
              step={1}
              disabled={fieldsDisabled}
              value={unit.precision}
              onChange={(event) =>
                setUnit({ ...unit, precision: event.target.value })
              }
            />
            <button
              type="button"
              className="memory-button"
              disabled={fieldsDisabled}
              onClick={() => void saveUnit()}
            >
              {editingUnit === null ? '新增单位' : '保存单位'}
            </button>
            {editingUnit !== null && (
              <button
                type="button"
                className="memory-button"
                disabled={fieldsDisabled}
                onClick={resetUnit}
              >
                取消编辑
              </button>
            )}
          </>
        )}
      </div>
      {units.map((item) => (
        <div className="customer-toolbar" key={item.id}>
          <span>
            {item.name}（精度 {item.precision}）
          </span>
          {canWrite && (
            <button
              type="button"
              className="memory-button"
              disabled={fieldsDisabled}
              onClick={() => {
                setError('');
                setNotice('');
                setEditingUnit(item.id);
                setUnit({
                  name: item.name,
                  precision: String(item.precision),
                });
              }}
            >
              编辑
            </button>
          )}
        </div>
      ))}
    </section>
  );
}
