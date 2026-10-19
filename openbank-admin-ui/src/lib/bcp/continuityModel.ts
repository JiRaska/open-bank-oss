// SPDX-License-Identifier: Apache-2.0

/** Parse recovery targets and named tiers from the canonical BCP policy table. */
export interface BcpPolicyTier {
  id: number
  key: string
  name: string
  label: readonly [string, string]
  color: string
  textColor: string
  rto: string
  rpo: string
  explicitShortNames: string[]
  appliesToOtherServices: boolean
}

export interface BcpPolicy {
  tiers: BcpPolicyTier[]
  source: 'docs/bcp/bcp-policy.md §2'
}

const TIER_COLORS = ['var(--danger)', 'var(--warning)', 'var(--accent)', 'var(--info)'] as const
const TIER_ID = /^T([0-3])\s+—\s+(.+)$/
const SERVICE_NAME = /^(?:[a-z0-9]+-)*[a-z0-9]+-service$|^[a-z0-9]+(?:-[a-z0-9]+)*$/

export function parseBcpPolicy(markdown: string): BcpPolicy | null {
  const tiers: BcpPolicyTier[] = []
  const ids = new Set<number>()
  const namedServices = new Set<string>()
  for (const line of markdown.split(/\r?\n/)) {
    const cells = line.split('|').map(cell => cell.trim())
    if (cells.length < 6) continue
    const tierMatch = TIER_ID.exec(cells[1] ?? '')
    if (!tierMatch) continue
    const id = Number(tierMatch[1])
    const name = tierMatch[2].trim()
    const serviceCell = cells[2]
    const rto = cells[3]
    const rpo = cells[4]
    if (ids.has(id) || !name || !rto || !rpo || !serviceCell) return null
    const appliesToOtherServices = /^all other services$/i.test(serviceCell)
    if (appliesToOtherServices && id !== 3) return null
    const explicitShortNames = appliesToOtherServices ? [] : serviceCell.split(/,\s*/)
    if (explicitShortNames.some(service => !SERVICE_NAME.test(service) || namedServices.has(service))) return null
    if (id === 3 && appliesToOtherServices && tiers.some(tier => tier.appliesToOtherServices)) return null
    explicitShortNames.forEach(service => namedServices.add(service))
    ids.add(id)
    const label = `T${id} — ${name}`
    tiers.push({
      id,
      key: `T${id}`,
      name,
      label: [label, label],
      color: TIER_COLORS[id],
      textColor: id === 3 ? 'var(--info-text)' : 'var(--text-inverse)',
      rto,
      rpo,
      explicitShortNames,
      appliesToOtherServices,
    })
  }
  if (ids.size !== 4 || ![0, 1, 2, 3].every(id => ids.has(id))) return null
  return { tiers: tiers.sort((a, b) => a.id - b.id), source: 'docs/bcp/bcp-policy.md §2' }
}

export interface BcpCatalogService {
  name: string
  short: string
  kind: string
  runnable?: boolean
  port?: number | null
  moneyPath?: boolean
}

export interface BcpServiceGraph {
  edges?: readonly { from: string; to: string; type: string }[]
  infraEdges?: readonly { from: string; to: string; type: string }[]
  infraNodes?: readonly { id: string; label: string; kind: string }[]
}

export interface BcpServiceRow {
  name: string
  label: string
  note: readonly [string, string]
  moneyPath: boolean
}

export interface BcpTier extends BcpPolicyTier {
  regulatoryBasis: readonly [string, string]
  services: BcpServiceRow[]
}

export interface BcpRoster {
  tiers: BcpTier[]
  policyServicesMissingFromCatalog: string[]
  unclassifiedCatalogServices: string[]
  catalogServiceCount: number
}

function titleCase(short: string): string {
  return short.replace(/-service$/, '').split('-').map(part => part[0]?.toUpperCase() + part.slice(1)).join(' ')
}

function dependenciesFor(name: string, graph?: BcpServiceGraph): string[] | null {
  if (!graph) return null
  const serviceEdges = (graph.edges ?? [])
    .filter(edge => edge.from === name)
    .map(edge => edge.to.replace(/^openbank-/, ''))
  const infraLabels = new Map((graph.infraNodes ?? []).map(node => [node.id, node.label]))
  const infraEdges = (graph.infraEdges ?? [])
    .filter(edge => edge.from === name)
    .map(edge => infraLabels.get(edge.to) ?? edge.to)
  return [...new Set([...serviceEdges, ...infraEdges])].sort((a, b) => a.localeCompare(b))
}

function graphInfrastructureShortNames(graph?: BcpServiceGraph): Set<string> {
  return new Set((graph?.infraNodes ?? [])
    .filter(node => node.kind === 'infra')
    .map(node => node.id.replace(/^infra:/, '')))
}

export function buildBcpRoster(catalog: readonly BcpCatalogService[], graph: BcpServiceGraph | undefined, policy: BcpPolicy | null): BcpRoster {
  const services = catalog
    .filter(service => service.kind === 'service')
    .slice()
    .sort((a, b) => a.short.localeCompare(b.short))
  if (!policy) return { tiers: [], policyServicesMissingFromCatalog: [], unclassifiedCatalogServices: services.map(service => service.short), catalogServiceCount: services.length }

  const serviceNames = new Set(services.map(service => service.short))
  const infrastructure = graphInfrastructureShortNames(graph)
  const explicitTierByName = new Map<string, number>()
  for (const tier of policy.tiers) {
    for (const short of tier.explicitShortNames) explicitTierByName.set(short, tier.id)
  }
  const defaultTier = policy.tiers.find(tier => tier.appliesToOtherServices)
  const classificationTier = (short: string) => explicitTierByName.get(short) ?? defaultTier?.id

  const tiers = policy.tiers.map((tier): BcpTier => ({
    ...tier,
    regulatoryBasis: [`Business Continuity Policy §2${tier.id === 0 ? '; aspirational until M6' : ''}`, `Business Continuity Policy §2${tier.id === 0 ? '; aspirational until M6' : ''}`],
    services: services
      .filter(service => classificationTier(service.short) === tier.id)
      .map(service => {
        const dependencies = dependenciesFor(service.name, graph)
        const dependencyText = dependencies === null
          ? 'Declared service dependencies unavailable.'
          : dependencies.length > 0
            ? `Declared dependencies: ${dependencies.join(', ')}.`
            : 'No direct dependencies are declared in the service graph.'
        const moneyPathText = service.moneyPath ? 'Money-path service per governance catalog.' : 'Not marked money-path in the governance catalog.'
        return {
          name: service.short,
          label: titleCase(service.short),
          moneyPath: service.moneyPath === true,
          note: [`${moneyPathText} ${dependencyText}`, `${moneyPathText} ${dependencyText}`],
        }
      }),
  }))

  const policyServicesMissingFromCatalog = policy.tiers
    .flatMap(tier => tier.explicitShortNames)
    .filter(short => !serviceNames.has(short) && !infrastructure.has(short))
    .sort()
  const unclassifiedCatalogServices = services
    .filter(service => classificationTier(service.short) === undefined)
    .map(service => service.short)

  return { tiers, policyServicesMissingFromCatalog, unclassifiedCatalogServices, catalogServiceCount: services.length }
}

export function bcpCatalogCoverageIsComplete(roster: BcpRoster): boolean {
  const allTierServices = roster.tiers.flatMap(tier => tier.services.map(service => service.name))
  return roster.catalogServiceCount > 0
    && roster.unclassifiedCatalogServices.length === 0
    && allTierServices.length === roster.catalogServiceCount
    && new Set(allTierServices).size === allTierServices.length
}
