// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Types + defensive parser for the agent-service recorded-merge DTO
// (GET /api/v1/agent-merge-records, ADR-0031 phase 3). A merge record is the
// durable evidence of the actual executor and separate human authorization.
// Parsing follows the
// same discipline as src/lib/approvals/evidence.ts — an untrusted upstream row
// that does not match the contract is DROPPED, never surfaced half-valid, and
// must never crash the page that renders it.

import { isUuid } from '@/lib/party/resolveParty'

export interface AgentMergeRecord {
  id: string
  agentId: string
  prNumber: number
  prUrl: string
  headBranch: string
  /** Actual executor; the approving human is never substituted here. */
  mergedBy: string
  mergedByType?: 'HUMAN' | 'GITHUB_APP'
  mergedById?: number | null
  headSha?: string | null
  mergeCommitSha?: string | null
  authorization?: MergeAuthorization | null
  policyAuthorization?: PolicyMergeAuthorization | null
  mergedAt: string
  title: string
  proposalId: string | null
  recordedAt: string
  /** Ingest origin, e.g. "github-actions" — distinguishes pipeline-fed rows from manual backfills. */
  source: string
}

export interface MergeAuthorization {
  kind: 'OWNER_WORKFLOW_EXACT_HEAD' | 'LEGACY_PR_AUTO_MERGE'
  scope: 'HEAD' | 'PR'
  approvedBy: string
  approvedById: number
  approvedAt: string
  evidenceUrl: string
  statusId?: number | null
  workflowRunId?: number | null
  pullRequestNodeId?: string | null
  mergeEventNodeId?: string | null
  mergeEventAt?: string | null
  observedAt?: string | null
}

export interface PolicyMergeAuthorization extends Omit<MergeAuthorization, 'kind' | 'scope'> {
  kind: 'POLICY_PR_AUTO_MERGE'
  scope: 'PR'
  policyCommitSha: string
  policyBlobSha: string
}

export interface AgentMergeRecordParseResult {
  records: AgentMergeRecord[]
  /** How many upstream rows failed validation and were dropped. */
  dropped: number
}

function nonEmpty(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function instant(value: unknown): value is string {
  return nonEmpty(value) && Number.isFinite(Date.parse(value))
}

function nullableUuid(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && isUuid(value))
}

function positiveId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function appEvidence(record: Record<string, unknown>, repo: string): boolean {
  if (record.mergedBy !== 'openbank-gitops-bot[bot]' || !positiveId(record.mergedById) ||
    typeof record.headSha !== 'string' || !/^[0-9a-f]{40}$/.test(record.headSha) ||
    typeof record.mergeCommitSha !== 'string' || !/^[0-9a-f]{40}$/.test(record.mergeCommitSha) ||
    (record.authorization != null && record.policyAuthorization != null)) return false
  const raw = record.policyAuthorization ?? record.authorization
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return false
  const proof = raw as Record<string, unknown>
  const policy = record.policyAuthorization != null
  if (policy && (proof.kind !== 'POLICY_PR_AUTO_MERGE' ||
    typeof proof.policyCommitSha !== 'string' || !/^[0-9a-f]{40}$/.test(proof.policyCommitSha) ||
    typeof proof.policyBlobSha !== 'string' || !/^[0-9a-f]{40}$/.test(proof.policyBlobSha) ||
    (record.headBranch as string).startsWith('codex/'))) return false
  if (!policy && (proof.policyCommitSha != null || proof.policyBlobSha != null)) return false
  if (typeof proof.approvedBy !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/.test(proof.approvedBy) ||
    !positiveId(proof.approvedById) || !instant(proof.approvedAt) ||
    Date.parse(proof.approvedAt) > Date.parse(record.mergedAt as string)) return false
  if (proof.kind === 'OWNER_WORKFLOW_EXACT_HEAD' && proof.scope === 'HEAD') {
    return positiveId(proof.statusId) && positiveId(proof.workflowRunId) &&
      proof.evidenceUrl === `https://github.com/${repo}/actions/runs/${proof.workflowRunId}` &&
      [proof.pullRequestNodeId, proof.mergeEventNodeId, proof.mergeEventAt, proof.observedAt].every(v => v == null)
  }
  if ((!policy && proof.kind !== 'LEGACY_PR_AUTO_MERGE') || proof.scope !== 'PR' ||
    (!policy && Date.parse(record.mergedAt as string) > Date.parse('2026-10-23T19:42:17Z')) ||
    proof.evidenceUrl !== record.prUrl || proof.statusId != null || proof.workflowRunId != null ||
    !nonEmpty(proof.pullRequestNodeId) || !nonEmpty(proof.mergeEventNodeId) ||
    !instant(proof.mergeEventAt) || !instant(proof.observedAt)) return false
  const mergedAt = Date.parse(record.mergedAt as string)
  const eventAt = Date.parse(proof.mergeEventAt)
  const observedAt = Date.parse(proof.observedAt)
  return Date.parse(proof.approvedAt) <= eventAt && Math.abs(mergedAt - eventAt) <= 60_000 &&
    observedAt >= Math.max(mergedAt, eventAt) && observedAt <= Date.parse(record.recordedAt as string) + 60_000
}

export function parseAgentMergeRecord(value: unknown): AgentMergeRecord | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if (
    !nonEmpty(record.id) || !isUuid(record.id) ||
    !nonEmpty(record.agentId) ||
    !positiveId(record.prNumber) ||
    !nonEmpty(record.prUrl) ||
    !nonEmpty(record.headBranch) ||
    !nonEmpty(record.mergedBy) ||
    !instant(record.mergedAt) ||
    !nonEmpty(record.title) ||
    (record.proposalId !== undefined && !nullableUuid(record.proposalId)) ||
    !instant(record.recordedAt) ||
    !nonEmpty(record.source)
  ) return null
  const pr = /^https:\/\/github\.com\/([A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+)\/pull\/([1-9][0-9]*)$/.exec(record.prUrl as string)
  if (!pr || pr[2] !== String(record.prNumber)) return null
  const actor = record.mergedByType ?? 'HUMAN'
  if (actor === 'GITHUB_APP') {
    if (!appEvidence(record, pr[1])) return null
  } else if (actor !== 'HUMAN' || record.mergedByType === null || record.authorization != null || record.policyAuthorization != null ||
    !/^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/.test(record.mergedBy as string)) return null
  // proposalId is optional upstream (a GitHub webhook knows PR numbers, not proposal ids) —
  // normalise an absent value to null so downstream rendering never sees undefined.
  return { ...record, proposalId: record.proposalId ?? null } as unknown as AgentMergeRecord
}

// A malformed row is dropped, not fatal: this panel is evidence-of-merge, and a
// single bad upstream row must not blank the whole list. Duplicated ids keep the
// first occurrence only.
export function parseAgentMergeRecordList(value: unknown): AgentMergeRecordParseResult {
  if (!Array.isArray(value)) return { records: [], dropped: 1 }
  const seen = new Set<string>()
  const records: AgentMergeRecord[] = []
  let dropped = 0
  for (const raw of value) {
    const record = parseAgentMergeRecord(raw)
    if (record === null || seen.has(record.id)) { dropped += 1; continue }
    seen.add(record.id)
    records.push(record)
  }
  return { records, dropped }
}
