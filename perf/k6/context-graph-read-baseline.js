// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only Context Graph baseline. Run each lens separately against an isolated, auth-enabled
// stack with a synthetic assigned investigation and a populated projection. The response includes
// policy and audit work; a rejected request or empty fixture is not a graph performance sample.
// Never put tokens or case/root identifiers in k6 tags, output or a committed report.
// Run CONTEXT_PERF_PROFILE=capacity on both isolated 1x and 10x annual-volume fixtures;
// 10x refers to stored data, while the request rate stays at the planned 100 RPS.
// For a reversal fixture, set CONTEXT_PERF_EXPECTED_REVERSAL_BOOKING_ID to require both
// the source-backed reversal edge and a posted reversal journal in every successful sample.
import http from "k6/http";
import { check, fail } from "k6";
import { Counter, Trend } from "k6/metrics";

// Capacity setup deliberately probes a denied identity; a 403 there is expected.
// The measured positive path still requires 200 through its explicit checks.
http.setResponseCallback(http.expectedStatuses(200, 403));

const lens = __ENV.CONTEXT_PERF_LENS;
const profile = __ENV.CONTEXT_PERF_PROFILE || "smoke";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const graphLatency = new Trend("context_graph_read_ms", true);
const responseStatuses = new Counter("context_http_response_status");
const responseStatusBuckets = new Set([0, 200, 204, 400, 401, 403, 404, 429, 500, 503]);

const scenarios = profile === "capacity" ? {
  authorized_graph_reads: {
    executor: "constant-arrival-rate",
    rate: 100,
    timeUnit: "1s",
    duration: "5m",
    preAllocatedVUs: 50,
    maxVUs: 100,
    gracefulStop: "10s",
  },
} : {
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
};

export const options = {
  // k6's default `url` system tag includes the path reference. Keep only non-identifying tags.
  systemTags: ["status", "method", "name", "check", "scenario", "expected_response"],
  scenarios,
  thresholds: {
    context_graph_read_ms: ["p(95)<300", "p(99)<1000"],
    ...(profile === "capacity" ? { dropped_iterations: ["count==0"] } : {}),
    http_req_failed: ["rate==0"],
    checks: ["rate==1"],
  },
};

export function setup() {
  if (!["smoke", "capacity"].includes(profile)) {
    fail("CONTEXT_PERF_PROFILE must be smoke or capacity");
  }
  const required = [
    "CONTEXT_PERF_URL",
    "CONTEXT_PERF_TOKEN",
    "CONTEXT_PERF_CASE_ID",
    "CONTEXT_PERF_PURPOSE",
    "CONTEXT_PERF_REFERENCE",
    "CONTEXT_PERF_LENS",
  ];
  if (required.some((key) => !__ENV[key])) fail("Context Graph baseline requires every CONTEXT_PERF_* setting");
  if (!["complaint", "incident", "authority", "aml", "aml-network", "kyb", "fraud-network", "lending-guarantees", "lending-shared"].includes(lens)) {
    fail("CONTEXT_PERF_LENS is not a supported graph lens");
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
  if (["lending-guarantees", "lending-shared"].includes(lens) &&
      (!UUID.test(__ENV.CONTEXT_PERF_REFERENCE) ||
       __ENV.CONTEXT_PERF_CASE_ID.toLowerCase() !== __ENV.CONTEXT_PERF_REFERENCE.toLowerCase() ||
       __ENV.CONTEXT_PERF_PURPOSE !== "LENDING_EXPOSURE_REVIEW" ||
       !UUID.test(__ENV.CONTEXT_PERF_EXPECTED_GUARANTEE_ID || ""))) {
    fail("Lending baseline requires an assigned loan and one synthetic approved guarantee fixture");
  }
  if (lens === "lending-shared" && !UUID.test(__ENV.CONTEXT_PERF_EXPECTED_RELATED_LOAN_ID || "")) {
    fail("Shared lending baseline requires a synthetic related loan fixture");
  }
  if (profile === "capacity" && ["lending-guarantees", "lending-shared"].includes(lens)) {
    if (!__ENV.CONTEXT_PERF_DENIED_TOKEN ||
        __ENV.CONTEXT_PERF_DENIED_TOKEN === __ENV.CONTEXT_PERF_TOKEN) {
      fail("Lending capacity baseline requires a distinct valid token without the graph role");
    }
    const deniedPath = lens === "lending-shared" ? "shared-guarantors" : "approved-guarantees";
    const denied = http.get(
      `${__ENV.CONTEXT_PERF_URL.replace(/\/$/, "")}/api/v1/context/lending-loans/${encodeURIComponent(__ENV.CONTEXT_PERF_REFERENCE)}/${deniedPath}`,
      {
        headers: {
          Authorization: `Bearer ${__ENV.CONTEXT_PERF_DENIED_TOKEN}`,
          "X-Investigation-Case-Id": __ENV.CONTEXT_PERF_CASE_ID,
          "X-Investigation-Purpose": __ENV.CONTEXT_PERF_PURPOSE,
        },
        tags: { name: `context_${lens}_denied_preflight` },
        redirects: 0,
        timeout: "2s",
      },
    );
    if (denied.status !== 403 || denied.body?.includes(__ENV.CONTEXT_PERF_EXPECTED_GUARANTEE_ID)) {
      fail("Lending capacity baseline requires a 403 without guarantee evidence for the denied role");
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
    "lending-guarantees": `/api/v1/context/lending-loans/${reference}/approved-guarantees`,
    "lending-shared": `/api/v1/context/lending-loans/${reference}/shared-guarantors`,
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
  responseStatuses.add(1, {
    status: responseStatusBuckets.has(response.status) ? String(response.status) : "other",
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
      if (lens === "lending-guarantees") return hasLendingGuaranteeEvidence(body);
      if (lens === "lending-shared") return hasLendingSharedEvidence(body);
      return hasEvidence(body, 100) &&
        (lens !== "authority" || body.actionAuthorization === "UNKNOWN");
    },
  });
}

function hasLendingGuaranteeEvidence(body) {
  const expected = __ENV.CONTEXT_PERF_EXPECTED_GUARANTEE_ID.toLowerCase();
  return body && typeof body.loanId === "string" &&
    body.loanId.toLowerCase() === __ENV.CONTEXT_PERF_REFERENCE.toLowerCase() &&
    typeof body.effectiveAt === "string" && typeof body.knownAt === "string" &&
    typeof body.truncated === "boolean" &&
    Array.isArray(body.guarantees) && body.guarantees.length > 0 && body.guarantees.length <= 100 &&
    body.guarantees.some((fact) => fact.guaranteeId?.toLowerCase() === expected &&
      UUID.test(fact.contractId) && UUID.test(fact.guarantorPartyId) &&
      UUID.test(fact.sourceDocumentId) && /^[0-9a-f]{64}$/i.test(fact.sourceSha256) &&
      Number.isInteger(fact.revision) && fact.revision > 0);
}

function hasLendingSharedEvidence(body) {
  const expectedLoan = __ENV.CONTEXT_PERF_EXPECTED_RELATED_LOAN_ID.toLowerCase();
  const expectedGuarantee = __ENV.CONTEXT_PERF_EXPECTED_GUARANTEE_ID.toLowerCase();
  if (!body || body.rootLoanId?.toLowerCase() !== __ENV.CONTEXT_PERF_REFERENCE.toLowerCase() ||
      typeof body.effectiveAt !== "string" || typeof body.knownAt !== "string" ||
      typeof body.candidateTruncated !== "boolean" || typeof body.relatedLoansTruncated !== "boolean" ||
      !Array.isArray(body.relatedLoans) || body.relatedLoans.length < 1 || body.relatedLoans.length > 4) return false;
  return body.relatedLoans.some((loan) => loan.loanId?.toLowerCase() === expectedLoan &&
    typeof loan.truncated === "boolean" && Array.isArray(loan.guarantees) &&
    loan.guarantees.length > 0 && loan.guarantees.length <= 20 &&
    loan.guarantees.some((fact) => fact.guaranteeId?.toLowerCase() === expectedGuarantee &&
      UUID.test(fact.guarantorPartyId) && UUID.test(fact.sourceDocumentId) &&
      /^[0-9a-f]{64}$/i.test(fact.sourceSha256)));
}

function hasFraudNetworkEvidence(body) {
  const assigned = Number(__ENV.CONTEXT_PERF_ASSIGNED_CASES);
  const expected = __ENV.CONTEXT_PERF_EXPECTED_RELATED_CASE_ID.toLowerCase();
  if (!body || !hasFraudEvidence(body.root, __ENV.CONTEXT_PERF_REFERENCE) ||
      !Array.isArray(body.related) || body.related.length < 1 || body.related.length > 4 ||
      !Number.isInteger(body.inspectedCandidates) || body.inspectedCandidates < body.related.length ||
      body.inspectedCandidates > 4 || body.comparedCandidates !== Math.min(assigned, 256) ||
      typeof body.candidateTruncated !== "boolean" ||
      (assigned > 256 && !body.candidateTruncated)) return false;
  const seen = new Set([body.root.caseId.toLowerCase()]);
  return body.related.every((item) => {
    const evidence = item?.evidence;
    if (!hasFraudEvidence(evidence) || seen.has(evidence.caseId.toLowerCase()) ||
        !Array.isArray(item.shared) || item.shared.length < 1 || item.shared.length > 2) return false;
    seen.add(evidence.caseId.toLowerCase());
    const expectedShared = [
      ...(body.root.accountId === evidence.accountId ? [{ type: "ACCOUNT", sourceId: body.root.accountId }] : []),
      ...(body.root.counterpartyId !== null && body.root.counterpartyId === evidence.counterpartyId
        ? [{ type: "COUNTERPARTY", sourceId: body.root.counterpartyId }] : []),
    ];
    return expectedShared.length > 0 && item.shared.length === expectedShared.length &&
      item.shared.every((edge, index) => edge.type === expectedShared[index].type &&
        edge.sourceId === expectedShared[index].sourceId);
  }) && seen.has(expected);
}

function hasFraudEvidence(evidence, caseId) {
  return evidence !== null && typeof evidence === "object" &&
    typeof evidence.caseId === "string" && UUID.test(evidence.caseId) &&
    (!caseId || evidence.caseId.toLowerCase() === caseId.toLowerCase()) &&
    typeof evidence.scoreId === "string" && UUID.test(evidence.scoreId) &&
    typeof evidence.accountId === "string" && UUID.test(evidence.accountId) &&
    (evidence.counterpartyId === null ||
      (typeof evidence.counterpartyId === "string" && UUID.test(evidence.counterpartyId))) &&
    evidence.status === "OPEN" && Number.isInteger(evidence.revision) && evidence.revision > 0 &&
    typeof evidence.openedAt === "string" && evidence.closedAt === null;
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
