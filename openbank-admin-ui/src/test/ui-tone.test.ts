// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import {
  BADGE_CLASS,
  DOT_CLASS,
  SWATCH_CLASS,
  TONE_TEXT_CLASS,
  statusBadgeClass,
  statusDotClass,
  statusTone,
} from '@/components/ui/tone'

describe('statusTone (ADR-0208 D2)', () => {
  it('maps terminal-good statuses to success', () => {
    for (const s of ['ACTIVE', 'COMPLETED', 'SETTLED', 'CLEARED', 'APPROVED', 'OK', 'SENT']) {
      expect(statusTone(s), s).toBe('success')
    }
  })

  it('maps in-flight statuses to warning', () => {
    for (const s of ['PENDING', 'PENDING_SCA', 'PENDING_APPROVAL', 'PENDING_CONFIRMATION', 'PROCESSING', 'QUEUED', 'DRAFT']) {
      expect(statusTone(s), s).toBe('warning')
    }
  })

  it('maps failure statuses to danger', () => {
    for (const s of ['FAILED', 'REJECTED', 'BLOCKED', 'SUSPENDED', 'CRITICAL', 'ERROR', 'BOUNCED', 'ESCALATED', 'DEPRECATED']) {
      expect(statusTone(s), s).toBe('danger')
    }
  })

  it('maps terminal-but-not-failed statuses to neutral, not danger', () => {
    // REVOKED/EXPIRED/CANCELLED are the customer or the clock ending something normally. Colouring
    // them red reads as "something went wrong" on a screen an operator scans for real problems.
    for (const s of ['REVOKED', 'EXPIRED', 'CANCELLED', 'CLOSED', 'MERGED', 'SUPPRESSED', 'CLEAN', 'ARCHIVED', 'INACTIVE']) {
      expect(statusTone(s), s).toBe('neutral')
    }
  })

  // The load-bearing assertion. An unrecognised status must never render as a green tick — that is
  // how a status nobody taught the helper about becomes a false "healthy" on a compliance screen.
  it('resolves an unknown status to neutral, never success', () => {
    for (const s of ['WAT', 'PENDING_FAILOVER', 'SOME_NEW_STATE', 'áčž', '']) {
      expect(statusTone(s), s).toBe('neutral')
    }
  })

  it('does not substring-match: PENDING_FAILOVER is not danger just because it contains FAIL', () => {
    expect(statusTone('PENDING_FAILOVER')).not.toBe('danger')
  })

  it('treats null and undefined as neutral', () => {
    expect(statusTone(null)).toBe('neutral')
    expect(statusTone(undefined)).toBe('neutral')
  })

  it('is case- and whitespace-insensitive', () => {
    expect(statusTone('active')).toBe('success')
    expect(statusTone('  Active  ')).toBe('success')
  })

  it('every Tone has both a badge and a dot class', () => {
    for (const tone of Object.keys(BADGE_CLASS) as Array<keyof typeof BADGE_CLASS>) {
      expect(BADGE_CLASS[tone], `badge ${tone}`).toMatch(/^badge badge-/)
      expect(DOT_CLASS[tone], `dot ${tone}`).toMatch(/^status-dot status-dot-/)
    }
  })

  it('never emits a raw colour value — only class names', () => {
    // The whole point of the indirection: theming and contrast live in globals.css, so a hex here
    // would put a colour decision back into TypeScript where it cannot be themed.
    for (const cls of [...Object.values(BADGE_CLASS), ...Object.values(DOT_CLASS)]) {
      expect(cls).not.toMatch(/#[0-9a-fA-F]{3,6}|rgb|hsl/)
    }
  })

  it('convenience helpers agree with statusTone', () => {
    expect(statusBadgeClass('FAILED')).toBe(BADGE_CLASS.danger)
    expect(statusDotClass('ACTIVE')).toBe(DOT_CLASS.success)
    expect(statusBadgeClass('NOPE')).toBe(BADGE_CLASS.neutral)
  })

  it('every Tone has a swatch and a text class, and swatch never reuses .badge geometry', () => {
    for (const tone of Object.keys(BADGE_CLASS) as Array<keyof typeof BADGE_CLASS>) {
      // Must compose .tone-swatch with the COLOUR-only .badge-* rule, never `.badge` itself:
      // `.badge` sets padding 4px 10px + border-radius 20px, which under the global
      // box-sizing: border-box collapses an 18px swatch to zero content width and a circle.
      expect(SWATCH_CLASS[tone], `swatch ${tone}`).toMatch(/^tone-swatch badge-/)
      expect(SWATCH_CLASS[tone], `swatch ${tone} must not apply .badge`).not.toMatch(/(^|\s)badge(\s|$)/)
      expect(TONE_TEXT_CLASS[tone], `text ${tone}`).toMatch(/^tone-text-/)
    }
  })

  // EXPIRED/REVOKED are domain-ambiguous: the shared default is the consent reading (a normal
  // lifecycle end), while pid/page.tsx reads them as warning/danger (an unusable or withdrawn
  // credential). This documents that the default is deliberate and that the override exists, so a
  // later migration of pid cannot silently inherit the wrong semantics.
  it('documents the domain-ambiguous defaults for EXPIRED and REVOKED', () => {
    expect(statusTone('EXPIRED')).toBe('neutral')
    expect(statusTone('REVOKED')).toBe('neutral')
    // A domain that disagrees passes an explicit tone rather than editing the shared map.
    expect(BADGE_CLASS.warning).toBe('badge badge-warning')
    expect(BADGE_CLASS.danger).toBe('badge badge-danger')
  })
})
