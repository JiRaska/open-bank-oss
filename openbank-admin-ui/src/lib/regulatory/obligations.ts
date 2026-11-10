// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-only loader for the baked regulatory obligations register (ADR-0367).
// Source of truth: openbank-libs/governance/regulatory-obligations.yaml, baked by
// scripts/generate-regulatory-obligations.mjs. Never import from a 'use client' component.

import { readFileSync } from 'fs'
import path from 'path'

import type { ObligationRegister } from './obligation-types'

function registerFile(): string {
  return process.env.OPENBANK_REGULATORY_OBLIGATIONS ?? path.resolve(process.cwd(), 'regulatory-obligations.json')
}

/**
 * A missing or unreadable file yields `available: false`, never an empty list: "no obligations"
 * and "register not shipped" must not render the same.
 */
export function getRegulatoryObligations(): ObligationRegister {
  try {
    const parsed = JSON.parse(readFileSync(registerFile(), 'utf-8')) as Omit<ObligationRegister, 'available'>
    const obligations = parsed.obligations ?? []
    return { available: obligations.length > 0, areas: parsed.areas ?? [], authorities: parsed.authorities ?? [], obligations }
  } catch {
    return { available: false, areas: [], authorities: [], obligations: [] }
  }
}
