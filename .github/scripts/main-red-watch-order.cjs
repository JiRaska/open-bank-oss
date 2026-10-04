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

module.exports = { eventIsSuperseded }
