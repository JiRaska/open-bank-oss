// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { trustedHttpsUrl } from '@/lib/security/trustedUrls'

describe('trusted HTTPS destinations', () => {
  it.each([
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'http://documents.example/terms',
    'https://bank.example@attacker.example/terms',
    '//attacker.example/terms',
    'not a URL',
  ])('rejects %s', candidate => {
    expect(trustedHttpsUrl(candidate)).toBeNull()
  })

  it('normalizes an HTTPS document URL while preserving its query and fragment', () => {
    expect(trustedHttpsUrl(' https://documents.example/terms?v=2#fees '))
      .toBe('https://documents.example/terms?v=2#fees')
  })
})
