// SPDX-License-Identifier: Apache-2.0
import { readAdminUiContentSnapshot } from '@/lib/evidence/contentSource'
import { isDomainId, type DomainGroup } from '@/lib/governance/domains'
import { k8sNameOf, type ServiceEntry } from './registry'

/** Business metadata only. Operational registries, ports, tenant selection and
 * BFF allowlists remain image-pinned even when reviewed source advances first. */
export async function currentDomainOwnership(registry: readonly ServiceEntry[]): Promise<{
  byModule: ReadonlyMap<string, DomainGroup>
  byWorkload: ReadonlyMap<string, DomainGroup>
}> {
  const byModule = new Map(registry.map(service => [service.container, service.group]))
  const identities = new Map(registry.map(service => [service.container, k8sNameOf(service)]))
  try {
    const result = await readAdminUiContentSnapshot()
    const services = result.snapshot?.content.catalog.services
    if (Array.isArray(services)) {
      for (const service of services) {
        if (service && typeof service === 'object' && typeof service.name === 'string'
          && /^openbank-[a-z0-9]+(?:-[a-z0-9]+)*$/.test(service.name) && isDomainId(service.dataDomain)) {
          byModule.set(service.name, service.dataDomain)
          // A new observed workload can receive ownership metadata before the
          // image is rebuilt, but this does not enroll any new operational target.
          if (!identities.has(service.name) && service.runnable === true) {
            identities.set(service.name, service.name.replace(/^openbank-/, ''))
          }
        }
      }
    }
  } catch {
    // The bounded source reader normally reports unavailable rather than throwing.
    // Ownership transport must never prevent observing the live workloads.
  }
  const byWorkload = new Map<string, DomainGroup>()
  const seen = new Set<string>()
  for (const [moduleName, workload] of identities) {
    // An identity collision is missing evidence, never permission to guess a target.
    byWorkload.set(workload, seen.has(workload) ? 'unclassified' : byModule.get(moduleName) ?? 'unclassified')
    seen.add(workload)
  }
  return { byModule, byWorkload }
}
