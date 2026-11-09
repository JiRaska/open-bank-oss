// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parseDocument, parseAllDocuments } from 'yaml'

export const SDLC_SOURCES = {
  rollout: 'openbank-infra/gitops/components/ledger/ledger-service.yaml',
  analysis: 'openbank-infra/gitops/components/argo-rollouts/analysis-template.yaml',
  application: 'openbank-infra/gitops/apps/ledger.yaml',
  train: '.github/workflows/merge-train.yml',
  deploy: '.github/workflows/auto-deploy.yml',
}

function documents(source) {
  return parseAllDocuments(source, { uniqueKeys: true }).map(doc => {
    if (doc.errors.length) throw new Error(doc.errors.map(error => error.message).join('; '))
    return doc.toJS()
  }).filter(Boolean)
}

/** Build evidence, not cluster telemetry. Missing declarations must fail the build. */
export function deriveSdlcEvidence(read) {
  const rollout = documents(read(SDLC_SOURCES.rollout)).find(doc => doc.kind === 'Rollout' && doc.metadata?.name === 'ledger-service')
  const analysis = documents(read(SDLC_SOURCES.analysis)).find(doc => doc.kind === 'ClusterAnalysisTemplate' && doc.metadata?.name === 'openbank-money-path-canary')
  const application = documents(read(SDLC_SOURCES.application)).find(doc => doc.kind === 'Application' && doc.metadata?.name === 'ledger')
  const trainDoc = parseDocument(read(SDLC_SOURCES.train), { uniqueKeys: true })
  const deployDoc = parseDocument(read(SDLC_SOURCES.deploy), { uniqueKeys: true })
  for (const doc of [trainDoc, deployDoc]) if (doc.errors.length) throw new Error(doc.errors[0].message)
  const train = trainDoc.toJS()
  const deploy = deployDoc.toJS()
  const canary = rollout?.spec?.strategy?.canary
  const metrics = analysis?.spec?.metrics
  if (!canary || !Array.isArray(canary.steps) || !Array.isArray(metrics) || !application?.spec?.syncPolicy) {
    throw new Error('SDLC evidence: expected ledger rollout, canary metrics and Argo application declarations')
  }
  const references = canary.analysis?.templates
  if (!Array.isArray(references) || references.length !== 1 || references[0].templateName !== analysis.metadata.name || references[0].clusterScope !== true) {
    throw new Error('SDLC evidence: ledger must reference the evidenced cluster analysis template; update the guide for a changed analysis binding')
  }
  const replicas = rollout.spec.replicas ?? 1
  if (!Number.isInteger(replicas) || replicas < 1) throw new Error('SDLC evidence: invalid replica count')
  const steps = canary.steps.map(step => {
    if (typeof step.setWeight === 'number') return { kind: 'weight', value: step.setWeight }
    if (step.pause) return { kind: 'pause', value: step.pause.duration == null ? null : String(step.pause.duration) }
    throw new Error('SDLC evidence: unsupported canary step; update the guide to explain it')
  })
  const trafficRouting = Boolean(canary.trafficRouting && Object.keys(canary.trafficRouting).length)
  const exactWeightsPossible = trafficRouting || steps.filter(step => step.kind === 'weight')
    .every(step => step.value * replicas % 100 === 0)
  const batchSize = Number(train.on?.workflow_dispatch?.inputs?.batch_size?.default)
  const strictBatchCap = Number(train.env?.MERGE_TRAIN_STRICT_BATCH_CAP)
  if (!Number.isInteger(batchSize) || batchSize < 1 || !Number.isInteger(strictBatchCap) || strictBatchCap < 1) {
    throw new Error('SDLC evidence: missing train batch limits')
  }
  return {
    sources: SDLC_SOURCES,
    train: { batchSize, strictBatchCap, heartbeat: train.on?.schedule?.map(item => item.cron) ?? [] },
    deploy: { mainPush: deploy.on?.push?.branches?.includes('main') === true, reconcile: deploy.on?.schedule?.map(item => item.cron) ?? [] },
    ledger: {
      replicas, trafficRouting, exactWeightsPossible, steps,
      automaticSync: Boolean(application.spec.syncPolicy.automated) && application.spec.syncPolicy.automated.enabled !== false,
      selfHeal: application.spec.syncPolicy.automated?.selfHeal === true,
      prune: application.spec.syncPolicy.automated?.prune === true,
      abortOnDeadline: rollout.spec.progressDeadlineAbort === true,
      deadlineSeconds: rollout.spec.progressDeadlineSeconds ?? null,
      metrics: metrics.map(metric => ({
        name: metric.name,
        successCondition: metric.successCondition ?? null,
        failureCondition: metric.failureCondition ?? null,
        initialDelay: metric.initialDelay ?? null,
        interval: metric.interval ?? null,
        count: metric.count ?? null,
        failureLimit: metric.failureLimit ?? null,
        consecutiveErrorLimit: metric.consecutiveErrorLimit ?? null,
      })),
    },
  }
}

export function readSdlcEvidence(repo) {
  return deriveSdlcEvidence(file => readFileSync(resolve(repo, file), 'utf8'))
}
