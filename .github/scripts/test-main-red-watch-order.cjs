// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const assert = require('node:assert/strict')
const { eventIsSuperseded } = require('./main-red-watch-order.cjs')

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
console.log('main-red-watch run ordering: 8 cases passed')
