// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Types of the baked regulatory obligations register (ADR-0367). Imports nothing, so a client
// component can use them without pulling `fs` into the browser bundle.

export type CoverageStatus = 'filed-return' | 'bff-only' | 'data-only' | 'missing' | 'external' | 'conditional'
export type CadenceConfidence = 'verified' | 'known-eu' | 'unverified'

export interface Bilingual { cs: string; en: string }

export interface RegulatoryObligation {
  id: string
  area: string
  name: Bilingual
  authority: string
  legalBasis: string
  sources: { law: string | null; spec: string | null }
  cadence: string
  cadenceConfidence: CadenceConfidence
  kind: string
  transport: string
  personalData: string
  applicability: string
  coverage: { status: CoverageStatus; reports: string[]; services: string[]; tracking: string | null }
  note: string | null
}

export interface ObligationRegister {
  available: boolean
  areas: (Bilingual & { id: string })[]
  authorities: (Bilingual & { id: string; url: string | null })[]
  obligations: RegulatoryObligation[]
}

export const COVERAGE_ORDER: CoverageStatus[] = ['missing', 'data-only', 'bff-only', 'filed-return', 'conditional', 'external']
