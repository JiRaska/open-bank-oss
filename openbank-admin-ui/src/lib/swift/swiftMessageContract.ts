// SPDX-License-Identifier: Apache-2.0

import type { Tone } from '@/components/ui/tone'

export const SWIFT_MESSAGE_TYPES = ['MT103', 'MT202', 'MT900', 'MT910', 'MT940', 'MT950', 'MT199'] as const
export const SWIFT_STATUSES = ['PENDING', 'VALIDATED', 'SENT', 'ACKNOWLEDGED', 'REJECTED', 'FAILED', 'COMPLETED'] as const

export type SwiftMessageType = (typeof SWIFT_MESSAGE_TYPES)[number]
export type SwiftStatus = (typeof SWIFT_STATUSES)[number]

export interface SwiftMessage {
  id: string
  messageType: SwiftMessageType
  senderBic: string
  receiverBic: string
  amount: number
  currency: string
  status: SwiftStatus
  createdAt: string
  reference: string
}

export type SwiftLifecycleGroup = 'in_flight' | 'confirmed' | 'exception'

const MESSAGE_TYPES = new Set<string>(SWIFT_MESSAGE_TYPES)
const STATUSES = new Set<string>(SWIFT_STATUSES)

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid SWIFT ${field}`)
  return value
}

function parseMessage(value: unknown): SwiftMessage {
  if (!isRecord(value)) throw new Error('Invalid SWIFT message')

  const messageType = requiredString(value, 'messageType')
  const status = requiredString(value, 'status')
  const amount = value.amount
  const currency = requiredString(value, 'currency')
  const createdAt = requiredString(value, 'createdAt')

  if (!MESSAGE_TYPES.has(messageType)) throw new Error('Invalid SWIFT messageType')
  if (!STATUSES.has(status)) throw new Error('Invalid SWIFT status')
  if (typeof amount !== 'number' || !Number.isFinite(amount) || amount <= 0) throw new Error('Invalid SWIFT amount')
  if (!/^[A-Z]{3}$/.test(currency)) throw new Error('Invalid SWIFT currency')
  if (Number.isNaN(Date.parse(createdAt))) throw new Error('Invalid SWIFT createdAt')

  return {
    id: requiredString(value, 'id'),
    messageType: messageType as SwiftMessageType,
    senderBic: requiredString(value, 'senderBic'),
    receiverBic: requiredString(value, 'receiverBic'),
    amount,
    currency,
    status: status as SwiftStatus,
    createdAt,
    reference: requiredString(value, 'reference'),
  }
}

export function parseSwiftMessages(raw: unknown): SwiftMessage[] {
  const rows = Array.isArray(raw)
    ? raw
    : isRecord(raw) && Array.isArray(raw.messages)
      ? raw.messages
      : null
  if (!rows) throw new Error('Invalid SWIFT message list')
  return rows.map(parseMessage)
}

export function swiftLifecycleGroup(status: SwiftStatus): SwiftLifecycleGroup {
  if (status === 'ACKNOWLEDGED' || status === 'COMPLETED') return 'confirmed'
  if (status === 'REJECTED' || status === 'FAILED') return 'exception'
  return 'in_flight'
}

export function swiftStatusTone(status: SwiftStatus): Tone {
  if (status === 'ACKNOWLEDGED' || status === 'COMPLETED') return 'success'
  if (status === 'REJECTED' || status === 'FAILED') return 'danger'
  if (status === 'PENDING') return 'warning'
  return 'info'
}
