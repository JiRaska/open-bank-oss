// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { k8sNameOf, type ServiceEntry } from '@/lib/services/registry'
import { INFRA } from '@/lib/infra/probes'

// Gatus keys have the form <group>_<endpoint>. The endpoint normally is the
// Kubernetes workload name. These are actual naming exceptions, not a fleet
// membership list: new ordinary services are matched from the baked catalog.
const ENDPOINT_ALIASES: Record<string, string> = {
  'psd2-api': 'psd2-service',
  'psd2-sandbox': 'psd2-service',
  'sepa-instant-service': 'sepa-instant',
  'tpp-registry': 'tpp-registry-service',
  'security-scanner': 'security-scanner-service',
}

// Non-Quarkus endpoints have no service-registry entry. Their stable IDs are
// declared by the infrastructure probe catalog; only spelling differences live here.
const INFRA_ALIASES: Record<string, string> = {
  postgresql: 'postgres',
  vault: 'vault',
}

const INFRA_IDS = new Set(INFRA.map(probe => probe.id))

/** Resolve a live Gatus endpoint against the generated service registry. */
export function gatusContainer(key: string, registry: readonly ServiceEntry[]): string | undefined {
  const separator = key.indexOf('_')
  if (separator < 1) return undefined
  const group = key.slice(0, separator)
  const endpoint = key.slice(separator + 1)
    .replace(/-—-liveness$/, '')
    .replace(/-\([^)]*\)$/, '')
  if (!endpoint) return undefined

  if (group === 'infrastructure' || group === 'observability') {
    const id = INFRA_ALIASES[endpoint] ?? endpoint
    return INFRA_IDS.has(id) || id === 'vault' ? `openbank-${id}` : undefined
  }

  if (group === 'psd2-open-banking' && endpoint === 'api-gateway') return 'openbank-api-gateway'
  const workload = ENDPOINT_ALIASES[endpoint] ?? endpoint
  const service = registry.find(entry => k8sNameOf(entry) === workload)
  return service?.container
}
