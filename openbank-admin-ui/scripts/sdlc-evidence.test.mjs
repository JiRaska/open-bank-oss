// SPDX-License-Identifier: Apache-2.0
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { stringify } from 'yaml'
import { deriveSdlcEvidence, SDLC_SOURCES } from './lib/sdlc-evidence.mjs'

function fixture({ replicas = 1, routing = false, weights = [10, 30, 100] } = {}) {
  return {
    [SDLC_SOURCES.rollout]: { kind: 'Rollout', metadata: { name: 'ledger-service' }, spec: {
      replicas, progressDeadlineAbort: true, progressDeadlineSeconds: 600,
      strategy: { canary: { analysis: { templates: [{ templateName: 'openbank-money-path-canary', clusterScope: true }] }, ...(routing ? { trafficRouting: { nginx: { stableIngress: 'example' } } } : {}), steps: weights.map(setWeight => ({ setWeight })) } },
    } },
    [SDLC_SOURCES.analysis]: { kind: 'ClusterAnalysisTemplate', metadata: { name: 'openbank-money-path-canary' }, spec: { metrics: [
      { name: 'error-rate', successCondition: 'len(result) == 0 || isNaN(result[0]) || result[0] < 0.01', failureCondition: 'result[0] >= 0.05', failureLimit: 1, count: 5 },
    ] } },
    [SDLC_SOURCES.application]: { kind: 'Application', metadata: { name: 'ledger' }, spec: { syncPolicy: { automated: { selfHeal: true, prune: true } } } },
    [SDLC_SOURCES.train]: { on: { workflow_dispatch: { inputs: { batch_size: { default: 16 } } }, schedule: [{ cron: '*/10 * * * *' }] }, env: { MERGE_TRAIN_STRICT_BATCH_CAP: '8' } },
    [SDLC_SOURCES.deploy]: { on: { push: { branches: ['main'] }, schedule: [{ cron: '23 */3 * * *' }] } },
  }
}
const derive = data => deriveSdlcEvidence(file => stringify(data[file]))

test('does not present 10/30 weights as expressible with one replica and no router', () => {
  const evidence = derive(fixture())
  assert.equal(evidence.ledger.exactWeightsPossible, false)
  assert.equal(evidence.ledger.trafficRouting, false)
  assert.equal(evidence.ledger.replicas, 1)
})
test('updates evidence when replica count, routing or rollout steps change', () => {
  assert.equal(derive(fixture({ replicas: 10 })).ledger.exactWeightsPossible, true)
  assert.equal(derive(fixture({ replicas: 2 })).ledger.exactWeightsPossible, false)
  assert.equal(derive(fixture({ routing: true })).ledger.exactWeightsPossible, true)
  const changed = derive(fixture({ weights: [0, 100] }))
  assert.equal(changed.ledger.exactWeightsPossible, true)
  assert.deepEqual(changed.ledger.steps.map(step => step.value), [0, 100])
})
test('preserves actual success, failure and error settings instead of a generic threshold', () => {
  const data = fixture()
  data[SDLC_SOURCES.analysis].spec.metrics[0].consecutiveErrorLimit = 60
  const metric = derive(data).ledger.metrics[0]
  assert.match(metric.successCondition, /len\(result\) == 0/)
  assert.match(metric.successCondition, /isNaN/)
  assert.equal(metric.failureCondition, 'result[0] >= 0.05')
  assert.equal(metric.failureLimit, 1)
  assert.equal(metric.consecutiveErrorLimit, 60)
})
test('fails closed on missing resources, unsupported steps or duplicate YAML keys', () => {
  const missing = fixture(); missing[SDLC_SOURCES.rollout].kind = 'Deployment'
  assert.throws(() => derive(missing), /expected ledger rollout/)
  const changed = fixture(); changed[SDLC_SOURCES.rollout].spec.strategy.canary.steps = [{ experiment: {} }]
  assert.throws(() => derive(changed), /unsupported canary step/)
  assert.throws(() => deriveSdlcEvidence(() => 'kind: Rollout\nkind: Deployment\n'), /unique|repeated|map keys/i)
})
test('does not describe an unbound or substituted analysis as active for ledger', () => {
  const data = fixture()
  delete data[SDLC_SOURCES.rollout].spec.strategy.canary.analysis
  assert.throws(() => derive(data), /analysis binding/)
  data[SDLC_SOURCES.rollout].spec.strategy.canary.analysis = { templates: [{ templateName: 'another-template', clusterScope: true }] }
  assert.throws(() => derive(data), /analysis binding/)
})
test('respects explicit disable of automated sync instead of its object presence', () => {
  const data = fixture()
  assert.equal(derive(data).ledger.automaticSync, true)
  data[SDLC_SOURCES.application].spec.syncPolicy.automated.enabled = false
  assert.equal(derive(data).ledger.automaticSync, false)
})
