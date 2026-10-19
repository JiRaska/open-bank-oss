// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { parseBcpPolicy } from '@/lib/bcp/continuityModel'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const MAX_POLICY_BYTES = 64 * 1024
const POLICY_CACHE_TTL_MS = 60_000
type Policy = ReturnType<typeof parseBcpPolicy>
interface CachedPolicy {
  promise: Promise<Policy>
  expiresAtMs: number
}
let cachedPolicy: CachedPolicy | undefined

async function loadPolicy() {
  const configuredPath = process.env.OPENBANK_BCP_POLICY_FILE
  const candidates = configuredPath
    ? [configuredPath]
    : [path.resolve(process.cwd(), 'bcp-policy.md'), path.resolve(process.cwd(), '../docs/bcp/bcp-policy.md')]
  for (const filename of candidates) {
    try {
      const file = await fs.open(filename, 'r')
      try {
        const stat = await file.stat()
        if (stat.size > MAX_POLICY_BYTES) return null
        const markdown = await file.readFile('utf8')
        return parseBcpPolicy(markdown)
      } finally {
        await file.close()
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT' && filename !== candidates.at(-1)) continue
      return null
    }
  }
  return null
}

export async function GET() {
  const now = Date.now()
  if (!cachedPolicy || (cachedPolicy.expiresAtMs !== Number.POSITIVE_INFINITY && now >= cachedPolicy.expiresAtMs)) {
    const entry: CachedPolicy = {
      // Expiry starts after the read settles. Infinity keeps simultaneous requests
      // on this same promise even if an unusually slow read crosses the TTL.
      expiresAtMs: Number.POSITIVE_INFINITY,
      promise: Promise.resolve(null),
    }
    entry.promise = loadPolicy().catch(() => null).finally(() => {
      if (cachedPolicy === entry) entry.expiresAtMs = Date.now() + POLICY_CACHE_TTL_MS
    })
    cachedPolicy = entry
  }
  const policy = await cachedPolicy.promise
  return NextResponse.json(
    policy ? { available: true, policy } : { available: false, policy: null },
    { status: policy ? 200 : 503, headers: { 'Cache-Control': 'no-store, max-age=0' } },
  )
}
