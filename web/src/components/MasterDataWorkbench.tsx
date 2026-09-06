import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import * as api from "../api/masterDataApi";

type BaseItem = {
  id: number;
  code: string;
  name: string;
  status: api.ArchiveStatus;
};
type Config<I extends BaseItem, F> = {
  title: string;
  kicker: string;
  createPermission: string;
  writePermission: string;
  empty: F;
  placeholder: string;
  search: (
    k: string,
    s: string,
    p: number,
  ) => Promise<{ items: I[]; total: number }>;
  get: (id: number) => Promise<I>;
  create: (f: F) => Promise<I>;
  update: (id: number, f: F) => Promise<I>;
  status: (id: number, s: api.ArchiveStatus) => Promise<I>;
  toForm: (i: I) => F;
  thirdLabel: string;
  third: (i: I) => string;
  fields: (f: F, set: (f: F) => void, editing: boolean) => React.ReactNode;
  details: (i: I) => React.ReactNode;
};

function MasterDataWorkbench<I extends BaseItem, F>({
  config,
  permissions,
}: {
  config: Config<I, F>;
  permissions: string[];
}) {
  const canCreate = permissions.includes(config.createPermission);
  const canWrite = permissions.includes(config.writePermission);
  const [items, setItems] = useState<I[]>([]);
  const [selected, setSelected] = useState<I | null>(null);
  const [form, setForm] = useState<F>(config.empty);
  const [draftKeyword, setDraftKeyword] = useState("");
  const [draftStatus, setDraftStatus] = useState("");
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reload, setReload] = useState(0);
  const detailRequest = useRef(0);
  const cancelDetailRequest = () => { detailRequest.current += 1; };

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError("");
    void config
      .search(keyword, status, page)
      .then((result) => {
        if (!live) return;
        setItems(result.items);
        setTotal(result.total);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!live) return;
        setError(
          cause instanceof ApiError
            ? cause.message
            : "列表加载失败，请重试",
        );
        setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [config, keyword, status, page, reload]);

  const choose = (item: I) => {
    const requestId = ++detailRequest.current;
    setSelected(item);
    setEditing(false);
    setError("");
    setNotice("");
    void config
      .get(item.id)
      .then((result) => {
        if (requestId === detailRequest.current) setSelected(result);
      })
      .catch(() => {
        if (requestId === detailRequest.current)
          setError(
            "详情加载失败，请重试",
          );
      });
  };
  const apply = () => {
    setKeyword(draftKeyword);
    setStatus(draftStatus);
    setPage(1);
    setReload((n) => n + 1);
  };
  const save = async (event: React.FormEvent) => {
    event.preventDefault(); cancelDetailRequest();
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const result = selected
        ? await config.update(selected.id, form)
        : await config.create(form);
      setSelected(result);
      setEditing(false);
      setNotice("档案已保存");
      setReload((n) => n + 1);
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : "保存失败，请检查输入",
      );
    } finally {
      setSaving(false);
    }
  };
  const toggle = async () => {
    if (!selected) return; cancelDetailRequest();
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const result = await config.status(
        selected.id,
        selected.status === "ENABLED" ? "DISABLED" : "ENABLED",
      );
      setSelected(result);
      setNotice(
        result.status === "ENABLED"
          ? "已启用"
          : "已停用",
      );
      setReload((n) => n + 1);
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : "状态更新失败",
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">
            {config.kicker}
          </p>
          <h1>{config.title}</h1>
          <p>
            查找、维护日常业务使用的基础资料。
          </p>
        </div>
        {canCreate && (
          <button
            type="button"
            className="memory-button memory-button-primary"
            onClick={() => {
              cancelDetailRequest(); setSelected(null);
              setForm(config.empty);
              setEditing(true);
              setError("");
              setNotice("");
            }}
          >
            新建{config.title.replace("档案", "")}
          </button>
        )}
      </header>
      {error && (
        <div className="memory-error" role="alert">
          {error}
          <button type="button" onClick={() => setReload((n) => n + 1)}>
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
          aria-label={`搜索${config.title}`}
          placeholder={config.placeholder}
          value={draftKeyword}
          onChange={(e) => setDraftKeyword(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") apply();
          }}
        />
        <select
          aria-label="状态"
          value={draftStatus}
          onChange={(e) => setDraftStatus(e.target.value)}
        >
          <option value="">全部状态</option>
          <option value="ENABLED">启用</option>
          <option value="DISABLED">停用</option>
        </select>
        <button type="button" className="memory-button" onClick={apply}>
          查询
        </button>
      </div>
      <div className="customer-layout">
        <div className="customer-list-panel">
          {loading ? (
            <p className="memory-empty">正在加载…</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">
              暂无{config.title}，
              {canCreate
                ? "可以新建一条。"
                : "请调整筛选条件。"}
            </p>
          ) : (
            <table className="memory-table">
              <thead>
                <tr>
                  <th>编码</th>
                  <th>名称</th>
                  <th>{config.thirdLabel}</th>
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
                      selected?.id === item.id ? "memory-row-selected" : ""
                    }
                    onClick={() => choose(item)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        choose(item);
                      }
                    }}
                  >
                    <td>{item.code}</td>
                    <td>{item.name}</td>
                    <td>{config.third(item)}</td>
                    <td>
                      <span
                        className={`customer-status customer-status-${item.status.toLowerCase()}`}
                      >
                        {item.status === "ENABLED"
                          ? "启用"
                          : "停用"}
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
                共 {total} 条 · 第 {page} 页
              </span>
              <span>
                <button
                  type="button"
                  disabled={page <= 1 || loading}
                  onClick={() => setPage((n) => n - 1)}
                >
                  上一页
                </button>
                <button
                  type="button"
                  disabled={page * 20 >= total || loading}
                  onClick={() => setPage((n) => n + 1)}
                >
                  下一页
                </button>
              </span>
            </div>
          )}
        </div>
        <aside className="customer-detail">
          {editing ? (
            <form className="customer-form" onSubmit={save}>
              <h2>
                {selected ? "编辑" : "新建"}
                {config.title.replace("档案", "")}
              </h2>
              {config.fields(form, setForm, Boolean(selected))}
              <div className="customer-actions">
                <button
                  type="submit"
                  className="memory-button memory-button-primary"
                  disabled={saving}
                >
                  {saving ? "保存中…" : "保存"}
                </button>
                <button
                  type="button"
                  className="memory-button"
                  disabled={saving}
                  onClick={() => setEditing(false)}
                >
                  取消
                </button>
              </div>
            </form>
          ) : selected ? (
            <>
              <h2>{selected.name}</h2>
              <p>{selected.code}</p>
              {config.details(selected)}
              {canWrite && (
                <div className="customer-actions">
                  <button
                    type="button"
                    className="memory-button"
                    disabled={saving}
                    onClick={() => {
                    cancelDetailRequest(); setForm(config.toForm(selected));
                      setEditing(true);
                      setError("");
                      setNotice("");
                    }}
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    className="memory-button"
                    disabled={saving}
                    onClick={() => void toggle()}
                  >
                    {selected.status === "ENABLED"
                      ? "停用"
                      : "启用"}
                  </button>
                </div>
              )}
            </>
          ) : (
            <p className="memory-empty">
              选择左侧记录查看详情。
            </p>
          )}
        </aside>
      </div>
    </section>
  );
}

type SupplierForm = api.SupplierForm;
type WarehouseForm = api.WarehouseForm;
const supplierEmpty: SupplierForm = {
  code: "",
  name: "",
  contactPerson: "",
  contactPhone: "",
  address: "",
  taxNo: "",
  settlementMethod: "MONTHLY",
};
const warehouseEmpty: WarehouseForm = {
  code: "",
  name: "",
  address: "",
  manager: "",
  locationEnabled: false,
};
const supplierConfig: Config<api.Supplier, SupplierForm> = {
  title: "供应商档案",
  kicker: "采购 / 基础档案",
  createPermission: "partner:create_supplier",
  writePermission: "partner:write",
  empty: supplierEmpty,
  placeholder:
    "编码、名称、联系人或电话",
  search: api.searchSuppliers,
  get: api.getSupplier,
  create: api.createSupplier,
  update: api.updateSupplier,
  status: api.setSupplierStatus,
  toForm: (i) => ({
    code: i.code,
    name: i.name,
    contactPerson: i.contactPerson ?? "",
    contactPhone: i.contactPhone ?? "",
    address: i.address ?? "",
    taxNo: i.taxNo ?? "",
    settlementMethod: i.settlementMethod,
  }),
  thirdLabel: "联系人",
  third: (i) => i.contactPerson ?? "—",
  fields: supplierFields,
  details: (i) => (
    <dl>
      <dt>联系人</dt>
      <dd>{i.contactPerson ?? "—"}</dd>
      <dt>电话</dt>
      <dd>{i.contactPhone ?? "—"}</dd>
      <dt>地址</dt>
      <dd>{i.address ?? "—"}</dd>
      <dt>税号</dt>
      <dd>{i.taxNo ?? "—"}</dd>
      <dt>结算方式</dt>
      <dd>
        {i.settlementMethod === "MONTHLY"
          ? "月结"
          : i.settlementMethod === "CASH"
            ? "现结"
            : "预付"}
      </dd>
    </dl>
  ),
};
const warehouseConfig: Config<api.Warehouse, WarehouseForm> = {
  title: "仓库档案",
  kicker: "库存 / 基础档案",
  createPermission: "warehouse:create_warehouse",
  writePermission: "warehouse:write",
  empty: warehouseEmpty,
  placeholder: "编码、名称、负责人",
  search: api.searchWarehouses,
  get: api.getWarehouse,
  create: api.createWarehouse,
  update: api.updateWarehouse,
  status: api.setWarehouseStatus,
  toForm: (i) => ({
    code: i.code,
    name: i.name,
    address: i.address ?? "",
    manager: i.manager ?? "",
    locationEnabled: i.locationEnabled,
  }),
  thirdLabel: "负责人",
  third: (i) => i.manager ?? "—",
  fields: warehouseFields,
  details: (i) => (
    <dl>
      <dt>负责人</dt>
      <dd>{i.manager ?? "—"}</dd>
      <dt>地址</dt>
      <dd>{i.address ?? "—"}</dd>
      <dt>库位管理</dt>
      <dd>{i.locationEnabled ? "启用" : "未启用"}</dd>
    </dl>
  ),
};

function supplierFields(
  f: SupplierForm,
  set: (f: SupplierForm) => void,
  editing: boolean,
) {
  return (
    <>
      <label>
        名称
        <input
          value={f.name}
          maxLength={200}
          required
          onChange={(e) => set({ ...f, name: e.target.value })}
        />
      </label>
      <label>
        编码
        <input
          value={f.code}
          maxLength={50}
          required={editing}
          onChange={(e) => set({ ...f, code: e.target.value })}
        />
      </label>
      <label>
        联系人
        <input
          value={f.contactPerson}
          maxLength={64}
          onChange={(e) => set({ ...f, contactPerson: e.target.value })}
        />
      </label>
      <label>
        联系电话
        <input
          value={f.contactPhone}
          maxLength={32}
          onChange={(e) => set({ ...f, contactPhone: e.target.value })}
        />
      </label>
      <label>
        地址
        <input
          value={f.address}
          maxLength={255}
          onChange={(e) => set({ ...f, address: e.target.value })}
        />
      </label>
      <label>
        税号
        <input
          value={f.taxNo}
          maxLength={64}
          onChange={(e) => set({ ...f, taxNo: e.target.value })}
        />
      </label>
      <label>
        结算方式
        <select
          value={f.settlementMethod}
          onChange={(e) =>
            set({
              ...f,
              settlementMethod: e.target
                .value as SupplierForm["settlementMethod"],
            })
          }
        >
          <option value="MONTHLY">月结</option>
          <option value="CASH">现结</option>
          <option value="PREPAID">预付</option>
        </select>
      </label>
    </>
  );
}
function warehouseFields(
  f: WarehouseForm,
  set: (f: WarehouseForm) => void,
  editing: boolean,
) {
  return (
    <>
      <label>
        名称
        <input
          value={f.name}
          maxLength={200}
          required
          onChange={(e) => set({ ...f, name: e.target.value })}
        />
      </label>
      <label>
        编码
        <input
          value={f.code}
          maxLength={50}
          required={editing}
          onChange={(e) => set({ ...f, code: e.target.value })}
        />
      </label>
      <label>
        地址
        <input
          value={f.address}
          maxLength={255}
          onChange={(e) => set({ ...f, address: e.target.value })}
        />
      </label>
      <label>
        负责人
        <input
          value={f.manager}
          maxLength={64}
          onChange={(e) => set({ ...f, manager: e.target.value })}
        />
      </label>
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={f.locationEnabled}
          onChange={(e) => set({ ...f, locationEnabled: e.target.checked })}
        />
        启用库位管理
      </label>
    </>
  );
}
export function SupplierWorkbench({ permissions }: { permissions: string[] }) {
  return (
    <MasterDataWorkbench config={supplierConfig} permissions={permissions} />
  );
}
export function WarehouseWorkbench({ permissions }: { permissions: string[] }) {
  return (
    <MasterDataWorkbench config={warehouseConfig} permissions={permissions} />
  );
}
