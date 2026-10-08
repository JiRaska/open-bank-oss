// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const assert = require('node:assert/strict')
const { eventIsSuperseded, failedJobsCovered } = require('./main-red-watch-order.cjs')

const event = { run_id: 100, attempt: 1 }
assert.equal(eventIsSuperseded(event, { id: 101, run_attempt: 1 }), true)
assert.equal(eventIsSuperseded(event, { id: 100, run_attempt: 2 }), true)
assert.equal(eventIsSuperseded(event, { id: 100, run_attempt: 1 }), false)
assert.throws(() => eventIsSuperseded(event, { id: 99, run_attempt: 1 }), /behind the event/)
assert.throws(() => eventIsSuperseded({ run_id: 100, attempt: 2 }, { id: 100, run_attempt: 1 }), /behind the event/)
assert.throws(() => eventIsSuperseded(event, null), /invalid latestId/)
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
const leaf = 'build (openbank-document-service) / openbank-document-service (contract)'
assert.equal(failedJobsCovered(issue, ['all-green']), false) // unrelated six-job green run
assert.equal(failedJobsCovered(issue, [leaf]), false) // aggregate did not complete
assert.equal(failedJobsCovered(issue, [leaf, 'all-green']), true)
assert.equal(failedJobsCovered('legacy issue without a job list', [leaf, 'all-green']), false)
assert.equal(failedJobsCovered(issue.replace('[failure]', '[unknown]'), [leaf, 'all-green']), false)
assert.equal(failedJobsCovered(issue.replace('- `all-green`', 'unparseable job\n- `all-green`'), [leaf, 'all-green']), false)
assert.equal(failedJobsCovered(issue, null), false)
console.log('main-red-watch run ordering and scoped recovery: 15 cases passed')
