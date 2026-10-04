// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { EvidenceIntegrityNotice } from '@/components/lending/EvidenceIntegrityNotice'
import { evidenceIntegrity, hashBadge, hashStatusOf } from '@/lib/lending/evidenceIntegrity'

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('openbank-admin-lang', 'en')
})
afterEach(() => cleanup())

const mount = (body: unknown) =>
  render(<LanguageProvider><EvidenceIntegrityNotice integrity={evidenceIntegrity(body)} /></LanguageProvider>)

describe('lending evidence integrity (#11900)', () => {
  it('raises an alert when the bundle says an entry was altered', () => {
    mount({ attestation: 'audit-chain', tampered: true, truncated: false, events: [] })
    expect(screen.getByTestId('evidence-tampered')).toHaveAttribute('role', 'alert')
    expect(screen.getByTestId('evidence-attestation')).toHaveTextContent('tamper-evident audit chain')
    expect(screen.queryByTestId('evidence-truncated')).toBeNull()
  })

  it('says a truncated trail is not the complete history', () => {
    mount({ attestation: 'audit-chain', tampered: false, truncated: true, events: [] })
    expect(screen.getByTestId('evidence-truncated')).toHaveTextContent('not the complete history')
    expect(screen.queryByTestId('evidence-tampered')).toBeNull()
  })

  it('renders nothing for an older backend that sends no integrity fields — absent is unknown, not verified', () => {
    mount({ attestation: 'local-outbox', events: [] })
    expect(screen.queryByTestId('evidence-tampered')).toBeNull()
    expect(screen.queryByTestId('evidence-truncated')).toBeNull()
    expect(screen.queryByTestId('evidence-attestation')).toBeNull()
  })

  it('only a literal true counts — a truthy string does not raise or hide the alarm by accident', () => {
    expect(evidenceIntegrity({ tampered: 'false' }).tampered).toBe(false)
    expect(evidenceIntegrity(null)).toEqual({ source: null, tampered: false, truncated: false })
  })

  it('maps row statuses to badges, and an unknown status to none', () => {
    expect(hashBadge(hashStatusOf('MISMATCH'))).toBe('altered')
    expect(hashBadge(hashStatusOf('UNCHAINED'))).toBe('unverifiable')
    expect(hashBadge(hashStatusOf('LEGACY_UNVERIFIABLE'))).toBe('unverifiable')
    expect(hashBadge(hashStatusOf('VERIFIED'))).toBeNull()
    expect(hashBadge(hashStatusOf('SOMETHING_NEW'))).toBeNull()
    expect(hashBadge(hashStatusOf(undefined))).toBeNull()
  })

  it('the page wires the notice and the per-row badge (the bundle flag, not the table rows, drives the alarm)', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/lending/applications/[id]/page.tsx'), 'utf8')
    expect(source).toContain('setIntegrity(evidenceIntegrity(body))')
    expect(source).toContain("{evidenceState === 'ok' && <EvidenceIntegrityNotice integrity={integrity} />}")
    expect(source).toContain('data-testid="evidence-row-altered"')
    expect(source).toContain('badge: hashBadge(hashStatusOf(e.hashStatus))')
  })
})
