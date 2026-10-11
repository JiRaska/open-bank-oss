// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** A late completion or retry must not overwrite a newer main workflow verdict. */
class LatestRunLagError extends Error {}

/**
 * The checkpoint is written only after issue mutation has completed. GitHub may
 * replace a pending workflow_run event, so the next event must replay every
 * completed attempt since this cursor, including attempts from other run IDs.
 * An in-progress older run blocks the cursor: completion order is not run order.
 */
async function unprocessedAttempts(checkpoint, fetchPage, options = {}) {
  const maxPages = options.maxPages ?? 100
  const maxAttempts = options.maxAttempts ?? 25
  const eventRunId = options.eventRunId
  if (!Number.isSafeInteger(checkpoint?.run_id) || checkpoint.run_id < 1 ||
      !Number.isSafeInteger(checkpoint.attempt) || checkpoint.attempt < 0 ||
      !Number.isSafeInteger(eventRunId) || eventRunId < 1 ||
      !Number.isSafeInteger(maxPages) || maxPages < 1 ||
      !Number.isSafeInteger(maxAttempts) || maxAttempts < 1) {
    throw new Error('Invalid main-red replay checkpoint or bound')
  }
  const newer = []
  const seen = new Set()
  let found = false
  let expectedTotal
  for (let page = 1; page <= maxPages; page++) {
    const data = await fetchPage(page)
    if (!data || !Number.isSafeInteger(data.total_count) || data.total_count < 0 ||
        !Array.isArray(data.workflow_runs) || data.workflow_runs.length > 100 ||
        (expectedTotal !== undefined && expectedTotal !== data.total_count)) {
      throw new Error('Incomplete or malformed completed-runs page')
    }
    expectedTotal = data.total_count
    for (const run of data.workflow_runs) {
      if (!Number.isSafeInteger(run?.id) || run.id < 1 || seen.has(run.id) ||
          !Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1 ||
          !['completed', 'queued', 'in_progress', 'requested', 'waiting', 'pending'].includes(run.status)) {
        throw new Error('Incomplete or malformed completed-runs entry')
      }
      seen.add(run.id)
      if (run.id === checkpoint.run_id) {
        if (run.run_attempt < checkpoint.attempt) throw new LatestRunLagError('Checkpoint attempt is not indexed')
        newer.push(run)
        found = true
        break
      }
      if (run.id > checkpoint.run_id) newer.push(run)
    }
    if (found) break
    if (data.workflow_runs.length < 100 || seen.size >= data.total_count) break
  }
  if (!found) {
    throw new Error('Replay checkpoint not found inside bounded Actions history; refusing to skip runs')
  }
  if (!seen.has(eventRunId) && eventRunId > checkpoint.run_id) {
    throw new LatestRunLagError('Delivered workflow run is not indexed in bounded Actions history')
  }
  const attempts = []
  for (const run of newer.sort((a, b) => a.id - b.id)) {
    const start = run.id === checkpoint.run_id ? checkpoint.attempt + 1 : 1
    for (let attempt = start; attempt <= run.run_attempt; attempt++) {
      if (run.status !== 'completed') {
        return { attempts, blockedBy: run.id, hasMore: true }
      }
      if (attempts.length === maxAttempts) {
        return { attempts, blockedBy: null, hasMore: true }
      }
      attempts.push({ id: run.id, attempt })
    }
  }
  return { attempts, blockedBy: null, hasMore: false }
}

function eventIsSuperseded(verdict, latest) {
  const runId = verdict?.run_id
  const attempt = verdict?.attempt
  for (const [name, value] of Object.entries({ runId, attempt })) {
    if (!Number.isSafeInteger(value) || value < 1) {
      throw new Error(`Cannot order main workflow runs: invalid ${name}`)
    }
  }
  if (latest == null) {
    throw new LatestRunLagError('Latest-completed workflow lookup has not indexed the event yet')
  }
  const latestId = latest.id
  const latestAttempt = latest.run_attempt
  for (const [name, value] of Object.entries({ latestId, latestAttempt })) {
    if (!Number.isSafeInteger(value) || value < 1) {
      throw new Error(`Cannot order main workflow runs: invalid ${name}`)
    }
  }
  if (latestId < runId || (latestId === runId && latestAttempt < attempt)) {
    throw new LatestRunLagError('Latest-completed workflow lookup is behind the event; refusing issue mutation')
  }
  return latestId > runId || latestAttempt > attempt
}

/** A newer indexed run does not prove the event's own run has been indexed. */
function latestIndexedRun(verdict, runs) {
  if (!Array.isArray(runs)) throw new Error('Malformed completed-runs inventory')
  if (!Number.isSafeInteger(verdict?.run_id) || verdict.run_id < 1 ||
      !Number.isSafeInteger(verdict?.attempt) || verdict.attempt < 1) {
    throw new Error('Malformed event identity in completed-runs inventory')
  }
  if (runs.some(run => !Number.isSafeInteger(run?.id) || run.id < 1 ||
      !Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1)) {
    throw new Error('Malformed completed-runs inventory entry')
  }
  const eventRun = runs.find(run => run?.id === verdict?.run_id)
  if (!eventRun) return null
  if (eventRun.run_attempt < verdict.attempt) return null
  return runs.reduce((newest, run) => !newest || run.id > newest.id ? run : newest, null)
}

/** GitHub lists one row per run ID, so expand every intervening rerun attempt. */
function laterRunAttempts(verdict, runs) {
  if (!Array.isArray(runs) || !Number.isSafeInteger(verdict?.run_id) || verdict.run_id < 1 ||
      !Number.isSafeInteger(verdict?.attempt) || verdict.attempt < 1) {
    throw new Error('Malformed completed-runs inventory or event identity')
  }
  const later = []
  for (const run of runs) {
    if (!Number.isSafeInteger(run?.id) || run.id < 1 ||
        !Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1) {
      throw new Error('Malformed completed-runs inventory entry')
    }
    if (run.id < verdict.run_id) continue
    for (let attempt = run.id === verdict.run_id ? verdict.attempt + 1 : 1;
      attempt <= run.run_attempt; attempt++) {
      later.push({ id: run.id, attempt })
    }
  }
  return later.sort((a, b) => a.id - b.id || a.attempt - b.attempt)
}

const FAILURE_HEADING = '### Unresolved failing jobs'
const FAILURE_SECTION = /(### (?:Unresolved failing jobs|Every failing job in this attempt)\r?\n\r?\n)([^]*?)(?=\r?\n\r?\n|$)/

function failureName(line) {
  return line.match(/^- `([^`]+)` \[(?:failure|timed_out)\](?: |$)/)?.[1]
}

function failureSteps(line) {
  const detail = line.split(' — ')[1]
  if (!detail || detail.startsWith('_no step-level failure reported_')) return null
  const beforeLog = detail.split(' ([log](')[0]
  const names = [...beforeLog.matchAll(/`([^`]+)`/g)].map(match => match[1])
  return names.length && names.every(name => name.trim()) ? names : null
}

/** Keep failures until the same named job has actually succeeded in a later attempt. */
function remainingFailureLines(issueBody, successfulJobs) {
  if (typeof issueBody !== 'string' || !Array.isArray(successfulJobs)) return null
  const section = issueBody.match(FAILURE_SECTION)
  if (!section) return null
  const lines = section[2].split(/\r?\n/).filter(Boolean)
  if (!lines.length || lines.some(line => !failureName(line))) return null
  if (successfulJobs.some(job => !job || typeof job.job !== 'string' ||
      !Array.isArray(job.steps) || job.steps.some(step => typeof step !== 'string'))) return null
  return lines.filter(line => {
    const failedSteps = failureSteps(line)
    if (!failedSteps) return true // No step identity cannot prove recovery.
    return !successfulJobs.some(job => job.job === failureName(line) &&
      failedSteps.every(step => job.steps.includes(step)))
  })
}

function failedJobsCovered(issueBody, successfulJobs) {
  const remaining = remainingFailureLines(issueBody, successfulJobs)
  return remaining !== null && remaining.length === 0
}

/** Replace only the job ledger; retain the issue's other diagnostic context. */
function withFailureLines(issueBody, lines) {
  if (remainingFailureLines(issueBody, []) === null || !lines.length || lines.some(line => !failureName(line))) {
    throw new Error('Cannot update malformed main-red failure inventory')
  }
  return issueBody.replace(FAILURE_SECTION, () => `${FAILURE_HEADING}\n\n${lines.join('\n')}`)
}

function mergeFailureLines(priorLines, currentLines) {
  const byName = new Map()
  for (const line of [...priorLines, ...currentLines]) {
    const name = failureName(line)
    if (!name) throw new Error('Cannot merge malformed main-red failure inventory')
    byName.set(name, line)
  }
  return [...byName.values()]
}

/** Replay later executions per job, so a partial green cannot erase an older failure. */
function unresolvedAfterLaterRuns(failingLines, laterRuns) {
  const original = new Map(failingLines.map(line => [failureName(line), line]))
  if (original.has(undefined)) throw new Error('Cannot replay malformed main-red failure inventory')
  const unresolved = new Map(original)
  for (const jobs of laterRuns) {
    for (const job of jobs) {
      if (!original.has(job.name)) continue
      if (job.conclusion === 'success') unresolved.delete(job.name)
      if (job.conclusion === 'failure' || job.conclusion === 'timed_out') {
        unresolved.set(job.name, original.get(job.name))
      }
    }
  }
  return [...unresolved.values()]
}

/** A delayed green proves only jobs whose latest subsequent execution still succeeded. */
function successfulAfterLaterRuns(successfulJobs, laterRuns) {
  const original = new Set(successfulJobs)
  const successful = new Set(original)
  for (const jobs of laterRuns) {
    for (const job of jobs) {
      if (!original.has(job.name)) continue
      if (job.conclusion === 'success') successful.add(job.name)
      if (job.conclusion === 'failure' || job.conclusion === 'timed_out') successful.delete(job.name)
    }
  }
  return [...successful]
}

const TERMINAL_JOB_CONCLUSIONS = new Set([
  'success', 'failure', 'timed_out', 'cancelled', 'skipped', 'neutral', 'action_required',
])

/** Never replay a later run from a truncated or still-indexing Jobs API inventory. */
async function fetchCompleteAttemptJobs(fetchPage) {
  const jobs = []
  const ids = new Set()
  let expected
  for (let page = 1; ; page++) {
    const data = await fetchPage(page)
    if (!data || !Number.isSafeInteger(data.total_count) || data.total_count < 1 ||
        (expected !== undefined && data.total_count !== expected) ||
        !Array.isArray(data.jobs) || !data.jobs.length || data.jobs.length > 100) {
      throw new Error('Incomplete or malformed attempt-scoped Jobs API page')
    }
    expected = data.total_count
    for (const job of data.jobs) {
      if (!job || !Number.isSafeInteger(job.id) || job.id < 1 || ids.has(job.id) ||
          typeof job.name !== 'string' || !job.name || job.status !== 'completed' ||
          !TERMINAL_JOB_CONCLUSIONS.has(job.conclusion)) {
        throw new Error('Incomplete or malformed attempt-scoped Jobs API job')
      }
      ids.add(job.id)
      jobs.push(job)
    }
    if (jobs.length > expected) throw new Error('Attempt-scoped Jobs API exceeded total_count')
    if (jobs.length === expected) return jobs
    if (data.jobs.length < 100) throw new Error('Incomplete attempt-scoped Jobs API page')
  }
}

/** Retry only GitHub's lagging completed-runs index; malformed data and API errors fail closed. */
async function waitForCurrentVerdict(verdict, fetchLatest, sleep,
  delays = [1000, 2000, 4000, 8000, 16000, 30000]) {
  for (let index = 0; ; index++) {
    const latest = await fetchLatest()
    try {
      return { latest, superseded: eventIsSuperseded(verdict, latest) }
    } catch (error) {
      if (!(error instanceof LatestRunLagError) || index === delays.length) throw error
      await sleep(delays[index])
    }
  }
}

module.exports = {
  unprocessedAttempts,
  eventIsSuperseded, latestIndexedRun, laterRunAttempts, failedJobsCovered, remainingFailureLines, withFailureLines,
  mergeFailureLines, unresolvedAfterLaterRuns, successfulAfterLaterRuns,
  fetchCompleteAttemptJobs, waitForCurrentVerdict, LatestRunLagError,
}
