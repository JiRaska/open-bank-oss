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

module.exports = { eventIsSuperseded, waitForCurrentVerdict, LatestRunLagError }
