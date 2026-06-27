// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side feature-flag catalog loader (ADR-0067). Surfaces the fleet's
// flag-as-code — the per-service flagd ConfigMaps in
// openbank-infra/gitops/components/<svc>/feature-flags.yaml — in the operator
// console as a read-only registry.
//
// READ-ONLY consumer (CLAUDE rule #3 / #7): the admin-ui *displays* the flags;
// the flag-as-code in gitops is the single source of truth (ADR-0067). This
// never writes or hand-maintains flag data — it is DERIVED from the gitops YAML,
// so it cannot rot the way a hand-edited manifest would. Flipping a flag is a
// git change (a future BFF "propose change" → PR), never an admin-ui write.
//
// Sourcing mirrors the governance-docs bundle pattern (lib/governance/docs.ts):
// openbank-infra/ is .dockerignore'd out of the runtime fs, so the Dockerfile's
// `flags-collector` stage bakes each feature-flags.yaml into an immutable
// /app/flags-bundle/<svc>.yaml. First existing candidate wins:
//   1. $OPENBANK_FLAGS_BUNDLE   (explicit override; set in the image)
//   2. <cwd>/flags-bundle       (image-baked bundle, flat <svc>.yaml files)
//   3. <cwd>/../openbank-infra/gitops/components  (local dev: repo tree)

import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

// Money-path services (source: openbank-libs/governance/rules.yaml →
// money_path_services). A flag on one of these is classified MONEY_PATH and a
// flip would require four-eyes (ADR-0067 §5, enforcement tracked in #419).
const MONEY_PATH = new Set([
  'ledger-service', 'transaction-service', 'account-service', 'balance-service',
  'sepa-payment', 'sepa-instant', 'domestic-payment', 'clearing-service',
  'swift-service', 'fx-service', 'lending-service', 'sca-service', 'consent-service',
  'fraud-service',
])

export type FlagClassification = 'money-path' | 'feature'

export interface FlagMeta {
  /** Owning service, e.g. "party-service". */
  service: string
  /** Flag key as evaluated in code / flag-as-code, e.g. "party-list-enriched". */
  key: string
  /** flagd state: ENABLED | DISABLED. */
  state: string
  /** Default variant name, e.g. "off" / "on". */
  defaultVariant: string
  /** Variant name → value map. */
  variants: Record<string, unknown>
  /** True when a targeting block is present (percentage / A/B rollout). */
  targeted: boolean
  /** Governance classification derived from the owning service. */
  classification: FlagClassification
}

async function isDir(p: string): Promise<boolean> {
  try { return (await fs.stat(p)).isDirectory() } catch { return false }
}
async function isFile(p: string): Promise<boolean> {
  try { return (await fs.stat(p)).isFile() } catch { return false }
}

/** Resolve { dir, mode } for the flag source, or null when no corpus is present. */
async function flagsSource(): Promise<{ dir: string; mode: 'bundle' | 'components' } | null> {
  const bundle = process.env.OPENBANK_FLAGS_BUNDLE || path.resolve(process.cwd(), 'flags-bundle')
  if (await isDir(bundle)) return { dir: bundle, mode: 'bundle' }
  const components = path.resolve(process.cwd(), '..', 'openbank-infra', 'gitops', 'components')
  if (await isDir(components)) return { dir: components, mode: 'components' }
  return null
}

/** List the feature-flags.yaml files to parse, given the resolved source. */
async function flagFiles(src: { dir: string; mode: 'bundle' | 'components' }): Promise<string[]> {
  if (src.mode === 'bundle') {
    // Flat <svc>.yaml files baked by the flags-collector stage.
    return (await fs.readdir(src.dir))
      .filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))
      .map(f => path.join(src.dir, f))
  }
  // Local dev: openbank-infra/gitops/components/<svc>/feature-flags.yaml
  const out: string[] = []
  for (const entry of await fs.readdir(src.dir)) {
    const candidate = path.join(src.dir, entry, 'feature-flags.yaml')
    if (await isFile(candidate)) out.push(candidate)
  }
  return out
}

/** Service id from a ConfigMap doc — prefer the standard label, fall back to namespace. */
function serviceOf(cm: Record<string, unknown>): string {
  const meta = (cm.metadata ?? {}) as Record<string, unknown>
  const labels = (meta.labels ?? {}) as Record<string, string>
  return labels['app.kubernetes.io/name'] || (meta.namespace as string) || (meta.name as string) || 'unknown'
}

/** Parse one feature-flags.yaml ConfigMap into FlagMeta rows. Defensive: a malformed
 *  file yields no rows rather than throwing — a registry must not 500 on bad input. */
function parseConfigMap(yamlText: string): FlagMeta[] {
  let cm: Record<string, unknown>
  try {
    cm = parseYaml(yamlText) as Record<string, unknown>
  } catch { return [] }
  if (!cm || typeof cm !== 'object') return []

  const data = (cm.data ?? {}) as Record<string, string>
  const flagsJson = data['flags.json']
  if (!flagsJson) return []

  let parsed: { flags?: Record<string, Record<string, unknown>> }
  try {
    parsed = JSON.parse(flagsJson)
  } catch { return [] }
  const flags = parsed.flags ?? {}

  const service = serviceOf(cm)
  const classification: FlagClassification = MONEY_PATH.has(service) ? 'money-path' : 'feature'

  return Object.entries(flags).map(([key, def]) => ({
    service,
    key,
    state: String(def.state ?? 'UNKNOWN'),
    defaultVariant: String(def.defaultVariant ?? ''),
    variants: (def.variants ?? {}) as Record<string, unknown>,
    targeted: def.targeting != null,
    classification,
  }))
}

/** Load the whole fleet's feature-flag catalog, sorted by service then key. */
export async function loadFlagCatalog(): Promise<FlagMeta[]> {
  const src = await flagsSource()
  if (!src) return []
  const files = await flagFiles(src)
  const all = await Promise.all(
    files.map(async f => parseConfigMap(await fs.readFile(f, 'utf-8'))),
  )
  return all.flat().sort((a, b) => a.service.localeCompare(b.service) || a.key.localeCompare(b.key))
}
