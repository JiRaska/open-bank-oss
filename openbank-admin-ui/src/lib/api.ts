// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Account, AccountBalance, CursorPage, Transaction, JournalEntry, ServiceInfo, ServiceHealth, ServiceSnapshot, ServiceConfigResponse, ServiceConfigSnapshot } from '@/types'
import type { GovernanceManifestEntry } from '@/lib/governance/manifest'

const ACCOUNT_SERVICE = '/api/svc/account-service'
const TRANSACTION_SERVICE = '/api/svc/transaction-service'
const NOTIFICATION_SERVICE = '/api/svc/notification-service'

export const SERVICES: { name: string; port: number }[] = [
  { name: 'account-service',        port: 8100 },
  { name: 'ledger-service',         port: 8101 },
  { name: 'transaction-service',    port: 8102 },
  { name: 'balance-service',        port: 8103 },
  { name: 'product-catalog',        port: 8104 },
  { name: 'pid-service',            port: 8105 },
  { name: 'consent-service',        port: 8106 },
  { name: 'psd2-service',           port: 8107 },
  { name: 'tpp-registry-service',   port: 8108 },
  { name: 'sca-service',            port: 8110 },
  { name: 'party-service',          port: 8111 },
  { name: 'notification-service',   port: 8112 },
  { name: 'audit-service',          port: 8113 },
  { name: 'kyc-service',            port: 8114 },
  { name: 'sepa-payment',           port: 8115 },
  { name: 'domestic-payment',       port: 8116 },
  { name: 'aml-service',            port: 8117 },
  { name: 'card-issuance-service',  port: 8118 },
  { name: 'fx-service',             port: 8119 },
  { name: 'security-scanner',       port: 8120 },
  { name: 'standing-order-service', port: 8121 },
  { name: 'swift-service',          port: 8122 },
  { name: 'sanctions-service',      port: 8123 },
  { name: 'clearing-service',       port: 8124 },
  { name: 'interest-service',       port: 8125 },
  { name: 'dispute-service',        port: 8135 },
  { name: 'sepa-instant',           port: 8127 },
  { name: 'vop-service',            port: 8149 },
  { name: 'agent-service',          port: 8109 },
  // Extended / reporting — were missing from the probe list before ADR-0071.
  { name: 'lending-service',        port: 8128 },
  { name: 'statement-service',      port: 8136 },
  { name: 'onboarding-service',     port: 8130 },
  { name: 'anacredit-service',      port: 8137 },
  { name: 'sdd-service',            port: 8132 },
]

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
  list: (partyId: string, cursor?: string) =>
    apiFetchSimple<CursorPage<Account>>(
      `${ACCOUNT_SERVICE}/api/v1/accounts?partyId=${partyId}${cursor ? `&cursor=${cursor}` : ''}`
    ),
  get: (id: string) => apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${id}`),
  getBalance: (id: string) => apiFetchSimple<AccountBalance>(`${ACCOUNT_SERVICE}/api/v1/accounts/${id}/balance`),
  getByIban: (iban: string) => apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/iban/${iban}`),
  open: (data: { partyId: string; productId: string; accountType: string; currencyCode: string; legalName: string }, idempotencyKey: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(data),
    }),
  close: (id: string, reason?: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${id}/close`, { method: 'POST', body: JSON.stringify({ reason }) }),
  freeze: (id: string, reason: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${id}/freeze`, { method: 'POST', body: JSON.stringify({ reason }) }),
  unfreeze: (id: string, reason: string) =>
    apiFetchSimple<Account>(`${ACCOUNT_SERVICE}/api/v1/accounts/${id}/unfreeze`, { method: 'POST', body: JSON.stringify({ reason }) }),
}

// Operator-initiated customer messaging (ADR-0176). draft/submit is a two-call maker
// step — see openbank-notification-service's OperatorMessageResource KDoc for why a single
// annotated endpoint cannot both create the row and be the four-eyes-gated one.
export interface OperatorMessage {
  id: string; partyId: string; template: string; referenceId: string; purpose: string; status: string
}
// The 202-pending shape AuthorizeInterceptor returns on submit's first (un-approved) call.
// Shares `status` with OperatorMessage so callers can branch on one field regardless of which
// shape actually came back.
export interface OpsMessageSubmitResult {
  status: string; approvalId?: string; id?: string
}

export const opsMessageApi = {
  draft: (data: { partyId: string; template: string; referenceId: string; purpose: string }) =>
    apiFetchSimple<OperatorMessage>(`${NOTIFICATION_SERVICE}/api/v1/opsmessages`, {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  // No approvalId yet -> first call, expect back {status:"PENDING_APPROVAL", approvalId}.
  // With approvalId (the maker's retry, once a different operator has approved) -> expect back
  // the sent OperatorMessage, status "SENT".
  submit: (id: string, approvalId?: string) =>
    apiFetchSimple<OpsMessageSubmitResult>(`${NOTIFICATION_SERVICE}/api/v1/opsmessages/${id}/submit`, {
      method: 'POST',
      headers: approvalId ? { 'X-Approval-Id': approvalId } : undefined,
    }),
  listPending: (page = 0, size = 20) =>
    apiFetchSimple<{ items: OperatorMessage[]; total: number }>(
      `${NOTIFICATION_SERVICE}/api/v1/opsmessages?page=${page}&size=${size}`,
    ),
  approve: (id: string) =>
    apiFetchSimple<{ id: string; status: string }>(
      `${NOTIFICATION_SERVICE}/api/v1/opsmessages/approvals/${id}/approve`,
      { method: 'POST' },
    ),
  reject: (id: string) =>
    apiFetchSimple<{ id: string; status: string }>(
      `${NOTIFICATION_SERVICE}/api/v1/opsmessages/approvals/${id}/reject`,
      { method: 'POST' },
    ),
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
  get: (id: string) => apiFetchSimple<JournalEntry>(`${LEDGER_SERVICE}/api/v1/journals/${id}`),
  getByTransaction: (transactionId: string) =>
    apiFetchSimple<JournalEntry[]>(`${LEDGER_SERVICE}/api/v1/journals/transaction/${transactionId}`),
}

export const transactionApi = {
  list: (accountId: string, cursor?: string) =>
    apiFetchSimple<CursorPage<Transaction>>(
      `${TRANSACTION_SERVICE}/api/v1/transactions?accountId=${accountId}${cursor ? `&cursor=${cursor}` : ''}`
    ),
  get: (id: string) => apiFetchSimple<Transaction>(`${TRANSACTION_SERVICE}/api/v1/transactions/${id}`),
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
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), 10000)
  try {
    const res = await fetch('/api/services/health', { cache: 'no-store', signal: controller.signal })
    clearTimeout(timeoutId)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data: { services: ServiceHealthEntry[] } = await res.json()
    return data.services.map(entry => {
      // info must be present if the service published EITHER a version OR a tech stack
      // (BuildInfo). Previously this only checked version/gitCommit, which dropped
      // the stack on the floor and Tech Inventory then showed N/A for everything.
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
    })
  } catch {
    return SERVICES.map(s => ({ name: s.name, port: s.port, info: null, health: null, rateLimitMax: null, rateLimitRemaining: null, apiVersion: null, latencyMs: null, reachable: false }))
  }
}

