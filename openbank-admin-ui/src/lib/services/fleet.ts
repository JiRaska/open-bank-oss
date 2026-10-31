// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side loader for THE service registry. The set of services is read from the baked,
// code-derived catalog (`catalog.json`, scripts/generate-catalog.mjs — the same artifact
// /api/catalog/services serves and /services derives its fleet count from), never from a list
// typed into this app. Server code only (node:fs); browser code gets the same data from
// `GET /api/catalog/services` and calls `buildRegistry` itself.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import {
  buildProxyAllowlist,
  buildRegistry,
  findInRegistry,
  type CatalogFleetModule,
  type ServiceEntry,
} from '@/lib/services/registry'

function catalogFile(): string {
  return process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json')
}

let cache: { file: string; registry: ServiceEntry[] } | null = null
const reported = new Set<string>()

/** Log a missing/unparseable catalog once per file, not once per request. */
function reportOnce(file: string, why: string): void {
  if (reported.has(file)) return
  reported.add(file)
  console.error(`[fleet] service catalog unavailable (${why}): ${file}. The derived fleet is EMPTY - health, config and the /api/svc BFF will report no services until catalog.json is baked into the image.`)
}

/**
 * The fleet, derived from catalog.json. An absent or unreadable snapshot yields an EMPTY fleet
 * (callers degrade through the graceful-state rule) - never a fabricated one. The failure is cached
 * for the process (a baked artifact does not appear later) and logged once.
 */
export function getRegistry(): ServiceEntry[] {
  const file = catalogFile()
  if (cache?.file === file) return cache.registry
  let registry: ServiceEntry[] = []
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf-8')) as { services?: CatalogFleetModule[] }
    if (Array.isArray(parsed.services)) registry = buildRegistry(parsed.services)
    else reportOnce(file, 'no services array')
  } catch (err) {
    reportOnce(file, err instanceof Error ? err.message : String(err))
  }
  cache = { file, registry }
  return registry
}

export function findService(id: string): ServiceEntry | undefined {
  return findInRegistry(getRegistry(), id)
}

/** `/api/svc/<key>` allowlist — see buildProxyAllowlist. */
export function getProxyAllowlist(): Record<string, { container: string; port: number }> {
  return buildProxyAllowlist(getRegistry())
}
