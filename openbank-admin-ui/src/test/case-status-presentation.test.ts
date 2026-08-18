// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { CASE_STATUSES, caseStatusPresentation } from '@/lib/governance/caseStatusPresentation'

describe('case status presentation', () => {
  it('turns every technical case status into a business-readable stage', () => {
    expect(CASE_STATUSES.map(status => caseStatusPresentation(status, 'en').label)).toEqual([
      'Gathering inputs',
      'Comparing inputs',
      'Dissent remains visible',
      'A proposal is in the thread',
      'The thread is closed',
    ])
  })

  it('keeps a synthesized case truthful about what the thread does not establish', () => {
    const presentation = caseStatusPresentation('SYNTHESIZED', 'en')

    expect(presentation.detail).toContain('delivery and the human decision are not shown')
    expect(presentation.detail).not.toContain('approved')
  })
})
