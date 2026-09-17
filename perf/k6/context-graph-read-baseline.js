// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only Context Graph baseline. Run each lens separately against an isolated, auth-enabled
// stack with a synthetic assigned investigation and a populated projection. The response includes
// policy and audit work; a rejected request or empty fixture is not a graph performance sample.
// Never put tokens or case/root identifiers in k6 tags, output or a committed report.
import http from "k6/http";
import { check, fail } from "k6";
import { Trend } from "k6/metrics";

http.setResponseCallback(http.expectedStatuses(200));

const lens = __ENV.CONTEXT_PERF_LENS;
const graphLatency = new Trend("context_graph_read_ms", true);

export const options = {
  // k6's default `url` system tag includes the path reference. Keep only non-identifying tags.
  systemTags: ["status", "method", "name", "check", "scenario", "expected_response"],
  scenarios: {
    authorized_graph_reads: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: 5 },
        { duration: "1m", target: 5 },
        { duration: "30s", target: 0 },
      ],
      gracefulStop: "10s",
    },
  },
  thresholds: {
    context_graph_read_ms: ["p(95)<300", "p(99)<1000"],
    http_req_failed: ["rate==0"],
    checks: ["rate==1"],
  },
};

export function setup() {
  const required = [
    "CONTEXT_PERF_URL",
    "CONTEXT_PERF_TOKEN",
    "CONTEXT_PERF_CASE_ID",
    "CONTEXT_PERF_PURPOSE",
    "CONTEXT_PERF_REFERENCE",
    "CONTEXT_PERF_LENS",
  ];
  if (required.some((key) => !__ENV[key])) fail("Context Graph baseline requires every CONTEXT_PERF_* setting");
  if (lens !== "complaint" && lens !== "incident") fail("CONTEXT_PERF_LENS must be complaint or incident");
  // Force an explicit local port-forward into the disposable target. This prevents a typo in an
  // environment variable from load-testing the shared sandbox or a production investigation.
  if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(__ENV.CONTEXT_PERF_URL)) {
    fail("CONTEXT_PERF_URL must be a localhost origin for an isolated target");
  }
  if (__ENV.CONTEXT_PERF_REFERENCE.length > 200) fail("Context Graph root reference is too long");
  if (__ENV.CONTEXT_PERF_CASE_ID.length > 200 || __ENV.CONTEXT_PERF_PURPOSE.length > 80) {
    fail("Context Graph case or purpose exceeds the API limit");
  }
}

export default function () {
  const baseUrl = __ENV.CONTEXT_PERF_URL.replace(/\/$/, "");
  const reference = encodeURIComponent(__ENV.CONTEXT_PERF_REFERENCE);
  const path = lens === "complaint"
    ? `/api/v1/context/complaints/${reference}`
    : `/api/v1/context/incidents/${reference}/impact`;
  const response = http.get(`${baseUrl}${path}`, {
    headers: {
      Authorization: `Bearer ${__ENV.CONTEXT_PERF_TOKEN}`,
      "X-Investigation-Case-Id": __ENV.CONTEXT_PERF_CASE_ID,
      "X-Investigation-Purpose": __ENV.CONTEXT_PERF_PURPOSE,
      "Cache-Control": "no-cache",
    },
    tags: { name: `context_${lens}` },
    responseType: "binary",
    redirects: 0,
    timeout: "2s",
  });
  graphLatency.add(response.timings.duration);

  let body = null;
  const bounded = response.body !== null && response.body.byteLength <= 256 * 1024;
  if (response.status === 200 && bounded) {
    try {
      body = response.json();
    } catch (_) {
      // A malformed 200 is a failed sample, not a successful graph read.
    }
  }
  check(response, {
    "authorized graph read returned 200": (r) => r.status === 200,
    "graph response is valid JSON": () => body !== null,
    "graph response is bounded": () => response.status === 200 && bounded,
    "graph fixture contains source evidence": () => lens === "complaint"
      ? body !== null && Array.isArray(body.nodes) && body.nodes.length > 0 && body.nodes.length <= 100 &&
          Array.isArray(body.edges) && body.edges.length > 0 && body.edges.length <= 200 &&
          typeof body.truncated === "boolean"
      : body !== null && body.projectionStatus === "AVAILABLE" && Number.isInteger(body.total) && body.total > 0,
  });
}
