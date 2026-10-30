// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * ADR-0352 phase 5b: the approval panel's view of a behavioural diff. The document is computed by the
 * catalog with the shared ProductBehaviourDiff (the engine `obp diff --behavioural` prints); this module
 * only reads it. Nothing here replays, recomputes or re-rounds a number — every amount is shown as the
 * server's fixed-scale string.
 */

export const BEHAVIOUR_DIFF_SCHEMA = 'openbank.product-behaviour-diff.v1'

type Triple = { a: string; b: string; delta: string }
type Movement = { debit: string; credit: string; net: string }

export type BehaviourDiffDocument = {
  schema: string
  revisionA: { productCode: string; revisionHash: string; irVersion: number }
  revisionB: { productCode: string; revisionHash: string; irVersion: number }
  range: { from: string; to: string; zone: string; days: number }
  portfolio: { accounts: number; seed: string | null }
  materialityThreshold: string
  valid: boolean
  identical: boolean
  changedAccounts: number
  aggregate: {
    outcome: Triple
    posted: Array<{ ruleId: string; accountingClass: string; a: Movement; b: Movement; deltaNet: string }>
    instructionCounts: Array<{ ruleId: string; kind: string; a: number; b: number; delta: number }>
  }
  signChanged: string[]
  thresholdCrossed: string[]
}

export type BehaviourDiffVerdict =
  | { kind: 'unknown-schema'; schema: unknown }
  | { kind: 'invalid' }
  | { kind: 'identical'; diff: BehaviourDiffDocument }
  | { kind: 'changed'; diff: BehaviourDiffDocument }

/**
 * How the panel must present a document. An unknown schema or `valid: false` is never rendered as a
 * result: the first is a contract mismatch, the second an interpreter defect (ADR-0352 §8), and an
 * approver must not read either as "this change moves nothing".
 */
export function behaviourDiffVerdict(diff: unknown): BehaviourDiffVerdict {
  const doc = diff as Partial<BehaviourDiffDocument> | null
  if (!doc || doc.schema !== BEHAVIOUR_DIFF_SCHEMA) return { kind: 'unknown-schema', schema: doc?.schema }
  if (doc.valid !== true) return { kind: 'invalid' }
  const full = doc as BehaviourDiffDocument
  return full.identical ? { kind: 'identical', diff: full } : { kind: 'changed', diff: full }
}

/** A small editable starting portfolio: one account below and one above a typical waiver threshold. */
export function samplePortfolio(currency: string, openedOn: string): string {
  const account = (id: string, balance: string, postings: unknown[]) => ({ id, state: { currency, balance, openedOn }, postings })
  return JSON.stringify({
    accounts: [
      account('sample-low', '500.00', []),
      account('sample-high', '20000.00', [{ on: openedOn, kind: 'DEPOSIT', direction: 'CREDIT', amount: '100.00' }]),
    ],
  }, null, 2)
}
