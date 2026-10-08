// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** A late completion or retry must not overwrite a newer main workflow verdict. */
function eventIsSuperseded(verdict, latest) {
  const runId = verdict?.run_id
  const attempt = verdict?.attempt
  const latestId = latest?.id
  const latestAttempt = latest?.run_attempt
  for (const [name, value] of Object.entries({ runId, attempt, latestId, latestAttempt })) {
    if (!Number.isSafeInteger(value) || value < 1) {
      throw new Error(`Cannot order main workflow runs: invalid ${name}`)
    }
  }
  if (latestId < runId || (latestId === runId && latestAttempt < attempt)) {
    throw new Error('Latest-completed workflow lookup is behind the event; refusing issue mutation')
  }
  return latestId > runId || latestAttempt > attempt
}

/** A scoped green run heals an incident only if it reran every formerly failing job. */
function failedJobsCovered(issueBody, successfulJobs) {
  if (typeof issueBody !== 'string' || !Array.isArray(successfulJobs)) return false
  const section = issueBody.match(/### Every failing job in this attempt\r?\n\r?\n([^]*?)(?:\r?\n\r?\n|$)/)
  if (!section) return false
  const lines = section[1].split(/\r?\n/).filter(Boolean)
  if (!lines.length || lines.some(line => !line.startsWith('- '))) return false
  const names = lines.map(line => line.match(/^- `([^`]+)` \[(?:failure|timed_out)\](?: |$)/)?.[1])
  if (names.some(name => !name)) return false
  const passed = new Set(successfulJobs)
  return names.every(name => passed.has(name))
}

module.exports = { eventIsSuperseded, failedJobsCovered }
