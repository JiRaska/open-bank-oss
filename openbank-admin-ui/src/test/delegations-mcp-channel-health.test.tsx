// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0384 D4: unknown must never render as a healthy-looking "none" / 0.

import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { McpChannelHealth } from '@/components/delegations/McpChannelHealth'

const BASE = {
  state: 'ok' as const,
  window: '1h',
  customerCalls: null,
  syntheticCalls: null,
  agentCalls: 3,
  rejectsByLayer: null,
  activeGrants: null,
}

function renderCs(firingAlerts: string[] | null) {
  return render(
    <LanguageProvider>
      <McpChannelHealth data={{ ...BASE, firingAlerts }} />
    </LanguageProvider>,
  )
}

describe('McpChannelHealth', () => {
  it('a failed alerts query renders as not measured, not as "none"', () => {
    renderCs(null)
    expect(screen.getByTestId('mcp-alerts-unmeasured')).toBeTruthy()
    expect(screen.queryByText(/^(Žádné|None)\.$/)).toBeNull()
  })

  it('an empty alert list renders as none', () => {
    renderCs([])
    expect(screen.queryByTestId('mcp-alerts-unmeasured')).toBeNull()
  })

  it('absent counters render as not measured, never 0', () => {
    renderCs([])
    expect(screen.getAllByText(/neměřeno|not measured/).length).toBeGreaterThanOrEqual(3)
    expect(screen.getByTestId('mcp-rejects-unmeasured')).toBeTruthy()
  })
})
