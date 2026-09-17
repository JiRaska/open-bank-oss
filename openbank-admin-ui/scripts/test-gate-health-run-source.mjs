// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import assert from 'node:assert/strict'
import test from 'node:test'
import { requireTrustedMainRuns, trustedRunQuery } from './gate-health-run-source.mjs'

const now = new Date('2026-09-17T14:00:00Z')
const run = (id, created_at) => ({
  id, created_at, status: 'completed', event: 'push', head_branch: 'main',
  head_sha: 'a'.repeat(40),
})

test('the actual API query selects a bounded main-push lane', () => {
  const query = trustedRunQuery(now, 20)
  const params = new URLSearchParams(query.split('?')[1])
  assert.equal(params.get('status'), 'completed')
  assert.equal(params.get('branch'), 'main')
  assert.equal(params.get('event'), 'push')
  assert.equal(params.get('created'), '>=2026-08-18T14:00:00Z')
  assert.equal(params.get('per_page'), '20')
  assert.throws(() => trustedRunQuery(now, 1000), /invalid CI run limit/)
})

test('main evidence is sorted by time rather than trusting API page order', () => {
  const older = run(1, '2026-09-17T12:00:00Z')
  const newer = run(2, '2026-09-17T13:00:00Z')
  assert.deepEqual(requireTrustedMainRuns({ workflow_runs: [older, newer] }, now), [newer, older])
})

test('a PR artifact cannot become the deployed gate-health snapshot', () => {
  const pr = { ...run(2, '2026-09-17T13:00:00Z'), event: 'pull_request', head_branch: 'codex/x' }
  assert.throws(() => requireTrustedMainRuns({ workflow_runs: [run(1, '2026-09-17T13:30:00Z'), pr] }, now), /untrusted/)
})

test('wrong-lane, malformed, empty and stale API pages fail closed', () => {
  const fresh = run(1, '2026-09-17T13:00:00Z')
  for (const bad of [
    { ...fresh, event: 'workflow_dispatch' },
    { ...fresh, head_branch: 'release' },
    { ...fresh, status: 'in_progress' },
    { ...fresh, created_at: 'nonsense' },
    { ...fresh, head_sha: 'not-a-sha' },
    run(1, '2026-08-17T12:00:00Z'),
    run(1, '2026-09-17T14:10:00Z'),
  ]) {
    assert.throws(() => requireTrustedMainRuns({ workflow_runs: [bad] }, now), /untrusted or stale/)
  }
  assert.throws(() => requireTrustedMainRuns({ workflow_runs: [] }, now), /no completed/)
  assert.throws(() => requireTrustedMainRuns({}, now), /no completed/)
  assert.throws(() => requireTrustedMainRuns({ workflow_runs: [run(1, '2026-09-15T13:00:00Z')] }, now), /too old/)
})
