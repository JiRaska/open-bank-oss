// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared loader for the enforced agent registry (openbank-libs/governance/agents.yaml,
// ADR-0031 D1). Bundled into the image the same way as agents.yaml itself (Dockerfile
// COPY). Factored out of /api/iaops/governance so the per-agent detail route
// (/api/iaops/agents/[agentId]) can reuse the same parsing without a second source
// of truth for "what does agents.yaml actually say".

import { existsSync, promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

// Normalises a tools.allow / tools.deny list that may contain either flat strings
// (standard agents) or tier-object entries (e.g. finops-agent uses {tier, resources}).
function normalizeToolList(items: unknown[]): string[] {
  return items.flatMap(item => {
    if (typeof item === 'string') return [item]
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      const tier = typeof o.tier === 'string' ? o.tier : ''
      const resources = Array.isArray(o.resources) ? (o.resources as unknown[]).map(String) : []
      return resources.length ? resources.map(r => `${tier}:${r}`) : tier ? [tier] : []
    }
    return []
  })
}

function agentsFile(): string {
  // Explicit override, set in the image (Dockerfile: OPENBANK_AGENTS_FILE=/app/agents.yaml).
  if (process.env.OPENBANK_AGENTS_FILE) return process.env.OPENBANK_AGENTS_FILE
  // Image-baked copy next to the standalone server.
  const baked = path.resolve(process.cwd(), 'agents.yaml')
  if (existsSync(baked)) return baked
  // Local dev and vitest: the source of truth one level up in the repo tree. Without this the
  // registry reads as UNAVAILABLE outside the image — the same fallback compliance.ts already
  // makes for compliance-controls.yaml, which sits in that very directory.
  return path.resolve(process.cwd(), '..', 'openbank-libs', 'governance', 'agents.yaml')
}

interface ParsedAgents {
  defaults?: Record<string, unknown>
  runtime?: Record<string, unknown>
  model_gateway?: Record<string, unknown>
  tool_tiers?: Record<string, string[]>
  case_classes?: { classes?: Record<string, unknown> }
  agents?: Record<string, unknown>[]
}

export interface AgentSchedule { daily: string | null; reactive: string | null }

export interface AgentCharter {
  id: string; plane: string; charter: string; owns: string[]; skills: string[]
  dataRead: string[]; pii: string; toolsAllow: string[]; toolsDeny: string[]
  requiresHuman: string[]; tokensPerRun: number | null; runsPerDay: number | null
  caseCapabilities: string[]
  /** Only finops-agent/devops-agent declare this today — most agents run on-demand, not on a clock. */
  schedule: AgentSchedule | null
}

export interface AgentCharterRegistry {
  available: boolean
  defaults: Record<string, unknown>
  agents: AgentCharter[]
  toolTiers: Record<string, string[]>
  runtime: Record<string, unknown>
  modelGateway: Record<string, unknown>
  caseClasses: string[]
}

async function readRaw(): Promise<{ available: boolean; data: ParsedAgents | null }> {
  try {
    const raw = await fs.readFile(agentsFile(), 'utf-8')
    const data = parseYaml(raw) as ParsedAgents
    if (data && Array.isArray(data.agents)) return { available: true, data }
    return { available: false, data: null }
  } catch {
    return { available: false, data: null }
  }
}

/** Parse the bundled agents.yaml into the view-model both the roster and the per-agent detail route consume. */
export async function loadAgentCharters(): Promise<AgentCharterRegistry> {
  const { available, data: d } = await readRaw()
  const defaults = (d?.defaults ?? {}) as Record<string, unknown>

  const agents: AgentCharter[] = (d?.agents ?? []).map(a => {
    const ag = a as Record<string, unknown>
    const tools = (ag.tools ?? {}) as Record<string, unknown>
    const limits = (ag.limits ?? {}) as Record<string, unknown>
    const dataScope = (ag.data_scope ?? {}) as Record<string, unknown>
    const scheduleRaw = ag.schedule as Record<string, unknown> | undefined
    const schedule: AgentSchedule | null = scheduleRaw
      ? {
          daily: typeof scheduleRaw.daily === 'string' ? scheduleRaw.daily : null,
          reactive: typeof scheduleRaw.reactive === 'string' ? scheduleRaw.reactive : null,
        }
      : null
    return {
      id: String(ag.id ?? 'unknown'),
      plane: String(ag.plane ?? '—'),
      charter: String(ag.charter ?? ''),
      owns: Array.isArray(ag.owns) ? (ag.owns as string[]) : [],
      skills: Array.isArray(ag.skills) ? (ag.skills as string[]) : [],
      dataRead: Array.isArray(dataScope.read) ? (dataScope.read as string[]) : [],
      pii: String(dataScope.pii ?? defaults.pii ?? 'masked'),
      toolsAllow: Array.isArray(tools.allow) ? normalizeToolList(tools.allow as unknown[]) : [],
      toolsDeny: Array.isArray(tools.deny) ? normalizeToolList(tools.deny as unknown[]) : [],
      requiresHuman: Array.isArray(ag.requires_human) ? (ag.requires_human as unknown[]).map(r =>
        typeof r === 'string' ? r : Object.entries(r as Record<string, unknown>).map(([k, v]) => `${k}: ${v}`).join(' ')) : [],
      tokensPerRun: typeof limits.tokens_per_run === 'number' ? limits.tokens_per_run : null,
      runsPerDay: typeof limits.runs_per_day === 'number' ? limits.runs_per_day : null,
      caseCapabilities: Array.isArray(ag.case_capabilities) ? (ag.case_capabilities as unknown[]).map(String) : [],
      schedule,
    }
  })

  return {
    available,
    defaults,
    agents,
    toolTiers: (d?.tool_tiers ?? {}) as Record<string, string[]>,
    runtime: (d?.runtime ?? {}) as Record<string, unknown>,
    modelGateway: (d?.model_gateway ?? {}) as Record<string, unknown>,
    caseClasses: Object.keys(d?.case_classes?.classes ?? {}),
  }
}

export async function loadAgentCharter(id: string): Promise<AgentCharter | null> {
  const { agents } = await loadAgentCharters()
  return agents.find(a => a.id === id) ?? null
}
