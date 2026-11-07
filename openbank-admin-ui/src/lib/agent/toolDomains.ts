// SPDX-License-Identifier: Apache-2.0
import { domainLabel } from '@/lib/governance/domains'
import { currentDomainOwnership } from '@/lib/services/domainOwnership'
import { getRegistry } from '@/lib/services/fleet'

/** Project display ownership only; tool identities and execution contracts stay unchanged. */
export async function canonicalToolDomains(tools: unknown[]): Promise<unknown[]> {
  const { byModule } = await currentDomainOwnership(getRegistry())
  return tools.map(tool => {
    if (tool === null || typeof tool !== 'object' || Array.isArray(tool)) return tool
    const entry = tool as Record<string, unknown>
    const service = typeof entry.service === 'string' ? entry.service : null
    // Historical advertised tool service; the execution identity remains verbatim.
    const moduleName = service === 'sepa-instant-service' ? 'openbank-sepa-instant'
      : service === null ? null : service.startsWith('openbank-') ? service : `openbank-${service}`
    const group = moduleName === null ? 'unclassified' : byModule.get(moduleName) ?? 'unclassified'
    return { ...entry, domain: domainLabel(group, 'en') }
  })
}
