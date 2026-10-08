// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const assert = require('node:assert/strict')
const {
  eventIsSuperseded, latestIndexedRun, failedJobsCovered, remainingFailureLines, withFailureLines,
  mergeFailureLines, unresolvedAfterLaterRuns, successfulAfterLaterRuns,
  fetchCompleteAttemptJobs, waitForCurrentVerdict, LatestRunLagError,
} = require('./main-red-watch-order.cjs')

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

  // Red R1 fails A. Red R2 fails B without running A. Green R3 runs B only.
  // A must survive both the red refresh and the partial green.
  const a = '- `A` [failure] — `Test A`'
  const b = '- `B` [failure] — `Test B`'
  const r1 = ['<!-- main-red:services-ci -->', '### Unresolved failing jobs', '', a, '', 'Context.'].join('\n')
  const r2 = withFailureLines(r1, mergeFailureLines(remainingFailureLines(r1, ['C']), [b]))
  assert.deepEqual(remainingFailureLines(r2, []), [a, b])
  assert.equal(failedJobsCovered(r2, ['B']), false)
  const r3 = withFailureLines(r2, remainingFailureLines(r2, ['B']))
  assert.deepEqual(remainingFailureLines(r3, []), [a])
  assert.equal(failedJobsCovered(r3, ['A']), true)
  assert.equal(remainingFailureLines(r3, ['A']).length, 0)
  assert.equal(mergeFailureLines([a], ['- `A` [timed_out] — latest failure'])[0], '- `A` [timed_out] — latest failure')
  assert.throws(() => withFailureLines('malformed issue', [a]), /malformed/)
  // A newer green that built B only must not erase a slow red A completion.
  assert.deepEqual(unresolvedAfterLaterRuns([a], [[{ name: 'B', conclusion: 'success' }]]), [a])
  assert.deepEqual(unresolvedAfterLaterRuns([a], [[{ name: 'A', conclusion: 'success' }]]), [])
  assert.deepEqual(unresolvedAfterLaterRuns([a], [
    [{ name: 'A', conclusion: 'success' }], [{ name: 'A', conclusion: 'failure' }],
  ]), [a])
  // R1 fails A; R2 succeeds A; R3 fails B without A. R3 refreshes [A,B]
  // before R2's delayed event arrives. The delayed green must remove A.
  const r3BeforeR2 = withFailureLines(r1, mergeFailureLines([a], [b]))
  const effectiveR2Success = successfulAfterLaterRuns(['A'], [[{ name: 'B', conclusion: 'failure' }]])
  assert.deepEqual(effectiveR2Success, ['A'])
  assert.deepEqual(remainingFailureLines(r3BeforeR2, effectiveR2Success), [b])
  assert.deepEqual(successfulAfterLaterRuns(['A'], [
    [{ name: 'A', conclusion: 'failure' }],
  ]), []) // A failed again after R2; the delayed green must not erase it.
  assert.deepEqual(successfulAfterLaterRuns(['A'], [
    [{ name: 'A', conclusion: 'failure' }], [{ name: 'A', conclusion: 'success' }],
  ]), ['A'])
  const job = (id, name, conclusion) => ({ id, name, conclusion, status: 'completed' })
  const completeJobs = [job(1, 'A', 'failure'), job(2, 'B', 'success')]
  assert.deepEqual(await fetchCompleteAttemptJobs(async () => ({
    total_count: 2, jobs: completeJobs,
  })), completeJobs)
  // A missing later A failure must not let the older A success clear the issue.
  await assert.rejects(fetchCompleteAttemptJobs(async () => ({
    total_count: 2, jobs: [job(2, 'B', 'success')],
  })), /Incomplete/)
  await assert.rejects(fetchCompleteAttemptJobs(async () => ({
    total_count: 2, jobs: [job(1, 'A', 'failure'), job(1, 'B', 'success')],
  })), /malformed/)
  await assert.rejects(fetchCompleteAttemptJobs(async () => ({
    total_count: 1, jobs: [{ ...job(1, 'A', 'failure'), status: 'in_progress' }],
  })), /malformed/)
  await assert.rejects(fetchCompleteAttemptJobs(async () => ({
    total_count: 1, jobs: [job(1, 'A', null)],
  })), /malformed/)
  const firstPage = Array.from({ length: 100 }, (_, index) => job(index + 1, `job-${index}`, 'success'))
  const pages = [{ total_count: 101, jobs: firstPage }, { total_count: 101, jobs: [job(101, 'A', 'failure')] }]
  assert.equal((await fetchCompleteAttemptJobs(async page => pages[page - 1])).length, 101)
  await assert.rejects(fetchCompleteAttemptJobs(async page =>
    page === 1 ? pages[0] : { total_count: 100, jobs: [job(101, 'A', 'failure')] }), /malformed/)

  const run100 = { id: 100, run_attempt: 1 }
  const run101 = { id: 101, run_attempt: 1 }
  assert.equal(latestIndexedRun(event, [run101]), null) // newer run indexed, event missing
  assert.deepEqual(latestIndexedRun(event, [run101, run100]), run101)
  assert.equal(latestIndexedRun({ run_id: 100, attempt: 2 }, [run100, run101]), null)
  assert.throws(() => latestIndexedRun(event, [{ id: '100', run_attempt: 1 }]), /Malformed/)
  const indexSnapshots = [[run101], [run101, run100]]
  const indexWaits = []
  const indexed = await waitForCurrentVerdict(event,
    async () => latestIndexedRun(event, indexSnapshots.shift()),
    async ms => indexWaits.push(ms), [1])
  assert.deepEqual(indexed, { latest: run101, superseded: true })
  assert.deepEqual(indexWaits, [1])
  await assert.rejects(waitForCurrentVerdict(event,
    async () => latestIndexedRun(event, [run101]), async () => {}, [1]), LatestRunLagError)

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
