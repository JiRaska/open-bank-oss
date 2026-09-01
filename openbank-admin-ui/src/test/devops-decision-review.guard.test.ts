// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('DevOps HITL decision review', () => {
  it('reviews the exact finding before preserving the existing decision payload', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/devops/page.tsx'), 'utf8')
    expect(source).toContain('role="alertdialog"')
    expect(source).toContain('pendingDecision.finding.proposedRemediation')
    expect(source).toContain('pendingDecision.finding.proposalPrUrl')
    expect(source).toContain("body: JSON.stringify({ id, action })")
    expect(source).toContain("setPendingDecision({ finding, action: 'approve' })")
    expect(source).not.toContain("onApprove={canDecide ? id => decide(id, 'approve')")
  })
})
