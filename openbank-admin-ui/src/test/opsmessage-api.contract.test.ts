// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { opsMessageApi, OPERATOR_MESSAGE_TEMPLATE_VARS, type ComposeMessageRequest } from '@/lib/api'

// Pins the admin-ui client to the SHIPPED notification-service contract (ADR-0176):
// OperatorMessageResource (POST /api/v1/notifications/messages) + ApprovalResource
// (PATCH /api/v1/notifications/approvals/{id}) + openapi.yaml. Regression guard for the gap where
// the client called an invented /opsmessages draft/submit/approve surface that 404s through the BFF.

function jsonRes(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function lastCall() {
  const mock = fetch as unknown as ReturnType<typeof vi.fn>
  const [url, init] = mock.mock.calls[mock.mock.calls.length - 1] as [string, RequestInit]
  const headers = (init.headers ?? {}) as Record<string, string>
  const body = init.body ? JSON.parse(init.body as string) : undefined
  return { url, method: init.method, headers, body }
}

const REQ: ComposeMessageRequest = {
  partyId: '11111111-1111-1111-1111-111111111111',
  template: 'GENERIC_NOTICE',
  recipient: 'customer@example.com',
  variables: { subject: 'Hello', note: 'A note' },
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('opsMessageApi — shipped notification-service contract', () => {
  it('compose POSTs to /api/v1/notifications/messages and maps 201 -> SENT', async () => {
    const notificationId = '22222222-2222-2222-2222-222222222222'
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(201, { id: notificationId })))

    const result = await opsMessageApi.compose(REQ)

    const call = lastCall()
    expect(call.url).toBe('/api/svc/notification-service/api/v1/notifications/messages')
    expect(call.method).toBe('POST')
    expect(call.headers['Content-Type']).toBe('application/json')
    // First (un-approved) call carries no approval header.
    expect(call.headers['X-Approval-Id']).toBeUndefined()
    expect(call.body).toEqual(REQ)
    expect(result).toEqual({ status: 'SENT', id: notificationId })
  })

  it('compose maps the AuthorizeInterceptor 202 body -> PENDING_APPROVAL', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(202, { status: 'PENDING_APPROVAL', approvalId: 'appr-1' })))

    const result = await opsMessageApi.compose(REQ)

    expect(result).toEqual({ status: 'PENDING_APPROVAL', approvalId: 'appr-1' })
  })

  it('compose retry sends the byte-identical body plus the X-Approval-Id header', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(201, { id: 'sent-after-approval' })))

    const result = await opsMessageApi.compose(REQ, 'appr-1')

    const call = lastCall()
    expect(call.headers['X-Approval-Id']).toBe('appr-1')
    expect(call.body).toEqual(REQ) // resource binding is content-derived — must not mutate on retry
    expect(result).toEqual({ status: 'SENT', id: 'sent-after-approval' })
  })

  it('compose surfaces a 400 (bad template variables / recipient) as a thrown error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(400, { message: 'recipient is not a well-formed email address' })))

    await expect(opsMessageApi.compose(REQ)).rejects.toThrow(/well-formed email/)
  })

  it('decide PATCHes /api/v1/notifications/approvals/{id} with an approve boolean', async () => {
    const approvalResponse = {
      id: 'appr-1', action: 'opsmessage.compose', resourceId: 'r', status: 'APPROVED', decidedBy: 'op-2',
    }
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(200, approvalResponse)))

    const decision = await opsMessageApi.decide('appr-1', true)

    const call = lastCall()
    expect(call.url).toBe('/api/svc/notification-service/api/v1/notifications/approvals/appr-1')
    expect(call.method).toBe('PATCH')
    expect(call.body).toEqual({ approve: true })
    expect(decision).toEqual(approvalResponse)
  })

  it('encodes an approval id before placing it in the decision path', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(200, {
      id: 'approval/with separator', action: 'opsmessage.compose', resourceId: null, status: 'REJECTED', decidedBy: 'op-2',
    })))

    await opsMessageApi.decide('approval/with separator', false)

    expect(lastCall().url).toBe('/api/svc/notification-service/api/v1/notifications/approvals/approval%2Fwith%20separator')
  })

  it('decide sends approve:false on a reject', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(200, {
      id: 'appr-1', action: 'opsmessage.compose', resourceId: null, status: 'REJECTED', decidedBy: 'op-2',
    })))

    await opsMessageApi.decide('appr-1', false)

    expect(lastCall().body).toEqual({ approve: false })
  })

  it('exposes exactly the shipped OperatorMessageTemplate variable sets — no OPERATOR_ACCOUNT_NOTICE', () => {
    expect(OPERATOR_MESSAGE_TEMPLATE_VARS).toEqual({
      GENERIC_NOTICE: ['subject', 'note'],
      SUPPORT_FOLLOWUP: ['ticketReference'],
    })
    expect(Object.keys(OPERATOR_MESSAGE_TEMPLATE_VARS)).not.toContain('OPERATOR_ACCOUNT_NOTICE')
  })
})
