// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ComplaintContextInvestigation } from '@/components/context/ComplaintContextInvestigation'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const payment = 'transaction:10000000-0000-0000-0000-000000000001'
const graph = {
  root: 'complaint:CMP-42',
  truncated: false,
  nodes: [
    node('complaint:CMP-42', 'COMPLAINT', 1),
    node(payment, 'TRANSACTION', 1),
    node('payment-stage:domestic:payment:1', 'PAYMENT_STAGE', 1, '2026-09-13T10:00:00Z'),
    node('payment-stage:domestic:payment:2', 'RAIL_EVIDENCE', 2, '2026-09-13T10:01:00Z'),
    node('booking-transaction:tx-1', 'TRANSACTION_BOOKING', 0, '2026-09-13T10:02:00Z', 'transaction-service'),
    node('ledger-booking:journal-1', 'LEDGER_BOOKING', 0, '2026-09-13T10:03:00Z', 'ledger-service'),
    node('clearing-item:item-1', 'CLEARING_ITEM', 2, '2026-09-13T10:04:00Z', 'clearing-service'),
    node('clearing-evidence:item-1:2', 'CLEARING_EVIDENCE', 2, '2026-09-13T10:05:00Z', 'clearing-service'),
    node('return-evidence:sepa:payment:4', 'RETURN_EVIDENCE', 4, '2026-09-13T10:06:00Z', 'sepa-payment'),
  ],
  edges: [
    edge('complaint-payment', graphRoot(), payment, 'CONCERNS_TRANSACTION', 1),
    edge('created', payment, 'payment-stage:domestic:payment:1', 'CREATED', 1),
    edge('submitted', payment, 'payment-stage:domestic:payment:2', 'SUBMITTED_TO', 2),
    edge('booking', payment, 'booking-transaction:tx-1', 'BOOKING_REQUESTED', 0),
    edge('posted', 'booking-transaction:tx-1', 'ledger-booking:journal-1', 'BOOKED_AS', 0),
    edge('clearing-item', payment, 'clearing-item:item-1', 'SUBMITTED_TO', 2),
    edge('cleared', 'clearing-item:item-1', 'clearing-evidence:item-1:2', 'SETTLED', 2),
    edge('returned', payment, 'return-evidence:sepa:payment:4', 'RETURNED_BY', 4),
  ],
}

function graphRoot() { return 'complaint:CMP-42' }

function node(key: string, type: string, sourceVersion: number, validFrom = '2026-09-13T09:59:00Z', sourceSystem = 'domestic-payment') {
  return {
    key, namespace: 'COMPLAINT', type, sourceSystem, sourceRef: key,
    label: type, classification: 'RESTRICTED', validFrom, validTo: null,
    recordedAt: '2026-09-13T10:02:00Z', sourceVersion,
  }
}

function edge(id: string, from: string, to: string, relation: string, sourceVersion: number) {
  return {
    id, namespace: 'COMPLAINT', from, to, relation, evidenceRef: id,
    validFrom: '2026-09-13T10:00:00Z', validTo: null,
    recordedAt: '2026-09-13T10:02:00Z', sourceVersion,
  }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('complaint context investigation', () => {
  it('renders ordered payment lifecycle evidence from the authorized graph response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(graph), { status: 200 })))
    render(<LanguageProvider initialLanguage="en"><ComplaintContextInvestigation /></LanguageProvider>)

    fireEvent.change(screen.getByLabelText('Complaint reference'), { target: { value: 'CMP-42' } })
    fireEvent.change(screen.getByLabelText('Case ID'), { target: { value: 'case-7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show graph' }))

    const timeline = await screen.findByRole('heading', { name: 'Payment timeline' })
    const items = timeline.parentElement?.querySelectorAll('li') ?? []
    expect(items).toHaveLength(7)
    expect(items[0]).toHaveTextContent('CREATED')
    expect(items[1]).toHaveTextContent('SUBMITTED TO')
    expect(items[2]).toHaveTextContent('BOOKING REQUESTED')
    expect(items[3]).toHaveTextContent('BOOKED AS')
    expect(items[4]).toHaveTextContent('SUBMITTED TO')
    expect(items[5]).toHaveTextContent('SETTLED')
    expect(items[6]).toHaveTextContent('RETURNED BY')
    fireEvent.change(screen.getByLabelText('Case ID'), { target: { value: 'case-other' } })
    expect(screen.queryByRole('heading', { name: 'Payment timeline' })).not.toBeInTheDocument()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/context/complaints/CMP-42?caseId=case-7&purpose=PAYMENT_COMPLAINT',
    )
  })
  it('discards a late response after the investigation purpose changes', async () => {
    let resolve!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(done => { resolve = done })))
    render(<LanguageProvider initialLanguage="en"><ComplaintContextInvestigation /></LanguageProvider>)
    fireEvent.change(screen.getByLabelText('Complaint reference'), { target: { value: 'CMP-42' } })
    fireEvent.change(screen.getByLabelText('Case ID'), { target: { value: 'case-7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show graph' }))
    fireEvent.change(screen.getByLabelText('Investigation purpose'), { target: { value: 'OTHER' } })
    await act(async () => { resolve(new Response(JSON.stringify(graph))) })
    expect(screen.queryByRole('heading', { name: 'Payment timeline' })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Complaint relationship graph' })).not.toBeInTheDocument()
  })

})
