#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
/**
 * Project one service's CI run envelope into the `ComponentTestPosture` the commit-addressed
 * evidence store serves (#13201), and print the object key it belongs under. Uploading is the
 * caller's job (`_service-ci.yml`, write-once via `If-None-Match: *`); this script only decides
 * WHAT is published and WHERE, so both are unit-testable without AWS.
 *
 *   node publish-test-evidence.mjs --run <run.json> --component openbank-x --commit <40-hex> \
 *     --released true|false --money-path true|false --out <posture.json> \
 *     [--key-commit <40-hex> --equivalence tree-equivalent]
 * `--commit` is the commit the tests ran on; `--key-commit` the running image's commit, which
 * may differ only after the caller proved tree equivalence (see reconcile-test-evidence.py).
 * stdout: the object key. Exit 1 (nothing written) when the envelope must not be published.
 */
import fs from 'fs'
import { componentPostureFromRun, evidenceStoreKey } from '../src/lib/test-intelligence-run-projection.mjs'

const args = process.argv.slice(2)
const arg = (name, fallback) => {
  const index = args.indexOf(name)
  if (index < 0 || index + 1 >= args.length) {
    if (fallback !== undefined) return fallback
    throw new Error(`missing ${name}`)
  }
  return args[index + 1]
}
const bool = name => {
  const value = arg(name)
  if (value !== 'true' && value !== 'false') throw new Error(`${name} must be true or false`)
  return value === 'true'
}

try {
  const component = arg('--component')
  const commit = arg('--commit')
  const keyCommit = arg('--key-commit', commit)
  const equivalence = arg('--equivalence', 'identical')
  const key = evidenceStoreKey(component, keyCommit)
  const run = JSON.parse(fs.readFileSync(arg('--run'), 'utf8'))
  const { posture, warnings } = componentPostureFromRun(run, {
    component, commit, keyCommit, equivalence, released: bool('--released'), moneyPath: bool('--money-path'),
    collectedAt: new Date(), staleAfterMs: 14 * 86_400_000,
  })
  for (const warning of warnings) console.error(`::warning::${warning}`)
  fs.writeFileSync(arg('--out'), `${JSON.stringify(posture)}\n`)
  process.stdout.write(`${key}\n`)
} catch (error) {
  console.error(`::error::not publishing Test Intelligence evidence: ${error.message}`)
  process.exit(1)
}
