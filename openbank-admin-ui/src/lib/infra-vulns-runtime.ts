// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import https from 'node:https'

export const INFRA_CONTAINERS: Record<string, { namespace: string; repository: string }> = {
  postgres: { namespace: 'accounts', repository: 'ghcr.io/cloudnative-pg/postgresql' },
  valkey: { namespace: 'accounts', repository: 'docker.io/valkey/valkey' },
  kafka: { namespace: 'messaging', repository: 'quay.io/strimzi/kafka' },
  'schema-registry': { namespace: 'messaging', repository: 'docker.io/apicurio/apicurio-registry-sql' },
  'kafka-ui': { namespace: 'messaging', repository: 'ghcr.io/kafbat/kafka-ui' },
  keycloak: { namespace: 'iam', repository: 'quay.io/keycloak/keycloak' },
  openbao: { namespace: 'vault', repository: 'quay.io/openbao/openbao' },
  grafana: { namespace: 'observability', repository: 'docker.io/grafana/grafana' },
  prometheus: { namespace: 'observability', repository: 'quay.io/prometheus/prometheus' },
  loki: { namespace: 'observability', repository: 'docker.io/grafana/loki' },
  tempo: { namespace: 'observability', repository: 'docker.io/grafana/tempo' },
  alloy: { namespace: 'observability', repository: 'docker.io/grafana/alloy' },
}

const DIGEST = /^sha256:[a-f0-9]{64}$/
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/
const MAX_SCAN_AGE_MS = 36 * 60 * 60 * 1000

type Pod = {
  status?: { phase?: string; conditions?: { type?: string; status?: string }[];
    containerStatuses?: { name: string; imageID?: string; ready?: boolean }[] }
  spec?: { containers?: { name: string; image: string }[] }
}

export function imageDigest(imageID: unknown): string | null {
  if (typeof imageID !== 'string') return null
  // Kubernetes runtimes can return docker-pullable:// or a plain repository@digest.
  // A bare containerd://sha256 is a container ID, not a registry manifest digest.
  const match = imageID.match(/^[^\s]+@(sha256:[a-f0-9]{64})$/)
  return match?.[1] ?? null
}

export function normalizedRepository(image: unknown): string | null {
  if (typeof image !== 'string' || !/^[a-z0-9][a-z0-9._:/@-]*$/.test(image)) return null
  const withoutDigest = image.split('@', 1)[0]
  const colon = withoutDigest.lastIndexOf(':')
  const ref = colon > withoutDigest.lastIndexOf('/') ? withoutDigest.slice(0, colon) : withoutDigest
  const parts = ref.split('/')
  if (parts.length < 2) return null
  const host = parts[0].includes('.') || parts[0].includes(':') ? parts.shift()! : 'docker.io'
  // Private ECR pull-through paths carry an upstream registry marker. Normalize
  // only that explicit form; unknown mirrors stay unverified.
  if (/^[a-z0-9-]+\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com$/.test(host)) {
    const origin = parts.shift()
    if (origin === 'ghcr') return `ghcr.io/${parts.join('/')}`
    if (origin === 'quay') return `quay.io/${parts.join('/')}`
    if (origin === 'dockerhub') return `docker.io/${parts.join('/')}`
    return null
  }
  return `${host}/${parts.join('/')}`
}

export function liveDigests(pods: Pod[], repository: string): string[] {
  const digests = new Set<string>()
  let matching = 0
  for (const pod of pods) {
    if (pod.status?.phase !== 'Running' ||
        !pod.status.conditions?.some(c => c.type === 'Ready' && c.status === 'True')) continue
    for (const container of pod.spec?.containers ?? []) {
      if (normalizedRepository(container.image) !== repository) continue
      matching++
      const status = pod.status.containerStatuses?.find(s => s.name === container.name)
      const digest = status?.ready ? imageDigest(status.imageID) : null
      if (!digest) throw new Error('ready infra container has no immutable image identity')
      digests.add(digest)
    }
  }
  if (!matching) throw new Error('no ready infra container')
  return [...digests].sort()
}

function k8sPods(namespace: string): Promise<Pod[]> {
  const token = readFileSync('/var/run/secrets/kubernetes.io/serviceaccount/token', 'utf8')
  const ca = readFileSync('/var/run/secrets/kubernetes.io/serviceaccount/ca.crt')
  const host = process.env.KUBERNETES_SERVICE_HOST
  if (!host) throw new Error('Kubernetes API unavailable')
  return new Promise((resolve, reject) => {
    const req = https.request({ host, port: process.env.KUBERNETES_SERVICE_PORT_HTTPS ?? '443',
      path: `/api/v1/namespaces/${namespace}/pods`, method: 'GET', ca,
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }, timeout: 5000 }, res => {
      const chunks: Buffer[] = []
      res.on('data', chunk => chunks.push(chunk as Buffer))
      res.on('end', () => {
        if (res.statusCode !== 200) { reject(new Error(`Kubernetes pod read ${res.statusCode}`)); return }
        try {
          const body: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'))
          if (!body || typeof body !== 'object' || !Array.isArray((body as { items?: unknown }).items)) throw new Error('invalid pod list')
          resolve((body as { items: Pod[] }).items)
        } catch (e) { reject(e) }
      })
    })
    req.on('error', reject)
    req.on('timeout', () => req.destroy(new Error('Kubernetes pod read timeout')))
    req.end()
  })
}

export async function currentInfraDigests(): Promise<Record<string, string[] | null>> {
  const result: Record<string, string[] | null> = {}
  const namespaces = [...new Set(Object.values(INFRA_CONTAINERS).map(c => c.namespace))]
  const lists = await Promise.all(namespaces.map(async ns => {
    try { return [ns, await k8sPods(ns)] as const } catch { return [ns, null] as const }
  }))
  const byNamespace = Object.fromEntries(lists) as Record<string, Pod[] | null>
  for (const [id, selector] of Object.entries(INFRA_CONTAINERS)) {
    try {
      const pods = byNamespace[selector.namespace]
      result[id] = pods ? liveDigests(pods, selector.repository) : null
    } catch { result[id] = null }
  }
  return result
}

export type RuntimeScan = {
  digest: string; critical: number; high: number; medium: number; low: number; total: number;
  top?: { id: string; severity: string; fixedIn?: string | null }[];
  reportSha256: string
}

export type RuntimeSnapshot = {
  schema: string; scannedAt: string; scanner: { name: string; version: string;
    db: { schemaVersion: number; sha256: string; fetchedAt: string } };
  images: Record<string, { scans: RuntimeScan[] }>
}

export function verifiedRuntimeScans(snapshot: RuntimeSnapshot | null, id: string,
  current: string[] | null, now = Date.now()): RuntimeScan[] | null {
  if (!snapshot || snapshot.schema !== 'openbank.infra-vulns.runtime/v2' || !current?.length ||
      !INSTANT.test(snapshot.scannedAt) || !Number.isFinite(Date.parse(snapshot.scannedAt)) ||
      now - Date.parse(snapshot.scannedAt) < 0 || now - Date.parse(snapshot.scannedAt) > MAX_SCAN_AGE_MS ||
      snapshot.scanner?.name !== 'grype' || !/^\d+\.\d+\.\d+$/.test(snapshot.scanner.version) ||
      !Number.isInteger(snapshot.scanner.db?.schemaVersion) || snapshot.scanner.db.schemaVersion <= 0 ||
      !/^[a-f0-9]{64}$/.test(snapshot.scanner.db?.sha256 ?? '') ||
      !INSTANT.test(snapshot.scanner.db?.fetchedAt ?? '') ||
      !Number.isFinite(Date.parse(snapshot.scanner.db.fetchedAt)) ||
      Date.parse(snapshot.scanner.db.fetchedAt) > now ||
      now - Date.parse(snapshot.scanner.db.fetchedAt) > MAX_SCAN_AGE_MS) return null
  const scans = snapshot.images?.[id]?.scans
  if (!Array.isArray(scans) || scans.length !== current.length) return null
  const seen = new Set<string>()
  for (const scan of scans) {
    if (!DIGEST.test(scan?.digest ?? '') || seen.has(scan.digest) ||
        !current.includes(scan.digest) || !/^[a-f0-9]{64}$/.test(scan.reportSha256 ?? '') ||
        !['critical', 'high', 'medium', 'low', 'total'].every(k =>
          Number.isInteger(scan[k as keyof RuntimeScan]) && (scan[k as keyof RuntimeScan] as number) >= 0)) return null
    seen.add(scan.digest)
  }
  return scans
}
