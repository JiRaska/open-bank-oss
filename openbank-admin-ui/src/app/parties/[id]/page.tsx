// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { Users, ArrowLeft, ShieldCheck, FileText, RefreshCw, Bell, ChevronDown, Send, Clock } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { hasPermission } from '@/lib/auth/roles'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { svcUrl, classifyBffFailure } from '@/lib/services/bff'
import { EntityChip } from '@/components/entities/EntityChip'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { opsMessageApi, OPERATOR_MESSAGE_TEMPLATE_VARS, type OperatorMessageTemplate, type ComposeMessageRequest } from '@/lib/api'

const PAGE_SIZE = 25

interface Party {
  id: string; partyType: string; status: string; legalName: string; tradingName?: string
  email: string; phone?: string; kycStatus: string; taxId?: string; registrationNumber?: string
  nationality?: string; dateOfBirth?: string; address?: { line1: string; city: string; postalCode: string; countryCode: string }
  createdAt: string; updatedAt: string
}

interface KycCase {
  id: string; status: string; checks: { checkType: string; status: string; result?: string }[]
  reviewedBy?: string; createdAt: string; updatedAt: string
}

// The list endpoint's NotificationSummary (notification-service openapi.yaml 1.5.0).
// Metadata only — `body` is deliberately absent here and is NOT fetched by this page.
interface NotificationSummary {
  id: string; partyId: string; channel: string; template: string; recipient: string
  subject?: string; status: string; sentAt?: string; readAt?: string; createdAt: string
}

function PartyDetailPage() {
  const { id } = useParams<{ id: string }>()
  const router = useRouter()
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { roles } = useAuth()
  const [party, setParty]     = useState<Party | null>(null)
  const [kyc, setKyc]         = useState<KycCase | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [tab, setTab] = useState<'overview' | 'messages'>('overview')

  const canSeeMessages = hasPermission(roles, 'notifications:view')

  const load = useCallback(async () => {
    setLoading(true); setUnavailable(null)
    try {
      const [partyRes, kycRes] = await Promise.allSettled([
        fetch(svcUrl('party-service', `/api/v1/parties/${id}`), { signal: AbortSignal.timeout(5000) }),
        fetch(svcUrl('kyc-service', `/api/v1/kyc/cases/party/${id}`), { signal: AbortSignal.timeout(5000) }),
      ])
      if (partyRes.status !== 'fulfilled') { setUnavailable({ kind: 'unreachable' }); return }
      if (!partyRes.value.ok) { setUnavailable({ kind: await classifyBffFailure(partyRes.value) }); return }
      setParty(await partyRes.value.json())
      // KYC is supplementary: a party with no case is normal, so a failure here degrades
      // that card rather than the page.
      if (kycRes.status === 'fulfilled' && kycRes.value.ok) setKyc(await kycRes.value.json())
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [id])

  useEffect(() => { load() }, [load])

  if (loading) return (
    <div>
      <PageHeader
        icon={<Users size={18} aria-hidden="true" />}
        title={<div className="skeleton" style={{ height: '24px', width: '200px' }} aria-label={t('Načítání detailu subjektu', 'Loading party detail')} />}
      />
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
        {[1,2].map(i => <div key={i} className="card" style={{ padding: '20px', height: '200px' }}><div className="skeleton" style={{ height: '100%' }} /></div>)}
      </div>
    </div>
  )

  if (unavailable) return (
    <div>
      <PageHeader
        icon={<Users size={18} aria-hidden="true" />}
        title={t('Detail subjektu', 'Party detail')}
        subtitle={t('Data subjektu nejsou v tomto prostředí dostupná.', 'Party data is unavailable in this environment.')}
        actions={<button className="btn btn-secondary" onClick={() => router.back()}><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět', 'Back')}</button>}
      />
      <DataUnavailable kind={unavailable.kind} service="Party-service" feature={t('Detail subjektu', 'Party detail')} lang={language} />
    </div>
  )

  if (!party) return null

  type TabId = 'overview' | 'messages'
  const tabs: Array<{ id: TabId; label: string; icon: React.ReactNode; show: boolean }> = [
    { id: 'overview' as TabId, label: t('Přehled', 'Overview'), icon: <Users size={12} />, show: true },
    { id: 'messages' as TabId, label: t('Zprávy', 'Messages'), icon: <Bell size={12} />, show: canSeeMessages },
  ].filter(item => item.show)

  return (
    <div>
      <PageHeader
        icon={<Users size={18} aria-hidden="true" />}
        title={party.legalName}
        subtitle={<span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{party.id}</span>}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/parties" style={{ color: 'var(--text-secondary)', textDecoration: 'none' }}>{t('Subjekty', 'Parties')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{party.legalName}</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px' }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit detail subjektu', 'Refresh party detail')}
          >
            <RefreshCw size={13} aria-hidden="true" /> {t('Obnovit', 'Refresh')}
          </button>
          <Link href="/parties" className="btn btn-secondary" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <ArrowLeft size={13} aria-hidden="true" /> {t('Zpět', 'Back')}
          </Link>
        </div>}
      />

      {/* Loop var is `item`, never `t` — a callback param named `t` shadows the translation
          function (see openbank-admin-ui/CLAUDE.md rule #4; sanctions/page.tsx does this). */}
      <div role="group" aria-label={t('Sekce detailu subjektu', 'Party detail sections')} style={{ display: 'flex', gap: '2px', marginBottom: '16px', flexWrap: 'wrap' }}>
        {tabs.map(item => (
          <button
            key={item.id}
            type="button"
            aria-pressed={tab === item.id}
            onClick={() => setTab(item.id)}
            style={{
              display: 'flex', alignItems: 'center', gap: '4px', padding: '5px 10px', fontSize: '11px',
              fontWeight: 600, borderRadius: '5px', border: 'none', cursor: 'pointer',
              background: tab === item.id ? 'var(--accent)' : 'transparent',
              color: tab === item.id ? '#fff' : 'var(--text-secondary)', transition: 'all 0.1s',
            }}
          >
            <span aria-hidden="true">{item.icon}</span>{item.label}
          </button>
        ))}
      </div>

      {tab === 'overview' && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
          {/* Party Details */}
          <div className="card" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
              <Users size={15} style={{ color: 'var(--accent)' }} />
              <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Detaily subjektu', 'Party Details')}</span>
              <span style={{ marginLeft: 'auto' }}><StatusBadge status={party.status} /></span>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {[
                [t('Typ', 'Type'),                        party.partyType],
                [t('Obchodní jméno', 'Legal Name'),       party.legalName],
                [t('Obchodní název', 'Trading Name'),     party.tradingName ?? '—'],
                [t('E-mail', 'Email'),                    party.email],
                [t('Telefon', 'Phone'),                   party.phone ?? '—'],
                [t('Daňové ID', 'Tax ID'),                party.taxId ?? '—'],
                [t('Reg. číslo', 'Reg. Number'),          party.registrationNumber ?? '—'],
                [t('Státní příslušnost', 'Nationality'),  party.nationality ?? '—'],
                [t('Datum narození', 'Date of Birth'),    party.dateOfBirth ?? '—'],
                [t('Vytvořeno', 'Created'),               new Date(party.createdAt).toLocaleString(dateLocale)],
                [t('Aktualizováno', 'Updated'),           new Date(party.updatedAt).toLocaleString(dateLocale)],
              ].map(([label, value]) => (
                <div key={label} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', borderBottom: '1px solid var(--border)', paddingBottom: '8px' }}>
                  <span style={{ color: 'var(--text-muted)' }}>{label}</span>
                  <span style={{ fontWeight: 500, textAlign: 'right', maxWidth: '200px', wordBreak: 'break-all' }}>{value}</span>
                </div>
              ))}
            </div>
          </div>

          {/* KYC Status */}
          <div className="card" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
              <ShieldCheck size={15} style={{ color: 'var(--accent)' }} />
              <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Stav KYC', 'KYC Status')}</span>
              <span style={{ marginLeft: 'auto' }}>
                <StatusBadge status={party.kycStatus} label={party.kycStatus?.replace('_', ' ')} />
              </span>
            </div>
            {kyc ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px' }}>
                  {t('ID případu:', 'Case ID:')} <span style={{ fontFamily: 'var(--font-mono)' }}>{kyc.id}</span>
                </div>
                {kyc.checks?.map(check => (
                  <div key={check.checkType} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
                    <span style={{ fontSize: '13px' }}>{check.checkType?.replace(/_/g, ' ') ?? check.checkType}</span>
                    <StatusBadge status={check.status} />
                  </div>
                ))}
                {kyc.reviewedBy && (
                  <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '8px' }}>
                    {t('Kontroloval:', 'Reviewed by:')} {kyc.reviewedBy}
                  </div>
                )}
              </div>
            ) : (
              <DataUnavailable kind="no_data" feature={t('Případ KYC', 'KYC case')} lang={language} dense />
            )}

            {/* Address */}
            {party.address && (
              <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                  <FileText size={13} style={{ color: 'var(--accent)' }} />
                  <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Adresa', 'Address')}</span>
                </div>
                <div style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: '1.6' }}>
                  {party.address.line1}<br />
                  {party.address.city}, {party.address.postalCode}<br />
                  {party.address.countryCode}
                </div>
              </div>
            )}
          </div>

          {/* Related entities (ADR-0231 D3) — the party → accounts walk is chips, not UUID copying. */}
          <RelatedAccounts key={party.id} partyId={party.id} />
        </div>
      )}

      {tab === 'messages' && <MessagesTab partyId={party.id} partyEmail={party.email} roles={roles} />}
    </div>
  )
}

type AccountRef = { id: string; accountNumber: string; currencyCode?: string; status?: string }

function RelatedAccounts({ partyId }: { partyId: string }) {
  const { t } = useLanguage()
  const [accounts, setAccounts] = useState<AccountRef[] | null>(null)
  const [unavailable, setUnavailable] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    const ctrl = new AbortController()
    fetch(svcUrl('account-service', '/api/v1/accounts', { partyId, limit: '20' }), {
      signal: ctrl.signal, cache: 'no-store',
    })
      .then(r => {
        if (!r.ok) throw new Error(`Related accounts HTTP ${r.status}`)
        return r.json()
      })
      .then(d => setAccounts(d?.data ?? []))
      .catch(error => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        setUnavailable(true)
      })
    return () => ctrl.abort()
  }, [partyId, reloadKey])

  const retry = () => {
    setAccounts(null)
    setUnavailable(false)
    setReloadKey(key => key + 1)
  }

  return (
    <div className="card" style={{ padding: '20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
        <Users size={15} style={{ color: 'var(--accent)' }} />
        <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Související účty', 'Related accounts')}</span>
      </div>
      {unavailable ? (
        <div role="status" aria-live="polite" style={{ fontSize: '12px', color: 'var(--warning-text)' }}>
          <div>{t('Související účty se nepodařilo načíst — tento stav neznamená, že subjekt nemá žádné účty.', 'Related accounts could not be loaded — this does not mean the party has no accounts.')}</div>
          <button type="button" onClick={retry} aria-label={t('Zkusit znovu načíst související účty', 'Retry loading related accounts')}
            style={{ marginTop: '10px', padding: '6px 12px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-primary)', cursor: 'pointer', fontSize: '12px', fontWeight: 600 }}>
            {t('Zkusit znovu', 'Try again')}
          </button>
        </div>
      ) : accounts === null ? (
        <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Načítám…', 'Loading…')}</div>
      ) : accounts.length === 0 ? (
        <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Žádné účty', 'No accounts')}</div>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
          {accounts.map(a => (
            <EntityChip
              key={a.id}
              type="account"
              id={a.id}
              label={a.accountNumber}
              sublabel={[a.currencyCode, a.status].filter(Boolean).join(' · ') || undefined}
            />
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * A party's notification history — metadata only.
 *
 * The rendered `body` is deliberately never fetched or shown. The list endpoint returns no
 * body, and this page does not call `GET /notifications/{id}` (which does). That keeps the
 * tab from putting message content one click from an operator while the read-side authz gap
 * is open (issue #1326): rest.rego's `operator-read-any` grants `.read`/`.list` on any
 * resource to every operator, and the BFF relays the operator's own bearer, so the
 * `notifications:view` permission gating this tab is UX only — it decides what we render,
 * not what an operator can fetch. Showing bodies here needs the policy fix first.
 */
function MessagesTab({ partyId, partyEmail, roles }: { partyId: string; partyEmail: string; roles: string[] }) {
  // A helper component outside the page needs its own language context (all admin-ui pages
  // are 'use client') — never reference the page's `t` out of scope (CLAUDE.md rule #4).
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [rows, setRows] = useState<NotificationSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const canCompose = hasPermission(roles, 'opsmessage:compose')
  const [sending, setSending] = useState(false)
  const [composeError, setComposeError] = useState<string | null>(null)
  const [template, setTemplate] = useState<OperatorMessageTemplate>('GENERIC_NOTICE')
  const [recipient, setRecipient] = useState(partyEmail)
  const [vars, setVars] = useState<Record<string, string>>({})
  // Set once compose returns 202 — nothing here polls automatically (ADR-0176 introduces the
  // fleet's first 202/X-Approval-Id UI flow; a real approval notification channel is a separate,
  // larger piece of work). We hold the EXACT request that was paused: the retry must resend the
  // byte-identical body (the interceptor binds the approval to the request's content), and the
  // maker relays `approvalId` to a colleague, who decides it on the Notifications page. The maker
  // then clicks "Retry send".
  const [pendingSubmit, setPendingSubmit] = useState<{ request: ComposeMessageRequest; approvalId: string } | null>(null)
  const [retrying, setRetrying] = useState(false)

  const load = useCallback(async (nextPage: number) => {
    if (nextPage === 0) setLoading(true); else setLoadingMore(true)
    setUnavailable(null)
    try {
      const res = await fetch(
        svcUrl('notification-service', '/api/v1/notifications', {
          partyId,
          page: String(nextPage),
          size: String(PAGE_SIZE),
        }),
        { signal: AbortSignal.timeout(5000) },
      )
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        // A bare 404 on the list endpoint means "nothing here", not a broken app.
        setUnavailable({ kind: kind === 'not_found' ? 'no_data' : kind })
        return
      }
      const data = await res.json()
      const items: NotificationSummary[] = data.items ?? []
      setRows(prev => nextPage === 0 ? items : [...prev, ...items])
      setTotal(data.total ?? items.length)
      setPage(nextPage)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false); setLoadingMore(false) }
  }, [partyId])

  useEffect(() => { load(0) }, [load])

  // The endpoint is offset-paged (page/size/total), not cursor-paged like party search.
  const hasNextPage = (page + 1) * PAGE_SIZE < total

  // Mirror the backend recipient guard (OperatorMessageService.EMAIL_PATTERN): reject blanks and
  // CR/LF header-injection before the BFF call, so a malformed address never leaves the browser.
  const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  const templateVars = OPERATOR_MESSAGE_TEMPLATE_VARS[template]

  // ADR-0176 D2: a single four-eyes-gated compose call (POST /notifications/messages). The
  // operator picks a closed-catalogue template, a recipient, and the template's exact variables;
  // no free-text template. When four-eyes enforcement is on the call pauses (202) until a
  // different operator decides it on the Notifications page.
  async function sendMessage() {
    const trimmedRecipient = recipient.trim()
    if (!EMAIL_PATTERN.test(trimmedRecipient)) {
      setComposeError(t('Zadejte platnou e-mailovou adresu příjemce.', 'Enter a valid recipient email address.'))
      return
    }
    // The request must carry EXACTLY the template's declared keys — extra or missing are both 400.
    const variables: Record<string, string> = {}
    for (const key of templateVars) variables[key] = (vars[key] ?? '').trim()
    if (templateVars.some(key => !variables[key])) {
      setComposeError(t('Vyplňte prosím všechna pole šablony.', 'Please fill in every template field.'))
      return
    }
    const request: ComposeMessageRequest = { partyId, template, recipient: trimmedRecipient, variables }
    setSending(true); setComposeError(null)
    try {
      const result = await opsMessageApi.compose(request)
      if (result.status === 'PENDING_APPROVAL') {
        setPendingSubmit({ request, approvalId: result.approvalId })
      } else {
        // four-eyes enforcement off in this environment — a real send, so refresh the history.
        setVars({})
        load(0)
      }
    } catch {
      // Never surface a raw backend message for a user-initiated write (CLAUDE.md rule).
      setComposeError(t(
        'Zprávu se nepodařilo odeslat. Zkuste to prosím znovu.',
        'The message could not be sent. Please try again.',
      ))
    } finally { setSending(false) }
  }

  async function retrySubmit() {
    if (!pendingSubmit) return
    setRetrying(true)
    try {
      // Byte-identical replay of the paused request, now carrying the approval id.
      const result = await opsMessageApi.compose(pendingSubmit.request, pendingSubmit.approvalId)
      if (result.status !== 'PENDING_APPROVAL') {
        setPendingSubmit(null)
        setVars({})
        load(0)
      }
      // Still PENDING_APPROVAL: nobody has decided it yet — leave the banner up.
    } catch {
      // A 409 (already resolved) or 403 (rejected/self-approval) most often means someone
      // already decided it — leave the banner up rather than guessing; the approvals panel
      // on the Notifications page has the authoritative status.
    } finally { setRetrying(false) }
  }

  function varLabel(key: string): string {
    switch (key) {
      case 'subject': return t('Předmět', 'Subject')
      case 'note': return t('Text zprávy', 'Message body')
      case 'ticketReference': return t('Číslo tiketu', 'Ticket reference')
      default: return key
    }
  }

  return (
    <>
      {canCompose && (
        <div className="card" style={{ padding: '14px 20px', marginBottom: '16px' }}>
          {pendingSubmit ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <Clock size={15} style={{ color: 'var(--yellow)' }} />
                <span style={{ fontSize: '13px' }}>
                  {t('Zpráva čeká na schválení druhým operátorem.', 'Message is awaiting a second operator’s approval.')}
                </span>
                <button type="button" className="btn btn-secondary" style={{ marginLeft: 'auto' }} onClick={retrySubmit} disabled={retrying} aria-busy={retrying} aria-label={t('Zkusit znovu odeslat zprávu', 'Retry sending message')}>
                  <RefreshCw size={13} aria-hidden="true" /> {retrying ? t('Zkouším…', 'Retrying…') : t('Zkusit znovu odeslat', 'Retry send')}
                </button>
              </div>
              {/* No backend endpoint lists pending approvals (ApprovalStore has no query), so the
                  checker cannot discover this from a queue — the maker relays the id out of band. */}
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                {t('Předejte toto ID schválení druhému operátorovi:', 'Give this approval id to a second operator:')}{' '}
                <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>{pendingSubmit.approvalId}</span>
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
                  {t('Šablona', 'Template')}
                  <select
                    className="input"
                    value={template}
                    onChange={e => { setTemplate(e.target.value as OperatorMessageTemplate); setVars({}); setComposeError(null) }}
                  >
                    <option value="GENERIC_NOTICE">{t('Obecné oznámení', 'Generic notice')}</option>
                    <option value="SUPPORT_FOLLOWUP">{t('Reakce na požadavek podpory', 'Support follow-up')}</option>
                  </select>
                </label>
                <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)', flex: 1, minWidth: '220px' }}>
                  {t('Příjemce (e-mail)', 'Recipient (email)')}
                  <input
                    className="input"
                    type="email"
                    value={recipient}
                    onChange={e => setRecipient(e.target.value)}
                    placeholder={t('jmeno@priklad.cz', 'name@example.com')}
                  />
                </label>
              </div>
              {templateVars.map(key => (
                <label key={key} style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
                  {varLabel(key)}
                  {key === 'note' ? (
                    <textarea
                      className="input"
                      rows={3}
                      value={vars[key] ?? ''}
                      onChange={e => setVars(prev => ({ ...prev, [key]: e.target.value }))}
                    />
                  ) : (
                    <input
                      className="input"
                      type="text"
                      value={vars[key] ?? ''}
                      onChange={e => setVars(prev => ({ ...prev, [key]: e.target.value }))}
                    />
                  )}
                </label>
              ))}
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <button type="button" className="btn btn-secondary" onClick={sendMessage} disabled={sending} aria-busy={sending} aria-label={t('Poslat zprávu', 'Send message')}>
                  <Send size={13} aria-hidden="true" /> {sending ? t('Odesílám…', 'Sending…') : t('Poslat zprávu', 'Send message')}
                </button>
                {composeError && <span style={{ fontSize: '12px', color: 'var(--red)' }}>{composeError}</span>}
              </div>
            </div>
          )}
        </div>
      )}
      <div className="card" style={{ overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '16px 20px', borderBottom: '1px solid var(--border)' }}>
        <Bell size={15} style={{ color: 'var(--accent)' }} />
        <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Historie zpráv', 'Message history')}</span>
        {!loading && !unavailable && (
          <span className="tag" style={{ marginLeft: 'auto' }}>{rows.length}/{total}</span>
        )}
      </div>

      {unavailable && unavailable.kind !== 'no_data' && (
        <DataUnavailable kind={unavailable.kind} service="Notification-service" feature={t('Historie zpráv', 'Message history')} lang={language} dense />
      )}

      {(!unavailable || unavailable.kind === 'no_data') && (
        <table className="data-table">
          <thead>
            <tr>
              <th>{t('Šablona', 'Template')}</th>
              <th>{t('Kanál', 'Channel')}</th>
              <th>{t('Příjemce', 'Recipient')}</th>
              <th>{t('Předmět', 'Subject')}</th>
              <th>{t('Stav', 'Status')}</th>
              <th>{t('Odesláno', 'Sent')}</th>
              <th>{t('Přečteno', 'Read')}</th>
            </tr>
          </thead>
          <tbody>
            {loading && Array.from({ length: 5 }).map((_, i) => (
              <tr key={i}>{Array.from({ length: 7 }).map((_, j) => (
                <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 2 ? '160px' : '80px' }} /></td>
              ))}</tr>
            ))}
            {!loading && rows.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 0 }}>
                <DataUnavailable
                  kind="no_data"
                  feature={t('Historie zpráv', 'Message history')}
                  lang={language}
                  detail={t('Tomuto subjektu nebyla odeslána žádná zpráva.', 'No message has been sent to this party.')}
                  dense
                />
              </td></tr>
            )}
            {!loading && rows.map(row => (
              <tr key={row.id}>
                <td><span className="tag">{row.template}</span></td>
                <td><span className="tag">{row.channel}</span></td>
                <td style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>{row.recipient}</td>
                <td style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>{row.subject ?? '—'}</td>
                <td>
                  <StatusBadge status={row.status} />
                </td>
                <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{row.sentAt ? new Date(row.sentAt).toLocaleString(dateLocale) : '—'}</td>
                <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{row.readAt ? new Date(row.readAt).toLocaleString(dateLocale) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {hasNextPage && !loadingMore && (
        <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border)' }}>
          <button type="button" className="btn btn-secondary" onClick={() => load(page + 1)} aria-label={t('Načíst další zprávy', 'Load more messages')}>
            <ChevronDown size={13} aria-hidden="true" /> {t('Načíst další', 'Load more')}
          </button>
        </div>
      )}
      {loadingMore && (
        <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border)', color: 'var(--text-muted)', fontSize: '13px' }}>
          {t('Načítám…', 'Loading…')}
        </div>
      )}
      </div>
    </>
  )
}

export default function PartyDetailPageGuarded() {
  return (
    <AuthGuard permission="parties:view">
      <PartyDetailPage />
    </AuthGuard>
  )
}
