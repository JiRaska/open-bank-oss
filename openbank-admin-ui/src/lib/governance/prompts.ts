// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Communication Studio — phase 1 loader (ADR-0285 D7).
//
// WHAT THIS IS, AND WHAT IT DELIBERATELY IS NOT
//   ADR-0285 splits every conversational prompt into an immutable git CORE and a
//   business-editable STYLE + PLAYBOOK layer served by openbank-communication-service.
//   Phase 1 ships only the read-only projection: what does the bank's bot actually say
//   today, and which part of it will a business editor never be allowed to touch. The
//   editable layers do not exist yet, so this loader does not invent them — it reports
//   `editableLayers: 'not-published'` and the page says which phase lands them. A UI that
//   showed an empty "style" editor would read as a shipped capability that silently does
//   nothing, which is the failure mode ADR-0285's own context section is about.
//
// SOURCE OF TRUTH
//   openbank-libs/governance/prompts/ — the ADR-0148 prompt registry: `registry.yaml`
//   (per-charter coverage claim with a closed status vocabulary) plus one directory of
//   `*.md` prompt files per charter. This module PARSES that tree; it never carries its own
//   copy of a prompt. The plane/charter metadata is joined from agents.yaml through the
//   existing `agentCharters` loader rather than re-parsed here, so "what does agents.yaml
//   say" keeps exactly one reader.
//
// SOURCING (mirrors lib/governance/docs.ts and flags.ts)
//   openbank-libs/ is .dockerignore'd out of the runtime fs, so the Dockerfile's
//   `governance-collector` stage bakes the registry into /app/governance-bundle/prompts.
//   First existing candidate wins:
//     1. $OPENBANK_PROMPTS_DIR                      (explicit override)
//     2. $OPENBANK_GOVERNANCE_DOCS/prompts          (bundle root override, same var as docs.ts)
//     3. <cwd>/governance-bundle/prompts            (image-baked bundle)
//     4. <cwd>/../openbank-libs/governance/prompts  (local dev: repo tree)

import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'
import { loadAgentCharters } from '@/lib/governance/agentCharters'

/** registry.yaml's closed status vocabulary (`.github/scripts/check-prompt-registry.py`). */
export type PromptCoverageStatus = 'registered' | 'pending' | 'external' | 'not-applicable'

const COVERAGE_STATUSES: readonly PromptCoverageStatus[] = [
  'registered', 'pending', 'external', 'not-applicable',
]

/**
 * State of the ADR-0285 editable layers for one persona. Phase 1 can only ever report
 * `not-published`: communication-service is unbuilt, so nothing has been published and the
 * runtime composes the git core alone. `published` becomes reachable in phase 2 — the value
 * exists here so the page renders the real state rather than assuming one.
 */
export type EditableLayerState = 'not-published' | 'published'

export interface PromptVersion {
  /** Registry file stem, e.g. "system.v2" — the version label a reviewer cites. */
  id: string
  /** The prompt text as committed. Read-only: this is core, not an editable layer. */
  text: string
  /** Characters — the size an editor's style layer would be added ON TOP of (ADR-0285 D3 cap). */
  chars: number
}

export interface Persona {
  /** Charter id from registry.yaml / agents.yaml, e.g. "customer-copilot". */
  id: string
  /** ADR-0148 coverage claim for this charter. */
  status: PromptCoverageStatus
  /** agents.yaml plane ("customer" | "control" | "development" | …), or null when unjoined. */
  plane: string | null
  /** One-line charter text from agents.yaml, or null. */
  charter: string | null
  /** Where the live prompt is built in code, as registry.yaml records it. */
  source: string | null
  /** Why a non-registered charter has no prompt here (registry.yaml `reason:`). */
  reason: string | null
  /** Issue/ADR that lands a `pending` charter's wiring. */
  blockedBy: string | null
  /** Runtime-substituted placeholders declared for the templated prompts. */
  placeholders: string[]
  /** Registered prompt versions, newest-looking last (registry order is authoritative). */
  versions: PromptVersion[]
  /** ADR-0285 D1: the editable layers, once communication-service serves them. */
  editableLayers: EditableLayerState
}

export interface PromptRegistryProjection {
  /** False when the registry tree is absent (not baked into the image, not in the repo). */
  available: boolean
  /** registry.yaml `schema_version`, or null when unavailable. */
  schemaVersion: number | null
  /** registry.yaml `related_adrs`, verbatim. */
  relatedAdrs: string[]
  personas: Persona[]
}

async function isDir(p: string): Promise<boolean> {
  try { return (await fs.stat(p)).isDirectory() } catch { return false }
}

async function registryDir(): Promise<string | null> {
  const bundleRoot = process.env.OPENBANK_GOVERNANCE_DOCS
  const candidates = [
    process.env.OPENBANK_PROMPTS_DIR,
    bundleRoot && path.join(bundleRoot, 'prompts'),
    path.resolve(process.cwd(), 'governance-bundle', 'prompts'),
    path.resolve(process.cwd(), '..', 'openbank-libs', 'governance', 'prompts'),
  ].filter((c): c is string => Boolean(c))
  for (const c of candidates) {
    if (await isDir(c)) return c
  }
  return null
}

function asString(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : null
}

function asStringList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}

function coverageStatus(v: unknown): PromptCoverageStatus {
  const s = typeof v === 'string' ? v.trim() : ''
  return (COVERAGE_STATUSES as readonly string[]).includes(s)
    ? (s as PromptCoverageStatus)
    // An unknown value is a registry the guard would already have rejected; treat it as the
    // most conservative reading rather than inventing coverage the ADR-0148 gate never granted.
    : 'pending'
}

/**
 * Read one registered prompt file. A listed-but-missing file cannot happen on a green main
 * (check-prompt-registry.py fails on it), but the image bundle is a copy and a copy can be
 * incomplete — so a missing file yields no version rather than throwing the whole page away.
 */
async function readPrompt(dir: string, charterId: string, promptId: string): Promise<PromptVersion | null> {
  // charterId/promptId come from registry.yaml, not from a request — but this file is read by a
  // route handler, so the traversal guard is cheap insurance against that changing later.
  if (!/^[a-z0-9][a-z0-9.-]*$/i.test(charterId) || !/^[a-z0-9][a-z0-9.-]*$/i.test(promptId)) return null
  try {
    const text = await fs.readFile(path.join(dir, charterId, `${promptId}.md`), 'utf8')
    return { id: promptId, text, chars: text.length }
  } catch {
    return null
  }
}

/**
 * Project the ADR-0148 prompt registry as the Communication Studio's persona list.
 *
 * Read-only by construction: there is no write path in this module, and ADR-0285 D1 says the
 * core is never writable through any API. The editable layers arrive in phase 2 from
 * openbank-communication-service, not from this file.
 */
export async function loadPromptRegistry(): Promise<PromptRegistryProjection> {
  const dir = await registryDir()
  if (!dir) return { available: false, schemaVersion: null, relatedAdrs: [], personas: [] }

  let parsed: Record<string, unknown>
  try {
    parsed = (parseYaml(await fs.readFile(path.join(dir, 'registry.yaml'), 'utf8')) ?? {}) as Record<string, unknown>
  } catch {
    return { available: false, schemaVersion: null, relatedAdrs: [], personas: [] }
  }

  const charters = Array.isArray(parsed.charters) ? (parsed.charters as Record<string, unknown>[]) : []

  // agents.yaml is the single source for plane/charter prose. When it is unavailable the
  // personas still render — with those two fields null — rather than the page failing whole.
  const registry = await loadAgentCharters().catch(() => null)
  const byId = new Map((registry?.agents ?? []).map(a => [a.id, a]))

  const personas: Persona[] = []
  for (const entry of charters) {
    const id = asString(entry.id)
    if (!id) continue
    const status = coverageStatus(entry.status)
    const versions: PromptVersion[] = []
    if (status === 'registered') {
      for (const promptId of asStringList(entry.prompts)) {
        const version = await readPrompt(dir, id, promptId)
        if (version) versions.push(version)
      }
    }
    const agent = byId.get(id)
    personas.push({
      id,
      status,
      plane: agent?.plane ?? null,
      charter: agent?.charter ?? null,
      source: asString(entry.source),
      reason: asString(entry.reason),
      blockedBy: asString(entry.blocked_by),
      placeholders: asStringList(entry.placeholders),
      versions,
      editableLayers: 'not-published',
    })
  }

  const schemaVersion = typeof parsed.schema_version === 'number' ? parsed.schema_version : null
  return {
    available: true,
    schemaVersion,
    relatedAdrs: asStringList(parsed.related_adrs),
    personas,
  }
}

/** One persona by id, or null. Same projection as the list — no second parsing path. */
export async function loadPersona(id: string): Promise<Persona | null> {
  const projection = await loadPromptRegistry()
  return projection.personas.find(p => p.id === id) ?? null
}
