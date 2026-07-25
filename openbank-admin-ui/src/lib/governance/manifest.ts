// SPDX-License-Identifier: Apache-2.0

export type DataLineageRole = 'producer' | 'consumer' | 'both' | 'internal'
export type DataDomain = 'core' | 'payments' | 'compliance' | 'identity' | 'open-banking' | 'platform'

export interface LineageNode {
  serviceName: string
  relationType: 'api' | 'topic' | 'datastore' | 'unknown'
  description?: string
}

export interface ServiceInterfaces {
  apis: string[]
  topics: string[]
  datastores: string[]
}

export interface SchemaLineage {
  ownedSchemas: string[]
  dependentSchemas: string[]
  driftStatus: Record<string, boolean | 'unknown'>
}

export interface GovernanceManifestEntry {
  serviceName: string
  dataDomain: DataDomain
  primaryDatastore: string
  /** Null iff the module declared `stateless: true` in its governance.yaml — it owns no DB schema (ADR-0071). */
  schemaName: string | null
  /** True iff the module asserted `stateless: true`; absent otherwise. */
  stateless?: true
  dataLineageRole: DataLineageRole
  flywayDeclaredVersion: string
  flywayCurrentVersion: string | null
  flywayDrift: boolean | 'unknown'
  dataClassification?: 'public' | 'internal' | 'confidential' | 'restricted' | 'unknown'
  retentionPolicy?: string
  evidenceExported?: boolean
  lineage?: {
    upstream: LineageNode[]
    downstream: LineageNode[]
    interfaces: ServiceInterfaces
  }
  schemaLineage?: SchemaLineage
}


// ── ADR-0071 ─────────────────────────────────────────────────────────────────
// The hand-edited GOVERNANCE_MANIFEST array that used to live here is GONE. The
// manifest is now code-derived (per-service governance.yaml → governance.json).
// - server consumers: import getGovernanceManifest from './governanceLoader'
// - client consumers: fetch '/api/services/governance'
// This file is now types-only (no data, no fs) so it stays client-bundle-safe.
