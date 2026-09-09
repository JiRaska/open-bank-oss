// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { isUuid } from '@/lib/party/resolveParty'
import type { ApprovalDomain, DomainApprovalItem } from '@/lib/approvals/triage'

export interface AgentProposal {
  id: string
  title: string
  rationale: string
  suggestedAction: string
  proposedBy: string
  proposedAt: string
  state: 'PROPOSED' | 'APPROVED' | 'REJECTED'
  decidedBy: string | null
  decidedAt: string | null
  decisionReason: string | null
  modelId: string | null
  agent?: { id: string; displayName: string; icon: 'bot' | 'user'; charterKnown: boolean }
}

export interface ApprovalInboxItem extends Omit<DomainApprovalItem, 'domain'> {
  domain: ApprovalDomain | 'agent'
}

export type ApprovalSourceState = 'ok' | 'forbidden' | 'unavailable' | 'not-configured'

export interface ApprovalInbox {
  items: ApprovalInboxItem[]
  sources: Record<string, ApprovalSourceState>
}

const DOMAINS = [
  'lending', 'sanctions', 'transaction', 'domestic-payment', 'clearing', 'fx', 'ledger', 'swift',
  'sepa-payment', 'sepa-instant', 'notification', 'party', 'account', 'consent', 'balance', 'billing',
  'delegation', 'agent',
] as const
const DOMAIN_SET = new Set<string>(DOMAINS)
const SOURCE_STATES = new Set<ApprovalSourceState>(['ok', 'forbidden', 'unavailable', 'not-configured'])

function nonEmpty(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function instant(value: unknown): value is string {
  return nonEmpty(value) && Number.isFinite(Date.parse(value))
}

function isAgent(value: unknown): value is NonNullable<AgentProposal['agent']> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const agent = value as Record<string, unknown>
  return nonEmpty(agent.id) && nonEmpty(agent.displayName) &&
    (agent.icon === 'bot' || agent.icon === 'user') && typeof agent.charterKnown === 'boolean'
}

function parseAgentProposal(value: unknown): AgentProposal | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const proposal = value as Record<string, unknown>
  if (
    !nonEmpty(proposal.id) || !isUuid(proposal.id) || !nonEmpty(proposal.title) ||
    !nonEmpty(proposal.rationale) || !nonEmpty(proposal.suggestedAction) ||
    !nonEmpty(proposal.proposedBy) || !instant(proposal.proposedAt) ||
    !['PROPOSED', 'APPROVED', 'REJECTED'].includes(String(proposal.state)) ||
    !nullableString(proposal.decidedBy) || !nullableString(proposal.decidedAt) ||
    !nullableString(proposal.decisionReason) || !nullableString(proposal.modelId) ||
    (proposal.decidedAt !== null && !instant(proposal.decidedAt)) ||
    (proposal.agent !== undefined && !isAgent(proposal.agent))
  ) return null
  return proposal as unknown as AgentProposal
}

export function parseAgentProposalList(value: unknown): AgentProposal[] | null {
  if (!Array.isArray(value) || value.length > 100) return null
  const proposals = value.map(parseAgentProposal)
  if (proposals.some(proposal => proposal === null)) return null
  const valid = proposals as AgentProposal[]
  if (new Set(valid.map(proposal => proposal.id)).size !== valid.length) return null
  return valid
}

function parseInboxItem(value: unknown): ApprovalInboxItem | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const item = value as Record<string, unknown>
  if (
    !nonEmpty(item.id) || !nonEmpty(item.domain) || !DOMAIN_SET.has(item.domain) ||
    !nonEmpty(item.action) || !nullableString(item.resourceId) || !nullableString(item.maker) ||
    !nullableString(item.proposedAt) || (item.proposedAt !== null && !instant(item.proposedAt))
  ) return null
  return item as unknown as ApprovalInboxItem
}

export function parseApprovalInbox(value: unknown): ApprovalInbox | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const inbox = value as Record<string, unknown>
  if (!Array.isArray(inbox.items) || typeof inbox.sources !== 'object' || inbox.sources === null || Array.isArray(inbox.sources)) return null
  const sources = inbox.sources as Record<string, unknown>
  if (Object.keys(sources).length !== DOMAINS.length || !DOMAINS.every(domain => SOURCE_STATES.has(sources[domain] as ApprovalSourceState))) return null
  const items = inbox.items.map(parseInboxItem)
  if (items.some(item => item === null)) return null
  const valid = items as ApprovalInboxItem[]
  if (new Set(valid.map(item => `${item.domain}:${item.id}`)).size !== valid.length) return null
  return { items: valid, sources: sources as Record<string, ApprovalSourceState> }
}
