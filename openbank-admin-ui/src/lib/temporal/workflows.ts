// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { CreditCard, Globe, Repeat2, BarChart2 } from 'lucide-react'

// The money-path saga workflows migrated to Temporal (ADR-0100). Shared by the
// Temporal overview page and the Temporal flow page. Each `steps` list is the
// documented saga — the last step is the compensation branch. Curated (the sagas
// are documented, not scraped from live histories); live throughput comes from
// /api/temporal/status (Prometheus).
export type MoneyWorkflow = {
  icon: typeof CreditCard
  color: string
  serviceCs: string
  serviceEn: string
  svc: string
  workflowCs: string
  stepsCs: string[]
  stepsEn: string[]
}

export const MONEY_WORKFLOWS: MoneyWorkflow[] = [
  {
    icon: CreditCard,
    color: '#6366f1',
    serviceCs: 'Tuzemské platby',
    serviceEn: 'Domestic payments',
    svc: 'openbank-domestic-payment-service',
    workflowCs: 'DomesticPaymentWorkflow',
    stepsCs: ['Validace IBAN + limit', 'Rezervace sald', 'Odeslání na CERTIS/instant rail', 'Potvrzení settlement', 'Kompenzace při odmítnutí'],
    stepsEn: ['IBAN + limit validation', 'Balance reservation', 'Submit to CERTIS/instant rail', 'Settlement confirmation', 'Compensation on rejection'],
  },
  {
    icon: Globe,
    color: '#8b5cf6',
    serviceCs: 'SEPA platby',
    serviceEn: 'SEPA payments',
    svc: 'openbank-sepa-payment-service',
    workflowCs: 'SepaPaymentWorkflow',
    stepsCs: ['Validace BIC + SEPA pravidla', 'ISO 20022 pacs.008 sestavení', 'Odeslání do SWIFT/EBA', 'SCT/SCT Inst potvrzení', 'Kompenzace přes pacs.004'],
    stepsEn: ['BIC + SEPA rules validation', 'ISO 20022 pacs.008 assembly', 'Submit to SWIFT/EBA', 'SCT/SCT Inst confirmation', 'Compensation via pacs.004'],
  },
  {
    icon: Repeat2,
    color: '#10b981',
    serviceCs: 'FX konverze',
    serviceEn: 'FX conversions',
    svc: 'openbank-fx-service',
    workflowCs: 'FxConversionWorkflow',
    stepsCs: ['Sestavení kurzu (ECB + spread)', 'G5 screening', 'Odpis zdrojové měny', 'Připsání cílové měny', 'Journal entry pár (ADR-0039)'],
    stepsEn: ['Rate assembly (ECB + spread)', 'G5 screening', 'Debit source currency', 'Credit target currency', 'Journal entry pair (ADR-0039)'],
  },
  {
    icon: BarChart2,
    color: '#f59e0b',
    serviceCs: 'Uzávěrky (EoD/EoM/EoY)',
    serviceEn: 'Closings (EoD/EoM/EoY)',
    svc: 'openbank-statement-service',
    workflowCs: 'StatementCloseWorkflow',
    stepsCs: ['Agregace transakcí dne/měsíce/roku', 'Výpočet úrokových nákladů', 'Export na S3 + Kafka výpis', 'Potvrzení uzávěrky'],
    stepsEn: ['Aggregate daily/monthly/yearly transactions', 'Compute interest charges', 'Export to S3 + Kafka statement', 'Closing confirmation'],
  },
]
