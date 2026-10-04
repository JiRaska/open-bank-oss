// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { useSession } from 'next-auth/react'
import * as Dialog from '@radix-ui/react-dialog'
import { RefreshCw } from 'lucide-react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { EntityChip } from '@/components/entities/EntityChip'
import { HumanReference } from '@/components/ui'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { useSingleFlight } from '@/lib/mutations/singleFlight'
import {
  approvalApiPath,
  approvalTarget,
  isOwnRequest,
  operatorApprovalSchema,
  type ApprovalTarget,
  type OperatorApproval,
  type OperatorApprovalDomain,
} from '@/lib/approvals/operator'

const SERVICE: Record<OperatorApprovalDomain, string> = { sca: 'sca-service', settlement: 'settlement-service' }

const STATUS: Record<OperatorApproval['status'], { cs: string; en: string }> = {
  PENDING: { cs: 'Čeká na rozhodnutí', en: 'Awaiting decision' },
  APPROVED: { cs: 'Schváleno', en: 'Approved' },
  REJECTED: { cs: 'Zamítnuto', en: 'Rejected' },
  EXECUTED: { cs: 'Oprávnění spotřebováno', en: 'Authorization consumed' },
}

const ACTION: Record<string, { cs: string; en: string }> = {
  'device.enroll': { cs: 'Registrace zařízení SCA', en: 'SCA device enrollment' },
  'device.revoke': { cs: 'Odvolání zařízení SCA', en: 'SCA device revocation' },
  'scaChallenge.consume': { cs: 'Spotřebování SCA challenge', en: 'SCA challenge consumption' },
  'settlement.create': { cs: 'Vytvoření settlementu', en: 'Settlement creation' },
}

export function OperatorApprovalWorkbench({ domain, id }: { domain: OperatorApprovalDomain; id: string }) {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const viewer = principalNameFromToken(session?.user?.accessToken)
  const [approval, setApproval] = useState<OperatorApproval | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [reviewed, setReviewed] = useState(false)
  const [intent, setIntent] = useState<boolean | null>(null)
  const [message, setMessage] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const generation = useRef(0)
  const cancel = useRef<HTMLButtonElement>(null)
  const flight = useSingleFlight()
  const path = approvalApiPath(domain, id)

  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    const current = ++generation.current
    async function load() {
      try {
        const response = await fetch(path, { cache: 'no-store', signal: AbortSignal.timeout(10000) })
        if (current !== generation.current) return
        if (!response.ok) {
          setUnavailable(await classifyBffFailure(response))
          return
        }
        const parsed = operatorApprovalSchema.safeParse(await response.json())
        if (current !== generation.current) return
        if (!parsed.success || parsed.data.id !== id) {
          setUnavailable('error')
          return
        }
        setApproval(parsed.data)
        setUncertain(false)
      } catch {
        if (current === generation.current) setUnavailable('unreachable')
      } finally {
        if (current === generation.current) setLoading(false)
      }
    }
    void load()
    return () => { generation.current += 1 }
  }, [id, path, reloadKey])

  function reload() {
    setLoading(true)
    setApproval(null)
    setUnavailable(null)
    setReviewed(false)
    setIntent(null)
    setMessage('')
    setReloadKey(key => key + 1)
  }

  const target = approval ? approvalTarget(domain, approval) : null
  const own = approval ? isOwnRequest(approval, viewer) : false
  const pending = approval?.status === 'PENDING' && approval.expired !== true && target !== null && !uncertain
  const decidable = pending && !own
  const actionLabel = approval ? (ACTION[approval.action] ? t(ACTION[approval.action].cs, ACTION[approval.action].en) : approval.action) : ''

  async function confirm() {
    if (intent === null || !approval || !decidable || (intent && !reviewed)) return
    const decision = intent
    await flight.run(`${domain}:${id}`, async () => {
      try {
        const response = await fetch(path, {
          method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ approve: decision }), signal: AbortSignal.timeout(10000),
        })
        if (!response.ok) {
          setUncertain(true)
          setMessage(response.status === 403
            ? t('Rozhodnutí nebylo povoleno. Žádost musí posoudit jiný oprávněný operátor.', 'The decision was not allowed. A different authorized operator must review the request.')
            : response.status === 409
              ? t('Žádost už byla rozhodnuta nebo spotřebována. Znovu načtěte stav.', 'The request was already decided or consumed. Reload the state.')
              : t('Výsledek nelze potvrdit. Před dalším rozhodnutím znovu načtěte stav.', 'The outcome could not be confirmed. Reload the state before another decision.'))
          return
        }
        const parsed = operatorApprovalSchema.safeParse(await response.json())
        if (!parsed.success || parsed.data.id !== id || parsed.data.status !== (decision ? 'APPROVED' : 'REJECTED')) {
          throw new Error('unexpected approval response')
        }
        setApproval(current => ({ ...(current ?? parsed.data), ...parsed.data, summary: current?.summary ?? parsed.data.summary }))
        setMessage(decision
          ? t('Schválení zaznamenáno. Autor nyní může zopakovat původní operaci.', 'Approval recorded. The maker can now retry the original operation.')
          : t('Žádost byla zamítnuta.', 'The request was rejected.'))
      } catch {
        setUncertain(true)
        setMessage(t('Odpověď chybí. Rozhodnutí mohlo uspět; znovu načtěte stav.', 'The response is missing. The decision may have succeeded; reload the state.'))
      } finally {
        setIntent(null)
      }
    })
  }

  const copyLabel = t('Kopírovat identifikátor', 'Copy identifier')

  return <section aria-busy={loading || flight.busy} className="card" style={{ padding: 24, marginTop: 16 }}>
    <button type="button" className="btn btn-secondary" disabled={loading || flight.busy} onClick={reload}>
      <RefreshCw size={14} aria-hidden="true" />{t('Znovu načíst stav', 'Reload state')}
    </button>
    {loading && <p role="status">{t('Načítám schválení…', 'Loading approval…')}</p>}
    {!loading && unavailable && <DataUnavailable kind={unavailable} service={SERVICE[domain]} feature={t('schválení operace', 'operation approval')} lang={language} />}
    {!loading && approval && <>
      <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(100px, 1fr) minmax(0, 3fr)', gap: 12, overflowWrap: 'anywhere' }}>
        <dt>{t('Žádost', 'Approval')}</dt>
        <dd><HumanReference label={actionLabel} reference={approval.id} copyLabel={copyLabel} /></dd>
        <dt>{t('Požádal', 'Requested by')}</dt><dd>{approval.makerId ?? '—'}</dd>
        <dt>{t('Stav', 'State')}</dt>
        <dd>{t(STATUS[approval.status].cs, STATUS[approval.status].en)}{approval.expired ? ` · ${t('platnost vypršela', 'expired')}` : ''}</dd>
        <dt>{t('Posoudil', 'Reviewed by')}</dt><dd>{approval.decidedBy ?? '—'}</dd>
        {target && <TargetRows target={target} copyLabel={copyLabel} />}
      </dl>
      <p style={{ color: 'var(--text-secondary)' }}>{t(
        'Schválení samo neprovede operaci. Autor ji musí zopakovat se stejnými parametry; stav „Oprávnění spotřebováno“ neznamená dokončení operace.',
        'Approval does not execute the operation. The maker must retry it with identical parameters; “Authorization consumed” does not mean the operation completed.',
      )}</p>
      {!target && <p role="status">{t('Tento typ žádosti nelze v tomto rozhraní bezpečně posoudit.', 'This request type cannot be safely reviewed in this interface.')}</p>}
      {pending && own && <p role="status">{t(
        'Tuto žádost jste vytvořil/a vy. Rozhodnout ji musí jiný operátor.',
        'You created this request. A different operator must decide it.',
      )}</p>}
      {decidable && <div style={{ display: 'grid', gap: 14 }}>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <input type="checkbox" checked={reviewed} onChange={event => setReviewed(event.target.checked)} disabled={flight.busy} />
          {t('Ověřil/a jsem autora, účel a přesný cíl operace.', 'I verified the maker, purpose and exact operation target.')}
        </label>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <button type="button" className="btn btn-primary" disabled={!reviewed || flight.busy} onClick={() => setIntent(true)}>{t('Schválit', 'Approve')}</button>
          <button type="button" className="btn btn-danger" disabled={flight.busy} onClick={() => setIntent(false)}>{t('Zamítnout', 'Reject')}</button>
        </div>
      </div>}
    </>}
    {message && <p role="status" aria-live="polite">{message}</p>}
    {intent !== null && approval && <Dialog.Root open onOpenChange={open => { if (!open && !flight.busy) setIntent(null) }}>
      <Dialog.Portal>
        <Dialog.Overlay style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)' }} />
        <Dialog.Content role="alertdialog" aria-busy={flight.busy} className="card"
          onOpenAutoFocus={event => { event.preventDefault(); cancel.current?.focus() }}
          onEscapeKeyDown={event => { if (flight.busy) event.preventDefault() }}
          onInteractOutside={event => event.preventDefault()}
          style={{ position: 'fixed', zIndex: 1201, top: '50%', left: '50%', transform: 'translate(-50%, -50%)', width: 'calc(100% - 40px)', maxWidth: 560, maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 24 }}>
          <Dialog.Title>{intent ? t('Potvrdit schválení', 'Confirm approval') : t('Potvrdit zamítnutí', 'Confirm rejection')}</Dialog.Title>
          <Dialog.Description>{t('Rozhodujete o této konkrétní žádosti. Server zakazuje vlastní schválení.', 'You are deciding this exact request. The server refuses self-approval.')}</Dialog.Description>
          <p style={{ overflowWrap: 'anywhere' }}><HumanReference label={actionLabel} reference={approval.id} copyLabel={copyLabel} /></p>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            <button ref={cancel} type="button" className="btn btn-secondary" disabled={flight.busy} onClick={() => setIntent(null)}>{t('Zpět ke kontrole', 'Back to review')}</button>
            <button type="button" className="btn btn-primary" disabled={flight.busy} onClick={() => void confirm()}>{flight.busy ? t('Ukládám…', 'Recording…') : t('Potvrdit rozhodnutí', 'Confirm decision')}</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>}
  </section>
}

function TargetRows({ target, copyLabel }: { target: ApprovalTarget; copyLabel: string }) {
  const { t } = useLanguage()
  if (target.kind === 'settlement') {
    return <><dt>{t('Vázaný pokyn', 'Bound instruction')}</dt><dd className="mono" style={{ whiteSpace: 'pre-wrap' }}>{target.summary}</dd></>
  }
  if (target.kind === 'challenge') {
    return <><dt>{t('Cíl operace', 'Operation target')}</dt>
      <dd><HumanReference label={t('SCA challenge', 'SCA challenge')} reference={target.challenge} copyLabel={copyLabel} /></dd></>
  }
  return <>
    <dt>{t('Klient', 'Party')}</dt><dd><EntityChip type="party" id={target.party} /></dd>
    <dt>{t('Zařízení', 'Device')}</dt>
    <dd>{t(
      'Konkrétní zařízení služba váže otiskem původního požadavku a nezveřejňuje ho; ověřte ho s autorem.',
      'The service binds the exact device to the original request fingerprint and does not publish it; confirm it with the maker.',
    )}</dd>
  </>
}
