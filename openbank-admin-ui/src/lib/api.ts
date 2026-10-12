// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Account, AccountBalance, CursorPage, Transaction, JournalEntry, ServiceInfo, ServiceHealth, ServiceSnapshot, ServiceConfigResponse, ServiceConfigSnapshot } from '@/types'
import type { GovernanceManifestEntry } from '@/lib/governance/manifest'
import { classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { buildRegistry, type CatalogFleetModule } from '@/lib/services/registry'

const ACCOUNT_SERVICE = '/api/svc/account-service'
const TRANSACTION_SERVICE = '/api/svc/transaction-service'
const NOTIFICATION_SERVICE = '/api/svc/notification-service'

// Values supplied by routes, deep links, and operator input must remain one path segment.
// Encoding at this boundary prevents a value such as "../../other-endpoint" from changing
// which same-origin API operation the browser calls.
const pathSegment = (value: string): string => encodeURIComponent(value)

/**
 * The fleet to list when the live snapshot endpoint is unavailable. DERIVED from the code-generated
 * catalog (the same artifact every other service view reads) - never a list typed into this file.
 * An absent catalog yields an empty list: the caller degrades honestly rather than invent services.
 */
async function fleetFromCatalog(): Promise<{ name: string; port: number }[]> {
  try {
    const res = await fetch('/api/catalog/services', { cache: 'no-store' })
    if (!res.ok) return []
    const body = await res.json() as { services?: CatalogFleetModule[] }
    return buildRegistry(body.services ?? []).map(s => ({ name: s.container.replace(/^openbank-/, ''), port: s.port }))
  } catch {
    return []
  }
}

export async function fetchAllServiceConfigSnapshots(): Promise<ServiceConfigSnapshot[]> {
  const res = await fetch('/api/services/config', { cache: 'no-store' })
  if (!res.ok) throw new Error(`config fetch failed: ${res.status}`)
  return res.json()
}

async function apiFetch<T>(url: string, options?: RequestInit): Promise<{ data: T; headers: Headers }> {
  const res = await fetch(url, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options?.headers },
  })
  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(error.message || `HTTP ${res.status}`)
  }
  const data = await res.json()
  return { data, headers: res.headers }
}

async function apiFetchSimple<T>(url: string, options?: RequestInit): Promise<T> {
  const { data } = await apiFetch<T>(url, options)
  return data
}

export async function fetchAllGovernanceManifests(): Promise<{ byService: Record<string, GovernanceManifestEntry>, timestamp: string }> {
  const res = await fetch('/api/services/governance', { cache: 'no-store' })
  if (!res.ok) throw new Error(`governance fetch failed: ${res.status}`)
  return res.json()
}

export const accountApi = {
  list: (partyId: string, cursor?: string) => {
    const params = new URLSearchParams({ partyId })
    if (cursor) params.set('cursor', cursor)
    return apiFetchSimple<CursorPage<Account>>(`${ACCOUNT_SERVICE}/api/v1/accounts?${params}`)
  },
  get: (id: string) => apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${pathSegment(id)}`),
  getBalance: (id: string) => apiFetchSimple<AccountBalance>(`${ACCOUNT_SERVICE}/api/v1/accounts/${pathSegment(id)}/balance`),
  getByIban: (iban: string) => apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/iban/${pathSegment(iban)}`),
  open: (data: { partyId: string; productId: string; accountType: string; currencyCode: string; legalName: string; termsVersion?: string; termsUrl?: string; termsEffectiveFrom?: string }, idempotencyKey: string) =>
    apiFetchSimple<unknown>(`${ACCOUNT_SERVICE}/api/v1/accounts`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(data),
    }),
  close: (id: string, reason?: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${pathSegment(id)}/close`, { method: 'POST', body: JSON.stringify({ reason }) }),
  freeze: (id: string, reason: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${pathSegment(id)}/freeze`, { method: 'POST', body: JSON.stringify({ reason }) }),
  unfreeze: (id: string, reason: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${pathSegment(id)}/unfreeze`, { method: 'POST', body: JSON.stringify({ reason }) }),
}

// Operator-initiated customer messaging (ADR-0176 D2/D5). Mirrors the SHIPPED
// notification-service contract exactly (OperatorMessageResource + ApprovalResource +
// openapi.yaml), not the draft/submit two-call sketch the ADR record used:
//   - compose is a SINGLE call — POST /api/v1/notifications/messages — that both persists
//     and sends. It is itself the four-eyes-gated action (`@Authorize("opsmessage.compose",
//     resource="#request")`); AuthorizeInterceptor pauses it with 202 when four-eyes
//     enforcement is on and lets the maker replay the byte-identical body once approved.
//   - the checker decides via a SINGLE PATCH /api/v1/notifications/approvals/{id} {approve},
//     not separate approve/reject verbs.
export type OperatorMessageTemplate = 'GENERIC_NOTICE' | 'SUPPORT_FOLLOWUP'

// Each template's EXACT required variable keys (notification-service OperatorMessageTemplate).
// The compose request must carry exactly these keys — extra AND missing are both rejected 400.
export const OPERATOR_MESSAGE_TEMPLATE_VARS: Record<OperatorMessageTemplate, readonly string[]> = {
  GENERIC_NOTICE: ['subject', 'note'],
  SUPPORT_FOLLOWUP: ['ticketReference'],
}

export interface ComposeMessageRequest {
  partyId: string
  template: OperatorMessageTemplate
  recipient: string
  variables: Record<string, string>
}

// compose resolves to one of two backend shapes: 201 ComposeMessageResponse ({id}) when the
// message was sent, or the AuthorizeInterceptor 202 body ({status, approvalId}) when four-eyes
// enforcement paused it pending a second operator. Callers branch on `status`.
export type ComposeResult =
  | { status: 'SENT'; id: string }
  | { status: 'PENDING_APPROVAL'; approvalId: string }

// PATCH /approvals/{id} -> ApprovalResponse.
export interface ApprovalDecision {
  id: string; action: string; resourceId: string | null; status: string; decidedBy: string | null
}

export const opsMessageApi = {
  // 201 -> {status:'SENT', id}. 202 (four-eyes on) -> {status:'PENDING_APPROVAL', approvalId}:
  // relay that id to a DIFFERENT operator to decide, then replay this exact request (same body)
  // with `approvalId` set — the interceptor binds the approval to the request's content, so the
  // retry must be byte-identical or it mints a fresh pending approval.
  compose: async (req: ComposeMessageRequest, approvalId?: string): Promise<ComposeResult> => {
    const res = await fetch(`${NOTIFICATION_SERVICE}/api/v1/notifications/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(approvalId ? { 'X-Approval-Id': approvalId } : {}) },
      body: JSON.stringify(req),
    })
    if (res.status === 202) {
      const body = await res.json().catch(() => ({}))
      return { status: 'PENDING_APPROVAL', approvalId: body.approvalId }
    }
    if (!res.ok) {
      const error = await res.json().catch(() => ({ message: res.statusText }))
      throw new Error(error.message || `HTTP ${res.status}`)
    }
    const body = await res.json()
    return { status: 'SENT', id: body.id }
  },
  // Checker decision (ApprovalResource.decide): one PATCH with an approve boolean. Self-approval
  // is refused server-side (403); an unknown/already-decided id is 404/409 — all thrown here.
  decide: (id: string, approve: boolean) =>
    apiFetchSimple<ApprovalDecision>(`${NOTIFICATION_SERVICE}/api/v1/notifications/approvals/${pathSegment(id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ approve }),
    }),
}

const LEDGER_SERVICE = '/api/svc/ledger-service'

export const ledgerApi = {
  list: (fromDate?: string, toDate?: string, cursor?: string) => {
    const params = new URLSearchParams()
    if (fromDate) params.set('fromDate', fromDate)
    if (toDate) params.set('toDate', toDate)
    if (cursor) params.set('cursor', cursor)
    return apiFetchSimple<CursorPage<JournalEntry>>(`${LEDGER_SERVICE}/api/v1/journals?${params}`)
  },
  get: (id: string) => apiFetchSimple<JournalEntry>(`${LEDGER_SERVICE}/api/v1/journals/${pathSegment(id)}`),
  getByTransaction: (transactionId: string) =>
    apiFetchSimple<JournalEntry[]>(`${LEDGER_SERVICE}/api/v1/journals/transaction/${pathSegment(transactionId)}`),
}

export const transactionApi = {
  list: (accountId: string, cursor?: string) => {
    const params = new URLSearchParams({ accountId })
    if (cursor) params.set('cursor', cursor)
    return apiFetchSimple<CursorPage<Transaction>>(`${TRANSACTION_SERVICE}/api/v1/transactions?${params}`)
  },
  get: (id: string) => apiFetchSimple<Transaction>(`${TRANSACTION_SERVICE}/api/v1/transactions/${pathSegment(id)}`),
}

import type { ServiceHealthEntry } from '@/app/api/services/health/route'

export type { ServiceHealthEntry }

export async function fetchServiceSnapshot(name: string, port: number): Promise<ServiceSnapshot> {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), 10000)
  try {
    const res = await fetch('/api/services/health', { cache: 'no-store', signal: controller.signal })
    clearTimeout(timeoutId)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data: { services: ServiceHealthEntry[] } = await res.json()
    const entry = data.services.find(s => s.name === name && s.port === port)
    if (!entry) return { name, port, info: null, health: null, rateLimitMax: null, rateLimitRemaining: null, apiVersion: null, latencyMs: null, reachable: false }
    const hasInfoSignal = Boolean(entry.version || entry.gitCommit || entry.stack)
    return {
      name: entry.name,
      port: entry.port,
      info: hasInfoSignal ? {
        service: entry.name,
        version: entry.version,
        apiVersion: null,
        buildTime: null,
        gitCommit: entry.gitCommit,
        timestamp: null,
        status: entry.status,
        stack: entry.stack ?? null,
      } : null,
      health: entry.status !== 'UNKNOWN' ? { status: entry.status === 'UP' ? 'UP' : 'DOWN', checks: [] } : null,
      rateLimitMax: null,
      rateLimitRemaining: null,
      apiVersion: null,
      latencyMs: entry.latencyMs,
      reachable: entry.reachable,
    }
  } catch {
    return { name, port, info: null, health: null, rateLimitMax: null, rateLimitRemaining: null, apiVersion: null, latencyMs: null, reachable: false }
  }
}

export async function fetchAllServiceSnapshots(): Promise<ServiceSnapshot[]> {
  const evidence = await fetchAllServiceSnapshotsEvidence()
  return evidence.ok
    ? evidence.snapshots
    : (await fleetFromCatalog()).map(s => ({ name: s.name, port: s.port, info: null, health: null, rateLimitMax: null, rateLimitRemaining: null, apiVersion: null, latencyMs: null, reachable: false }))
}

export type ServiceSnapshotsEvidence =
  | { ok: true; snapshots: ServiceSnapshot[] }
  | { ok: false; failure: BffFailure }

function hasVersion(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  return typeof (value as { version?: unknown }).version === 'string' && (value as { version: string }).version.length > 0
}

function isStack(value: unknown): boolean {
  if (value == null) return true
  if (typeof value !== 'object' || Array.isArray(value)) return false
  const stack = value as Record<string, unknown>
  return ['kotlin', 'quarkus', 'java', 'gradle', 'libs'].every(key => stack[key] === undefined || hasVersion(stack[key]))
}

function isHealthEntry(value: unknown): value is ServiceHealthEntry {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const entry = value as Record<string, unknown>
  return typeof entry.name === 'string' && entry.name.length > 0 &&
    typeof entry.port === 'number' && Number.isInteger(entry.port) && entry.port > 0 &&
    (entry.status === 'UP' || entry.status === 'DOWN' || entry.status === 'UNKNOWN') &&
    typeof entry.reachable === 'boolean' &&
    (entry.latencyMs === null || (typeof entry.latencyMs === 'number' && Number.isFinite(entry.latencyMs))) &&
    (entry.version == null || typeof entry.version === 'string') &&
    (entry.gitCommit == null || typeof entry.gitCommit === 'string') &&
    isStack(entry.stack)
}

function snapshotFromHealthEntry(entry: ServiceHealthEntry): ServiceSnapshot {
  const hasInfoSignal = Boolean(entry.version || entry.gitCommit || entry.stack)
  return {
    name: entry.name,
    port: entry.port,
    info: hasInfoSignal ? {
      service: entry.name,
      version: entry.version,
      apiVersion: null,
      buildTime: null,
      gitCommit: entry.gitCommit,
      timestamp: null,
      status: entry.status,
      stack: entry.stack ?? null,
    } : null,
    health: entry.status !== 'UNKNOWN' ? { status: entry.status === 'UP' ? 'UP' : 'DOWN', checks: [] } : null,
    rateLimitMax: null,
    rateLimitRemaining: null,
    apiVersion: null,
    latencyMs: entry.latencyMs,
    reachable: entry.reachable,
  }
}

/** Evidence-preserving variant for screens that must distinguish a failed collector from an all-down fleet. */
export async function fetchAllServiceSnapshotsEvidence(): Promise<ServiceSnapshotsEvidence> {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), 10000)
  try {
    const res = await fetch('/api/services/health', { cache: 'no-store', signal: controller.signal })
    if (!res.ok) return { ok: false, failure: await classifyBffFailure(res) }
    const data = await res.json().catch(() => null)
    if (typeof data !== 'object' || data === null || !Array.isArray((data as { services?: unknown }).services)) {
      return { ok: false, failure: 'error' }
    }
    const services = (data as { services: unknown[] }).services
    if (!services.every(isHealthEntry)) return { ok: false, failure: 'error' }
    if (new Set(services.map(entry => `${entry.name}:${entry.port}`)).size !== services.length) {
      return { ok: false, failure: 'error' }
    }
    return { ok: true, snapshots: services.map(snapshotFromHealthEntry) }
  } catch {
    return { ok: false, failure: 'unreachable' }
  } finally {
    clearTimeout(timeoutId)
  }
}
