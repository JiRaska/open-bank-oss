// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export type CaseStatus = 'OPEN' | 'CONVERGING' | 'CONTESTED' | 'SYNTHESIZED' | 'CLOSED'
export type CaseEntryType = 'CASE_OPENED' | 'CONTRIBUTION' | 'PROPOSAL_EMITTED' | 'SHADOW_RECORDED' | 'POLICY_DECISION' | 'SIGNAL_INVOKED' | 'SIGNAL_CONSUMED' | 'CONTRIBUTION_PERSISTED'

export interface CaseThreadEntry {
  type: CaseEntryType
  actor?: string
  evidenceRefs?: string[]
  superseded?: boolean
  contested?: boolean
}

export interface CaseThreadForBrief {
  status: CaseStatus
  entries: CaseThreadEntry[]
}

export type DecisionBriefStage = 'proposal_recorded' | 'shadow_recorded' | 'needs_convergence' | 'gathering_evidence'

export interface CaseDecisionBrief {
  stage: DecisionBriefStage
  contributorCount: number
  evidenceRefCount: number
  contestedContributionCount: number
}

// This deliberately derives only from the read-only thread returned by the
// coordinator. It is a plain-language summary, never a new decision signal.
export function deriveCaseDecisionBrief(thread: CaseThreadForBrief): CaseDecisionBrief {
  const activeContributions = thread.entries.filter(entry => entry.type === 'CONTRIBUTION' && !entry.superseded)
  const contributorCount = new Set(activeContributions.map(entry => entry.actor).filter((actor): actor is string => Boolean(actor))).size
  const evidenceRefCount = new Set(activeContributions.flatMap(entry => entry.evidenceRefs ?? [])).size
  const contestedContributionCount = activeContributions.filter(entry => entry.contested).length
  const proposalEmitted = thread.entries.some(entry => entry.type === 'PROPOSAL_EMITTED')
  const shadowRecorded = thread.entries.some(entry => entry.type === 'SHADOW_RECORDED')

  return {
    stage: proposalEmitted
      ? 'proposal_recorded'
      : shadowRecorded
        ? 'shadow_recorded'
        : thread.status === 'CONTESTED'
          ? 'needs_convergence'
          : 'gathering_evidence',
    contributorCount,
    evidenceRefCount,
    contestedContributionCount,
  }
}
