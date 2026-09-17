// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

const LOOKBACK_MS = 30 * 24 * 60 * 60 * 1000
const MAX_NEWEST_AGE_MS = 48 * 60 * 60 * 1000
const FUTURE_SKEW_MS = 5 * 60 * 1000

export function trustedRunQuery(now, limit) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    throw new Error('invalid CI run limit')
  }
  const since = new Date(now.getTime() - LOOKBACK_MS).toISOString().replace(/\.\d{3}Z$/, 'Z')
  return `/actions/workflows/ci.yml/runs?${new URLSearchParams({
    status: 'completed', branch: 'main', event: 'push', created: `>=${since}`,
    per_page: String(limit),
  })}`
}

export function requireTrustedMainRuns(payload, now) {
  const runs = payload?.workflow_runs
  if (!Array.isArray(runs) || runs.length === 0) {
    throw new Error('no completed main-push CI runs in the bounded window')
  }
  const lower = now.getTime() - LOOKBACK_MS
  const upper = now.getTime() + FUTURE_SKEW_MS
  for (const run of runs) {
    const created = Date.parse(run?.created_at)
    if (run?.event !== 'push' || run?.head_branch !== 'main' || run?.status !== 'completed' ||
        !Number.isSafeInteger(run?.id) || !/^[a-f0-9]{40}$/.test(run?.head_sha ?? '') ||
        !Number.isFinite(created) || created < lower || created > upper) {
      throw new Error('CI runs API returned an untrusted or stale run')
    }
  }
  const ordered = [...runs].sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))
  if (Date.parse(ordered[0].created_at) < now.getTime() - MAX_NEWEST_AGE_MS) {
    throw new Error('newest completed main-push CI run is too old for current gate health')
  }
  return ordered
}
