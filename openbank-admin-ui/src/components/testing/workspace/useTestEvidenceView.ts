// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useState } from 'react'
import { decodeTestReadReply, parseTestReadRequest, testReadUrl, type TestReadError, type TestReadReply, type TestReadRequest } from '@/lib/test-intelligence-delivery'

type ReadState = { key: string; value: TestReadReply | null; error: TestReadError | null }

/** Abort superseded searches and bind every result to its URL and collection epoch. */
export function useTestEvidenceView(request: TestReadRequest | null, refreshKey = '') {
  const url = request ? testReadUrl(request) : null
  const [result, setResult] = useState<ReadState | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!url) return
    const controller = new AbortController()
    const requested = parseTestReadRequest(new URL(url, 'https://local.invalid').searchParams)
    const fail = (error: TestReadError) => {
      if (!controller.signal.aborted) setResult(current => ({ key: url, value: current?.key === url ? current.value : null, error }))
    }
    const timer = window.setTimeout(async () => {
      if (!requested) { fail('invalid_query'); return }
      try {
        const response = await fetch(url, { cache: 'no-cache', signal: controller.signal })
        const body: unknown = await response.json()
        if (!response.ok) {
          fail(response.status === 409 ? 'evidence_changed' : response.status === 404 ? 'test_not_found' : 'unavailable')
          return
        }
        const decoded = decodeTestReadReply(body, requested)
        if (!decoded.ok) { fail(decoded.error); return }
        if (!controller.signal.aborted) setResult({ key: url, value: decoded.value, error: null })
      } catch { fail('unavailable') }
    }, requested?.view === 'component' && requested.query ? 180 : 0)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [url, refreshKey, attempt])
  const current = result?.key === url ? result : null
  return { value: current?.value ?? null, error: current?.error ?? null, loading: url !== null && current === null, retry: () => setAttempt(value => value + 1) }
}
