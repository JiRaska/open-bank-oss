// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Typed accessor for platform-versions.json, which scripts/generate-platform-versions.mjs PARSES
// from the infra sources of truth (variables.tf, main.tf, the Loki GitOps app, the EKS lifecycle
// table). UI code must never type a Kubernetes/EKS/Loki version itself: it reads it here, and when
// the snapshot is missing it shows UNKNOWN rather than a guess (guarded by
// src/test/no-hardcoded-platform-versions.guard.test.ts).

export interface EksVersionEntry {
  eks_release: string
  end_of_standard_support: string
  end_of_extended_support: string
}

export interface EksLifecycle {
  _meta: { pricing_note?: string; last_refreshed: string }
  versions: Record<string, EksVersionEntry>
}

export interface PlatformVersions {
  schema: string
  kubernetesVersion: string
  nodeGroup: { instanceType: string; desiredSize: number; minSize: number; maxSize: number }
  loki: { chartVersion: string; appVersion: string }
  eksLifecycle: EksLifecycle
  sources: Record<string, string>
}

export const UNKNOWN_VERSION = 'unknown (snapshot missing)'

function load(): PlatformVersions | null {
  try {
    // Resolved at build time; a missing snapshot degrades to the explicit unknown state.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const data = require('../../platform-versions.json') as PlatformVersions
    return data?.kubernetesVersion ? data : null
  } catch {
    return null
  }
}

export const platformVersions: PlatformVersions | null = load()

export const eksVersion = (): string => platformVersions?.kubernetesVersion ?? UNKNOWN_VERSION
export const lokiChartVersion = (): string => platformVersions?.loki.chartVersion ?? UNKNOWN_VERSION
export const lokiAppVersion = (): string => platformVersions?.loki.appVersion ?? UNKNOWN_VERSION

export function nodeGroupSummary(): string {
  const n = platformVersions?.nodeGroup
  return n ? `${n.instanceType}, desired ${n.desiredSize} (min ${n.minSize}, max ${n.maxSize})` : UNKNOWN_VERSION
}
