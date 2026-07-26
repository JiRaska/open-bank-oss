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

export interface DatabaseLineage {
  ownedDatabases: string[]
  dependentDatabases: string[]
  driftStatus: Record<string, boolean | 'unknown'>
}

export interface GovernanceManifestEntry {
  serviceName: string
  dataDomain: DataDomain
  primaryDatastore: string
  /** The database this module OWNS. Null iff it declared `ownsNoDatabase: true` (ADR-0071/ADR-0196). */
  databaseName: string | null
  /**
   * Whether `databaseName` was confirmed against a datasource URL in the tree ('derived') or is
   * an unconfirmed claim ('declared-only'). Null when there is no databaseName to verify.
   */
  databaseNameEvidence?: 'derived' | 'declared-only' | null
  /**
   * True iff the module asserted `ownsNoDatabase: true`. NOT the same as "stateless" — such a
   * module may still hold durable Redis state (customer-edge keeps passkeys there).
   */
  ownsNoDatabase?: true
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
  databaseLineage?: DatabaseLineage
}


// ── ADR-0071 ─────────────────────────────────────────────────────────────────
// The hand-edited GOVERNANCE_MANIFEST array that used to live here is GONE. The
// manifest is now code-derived (per-service governance.yaml → governance.json).
// - server consumers: import getGovernanceManifest from './governanceLoader'
// - client consumers: fetch '/api/services/governance'
// This file is now types-only (no data, no fs) so it stays client-bundle-safe.
