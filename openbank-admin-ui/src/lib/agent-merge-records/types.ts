// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Types + defensive parser for the agent-service recorded-merge DTO
// (GET /api/v1/agent-merge-records, ADR-0031 phase 3). A merge record is the
// durable evidence that a HUMAN merged an agent-authored PR: the merge half of
// the AI-attribution chain next to the proposal decision. Parsing follows the
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
  /** The HUMAN login who pressed merge — the audit actor for this event. */
  mergedBy: string
  mergedAt: string
  title: string
  proposalId: string | null
  recordedAt: string
  /** Ingest origin, e.g. "github-actions" — distinguishes pipeline-fed rows from manual backfills. */
  source: string
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

export function parseAgentMergeRecord(value: unknown): AgentMergeRecord | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if (
    !nonEmpty(record.id) || !isUuid(record.id) ||
    !nonEmpty(record.agentId) ||
    typeof record.prNumber !== 'number' || !Number.isInteger(record.prNumber) || record.prNumber <= 0 ||
    !nonEmpty(record.prUrl) ||
    !nonEmpty(record.headBranch) ||
    !nonEmpty(record.mergedBy) ||
    !instant(record.mergedAt) ||
    !nonEmpty(record.title) ||
    (record.proposalId !== undefined && !nullableUuid(record.proposalId)) ||
    !instant(record.recordedAt) ||
    !nonEmpty(record.source)
  ) return null
  // proposalId is optional upstream (a GitHub webhook knows PR numbers, not proposal ids) —
  // normalise an absent value to null so downstream rendering never sees undefined.
  return { ...record, proposalId: record.proposalId ?? null } as unknown as AgentMergeRecord
}

// A malformed row is dropped, not fatal: this panel is evidence-of-merge, and a
// single bad upstream row must not blank the whole list. Duplicated ids keep the
// first occurrence only.
export function parseAgentMergeRecordList(value: unknown): AgentMergeRecordParseResult {
  if (!Array.isArray(value)) return { records: [], dropped: 0 }
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
