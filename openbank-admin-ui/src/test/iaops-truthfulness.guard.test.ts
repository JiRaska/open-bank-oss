// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('IAOps agent capability truthfulness', () => {
  it('does not expose a fake interactive HITL trigger', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')

    expect(source).toContain('Analysis is not connected to the HITL backend yet')
    expect(source).toContain('Analýza zatím není připojená k HITL backendu')
    expect(source).not.toContain("alert(t('Funkce přijde v P4 (HITL backend)'")
    expect(source).not.toContain("{t('Spustit analýzu', 'Trigger Analysis')}")
  })

  it('does not imply an unconfigured monthly budget or hide burn-rate semantics', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')
    const costsRoute = readFileSync(path.resolve(__dirname, '../app/api/finops/ai-costs/route.ts'), 'utf8')

    expect(source).toContain("Cost / Budget status")
    expect(source).toContain('Monthly budget is not configured; burn rate uses daily thresholds ($1 / $5 / $10).')
    expect(source).toContain('role="status"')
    expect(source).not.toContain("t('Náklady / Budget', 'Cost / Budget')")
    expect(costsRoute).toContain('No monthly USD budget is configured in the current agents.yaml contract.')
    expect(costsRoute).not.toContain('TODO: read from agents.yaml limits.monthly_budget_usd')
  })

  it('labels partial Prometheus windows and does not invent provider placement', () => {
    const iaops = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')
    const finops = readFileSync(path.resolve(__dirname, '../app/finops/page.tsx'), 'utf8')
    const costsRoute = readFileSync(path.resolve(__dirname, '../app/api/finops/ai-costs/route.ts'), 'utf8')

    expect(costsRoute).toContain("PROMETHEUS_RETENTION_HOURS ?? '12'")
    expect(costsRoute).toContain('selfHostedPct: null')
    expect(costsRoute).not.toContain('selfHostedPct: 60')
    expect(iaops).toContain("costCoverage.windows['7d'].availableHours")
    expect(finops).toContain("aiCosts.coverage.windows['7d'].availableHours")
    expect(finops).toContain('Provider split is not exported by the bridge.')
  })
})
