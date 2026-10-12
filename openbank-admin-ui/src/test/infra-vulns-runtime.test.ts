// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { imageDigest, liveDigests, normalizedRepository, verifiedRuntimeScans, type RuntimeSnapshot } from '@/lib/infra-vulns-runtime'

const A = `sha256:${'a'.repeat(64)}`
const B = `sha256:${'b'.repeat(64)}`
const hash = 'c'.repeat(64)
const now = Date.parse('2026-10-12T12:00:00Z')
const snapshot = (): RuntimeSnapshot => ({
  schema: 'openbank.infra-vulns.runtime/v2', scannedAt: '2026-10-12T11:00:00Z',
  scanner: { name: 'grype', version: '0.118.0',
    db: { schemaVersion: 6, sha256: hash, fetchedAt: '2026-10-12T10:59:00Z' } },
  images: { postgres: { scans: [
    { digest: A, reportSha256: hash, critical: 1, high: 0, medium: 2, low: 0, total: 3 },
    { digest: B, reportSha256: hash, critical: 0, high: 1, medium: 0, low: 0, total: 1 },
  ] } },
})

describe('runtime Grype source identity', () => {
  it('reads registry manifest digest from a ready pod and preserves rollout digests', () => {
    const pod = (digest: string) => ({ status: { phase: 'Running', conditions: [{ type: 'Ready', status: 'True' }],
      containerStatuses: [{ name: 'database', imageID: `docker-pullable://registry/db@${digest}`, ready: true }] },
      spec: { containers: [{ name: 'database', image: 'ghcr.io/cloudnative-pg/postgresql:18' }] } })
    expect(liveDigests([pod(B), pod(A)], 'ghcr.io/cloudnative-pg/postgresql')).toEqual([A, B])
    expect(imageDigest(`containerd://${A}`)).toBeNull()
    expect(() => liveDigests([pod(A), { ...pod(B), status: { ...pod(B).status,
      containerStatuses: [{ name: 'database', imageID: `containerd://${B}`, ready: true }] } }],
    'ghcr.io/cloudnative-pg/postgresql')).toThrow()
  })

  it('matches exact normalized repositories, never helper or proxy substring decoys', () => {
    expect(normalizedRepository('ghcr.io/cloudnative-pg/postgresql:18')).toBe('ghcr.io/cloudnative-pg/postgresql')
    expect(normalizedRepository('ghcr.io/cloudnative-pg/postgresql-helper:18')).not.toBe('ghcr.io/cloudnative-pg/postgresql')
    expect(normalizedRepository('ghcr.io/kafbat/kafka-ui-proxy:1')).not.toBe('ghcr.io/kafbat/kafka-ui')
    expect(normalizedRepository('docker.io/valkey/valkey:8')).toBe('docker.io/valkey/valkey')
    expect(normalizedRepository('123456789012.dkr.ecr.eu-west-1.amazonaws.com/ghcr/cloudnative-pg/postgresql:18'))
      .toBe('ghcr.io/cloudnative-pg/postgresql')
  })

  it('accepts complete matching digests and rejects stale, partial, legacy and invalid provenance', () => {
    expect(verifiedRuntimeScans(snapshot(), 'postgres', [A, B], now)).toHaveLength(2)
    expect(verifiedRuntimeScans(snapshot(), 'postgres', [A], now)).toBeNull()
    expect(verifiedRuntimeScans(snapshot(), 'postgres', [A, `sha256:${'d'.repeat(64)}`], now)).toBeNull()
    expect(verifiedRuntimeScans(snapshot(), 'postgres', null, now)).toBeNull()
    const old = snapshot(); old.schema = 'openbank.infra-vulns/v1'
    expect(verifiedRuntimeScans(old, 'postgres', [A, B], now)).toBeNull()
    const stale = snapshot(); stale.scannedAt = '2026-10-10T00:00:00Z'
    expect(verifiedRuntimeScans(stale, 'postgres', [A, B], now)).toBeNull()
    const staleDb = snapshot(); staleDb.scanner.db.fetchedAt = '2026-10-01T00:00:00Z'
    expect(verifiedRuntimeScans(staleDb, 'postgres', [A, B], now)).toBeNull()
    const missingDb = snapshot(); missingDb.scanner.db.sha256 = ''
    expect(verifiedRuntimeScans(missingDb, 'postgres', [A, B], now)).toBeNull()
    const duplicate = snapshot(); duplicate.images.postgres.scans[1].digest = A
    expect(verifiedRuntimeScans(duplicate, 'postgres', [A, B], now)).toBeNull()
  })
})
