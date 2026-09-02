// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// PID quick-create is a two-step write: create the party, then enrich that SAME party from BankID.
// Once create succeeds, retrying the whole flow cannot recover: pid-service has no Idempotency-Key
// support and rejects the already-bound bankIdSub with 409. Drive the page like an operator and pin
// the only safe browser transition: a failed sync retry skips create and targets the retained party.

import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PidPage from '@/app/pid/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { name: 'Operator', roles: ['ROLE_OPERATOR', 'ROLE_ADMIN'] } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

const PARTY_ID = '77777777-7777-4777-8777-777777777777'

const ORIGINAL_SYNC_PAYLOAD = {
  bankIdSub: 'bankid|recoverable-subject',
  givenName: 'Ada',
  familyName: 'Lovelace',
  birthdate: '1985-04-12',
  gender: 'FEMALE',
  birthplace: 'Praha',
  nationalities: ['CZ'],
  idDocuments: [{
    type: 'NATIONAL_ID',
    number: 'ID-123456',
    issuingCountry: 'CZ',
    issuedAt: '2024-01-02',
    expiresAt: '2034-01-02',
  }],
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>(settle => { resolve = settle })
  return { promise, resolve }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function fill(id: string, value: string) {
  const field = document.getElementById(id)
  expect(field, `missing #${id}`).toBeTruthy()
  fireEvent.change(field!, { target: { value } })
}

async function openAndFillQuickCreate() {
  fireEvent.click(await screen.findByRole('button', { name: 'Open PID quick create' }))
  fill('pid-given-name', 'Ada')
  fill('pid-family-name', 'Lovelace')
  fill('pid-birthdate', '1985-04-12')
  fill('pid-gender', 'FEMALE')
  fill('pid-birthplace', 'Praha')
  fill('pid-bankid-sub', 'bankid|recoverable-subject')
  fill('pid-document-number', 'ID-123456')
  fill('pid-document-issued-at', '2024-01-02')
  fill('pid-document-expires-at', '2034-01-02')
  const form = document.getElementById('pid-given-name')?.closest('form')
  expect(form).toBeTruthy()
  return form as HTMLFormElement
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('PID quick-create recovery', () => {
  it('retries only BankID sync against the already-created party', async () => {
    const requests: string[] = []
    let syncAttempts = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method !== 'POST') return json([])
      requests.push(url)
      if (url.endsWith('/api/v1/parties')) return json({ id: PARTY_ID }, 201)
      if (url.endsWith(`/api/v1/parties/${PARTY_ID}/sync/bankid`)) {
        syncAttempts += 1
        return syncAttempts === 1 ? json({ code: 'UPSTREAM_UNAVAILABLE' }, 503) : json({ id: PARTY_ID })
      }
      return json({ code: 'UNEXPECTED_REQUEST' }, 500)
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => {
      render(<LanguageProvider><PidPage /></LanguageProvider>)
    })
    const form = await openAndFillQuickCreate()

    await act(async () => { fireEvent.submit(form) })
    await waitFor(() => expect(screen.getByText(new RegExp(`Record created \\(ID: ${PARTY_ID}\\)`))).toBeInTheDocument())

    const retry = screen.getByRole('button', { name: 'Retry BankID sync' })
    expect(document.getElementById('pid-bankid-sub')).toBeDisabled()
    await act(async () => { fireEvent.click(retry) })

    await waitFor(() => expect(screen.getByText('Record created and synchronized successfully.')).toBeInTheDocument())
    expect(requests.filter(url => url.endsWith('/api/v1/parties'))).toHaveLength(1)
    expect(requests.filter(url => url.endsWith(`/api/v1/parties/${PARTY_ID}/sync/bankid`))).toHaveLength(2)
  })

  it('binds retries to the submitted identity while party creation is pending', async () => {
    const createResponse = deferred<Response>()
    const syncBodies: unknown[] = []
    let syncAttempts = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method !== 'POST') return json([])
      if (url.endsWith('/api/v1/parties')) return createResponse.promise
      if (url.endsWith(`/api/v1/parties/${PARTY_ID}/sync/bankid`)) {
        syncBodies.push(JSON.parse(String(init.body)))
        syncAttempts += 1
        return syncAttempts === 1 ? json({ code: 'UPSTREAM_UNAVAILABLE' }, 503) : json({ id: PARTY_ID })
      }
      return json({ code: 'UNEXPECTED_REQUEST' }, 500)
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => {
      render(<LanguageProvider><PidPage /></LanguageProvider>)
    })
    const form = await openAndFillQuickCreate()

    fireEvent.submit(form)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/v1\/parties$/),
      expect.objectContaining({ method: 'POST' }),
    ))

    const bankIdSub = document.getElementById('pid-bankid-sub') as HTMLInputElement
    const givenName = document.getElementById('pid-given-name') as HTMLInputElement
    expect(bankIdSub).toBeDisabled()
    expect(givenName).toBeDisabled()
    fireEvent.change(bankIdSub, { target: { value: 'bankid|mutated-subject' } })
    fireEvent.change(givenName, { target: { value: 'Mutated' } })

    await act(async () => { createResponse.resolve(json({ id: PARTY_ID }, 201)) })
    const retry = await screen.findByRole('button', { name: 'Retry BankID sync' })
    await act(async () => { fireEvent.click(retry) })

    await waitFor(() => expect(screen.getByText('Record created and synchronized successfully.')).toBeInTheDocument())
    expect(syncBodies).toEqual([ORIGINAL_SYNC_PAYLOAD, ORIGINAL_SYNC_PAYLOAD])
  })
})
