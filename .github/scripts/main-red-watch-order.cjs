// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** A late completion or retry must not overwrite a newer main workflow verdict. */
class LatestRunLagError extends Error {}

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

const FAILURE_HEADING = '### Unresolved failing jobs'
const FAILURE_SECTION = /(### (?:Unresolved failing jobs|Every failing job in this attempt)\r?\n\r?\n)([^]*?)(?=\r?\n\r?\n|$)/

function failureName(line) {
  return line.match(/^- `([^`]+)` \[(?:failure|timed_out)\](?: |$)/)?.[1]
}

/** Keep failures until the same named job has actually succeeded in a later attempt. */
function remainingFailureLines(issueBody, successfulJobs) {
  if (typeof issueBody !== 'string' || !Array.isArray(successfulJobs)) return null
  const section = issueBody.match(FAILURE_SECTION)
  if (!section) return null
  const lines = section[2].split(/\r?\n/).filter(Boolean)
  if (!lines.length || lines.some(line => !failureName(line))) return null
  const passed = new Set(successfulJobs)
  return lines.filter(line => !passed.has(failureName(line)))
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
  eventIsSuperseded, latestIndexedRun, failedJobsCovered, remainingFailureLines, withFailureLines,
  mergeFailureLines, unresolvedAfterLaterRuns, successfulAfterLaterRuns,
  fetchCompleteAttemptJobs, waitForCurrentVerdict, LatestRunLagError,
}
