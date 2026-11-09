// SPDX-License-Identifier: Apache-2.0
import 'server-only'
import { createEvidenceSnapshotReader } from './publishedSnapshot'
import { TEST_INTELLIGENCE_SNAPSHOT_MAX_BYTES, testIntelligenceCollectedAt, validateTestIntelligenceSnapshot } from './testIntelligenceSnapshot'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

const readers = new Map<string, ReturnType<typeof createEvidenceSnapshotReader<TestIntelligenceReport>>>()

/** One validated immutable source shared by the report and its contextual explanation. */
export function testIntelligenceReader(baseUrl: string | undefined) {
  const key = baseUrl ?? '<disabled>'
  let reader = readers.get(key)
  if (!reader) {
    reader = createEvidenceSnapshotReader({ baseUrl, maxBytes: TEST_INTELLIGENCE_SNAPSHOT_MAX_BYTES, validateSnapshot: validateTestIntelligenceSnapshot, collectedAt: testIntelligenceCollectedAt })
    if (readers.size >= 8) readers.delete(readers.keys().next().value!)
    readers.set(key, reader)
  }
  return reader
}
