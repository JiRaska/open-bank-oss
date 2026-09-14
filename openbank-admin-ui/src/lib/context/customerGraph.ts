// SPDX-License-Identifier: Apache-2.0

import type { Customer360Evidence } from '@/lib/customer360/evidence'

export type CustomerGraphKind =
  | 'domain' | 'account' | 'product' | 'card' | 'notification' | 'consent'
  | 'application' | 'case' | 'device' | 'document'

export interface CustomerGraphNode {
  id: string
  kind: CustomerGraphKind
  label: string
  source: string
  facts: string[]
}

export interface CustomerGraphEdge {
  id: string
  from: string
  to: string
  relation: string
}

export interface CustomerGraph {
  nodes: CustomerGraphNode[]
  edges: CustomerGraphEdge[]
  truncated: boolean
}

export interface AccountFact {
  id: string
  accountNumber: string
  accountType: string
  productId: string
  currencyCode: string
  status: string
  openedAt: string
}

export interface CardFact {
  id: string
  accountId: string
  productCode: string
  cardType: string
  network: string
  maskedPan: string
  status: string
  createdAt: string
  activatedAt?: string | null
  blockedAt?: string | null
  blockedReason?: string | null
}

export interface NotificationFact {
  id: string
  channel: string
  template: string
  status: string
  createdAt: string
  sentAt?: string | null
  readAt?: string | null
}

export interface LendingApplicationFact {
  id: string
  status: string
  productKind: string
  createdAt: string
}

export interface AmlCaseFact {
  id: string
  accountId: string
  transactionId: string
  screeningType: string
  riskLevel: string
  status: string
  alertCode: string
  screenedAt: string
}

export interface DeviceFact {
  id: string
  platform: string
  appVersion: string
  osVersion: string
  status: string
  registeredAt: string
  refreshedAt: string | null
  lastUsedAt: string | null
}

export interface DocumentFact {
  id: string
  templateCode: string
  templateVersion: string
  contentType: string
  sizeBytes: number
  status: string
  caseRef: string
  productRef: string
  retainUntil: string
  createdAt: string
}

export interface LiveCustomerFacts {
  accounts: AccountFact[]
  cards: CardFact[]
  notifications: NotificationFact[]
  lendingApplications: LendingApplicationFact[]
  amlCases: AmlCaseFact[]
  devices: DeviceFact[]
  documents: DocumentFact[]
  unavailable: string[]
}

export function parseLiveCustomerFacts(value: unknown): LiveCustomerFacts {
  if (!isRecord(value)) return emptyLiveCustomerFacts(['graph'])
  const unavailable = Array.isArray(value.unavailable)
    ? value.unavailable.filter((item): item is string => typeof item === 'string')
    : []
  return {
    accounts: parseAccounts({ data: value.accounts }),
    cards: parseCards(value.cards),
    notifications: parseNotifications({ items: value.notifications }),
    lendingApplications: parseLendingApplications(value.lendingApplications),
    amlCases: parseAmlCases(value.amlCases),
    devices: parseDevices({ items: value.devices }),
    documents: parseDocuments(value.documents),
    unavailable,
  }
}

export function emptyLiveCustomerFacts(unavailable: string[] = []): LiveCustomerFacts {
  return {
    accounts: [], cards: [], notifications: [], lendingApplications: [], amlCases: [],
    devices: [], documents: [], unavailable,
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const text = (row: Record<string, unknown>, key: string): string =>
  typeof row[key] === 'string' ? (row[key] as string) : ''

const nullableText = (row: Record<string, unknown>, key: string): string | null =>
  row[key] === null || row[key] === undefined ? null : text(row, key) || null

const identifier = (row: Record<string, unknown>, key: string): string => {
  const value = row[key]
  if (typeof value === 'string') return value
  return isRecord(value) ? text(value, 'value') : ''
}

const number = (row: Record<string, unknown>, key: string): number =>
  typeof row[key] === 'number' && Number.isFinite(row[key]) ? row[key] : 0

function records(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter(isRecord) : []
}

export function parseAccounts(value: unknown): AccountFact[] {
  if (!isRecord(value)) return []
  return records(value.data).flatMap(row => {
    const id = text(row, 'id')
    const productId = text(row, 'productId')
    if (!id) return []
    return [{
      id,
      accountNumber: text(row, 'accountNumber'),
      accountType: text(row, 'accountType'),
      productId,
      currencyCode: text(row, 'currencyCode'),
      status: text(row, 'status'),
      openedAt: text(row, 'openedAt'),
    }]
  })
}

export function parseCards(value: unknown): CardFact[] {
  return records(value).flatMap(row => {
    const id = text(row, 'id')
    const accountId = text(row, 'accountId')
    if (!id || !accountId) return []
    return [{
      id,
      accountId,
      productCode: text(row, 'productCode'),
      cardType: text(row, 'cardType'),
      network: text(row, 'network'),
      maskedPan: text(row, 'maskedPan'),
      status: text(row, 'status'),
      createdAt: text(row, 'createdAt'),
      activatedAt: nullableText(row, 'activatedAt'),
      blockedAt: nullableText(row, 'blockedAt'),
      blockedReason: nullableText(row, 'blockedReason'),
    }]
  })
}

export function parseNotifications(value: unknown): NotificationFact[] {
  if (!isRecord(value)) return []
  return records(value.items).flatMap(row => {
    const id = text(row, 'id')
    if (!id) return []
    return [{
      id,
      channel: text(row, 'channel'),
      template: text(row, 'template'),
      status: text(row, 'status'),
      createdAt: text(row, 'createdAt'),
      sentAt: nullableText(row, 'sentAt'),
      readAt: nullableText(row, 'readAt'),
    }]
  })
}

export function parseLendingApplications(value: unknown): LendingApplicationFact[] {
  return records(value).flatMap(row => {
    const id = identifier(row, 'id')
    if (!id) return []
    return [{
      id, status: text(row, 'status'), productKind: text(row, 'productKind'), createdAt: text(row, 'createdAt'),
    }]
  })
}

export function parseAmlCases(value: unknown): AmlCaseFact[] {
  return records(value).flatMap(row => {
    const id = text(row, 'id')
    if (!id) return []
    return [{
      id, accountId: text(row, 'accountId'), transactionId: text(row, 'transactionId'),
      screeningType: text(row, 'screeningType'), riskLevel: text(row, 'riskLevel'),
      status: text(row, 'status'), alertCode: text(row, 'alertCode'), screenedAt: text(row, 'screenedAt'),
    }]
  })
}

export function parseDevices(value: unknown): DeviceFact[] {
  if (!isRecord(value)) return []
  return records(value.items).flatMap(row => {
    const id = text(row, 'id')
    if (!id) return []
    return [{
      id, platform: text(row, 'platform'), appVersion: text(row, 'appVersion'), osVersion: text(row, 'osVersion'),
      status: text(row, 'status'), registeredAt: text(row, 'registeredAt'),
      refreshedAt: nullableText(row, 'refreshedAt'), lastUsedAt: nullableText(row, 'lastUsedAt'),
    }]
  })
}

export function parseDocuments(value: unknown): DocumentFact[] {
  return records(value).flatMap(row => {
    const id = text(row, 'id')
    if (!id) return []
    return [{
      id, templateCode: text(row, 'templateCode'), templateVersion: text(row, 'templateVersion'),
      contentType: text(row, 'contentType'), sizeBytes: number(row, 'sizeBytes'), status: text(row, 'status'), caseRef: text(row, 'caseRef'),
      productRef: text(row, 'productRef'), retainUntil: text(row, 'retainUntil'), createdAt: text(row, 'createdAt'),
    }]
  })
}

export function buildCustomerGraph(
  evidence: Customer360Evidence,
  live: LiveCustomerFacts,
): CustomerGraph {
  const nodes: CustomerGraphNode[] = []
  const edges: CustomerGraphEdge[] = []
  const addEdge = (from: string, to: string, relation: string) =>
    edges.push({ id: `${from}:${relation}:${to}`, from, to, relation })

  for (const domain of evidence.domains) {
    const id = `domain:${domain.aggregateType}`
    nodes.push({
      id, kind: 'domain', label: domain.aggregateType, source: 'analytics-sink',
      facts: [`Events: ${domain.events}`, `Latest event: ${domain.lastEventType}`, `Event time: ${domain.lastOccurredAt}`],
    })
    addEdge('customer', id, 'OBSERVED_IN')
  }

  const products = new Set<string>()
  const liveAccountIds = new Set(live.accounts.map(account => account.id))
  for (const accountId of evidence.accountIds) {
    if (liveAccountIds.has(accountId)) continue
    const id = `account:${accountId}`
    nodes.push({
      id, kind: 'account', label: accountId, source: 'analytics-sink',
      facts: [`Projected account reference: ${accountId}`],
    })
    addEdge('customer', id, 'OBSERVED_ACCOUNT')
  }
  for (const account of live.accounts.slice(0, 50)) {
    const id = `account:${account.id}`
    nodes.push({
      id, kind: 'account', label: account.accountNumber || account.id, source: 'account-service',
      facts: [account.accountType, account.currencyCode, `Status: ${account.status}`, `Opened: ${account.openedAt}`],
    })
    addEdge('customer', id, 'OWNS')
    if (account.productId) {
      products.add(account.productId)
      addEdge(id, `product:${account.productId}`, 'BASED_ON_PRODUCT')
    }
  }

  for (const card of live.cards.slice(0, 50)) {
    const id = `card:${card.id}`
    nodes.push({
      id, kind: 'card', label: card.maskedPan || `${card.cardType} card`, source: 'card-issuance-service',
      facts: [
        [card.cardType, card.network].filter(Boolean).join(' · '),
        `Status: ${card.status}`,
        `Created: ${card.createdAt}`,
        ...(card.activatedAt ? [`Activated: ${card.activatedAt}`] : []),
        ...(card.blockedAt ? [`Blocked: ${card.blockedAt}`] : []),
        ...(card.blockedReason ? [`Block reason: ${card.blockedReason}`] : []),
      ].filter(Boolean),
    })
    addEdge(`account:${card.accountId}`, id, 'HAS_CARD')
    if (card.productCode) {
      products.add(card.productCode)
      addEdge(id, `product:${card.productCode}`, 'ISSUED_AS_PRODUCT')
    }
  }

  for (const product of products) {
    nodes.push({
      id: `product:${product}`, kind: 'product', label: product, source: 'product reference',
      facts: [`Product reference: ${product}`],
    })
  }

  for (const notification of live.notifications.slice(0, 30)) {
    const id = `notification:${notification.id}`
    nodes.push({
      id, kind: 'notification', label: notification.template || notification.channel, source: 'notification-service',
      facts: [
        `Channel: ${notification.channel}`,
        `Status: ${notification.status}`,
        `Created: ${notification.createdAt}`,
        ...(notification.sentAt ? [`Sent: ${notification.sentAt}`] : []),
        ...(notification.readAt ? [`Read: ${notification.readAt}`] : []),
      ],
    })
    addEdge('customer', id, 'RECEIVED_NOTIFICATION')
  }

  for (const application of live.lendingApplications.slice(0, 30)) {
    const id = `application:${application.id}`
    nodes.push({
      id, kind: 'application', label: application.productKind || application.id, source: 'lending-service',
      facts: [`Status: ${application.status}`, `Created: ${application.createdAt}`],
    })
    addEdge('customer', id, 'APPLIED_FOR_CREDIT')
  }

  const transactionReferences = new Set<string>()
  for (const amlCase of live.amlCases.slice(0, 30)) {
    const id = `case:${amlCase.id}`
    nodes.push({
      id, kind: 'case', label: amlCase.screeningType || amlCase.id, source: 'aml-service',
      facts: [
        `Status: ${amlCase.status}`, `Risk: ${amlCase.riskLevel}`, `Alert: ${amlCase.alertCode}`,
        `Screened: ${amlCase.screenedAt}`,
      ],
    })
    addEdge('customer', id, 'SUBJECT_OF_AML_CASE')
    if (amlCase.accountId) addEdge(`account:${amlCase.accountId}`, id, 'SCREENED_IN_CASE')
    if (amlCase.transactionId) {
      const transactionId = `domain-reference:transaction:${amlCase.transactionId}`
      if (!transactionReferences.has(transactionId)) {
        transactionReferences.add(transactionId)
        nodes.push({ id: transactionId, kind: 'domain', label: amlCase.transactionId, source: 'aml-service', facts: ['Transaction reference from AML case'] })
      }
      addEdge(transactionId, id, 'TRIGGERED_CASE')
    }
  }

  for (const device of live.devices.slice(0, 20)) {
    const id = `device:${device.id}`
    nodes.push({
      id, kind: 'device', label: device.platform || device.id, source: 'notification-service',
      facts: [
        `Status: ${device.status}`, `Registered: ${device.registeredAt}`,
        ...(device.appVersion ? [`App version: ${device.appVersion}`] : []),
        ...(device.osVersion ? [`OS version: ${device.osVersion}`] : []),
        ...(device.refreshedAt ? [`Refreshed: ${device.refreshedAt}`] : []),
        ...(device.lastUsedAt ? [`Last used: ${device.lastUsedAt}`] : []),
      ],
    })
    addEdge('customer', id, 'USES_DEVICE')
  }

  for (const document of live.documents.slice(0, 30)) {
    const id = `document:${document.id}`
    nodes.push({
      id, kind: 'document', label: document.templateCode || document.id, source: 'document-service',
      facts: [
        `Status: ${document.status}`, `Version: ${document.templateVersion}`, `Created: ${document.createdAt}`,
        ...(document.contentType ? [`Content type: ${document.contentType}`] : []),
        ...(document.retainUntil ? [`Retain until: ${document.retainUntil}`] : []),
        ...(document.caseRef ? [`Case reference: ${document.caseRef}`] : []),
      ],
    })
    addEdge('customer', id, 'HAS_DOCUMENT')
    if (document.productRef) {
      if (!products.has(document.productRef)) {
        products.add(document.productRef)
        nodes.push({
          id: `product:${document.productRef}`, kind: 'product', label: document.productRef,
          source: 'document-service', facts: [`Product reference: ${document.productRef}`],
        })
      }
      addEdge(id, `product:${document.productRef}`, 'DOCUMENTS_PRODUCT')
    }
  }

  const consentIds = new Set<string>()
  for (const consent of evidence.consents) {
    if (consentIds.has(consent.consentId)) continue
    consentIds.add(consent.consentId)
    const observations = evidence.consents.filter(item => item.consentId === consent.consentId)
    const id = `consent:${consent.consentId}`
    nodes.push({
      id, kind: 'consent', label: consent.consentId, source: 'analytics-sink',
      facts: observations.slice(0, 10).flatMap(item => [`State: ${item.status}`, `Scopes: ${item.scopes.join(', ') || '—'}`]),
    })
    addEdge('customer', id, 'HAS_CONSENT')
  }

  return {
    nodes,
    edges,
    truncated: live.accounts.length > 50 || live.cards.length > 50 || live.notifications.length > 30
      || live.lendingApplications.length > 30 || live.amlCases.length > 30 || live.devices.length > 20
      || live.documents.length > 30,
  }
}
