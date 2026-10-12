// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Client-safe shapes + pure helpers for the merged LIVE (Prometheus) / DECLARED (gitops, terraform)
// platform-version view served by /api/platform-versions. No version is typed here: every value
// arrives from a live query or from the generated platform-versions.json.

export type DriftStatus = 'match' | 'drift' | 'live-unavailable' | 'not-declared' | 'unknown'

export interface LiveVersion { version: string; count: number }

export interface VersionItem {
  key: string
  declared: string | null
  declaredSource: string | null
  /** Distinct running versions with pod/node counts; null when the live source was unavailable. */
  live: LiveVersion[] | null
  status: DriftStatus
}

export interface NodeSummary {
  ready: number | null
  total: number | null
  byType: { instanceType: string; capacityType: string | null; count: number }[]
}

export interface Inventory {
  gitops: { apps: string[]; components: string[]; clickhouse: boolean; istio: boolean; cilium: boolean }
  /** Environments whose IaC instantiates the resource type; empty = not defined/used anywhere. */
  iac: Record<'route53' | 'cloudfront' | 'acm' | 'alb' | 'cloudtrail' | 'awsConfig' | 'objectLock', string[]>
}

export interface PlatformView {
  fetchedAt: string
  liveAvailable: boolean
  liveSource: 'live (Prometheus)'
  liveError: string | null
  items: Record<string, VersionItem>
  nodes: NodeSummary | null
  /** Live Running pods per namespace / ready DaemonSet pods per `ns/name`; null when Prometheus is down. */
  podsRunningByNamespace: Record<string, number> | null
  daemonSetsReady: Record<string, number> | null
  /** Build-time facts derived from gitops/apps and IaC (see scripts/lib/platform-versions.mjs). */
  inventory: Inventory | null
  declaredNodeGroup: { instanceType: string; desiredSize: number; minSize: number; maxSize: number } | null
  eksLifecycleSource: 'live (endoflife.date)' | 'snapshot'
}

export const LIVE_UNAVAILABLE_NOTE = 'declared — live unavailable'

/** Compare a declared version with the live set. Live matches only when it is exactly {declared}. */
export function compareVersions(
  declared: string | null,
  live: LiveVersion[] | null,
  normalize: (v: string) => string = v => v,
): DriftStatus {
  if (live === null || live.length === 0) return declared === null ? 'unknown' : 'live-unavailable'
  if (declared === null) return 'not-declared'
  const distinct = new Set(live.map(l => normalize(l.version)))
  return distinct.size === 1 && distinct.has(normalize(declared)) ? 'match' : 'drift'
}

export interface FormattedVersion {
  primary: string
  note: string | null
  drift: boolean
}

export const UNKNOWN = 'unknown'

/** Primary value is the LIVE version(s); declared is shown alongside; drift is flagged. */
export function formatVersion(item: VersionItem | undefined): FormattedVersion {
  if (!item) return { primary: UNKNOWN, note: null, drift: false }
  const live = item.live && item.live.length > 0 ? item.live.map(l => l.version).join(' + ') : null
  switch (item.status) {
    case 'match':
      return { primary: live ?? UNKNOWN, note: `declared ${item.declared}`, drift: false }
    case 'drift':
      return { primary: live ?? UNKNOWN, note: `declared ${item.declared}`, drift: true }
    case 'live-unavailable':
      return { primary: item.declared ?? UNKNOWN, note: LIVE_UNAVAILABLE_NOTE, drift: false }
    case 'not-declared':
      return { primary: live ?? UNKNOWN, note: null, drift: false }
    default:
      return { primary: UNKNOWN, note: null, drift: false }
  }
}

/** One-line text for prose: "1.36.0 (declared 1.36) [DRIFT]" or "1.36 (declared — live unavailable)". */
export function versionText(item: VersionItem | undefined): string {
  const f = formatVersion(item)
  const note = f.note ? ` (${f.note})` : ''
  return `${f.primary}${note}${f.drift ? ' [DRIFT]' : ''}`
}

export function nodesText(view: PlatformView | null): string {
  const n = view?.nodes
  const declared = view?.declaredNodeGroup
  const decl = declared
    ? `declared ${declared.instanceType} x${declared.desiredSize} (min ${declared.minSize}, max ${declared.maxSize})`
    : null
  if (!n || n.total === null) return decl ? `${decl} — live unavailable` : UNKNOWN
  const types = n.byType.map(t => `${t.count} x ${t.instanceType}${t.capacityType ? ` ${t.capacityType}` : ''}`).join(', ')
  const ready = n.ready === null ? '?' : String(n.ready)
  return `${ready}/${n.total} Ready${types ? ` (${types})` : ''}${decl ? `; ${decl}` : ''}`
}
