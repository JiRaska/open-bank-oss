// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { useSingleFlight } from '@/lib/mutations/singleFlight'
import { approvalTarget, scaApprovalSchema, type ScaApproval } from '@/lib/sca/approval'

export function ScaApprovalWorkbench({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const [approval, setApproval] = useState<ScaApproval | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [reviewed, setReviewed] = useState(false)
  const [fingerprint, setFingerprint] = useState('')
  const [intent, setIntent] = useState<boolean | null>(null)
  const [message, setMessage] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const generation = useRef(0)
  const cancel = useRef<HTMLButtonElement>(null)
  const flight = useSingleFlight()

  const load = useCallback(async () => {
    const current = ++generation.current
    try {
      const response = await fetch(`/api/sca/approvals/${encodeURIComponent(id)}`, {
        cache: 'no-store', signal: AbortSignal.timeout(10000),
      })
      if (current !== generation.current) return
      if (!response.ok) {
        setUnavailable(await classifyBffFailure(response))
        return
      }
      const parsed = scaApprovalSchema.safeParse(await response.json())
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
  }, [id])

  function reload() {
    setLoading(true)
    setApproval(null)
    setUnavailable(null)
    setReviewed(false)
    setFingerprint('')
    setIntent(null)
    setMessage('')
    void load()
  }

  useEffect(() => {
    void load()
    return () => { generation.current += 1 }
  }, [load])

  const target = approval ? approvalTarget(approval) : null
  const pending = approval?.status === 'PENDING' && target !== null && !uncertain
  const matches = target && (!target.fingerprint || fingerprint.trim().toLowerCase() === target.target)

  async function confirm() {
    if (intent === null || !approval || !pending || (intent && (!reviewed || !matches))) return
    const decision = intent
    await flight.run(id, async () => {
      try {
        const response = await fetch(`/api/sca/approvals/${encodeURIComponent(id)}`, {
          method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ approve: decision }), signal: AbortSignal.timeout(10000),
        })
        if (!response.ok) {
          setUncertain(true)
          setMessage(response.status === 403
            ? t('Rozhodnutí nebylo povoleno. Žádost musí posoudit jiný oprávněný operátor.', 'The decision was not allowed. A different authorized operator must review the request.')
            : t('Výsledek nelze potvrdit. Před dalším rozhodnutím znovu načtěte stav.', 'The outcome could not be confirmed. Reload the state before another decision.'))
          return
        }
        const parsed = scaApprovalSchema.safeParse(await response.json())
        if (!parsed.success || parsed.data.id !== id || parsed.data.status !== (decision ? 'APPROVED' : 'REJECTED')) {
          throw new Error('unexpected approval response')
        }
        setApproval(parsed.data)
        setMessage(decision
          ? t('Schválení zaznamenáno. Autor nyní může zopakovat původní operaci.', 'Approval recorded. The maker can now retry the original operation.')
          : t('Žádost byla zamítnuta.', 'The request was rejected.'))
      } catch {
        setUncertain(true)
        setMessage(t('Odpověď chybí. Operace mohla uspět; znovu načtěte stav.', 'The response is missing. The decision may have succeeded; reload the state.'))
      } finally {
        setIntent(null)
      }
    })
  }

  return <section aria-busy={loading || flight.busy} className="card" style={{ padding: 24, marginTop: 16 }}>
    <button type="button" className="btn btn-secondary" disabled={loading || flight.busy} onClick={reload}>
      {t('Znovu načíst stav', 'Reload state')}
    </button>
    {loading && <p role="status">{t('Načítám schválení…', 'Loading approval…')}</p>}
    {!loading && unavailable && <DataUnavailable kind={unavailable} service="sca-service" feature={t('schválení SCA', 'SCA approval')} lang={language} />}
    {!loading && approval && <>
      <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(100px, 1fr) minmax(0, 3fr)', gap: 12, overflowWrap: 'anywhere' }}>
        <dt>{t('Žádost', 'Approval')}</dt><dd>{approval.id}</dd>
        <dt>{t('Akce', 'Action')}</dt><dd>{approval.action}</dd>
        <dt>{t('Požádal', 'Requested by')}</dt><dd>{approval.makerId}</dd>
        <dt>{t('Stav', 'State')}</dt><dd>{approval.status}</dd>
        <dt>{t('Posoudil', 'Reviewed by')}</dt><dd>{approval.decidedBy ?? '—'}</dd>
        {target?.party && <><dt>{t('Klient', 'Party')}</dt><dd>{target.party}</dd></>}
        <dt>{target?.fingerprint ? t('Otisk registrace', 'Enrollment fingerprint') : t('Cíl operace', 'Operation target')}</dt>
        <dd>{target?.target ?? approval.resourceId ?? '—'}</dd>
      </dl>
      <p style={{ color: 'var(--text-secondary)' }}>{t(
        'Schválení samo neprovede operaci. Stav EXECUTED označuje spotřebované oprávnění; výsledek ověřte u zařízení nebo challenge.',
        'Approval does not execute the operation. EXECUTED marks a consumed authorization; verify the result on the device or challenge.',
      )}</p>
      {!target && <p role="status">{t('Tento typ žádosti nelze v tomto rozhraní bezpečně posoudit.', 'This request type cannot be safely reviewed in this interface.')}</p>}
      {pending && <div style={{ display: 'grid', gap: 14 }}>
        {target?.fingerprint && <label>
          {t('Očekávaný otisk z ověřeného požadavku', 'Expected fingerprint from the verified request')}
          <input className="input" value={fingerprint} onChange={event => setFingerprint(event.target.value)} disabled={flight.busy} maxLength={64} autoComplete="off" style={{ display: 'block', width: '100%', marginTop: 6 }} />
        </label>}
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <input type="checkbox" checked={reviewed} onChange={event => setReviewed(event.target.checked)} disabled={flight.busy} />
          {t('Ověřil/a jsem autora, účel a přesný cíl operace.', 'I verified the maker, purpose and exact operation target.')}
        </label>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <button type="button" className="btn btn-primary" disabled={!reviewed || !matches || flight.busy} onClick={() => setIntent(true)}>{t('Schválit', 'Approve')}</button>
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
          <p style={{ overflowWrap: 'anywhere' }}>{approval.id}</p>
          <p style={{ overflowWrap: 'anywhere' }}>{approval.action}: {approval.resourceId}</p>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            <button ref={cancel} type="button" className="btn btn-secondary" disabled={flight.busy} onClick={() => setIntent(null)}>{t('Zpět ke kontrole', 'Back to review')}</button>
            <button type="button" className="btn btn-primary" disabled={flight.busy} onClick={() => void confirm()}>{flight.busy ? t('Ukládám…', 'Recording…') : t('Potvrdit rozhodnutí', 'Confirm decision')}</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>}
  </section>
}
