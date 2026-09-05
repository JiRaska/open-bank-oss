// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useCallback, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  CreditCard, Search, RefreshCw, CheckCircle2, XCircle, Clock, ChevronRight, Plus, ShieldCheck, X, Layers,
  Smartphone, Scale,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { hasPermission } from '@/lib/auth/roles'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import { CARD_STATUSES, type CardTransition } from '@/lib/cards/lifecycle'
import { CARD_TYPES, type Card } from '@/lib/cards/types'
import { cardStatusColor } from '@/components/cards/CardStatusChip'
import { CardLifecycleMap } from '@/components/cards/CardLifecycleMap'
import { CardTransitionButtons } from '@/components/cards/CardTransitionButtons'
import { ConfirmTransitionDialog } from '@/components/cards/ConfirmTransitionDialog'
import { CardOperationFeedback } from '@/components/cards/CardOperationFeedback'
import { IssueCardDialog } from '@/components/cards/IssueCardDialog'
import { useCardOperations } from '@/lib/cards/useCardOperations'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatCard } from '@/components/ui/StatCard'
import type { Tone } from '@/components/ui/tone'

// Admin-UI rule #2: page the render. `GET /api/v1/cards` is an unpaginated
// list-all on the service side, so the cap has to be applied here — a portfolio
// of thousands of cards must not become thousands of DOM rows.
const PAGE_SIZE = 25

const ALL = '__ALL__'

export default function CardsPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const router = useRouter()
  const { data: session } = useSession()
  const canIssue = hasPermission(session?.user?.roles ?? [], 'cards:issue')
  const canManage = hasPermission(session?.user?.roles ?? [], 'cards:manage')
  const canBlock = hasPermission(session?.user?.roles ?? [], 'cards:block')
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>(ALL)
  const [typeFilter, setTypeFilter] = useState<string>(ALL)
  const [highlighted, setHighlighted] = useState<string | null>(null)
  const [visible, setVisible] = useState(PAGE_SIZE)
  const [pending, setPending] = useState<{ card: Card; transition: CardTransition } | null>(null)
  const [issuing, setIssuing] = useState(false)
  const cardsResultsRef = useRef<HTMLElement>(null)
  const closeFocusOverrideRef = useRef<HTMLElement | null>(null)

  // Single graceful data path (admin-ui rule #1): the hook classifies a non-OK
  // BFF response and auto-wakes a scaled-to-zero pod (KEDA, ADR-0057) instead of
  // showing a cold 503 as "not responding".
  const { data, loading, unavailable, waking, reload } = useServiceResource<Card[]>(
    svcUrl('card-issuance-service', '/api/v1/cards'),
    { select: (raw) => (Array.isArray(raw) ? (raw as Card[]) : ((raw as { cards?: Card[] }).cards ?? [])) },
  )
  const cards = useMemo(() => data ?? [], [data])

  // Every write goes through one hook, so the list and the detail view classify,
  // explain and four-eyes-handle a failure identically.
  const ops = useCardOperations(reload)

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return cards.filter(c => {
      if (statusFilter !== ALL && c.status !== statusFilter) return false
      if (typeFilter !== ALL && c.cardType !== typeFilter) return false
      if (!needle) return true
      return (
        c.maskedPan?.toLowerCase().includes(needle) ||
        c.cardholderName?.toLowerCase().includes(needle) ||
        c.productCode?.toLowerCase().includes(needle) ||
        String(c.cardType).toLowerCase().includes(needle) ||
        String(c.status).toLowerCase().includes(needle)
      )
    })
  }, [cards, search, statusFilter, typeFilter])

  const page = filtered.slice(0, visible)
  const highlightedCard = cards.find(c => c.id === highlighted) ?? null
  const hasFilters = search.trim().length > 0 || statusFilter !== ALL || typeFilter !== ALL

  const open = useCallback((cardId: string) => router.push(`/cards/${cardId}`), [router])
  const clearFilters = () => {
    setSearch('')
    setStatusFilter(ALL)
    setTypeFilter(ALL)
    setVisible(PAGE_SIZE)
  }

  const onSelectTransition = (card: Card, tr: CardTransition) => {
    setHighlighted(card.id)
    ops.setFeedback(null)
    if (tr.irreversible) {
      closeFocusOverrideRef.current = null
      setPending({ card, transition: tr })
    }
    else void ops.runTransition(card, tr)
  }

  const countBy = (status: string) => cards.filter(c => c.status === status).length

  const chip = (active: boolean): React.CSSProperties => ({
    padding: '4px 11px', borderRadius: '999px', fontSize: '11px', fontWeight: 700, cursor: 'pointer',
    background: active ? 'var(--accent-bg)' : 'var(--surface-2)',
    color: active ? 'var(--accent-text)' : 'var(--text-secondary)',
    border: `1px solid ${active ? 'var(--accent)' : 'var(--border)'}`,
  })

  return (
    <AuthGuard permission="cards:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader
          icon={<CreditCard size={20} aria-hidden="true" />}
          title={t('Vydávání karet', 'Card Issuance')}
          subtitle={t('Vydávání a správa platebních karet — PCI DSS Level 1', 'Card issuance and management — PCI DSS Level 1')}
          actions={<div className="flex flex-wrap items-center gap-2">
            <ServiceStatusBadge
              label="card-issuance :8118"
              loading={loading}
              waking={waking}
              unavailable={unavailable}
              copy={{
                up: t('card-issuance běží', 'card-issuance is up'),
                idle: t('card-issuance spí (scale-to-zero), probouzí se…', 'card-issuance idle (scaled to zero), waking…'),
                down: t('card-issuance neodpovídá', 'card-issuance is not responding'),
                checking: t('Zjišťuji stav služby…', 'Checking service…'),
              }}
            />
            {canIssue && (
              <button type="button" className="btn btn-primary btn-sm" onClick={() => { ops.setFeedback(null); setIssuing(true) }}>
                <Plus size={13} aria-hidden="true" /> {t('Vydat kartu', 'Issue a card')}
              </button>
            )}
            {/* The capability matrix is a sibling surface, not a filter on this list: it answers
                "which network offers what, and what do we bind" rather than anything about the
                cards below (ADR-0283 phase 3). */}
            <Link href="/cards/capabilities" className="btn btn-ghost btn-sm">
              <Layers size={13} aria-hidden="true" /> {t('Schopnosti sítí', 'Network capabilities')}
            </Link>
            {/* Both desks are per-CARD, not fleet-wide: the token vault and the case file belong to
                the network, so a list of "all tokens" or "all cases" would be a list of what this
                bank happens to have recorded (ADR-0283 phase 3). */}
            <Link href="/cards/tokens" className="btn btn-ghost btn-sm">
              <Smartphone size={13} aria-hidden="true" /> {t('Síťové tokeny', 'Network tokens')}
            </Link>
            <Link href="/cards/disputes" className="btn btn-ghost btn-sm">
              <Scale size={13} aria-hidden="true" /> {t('Karetní spory', 'Card disputes')}
            </Link>
            <button type="button" className="btn btn-ghost btn-sm" onClick={reload} disabled={loading} aria-busy={loading} aria-label={t('Obnovit karty', 'Refresh cards')}>
              <RefreshCw size={13} aria-hidden="true" /> {t('Obnovit', 'Refresh')}
            </button>
          </div>}
        />

        {/* KPIs */}
        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Celkem karet', 'Total cards'), value: cards.length, icon: <CreditCard size={16} /> },
            { label: t('Aktivní', 'Active'), value: countBy('ACTIVE'), icon: <CheckCircle2 size={16} />, tone: 'success' as Tone },
            { label: t('Blokované', 'Blocked'), value: countBy('BLOCKED'), icon: <XCircle size={16} />, tone: 'danger' as Tone },
            { label: t('Čekající', 'Pending'), value: countBy('PENDING'), icon: <Clock size={16} />, tone: 'warning' as Tone },
          ].map(k => <StatCard key={k.label} label={k.label} value={k.value} icon={k.icon} tone={k.tone} />)}
        </div>

        <CardLifecycleMap current={highlightedCard?.status} />

        {!pending && <CardOperationFeedback feedback={ops.feedback} onDismiss={() => ops.setFeedback(null)} />}

        {/* Table */}
        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'grid', gap: '10px' }}>
            <div style={{ position: 'relative' }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => { setSearch(e.target.value); setVisible(PAGE_SIZE) }}
                placeholder={t('Hledat podle PAN, držitele nebo produktu…', 'Search by PAN, cardholder or product…')}
                aria-label={t('Hledat karty', 'Search cards')}
                aria-controls="cards-results"
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
            <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap', alignItems: 'center' }}>
              <div role="group" aria-label={t('Filtr podle stavu', 'Filter by status')} style={{ display: 'flex', gap: '5px', flexWrap: 'wrap' }}>
                <button type="button" aria-controls="cards-results" aria-pressed={statusFilter === ALL} style={chip(statusFilter === ALL)} onClick={() => { setStatusFilter(ALL); setVisible(PAGE_SIZE) }}>
                  {t('Vše', 'All')} · {cards.length}
                </button>
                {CARD_STATUSES.filter(s => countBy(s) > 0).map(s => {
                  const c = cardStatusColor(s)
                  const active = statusFilter === s
                  return (
                    <button key={s} type="button" aria-controls="cards-results" aria-pressed={active} onClick={() => { setStatusFilter(active ? ALL : s); setVisible(PAGE_SIZE) }}
                      style={{ ...chip(active), background: active ? c.bg : 'var(--surface-2)', color: active ? c.text : 'var(--text-secondary)', borderColor: active ? c.border : 'var(--border)' }}>
                      {s} · {countBy(s)}
                    </button>
                  )
                })}
              </div>
              <div role="group" aria-label={t('Filtr podle typu', 'Filter by type')} style={{ display: 'flex', gap: '5px', flexWrap: 'wrap' }}>
                {CARD_TYPES.filter(ct => cards.some(c => c.cardType === ct)).map(ct => {
                  const active = typeFilter === ct
                  return (
                    <button key={ct} type="button" aria-controls="cards-results" aria-pressed={active} style={chip(active)} onClick={() => { setTypeFilter(active ? ALL : ct); setVisible(PAGE_SIZE) }}>
                      {ct}
                    </button>
                  )
                })}
              </div>
              {hasFilters && <button type="button" className="btn btn-ghost btn-sm" onClick={clearFilters} aria-controls="cards-results" aria-label={t('Vyčistit všechny filtry karet', 'Clear all card filters')}>
                <X size={13} aria-hidden="true" /> {t('Vyčistit filtry', 'Clear filters')}
              </button>}
            </div>
            <p role="status" aria-live="polite" style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-tertiary)' }}>
              {hasFilters
                ? t(`${filtered.length} karet odpovídá filtrům`, `${filtered.length} cards match the filters`)
                : t(`${cards.length} karet v portfoliu`, `${cards.length} cards in the portfolio`)}
            </p>
          </div>

          <section
            ref={cardsResultsRef}
            id="cards-results"
            tabIndex={-1}
            aria-label={t('Výsledky karet', 'Card results')}
          >
          {unavailable && <DataUnavailable kind={unavailable.kind} service={t('Card-issuance-service', 'Card-issuance-service')} feature={t('Karty', 'Cards')} lang={language} dense={cards.length > 0} />}
          {loading && cards.length === 0 ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} />
              <div>{t('Načítám karty…', 'Loading cards…')}</div>
            </div>
          ) : unavailable && cards.length === 0 ? null : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Karty', 'Cards')} lang={language}
              detail={cards.length === 0
                ? t('Služba běží, zatím nebyly vydány žádné karty.', 'The service is running; no cards have been issued yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid var(--border)' }}>
                    {[t('PAN', 'PAN'), t('Typ', 'Type'), t('Status', 'Status'), t('Platnost', 'Expiry'),
                      t('Držitel', 'Cardholder'), t('Vytvořeno', 'Created'), t('Akce', 'Actions'), ''].map((h, i) => (
                      <th key={`${h}-${i}`} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700,
                        color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {page.map(c => {
                    const sc = cardStatusColor(c.status)
                    const isHighlighted = c.id === highlighted
                    return (
                      <tr key={c.id}
                        tabIndex={0}
                        aria-label={t(`Detail karty ${c.maskedPan}`, `Card detail ${c.maskedPan}`)}
                        onClick={() => open(c.id)}
                        onFocus={() => setHighlighted(c.id)}
                        onMouseEnter={e => { setHighlighted(c.id); if (!isHighlighted) e.currentTarget.style.background = 'var(--surface-2)' }}
                        onMouseLeave={e => { e.currentTarget.style.background = '' }}
                        onKeyDown={e => {
                          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(c.id) }
                        }}
                        style={{
                          borderBottom: '1px solid var(--border)', cursor: 'pointer',
                          background: isHighlighted ? 'var(--accent-bg)' : undefined,
                          boxShadow: isHighlighted ? 'inset 3px 0 0 var(--accent)' : undefined,
                        }}>
                        <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--text-primary)' }}>
                          {/* A real anchor as well as a clickable row: middle-click,
                              open-in-new-tab and "copy link" are how an operator puts a
                              card into a ticket. */}
                          <Link href={`/cards/${c.id}`} onClick={e => e.stopPropagation()}
                            style={{ color: 'inherit', textDecoration: 'none' }}>{c.maskedPan}</Link>
                        </td>
                        <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.cardType}</td>
                        <td style={{ padding: '12px 16px' }}>
                          <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                            background: sc.bg, color: sc.text, border: `1px solid ${sc.border}` }}>{c.status}</span>
                        </td>
                        <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.expiryDate}</td>
                        <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.cardholderName || '—'}</td>
                        <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{c.createdAt ? new Date(c.createdAt).toLocaleDateString(dateLocale) : '—'}</td>
                        <td style={{ padding: '10px 16px' }} onClick={e => e.stopPropagation()}>
                          {(canManage || canBlock) && <CardTransitionButtons card={c} busy={ops.busy} canManage={canManage} canBlock={canBlock} onSelect={tr => onSelectTransition(c, tr)} />}
                        </td>
                        <td style={{ padding: '10px 12px', color: 'var(--text-tertiary)' }}><ChevronRight size={14} /></td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
              <div style={{ padding: '12px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', flexWrap: 'wrap' }}>
                <span style={{ fontSize: '11.5px', color: 'var(--text-tertiary)' }}>
                  {t(`Zobrazeno ${page.length} z ${filtered.length}`, `Showing ${page.length} of ${filtered.length}`)}
                </span>
                {page.length < filtered.length && (
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVisible(v => v + PAGE_SIZE)} aria-label={t('Načíst další karty', 'Load more cards')}>
                    {t('Načíst další', 'Load more')}
                  </button>
                )}
              </div>
            </>
          )}
          </section>
        </div>

        <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', marginTop: '14px', fontSize: '11.5px', color: 'var(--text-tertiary)' }}>
          <ShieldCheck size={13} style={{ flexShrink: 0, marginTop: '1px', color: 'var(--success)' }} />
          <span>{t(
            'Portál pracuje výhradně s maskovaným PAN; úplné číslo karty ani CVV zde nejsou dostupné (PCI DSS).',
            'The portal works with the masked PAN only; the full card number and CVV are not available here (PCI DSS).',
          )}</span>
        </div>
      </div>

      {pending && (
        <ConfirmTransitionDialog
          card={pending.card}
          transition={pending.transition}
          busy={ops.busy !== null}
          feedback={ops.feedback}
          closeFocusOverrideRef={closeFocusOverrideRef}
          onCancel={() => setPending(null)}
          onDismissFeedback={() => ops.setFeedback(null)}
          onConfirm={reason => void ops.runTransition(pending.card, pending.transition, reason).then(ok => {
            if (ok) {
              closeFocusOverrideRef.current = cardsResultsRef.current
              setPending(null)
            }
          })}
        />
      )}

      {issuing && (
        <IssueCardDialog
          onClose={() => setIssuing(false)}
          onIssued={card => { setIssuing(false); reload(); open(card.id) }}
        />
      )}
    </AuthGuard>
  )
}
