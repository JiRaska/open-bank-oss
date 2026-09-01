// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')

describe('payment creation single-flight contract', () => {
  it('guards both money-path submit handlers and exposes truthful progress', () => {
    expect(page.match(/claimSingleFlight\(createInFlight\)/g)).toHaveLength(2)
    expect(page.match(/releaseSingleFlight\(createInFlight\)/g)).toHaveLength(2)
    expect(page.match(/aria-busy=\{creating\}/g)).toHaveLength(2)
    expect(page).toContain('idempotencyKeyForPayload(domesticAttempt, body')
    expect(page).toContain('idempotencyKeyForPayload(sepaAttempt, body')
    expect(page).not.toContain("'Idempotency-Key': crypto.randomUUID()")
  })
})
