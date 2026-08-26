// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const approvals = readFileSync(resolve(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')
const notifications = readFileSync(resolve(process.cwd(), 'src/app/notifications/page.tsx'), 'utf8')
const partyDetail = readFileSync(resolve(process.cwd(), 'src/app/parties/[id]/page.tsx'), 'utf8')

describe('notification approval navigation', () => {
  it('deep-links a discovered notification approval into its decision workspace', () => {
    expect(approvals).toContain("item.domain === 'notification'")
    expect(approvals).toContain('/notifications?approvalId=')
    expect(notifications).toContain("get('approvalId')")
    expect(notifications).toContain('id="message-approvals"')
  })

  it('teaches makers that the queue is discoverable instead of requiring out-of-band ID relay', () => {
    expect(partyDetail).toContain('automaticky zobrazí jinému operátorovi v Centru schvalování')
    expect(partyDetail).toContain('automatically appears for another operator in the Approval Centre')
    expect(partyDetail).not.toContain('Give this approval id to a second operator')
    expect(notifications).not.toContain('there is no list endpoint')
  })
})
