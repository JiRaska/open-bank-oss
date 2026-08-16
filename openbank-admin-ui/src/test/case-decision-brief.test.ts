// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { deriveCaseDecisionBrief } from '@/lib/governance/caseDecisionBrief'

describe('case decision brief', () => {
  it('reports only a proposal event after the coordinator emitted one', () => {
    const brief = deriveCaseDecisionBrief({
      status: 'SYNTHESIZED',
      entries: [
        { type: 'CONTRIBUTION', actor: 'aml-agent', evidenceRefs: ['alert-17', 'policy-9'] },
        { type: 'CONTRIBUTION', actor: 'fraud-agent', evidenceRefs: ['alert-17'], superseded: true },
        { type: 'PROPOSAL_EMITTED' },
      ],
    })

    expect(brief).toEqual({
      stage: 'proposal_recorded',
      contributorCount: 1,
      evidenceRefCount: 2,
      contestedContributionCount: 0,
    })
  })

  it('keeps dissent visible when the case still needs convergence', () => {
    const brief = deriveCaseDecisionBrief({
      status: 'CONTESTED',
      entries: [
        { type: 'CONTRIBUTION', actor: 'aml-agent', evidenceRefs: ['alert-17'], contested: true },
        { type: 'CONTRIBUTION', actor: 'fraud-agent', evidenceRefs: ['trace-4'] },
      ],
    })

    expect(brief).toEqual({
      stage: 'needs_convergence',
      contributorCount: 2,
      evidenceRefCount: 2,
      contestedContributionCount: 1,
    })
  })

  it('does not imply evidence exists before any specialist contributes', () => {
    expect(deriveCaseDecisionBrief({ status: 'OPEN', entries: [{ type: 'CASE_OPENED' }] })).toEqual({
      stage: 'gathering_evidence',
      contributorCount: 0,
      evidenceRefCount: 0,
      contestedContributionCount: 0,
    })
  })
})
