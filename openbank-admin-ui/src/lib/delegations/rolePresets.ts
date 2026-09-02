// SPDX-License-Identifier: Apache-2.0
export const CAPABILITIES_BY_RESOURCE = {
  ACCOUNT: ['ACCOUNT_VIEW_DETAILS', 'ACCOUNT_READ_BALANCES', 'ACCOUNT_READ_TRANSACTIONS', 'ACCOUNT_DOWNLOAD_STATEMENTS', 'ACCOUNT_PROPOSE_PAYMENT', 'ACCOUNT_INITIATE_PAYMENT', 'ACCOUNT_MANAGE_BENEFICIARIES', 'ACCOUNT_MANAGE_LIMITS', 'DELEGATION_MANAGE'],
  SAVINGS_GOAL: ['SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAW', 'SAVINGS_PROPOSE_WITHDRAW'],
  CARD: ['CARD_VIEW', 'CARD_VIEW_TRANSACTIONS', 'CARD_MANAGE_LIMITS', 'CARD_MANAGE_STATUS', 'CARD_MANAGE_CHANNELS'],
  PAYMENT: ['OBJECT_READ'], STATEMENT: ['OBJECT_READ'], DOCUMENT: ['OBJECT_READ'],
} as const
export type DelegationResource = keyof typeof CAPABILITIES_BY_RESOURCE
export type RolePreset = { id: string; name: string; description: string; resourceType: DelegationResource; capabilities: string[]; createdAt?: string; updatedAt?: string }

const RESERVED_OWNERSHIP_PRESET_NAMES = new Set(['majitel účtu', 'majitel karty', 'account owner', 'card owner'])
const FULL_DELEGATE_NAMES: Partial<Record<DelegationResource, string>> = {
  ACCOUNT: 'Plný disponent účtu',
  CARD: 'Plný disponent karty',
}

export const isReservedOwnershipPresetName = (name: string): boolean =>
  RESERVED_OWNERSHIP_PRESET_NAMES.has(name.normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase())

/** Truthful presentation for historical stock presets until their guarded V14 correction lands. */
export const truthfulPresetName = (preset: Pick<RolePreset, 'name' | 'resourceType'>): string =>
  isReservedOwnershipPresetName(preset.name)
    ? (FULL_DELEGATE_NAMES[preset.resourceType] ?? 'Delegovatelná role')
    : preset.name

// Kept in the full matrix because an account owner can manage sharing by virtue of ownership.
// It is not offered in delegation presets: no grant-consuming service authorizes a delegate to
// create another grant, so presenting it as assignable would promise recursive authority that the
// platform does not enforce. Existing grants may still carry the wire value and remain readable.
export const isAssignablePresetCapability = (capability: string): boolean => capability !== 'DELEGATION_MANAGE'

export const assignablePresetCapabilities = (resource: DelegationResource): readonly string[] =>
  CAPABILITIES_BY_RESOURCE[resource].filter(isAssignablePresetCapability)

export type CapabilityIntent = 'view' | 'act' | 'manage'

const CAPABILITY_LABELS: Record<string, [string, string]> = {
  ACCOUNT_VIEW_DETAILS: ['Detail účtu', 'Account details'],
  ACCOUNT_READ_BALANCES: ['Zůstatky', 'Balances'],
  ACCOUNT_READ_TRANSACTIONS: ['Transakce', 'Transactions'],
  ACCOUNT_DOWNLOAD_STATEMENTS: ['Výpisy', 'Statements'],
  ACCOUNT_PROPOSE_PAYMENT: ['Připravit platbu', 'Propose payment'],
  ACCOUNT_INITIATE_PAYMENT: ['Provést platbu', 'Execute payment'],
  ACCOUNT_MANAGE_BENEFICIARIES: ['Příjemci', 'Beneficiaries'],
  ACCOUNT_MANAGE_LIMITS: ['Limity účtu', 'Account limits'],
  DELEGATION_MANAGE: ['Disponenti', 'Delegates'],
  CARD_VIEW: ['Detail karty', 'Card details'],
  CARD_VIEW_TRANSACTIONS: ['Transakce karty', 'Card transactions'],
  CARD_MANAGE_LIMITS: ['Limity karty', 'Card limits'],
  CARD_MANAGE_STATUS: ['Blokace karty', 'Card status'],
  CARD_MANAGE_CHANNELS: ['Kanály karty', 'Card channels'],
  SAVINGS_DEPOSIT: ['Vklad', 'Deposit'],
  SAVINGS_WITHDRAW: ['Výběr', 'Withdraw'],
  SAVINGS_PROPOSE_WITHDRAW: ['Připravit výběr', 'Propose withdrawal'],
  OBJECT_READ: ['Zobrazit', 'View'],
}

export function capabilityLabel(capability: string, language: 'cs' | 'en'): string {
  const label = CAPABILITY_LABELS[capability]
  return label ? label[language === 'cs' ? 0 : 1] : capability.replace(/^(ACCOUNT|SAVINGS|CARD|OBJECT)_/, '')
}

export function capabilityIntent(capability: string): CapabilityIntent {
  if (capability.includes('_READ_') || capability.includes('_VIEW') || capability === 'OBJECT_READ' || capability.includes('DOWNLOAD')) return 'view'
  if (capability.includes('MANAGE') || capability === 'DELEGATION_MANAGE') return 'manage'
  return 'act'
}
