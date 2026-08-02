// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.


// Infra probes, lifted out of `app/api/infra/status/route.ts`.
//
// A Next route file may only export HTTP handlers and a known set of config fields. These were
// exported from the route purely so a test could import them — Turbopack tolerates that, webpack
// rejects it outright ("probeInfra is not a valid Route export field"), and it was the single
// blocker to building with the bundler that emits client source maps at all (#3235).
//
// The structure is better for its own sake: probe logic that a test drives directly does not belong
// in a request handler.

import * as net from 'net'
import { inCluster } from '@/lib/discovery'


export interface InfraStatusResult {
  id: string
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  latencyMs: number | null
  checkedAt: string | null
}

type InfraProbe =
  | { kind: 'http'; url: string; okCodes?: number[] }
  | { kind: 'tcp'; host: string; port: number }
  | { kind: 'absent' } // not deployed in this topology → UNKNOWN, never a false DOWN

export interface InfraDef {
  id: string
  probe: InfraProbe
}

// In-cluster (ADR-0027 substrate): probe the real Kubernetes Service DNS. The
// previous list addressed `localhost`/compose container names, so every probe
// failed inside the pod and the whole infra panel + BCP Tier 0/5 read DOWN. A
// component that genuinely isn't part of the sandbox topology yet (loki, kafka-ui)
// is marked `absent` ⇒ UNKNOWN, not a misleading red. Ids are stable contract
// keys consumed by /docs/bcp and /infrastructure.
export const CLUSTER_INFRA: InfraDef[] = [
  { id: 'postgres',        probe: { kind: 'tcp',  host: 'accounts-db-rw.accounts.svc',                       port: 5432 } },
  { id: 'kafka',           probe: { kind: 'tcp',  host: 'openbank-cluster-kafka-bootstrap.messaging.svc',    port: 9092 } },
  { id: 'keycloak',        probe: { kind: 'http', url: 'http://keycloak.iam.svc:8080/realms/master', okCodes: [200] } },
  { id: 'openbao',         probe: { kind: 'http', url: 'http://openbao.vault.svc:8200/v1/sys/health', okCodes: [200, 429, 472, 473] } },
  { id: 'valkey',          probe: { kind: 'tcp',  host: 'redis.accounts.svc',                                port: 6379 } },
  { id: 'schema-registry', probe: { kind: 'tcp',  host: 'apicurio-registry.messaging.svc',                   port: 8080 } },
  { id: 'grafana',         probe: { kind: 'http', url: 'http://kube-prometheus-stack-grafana.observability.svc:80/api/health', okCodes: [200] } },
  { id: 'prometheus',      probe: { kind: 'http', url: 'http://kube-prometheus-stack-prometheus.observability.svc:9090/-/ready', okCodes: [200] } },
  { id: 'loki',            probe: { kind: 'http', url: 'http://loki.observability.svc:3100/ready', okCodes: [200] } },
  { id: 'tempo',           probe: { kind: 'tcp',  host: 'tempo.observability.svc',                            port: 3200 } },
  { id: 'kafka-ui',        probe: { kind: 'http', url: 'http://kafka-ui.messaging.svc:8080/actuator/health', okCodes: [200] } },
  // Expanded observability stack (ADR-0077/0079/0088). TCP probes (port open ⇒ up)
  // rather than HTTP health paths — a wrong path reads as a misleading red, and the
  // liveness signal we need here is "the service is reachable in-cluster".
  { id: 'pyroscope',       probe: { kind: 'tcp',  host: 'pyroscope.observability.svc',                        port: 4040 } },
  { id: 'alertmanager',    probe: { kind: 'tcp',  host: 'kube-prometheus-stack-alertmanager.observability.svc', port: 9093 } },
  { id: 'alloy',           probe: { kind: 'tcp',  host: 'alloy.observability.svc',                            port: 12345 } },
  { id: 'otel-collector',  probe: { kind: 'tcp',  host: 'otel-collector.observability.svc',                   port: 4317 } },
  { id: 'pyrra',           probe: { kind: 'tcp',  host: 'pyrra-api.observability.svc',                        port: 9099 } },
  { id: 'glitchtip',       probe: { kind: 'tcp',  host: 'glitchtip-web.observability.svc',                    port: 80 } },
  { id: 'goalert',         probe: { kind: 'tcp',  host: 'goalert.observability.svc',                          port: 8080 } },
  { id: 'ntfy',            probe: { kind: 'tcp',  host: 'ntfy.observability.svc',                             port: 8080 } },
  // Platform control plane + orchestration. TCP probes (port open ⇒ up) against
  // the real Service DNS, verified against the live cluster. Temporal underpins
  // payment/settlement/statement workflow orchestration; KEDA drives the
  // scale-to-zero the rest of the fleet relies on (ADR-0057).
  { id: 'temporal',        probe: { kind: 'tcp',  host: 'temporal-frontend.temporal.svc',                    port: 7233 } },
  { id: 'keda',            probe: { kind: 'tcp',  host: 'keda-operator.keda.svc',                             port: 9666 } },
  { id: 'argocd',          probe: { kind: 'tcp',  host: 'argocd-server.argocd.svc',                           port: 80 } },
  { id: 'kyverno',         probe: { kind: 'tcp',  host: 'kyverno-svc-metrics.kyverno.svc',                    port: 8000 } },
  { id: 'cert-manager',    probe: { kind: 'tcp',  host: 'cert-manager.cert-manager.svc',                      port: 9402 } },
  { id: 'karpenter',       probe: { kind: 'tcp',  host: 'karpenter.kube-system.svc',                          port: 8080 } },
]

// Off-cluster (local dev / docker-compose): legacy localhost/container probes.
function containerHost(name: string): string {
  return process.env.SERVICES_HOST === 'container' ? name : 'localhost'
}

export const LOCAL_INFRA: InfraDef[] = [
  { id: 'postgres',        probe: { kind: 'tcp',  host: containerHost('openbank-postgres'),         port: 5432 } },
  { id: 'kafka',           probe: { kind: 'tcp',  host: containerHost('openbank-kafka'),             port: process.env.SERVICES_HOST === 'container' ? 9092 : 29092 } },
  { id: 'keycloak',        probe: { kind: 'http', url: `http://${containerHost('openbank-keycloak')}:8080/realms/master`, okCodes: [200] } },
  // Local docker-compose still ships Vault; the in-cluster store is OpenBao (runbook 0005).
  { id: 'openbao',         probe: { kind: 'http', url: `http://${containerHost('openbank-vault')}:8200/v1/sys/health`, okCodes: [200, 429, 472, 473] } },
  { id: 'valkey',          probe: { kind: 'tcp',  host: containerHost('openbank-valkey'),            port: 6379 } },
  { id: 'schema-registry', probe: { kind: 'http', url: `http://${containerHost('openbank-schema-registry')}:8081/`, okCodes: [200, 302] } },
  { id: 'grafana',         probe: { kind: 'http', url: `http://${containerHost('openbank-grafana')}:3000/api/health`, okCodes: [200] } },
  { id: 'prometheus',      probe: { kind: 'http', url: `http://${containerHost('openbank-prometheus')}:9090/-/ready`, okCodes: [200] } },
  { id: 'loki',            probe: { kind: 'http', url: `http://${containerHost('openbank-loki')}:3100/ready`, okCodes: [200] } },
  { id: 'tempo',           probe: { kind: 'http', url: `http://${containerHost('openbank-tempo')}:3200/ready`, okCodes: [200] } },
  { id: 'kafka-ui',        probe: { kind: 'http', url: `http://${containerHost('openbank-kafka-ui')}:8080/`, okCodes: [200, 302] } },
  // Platform control plane is Kubernetes-only — not part of the docker-compose
  // topology. Report UNKNOWN off-cluster rather than a misleading DOWN.
  { id: 'temporal',        probe: { kind: 'absent' } },
  { id: 'keda',            probe: { kind: 'absent' } },
  { id: 'argocd',          probe: { kind: 'absent' } },
  { id: 'kyverno',         probe: { kind: 'absent' } },
  { id: 'cert-manager',    probe: { kind: 'absent' } },
  { id: 'karpenter',       probe: { kind: 'absent' } },
]


function tcpProbe(host: string, port: number, timeoutMs = 3000): Promise<number | null> {
  return new Promise(resolve => {
    const start = Date.now()
    const sock = new net.Socket()
    const done = (ok: boolean) => {
      sock.destroy()
      resolve(ok ? Date.now() - start : null)
    }
    sock.setTimeout(timeoutMs)
    sock.connect(port, host, () => done(true))
    sock.on('error', () => done(false))
    sock.on('timeout', () => done(false))
  })
}

export async function probeInfra(def: InfraDef): Promise<InfraStatusResult> {
  const now = new Date().toISOString()
  try {
    if (def.probe.kind === 'absent') {
      // Not part of the current topology — report UNKNOWN so the UI shows a
      // neutral state instead of a false outage.
      return { id: def.id, status: 'UNKNOWN', latencyMs: null, checkedAt: now }
    }
    if (def.probe.kind === 'tcp') {
      const latencyMs = await tcpProbe(def.probe.host, def.probe.port)
      return { id: def.id, status: latencyMs !== null ? 'UP' : 'DOWN', latencyMs, checkedAt: now }
    }

    const start = Date.now()
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 4000)
    const res = await fetch(def.probe.url, { signal: ctrl.signal, cache: 'no-store' })
    clearTimeout(timer)
    const latencyMs = Date.now() - start
    const okCodes = def.probe.okCodes ?? [200]
    const status = okCodes.includes(res.status) ? 'UP' : 'DOWN'
    return { id: def.id, status, latencyMs, checkedAt: now }
  } catch {
    return { id: def.id, status: 'DOWN', latencyMs: null, checkedAt: now }
  }
}


export const INFRA: InfraDef[] = inCluster() ? CLUSTER_INFRA : LOCAL_INFRA
