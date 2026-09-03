import test from "node:test";
import assert from "node:assert/strict";

const response = (body) => ({ ok: true, status: 200, json: async () => body });
const supplier = {
  id: 1,
  code: "SUP-1",
  name: "A",
  contactPerson: null,
  contactPhone: null,
  address: null,
  taxNo: null,
  settlementMethod: "MONTHLY",
  status: "ENABLED",
  createdAt: "",
  updatedAt: "",
};
const warehouse = {
  id: 2,
  code: "WH-1",
  name: "Main",
  address: null,
  manager: null,
  locationEnabled: false,
  status: "ENABLED",
  createdAt: "",
  updatedAt: "",
};

test("supplier and warehouse APIs use real query and action paths", async () => {
  const oldFetch = globalThis.fetch;
  const oldStorage = globalThis.localStorage;
  try {
    const calls = [];
    globalThis.localStorage = {
      getItem: () => null,
      setItem() {},
      removeItem() {},
    };
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({ items: [], total: 0, page: 1, size: 20 });
    };
    const api = await import("../src/api/masterDataApi.ts");
    await api.searchSuppliers(" acme ", "ENABLED", 2);
    await api.searchWarehouses(" main ", "DISABLED", 3);
    await api.setSupplierStatus(1, "DISABLED");
    await api.setWarehouseStatus(2, "ENABLED");
    assert.match(
      calls[0][0],
      /\/api\/partner\/suppliers\?page=2&size=20&keyword=acme&status=ENABLED/,
    );
    assert.equal(calls[0][1].method, "GET");
    assert.match(
      calls[1][0],
      /\/api\/warehouse\/warehouses\?page=3&size=20&keyword=main&status=DISABLED/,
    );
    assert.equal(calls[2][0], "/api/partner/suppliers/1/disable");
    assert.equal(calls[2][1].method, "POST");
    assert.equal(calls[3][0], "/api/warehouse/warehouses/2/enable");
    assert.equal(calls[3][1].method, "POST");
  } finally {
    globalThis.fetch = oldFetch;
    globalThis.localStorage = oldStorage;
  }
});

test("create and update send exact DTO bodies, including boolean locationEnabled", async () => {
  const oldFetch = globalThis.fetch;
  const oldStorage = globalThis.localStorage;
  try {
    const bodies = [];
    const methods = [];
    globalThis.localStorage = {
      getItem: () => null,
      setItem() {},
      removeItem() {},
    };
    globalThis.fetch = async (_input, init = {}) => {
      bodies.push(JSON.parse(init.body));
      methods.push(init.method);
      return response(supplier);
    };
    const api = await import("../src/api/masterDataApi.ts");
    await api.createSupplier({
      code: "S",
      name: "A",
      contactPerson: "P",
      contactPhone: "1",
      address: "X",
      taxNo: "T",
      settlementMethod: "CASH",
      id: 99,
      status: "DISABLED",
    });
    await api.updateSupplier(1, {
      code: "S",
      name: "A",
      contactPerson: "P",
      contactPhone: "1",
      address: "X",
      taxNo: "T",
      settlementMethod: "CASH",
      id: 99,
      status: "DISABLED",
    });
    await api.createWarehouse({
      code: "W",
      name: "Main",
      address: "X",
      manager: "M",
      locationEnabled: true,
      id: 99,
      status: "DISABLED",
    });
    await api.updateWarehouse(2, {
      code: "W",
      name: "Main",
      address: "X",
      manager: "M",
      locationEnabled: false,
      id: 99,
      status: "DISABLED",
    });
    assert.deepEqual(Object.keys(bodies[0]).sort(), [
      "address",
      "code",
      "contactPerson",
      "contactPhone",
      "name",
      "settlementMethod",
      "taxNo",
    ]);
    assert.deepEqual(Object.keys(bodies[1]).sort(), [
      "address",
      "code",
      "contactPerson",
      "contactPhone",
      "name",
      "settlementMethod",
      "taxNo",
    ]);
    assert.deepEqual(Object.keys(bodies[2]).sort(), [
      "address",
      "code",
      "locationEnabled",
      "manager",
      "name",
    ]);
    assert.equal(bodies[2].locationEnabled, true);
    assert.deepEqual(Object.keys(bodies[3]).sort(), [
      "address",
      "code",
      "locationEnabled",
      "manager",
      "name",
    ]);
    assert.equal(bodies[3].locationEnabled, false);
    assert.deepEqual(methods, ["POST", "PUT", "POST", "PUT"]);
  } finally {
    globalThis.fetch = oldFetch;
    globalThis.localStorage = oldStorage;
  }
});
