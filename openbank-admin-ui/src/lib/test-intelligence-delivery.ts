// SPDX-License-Identifier: Apache-2.0
import type { ComponentTestPosture, EvidenceKind, TestCaseHistory, TestIntelligenceReport } from './types/test-intelligence'
import { TEST_KINDS, testCell, testScopeCounts } from './test-intelligence-workspace'

export const TEST_CASE_PAGE_SIZE = 25
type CaseIdentity = Pick<TestCaseHistory, 'fingerprint' | 'component' | 'kind'>
export type TestCaseListItem = CaseIdentity & Pick<TestCaseHistory, 'name' | 'state' | 'lastState' | 'lastObservedAt'>
export type MatrixComponent = Pick<ComponentTestPosture, 'component' | 'domain' | 'moneyPath'> & {
  cells: Record<EvidenceKind, ReturnType<typeof testCell>>
  running?: Pick<NonNullable<ComponentTestPosture['running']>, 'match' | 'latestEvidenceAt'>
}
export interface TestMatrixReport {
  schemaVersion: 1; view: 'overview'; collectedAt: string
  components: MatrixComponent[]
  scope: ReturnType<typeof testScopeCounts>
  totals: TestIntelligenceReport['totals']
  evidence?: TestIntelligenceReport['evidence']
  warnings: string[]
}
export interface ComponentTestsReply {
  schemaVersion: 1; view: 'component'; collectedAt: string
  component: ComponentTestPosture
  tests: TestCaseListItem[]
  total: number; offset: number; limit: number
}
export interface TestCaseReply {
  schemaVersion: 1; view: 'case'; collectedAt: string; testCase: TestCaseHistory
}
export type TestReadRequest = { view: 'full' } | { view: 'overview' }
  | { view: 'component'; component: string; kind: EvidenceKind | ''; query: string; offset: number; limit: number; collectedAt?: string }
  | { view: 'case'; component: string; fingerprint: string; kind: EvidenceKind | ''; collectedAt?: string }
export type TestReadReply = TestIntelligenceReport | TestMatrixReport | ComponentTestsReply | TestCaseReply
export type TestReadError = 'invalid_query' | 'evidence_changed' | 'component_not_found' | 'test_not_found' | 'unavailable'
export type TestReadResult = { ok: true; value: TestReadReply } | { ok: false; error: TestReadError; status: number }

export function testReadUrl(request: TestReadRequest): string {
  const params = new URLSearchParams({ view: request.view })
  if (request.view === 'component' || request.view === 'case') {
    params.set('component', request.component)
    if (request.kind) params.set('kind', request.kind)
    if (request.collectedAt) params.set('collectedAt', request.collectedAt)
    if (request.view === 'case') params.set('fingerprint', request.fingerprint)
    else {
      params.set('offset', String(request.offset)); params.set('limit', String(request.limit))
      if (request.query) params.set('q', request.query)
    }
  }
  return `/api/test-intelligence?${params}`
}

export function parseTestReadRequest(params: URLSearchParams): TestReadRequest | null {
  const view = params.get('view') ?? 'full'
  if (view === 'full' || view === 'overview') return { view }
  if (view !== 'component' && view !== 'case') return null
  const component = params.get('component') ?? ''
  const kind = params.get('kind') ?? ''
  const collectedAt = params.get('collectedAt') ?? undefined
  if (!/^openbank-[a-z0-9-]{2,90}$/.test(component) || (kind && !TEST_KINDS.includes(kind as EvidenceKind))) return null
  if (collectedAt && (collectedAt.length > 64 || !/^\d{4}-\d\d-\d\dT/.test(collectedAt) || !Number.isFinite(Date.parse(collectedAt)))) return null
  const common = { component, kind: kind as EvidenceKind | '', ...(collectedAt ? { collectedAt } : {}) }
  if (view === 'case') {
    const fingerprint = params.get('fingerprint') ?? ''
    return /^[a-zA-Z0-9:_-]{1,160}$/.test(fingerprint) ? { view, ...common, fingerprint } : null
  }
  const query = (params.get('q') ?? '').trim()
  const offset = params.get('offset') ?? '0'; const limit = params.get('limit') ?? String(TEST_CASE_PAGE_SIZE)
  if (query.length > 160 || !/^(?:0|[1-9]\d{0,6})$/.test(offset) || Number(offset) > 1_000_000
    || !/^\d{1,2}$/.test(limit) || Number(limit) < 1 || Number(limit) > TEST_CASE_PAGE_SIZE) return null
  return { view, ...common, query, offset: Number(offset), limit: Number(limit) }
}

/** Project before serialization: the default matrix never carries the retained testcase archive. */
export function selectTestReadView(report: TestIntelligenceReport, request: TestReadRequest): TestReadResult {
  if (request.view === 'full') return { ok: true, value: report }
  if (request.view === 'overview') {
    return { ok: true, value: {
      schemaVersion: 1, view: 'overview', collectedAt: report.collectedAt,
      components: report.components.map(component => ({
        component: component.component, domain: component.domain, moneyPath: component.moneyPath,
        cells: Object.fromEntries(TEST_KINDS.map(kind => [kind, testCell(report, component, kind)])) as MatrixComponent['cells'],
        ...(component.running ? { running: { match: component.running.match, latestEvidenceAt: component.running.latestEvidenceAt } } : {}),
      })),
      scope: testScopeCounts(report), totals: report.totals, evidence: report.evidence, warnings: report.warnings,
    } }
  }
  if (request.collectedAt && request.collectedAt !== report.collectedAt) return { ok: false, error: 'evidence_changed', status: 409 }
  if (!report.components.length) return { ok: false, error: 'unavailable', status: 503 }
  const component = report.components.find(item => item.component === request.component)
  if (!component) return { ok: false, error: 'component_not_found', status: 404 }
  if (request.view === 'case') {
    const matches = report.testCases.filter(item => item.component === request.component && item.fingerprint === request.fingerprint && (!request.kind || item.kind === request.kind))
    if (matches.length !== 1) return { ok: false, error: 'test_not_found', status: 404 }
    return { ok: true, value: { schemaVersion: 1, view: 'case', collectedAt: report.collectedAt, testCase: matches[0] } }
  }
  const needle = request.query.toLocaleLowerCase()
  const matches = report.testCases.filter(item => item.component === request.component && (!request.kind || item.kind === request.kind)
    && (!needle || `${item.name} ${item.classname}`.toLocaleLowerCase().includes(needle)))
  const offset = Math.min(request.offset, Math.max(0, Math.ceil(matches.length / request.limit) - 1) * request.limit)
  return { ok: true, value: {
    schemaVersion: 1, view: 'component', collectedAt: report.collectedAt, component,
    tests: matches.slice(offset, offset + request.limit).map(({ fingerprint, component, kind, name, state, lastState, lastObservedAt }) => ({ fingerprint, component, kind, name, state, lastState, lastObservedAt })),
    total: matches.length, offset, limit: request.limit,
  } }
}

const nonnegative = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
/** Old servers ignore view parameters during rollout; normalize their complete response losslessly. */
export function decodeTestReadReply(value: unknown, request: TestReadRequest): TestReadResult {
  if (!record(value) || value.schemaVersion !== 1 || (typeof value.collectedAt !== 'string' || !Number.isFinite(Date.parse(value.collectedAt)))) return { ok: false, error: 'unavailable', status: 502 }
  if (!('view' in value) && Array.isArray(value.components) && Array.isArray(value.testCases) && Array.isArray(value.warnings) && record(value.totals)) {
    return selectTestReadView(value as unknown as TestIntelligenceReport, request)
  }
  if (value.view !== request.view) return { ok: false, error: 'unavailable', status: 502 }
  if ((request.view === 'case' || request.view === 'component') && request.collectedAt && value.collectedAt !== request.collectedAt) return { ok: false, error: 'evidence_changed', status: 409 }
  if (request.view === 'overview' && Array.isArray(value.components) && Array.isArray(value.warnings) && record(value.totals) && record(value.scope) && ['failed', 'stale', 'missing'].every(key => nonnegative((value.scope as Record<string, unknown>)[key]))
    && value.components.every(item => record(item) && typeof item.component === 'string' && record(item.cells)
      && Object.values(item.cells).length === TEST_KINDS.length && TEST_KINDS.every(kind => { const cell = (item.cells as Record<string, unknown>)[kind]; return record(cell) && typeof cell.state === 'string' }))) {
    return { ok: true, value: value as unknown as TestMatrixReport }
  }
  if (request.view === 'component' && record(value.component) && value.component.component === request.component && Array.isArray(value.tests)
    && value.tests.length <= request.limit && nonnegative(value.total) && value.total >= value.tests.length && nonnegative(value.offset) && value.limit === request.limit && Array.isArray(value.component.evidence)
    && value.tests.every(item => record(item) && item.component === request.component && typeof item.fingerprint === 'string' && typeof item.name === 'string' && typeof item.lastState === 'string' && typeof item.lastObservedAt === 'string' && (!request.kind || item.kind === request.kind))) {
    return { ok: true, value: value as unknown as ComponentTestsReply }
  }
  if (request.view === 'case' && record(value.testCase) && value.testCase.component === request.component && value.testCase.fingerprint === request.fingerprint
    && typeof value.testCase.name === 'string' && typeof value.testCase.classname === 'string' && (!request.kind || value.testCase.kind === request.kind)) {
    return { ok: true, value: value as unknown as TestCaseReply }
  }
  return { ok: false, error: 'unavailable', status: 502 }
}
