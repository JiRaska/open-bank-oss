// SPDX-License-Identifier: Apache-2.0
import http from "k6/http";
import { SharedArray } from "k6/data";
import exec from "k6/execution";

const base = __ENV.EDGE_URL;
if (!/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:[0-9]+)?(\/[a-zA-Z0-9/_-]*)?$/.test(base || "")) {
  throw new Error("EDGE_URL must identify the isolated Customer Edge through loopback");
}
const rate = Number(__ENV.RATE_PER_ENDPOINT);
const vus = Number(__ENV.VUS_PER_ENDPOINT);
if (!Number.isSafeInteger(rate) || rate < 1 || !Number.isSafeInteger(vus) || vus < 1 || !__ENV.DURATION) {
  throw new Error("Set positive RATE_PER_ENDPOINT, VUS_PER_ENDPOINT and DURATION");
}
const fixtures = new SharedArray("synthetic customer fixtures", () => {
  const rows = JSON.parse(open(__ENV.CUSTOMER_FIXTURE));
  if (!Array.isArray(rows) || rows.length === 0) throw new Error("A synthetic fixture is required");
  return rows;
});

http.setResponseCallback(http.expectedStatuses(200));
const paths = {
  account: ["accounts", "accountId"],
  balance: ["balances", "accountId"],
  payment: ["domestic-payments", "paymentId"],
};
export const options = {
  discardResponseBodies: true,
  // Keep fixture IDs and tokens out of emitted metrics.
  systemTags: ["scenario", "status", "expected_response", "error_code"],
  scenarios: Object.fromEntries(Object.keys(paths).map((name) => [name, {
    executor: "constant-arrival-rate",
    rate,
    timeUnit: "1s",
    duration: __ENV.DURATION,
    preAllocatedVUs: vus,
    gracefulStop: "10s",
  }])),
};

export default function () {
  const scenario = exec.scenario.name;
  const [route, idField] = paths[scenario];
  const fixture = fixtures[exec.scenario.iterationInTest % fixtures.length];
  const id = fixture[idField];
  if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id || "") || !fixture.token) {
    exec.test.abort("A synthetic fixture lacks a valid resource identifier or customer token");
  }
  http.get(`${base.replace(/\/$/, "")}/${route}/${id}`, {
    headers: { Authorization: `Bearer ${fixture.token}` },
    redirects: 0,
    timeout: "5s",
  });
}
