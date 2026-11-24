// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { setTimeout as wait } from 'node:timers/promises'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

interface AppSession {
  edgeUrl: string
  appToken: string
}

export async function observeApproval(
  app: AppSession,
  proposalId: string,
  timeoutMs: number,
  onPending: () => void,
  intervalMs = 1_000,
): Promise<void> {
  if (!UUID.test(proposalId)) throw new Error('proposal observation requires a valid proposal ID')
  const deadline = Date.now() + timeoutMs
  const detailUrl = `${app.edgeUrl}/mcp-proposals/${proposalId}`
  let sawPending = false

  while (Date.now() < deadline) {
    const remaining = Math.max(1, deadline - Date.now())
    const response = await fetch(detailUrl, {
      method: 'GET',
      headers: { Authorization: `Bearer ${app.appToken}`, Accept: 'application/json' },
      redirect: 'error',
      signal: AbortSignal.timeout(remaining),
    })
    if (!response.ok) throw new Error(`customer-edge proposal read returned HTTP ${response.status}`)
    const proposal = await response.json()
    if (proposal?.id !== proposalId) throw new Error('customer-edge returned a different proposal')
    if (!sawPending) {
      if (proposal.status !== 'PENDING' || proposal.executedPaymentId != null) {
        throw new Error('expected the proposal to be pending before the app action')
      }
      sawPending = true
      onPending()
    } else if (proposal.status === 'EXECUTED') {
      if (typeof proposal.executedPaymentId !== 'string' || !UUID.test(proposal.executedPaymentId)) {
        throw new Error('executed proposal is missing its payment ID')
      }
      return
    } else if (proposal.status !== 'PENDING' && proposal.status !== 'APPROVED') {
      throw new Error('proposal did not reach payment initiation')
    }
    await wait(Math.min(intervalMs, Math.max(0, deadline - Date.now())))
  }
  throw new Error('timed out waiting for sandbox payment initiation')
}

/** The synthetic proposal proof ends at a read-only owner view. */
export async function verifyPendingOwnerProposal(
  app: AppSession & { partyId: string; accountId: string },
  proposalId: string,
  args: Record<string, unknown>,
  clientId: string,
  timeoutMs: number,
): Promise<void> {
  if (!UUID.test(proposalId)) throw new Error('owner verification requires a valid proposal ID')
  const response = await fetch(`${app.edgeUrl}/mcp-proposals/${proposalId}`, {
    method: 'GET',
    headers: { Authorization: `Bearer ${app.appToken}`, Accept: 'application/json' },
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs),
  })
  if (response.status !== 200) throw new Error(`customer-edge proposal read returned HTTP ${response.status}`)
  const proposal = await response.json()
  const amount = typeof args.amount === 'string' ? /^(\d+)(?:\.(\d{1,2}))?$/.exec(args.amount) : null
  const normalizeText = (value: unknown) => typeof value === 'string' ? value.trim().normalize('NFC') || null : null
  const iban = typeof args.creditorIban === 'string' ? args.creditorIban.replace(/ /g, '').toUpperCase() : null
  if (proposal?.id !== proposalId || proposal.status !== 'PENDING' || proposal.executedPaymentId != null ||
      proposal.partyId !== app.partyId || proposal.debtorAccountId !== app.accountId ||
      proposal.mcpClientId !== clientId || proposal.currency !== args.currency ||
      !amount || proposal.amount !== `${amount[1]}.${(amount[2] ?? '').padEnd(2, '0')}` ||
      proposal.creditorIban !== iban || proposal.creditorName !== normalizeText(args.creditorName) ||
      (proposal.reference ?? null) !== normalizeText(args.reference)) {
    throw new Error('owner view does not match the pending synthetic proposal')
  }
}
