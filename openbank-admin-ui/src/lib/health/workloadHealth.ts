// SPDX-License-Identifier: Apache-2.0

export type WorkloadHealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

/** Kubernetes workload identity; legacy static snapshots have no namespace. */
export function workloadHealthIdentity(entry: { name: string; namespace?: string; port?: number }): string {
  return entry.namespace
    ? `${entry.namespace}/${entry.name}`
    : `${entry.name}:${entry.port ?? ''}`
}

/** Conservative logical-service status across all observed workload instances. */
export function aggregateHealthStatuses(statuses: readonly unknown[]): WorkloadHealthStatus {
  if (statuses.length === 0) return 'UNKNOWN'
  if (statuses.includes('DOWN')) return 'DOWN'
  if (statuses.some(status => status !== 'UP')) return 'UNKNOWN'
  return 'UP'
}

/** Match every workload alias for one logical catalog service and aggregate it. */
export function aggregateNamedWorkloadStatuses(
  services: readonly { name: string; container?: string; status: unknown }[],
  aliases: ReadonlySet<string>,
): WorkloadHealthStatus {
  return aggregateHealthStatuses(services
    .filter(service => aliases.has(service.name) || (service.container !== undefined && aliases.has(service.container)))
    .map(service => service.status))
}
