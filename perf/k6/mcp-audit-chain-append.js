// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// DECLARED, NOT RUNNABLE YET (ADR-0384 D0, #16617 layer 6).
//
// The customer MCP channel appends every audited call to a hash-linked chain under a per-producer
// advisory lock, so the chain's append rate is a CEILING on audited calls per deployment. ADR-0384
// requires that ceiling to be measured before the route opens. Until mcp-service opts in to the
// hash-linked audit publisher (`openbank.audit.publisher=hash-linked-outbox`, with an
// `AuditChainOutbox` over its own database) there is no append path to drive, so this script
// refuses to run rather than measure the logging fallback and report it as a chain.
//
// What it will measure once D0 lands (the contract this file reserves, so the scenario is catalogued
// and visible in Test Intelligence as blocked, not absent):
//   - sustained append rate with N concurrent producers contending for the one chain-head lock;
//   - p95/p99 append latency including lock wait, and the time a tool call spends waiting on it;
//   - chain integrity after the run: every row links to its predecessor, no gap, no fork
//     (`mcp_chain_breaks` must be 0 — a faster append that forks the chain is a regression);
//   - the saturation point, so the per-call limiter cost table can be sized below it.
import { fail } from "k6";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";

// Declared so the reserved thresholds below resolve. Nothing is ever recorded into them until the
// append driver exists: a zero sample would read as a measured, healthy chain.
new Counter("mcp_chain_breaks");
new Trend("mcp_chain_append_ms", true);

export const options = {
  scenarios: {
    append: { executor: "constant-arrival-rate", rate: 50, timeUnit: "1s", duration: "2m", preAllocatedVUs: 20, maxVUs: 100 },
  },
  thresholds: {
    // Reserved, advisory until a baseline exists (ADR-0243). Integrity is the only hard one.
    mcp_chain_breaks: ["count==0"],
    mcp_chain_append_ms: ["p(95)<50", "p(99)<200"],
  },
};

export function setup() {
  fail("mcp-audit-chain-append is pending ADR-0384 D0 (mcp-service has no hash-linked audit publisher yet); nothing to measure");
}

// `k6 run --no-setup` skips setup(), so the refusal must also live here: this never measures anything.
export default function () {
  exec.test.abort("mcp-audit-chain-append is pending ADR-0384 D0; refusing to run without setup() and record nothing");
}
