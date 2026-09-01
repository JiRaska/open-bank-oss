// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Suspense, useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, ChevronDown, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
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

// The list route is the source of truth (no by-id backend endpoint exists);
// refresh re-fetches the list and picks this id out of it.
const LIST_ROUTE: Record<string, string> = {
  SEPA: '/api/sepa-payments',
  DOMESTIC: '/api/domestic-payments',
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

  const [payment, setPayment] = useState<Payment | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [showRaw, setShowRaw] = useState(false)

  async function load() {
    setLoading(true)
    // Show the handed-off row immediately (no round-trip); refresh in the background.
    const stashed = readStashedRow<Payment>('payments', id)
    if (stashed) { setPayment(stashed); setUnavailable(null) }

    const route = LIST_ROUTE[type]
    if (!route) {
      // Direct URL with no/unknown type and no stash — we can't know which service to ask.
      if (!stashed) setUnavailable({ kind: 'not_found' })
      setLoading(false)
      return
    }
    try {
      const res = await fetch(route, { signal: AbortSignal.timeout(10_000), cache: 'no-store' })
      if (!res.ok) {
        if (!stashed) setUnavailable({ kind: await classifyBffFailure(res) })
        setLoading(false)
        return
      }
      const body = (await res.json()) as unknown
      const items = (Array.isArray(body) ? body : ((body as { items?: unknown[] }).items ?? [])) as Payment[]
      const found = items.find(p => p.id === id)
      if (found) { setPayment({ ...found, type: type as Payment['type'] }); setUnavailable(null) }
      else if (!stashed) setUnavailable({ kind: 'not_found' })
    } catch {
      if (!stashed) setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [id, type])

  const svcLabel = type === 'SEPA' ? t('SEPA-payment', 'SEPA-payment') : t('Domestic-payment', 'Domestic-payment')

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
          <button type="button" className="btn btn-secondary" onClick={load} disabled={loading} aria-busy={loading} aria-label={t('Obnovit platbu', 'Refresh payment')}>
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
