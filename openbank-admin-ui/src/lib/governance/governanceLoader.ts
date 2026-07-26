// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Server-only loader for the code-derived governance manifest (ADR-0071). Reads the
// baked governance.json (generate-governance.mjs ← per-service governance.yaml) and
// projects it onto the GovernanceManifestEntry shape consumers expect. This REPLACES
// the hand-edited GOVERNANCE_MANIFEST array that used to live in manifest.ts
// (CLAUDE.md rule #7 — derived, never hand-edited).
//
// Server-only: it touches `fs`, so never import it from a 'use client' component.
// Client surfaces read the same data over HTTP via /api/services/governance instead.
// Runtime Flyway current/drift need a live DB (none yet) → honest null / 'unknown'.

// Server-only: imports `fs`, so a 'use client' component importing this fails the
// build. (No `server-only` package dep added — the fs import is the de-facto guard.)
import { readFileSync } from 'fs'
import path from 'path'
import type { GovernanceManifestEntry } from './manifest'

function governanceFile(): string {
  return process.env.OPENBANK_GOVERNANCE ?? path.resolve(process.cwd(), 'governance.json')
}

let cache: GovernanceManifestEntry[] | null = null

export function getGovernanceManifest(): GovernanceManifestEntry[] {
  if (cache) return cache
  try {
    const parsed = JSON.parse(readFileSync(governanceFile(), 'utf-8')) as {
      services?: Array<Partial<GovernanceManifestEntry>>
    }
    cache = (parsed.services ?? []).map(s => ({
      serviceName: s.serviceName!,
      dataDomain: s.dataDomain!,
      primaryDatastore: s.primaryDatastore!,
      databaseName: s.databaseName ?? null,   // null = declared ownsNoDatabase (ADR-0071/ADR-0196)
      databaseNameEvidence: s.databaseNameEvidence ?? null,
      ownsNoDatabase: s.ownsNoDatabase,
      dataLineageRole: s.dataLineageRole!,
      flywayDeclaredVersion: s.flywayDeclaredVersion ?? '',
      flywayCurrentVersion: null,        // runtime, no live-DB integration yet
      flywayDrift: 'unknown' as const,   // runtime
      dataClassification: s.dataClassification,
      retentionPolicy: s.retentionPolicy,
      evidenceExported: s.evidenceExported,
      lineage: s.lineage,
      databaseLineage: s.databaseLineage,
    }))
  } catch {
    cache = [] // honest empty if the snapshot isn't baked (e.g. clean checkout)
  }
  return cache
}

export function getGovernanceManifestByService(serviceName: string): GovernanceManifestEntry | undefined {
  return getGovernanceManifest().find(e => e.serviceName === serviceName)
}
