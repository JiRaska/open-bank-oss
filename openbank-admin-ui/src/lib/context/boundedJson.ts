// SPDX-License-Identifier: Apache-2.0

/** Cap bytes before JSON parsing; an upstream Content-Length header is not the limit. */
export async function readBoundedContextJson(response: Response, maxBytes: number): Promise<unknown> {
  const length = response.headers.get('content-length')
  if (length !== null && Number(length) > maxBytes) {
    void response.body?.cancel().catch(() => undefined)
    throw new Error('Context evidence response exceeds the byte limit')
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('Context evidence response has no body')
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > maxBytes) throw new Error('Context evidence response exceeds the byte limit')
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
