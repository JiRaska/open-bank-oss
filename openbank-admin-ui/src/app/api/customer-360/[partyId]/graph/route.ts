// SPDX-License-Identifier: Apache-2.0

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import {
  parseAccounts, parseAmlCases, parseCards, parseDevices, parseDocuments,
  parseLendingApplications, parseNotifications,
} from '@/lib/context/customerGraph'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TIMEOUT_MS = 5_000
const MAX_SOURCE_BYTES = 1024 * 1024
const LIMITS = { accounts: 50, cards: 50, notifications: 30, lending: 30, aml: 30, devices: 20, documents: 30 } as const

type Source = 'accounts' | 'cards' | 'notifications' | 'lending' | 'aml' | 'devices' | 'documents'
type ReadResult = { source: Source; body: unknown; available: boolean }

function sourceRowCount(source: Source, body: unknown): number {
  if (source === 'accounts' && body && typeof body === 'object' && 'data' in body) {
    return Array.isArray(body.data) ? body.data.length : 0
  }
  if ((source === 'notifications' || source === 'devices') && body && typeof body === 'object' && 'items' in body) {
    return Array.isArray(body.items) ? body.items.length : 0
  }
  return Array.isArray(body) ? body.length : 0
}

async function boundedJson(response: Response): Promise<unknown> {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('Missing graph source body')
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > MAX_SOURCE_BYTES) throw new Error('Graph source response too large')
      chunks.push(value)
    }
  } catch (error) {
    void reader.cancel().catch(() => undefined)
    throw error
  }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown
}

async function read(source: Source, url: string, authorization: string): Promise<ReadResult> {
  try {
    const response = await fetch(url, {
      headers: { authorization }, cache: 'no-store', signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    if (!response.ok) return { source, body: null, available: false }
    return { source, body: await boundedJson(response), available: true }
  } catch {
    return { source, body: null, available: false }
  }
}

export async function GET(_request: Request, { params }: { params: Promise<{ partyId: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasPermission(session.user.roles ?? [], 'compliance:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  const { partyId } = await params
  if (!UUID_RE.test(partyId)) return NextResponse.json({ error: 'invalid_party_id' }, { status: 400 })

  const bearer = `Bearer ${session.user.accessToken}`
  const results = await Promise.all([
    read('accounts', serverSvcUrl('account-service', 'accounts', 8100, '/api/v1/accounts', { partyId, limit: String(LIMITS.accounts + 1) }), bearer),
    read('cards', serverSvcUrl('card-issuance-service', 'payments', 8118, `/api/v1/cards/party/${partyId}`, {
      limit: String(LIMITS.cards + 1),
    }), bearer),
    read('notifications', serverSvcUrl('notification-service', 'notifications', 8112, '/api/v1/notifications', {
      partyId, page: '0', size: String(LIMITS.notifications + 1),
    }), bearer),
    read('lending', serverSvcUrl('lending-service', 'lending', 8126, '/api/v1/lending/applications', {
      partyId, limit: String(LIMITS.lending + 1),
    }), bearer),
    read('aml', serverSvcUrl('aml-service', 'aml', 8117, '/api/v1/aml/cases', {
      partyId, limit: String(LIMITS.aml + 1), offset: '0',
    }), bearer),
    read('devices', serverSvcUrl('notification-service', 'notifications', 8112, '/api/v1/devices', {
      partyId, limit: String(LIMITS.devices + 1),
    }), bearer),
    read('documents', serverSvcUrl('document-service', 'documents', 8143, '/api/v1/documents', {
      partyRef: partyId, page: '0', size: String(LIMITS.documents + 1),
    }), bearer),
  ])
  const bySource = new Map(results.map(result => [result.source, result]))
  const accounts = bySource.get('accounts')!
  const cards = bySource.get('cards')!
  const notifications = bySource.get('notifications')!
  const parsed = {
    accounts: accounts.available ? parseAccounts(accounts.body) : [],
    cards: cards.available ? parseCards(cards.body) : [],
    notifications: notifications.available ? parseNotifications(notifications.body) : [],
    lending: bySource.get('lending')!.available ? parseLendingApplications(bySource.get('lending')!.body) : [],
    aml: bySource.get('aml')!.available ? parseAmlCases(bySource.get('aml')!.body) : [],
    devices: bySource.get('devices')!.available ? parseDevices(bySource.get('devices')!.body) : [],
    documents: bySource.get('documents')!.available ? parseDocuments(bySource.get('documents')!.body) : [],
  }

  return NextResponse.json({
    accounts: parsed.accounts.slice(0, LIMITS.accounts),
    cards: parsed.cards.slice(0, LIMITS.cards),
    notifications: parsed.notifications.slice(0, LIMITS.notifications),
    lendingApplications: parsed.lending.slice(0, LIMITS.lending),
    amlCases: parsed.aml.slice(0, LIMITS.aml),
    devices: parsed.devices.slice(0, LIMITS.devices),
    documents: parsed.documents.slice(0, LIMITS.documents),
    unavailable: results.filter(result => !result.available).map(result => result.source),
    truncated: results.filter(result => result.available && sourceRowCount(result.source, result.body) > LIMITS[result.source])
      .map(result => result.source),
    fetchedAt: new Date().toISOString(),
  }, { headers: { 'cache-control': 'private, no-store' } })
}
