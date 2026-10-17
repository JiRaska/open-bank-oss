// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const assert = require('node:assert/strict')
const { applyVerdict, runCatchup, bootstrapCheckpoint, waitForIndex } = require('./main-red-watch-catchup.cjs')
const { parseCheckpoint, readCheckpoint, writeCheckpoint } = require('./main-red-watch-checkpoint.cjs')
const { LatestRunLagError, unprocessedAttempts } = require('./main-red-watch-order.cjs')

const context = { repo: { owner: 'o', repo: 'r' }, workflow: 'main red watch', runId: 900 }
const leaf = 'build (openbank-document-service) / openbank-document-service (contract)'
const failed = job => ({ job, conclusion: 'failure',
  steps: [job === 'all-green' ? 'Verify no service failed' : 'Provider verification'],
  job_url: 'https://example.invalid/log' })
const succeeded = (job, steps) => ({ job, steps })
const greenJob = job => succeeded(job, [job === 'all-green' ? 'Verify no service failed' :
  job.includes('ledger') ? 'Provider verification (:openbank-ledger-service:providerPactTest)' : 'Provider verification'])
const verdict = (run_id, status, failing, successful_jobs = []) => ({
  run_id, attempt: 1, status, failing,
  successful_jobs: successful_jobs.map(job => typeof job === 'string' ? greenJob(job) : job),
  workflow: 'Services CI',
  head_sha: 'abc123456789', html_url: 'https://example.invalid/run',
})

async function main() {
  const firstEvent = { id: 102, created_at: '2026-10-11T01:00:00Z' }
  const bootstrapApi = runs => ({ rest: { actions: { listWorkflowRuns: async () => ({ data: runs }) } } })
  // A short Actions page can still claim older runs in total_count. It must
  // never seed the cursor at 102 and silently skip the missing red 101.
  await assert.rejects(bootstrapCheckpoint(bootstrapApi({ total_count: 2,
    workflow_runs: [{ id: 102 }] }), context, 7, firstEvent), LatestRunLagError)
  assert.deepEqual(await bootstrapCheckpoint(bootstrapApi({ total_count: 2,
    workflow_runs: [{ id: 102 }, { id: 101 }] }), context, 7, firstEvent),
  { run_id: 101, attempt: 0 })

  let indexReads = 0
  const sleeps = []
  assert.equal(await waitForIndex(async () => {
    if (++indexReads === 1) throw new LatestRunLagError('not indexed yet')
    return 42
  }, async ms => sleeps.push(ms)), 42)
  assert.deepEqual(sleeps, [1000])
  await assert.rejects(waitForIndex(async () => { throw new Error('permission denied') },
    async () => { throw new Error('unexpected wait') }), /permission denied/)
  assert.throws(() => parseCheckpoint({ version: 1, workflow_id: 7, run_id: 100, attempt: -1 }, 7), /Malformed/)
  assert.throws(() => parseCheckpoint({ version: 1, workflow_id: 8, run_id: 100, attempt: 1 }, 7), /Malformed/)

  let content = null
  let currentSha = null
  const files = {
    rest: { repos: {
      getContent: async () => {
        if (!content) throw Object.assign(new Error('missing'), { status: 404 })
        return { data: { type: 'file', sha: currentSha, encoding: 'base64', content } }
      },
      createOrUpdateFileContents: async args => {
        if ((args.sha || null) !== currentSha) throw Object.assign(new Error('stale SHA'), { status: 409 })
        currentSha = `sha-${Number((currentSha || 'sha-0').split('-')[1]) + 1}`
        content = args.content
        return { data: { content: { sha: currentSha } } }
      },
    } },
  }
  assert.deepEqual(await readCheckpoint(files, context.repo, 7), { checkpoint: null, sha: null })
  const cursor = { version: 1, workflow_id: 7, run_id: 101, attempt: 1 }
  assert.equal(await writeCheckpoint(files, context.repo, 7, cursor, null), 'sha-1')
  assert.deepEqual((await readCheckpoint(files, context.repo, 7)).checkpoint, cursor)
  await assert.rejects(writeCheckpoint(files, context.repo, 7,
    { ...cursor, run_id: 102 }, 'stale'), /advanced concurrently/)
  assert.deepEqual((await readCheckpoint(files, context.repo, 7)).checkpoint, cursor)
  const originalWrite = files.rest.repos.createOrUpdateFileContents
  let branchRace = true
  files.rest.repos.createOrUpdateFileContents = async args => {
    if (branchRace) {
      branchRace = false
      throw Object.assign(new Error('other workflow updated branch'), { status: 409 })
    }
    return originalWrite(args)
  }
  assert.equal(await writeCheckpoint(files, context.repo, 7,
    { ...cursor, run_id: 102 }, 'sha-1'), 'sha-2')

  const issues = []
  const github = {
    paginate: async (_method, args) => issues.filter(i => i.state === args.state),
    rest: { issues: {
      listForRepo: async () => {},
      create: async args => {
        const issue = { number: issues.length + 1, state: 'open', title: args.title, body: args.body }
        issues.push(issue)
        return { data: issue }
      },
      update: async args => {
        const issue = issues.find(i => i.number === args.issue_number)
        Object.assign(issue, args)
        if (args.state === 'closed') issue.closed_at = new Date().toISOString()
        return { data: issue }
      },
      createComment: async () => ({ data: {} }),
    } },
  }
  // The red event's own watcher was replaced before it created an issue. A
  // later green event replays red 101 first, then scoped green 102. The issue
  // survives until the former failing leaf really runs successfully.
  await applyVerdict(github, context, verdict(101, 'red', [failed(leaf), failed('all-green')]))
  assert.equal(issues.length, 1)
  assert.equal(issues[0].state, 'open')
  await applyVerdict(github, context, verdict(101, 'red', [failed(leaf), failed('all-green')]))
  assert.equal(issues.length, 1)
  await applyVerdict(github, context, verdict(102, 'green', [], ['all-green']))
  assert.equal(issues[0].state, 'open')
  assert.match(issues[0].body, /openbank-document-service/)
  await applyVerdict(github, context, verdict(103, 'green', [], [leaf, 'all-green']))
  assert.equal(issues[0].state, 'closed')
  // A repeated green after issue mutation but before cursor persistence must
  // be idempotent, and must not create another issue.
  await applyVerdict(github, context, verdict(103, 'green', [], [leaf, 'all-green']))
  assert.equal(issues.length, 1)

  // #4195 predates this watch: its actual issue body uses the old heading and
  // records both the ledger provider failure and the aggregate all-green job.
  // Services CI 38169631397 on e6e2e5af succeeded after building consent only.
  // Main-red watch 38171206078 then falsely closed #4195 although the
  // ledger provider verification from 38129335305 never ran again.
  const ledger = 'build (openbank-ledger-service) / openbank-ledger-service (contract)'
  const legacy = { number: 2, state: 'open', title: 'main is red: Services CI', body: [
    '<!-- main-red:services-ci -->',
    '## `main` is red — nothing else would have told you',
    '',
    '### Every failing job in this attempt',
    '',
    `- \`${ledger}\` [failure] — \`Provider verification (:openbank-ledger-service:providerPactTest)\` ([log](https://example.invalid/ledger))`,
    '- `all-green` [failure] — `Verify no service failed` ([log](https://example.invalid/all-green))',
    '',
    'The ledger provider contract is still failing.',
  ].join('\n') }
  issues.push(legacy)
  await applyVerdict(github, context, verdict(38169631397, 'green', [], [
    'Detect changed services',
    'build (openbank-consent-service) / openbank-consent-service (build)',
    'build (openbank-consent-service) / openbank-consent-service (contract)',
    'all-green',
  ]))
  assert.equal(legacy.state, 'open')
  assert.match(legacy.body, /build \(openbank-ledger-service\) \/ openbank-ledger-service \(contract\)/)
  assert.doesNotMatch(legacy.body, /- `all-green` \[failure\]/)
  // The next run, 38170756443 at b433e3e18, built tax-reporting only.
  // Watch 38171689719 also falsely closed #4195 on this scoped green.
  await applyVerdict(github, context, verdict(38170756443, 'green', [], [
    'Detect changed services',
    'verification-metadata (openbank-tax-reporting-service)',
    'build (openbank-tax-reporting-service) / openbank-tax-reporting-service (build)',
    'build (openbank-tax-reporting-service) / openbank-tax-reporting-service (contract)',
    'all-green',
  ]))
  assert.equal(legacy.state, 'open')
  assert.match(legacy.body, /build \(openbank-ledger-service\) \/ openbank-ledger-service \(contract\)/)
  // Watch 38174993420 falsely closed #4195 after Services CI 38174474375
  // on 07811147. Its build and verification-metadata jobs were skipped;
  // only Detect changed services and all-green succeeded. The newer
  // 38177090662 on e473c2a repeated the same no-service shape.
  for (const runId of [38174474375, 38177090662]) {
    await applyVerdict(github, context, verdict(runId, 'green', [], [
      'Detect changed services', 'all-green',
    ]))
    assert.equal(legacy.state, 'open')
    assert.match(legacy.body, /build \(openbank-ledger-service\) \/ openbank-ledger-service \(contract\)/)
  }
  // Watch 38194688603 falsely closed #4195 again after Services CI
  // 38188489700 on f05874a76. That green run built pension-fund only;
  // the ledger provider job was absent from its complete job list.
  await applyVerdict(github, context, verdict(38188489700, 'green', [], [
    'Detect changed services',
    'verification-metadata (openbank-pension-fund-service)',
    'build (openbank-pension-fund-service) / openbank-pension-fund-service (build)',
    'build (openbank-pension-fund-service) / openbank-pension-fund-service (contract)',
    'all-green',
  ]))
  assert.equal(legacy.state, 'open')
  assert.match(legacy.body, /build \(openbank-ledger-service\) \/ openbank-ledger-service \(contract\)/)
  // A reused job name can succeed after its provider step is skipped or absent.
  // The exact legacy failure must survive both outcomes.
  await applyVerdict(github, context, verdict(105, 'green', [], [
    succeeded(ledger, ['Setup']), greenJob('all-green'),
  ]))
  assert.equal(legacy.state, 'open')
  await applyVerdict(github, context, verdict(106, 'green', [], [
    succeeded(ledger, []), greenJob('all-green'),
  ]))
  assert.equal(legacy.state, 'open')
  await applyVerdict(github, context, verdict(107, 'green', [], [ledger, 'all-green']))
  assert.equal(legacy.state, 'closed')

  // Exercise the workflow entry point, not just the issue reducer: a missing
  // red webhook is recovered from the Actions list and the cursor advances
  // only after the red and subsequent scoped green have been applied.
  const entryIssues = []
  const sourceRuns = [102, 101, 100].map(id => ({ id, run_attempt: 1, status: 'completed',
    head_branch: 'main', event: 'push', created_at: '2026-10-11T00:00:00Z' }))
  let entryContent = Buffer.from(JSON.stringify({ version: 1, workflow_id: 7, run_id: 100, attempt: 1 })).toString('base64')
  let entrySha = 'initial'
  const entryGithub = {
    paginate: async (_method, args) => entryIssues.filter(i => i.state === args.state),
    rest: {
      git: { getRef: async () => ({ data: { object: { sha: 'main-sha' } } }) },
      actions: { listWorkflowRuns: async () => ({ data: {
        total_count: sourceRuns.length, workflow_runs: sourceRuns,
      } }) },
      repos: {
        getContent: async () => ({ data: { type: 'file', sha: entrySha,
          encoding: 'base64', content: entryContent } }),
        createOrUpdateFileContents: async args => {
          assert.equal(args.sha, entrySha)
          entryContent = args.content
          entrySha = 'updated'
          return { data: { content: { sha: entrySha } } }
        },
      },
      issues: {
        listForRepo: async () => {},
        create: async args => {
          const issue = { number: entryIssues.length + 1, state: 'open', body: args.body, title: args.title }
          entryIssues.push(issue)
          return { data: issue }
        },
        update: async args => {
          const issue = entryIssues.find(i => i.number === args.issue_number)
          Object.assign(issue, args)
          return { data: issue }
        },
        createComment: async () => ({ data: {} }),
      },
    },
  }
  const entryContext = { ...context, payload: { workflow_run: {
    id: 102, run_attempt: 1, workflow_id: 7, head_branch: 'main', event: 'push',
  } } }
  const seen = []
  await runCatchup({ github: entryGithub, context: entryContext,
    core: { info: () => {}, warning: () => {} },
    classify: async (_repo, id, attempt) => {
      seen.push(`${id}/${attempt}`)
      return id === 101 ? verdict(101, 'red', [failed(leaf)]) : verdict(102, 'green', [], ['all-green'])
    },
  })
  assert.deepEqual(seen, ['101/1', '102/1'])
  assert.equal(entryIssues.length, 1)
  assert.equal(entryIssues[0].state, 'open')
  assert.equal(JSON.parse(Buffer.from(entryContent, 'base64')).run_id, 102)
  // The final new red event 103 has no webhook consumer. The scheduled
  // repository_dispatch backstop discovers it from the same cursor.
  sourceRuns.unshift({ id: 103, run_attempt: 1, status: 'completed',
    head_branch: 'main', event: 'push', created_at: '2026-10-11T01:00:00Z' })
  await runCatchup({ github: entryGithub,
    context: { ...context, eventName: 'repository_dispatch', payload: { action: 'main-red-reconcile',
      client_payload: { workflow_id: 7 } } },
    core: { info: () => {}, warning: () => {} },
    classify: async (_repo, id) => verdict(id, 'red', [failed('new-failure')]),
  })
  assert.equal(entryIssues[0].state, 'open')
  assert.match(entryIssues[0].body, /new-failure/)
  assert.equal(JSON.parse(Buffer.from(entryContent, 'base64')).run_id, 103)
  // Preserve manually closed unresolved evidence across a differently scoped red.
  issues.length = 0
  await applyVerdict(github, context, verdict(201, 'red', [failed('A')]))
  issues[0].state = 'closed'
  issues[0].closed_at = new Date().toISOString()
  await applyVerdict(github, context, verdict(202, 'red', [failed('B')]))
  await applyVerdict(github, context, verdict(203, 'green', [], [greenJob('B')]))
  assert.equal(issues[0].state, 'open')
  assert.match(issues[0].body, /- `A` \[failure\]/)
  await applyVerdict(github, context, verdict(204, 'green', [], [greenJob('A')]))
  assert.equal(issues[0].state, 'closed')
  assert.match(issues[0].body, /### Recovered failing jobs/)
  // Proven recovered failures must not return when a new failure reopens it.
  await applyVerdict(github, context, verdict(205, 'red', [failed('B')]))
  assert.doesNotMatch(issues[0].body, /- `A` \[failure\]/)
  await applyVerdict(github, context, verdict(206, 'green', [], [greenJob('B')]))
  assert.equal(issues[0].state, 'closed')

  // Global admission updates one PR per updater run. A validated history budget
  // alone is not proof of a branch update; retain each target until its own
  // exact server-side merge transition is observed.
  issues.length = 0
  const updater = (runId, status, failing = [], successful = []) => ({
    ...verdict(runId, status, failing, successful), workflow: 'Auto-update armed PRs',
  })
  const targetEvidence = async (_github, _repo, _verdict) => ({ targets: [
    { pr: 42, budget_validated: false, history_error: true, updated: false },
    { pr: 43, budget_validated: false, history_error: true, updated: false },
  ] })
  await applyVerdict(github, context, updater(700, 'red', [failed('updater')]), targetEvidence)
  assert.deepEqual(require('./main-red-watch-target.cjs').targetPrs(issues[0].body), [42, 43])
  await applyVerdict(github, context, updater(701, 'green'), async () => ({ targets: [
    { pr: 42, budget_validated: true, updated: true },
    { pr: 43, budget_validated: true, updated: false },
  ] }))
  assert.equal(issues[0].state, 'open')
  assert.deepEqual(require('./main-red-watch-target.cjs').targetPrs(issues[0].body), [43])
  assert.match(issues[0].body, /- `updater` \[failure\]/)
  await applyVerdict(github, context, updater(702, 'green', [], ['updater']), async () => ({ targets: [
    { pr: 43, budget_validated: true, updated: false },
  ] }))
  assert.equal(issues[0].state, 'open', 'budget validation without observed update must remain blocking')
  assert.deepEqual(require('./main-red-watch-target.cjs').targetPrs(issues[0].body), [43])
  await applyVerdict(github, context, updater(703, 'green', [], ['updater']), async () => ({ targets: [
    { pr: 43, budget_validated: true, updated: true },
  ] }))
  assert.equal(issues[0].state, 'closed')
  assert.match(issues[0].body, /### Recovered failing jobs/)

  // Different failed steps in the same job remain independent obligations.
  issues.length = 0
  await applyVerdict(github, context, verdict(301, 'red', [{ ...failed('A'), steps: ['X'] }]))
  await applyVerdict(github, context, verdict(302, 'red', [{ ...failed('A'), steps: ['Y'] }]))
  await applyVerdict(github, context, verdict(303, 'green', [], [succeeded('A', ['Y'])]))
  assert.equal(issues[0].state, 'open')
  assert.match(issues[0].body, /`X`/)
  await applyVerdict(github, context, verdict(304, 'green', [], [succeeded('A', ['X'])]))
  assert.equal(issues[0].state, 'closed')

  const staleAttempt = { total_count: 1, workflow_runs: [{ id: 401, run_attempt: 1, status: 'completed' }] }
  await assert.rejects(unprocessedAttempts({ run_id: 401, attempt: 0 }, async () => staleAttempt,
    { eventRunId: 401, eventAttempt: 2 }), LatestRunLagError)
  const freshAttempt = { total_count: 1, workflow_runs: [{ id: 401, run_attempt: 2, status: 'completed' }] }
  assert.deepEqual((await unprocessedAttempts({ run_id: 401, attempt: 0 }, async () => freshAttempt,
    { eventRunId: 401, eventAttempt: 2 })).attempts, [{ id: 401, attempt: 1 }, { id: 401, attempt: 2 }])
  console.log('main-red-watch durable checkpoint and replaced-event recovery: passed')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
