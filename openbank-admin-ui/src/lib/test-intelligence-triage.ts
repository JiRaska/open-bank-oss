// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { TestCaseHistory } from '@/lib/types/test-intelligence'

export type TestTriageFilter = 'all' | 'failing' | 'flaky' | 'skipped' | 'unstable'

const STATE_PRIORITY: Record<TestCaseHistory['state'], number> = {
  failing: 0,
  flaky: 1,
  skipped: 2,
  stable: 3,
}

/**
 * Keep operator triage derived from immutable run observations.  The filter deliberately
 * cannot change a verdict, hide a money-path gate, or treat an empty result as healthy.
 */
export function filterTestCases(
  testCases: TestCaseHistory[],
  filter: TestTriageFilter,
  query: string,
): TestCaseHistory[] {
  const normalizedQuery = query.trim().toLocaleLowerCase()
  return testCases
    .filter(testCase => filter === 'all' || (filter === 'unstable'
      ? testCase.sameCommitTransitions > 0
      : testCase.state === filter))
    .filter(testCase => !normalizedQuery || [
      testCase.name,
      testCase.classname,
      testCase.component,
      testCase.kind,
      testCase.owner,
      testCase.fingerprint,
    ].some(value => value.toLocaleLowerCase().includes(normalizedQuery)))
    .sort((left, right) => {
      const priority = STATE_PRIORITY[left.state] - STATE_PRIORITY[right.state]
      if (priority !== 0) return priority
      if (right.sameCommitTransitions !== left.sameCommitTransitions) return right.sameCommitTransitions - left.sameCommitTransitions
      if (right.wastedDurationMs !== left.wastedDurationMs) return right.wastedDurationMs - left.wastedDurationMs
      return right.lastObservedAt.localeCompare(left.lastObservedAt)
    })
}
