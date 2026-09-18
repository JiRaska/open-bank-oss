// SPDX-License-Identifier: Apache-2.0

import { contextServiceUrl } from '@/lib/context/server'
import { parseOwnershipHistory, type OwnershipHistory } from '@/lib/context/kybOwnership'

/** Cap bytes before JSON parsing; a source Content-Length header is never trusted as the limit. */
export async function readBoundedOwnershipJson(response: Response, maxBytes: number): Promise<unknown> {
  const length = response.headers.get('content-length')
  if (length !== null && Number(length) > maxBytes) {
    void response.body?.cancel().catch(() => undefined)
    throw new Error('Ownership evidence response exceeds the byte limit')
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('Ownership evidence response has no body')
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > maxBytes) throw new Error('Ownership evidence response exceeds the byte limit')
      chunks.push(value)
    }
  } catch (error) {
    void reader.cancel().catch(() => undefined)
    throw error
  }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown
}

/** Revalidate the exact case assignment on every list and selected-detail request. */
export async function loadAuthorizedOwnershipHistory(caseId: string, token: string): Promise<{ status: number; history?: OwnershipHistory }> {
  const response = await fetch(contextServiceUrl(`/api/v1/context/kyb-cases/${caseId}/ownership-observations`), {
    cache: 'no-store', signal: AbortSignal.timeout(5_000),
    headers: {
      Accept: 'application/json', Authorization: `Bearer ${token}`,
      'X-Investigation-Case-Id': caseId, 'X-Investigation-Purpose': 'KYB_OWNERSHIP_REVIEW',
    },
  })
  if (!response.ok) return { status: [401, 403, 404, 429, 503].includes(response.status) ? response.status : 502 }
  const history = parseOwnershipHistory(await readBoundedOwnershipJson(response, 32 * 1024))
  if (history.root !== `kyb-case:${caseId}`) throw new Error('Scope mismatch')
  return { status: 200, history }
}
