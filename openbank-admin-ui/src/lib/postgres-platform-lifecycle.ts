// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { PinnedComponent } from './platform-versions'

interface LifecycleSnapshot {
  schema?: string
  components?: { id?: string; lifecycle?: { cycles?: { cycle?: string; eol?: string | boolean | null }[] } }[]
}

export function postgresPins(pin: PinnedComponent | null): { version: string; manifests: number }[] {
  if (!pin) return []
  const versions = [{ version: pin.version, manifests: pin.pinnedIn ?? 1 }]
  for (const [version, files] of Object.entries(pin.otherPins ?? {})) {
    versions.push({ version, manifests: files.length })
  }
  return versions.sort((a, b) => b.manifests - a.manifests || a.version.localeCompare(b.version))
}

// The separately generated infra-lifecycle.json is baked into the same runtime image. Its
// endoflife.date PostgreSQL cycles are the existing maintained source for lifecycle dates.
export function postgresEol(version: string, rawSnapshot: unknown): string | null {
  const snapshot = rawSnapshot as LifecycleSnapshot | null
  if (snapshot?.schema !== 'openbank.infra-lifecycle/v1') return null
  const major = version.match(/^(\d+)\./)?.[1]
  if (!major) return null
  if (!Array.isArray(snapshot.components)) return null
  const cycles = snapshot.components.find(c => c?.id === 'postgres')?.lifecycle?.cycles
  if (!Array.isArray(cycles)) return null
  const eol = cycles.find(c => c?.cycle === major)?.eol
  if (typeof eol !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(eol)) return null
  const date = new Date(`${eol}T00:00:00Z`)
  return Number.isNaN(date.getTime()) ? null : date.toISOString().slice(0, 10) === eol ? eol : null
}
