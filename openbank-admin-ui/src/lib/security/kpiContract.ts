// SPDX-License-Identifier: Apache-2.0

type Unavailable = { available: false; reason: string }
export type SecurityKpis = {
  generatedAt: string
  netpol: Unavailable | { available: true; covered: number; total: number; coveragePct: number; gateGreen: boolean }
  freshness: Unavailable | { available: true; fleetScore: number; scoredModules: number; unknownModules: number }
  credentials: Unavailable | { available: true; totalSecrets: number; staticSecrets: number; withDeadline: number; overdue: number; undeclared: number; overdueFound: boolean }
  fuzz: Unavailable | { available: true; inScope: number; tested: number; coveragePct: number; totalExercised: number; excludedCount: number; runDate: string }
  threatModels: Unavailable | { available: true; moneyPathTotal: number; withModel: number; staleCount: number; oldestDays: number }
  mttr: Unavailable | { available: true; fixedCount: number; medianFixDays: number | null; openCount: number; oldestOpenDays: number }
}

const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
const DATE = /^\d{4}-\d{2}-\d{2}$/

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
  return value as Record<string, unknown>
}
function integer(value: unknown, max = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isInteger(value) || (value as number) < 0 || (value as number) > max) throw new Error('Invalid count')
  return value as number
}
function percent(value: unknown): number { return integer(value, 100) }
function bool(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid boolean')
  return value
}
function unavailable(section: Record<string, unknown>): Unavailable | null {
  if (section.available !== false) return null
  if (typeof section.reason !== 'string' || section.reason.trim() === '' || section.reason.length > 500) throw new Error('Invalid unavailable reason')
  return { available: false, reason: section.reason }
}
function ratio(actual: number, total: number): number { return total === 0 ? 0 : Math.round(100 * actual / total) }

export function parseSecurityKpis(value: unknown): SecurityKpis {
  const raw = object(value)
  if (typeof raw.generatedAt !== 'string' || !RFC3339.test(raw.generatedAt) || !Number.isFinite(Date.parse(raw.generatedAt))) throw new Error('Invalid generatedAt')

  const netpolRaw = object(raw.netpol); const netpolUnavailable = unavailable(netpolRaw)
  const netpol = netpolUnavailable ?? (() => {
    if (netpolRaw.available !== true) throw new Error('Invalid netpol availability')
    const covered = integer(netpolRaw.covered); const total = integer(netpolRaw.total); const coveragePct = percent(netpolRaw.coveragePct)
    if (covered > total || coveragePct !== ratio(covered, total)) throw new Error('Contradictory netpol coverage')
    return { available: true as const, covered, total, coveragePct, gateGreen: bool(netpolRaw.gateGreen) }
  })()

  const freshnessRaw = object(raw.freshness); const freshnessUnavailable = unavailable(freshnessRaw)
  const freshness = freshnessUnavailable ?? (() => {
    if (freshnessRaw.available !== true) throw new Error('Invalid freshness availability')
    const scoredModules = integer(freshnessRaw.scoredModules); const unknownModules = integer(freshnessRaw.unknownModules)
    return { available: true as const, fleetScore: percent(freshnessRaw.fleetScore), scoredModules, unknownModules }
  })()

  const credentialsRaw = object(raw.credentials); const credentialsUnavailable = unavailable(credentialsRaw)
  const credentials = credentialsUnavailable ?? (() => {
    if (credentialsRaw.available !== true) throw new Error('Invalid credentials availability')
    const totalSecrets = integer(credentialsRaw.totalSecrets); const staticSecrets = integer(credentialsRaw.staticSecrets)
    const withDeadline = integer(credentialsRaw.withDeadline); const overdue = integer(credentialsRaw.overdue); const undeclared = integer(credentialsRaw.undeclared)
    if (staticSecrets > totalSecrets || withDeadline + overdue + undeclared !== staticSecrets) throw new Error('Contradictory credential counts')
    const overdueFound = bool(credentialsRaw.overdueFound)
    if (overdueFound !== (overdue > 0)) throw new Error('Contradictory credential verdict')
    return { available: true as const, totalSecrets, staticSecrets, withDeadline, overdue, undeclared, overdueFound }
  })()

  const fuzzRaw = object(raw.fuzz); const fuzzUnavailable = unavailable(fuzzRaw)
  const fuzz = fuzzUnavailable ?? (() => {
    if (fuzzRaw.available !== true) throw new Error('Invalid fuzz availability')
    const inScope = integer(fuzzRaw.inScope); const tested = integer(fuzzRaw.tested); const coveragePct = percent(fuzzRaw.coveragePct)
    if (tested > inScope || coveragePct !== ratio(tested, inScope)) throw new Error('Contradictory fuzz coverage')
    if (typeof fuzzRaw.runDate !== 'string' || (fuzzRaw.runDate !== '' && !DATE.test(fuzzRaw.runDate))) throw new Error('Invalid fuzz date')
    return { available: true as const, inScope, tested, coveragePct, totalExercised: integer(fuzzRaw.totalExercised), excludedCount: integer(fuzzRaw.excludedCount), runDate: fuzzRaw.runDate }
  })()

  const threatRaw = object(raw.threatModels); const threatUnavailable = unavailable(threatRaw)
  const threatModels = threatUnavailable ?? (() => {
    if (threatRaw.available !== true) throw new Error('Invalid threat-model availability')
    const moneyPathTotal = integer(threatRaw.moneyPathTotal); const withModel = integer(threatRaw.withModel); const staleCount = integer(threatRaw.staleCount)
    if (withModel > moneyPathTotal || staleCount > withModel) throw new Error('Contradictory threat-model counts')
    return { available: true as const, moneyPathTotal, withModel, staleCount, oldestDays: integer(threatRaw.oldestDays) }
  })()

  const mttrRaw = object(raw.mttr); const mttrUnavailable = unavailable(mttrRaw)
  const mttr = mttrUnavailable ?? (() => {
    if (mttrRaw.available !== true) throw new Error('Invalid MTTR availability')
    const median = mttrRaw.medianFixDays
    if (median !== null && (typeof median !== 'number' || !Number.isFinite(median) || median < 0)) throw new Error('Invalid median fix time')
    return { available: true as const, fixedCount: integer(mttrRaw.fixedCount), medianFixDays: median as number | null, openCount: integer(mttrRaw.openCount), oldestOpenDays: integer(mttrRaw.oldestOpenDays) }
  })()

  return { generatedAt: raw.generatedAt, netpol, freshness, credentials, fuzz, threatModels, mttr }
}
