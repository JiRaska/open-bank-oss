// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { unprocessedAttempts, remainingFailureLines, withFailureLines, mergeFailureLines, LatestRunLagError } =
  require('./main-red-watch-order.cjs')
const { ensureStateBranch, readCheckpoint, writeCheckpoint } = require('./main-red-watch-checkpoint.cjs')

const MAX_ATTEMPTS = 200
const BATCH_SIZE = 25
const REOPEN_WINDOW_DAYS = 14
class TransientClassifierError extends Error {}

async function waitForIndex(task, sleep = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  const delays = [1000, 2000, 4000, 8000, 16000, 30000]
  for (let attempt = 0; ; attempt++) {
    try {
      return await task()
    } catch (error) {
      if (!(error instanceof LatestRunLagError) || attempt === delays.length) throw error
      await sleep(delays[attempt])
    }
  }
}

async function classifyAttempt(repo, id, attempt) {
  const jsonPath = path.join(os.tmpdir(), `main-red-${process.pid}-${id}-${attempt}.json`)
  try {
    const proc = spawnSync('python3', [
      '.github/scripts/check-main-red-watch.py', '--inspect-run',
      '--repo', `${repo.owner}/${repo.repo}`, '--run-id', String(id),
      '--attempt', String(attempt), '--json', jsonPath,
    ], { encoding: 'utf8', timeout: 120000 })
    if (proc.status === 3) {
      throw new TransientClassifierError(`Actions API did not answer for main run ${id}/${attempt}`)
    }
    if (proc.error || ![0, 2].includes(proc.status)) {
      throw new Error(`Cannot classify main run ${id}/${attempt}: ${proc.error?.message || proc.stderr || proc.stdout}`)
    }
    const verdict = JSON.parse(fs.readFileSync(jsonPath, 'utf8'))
    if (verdict.run_id !== id || verdict.attempt !== attempt ||
        !['green', 'red', 'not-a-verdict'].includes(verdict.status)) {
      throw new Error(`Malformed verdict for main run ${id}/${attempt}`)
    }
    return verdict
  } finally {
    fs.rmSync(jsonPath, { force: true })
  }
}

function failureLines(verdict) {
  if (!Array.isArray(verdict.failing) || !verdict.failing.length) throw new Error('Red verdict has no failing jobs')
  return verdict.failing.map(f => {
    if (!f || typeof f.job !== 'string' || !f.job ||
        !['failure', 'timed_out'].includes(f.conclusion) || !Array.isArray(f.steps)) {
      throw new Error('Malformed failing job in red verdict')
    }
    const steps = f.steps.length ? f.steps.map(s => `\`${s}\``).join(', ') : '_no step-level failure reported_'
    return `- \`${f.job}\` [${f.conclusion}] — ${steps} ([log](${f.job_url}))`
  })
}

function redBody(verdict, marker, lines, context) {
  const first = verdict.failing[0]
  const gate = first.steps.length ? `${first.job} / ${first.steps[0]}` : first.job
  const body = [
    marker, '## `main` is red — nothing else would have told you', '',
    '| | |', '|---|---|',
    `| workflow | \`${verdict.workflow}\` |`,
    `| first failing gate | \`${gate}\` |`,
    `| head sha | \`${verdict.head_sha}\` |`,
    `| run | [${verdict.run_id} attempt ${verdict.attempt}](${verdict.html_url}) |`,
    '', '### Unresolved failing jobs', '', ...lines, '',
    'Each listed job needs a successful execution on main before this issue closes.',
    'Skipped and absent jobs do not prove recovery.', '',
    `_Automated: \`${context.workflow}\` run ${context.runId} (issue #4019)._`,
  ].join('\n')
  const title = `main is red: ${verdict.workflow} — ${gate} @ ${(verdict.head_sha || '').slice(0, 9)}`
  return { title, body }
}

async function applyVerdict(github, context, verdict) {
  if (verdict.status === 'not-a-verdict') return
  const repo = context.repo
  const slug = verdict.workflow.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const marker = `<!-- main-red:${slug} -->`
  const findByMarker = async state => {
    const issues = await github.paginate(github.rest.issues.listForRepo, {
      ...repo, state, labels: 'main-red', per_page: 100, sort: 'updated', direction: 'desc',
    })
    return issues.find(i => (i.body || '').includes(marker))
  }
  const open = await findByMarker('open')
  if (verdict.status === 'green') {
    if (!open) return
    const remaining = remainingFailureLines(open.body, verdict.successful_jobs)
    if (remaining === null) throw new Error('Cannot read open main-red failure inventory')
    if (remaining.length) {
      const prior = remainingFailureLines(open.body, [])
      if (remaining.length !== prior.length) {
        await github.rest.issues.update({ ...repo, issue_number: open.number,
          body: withFailureLines(open.body, remaining) })
      }
      return
    }
    await github.rest.issues.createComment({ ...repo, issue_number: open.number,
      body: `\`${verdict.workflow}\` recovered every recorded failing job on main through run ${verdict.run_id} attempt ${verdict.attempt}.` })
    await github.rest.issues.update({ ...repo, issue_number: open.number, state: 'closed' })
    return
  }
  const current = failureLines(verdict)
  if (open) {
    const outstanding = remainingFailureLines(open.body, verdict.successful_jobs)
    if (outstanding === null) throw new Error('Cannot read open main-red failure inventory')
    const merged = mergeFailureLines(outstanding, current)
    const { title, body } = redBody(verdict, marker, merged, context)
    await github.rest.issues.update({ ...repo, issue_number: open.number, title, body })
    return
  }
  const { title, body } = redBody(verdict, marker, current, context)
  const closed = await findByMarker('closed')
  const fresh = closed?.closed_at &&
    (Date.now() - Date.parse(closed.closed_at)) < REOPEN_WINDOW_DAYS * 864e5
  if (fresh) {
    await github.rest.issues.update({ ...repo, issue_number: closed.number, title, body, state: 'open' })
    return
  }
  await github.rest.issues.create({ ...repo, title, body, labels: ['main-red', 'fleet-health'] })
}

async function bootstrapCheckpoint(github, context, workflowId, event) {
  const eventCreated = Date.parse(event.created_at)
  if (!Number.isFinite(eventCreated)) throw new Error('Cannot establish first replay window')
  // The new watch cannot observe pre-deployment history indefinitely. Include
  // the preceding 48 hours to recover events replaced during the rollout. If
  // this bounded bootstrap is too large, fail rather than silently skip it.
  const since = new Date(eventCreated - 48 * 3600 * 1000).toISOString()
  const recent = []
  for (let page = 1; page <= 100; page++) {
    const response = await github.rest.actions.listWorkflowRuns({
      ...context.repo, workflow_id: workflowId, branch: 'main', event: 'push',
      created: `>=${since}`, per_page: 100, page,
    })
    const runs = response.data.workflow_runs
    if (!Array.isArray(runs) || runs.length > 100) throw new Error('Malformed bootstrap runs page')
    recent.push(...runs)
    if (runs.length < 100 || recent.length === response.data.total_count) break
    if (page === 100) throw new Error('Bootstrap exceeded 100 pages; refusing to skip source runs')
  }
  if (!recent.length || !recent.some(run => run.id === event.id)) {
    throw new LatestRunLagError('Delivered run missing from bootstrap inventory')
  }
  const oldest = recent.reduce((a, b) => a.id < b.id ? a : b)
  return { run_id: oldest.id, attempt: 0 }
}

async function runCatchup({ github, context, core, classify = classifyAttempt,
  bootstrap = bootstrapCheckpoint }) {
  let event = context.payload.workflow_run
  const isReconcile = !event && context.eventName === 'repository_dispatch'
  const workflowId = event?.workflow_id || (isReconcile && context.payload.client_payload?.workflow_id)
  if (isReconcile) {
    if (!Number.isSafeInteger(workflowId) || workflowId < 1) {
      throw new Error('Invalid reconciliation workflow ID')
    }
    const latest = await github.rest.actions.listWorkflowRuns({
      ...context.repo, workflow_id: workflowId, branch: 'main', event: 'push', per_page: 1,
    })
    event = latest.data.workflow_runs?.[0]
    if (!event && latest.data.total_count === 0) {
      core.info(`Watched workflow ${workflowId} has no main push run to reconcile`)
      return
    }
  }
  if (!Number.isSafeInteger(workflowId) || workflowId < 1 ||
      !Number.isSafeInteger(event?.id) || event.id < 1 ||
      event.head_branch !== 'main' || event.event !== 'push') {
    throw new Error('Invalid main push workflow_run identity')
  }
  await ensureStateBranch(github, context.repo)
  let { checkpoint, sha } = await readCheckpoint(github, context.repo, workflowId)
  if (!checkpoint) checkpoint = await waitForIndex(() => bootstrap(github, context, workflowId, event))
  let processed = 0
  while (processed < MAX_ATTEMPTS) {
    const plan = await waitForIndex(() => unprocessedAttempts(checkpoint, async page => {
      const response = await github.rest.actions.listWorkflowRuns({
        ...context.repo, workflow_id: workflowId, branch: 'main', event: 'push',
        per_page: 100, page,
      })
      return response.data
    }, { eventRunId: event.id, maxAttempts: Math.min(BATCH_SIZE, MAX_ATTEMPTS - processed) }))
    if (!plan.attempts.length) {
      if (plan.blockedBy) core.info(`Waiting for older run ${plan.blockedBy} before advancing checkpoint`)
      return
    }
    let lastProcessed = null
    for (const attempt of plan.attempts) {
      let verdict
      try {
        verdict = await classify(context.repo, attempt.id, attempt.attempt)
      } catch (error) {
        if (!(error instanceof TransientClassifierError)) throw error
        if (lastProcessed) {
          const next = { version: 1, workflow_id: workflowId,
            run_id: lastProcessed.id, attempt: lastProcessed.attempt }
          sha = await writeCheckpoint(github, context.repo, workflowId, next, sha)
          checkpoint = next
        }
        core.warning(`${error.message}; failing attempt remains unprocessed, retry on the next source event`)
        return
      }
      await applyVerdict(github, context, verdict)
      lastProcessed = attempt
      processed++
      core.info(`Processed ${verdict.workflow} ${attempt.id}/${attempt.attempt}: ${verdict.status}`)
    }
    const next = { version: 1, workflow_id: workflowId,
      run_id: lastProcessed.id, attempt: lastProcessed.attempt }
    sha = await writeCheckpoint(github, context.repo, workflowId, next, sha)
    checkpoint = next
    if (!plan.hasMore) return
  }
  throw new Error(`Bounded catch-up processed ${processed} attempts; checkpoint saved, retry to continue`)
}

module.exports = { runCatchup, applyVerdict, bootstrapCheckpoint, failureLines, waitForIndex }
