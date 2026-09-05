// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Suspense, useEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, ChevronDown, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { readStashedRow } from '@/lib/services/rowHandoff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge, statusTone, type Tone } from '@/components/ui'

// Mirrors the list-row shape on the payments page. The record can carry more
// fields than the table showed — the detail view surfaces all of them.
interface Payment {
  id: string
  type?: 'SEPA' | 'DOMESTIC'
  status?: string
  amount?: number
  currency?: string
  debtorIban?: string
  creditorIban?: string
  creditorAccountNumber?: string
  creditorBankCode?: string
  creditorName?: string
  remittanceInfo?: string
  endToEndId?: string
  createdAt?: string
  [k: string]: unknown
}

// These two statuses are specific to payment processing. Keep their explicit
// meaning here rather than broadening the cross-domain status vocabulary:
// RECEIVED means accepted by the payment service, not settled; SENT_TO_CLEARING
// remains an in-flight operator state that needs attention.
function paymentStatusTone(status: string | undefined): Tone {
  if (status === 'RECEIVED') return 'info'
  if (status === 'SENT_TO_CLEARING') return 'warning'
  return statusTone(status)
}

// Both payment rails expose an authorized by-id resource. Use the generic BFF so
// the browser keeps the session→bearer relay and the backend enforces read RBAC
// for this exact payment instead of broadening the request to the whole list.
const DETAIL_ROUTE: Record<string, { service: string; path: string }> = {
  SEPA: { service: 'sepa-payment', path: '/api/v1/sepa-payments' },
  DOMESTIC: { service: 'domestic-payment', path: '/api/v1/domestic-payments' },
}

export default function PaymentDetailPage() {
  return (
    <Suspense fallback={null}>
      <PaymentDetailContent />
    </Suspense>
  )
}

function PaymentDetailContent() {
  const { id } = useParams<{ id: string }>()
  const params = useSearchParams()
  const type = (params.get('type') ?? '').toUpperCase()
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const routeKey = `${type}:${id}`

  const [paymentSnapshot, setPaymentSnapshot] = useState<{ key: string; payment: Payment } | null>(null)
  const [loadingState, setLoadingState] = useState<{ key: string; active: boolean }>({ key: routeKey, active: true })
  const [unavailableSnapshot, setUnavailableSnapshot] = useState<{ key: string; kind: UnavailableKind } | null>(null)
  const [showRaw, setShowRaw] = useState(false)
  const requestGeneration = useRef(0)

  // Key every rendered state to the current route. A param/rail change must not
  // flash the previous payment or its failure before the new effect starts.
  const payment = paymentSnapshot?.key === routeKey ? paymentSnapshot.payment : null
  const unavailable = unavailableSnapshot?.key === routeKey
    ? { kind: unavailableSnapshot.kind }
    : null
  const loading = loadingState.key !== routeKey || loadingState.active

  async function load(resetForRoute = false) {
    const generation = ++requestGeneration.current
    const stillCurrent = () => requestGeneration.current === generation
    setLoadingState({ key: routeKey, active: true })

    // Show the handed-off row immediately on navigation (no round-trip); a
    // manual refresh retains the freshest currently displayed snapshot.
    const handedOff = resetForRoute ? readStashedRow<Payment>('payments', id) : null
    const stashed = handedOff?.id === id && handedOff.type === type ? handedOff : null
    if (resetForRoute) {
      setPaymentSnapshot(stashed ? { key: routeKey, payment: stashed } : null)
    }

    const target = DETAIL_ROUTE[type]
    if (!target) {
      // A missing/unknown rail cannot be refreshed truthfully. Keep a handed-off
      // preview if present, but mark it unavailable rather than presenting it as live.
      if (stillCurrent()) {
        setUnavailableSnapshot({ key: routeKey, kind: 'not_found' })
        setLoadingState({ key: routeKey, active: false })
      }
      return
    }
    try {
      const route = svcUrl(target.service, `${target.path}/${encodeURIComponent(id)}`)
      const res = await fetch(route, { signal: AbortSignal.timeout(10_000), cache: 'no-store' })
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        if (stillCurrent()) setUnavailableSnapshot({ key: routeKey, kind })
        return
      }
      const fresh = (await res.json()) as Payment
      if (!stillCurrent()) return
      setPaymentSnapshot({ key: routeKey, payment: { ...fresh, type: type as Payment['type'] } })
      setUnavailableSnapshot(null)
    } catch {
      if (stillCurrent()) setUnavailableSnapshot({ key: routeKey, kind: 'unreachable' })
    } finally {
      if (stillCurrent()) setLoadingState({ key: routeKey, active: false })
    }
  }

  useEffect(() => {
    void load(true)
    return () => { requestGeneration.current += 1 }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, type])

  const svcLabel = type === 'SEPA' ? t('SEPA-payment', 'SEPA-payment') : t('Domestic-payment', 'Domestic-payment')
  const stalePreviewCopy = unavailable?.kind === 'unauthorized'
    ? {
        title: t('Relace vypršela — zobrazuji uložený náhled', 'Session expired — showing saved preview'),
        detail: t('Uložený náhled může být zastaralý. Pro načtení živých dat se znovu přihlaste.', 'This saved preview may be stale. Sign in again to load live data.'),
      }
    : {
        title: t('Živá platba není dostupná — zobrazuji uložený náhled', 'Live payment unavailable — showing saved preview'),
        detail: t('Obnovení živých dat selhalo. Uložený náhled může být zastaralý; zkuste platbu obnovit znovu.', 'The live refresh failed. This saved preview may be stale; try refreshing the payment again.'),
      }

  return (
    <div>
      <PageHeader
        title={`${id.slice(0, 8)}…`}
        subtitle={t('Detail platebního příkazu', 'Payment order detail')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/payments" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('Platby', 'Payments')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current mono" style={{ fontSize: '12px' }}>{id.slice(0, 12)}…</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          {payment?.status && <StatusBadge status={payment.status} tone={paymentStatusTone(payment.status)} />}
          {payment?.type && <span className="tag">{payment.type}</span>}
          <Link href="/payments" className="btn btn-secondary"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět', 'Back')}</Link>
          <button type="button" className="btn btn-secondary" onClick={() => { void load() }} disabled={loading} aria-busy={loading} aria-label={t('Obnovit platbu', 'Refresh payment')}>
            <RefreshCw size={13} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {loading && !payment ? (
        <div style={{ padding: '40px 0', color: 'var(--text-tertiary)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <RefreshCw size={14} className="animate-spin" /> {t('Načítám platbu…', 'Loading payment…')}
        </div>
      ) : !payment && unavailable ? (
        <div className="card"><DataUnavailable kind={unavailable.kind} service={svcLabel} feature={t('Platba', 'Payment')} lang={language} /></div>
      ) : payment ? (
        <>
          {unavailable && (
            <div className="card" style={{ marginBottom: '14px' }}>
              <DataUnavailable
                kind={unavailable.kind}
                service={svcLabel}
                feature={t('Platba', 'Payment')}
                lang={language}
                dense
                title={stalePreviewCopy.title}
                detail={stalePreviewCopy.detail}
              />
            </div>
          )}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
          <div className="card">
            <div className="card-header"><span className="card-header-title">{t('Platba', 'Payment')}</span></div>
            <DetailRows rows={[
              { label: t('ID platby', 'Payment ID'), value: payment.id, mono: true },
              { label: t('Typ', 'Type'), value: payment.type ?? '—' },
              { label: t('Stav', 'Status'), value: payment.status ?? '—' },
              { label: t('Částka', 'Amount'), value: payment.amount != null ? `${Number(payment.amount).toLocaleString(numberLocale, { minimumFractionDigits: 2 })} ${payment.currency ?? ''}` : '—' },
              { label: 'End-to-End ID', value: (payment.endToEndId as string) ?? '—', mono: true },
              { label: t('Vytvořeno', 'Created'), value: payment.createdAt ? new Date(payment.createdAt).toLocaleString(numberLocale) : '—' },
            ]} />
          </div>
          <div className="card">
            <div className="card-header"><span className="card-header-title">{t('Strany & směrování', 'Parties & routing')}</span></div>
            <DetailRows rows={[
              { label: t('Plátce IBAN', 'Debtor IBAN'), value: payment.debtorIban ?? '—', mono: true },
              { label: t('Příjemce', 'Creditor'), value: payment.creditorName ?? '—' },
              { label: t('Příjemce IBAN', 'Creditor IBAN'), value: payment.creditorIban ?? '—', mono: true },
              { label: t('Účet / kód banky příjemce', 'Creditor account / bank'), value: payment.creditorAccountNumber ? `${payment.creditorAccountNumber}${payment.creditorBankCode ? '/' + payment.creditorBankCode : ''}` : '—', mono: true },
              { label: t('Zpráva pro příjemce', 'Remittance info'), value: payment.remittanceInfo ?? '—' },
            ]} />
          </div>
          <div className="card" style={{ gridColumn: '1 / -1' }}>
            <button type="button" aria-expanded={showRaw} aria-controls={showRaw ? 'payment-raw-payload' : undefined} aria-label={showRaw ? t('Skrýt surová data platby', 'Hide raw payment payload') : t('Zobrazit surová data platby', 'Show raw payment payload')} onClick={() => setShowRaw(s => !s)}
              style={{ width: '100%', display: 'flex', alignItems: 'center', gap: '6px', padding: '12px 18px', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>
              {showRaw ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
              {t('Surová data (JSON)', 'Raw payload (JSON)')}
            </button>
            {showRaw && (
              <div id="payment-raw-payload" role="region" aria-label={t('Surová data platby', 'Raw payment payload')}>
                <pre style={{ margin: 0, padding: '0 18px 18px', fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', overflowX: 'auto' }}>
                  {JSON.stringify(payment, null, 2)}
                </pre>
              </div>
            )}
          </div>
          </div>
        </>
      ) : null}
    </div>
  )
}

function DetailRows({ rows }: { rows: { label: string; value: string; mono?: boolean }[] }) {
  return (
    <div style={{ padding: '4px 0' }}>
      {rows.map((row, i, arr) => (
        <div key={row.label} style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '16px',
          padding: '10px 18px', borderBottom: i < arr.length - 1 ? '1px solid var(--border)' : 'none',
        }}>
          <span style={{ fontSize: '12px', color: 'var(--text-secondary)', flexShrink: 0 }}>{row.label}</span>
          <span style={{
            fontSize: '12px', fontWeight: 500, color: 'var(--text-primary)', textAlign: 'right',
            fontFamily: row.mono ? 'var(--font-mono)' : 'inherit',
            maxWidth: '320px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>{row.value}</span>
        </div>
      ))}
    </div>
  )
}
