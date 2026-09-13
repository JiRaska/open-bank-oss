// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export type AccountType = 'CURRENT' | 'SAVINGS' | 'TERM_DEPOSIT' | 'NOSTRO' | 'GL_ASSET' | 'GL_LIABILITY' | 'GL_INCOME' | 'GL_EXPENSE'
export type AccountStatus = 'PENDING_ACTIVATION' | 'ACTIVE' | 'DORMANT' | 'FROZEN' | 'CLOSED'
export type TransactionType = 'DEBIT' | 'CREDIT' | 'TRANSFER' | 'FEE' | 'INTEREST' | 'REVERSAL' | 'ADJUSTMENT'
export type TransactionStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'REVERSED'

export interface Account {
  id: string
  accountNumber: string
  accountType: AccountType
  partyId: string
  productId: string
  currencyCode: string
  status: AccountStatus
  openedAt: string
  closedAt?: string
}

export interface AccountBalance {
  accountId: string
  availableBalance: number
  currentBalance: number
  reservedBalance: number
  pendingBalance: number
  currencyCode: string
  lastUpdatedAt: string
}

export interface Transaction {
  id: string
  referenceNumber: string
  type: TransactionType
  sourceAccountId?: string
  targetAccountId?: string
  amount: number
  currencyCode: string
  status: TransactionStatus
  description?: string
  valueDate: string
  bookingDate: string
  initiatedAt: string
  completedAt?: string
}

export interface CursorPage<T> {
  data: T[]
  pagination: {
    limit: number
    hasNextPage: boolean
    nextCursor?: string
    previousCursor?: string
  }
}

export interface ApiError {
  traceId: string
  status: number
  code: string
  message: string
}

// ── Service Info & Health ──────────────────────────────────────────────────

export interface ServiceInfo {
  service: string
  name?: string
  version?: string | null
  apiVersion?: string | null
  buildTime?: string | null
  gitCommit?: string | null
  environment?: string
  startedAt?: string
  timestamp?: string | null
  status: string
  /**
   * Tech-stack snapshot baked into the libs JAR at build time (Kotlin,
   * Quarkus, Gradle, libs build info) plus runtime JVM info. Populated by
   * openbank-libs.web.ServiceInfoResource → BuildInfo. See SBOM-2.
   */
  stack?: ServiceStack | null
}

export interface ServiceStack {
  kotlin?:  { version: string }
  quarkus?: { version: string; lts?: boolean; supportUntil?: string }
  java?:    { version: string; vendor?: string; arch?: string; cpu?: number; maxHeapMib?: number }
  gradle?:  { version: string }
  libs?:    { version: string; buildTime?: string; gitCommit?: string }
}

export interface ServiceHealth {
  status: 'UP' | 'DOWN' | 'DEGRADED'
  checks: HealthCheck[]
}

export interface HealthCheck {
  name: string
  status: 'UP' | 'DOWN'
  data?: Record<string, unknown>
}

// ── Aggregated per-service snapshot ───────────────────────────────────────

export interface ServiceSnapshot {
  name: string
  port: number
  info: ServiceInfo | null
  health: ServiceHealth | null
  rateLimitMax: number | null
  rateLimitRemaining: number | null
  apiVersion: string | null
  latencyMs: number | null
  reachable: boolean
}

export interface JournalLine {
  id: string
  glAccountId: string
  side: 'DEBIT' | 'CREDIT'
  amount: number
  currencyCode: string
  baseAmount: number
  baseCurrencyCode: string
  sequence: number
}

export interface JournalEntry {
  id: string
  entryNumber: number | null
  transactionId: string
  entryDate: string
  valueDate: string
  description: string | null
  status: string
  lines: JournalLine[]
  createdAt: string
}

// ── Service config (live, fetched from /api/v1/config) ───────────────────

export interface ServiceConfigResponse {
  service: string
  rateLimit: { maxConcurrent: number } | null
  circuitBreaker: {
    requestVolumeThreshold: number
    failureRatio: number
    successThreshold: number
    delayMs: number
  } | null
  retry: { maxRetries: number; delayMs: number; jitterMs: number } | null
  timeout: { valueMs: number } | null
}

export interface ServiceConfigSnapshot {
  name: string
  port: number
  config: ServiceConfigResponse | null
  health: ServiceHealth | null
  latencyMs: number | null
  reachable: boolean
}

/** @deprecated Use ServiceConfigResponse – kept for backward compatibility */
export type ResilienceConfig = ServiceConfigResponse
