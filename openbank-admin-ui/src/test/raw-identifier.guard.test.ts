// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// RULE: a raw identifier is never the text a person reads. A row is named by something the reader
// recognises (product + maturity, contract number, customer); the id may appear only as a small,
// copyable secondary reference — `HumanReference` from `@/components/ui`, or `EntityChip` for a
// party/account the BFF can resolve.
//
// Why a guard: the liquidity page listed dozens of rows labelled `Loan 8169e954-…` and the owner
// called it frightening to read; the same shape exists on dozens of other pages. Prose does not
// stop the next one, so this ratchets:
//   - a file NOT in BASELINE must have zero findings (every new page starts clean);
//   - a file IN BASELINE may not gain findings, and must have its entry LOWERED (or removed) when
//     it is swept, so the debt only ever shrinks.
// A line that interpolates an id-named value which is not an entity id (a parameter-set name, a
// control code) is waived with a `raw-id-ok: <reason>` comment on that line.

import { readFileSync, globSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { findRawIdentifiers } from './raw-identifier-detector'

// Burn down; never add to. Measured 2026-10-01 at the introduction of this guard.
const BASELINE: Record<string, number> = {
  'src/app/accounts/page.tsx': 1,
  'src/app/approvals/delegation/[id]/page.tsx': 1,
  'src/app/approvals/page.tsx': 1,
  'src/app/audit/page.tsx': 2,
  'src/app/balance-sheet/ledger-backfill/page.tsx': 1,
  'src/app/balance-sheet/snapshots/[id]/min-reserves/page.tsx': 1,
  'src/app/balance-sheet/snapshots/[id]/page.tsx': 1,
  'src/app/communication/[personaId]/page.tsx': 1,
  'src/app/communication/edit/[personaKey]/page.tsx': 1,
  'src/app/communication/page.tsx': 1,
  'src/app/consents/page.tsx': 2,
  'src/app/customer-360/page.tsx': 1,
  'src/app/day-end/page.tsx': 1,
  'src/app/delegations/page.tsx': 1,
  'src/app/disputes/page.tsx': 1,
  'src/app/docs/control-tower/page.tsx': 1,
  'src/app/feedback/page.tsx': 2,
  'src/app/fraud/page.tsx': 1,
  'src/app/fx/page.tsx': 2,
  'src/app/iaops/agents/[agentId]/page.tsx': 1,
  'src/app/iaops/cases/[caseId]/page.tsx': 1,
  'src/app/iaops/page.tsx': 2,
  'src/app/interest/page.tsx': 1,
  'src/app/kyc/page.tsx': 1,
  'src/app/ledger/page.tsx': 3,
  'src/app/loyalty/page.tsx': 2,
  'src/app/observability/traces/page.tsx': 2,
  'src/app/onboarding/page.tsx': 1,
  'src/app/parties/[id]/page.tsx': 3,
  'src/app/payments/page.tsx': 3,
  'src/app/pid/page.tsx': 3,
  'src/app/product-studio/page.tsx': 1,
  'src/app/security/incidents/page.tsx': 1,
  'src/app/system/agent/page.tsx': 1,
  'src/app/system/tests/page.tsx': 6,
  'src/app/transactions/page.tsx': 2,
  'src/app/treasury/nostro/page.tsx': 3,
  'src/components/campaigns/PeopleSummary.tsx': 1,
  'src/components/devops/GateCatalogExplorer.tsx': 1,
  'src/components/devops/QualityGateHealthPanel.tsx': 2,
  'src/components/devops/RemediationReviewDialog.tsx': 1,
  'src/components/party/CustomerContextGraph.tsx': 1,
}

const FILES = [...globSync('src/app/**/*.tsx'), ...globSync('src/components/**/*.tsx')].sort()

describe('raw identifiers are never rendered as primary text', () => {
  it('scans the tree it is meant to (a zero-file glob would pass vacuously)', () => {
    expect(FILES.length).toBeGreaterThan(200)
  })

  it('detector self-test: flags each known-positive shape', () => {
    const positives = [
      "<td>{l.instrumentId}</td>",
      "<td className=\"mono\">{p.id.slice(0, 8)}…</td>",
      "label={`Loan ${p.instrumentId}`}",
      "{t(`Úvěr ${loan.id}`, `Loan ${loan.id}`)}",
      "<span>Loan 8169e954-b42d-4bab-be5f-4c160263f098: due</span>",
      "<option key={a.id} value={a.id}>{a.accountId}</option>",
    ]
    for (const line of positives) expect(findRawIdentifiers(line), line).not.toEqual([])
  })

  it('detector self-test: ignores ids in URLs, keys, data attributes, waived lines and copyable references', () => {
    const negatives = [
      "<Link href={`/parties/${p.partyId}`}>{p.legalName}</Link>",
      "<tr key={l.instrumentId} data-id={l.instrumentId}>",
      "<HumanReference label={named.label} reference={l.instrumentId} />",
      "label={`${t('Sada', 'Set')} ${data.parameterSetId}`} /> {/* raw-id-ok: parameter-set name */}",
      "{errors.partyId && <span role=\"alert\">{errors.partyId}</span>}",
      "// Loan 8169e954-b42d-4bab-be5f-4c160263f098 in a comment",
    ]
    for (const line of negatives) expect(findRawIdentifiers(line), line).toEqual([])
  })

  it('no file outside the baseline renders a raw identifier, and no baseline file gains one', () => {
    const regressions: string[] = []
    for (const f of FILES) {
      const found = findRawIdentifiers(readFileSync(f, 'utf8'))
      const allowed = BASELINE[f] ?? 0
      if (found.length > allowed) {
        regressions.push(`${f}: ${found.length} > baseline ${allowed}\n` + found.map(x => `    ${f}:${x.line} [${x.rule}] ${x.text.slice(0, 120)}`).join('\n'))
      }
    }
    expect(regressions, 'render a HumanReference / EntityChip instead of the id').toEqual([])
  })

  it('the baseline is tight: a swept file must lower or drop its entry', () => {
    const stale: string[] = []
    for (const [f, allowed] of Object.entries(BASELINE)) {
      let found = 0
      try { found = findRawIdentifiers(readFileSync(f, 'utf8')).length } catch { found = 0 }
      if (found < allowed) stale.push(`${f}: baseline ${allowed}, now ${found} — lower it`)
    }
    expect(stale).toEqual([])
  })
})
