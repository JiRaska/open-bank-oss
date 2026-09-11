// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseAudiencePreview } from '@/lib/audiences/previewContract'

const preview = { state: 'ok', name: 'actives', version: 2, size: 1240, asOf: '2026-09-09T08:00:00Z' }

describe('audience reach preview contract', () => {
  it('accepts matching production-evaluator evidence', () => {
    expect(parseAudiencePreview(preview, 'actives', 2)).toEqual({ state: 'ok', size: 1240, asOf: preview.asOf })
  })

  it.each([
    [{ ...preview, name: 'another' }, 'identity'],
    [{ ...preview, version: 3 }, 'identity'],
    [{ ...preview, size: -1 }, 'size'],
    [{ ...preview, size: 1.5 }, 'size'],
    [{ ...preview, asOf: 'not-a-date' }, 'asOf'],
    [{ state: 'ok' }, 'identity'],
    [{ state: 'mystery' }, 'state'],
  ])('rejects malformed or mismatched reach evidence', (raw, message) => {
    expect(() => parseAudiencePreview(raw, 'actives', 2)).toThrow(message)
  })

  it('keeps a known operational failure distinct from a zero-sized cohort', () => {
    expect(parseAudiencePreview({ state: 'unreachable' }, 'actives', 2)).toEqual({ state: 'unreachable' })
  })
})
