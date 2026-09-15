// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { safeExternalUrl } from '@/lib/security/safeExternalUrl'

describe('safeExternalUrl', () => {
  it('accepts and canonicalizes absolute HTTPS links', () => {
    expect(safeExternalUrl('https://github.com/JiRaska/open-bank-oss/pull/1')).toBe(
      'https://github.com/JiRaska/open-bank-oss/pull/1',
    )
  })

  it.each([
    'javascript:alert(document.domain)',
    'data:text/html,<script>alert(1)</script>',
    'http://operator.example/runbook',
    '/relative/path',
    'not a URL',
  ])('rejects unsafe or ambiguous navigation %s', value => {
    expect(safeExternalUrl(value)).toBeNull()
  })
})
