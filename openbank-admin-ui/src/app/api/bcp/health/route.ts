// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

const GATUS_URL = process.env.GATUS_URL ?? 'http://openbank-gatus:8080'

const GATUS_KEY_TO_CONTAINER: Record<string, string> = {
  'core-banking_account-service':                  'openbank-account-service',
  'core-banking_account-service-—-liveness':       'openbank-account-service',
  'core-banking_product-catalog':                  'openbank-product-catalog',
  'core-banking_ledger-service':                   'openbank-ledger-service',
  'core-banking_transaction-service':              'openbank-transaction-service',
  'core-banking_balance-service':                  'openbank-balance-service',
  'core-banking_pid-service':                      'openbank-pid-service',
  'core-banking_party-service':                    'openbank-party-service',
  'core-banking_notification-service':             'openbank-notification-service',
  'psd2-open-banking_psd2-api-(čobs)':             'openbank-psd2-service',
  'psd2-open-banking_psd2-sandbox':                'openbank-psd2-service',
  'psd2-open-banking_consent-service':             'openbank-consent-service',
  'psd2-open-banking_sca-service':                 'openbank-sca-service',
  'psd2-open-banking_tpp-registry':                'openbank-tpp-registry-service',
  'psd2-open-banking_sepa-payment':                'openbank-sepa-payment',
  'psd2-open-banking_domestic-payment':            'openbank-domestic-payment',
  'psd2-open-banking_api-gateway':                 'openbank-api-gateway',
  'compliance_aml-service':                        'openbank-aml-service',
  'compliance_audit-service':                      'openbank-audit-service',
  'compliance_kyc-service':                        'openbank-kyc-service',
  'compliance_sanctions-service':                  'openbank-sanctions-service',
  'compliance_security-scanner':                   'openbank-security-scanner',
  'payments_card-issuance-service':                'openbank-card-issuance-service',
  'payments_fx-service':                           'openbank-fx-service',
  'payments_standing-order-service':               'openbank-standing-order-service',
  'payments_swift-service':                        'openbank-swift-service',
  'payments_clearing-service':                     'openbank-clearing-service',
  'payments_interest-service':                     'openbank-interest-service',
  'payments_dispute-service':                      'openbank-dispute-service',
  'payments_sepa-instant-service':                 'openbank-sepa-instant-service',
  'infrastructure_postgresql':                     'openbank-postgres',
  'infrastructure_kafka':                          'openbank-kafka',
  'infrastructure_keycloak-(iam)':                 'openbank-keycloak',
  'infrastructure_valkey-(cache)':                 'openbank-valkey',
  'infrastructure_vault-(secrets)':                'openbank-vault',
  'infrastructure_schema-registry':                'openbank-schema-registry',
  'observability_grafana':                         'openbank-grafana',
  'observability_prometheus':                      'openbank-prometheus',
  'observability_loki':                            'openbank-loki',
  'observability_tempo':                           'openbank-tempo',
  'ai---automation_agent-service-(mcp)':           'openbank-agent-service',
}

interface GatusEndpoint {
  key: string
  results: { success: boolean }[]
}

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5000)
    const res = await fetch(`${GATUS_URL}/api/v1/endpoints/statuses`, {
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(t)

    if (!res.ok) throw new Error(`Gatus returned ${res.status}`)

    const endpoints: GatusEndpoint[] = await res.json()

    const result: Record<string, 'healthy' | 'unhealthy'> = {}

    for (const ep of endpoints) {
      const container = GATUS_KEY_TO_CONTAINER[ep.key]
      if (!container) continue
      const last = ep.results.at(-1)
      const status: 'healthy' | 'unhealthy' = last?.success ? 'healthy' : 'unhealthy'
      if (result[container] === 'unhealthy') continue
      result[container] = status
    }

    return NextResponse.json(result, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json(
      { error: 'gatus_unavailable' },
      { status: 503, headers: { 'Cache-Control': 'no-store' } }
    )
  }
}
