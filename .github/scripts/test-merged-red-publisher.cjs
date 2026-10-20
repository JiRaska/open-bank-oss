#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use strict'

// Execute the committed publisher with fake API responses: declaration strings alone cannot
// prove that an unchanged verdict avoids a write.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor
const workflow = fs.readFileSync(path.join(__dirname, '../workflows/merged-past-red-check-watch.yml'), 'utf8')
const anchor = "            const fs = require('fs')"
assert(workflow.includes(anchor), 'publisher script must exist')
const source = workflow.slice(workflow.indexOf(anchor)).split('\n').map(line => line.slice(12)).join('\n')
const publish = new AsyncFunction('require', 'github', 'context', 'core', 'process', source)
let issue
let writes = 0
let suiteCount = 4
let offenders = [{ pushed_at: '2026-10-20T00:00:00Z', pr: 1, head_sha: 'abc123', details: ['Required check is failing.'] }]
const github = { rest: {
  search: { issuesAndPullRequests: async ({ q }) => ({ data: {
    items: issue && q.includes('is:open') && q.includes('A PR was merged') ? [issue] : [],
    total_count: issue ? 1 : 0, incomplete_results: false,
  } }) },
  issues: {
    create: async value => { issue = { ...value, number: 1 }; writes++; return { data: issue } },
    update: async value => { issue = { ...issue, ...value }; writes++ },
    createComment: async () => { throw new Error('unexpected comment') },
  },
} }
const fakeRequire = name => name === 'fs' ? { readFileSync: () => JSON.stringify({
  status: 'dirty', period: 'month', suites_examined: suiteCount, offenders,
}) } : require(name)
const run = () => publish(fakeRequire, github, {
  repo: { owner: 'example', repo: 'example' }, workflow: 'watch', runId: 1,
}, { info: () => {} }, { env: { VERDICT_CODE: '2' } })

async function test() {
  await run()
  assert.equal(writes, 1, 'new finding must create an issue')
  await run()
  assert.equal(writes, 1, 'identical finding must not write')
  suiteCount++
  await run()
  assert.equal(writes, 1, 'benign suite count must not update the finding')
  // Simulate the currently deployed body and legacy volatile footer. The first fixed run
  // must not spend a write just to migrate cosmetic diagnostic text.
  issue.body = issue.body.replace('Window `month`.', 'Window `month`, 99 bypass suites examined.')
    .replace('_Automated by `watch`', '_Automated: `watch` run 42')
  await run()
  assert.equal(writes, 1, 'legacy count and footer must compare equivalent')
  offenders = [...offenders, { ...offenders[0], pr: 2, head_sha: 'def456' }]
  await run()
  assert.equal(writes, 2, 'changed offenders must still update the finding')
  assert.match(issue.title, /^2 merges/)
  console.log('publisher regression: create, unchanged, benign count, legacy migration and changed offenders pass')
}
test().catch(error => { console.error(error); process.exitCode = 1 })
