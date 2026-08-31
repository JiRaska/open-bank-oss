// SPDX-License-Identifier: Apache-2.0
export const CAPABILITIES_BY_RESOURCE = {
  ACCOUNT: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_READ_TRANSACTIONS', 'ACCOUNT_INITIATE_PAYMENT', 'ACCOUNT_PROPOSE_PAYMENT', 'DELEGATION_MANAGE'],
  SAVINGS_GOAL: ['SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAW', 'SAVINGS_PROPOSE_WITHDRAW'],
  CARD: ['CARD_VIEW', 'CARD_MANAGE_LIMITS'],
  PAYMENT: ['OBJECT_READ'], STATEMENT: ['OBJECT_READ'], DOCUMENT: ['OBJECT_READ'],
} as const
export type DelegationResource = keyof typeof CAPABILITIES_BY_RESOURCE
export type RolePreset = { id: string; name: string; description: string; resourceType: DelegationResource; capabilities: string[]; createdAt?: string; updatedAt?: string }
