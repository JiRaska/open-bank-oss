// SPDX-License-Identifier: Apache-2.0
import 'server-only'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { testIntelligenceReader } from './testIntelligenceReader'
import { TEST_INTELLIGENCE_SNAPSHOT_MAX_BYTES, testIntelligenceSnapshotBaseUrl, validateTestIntelligenceSnapshot } from './testIntelligenceSnapshot'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

/** Same validated source contract as the report route; a configured outage never falls back silently. */
export async function readTestExplanationSource(): Promise<TestIntelligenceReport | null> {
  const baseUrl = testIntelligenceSnapshotBaseUrl()
  const reader = testIntelligenceReader(baseUrl)
  let published
  try { published = await reader.read() } catch { return null }
  if (published.status === 'unavailable') return null
  if (published.snapshot) return published.snapshot
  try {
    const file = process.env.OPENBANK_TEST_INTELLIGENCE ?? path.resolve(process.cwd(), 'test-intelligence.json')
    const handle = await fs.open(file, 'r')
    try {
      const maximum = TEST_INTELLIGENCE_SNAPSHOT_MAX_BYTES
      const buffer = Buffer.alloc(maximum + 1)
      let offset = 0
      while (offset < buffer.length) {
        const { bytesRead } = await handle.read(buffer, offset, buffer.length - offset, offset)
        if (!bytesRead) break
        offset += bytesRead
      }
      if (offset > maximum) return null
      const report: unknown = JSON.parse(buffer.subarray(0, offset).toString('utf8'))
      return validateTestIntelligenceSnapshot(report, Date.now()) ? report : null
    } finally { await handle.close() }
  } catch { return null }
}
