// SPDX-License-Identifier: Apache-2.0
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { publishEvidenceSnapshot } from './publish-admin-ui-snapshot.mjs'
import { validateGateHealthSnapshotData } from '../../openbank-admin-ui/src/lib/evidence/gateHealthSnapshot.ts'
import { isReadinessSnapshot } from '../../openbank-admin-ui/src/lib/evidence/readinessSnapshot.ts'
import { validateTestIntelligenceSnapshot, testIntelligenceCollectedAt } from '../../openbank-admin-ui/src/lib/evidence/testIntelligenceSnapshot.ts'

import { validateSecurityKpiSnapshot, securityKpiCollectedAt } from '../../openbank-admin-ui/src/lib/security/kpiSnapshot.ts'

export const DATASETS = Object.freeze({
  'gate-health': { validateSnapshot: validateGateHealthSnapshotData, collectedAt: value => value.collectedAt },
  readiness: { validateSnapshot: isReadinessSnapshot, collectedAt: value => value.collected_at, snapshotSourceSha: value => value.source_sha },
  'test-intelligence': { validateSnapshot: validateTestIntelligenceSnapshot, collectedAt: testIntelligenceCollectedAt },
  'security-kpis': { validateSnapshot: validateSecurityKpiSnapshot, collectedAt: securityKpiCollectedAt },
})

export function publishDataset({ dataset, ...options }) {
  if (!Object.hasOwn(DATASETS, dataset)) throw new Error('unknown evidence dataset')
  return publishEvidenceSnapshot({ ...options, ...DATASETS[dataset], prefix: `admin-ui/${dataset}` })
}

export function parseArgs(argv, env = process.env) {
  const options = { bucket: env.EVIDENCE_BUCKET }
  const flags = { '--dataset': 'dataset', '--bucket': 'bucket', '--file': 'file', '--rollback': 'rollback' }
  for (let index = 0; index < argv.length; index += 2) {
    const key = flags[argv[index]]
    const value = argv[index + 1]
    if (!key || !value || value.startsWith('--')) throw new Error(`invalid argument ${argv[index]}`)
    if (key !== 'bucket' && options[key] !== undefined) throw new Error(`duplicate argument ${argv[index]}`)
    options[key] = value
  }
  if (!Object.hasOwn(DATASETS, options.dataset)) throw new Error('a supported --dataset is required')
  return options
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    const options = parseArgs(process.argv.slice(2))
    const result = publishDataset(options)
    process.stdout.write(`Published ${options.dataset} snapshot ${result.snapshot}\n`)
  } catch (error) {
    process.stderr.write(`Evidence publication failed: ${error instanceof Error ? error.message : 'unknown error'}\n`)
    process.exitCode = 1
  }
}
