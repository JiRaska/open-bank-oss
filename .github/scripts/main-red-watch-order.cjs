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
  eventIsSuperseded, failedJobsCovered, remainingFailureLines, withFailureLines,
  mergeFailureLines, unresolvedAfterLaterRuns, waitForCurrentVerdict, LatestRunLagError,
}
