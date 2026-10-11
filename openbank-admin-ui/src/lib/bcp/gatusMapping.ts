// SPDX-License-Identifier: Apache-2.0
import type { ServiceEntry } from '@/lib/services/registry'

// Only Gatus naming exceptions live here. The set of services comes from the generated catalog.
const GATUS_ALIASES: Record<string, string> = {
  'psd2-api-(čobs)': 'psd2-service',
  'psd2-sandbox': 'psd2-service',
  'tpp-registry': 'tpp-registry-service',
  'agent-service-(mcp)': 'agent-service',
  'sepa-instant-service': 'sepa-instant',
}

// Infrastructure probes have no service module, so they cannot be found in the fleet catalog.
const INFRA_PROBES: Record<string, string> = {
  'infrastructure_postgresql': 'postgres',
  'infrastructure_kafka': 'kafka',
  'infrastructure_keycloak-(iam)': 'keycloak',
  'infrastructure_valkey-(cache)': 'valkey',
  'infrastructure_vault-(secrets)': 'vault',
  'infrastructure_schema-registry': 'schema-registry',
  'observability_grafana': 'grafana',
  'observability_prometheus': 'prometheus',
  'observability_loki': 'loki',
  'observability_tempo': 'tempo',
  'psd2-open-banking_api-gateway': 'api-gateway',
}

export function gatusContainer(key: string, registry: readonly ServiceEntry[]): string | undefined {
  const infra = Object.hasOwn(INFRA_PROBES, key) ? INFRA_PROBES[key] : undefined
  if (infra) return `openbank-${infra}`
  const separator = key.indexOf('_')
  if (separator < 0) return undefined
  const probe = key.slice(separator + 1).replace(/-—-liveness$/, '')
  const short = Object.hasOwn(GATUS_ALIASES, probe) ? GATUS_ALIASES[probe] : probe
  return registry.find(service => service.container === `openbank-${short}`)?.container
}
