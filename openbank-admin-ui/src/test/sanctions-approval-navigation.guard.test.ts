// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const approvals = readFileSync(resolve(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')
const sanctions = readFileSync(resolve(process.cwd(), 'src/app/sanctions/page.tsx'), 'utf8')

describe('sanctions approval navigation', () => {
  it('deep-links a discovered sanctions approval into its governed decision workspace', () => {
    expect(approvals).toContain("item.domain === 'sanctions'")
    expect(approvals).toContain('/sanctions?approvalId=')
    expect(sanctions).toContain("searchParams.get('approvalId')")
    expect(sanctions).toContain('id="sanctions-approvals"')
  })

  it('describes the real discoverable queue instead of out-of-band handover', () => {
    expect(sanctions).toContain('automatically appears for another authorised operator')
    expect(sanctions).not.toContain('Hand this id to another operator')
    expect(sanctions).not.toContain('exposes no pending-approvals list endpoint')
  })
})
