// SPDX-License-Identifier: Apache-2.0
// Project the `identifier-conformance` CI artifact (identifier-conformance/v1, written by
// .github/scripts/check-identifier-validator-inventory.py) into the ADR-0273 snapshot as its
// own dimension: spec conformance. It is kept apart from execution, code coverage and
// scenario coverage on purpose: a validator can be executed, covered and still wrong.
//
// Three rules carry the whole design:
//   * no artifact (or an unreadable one) is "no evidence", never green;
//   * `not-run` is not `pass` — a differential that did not run cannot pass a validator;
//   * an unverified ad-hoc copy is unverified however its other columns read.

export const IDENTIFIER_CONFORMANCE_SCHEMA = 'identifier-conformance/v1'

const RESULT_VALUES = new Set(['pass', 'fail', 'not-run'])
const STATUSES = new Set(['conformance-tested', 'known-defect', 'unverified-ad-hoc-copy'])
const FUZZ_LABELS = new Set(['semantic', 'robustness-only', 'none'])
const PITEST = new Set(['advisory', 'service-lane', 'none'])

const strings = value => Array.isArray(value) ? value.filter(item => typeof item === 'string') : []
const result = value => RESULT_VALUES.has(value) ? value : 'not-run'
const httpsUrl = value => typeof value === 'string' && value.startsWith('https://') ? value : null

/**
 * A fuzzer is conformance evidence only when the matrix says its properties are checked
 * against a semantic reference. Anything else that fuzzes — explicitly robustness-only, or a
 * fuzzer with no label the matrix vouches for — only proves the validator does not crash.
 */
export function fuzzIsRobustnessOnly(fuzz) {
  if (!fuzz || typeof fuzz !== 'object') return false
  if (fuzz.label === 'robustness-only') return true
  return typeof fuzz.fuzzer === 'string' && fuzz.fuzzer.length > 0 && fuzz.label !== 'semantic'
}

function validatorRow(row, freshnessAwareState) {
  const status = STATUSES.has(row.status) ? row.status : 'unverified-ad-hoc-copy'
  const hasReference = row.differential === true
  const res = row.result && typeof row.result === 'object' ? row.result : null
  const vectors = res ? result(res.vectors) : 'not-run'
  const differential = hasReference ? (res ? result(res.differential) : 'not-run') : 'no-reference'
  const at = res && typeof res.at === 'string' ? res.at : null
  const vectorCount = Number.isInteger(row.vectors) && row.vectors >= 0 ? row.vectors : 0
  let state
  if (status === 'unverified-ad-hoc-copy') state = 'unknown'
  else if (vectors === 'fail' || differential === 'fail') state = 'failed'
  else if (status === 'known-defect') state = 'blocked'
  else if (vectorCount > 0 && vectors === 'pass' && (differential === 'pass' || differential === 'no-reference')) {
    state = freshnessAwareState('passed', at)
  } else state = 'not-run'
  const fuzz = row.fuzz && typeof row.fuzz === 'object' ? row.fuzz : {}
  return {
    identifier: typeof row.identifier === 'string' ? row.identifier : 'unknown',
    module: typeof row.module === 'string' ? row.module : 'unknown',
    symbol: typeof row.symbol === 'string' ? row.symbol : '',
    file: typeof row.file === 'string' ? row.file : '',
    status, state,
    vectors: { count: vectorCount, sources: strings(row.sources), result: vectors },
    differential: { reference: typeof row.reference === 'string' ? row.reference : null, result: differential },
    fuzz: {
      fuzzer: typeof fuzz.fuzzer === 'string' ? fuzz.fuzzer : null,
      label: FUZZ_LABELS.has(fuzz.label) ? fuzz.label : 'none',
      robustnessOnly: fuzzIsRobustnessOnly(fuzz),
    },
    pitest: PITEST.has(row.pitest) ? row.pitest : 'none',
    knownDefects: strings(row.known_defects),
    lastRun: { at, url: res ? httpsUrl(res.run) : null },
  }
}

/**
 * @param matrix parsed artifact JSON, or null when no artifact was staged
 * @param freshnessAwareState the shared ADR-0273 freshness rule (createRunProjection)
 */
export function projectIdentifierConformance(matrix, freshnessAwareState) {
  if (matrix === null || matrix === undefined) {
    return {
      state: 'absent', observedAt: null, run: null, validators: [],
      detail: 'No identifier-conformance artifact from a main run was staged for this deployment: there is no spec-conformance evidence.',
    }
  }
  if (matrix?.schema !== IDENTIFIER_CONFORMANCE_SCHEMA || !Array.isArray(matrix.validators)) {
    return {
      state: 'invalid', observedAt: null, run: null, validators: [],
      detail: `The staged identifier-conformance artifact is not ${IDENTIFIER_CONFORMANCE_SCHEMA}; it is treated as no evidence.`,
    }
  }
  const validators = matrix.validators.filter(row => row && typeof row === 'object')
    .map(row => validatorRow(row, freshnessAwareState))
    .sort((a, b) => a.identifier.localeCompare(b.identifier) || a.module.localeCompare(b.module))
  const observed = validators.map(row => row.lastRun.at).filter(at => Number.isFinite(Date.parse(at ?? '')))
    .sort((a, b) => Date.parse(b) - Date.parse(a))
  const run = validators.map(row => row.lastRun.url).find(Boolean) ?? null
  return {
    state: 'present', observedAt: observed[0] ?? null, run, validators,
    detail: observed.length === 0
      ? 'The matrix carries no run verdicts, so no validator can read as conformance-tested.'
      : 'Spec vectors and the independent reference are verdicts of the run linked; fuzz labels and pitest lanes are declarations.',
  }
}
