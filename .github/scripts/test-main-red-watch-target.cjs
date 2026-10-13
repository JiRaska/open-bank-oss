// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { applyVerdict } = require('./main-red-watch-catchup.cjs')
const { targetPrs, withTargets, validateEvidence, targetsCovered, loadTargetEvidence } =
  require('./main-red-watch-target.cjs')

const workflow = 'Auto-update armed PRs'
const job = 'update behind + green + armed PRs'
const step = 'Update behind + green + armed PRs'
const sha = 'e96cd0e2a9a5269b5b3904838a8cc608153bbf6a'
const context = { repo: { owner: 'o', repo: 'r' }, workflow: 'main red watch', runId: 38261087525 }
const verdict = (run_id, status) => ({
  run_id, attempt: 1, status, workflow, head_sha: sha,
  html_url: `https://example.invalid/actions/runs/${run_id}`,
  failing: status === 'red' ? [{ job, conclusion: 'failure', steps: [step],
    job_url: 'https://example.invalid/job' }] : [],
  successful_jobs: status === 'green' ? [{ job, steps: [step] }] : [],
})
const report = (run_id, target) => ({
  version: 1, run_id, attempt: 1, main_sha: sha, complete: true, successful: true,
  dry_run: false,
  targets: [target],
})
const target = (budget_validated, history_error = false, updated = budget_validated) => ({
  pr: 12435, head_sha: 'f7220d2ac0000000000000000000000000000000',
  budget_validated, update_count: budget_validated ? 3 : null, history_error, updated,
  updated_head_sha: updated ? 'b'.repeat(40) : null,
})

async function main() {
  assert.equal(targetPrs('legacy main-red issue'), null)
  assert.deepEqual(targetPrs(withTargets('issue body', [12435])), [12435])
  assert.deepEqual(targetPrs(withTargets(withTargets('issue body', [12435]), [12435])), [12435])
  assert.equal(targetPrs('<!-- main-red-target-pr:0 -->'), null)
  assert.equal(targetPrs('<!-- main-red-target-pr:12435 -->\n<!-- main-red-target-pr:unknown -->'), null)
  assert.throws(() => validateEvidence({ ...report(38260876231, target(true)), attempt: 2 },
    verdict(38260876231, 'green')), /mismatched/)
  assert.throws(() => validateEvidence({ ...report(38260876231, target(true)), main_sha: '0'.repeat(40) },
    verdict(38260876231, 'green')), /mismatched/)
  assert.throws(() => validateEvidence({ ...report(38260876231, target(true)),
    targets: [target(true), target(true)] }, verdict(38260876231, 'green')), /Malformed target entry/)
  assert.throws(() => validateEvidence(report(38260876231, target(false, false, true)),
    verdict(38260876231, 'green')), /Malformed target entry/)
  assert.throws(() => validateEvidence({ ...report(38260876231, target(true)), successful: false },
    verdict(38260876231, 'green')), /mismatched/)
  assert.equal(targetsCovered(withTargets('issue body', [12435]), report(38260876231, target(false))), false)
  assert.equal(targetsCovered(withTargets('issue body', [12435]), report(38260876231, target(true))), true)
  assert.equal(targetsCovered(withTargets('issue body', [12435]),
    report(38260876231, target(true, false, false))), false)
  assert.equal(targetsCovered('legacy issue', report(38260876231, target(true))), false)

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'main-red-target-test-'))
  try {
    const jsonPath = path.join(dir, 'auto-update-target-report.json')
    const zipPath = path.join(dir, 'evidence.zip')
    fs.writeFileSync(jsonPath, JSON.stringify(report(38260876231, target(false))))
    assert.equal(spawnSync('zip', ['-jq', zipPath, jsonPath]).status, 0)
    const artifacts = [{ id: 9, name: 'auto-update-target-evidence-1', expired: false }]
    const gh = { rest: { actions: {
      listWorkflowRunArtifacts: async () => ({ data: { total_count: artifacts.length, artifacts } }),
      downloadArtifact: async () => ({ data: fs.readFileSync(zipPath) }),
    } } }
    assert.equal((await loadTargetEvidence(gh, context.repo, verdict(38260876231, 'green')))
      .targets[0].budget_validated, false)
    artifacts[0].name = 'auto-update-target-evidence-2'
    assert.equal(await loadTargetEvidence(gh, context.repo, verdict(38260876231, 'green')), null)
    artifacts[0].name = 'auto-update-target-evidence-1'
    await assert.rejects(loadTargetEvidence(gh, context.repo, verdict(38260876232, 'green')),
      /mismatched/)
    artifacts.push({ ...artifacts[0], id: 10 })
    await assert.rejects(loadTargetEvidence(gh, context.repo, verdict(38260876231, 'green')),
      /Ambiguous/)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }

  const issues = []
  const github = {
    paginate: async (_method, args) => issues.filter(issue => issue.state === args.state),
    rest: { issues: {
      listForRepo: () => {},
      create: async args => {
        const issue = { number: 12515, state: 'open', body: args.body, title: args.title }
        issues.push(issue)
        return { data: issue }
      },
      update: async args => {
        const issue = issues.find(item => item.number === args.issue_number)
        Object.assign(issue, args)
        return { data: issue }
      },
      createComment: async () => ({ data: {} }),
    } },
  }
  // The failing run binds the history defect to PR #12435, not just the generic job.
  await applyVerdict(github, context, verdict(38256260163, 'red'),
    async () => report(38256260163, target(false, true)))
  assert.deepEqual(targetPrs(issues[0].body), [12435])
  // This models the actual later green run: the same job and step succeeded,
  // but #12435 was skipped for missing contexts before history was read.
  await applyVerdict(github, context, verdict(38260876231, 'green'),
    async () => report(38260876231, target(false)))
  assert.equal(issues[0].state, 'open')
  assert.deepEqual(targetPrs(issues[0].body), [12435])
  // The target history was read, but another PR consumed the update cap.
  await applyVerdict(github, context, verdict(38260876232, 'green'),
    async () => report(38260876232, target(true, false, false)))
  assert.equal(issues[0].state, 'open')
  // A report from a later attempt that did validate the target's history may close it.
  await applyVerdict(github, context, verdict(38270000000, 'green'),
    async () => report(38270000000, target(true)))
  assert.equal(issues[0].state, 'closed')

  // Legacy #12515 has no machine-readable target: even an all-green generic job
  // and a report for #12435 cannot establish which PR caused that older failure.
  issues[0].state = 'open'
  issues[0].body = issues[0].body.replace(/<!-- main-red-target-pr:12435 -->/, '')
  await applyVerdict(github, context, verdict(38270000001, 'green'),
    async () => report(38270000001, target(true)))
  assert.equal(issues[0].state, 'open')
  issues[0].body = withTargets(issues[0].body, [12435])
  await applyVerdict(github, context, verdict(38270000002, 'red'),
    async () => report(38270000002, target(true)))
  assert.equal(targetPrs(issues[0].body), null) // target validated, unrelated red remains
  console.log('main-red target-bound recovery: passed')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
