// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only Context Graph baseline. Run each lens separately against an isolated, auth-enabled
// stack with a synthetic assigned investigation and a populated projection. The response includes
// policy and audit work; a rejected request or empty fixture is not a graph performance sample.
// Never put tokens or case/root identifiers in k6 tags, output or a committed report.
// For a reversal fixture, set CONTEXT_PERF_EXPECTED_REVERSAL_BOOKING_ID to require both
// the source-backed reversal edge and a posted reversal journal in every successful sample.
import http from "k6/http";
import { check, fail } from "k6";
import { Trend } from "k6/metrics";

http.setResponseCallback(http.expectedStatuses(200));

const lens = __ENV.CONTEXT_PERF_LENS;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
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
  if (!["complaint", "incident", "authority", "aml", "aml-network", "kyb", "fraud-network"].includes(lens)) {
    fail("CONTEXT_PERF_LENS must be complaint, incident, authority, aml, aml-network, kyb or fraud-network");
  }
  // Force an explicit local port-forward into the disposable target. This prevents a typo in an
  // environment variable from load-testing the shared sandbox or a production investigation.
  if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(__ENV.CONTEXT_PERF_URL)) {
    fail("CONTEXT_PERF_URL must be a localhost origin for an isolated target");
  }
  if (__ENV.CONTEXT_PERF_REFERENCE.length > 200) fail("Context Graph root reference is too long");
  if (__ENV.CONTEXT_PERF_CASE_ID.length > 200 || __ENV.CONTEXT_PERF_PURPOSE.length > 80) {
    fail("Context Graph case or purpose exceeds the API limit");
  }
  if (lens === "kyb" && (!/^[1-9][0-9]*$/.test(__ENV.CONTEXT_PERF_MIN_KYB_REVISIONS || "") ||
      Number(__ENV.CONTEXT_PERF_MIN_KYB_REVISIONS) > 50)) {
    fail("KYB baseline requires CONTEXT_PERF_MIN_KYB_REVISIONS between 1 and 50");
  }
  if (lens === "fraud-network") {
    const assigned = Number(__ENV.CONTEXT_PERF_ASSIGNED_CASES);
    if (!Number.isSafeInteger(assigned) || assigned < 2 || assigned > 10000 ||
        !UUID.test(__ENV.CONTEXT_PERF_EXPECTED_RELATED_CASE_ID || "")) {
      fail("Fraud network baseline requires a declared assigned-case count and a synthetic related-case fixture");
    }
  }
  if (lens === "complaint" && __ENV.CONTEXT_PERF_EXPECTED_REVERSAL_BOOKING_ID &&
      !UUID.test(__ENV.CONTEXT_PERF_EXPECTED_REVERSAL_BOOKING_ID)) {
    fail("Complaint reversal fixture requires a synthetic reversal booking UUID");
  }
}

export default function () {
  const baseUrl = __ENV.CONTEXT_PERF_URL.replace(/\/$/, "");
  const reference = encodeURIComponent(__ENV.CONTEXT_PERF_REFERENCE);
  const paths = {
    complaint: `/api/v1/context/complaints/${reference}`,
    incident: `/api/v1/context/incidents/${reference}/impact`,
    authority: `/api/v1/context/authorizations/${reference}`,
    aml: `/api/v1/context/aml-cases/${reference}`,
    "aml-network": `/api/v1/context/aml-cases/${reference}/network`,
    kyb: `/api/v1/context/kyb-cases/${reference}/ownership-observations`,
    "fraud-network": `/api/v1/context/fraud-cases/${reference}/network`,
  };
  const path = paths[lens];
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
    "graph fixture contains source evidence": () => {
      if (body === null) return false;
      if (lens === "complaint") {
        const reversal = __ENV.CONTEXT_PERF_EXPECTED_REVERSAL_BOOKING_ID;
        const reversalKey = `booking-transaction:${reversal}`;
        const nodesBounded = Array.isArray(body.nodes) && body.nodes.length > 0 && body.nodes.length <= 100;
        const edgesBounded = Array.isArray(body.edges) && body.edges.length > 0 && body.edges.length <= 200;
        const reversalEvidence = !reversal || (nodesBounded && edgesBounded &&
          body.nodes.some((node) => node.key === reversalKey) &&
          body.edges.some((edge) => edge.to === reversalKey && edge.relation === "REVERSED_BY") &&
          body.edges.some((edge) => edge.from === reversalKey && edge.relation === "BOOKED_AS")
        );
        return nodesBounded && edgesBounded && typeof body.truncated === "boolean" && reversalEvidence;
      }
      if (lens === "incident") {
        return body.projectionStatus === "AVAILABLE" && Number.isInteger(body.total) && body.total > 0;
      }
      if (lens === "aml-network") {
        return Array.isArray(body.related) && body.related.length > 0 && body.related.length <= 4 &&
          hasEvidence(body.root, 100) && body.related.every((caseHistory) => hasEvidence(caseHistory, 20));
      }
      if (lens === "kyb") {
        return body.root === `kyb-case:${__ENV.CONTEXT_PERF_REFERENCE}` &&
          Array.isArray(body.observations) &&
          body.observations.length >= Number(__ENV.CONTEXT_PERF_MIN_KYB_REVISIONS) &&
          body.observations.length <= 50 && typeof body.truncated === "boolean" &&
          body.observations.every((item) => Number.isInteger(item.revision) && item.revision > 0 &&
            /^[0-9a-f]{64}$/.test(item.sourceSha256)) &&
          body.observations.every((item, index) => index === 0 ||
            item.revision < body.observations[index - 1].revision);
      }
      if (lens === "fraud-network") return hasFraudNetworkEvidence(body);
      return hasEvidence(body, 100) &&
        (lens !== "authority" || body.actionAuthorization === "UNKNOWN");
    },
  });
}

function hasFraudNetworkEvidence(body) {
  const assigned = Number(__ENV.CONTEXT_PERF_ASSIGNED_CASES);
  const expected = __ENV.CONTEXT_PERF_EXPECTED_RELATED_CASE_ID.toLowerCase();
  if (!body || body.root?.status !== "OPEN" ||
      body.root.caseId?.toLowerCase() !== __ENV.CONTEXT_PERF_REFERENCE.toLowerCase() ||
      !Array.isArray(body.related) || body.related.length < 1 || body.related.length > 4 ||
      !Number.isInteger(body.inspectedCandidates) || body.inspectedCandidates < body.related.length ||
      body.inspectedCandidates > 4 || body.comparedCandidates !== Math.min(assigned, 256) ||
      typeof body.candidateTruncated !== "boolean" ||
      (assigned > 256 && !body.candidateTruncated)) return false;
  const seen = new Set([body.root.caseId]);
  return body.related.every((item) => {
    const evidence = item?.evidence;
    if (evidence?.status !== "OPEN" || seen.has(evidence.caseId) ||
        !Array.isArray(item.shared) || item.shared.length < 1 || item.shared.length > 2) return false;
    seen.add(evidence.caseId);
    return item.shared.every((edge) =>
      typeof edge.sourceId === "string" && UUID.test(edge.sourceId) &&
      ((edge.type === "ACCOUNT" && edge.sourceId === body.root.accountId && edge.sourceId === evidence.accountId) ||
        (edge.type === "COUNTERPARTY" && edge.sourceId === body.root.counterpartyId &&
          edge.sourceId === evidence.counterpartyId)));
  }) && seen.has(expected);
}

function hasEvidence(history, limit) {
  return history !== null && typeof history === "object" &&
    typeof history.root === "string" && history.root.length > 0 &&
    Array.isArray(history.observations) && history.observations.length > 0 &&
    history.observations.length <= limit &&
    history.observations.every((item) => typeof item.evidenceRef === "string" &&
      /^[0-9a-f]{64}$/.test(item.contentHash)) &&
    typeof history.truncated === "boolean";
}
