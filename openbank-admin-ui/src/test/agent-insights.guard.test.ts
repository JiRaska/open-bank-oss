// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI agent-output rule (enforced) ──────────────────────────────────
//
// Why this guard exists: the fleet has a growing population of AI agents
// (devops-agent, finops-agent, the AML/sanctions/GDPR oversight agents — ADR
// -0031/0112/0119), and each one's operator-facing output was drifting into
// bespoke copy-pasted card markup on every page. DevOps, FinOps and IAOps each
// grew a near-identical finding renderer, and the FinOps one ended up buried at
// the bottom of an unrelated cost panel where an operator never saw it.
//
// The rule (admin-ui CLAUDE.md #6): a page that surfaces an AI agent's findings
// renders them through the shared <AgentInsightsPanel>, directly below the page's
// metric/KPI cards. Pages must NOT hand-roll finding cards.
//
// This test is the executable form of that rule. It detects a hand-rolled
// renderer by its signature — a HITL lifecycle status map with the
// proposed/approved/rejected keys — and fails if the owning page does not import
// AgentInsightsPanel. If this flags your page: map your finding type to the
// shared AgentFinding view-model (see toAgentFinding() in devops/finops/iaops)
// and render <AgentInsightsPanel> instead of bespoke cards.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import path from 'path'

const APP_DIR = path.resolve(__dirname, '../app')

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (entry === 'page.tsx') out.push(full)
  }
  return out
}

// The signature of a hand-rolled agent-*finding* renderer: an object literal that
// maps the lifecycle states (both the lowercase finops and uppercase devops
// variants), AND a reference to a `detector` field — the hallmark of the D1–D6
// detector findings that AgentInsightsPanel renders. Requiring `detector` keeps
// the guard scoped to inline findings panels and off the dedicated agent-approval
// queue (approvals/page.tsx), which is a richer, intentionally separate workbench
// with no detector codes and no metrics row.
function hasInlineFindingRenderer(src: string): boolean {
  return /\bproposed\s*:/i.test(src)
    && /\bapproved\s*:/i.test(src)
    && /\brejected\s*:/i.test(src)
    && /\bdetector\b/.test(src)
}

function usesSharedPanel(src: string): boolean {
  return /AgentInsightsPanel/.test(src)
}

describe('agent-output rule (admin-ui CLAUDE.md #6)', () => {
  const pages = walk(APP_DIR)

  it('finds page files to scan', () => {
    expect(pages.length).toBeGreaterThan(0)
  })

  for (const page of pages) {
    const rel = path.relative(APP_DIR, page)
    it(`${rel} renders agent findings via the shared AgentInsightsPanel`, () => {
      const src = readFileSync(page, 'utf8')
      if (!hasInlineFindingRenderer(src)) return // page has no agent-finding renderer — nothing to enforce

      expect(
        usesSharedPanel(src),
        `${rel} hand-rolls an AI-agent finding renderer (HITL proposed/approved/rejected status map). ` +
        `Map the finding type to the shared AgentFinding view-model and render ` +
        `<AgentInsightsPanel> (src/components/agent/AgentInsightsPanel.tsx) instead — see ` +
        `devops/finops/iaops/page.tsx for the toAgentFinding() pattern.`,
      ).toBe(true)
    })
  }
})
