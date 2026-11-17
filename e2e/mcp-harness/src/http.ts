// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Raw HTTP for the attack suite. The SDK client is the right tool for the journey, but an attack
// must see the status code and headers the SDK deliberately hides (it turns 401 into an exception).

export interface RawResponse {
  status: number
  headers: Headers
  body: string
  json?: any
}

export async function send(url: string, init: RequestInit & { form?: Record<string, string | undefined> }): Promise<RawResponse> {
  const headers = new Headers(init.headers)
  let body = init.body
  if (init.form) {
    const p = new URLSearchParams()
    for (const [k, v] of Object.entries(init.form)) if (v !== undefined) p.set(k, v)
    body = p.toString()
    headers.set('content-type', 'application/x-www-form-urlencoded')
  }
  const res = await fetch(url, { ...init, headers, body, redirect: 'manual' })
  const text = await res.text()
  let parsed: unknown
  try { parsed = text ? JSON.parse(text) : undefined } catch { parsed = undefined }
  return { status: res.status, headers: res.headers, body: text, json: parsed }
}

let rpcId = 0

/** One JSON-RPC call over plain POST, the shape openbank-mcp-service serves at `/mcp`. */
export function rpc(url: string, method: string, params: unknown, headers: Record<string, string> = {}): Promise<RawResponse> {
  return send(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...headers },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }),
  })
}
