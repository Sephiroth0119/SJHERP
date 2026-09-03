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
            : "\u5217\u8868\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5",
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
            "\u8be6\u60c5\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5",
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
      setNotice("\u6863\u6848\u5df2\u4fdd\u5b58");
      setReload((n) => n + 1);
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : "\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u8f93\u5165",
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
          ? "\u5df2\u542f\u7528"
          : "\u5df2\u505c\u7528",
      );
      setReload((n) => n + 1);
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : "\u72b6\u6001\u66f4\u65b0\u5931\u8d25",
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
            \u67e5\u627e\u3001\u7ef4\u62a4\u65e5\u5e38\u4e1a\u52a1\u4f7f\u7528\u7684\u57fa\u7840\u8d44\u6599\u3002
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
            \u65b0\u5efa{config.title.replace("\u6863\u6848", "")}
          </button>
        )}
      </header>
      {error && (
        <div className="memory-error" role="alert">
          {error}
          <button type="button" onClick={() => setReload((n) => n + 1)}>
            \u91cd\u8bd5
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
          aria-label={`\u641c\u7d22${config.title}`}
          placeholder={config.placeholder}
          value={draftKeyword}
          onChange={(e) => setDraftKeyword(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") apply();
          }}
        />
        <select
          aria-label="\u72b6\u6001"
          value={draftStatus}
          onChange={(e) => setDraftStatus(e.target.value)}
        >
          <option value="">\u5168\u90e8\u72b6\u6001</option>
          <option value="ENABLED">\u542f\u7528</option>
          <option value="DISABLED">\u505c\u7528</option>
        </select>
        <button type="button" className="memory-button" onClick={apply}>
          \u67e5\u8be2
        </button>
      </div>
      <div className="customer-layout">
        <div className="customer-list-panel">
          {loading ? (
            <p className="memory-empty">\u6b63\u5728\u52a0\u8f7d\u2026</p>
          ) : items.length === 0 ? (
            <p className="memory-empty">
              \u6682\u65e0{config.title}锛?              {canCreate
                ? "\u53ef\u4ee5\u65b0\u5efa\u4e00\u6761\u3002"
                : "\u8bf7\u8c03\u6574\u7b5b\u9009\u6761\u4ef6\u3002"}
            </p>
          ) : (
            <table className="memory-table">
              <thead>
                <tr>
                  <th>\u7f16\u7801</th>
                  <th>\u540d\u79f0</th>
                  <th>{config.thirdLabel}</th>
                  <th>\u72b6\u6001</th>
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
                          ? "\u542f\u7528"
                          : "\u505c\u7528"}
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
                \u5171 {total} 鏉?路 \u7b2c {page} \u9875
              </span>
              <span>
                <button
                  type="button"
                  disabled={page <= 1 || loading}
                  onClick={() => setPage((n) => n - 1)}
                >
                  \u4e0a\u4e00\u9875
                </button>
                <button
                  type="button"
                  disabled={page * 20 >= total || loading}
                  onClick={() => setPage((n) => n + 1)}
                >
                  \u4e0b\u4e00\u9875
                </button>
              </span>
            </div>
          )}
        </div>
        <aside className="customer-detail">
          {editing ? (
            <form className="customer-form" onSubmit={save}>
              <h2>
                {selected ? "\u7f16\u8f91" : "\u65b0\u5efa"}
                {config.title.replace("\u6863\u6848", "")}
              </h2>
              {config.fields(form, setForm, Boolean(selected))}
              <div className="customer-actions">
                <button
                  type="submit"
                  className="memory-button memory-button-primary"
                  disabled={saving}
                >
                  {saving ? "\u4fdd\u5b58\u4e2d\u2026" : "\u4fdd\u5b58"}
                </button>
                <button
                  type="button"
                  className="memory-button"
                  disabled={saving}
                  onClick={() => setEditing(false)}
                >
                  \u53d6\u6d88
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
                    \u7f16\u8f91
                  </button>
                  <button
                    type="button"
                    className="memory-button"
                    disabled={saving}
                    onClick={() => void toggle()}
                  >
                    {selected.status === "ENABLED"
                      ? "\u505c\u7528"
                      : "\u542f\u7528"}
                  </button>
                </div>
              )}
            </>
          ) : (
            <p className="memory-empty">
              \u9009\u62e9\u5de6\u4fa7\u8bb0\u5f55\u67e5\u770b\u8be6\u60c5\u3002
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
  title: "\u4f9b\u5e94\u5546\u6863\u6848",
  kicker: "\u91c7\u8d2d / \u57fa\u7840\u6863\u6848",
  createPermission: "partner:create_supplier",
  writePermission: "partner:write",
  empty: supplierEmpty,
  placeholder:
    "\u7f16\u7801\u3001\u540d\u79f0\u3001\u8054\u7cfb\u4eba\u6216\u7535\u8bdd",
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
  thirdLabel: "\u8054\u7cfb\u4eba",
  third: (i) => i.contactPerson ?? "—",
  fields: supplierFields,
  details: (i) => (
    <dl>
      <dt>\u8054\u7cfb\u4eba</dt>
      <dd>{i.contactPerson ?? "—"}</dd>
      <dt>\u7535\u8bdd</dt>
      <dd>{i.contactPhone ?? "—"}</dd>
      <dt>\u5730\u5740</dt>
      <dd>{i.address ?? "—"}</dd>
      <dt>\u7a0e\u53f7</dt>
      <dd>{i.taxNo ?? "—"}</dd>
      <dt>\u7ed3\u7b97\u65b9\u5f0f</dt>
      <dd>
        {i.settlementMethod === "MONTHLY"
          ? "\u6708\u7ed3"
          : i.settlementMethod === "CASH"
            ? "\u73b0\u7ed3"
            : "\u9884\u4ed8"}
      </dd>
    </dl>
  ),
};
const warehouseConfig: Config<api.Warehouse, WarehouseForm> = {
  title: "\u4ed3\u5e93\u6863\u6848",
  kicker: "\u5e93\u5b58 / \u57fa\u7840\u6863\u6848",
  createPermission: "warehouse:create_warehouse",
  writePermission: "warehouse:write",
  empty: warehouseEmpty,
  placeholder: "\u7f16\u7801\u3001\u540d\u79f0\u3001\u8d1f\u8d23\u4eba",
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
  thirdLabel: "\u8d1f\u8d23\u4eba",
  third: (i) => i.manager ?? "—",
  fields: warehouseFields,
  details: (i) => (
    <dl>
      <dt>\u8d1f\u8d23\u4eba</dt>
      <dd>{i.manager ?? "—"}</dd>
      <dt>\u5730\u5740</dt>
      <dd>{i.address ?? "—"}</dd>
      <dt>\u5e93\u4f4d\u7ba1\u7406</dt>
      <dd>{i.locationEnabled ? "\u542f\u7528" : "\u672a\u542f\u7528"}</dd>
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
        \u540d\u79f0
        <input
          value={f.name}
          maxLength={200}
          required
          onChange={(e) => set({ ...f, name: e.target.value })}
        />
      </label>
      <label>
        \u7f16\u7801
        <input
          value={f.code}
          maxLength={50}
          required={editing}
          onChange={(e) => set({ ...f, code: e.target.value })}
        />
      </label>
      <label>
        \u8054\u7cfb\u4eba
        <input
          value={f.contactPerson}
          maxLength={64}
          onChange={(e) => set({ ...f, contactPerson: e.target.value })}
        />
      </label>
      <label>
        \u8054\u7cfb\u7535\u8bdd
        <input
          value={f.contactPhone}
          maxLength={32}
          onChange={(e) => set({ ...f, contactPhone: e.target.value })}
        />
      </label>
      <label>
        \u5730\u5740
        <input
          value={f.address}
          maxLength={255}
          onChange={(e) => set({ ...f, address: e.target.value })}
        />
      </label>
      <label>
        \u7a0e\u53f7
        <input
          value={f.taxNo}
          maxLength={64}
          onChange={(e) => set({ ...f, taxNo: e.target.value })}
        />
      </label>
      <label>
        \u7ed3\u7b97\u65b9\u5f0f
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
          <option value="MONTHLY">\u6708\u7ed3</option>
          <option value="CASH">\u73b0\u7ed3</option>
          <option value="PREPAID">\u9884\u4ed8</option>
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
        \u540d\u79f0
        <input
          value={f.name}
          maxLength={200}
          required
          onChange={(e) => set({ ...f, name: e.target.value })}
        />
      </label>
      <label>
        \u7f16\u7801
        <input
          value={f.code}
          maxLength={50}
          required={editing}
          onChange={(e) => set({ ...f, code: e.target.value })}
        />
      </label>
      <label>
        \u5730\u5740
        <input
          value={f.address}
          maxLength={255}
          onChange={(e) => set({ ...f, address: e.target.value })}
        />
      </label>
      <label>
        \u8d1f\u8d23\u4eba
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
        \u542f\u7528\u5e93\u4f4d\u7ba1\u7406
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