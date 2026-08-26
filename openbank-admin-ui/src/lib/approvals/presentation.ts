// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export type ApprovalLanguage = 'cs' | 'en'

export type ApprovalDomain =
  | 'lending' | 'sanctions' | 'transaction' | 'domestic-payment' | 'clearing' | 'fx'
  | 'ledger' | 'swift' | 'sepa-payment' | 'sepa-instant' | 'notification' | 'party'
  | 'account' | 'balance' | 'billing' | 'consent' | 'agent'

type BilingualLabel = { cs: string; en: string }

const APPROVAL_TTL_MS = 24 * 60 * 60 * 1000

const DOMAIN_LABELS: Record<ApprovalDomain, BilingualLabel> = {
  lending: { cs: 'Úvěry', en: 'Lending' },
  sanctions: { cs: 'Sankce', en: 'Sanctions' },
  transaction: { cs: 'Transakce', en: 'Transactions' },
  'domestic-payment': { cs: 'Tuzemské platby', en: 'Domestic payments' },
  clearing: { cs: 'Clearing', en: 'Clearing' },
  fx: { cs: 'Měnové konverze', en: 'Foreign exchange' },
  ledger: { cs: 'Hlavní kniha', en: 'Ledger' },
  swift: { cs: 'SWIFT', en: 'SWIFT' },
  'sepa-payment': { cs: 'SEPA platby', en: 'SEPA payments' },
  'sepa-instant': { cs: 'Okamžité SEPA platby', en: 'SEPA instant payments' },
  notification: { cs: 'Provozní zprávy', en: 'Operator messages' },
  party: { cs: 'Klientské profily', en: 'Party profiles' },
  account: { cs: 'Účty', en: 'Accounts' },
  balance: { cs: 'Zůstatky', en: 'Balances' },
  billing: { cs: 'Poplatky a účtování', en: 'Billing' },
  consent: { cs: 'Souhlasy', en: 'Consents' },
  agent: { cs: 'AI návrhy', en: 'AI proposals' },
}

// Exact policy actions that can currently enter the four-eyes queue. The technical key remains
// visible beside this label in the UI, so localization never weakens auditability.
const ACTION_LABELS: Record<string, BilingualLabel> = {
  'account.freeze': { cs: 'Zmrazit účet', en: 'Freeze account' },
  'account.unfreeze': { cs: 'Odblokovat účet', en: 'Unfreeze account' },
  'balance.credit': { cs: 'Připsat korekci zůstatku', en: 'Credit balance adjustment' },
  'balance.debit': { cs: 'Odepsat korekci zůstatku', en: 'Debit balance adjustment' },
  'billing.post': { cs: 'Zaúčtovat poplatek', en: 'Post fee' },
  'billing.reverse': { cs: 'Stornovat poplatek', en: 'Reverse fee' },
  'clearingBatch.settle': { cs: 'Vypořádat clearingovou dávku', en: 'Settle clearing batch' },
  'consent.grant': { cs: 'Udělit souhlas', en: 'Grant consent' },
  'consent.revoke': { cs: 'Odvolat souhlas', en: 'Revoke consent' },
  'domestic-payment.transitionStatus': { cs: 'Změnit stav tuzemské platby', en: 'Transition domestic payment' },
  'fx.convert': { cs: 'Provést měnovou konverzi', en: 'Execute currency conversion' },
  'ledger.reverse': { cs: 'Stornovat účetní zápis', en: 'Reverse ledger journal' },
  'lending.collateralRegister': { cs: 'Zaregistrovat zajištění', en: 'Register collateral' },
  'lending.disburse': { cs: 'Čerpat úvěr', en: 'Disburse loan' },
  'opsmessage.compose': { cs: 'Odeslat provozní zprávu klientovi', en: 'Send operator message to customer' },
  'party.merge': { cs: 'Sloučit klientské profily', en: 'Merge party profiles' },
  'sanctions.clear': { cs: 'Rozhodnout sankční nález', en: 'Decide sanctions hit' },
  'sctInstPayment.recall': { cs: 'Odvolat okamžitou SEPA platbu', en: 'Recall SEPA instant payment' },
  'sepaPayment.transitionStatus': { cs: 'Změnit stav SEPA platby', en: 'Transition SEPA payment' },
  'swift.send': { cs: 'Odeslat SWIFT zprávu', en: 'Send SWIFT message' },
  'transaction.reverse': { cs: 'Stornovat transakci', en: 'Reverse transaction' },
  'transaction.sweep': { cs: 'Převést zůstatek při sloučení klienta', en: 'Sweep balance during party merge' },
}

export function approvalDomainLabel(domain: ApprovalDomain, language: ApprovalLanguage): string {
  return DOMAIN_LABELS[domain][language]
}

export function approvalActionLabel(action: string, language: ApprovalLanguage): string {
  const exact = ACTION_LABELS[action]
  if (exact) return exact[language]

  const verb = action.split('.').at(-1) ?? action
  const words = verb.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[-_]/g, ' ').trim()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : action
}

export function approvalExpiresAt(createdAt: string | null): Date | null {
  if (!createdAt) return null
  const createdMillis = Date.parse(createdAt)
  return Number.isFinite(createdMillis) ? new Date(createdMillis + APPROVAL_TTL_MS) : null
}
