// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const assert = require('node:assert/strict')
const { eventIsSuperseded, failedJobsCovered, waitForCurrentVerdict, LatestRunLagError } = require('./main-red-watch-order.cjs')

const event = { run_id: 100, attempt: 1 }
assert.equal(eventIsSuperseded(event, { id: 101, run_attempt: 1 }), true)
assert.equal(eventIsSuperseded(event, { id: 100, run_attempt: 2 }), true)
assert.equal(eventIsSuperseded(event, { id: 100, run_attempt: 1 }), false)
assert.throws(() => eventIsSuperseded(event, { id: 99, run_attempt: 1 }), /behind the event/)
assert.throws(() => eventIsSuperseded({ run_id: 100, attempt: 2 }, { id: 100, run_attempt: 1 }), /behind the event/)
assert.throws(() => eventIsSuperseded(event, null), LatestRunLagError)
assert.throws(() => eventIsSuperseded({ ...event, attempt: 0 }, { id: 100, run_attempt: 1 }), /invalid attempt/)

// 2026-09-29: a slow red CI completion reopened #4191 after a newer green CI run.
assert.equal(eventIsSuperseded(
  { run_id: 36552705919, attempt: 1 },
  { id: 36554533955, run_attempt: 1 },
), true)
const issue = [
  '<!-- main-red:services-ci -->',
  '### Every failing job in this attempt',
  '',
  '- `build (openbank-document-service) / openbank-document-service (contract)` [failure] — `Provider verification`',
  '- `all-green` [failure] — `Verify no service failed`',
  '',
  'Read this as an incident.',
].join('\n')
async function main() {
  const leaf = 'build (openbank-document-service) / openbank-document-service (contract)'
  assert.equal(failedJobsCovered(issue, ['all-green']), false) // unrelated six-job green run
  assert.equal(failedJobsCovered(issue, [leaf]), false) // aggregate did not complete
  assert.equal(failedJobsCovered(issue, [leaf, 'all-green']), true)
  assert.equal(failedJobsCovered('legacy issue without a job list', [leaf, 'all-green']), false)
  assert.equal(failedJobsCovered(issue.replace('[failure]', '[unknown]'), [leaf, 'all-green']), false)
  assert.equal(failedJobsCovered(issue.replace('- `all-green`', 'unparseable job\n- `all-green`'), [leaf, 'all-green']), false)
  assert.equal(failedJobsCovered(issue, null), false)
  const delays = []
  const runs = [{ id: 99, run_attempt: 1 }, null, { id: 100, run_attempt: 1 }]
  const current = await waitForCurrentVerdict(
    event, async () => runs.shift(), async ms => delays.push(ms), [1, 2],
  )
  assert.deepEqual(current, { latest: { id: 100, run_attempt: 1 }, superseded: false })
  assert.deepEqual(delays, [1, 2])

  // A newer completion appearing during indexing wins; no old red verdict may reopen an issue.
  const newer = [{ id: 99, run_attempt: 1 }, { id: 101, run_attempt: 1 }]
  assert.equal((await waitForCurrentVerdict(
    event, async () => newer.shift(), async () => {}, [1],
  )).superseded, true)

  // Exhaustion, malformed data and transport errors remain fail-closed.
  let reads = 0
  await assert.rejects(waitForCurrentVerdict(
    event, async () => { reads++; return { id: 99, run_attempt: 1 } }, async () => {}, [1, 2],
  ), LatestRunLagError)
  assert.equal(reads, 3)
  reads = 0
  await assert.rejects(waitForCurrentVerdict(
    event, async () => { reads++; return { id: '100', run_attempt: 1 } }, async () => {}, [1, 2],
  ), /invalid latestId/)
  assert.equal(reads, 1)
  reads = 0
  await assert.rejects(waitForCurrentVerdict(
    event, async () => { reads++; throw new Error('API unavailable') }, async () => {}, [1, 2],
  ), /API unavailable/)
  assert.equal(reads, 1)
  console.log('main-red-watch run ordering, scoped recovery and index-lag retry: passed')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
