// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'fs'
import path from 'path'

export interface AiGovernanceDecision {
  id: string
  title: string
  status: 'built' | 'partial' | 'planned'
  detail: string
}

export interface AiGovernanceComplianceRow {
  framework: string
  requirement: string
  control: string
  status: 'built' | 'partial' | 'planned'
}

export interface AiGovernanceAuditTrail {
  capture: string[]
  pipeline: string[]
  live: string[]
  planned: string[]
}

export interface AiGovernanceSnapshot {
  adrRef: string
  adrStatus: string
  phase: number
  totalPhases: number
  phaseLabel: string
  agentsActing: number
  decisions: AiGovernanceDecision[]
  decisionSummary: { built: number; partial: number; planned: number; total: number }
  compliance: AiGovernanceComplianceRow[]
  auditTrail: AiGovernanceAuditTrail
  facts: Record<string, unknown>
}

function snapshotFile(): string {
  return process.env.OPENBANK_AI_GOVERNANCE_SNAPSHOT
    ?? path.resolve(process.cwd(), 'ai-governance-snapshot.json')
}

let cache: AiGovernanceSnapshot | null = null

export function loadAiGovernanceSnapshot(): AiGovernanceSnapshot {
  if (cache) return cache
  const file = snapshotFile()
  try {
    cache = JSON.parse(readFileSync(file, 'utf-8')) as AiGovernanceSnapshot
    return cache
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`Failed to load AI governance snapshot from ${file}: ${message}`)
  }
}
