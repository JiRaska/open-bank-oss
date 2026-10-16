// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// ADR-0227 D1/D3: the approval store does not carry a risk classification, and a service
// runtime cannot reach the authoritative action-class metadata (rules.yaml four_eyes) without
// coupling the inbox read to the OPA bundle. So the BFF derives the risk class per item from
// a STATIC COPY of that metadata below. The copy is guarded: approval-risk-map.guard.test.ts
// parses openbank-libs/governance/rules.yaml and fails on any drift between the YAML and the
// sets below. When rules.yaml four_eyes.verbs / four_eyes.actions / money_path_services (or
// the action-prefix overrides) change, update this file in the same PR.

// four_eyes.verbs (rules.yaml) — money-moving verb suffixes. Only meaningful for an action
// whose scope derives from money_path_services, mirroring rest.rego's four_eyes_required.
const FOUR_EYES_VERBS: ReadonlySet<string> = new Set([
  'transfer',
  'post',
  'reverse',
  'freeze',
  'release',
  'flip',
  'transitionStatus',
  'recall',
  'settle',
  'disburse',
  'send',
  'credit',
  'debit',
  'collateralRegister',
  'convert',
  'grant',
  'revoke',
  'sweep',
  'clear',
])

// four_eyes.actions (rules.yaml) — exact action names gated independently of money-path
// scope. These are the destructive/blast-radius gates: identity merge, customer messaging,
// campaign activation, device enrolment, and the rest.
const FOUR_EYES_ACTIONS: ReadonlySet<string> = new Set([
  'settlement.create',
  'catalog.publish',
  'commstyle.publish',
  'opsmessage.compose',
  'campaign.activate',
  'party.merge',
  'sdd.approve',
  'interest.create',
  'interest.trigger',
  'device.enroll',
  'scaChallenge.consume',
])

// money_path_services (rules.yaml), normalised the way rest.rego derives action scopes:
// strip `openbank-` and a trailing `-service`.
const MONEY_PATH_SCOPES: ReadonlySet<string> = new Set([
  'ledger',
  'transaction',
  'account',
  'balance',
  'sepa-payment',
  'sepa-instant',
  'domestic-payment',
  'clearing',
  'swift',
  'fx',
  'lending',
  'sdd',
  'interest',
  'sca',
  'consent',
  'delegation',
  'fraud',
  'billing',
  'settlement',
  'sanctions',
  'vop',
  'standing-order',
  'psd2',
  'card-issuance',
  'treasury',
  'card-processing',
  'pension',
  'pension-fund',
])

// money_path_action_prefixes (rules.yaml): scopes whose @Authorize action prefix diverges
// from the derived service name. Mapped here to the derived scope they belong to.
const ACTION_PREFIX_OVERRIDES: Record<string, string> = {
  sepaPayment: 'sepa-payment',
  sctInstPayment: 'sepa-instant',
  clearingBatch: 'clearing',
  device: 'sca',
  scaChallenge: 'sca',
}

export type ApprovalRiskClass = 'MONEY_PATH' | 'DESTRUCTIVE' | 'OPERATIONAL'

/**
 * Classify an inbox item's action the way rest.rego's four_eyes_required does:
 * - MONEY_PATH — the action's scope derives from money_path_services AND its verb is in
 *   four_eyes.verbs (the money-moving gates).
 * - DESTRUCTIVE — the exact action is in four_eyes.actions (blast-radius gates, independent
 *   of money-path scope).
 * - OPERATIONAL — everything else pending in a store: still four-eyes-gated, but no elevated
 *   action-class metadata applies. Never inferred by verb heuristics — the rules.yaml lists
 *   exist because action-name-alone guesses were wrong twice (issue #395, #938 follow-up).
 */
export function classifyApprovalRisk(domain: string, action: string): ApprovalRiskClass {
  if (FOUR_EYES_ACTIONS.has(action)) return 'DESTRUCTIVE'
  const prefix = action.slice(0, action.indexOf('.'))
  const verb = action.slice(action.lastIndexOf('.') + 1)
  const scope = ACTION_PREFIX_OVERRIDES[prefix] ?? (MONEY_PATH_SCOPES.has(prefix) ? prefix : null)
  if (scope !== null && FOUR_EYES_VERBS.has(verb)) return 'MONEY_PATH'
  // A money-path domain whose action verb is not gated (e.g. treasury.MM_PLACEMENT, whose
  // four-eyes lives in the domain, not in four_eyes.actions) is operationally gated but not
  // money-moving per the OPA metadata.
  return 'OPERATIONAL'
}

// Kept for the drift guard: raw rule data the guard test cross-checks against rules.yaml.
export const RISK_RULE_COPY = {
  fourEyesVerbs: FOUR_EYES_VERBS,
  fourEyesActions: FOUR_EYES_ACTIONS,
  moneyPathScopes: MONEY_PATH_SCOPES,
  actionPrefixOverrides: ACTION_PREFIX_OVERRIDES,
} as const
