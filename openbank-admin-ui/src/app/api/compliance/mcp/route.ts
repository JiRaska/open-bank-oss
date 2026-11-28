// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Read-only MCP server over the compliance trace (ADR-0379 phase 3): JSON-RPC 2.0 with
// `initialize`, `tools/list` and `tools/call`. It lives in the BFF because the trace is joined
// here (ADR-0056). The caller is the signed-in member of staff, under their own roles: the route
// guard admits `/api/compliance` only with `compliance:view`, and the handler re-checks both the
// session and that permission. Every tool is a read. There is no write method to authorize.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { loadComplianceTrace } from '@/lib/compliance/trace-server'
import { COMPLIANCE_TOOLS, ToolInputError, callComplianceTool } from '@/lib/compliance/tools'
import { hasPermission } from '@/lib/auth/roles'

export const dynamic = 'force-dynamic'

const PROTOCOL_VERSION = '2025-06-18'
type Id = string | number | null

const rpcError = (id: Id, code: number, message: string) => NextResponse.json({ jsonrpc: '2.0', id, error: { code, message } })
const rpcResult = (id: Id, result: unknown) => NextResponse.json({ jsonrpc: '2.0', id, result })

export async function POST(request: Request) {
  const session = await auth()
  if (!session?.user) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  // The route guard already requires this; checked again so a guard regression cannot open it.
  if (!hasPermission(session.user.roles ?? [], 'compliance:view')) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  let msg: { jsonrpc?: unknown; id?: unknown; method?: unknown; params?: unknown }
  try {
    msg = await request.json()
  } catch {
    return rpcError(null, -32700, 'parse error')
  }
  if (!msg || typeof msg !== 'object' || Array.isArray(msg)) return rpcError(null, -32600, 'batch requests are not supported')
  const id: Id = typeof msg.id === 'string' || typeof msg.id === 'number' ? msg.id : null
  if (msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return rpcError(id, -32600, 'invalid request')

  // A notification carries no id and gets no response body.
  if (msg.id === undefined) return new NextResponse(null, { status: 202 })

  switch (msg.method) {
    case 'initialize':
      return rpcResult(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'openbank-compliance-trace', version: '1' },
        instructions: 'Read-only. Verdicts are computed by code (ADR-0379); cite trace refs such as control:<id> or obligation:<id>.',
      })
    case 'ping':
      return rpcResult(id, {})
    case 'tools/list':
      return rpcResult(id, { tools: COMPLIANCE_TOOLS })
    case 'tools/call': {
      const params = (msg.params ?? {}) as { name?: unknown; arguments?: unknown }
      if (typeof params.name !== 'string') return rpcError(id, -32602, 'params.name is required')
      if (!COMPLIANCE_TOOLS.some(t => t.name === params.name)) return rpcError(id, -32602, `unknown tool: ${params.name}`)
      const trace = await loadComplianceTrace()
      if (trace.status !== 'available') {
        return rpcResult(id, { isError: true, content: [{ type: 'text', text: `compliance trace unavailable: ${trace.missing.join(', ')}` }] })
      }
      try {
        const result = callComplianceTool(trace, params.name, params.arguments)
        return rpcResult(id, { content: [{ type: 'text', text: JSON.stringify(result) }], structuredContent: result })
      } catch (error) {
        if (error instanceof ToolInputError) return rpcResult(id, { isError: true, content: [{ type: 'text', text: error.message }] })
        return rpcError(id, -32603, 'internal error')
      }
    }
    default:
      return rpcError(id, -32601, `method not found: ${msg.method}`)
  }
}
