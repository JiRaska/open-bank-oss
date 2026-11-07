// SPDX-License-Identifier: Apache-2.0
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { validateGateHealthSnapshotData } from '../../openbank-admin-ui/src/lib/evidence/gateHealthSnapshot.ts'
import { publishEvidenceSnapshot } from './publish-admin-ui-snapshot.mjs'

export const DEFAULT_PREFIX = 'admin-ui/gate-health'
export function publishGateHealth(options = {}) {
  return publishEvidenceSnapshot({
    ...options,
    prefix: options.prefix ?? DEFAULT_PREFIX,
    validateSnapshot: validateGateHealthSnapshotData,
    collectedAt: snapshot => snapshot.collectedAt,
  })
}

export function parseArgs(argv, env = process.env) {
  const options = { prefix: DEFAULT_PREFIX }
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index]
    const value = argv[index + 1]
    if (!value || value.startsWith('--')) throw new Error(`missing value for ${flag}`)
    if (flag === '--file') options.file = value
    else if (flag === '--bucket') options.bucket = value
    else if (flag === '--prefix') options.prefix = value
    else if (flag === '--rollback') options.rollback = value
    else throw new Error(`unknown argument ${flag}`)
    index += 1
  }
  options.bucket ??= env.EVIDENCE_BUCKET
  return options
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    const result = publishGateHealth(parseArgs(process.argv.slice(2)))
    process.stdout.write(`Published gate-health snapshot ${result.snapshot}\n`)
  } catch (error) {
    process.stderr.write(`Gate-health publication failed: ${error instanceof Error ? error.message : 'unknown error'}\n`)
    process.exitCode = 1
  }
}
