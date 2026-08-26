// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import Link from 'next/link'
import { useCallback, useEffect, useState } from 'react'
import { useSession } from 'next-auth/react'
import { AlertTriangle, CheckCircle2, Fingerprint, RefreshCw, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { hasRole, ROLES } from '@/lib/auth/roles'
import { classifyBffFailure, svcUrl, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'

interface ClosedPeriod {
  id: string
  period: string
  periodType: 'MONTH' | 'QUARTER' | 'YEAR'
  from: string
  to: string
  status: 'DRAFT' | 'FROZEN'
  evidenceState: 'NONE' | 'HASH_ONLY' | 'LINES_V1'
  computedAt: string
  accountCount: number
  contentHash: string
  draftedBy: string | null
  frozenBy: string | null
  frozenAt: string | null
}

interface Verification {
  period: string
  status: 'DRAFT' | 'FROZEN'
  matches: boolean
  balanced: boolean
  recomputedAt: string
}

type Notice = { ok: boolean; text: string } | null

function lastCompletedMonthEnd(): string {
  const now = new Date()
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0)).toISOString().slice(0, 10)
}

function endpoint(date: string, suffix = ''): string {
  return svcUrl('ledger-service', `/api/v1/ledger/periods/MONTH/${date}${suffix}`)
}

export function RegulatoryPeriodPanel() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = session?.user?.roles ?? []
  // Unlike the general closings permission, ClosedPeriodResource accepts ROLE_OPERATOR only.
  // Use the exact backend role instead of inventing a permission that would misleadingly
  // inherit the console's usual ADMIN super-set semantics.
  const canManage = hasRole(roles, ROLES.OPERATOR)
  // ClosedPeriodResource persists JsonWebToken.subject; Auth.js projects that same `sub` to
  // session.user.id. Email/name are retained as compatibility fallbacks for non-JWT test/dev IdPs.
  const currentPrincipals = [session?.user?.id, session?.user?.email, session?.user?.name].filter(Boolean)
  const [date, setDate] = useState(lastCompletedMonthEnd)
  const [period, setPeriod] = useState<ClosedPeriod | null>(null)
  const [verification, setVerification] = useState<Verification | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<BffFailure | null>(null)
  const [acting, setActing] = useState<'draft' | 'verify' | 'freeze' | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [notice, setNotice] = useState<Notice>(null)

  const isOwnDraft = !!period?.draftedBy && currentPrincipals.some(principal => principal === period.draftedBy)
  const reportReady = period?.status === 'FROZEN' && period.evidenceState === 'LINES_V1'
  const freezeReady = period?.status === 'DRAFT' && verification?.matches === true &&
    verification.balanced === true && confirmed && !isOwnDraft

  const load = useCallback(async () => {
    setLoading(true); setNotice(null); setVerification(null); setConfirmed(false); setUnavailable(null)
    try {
      const response = await fetch(endpoint(date), { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
      if (!response.ok) {
        const kind = await classifyBffFailure(response)
        if (kind === 'not_found') { setPeriod(null); return }
        setPeriod(null); setUnavailable(kind); return
      }
      setPeriod(await response.json() as ClosedPeriod)
    } catch {
      setPeriod(null)
      setUnavailable('unreachable')
    } finally {
      setLoading(false)
    }
  }, [date])

  // Defer the initial fetch one task: this is an external synchronisation, and avoids a
  // synchronous state cascade inside the effect while preserving cancellation on unmount.
  useEffect(() => {
    const timer = setTimeout(() => void load(), 0)
    return () => clearTimeout(timer)
  }, [load])

  const mutate = useCallback(async (action: 'draft' | 'freeze') => {
    setActing(action); setNotice(null)
    try {
      const response = await fetch(endpoint(date, action === 'freeze' ? '/freeze' : ''), {
        method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(20_000),
      })
      if (!response.ok) {
        setNotice({
          ok: false,
          text: action === 'freeze'
            ? t('Zmrazení bylo odmítnuto. Checker musí být jiný než maker a otisk musí stále souhlasit.', 'Freeze was refused. The checker must differ from the maker and the evidence hash must still match.')
            : t('Draft nelze vytvořit. Období musí být ukončené a předvaha vyvážená.', 'The draft cannot be created. The period must have ended and the trial balance must be balanced.'),
        })
        return
      }
      const updated = await response.json() as ClosedPeriod
      setPeriod(updated); setVerification(null); setConfirmed(false)
      setNotice({
        ok: true,
        text: action === 'freeze'
          ? t('Období je zmrazené jako neměnný regulatorní důkaz.', 'The period is frozen as immutable regulatory evidence.')
          : t('Draft vytvořen. Zmrazení musí provést jiný operátor po nezávislém ověření.', 'Draft created. A different operator must independently verify and freeze it.'),
      })
    } catch {
      setNotice({ ok: false, text: t('Ledger-service neodpověděl.', 'Ledger-service did not respond.') })
    } finally {
      setActing(null)
    }
  }, [date, t])

  const verify = useCallback(async () => {
    setActing('verify'); setNotice(null); setConfirmed(false)
    try {
      const response = await fetch(endpoint(date, '/verify'), { cache: 'no-store', signal: AbortSignal.timeout(15_000) })
      if (!response.ok) throw new Error(String(response.status))
      const result = await response.json() as Verification
      setVerification(result)
      setNotice({
        ok: result.matches && result.balanced,
        text: result.matches && result.balanced
          ? t('Otisk souhlasí a předvaha je vyvážená.', 'The evidence hash matches and the trial balance is balanced.')
          : t('Ověření selhalo — období nesmí být zmrazeno ani použito pro reporting.', 'Verification failed — the period must not be frozen or used for reporting.'),
      })
    } catch {
      setVerification(null)
      setNotice({ ok: false, text: t('Ověření období se nezdařilo.', 'Period verification failed.') })
    } finally {
      setActing(null)
    }
  }, [date, t])

  return (
    <section aria-labelledby="regulatory-period-title">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: '12px', flexWrap: 'wrap', marginBottom: '16px' }}>
        <div>
          <h2 id="regulatory-period-title" style={{ fontSize: '16px', fontWeight: 700 }}>{t('Neměnný podklad FINREP/COREP', 'Immutable FINREP/COREP evidence')}</h2>
          <p style={{ marginTop: '4px', fontSize: '12px', color: 'var(--text-secondary)', maxWidth: '720px' }}>
            {t('Maker vytvoří draft z ukončeného měsíce. Jiný operátor ověří hash a teprve potom období zmrazí. Tato obrazovka nezobrazuje účty ani finanční hodnoty.', 'A maker creates a draft for a completed month. A different operator verifies its hash before freezing it. This screen exposes neither accounts nor monetary values.')}
          </p>
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <label style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t('Měsíc', 'Month')}{' '}
            <input aria-label={t('Datum v měsíci', 'Date within month')} type="date" value={date} max={lastCompletedMonthEnd()} onChange={event => setDate(event.target.value)} className="input" style={{ width: '150px' }} />
          </label>
          <button className="btn btn-secondary" onClick={() => void load()} disabled={loading || !date}>
            <RefreshCw size={13} className={loading ? 'animate-spin' : undefined} /> {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>

      {notice && <div role="status" style={{ padding: '10px 14px', marginBottom: '12px', borderRadius: 'var(--r-md)', background: notice.ok ? 'var(--success-bg)' : 'var(--warning-bg)', color: notice.ok ? 'var(--success)' : 'var(--warning)', border: `1px solid ${notice.ok ? 'var(--success-border)' : 'var(--warning-border)'}` }}>{notice.text}</div>}

      {loading ? <div className="skeleton" style={{ height: '180px' }} /> : unavailable ? (
        <div className="card"><DataUnavailable kind={unavailable} service="Ledger-service" feature={t('regulatorní období', 'regulatory period')} lang={language} /></div>
      ) : !period ? (
        <div className="card" style={{ padding: '24px', textAlign: 'center' }}>
          <AlertTriangle size={22} style={{ color: 'var(--warning)', marginBottom: '8px' }} />
          <div style={{ fontWeight: 700 }}>{t('Pro tento měsíc neexistuje regulatorní uzávěrka', 'No regulatory close exists for this month')}</div>
          <p style={{ margin: '6px auto 16px', maxWidth: '560px', fontSize: '12px', color: 'var(--text-secondary)' }}>{t('FINREP/COREP náhled zůstane zablokovaný, dokud maker nevytvoří DRAFT a nezávislý checker jej nezmrazí.', 'FINREP/COREP preview remains blocked until a maker creates a DRAFT and an independent checker freezes it.')}</p>
          {canManage && <button className="btn btn-primary" onClick={() => void mutate('draft')} disabled={acting !== null}><ShieldCheck size={13} /> {acting === 'draft' ? t('Vytvářím…', 'Creating…') : t('Vytvořit DRAFT (maker)', 'Create DRAFT (maker)')}</button>}
        </div>
      ) : (
        <div className="card" style={{ padding: '18px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
            <div>
              <span className={reportReady ? 'pill pill-success' : 'pill'}>{period.status}</span>{' '}
              <span className={period.evidenceState === 'LINES_V1' ? 'pill pill-success' : 'pill pill-danger'}>{period.evidenceState}</span>
              <h3 style={{ marginTop: '10px', fontSize: '15px' }}>{period.period} · {period.from} – {period.to}</h3>
            </div>
            {reportReady && <Link className="btn btn-primary" href="/regulatory">{t('Otevřít FINREP/COREP náhled', 'Open FINREP/COREP preview')}</Link>}
          </div>
          <dl style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: '12px', marginTop: '18px' }}>
            <div><dt style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Maker', 'Maker')}</dt><dd style={{ fontSize: '13px', fontWeight: 600 }}>{period.draftedBy ?? '—'}</dd></div>
            <div><dt style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Checker', 'Checker')}</dt><dd style={{ fontSize: '13px', fontWeight: 600 }}>{period.frozenBy ?? '—'}</dd></div>
            <div><dt style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Počet GL řádků', 'GL line count')}</dt><dd style={{ fontSize: '13px', fontWeight: 600 }}>{period.accountCount}</dd></div>
            <div><dt style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Otisk důkazu', 'Evidence fingerprint')}</dt><dd title={period.contentHash} style={{ fontSize: '12px', fontFamily: 'JetBrains Mono, monospace' }}><Fingerprint size={12} style={{ display: 'inline', marginRight: '5px' }} />{period.contentHash.slice(0, 16)}…</dd></div>
          </dl>

          {period.status === 'DRAFT' && canManage && (
            <div style={{ marginTop: '18px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                <button className="btn btn-secondary" onClick={() => void mutate('draft')} disabled={acting !== null}>{t('Obnovit DRAFT (maker)', 'Refresh DRAFT (maker)')}</button>
                <button className="btn btn-secondary" onClick={() => void verify()} disabled={acting !== null}><CheckCircle2 size={13} /> {acting === 'verify' ? t('Ověřuji…', 'Verifying…') : t('Nezávisle ověřit', 'Verify independently')}</button>
              </div>
              {isOwnDraft && <p style={{ marginTop: '10px', fontSize: '12px', color: 'var(--warning)' }}>{t('Jste maker tohoto draftu. Zmrazení musí provést jiný operátor.', 'You are this draft’s maker. A different operator must freeze it.')}</p>}
              {verification?.matches && verification.balanced && !isOwnDraft && (
                <label style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', marginTop: '12px', fontSize: '12px' }}>
                  <input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />
                  {t('Potvrzuji nezávislou kontrolu období a chápu, že zmrazení vytvoří neměnný regulatorní důkaz.', 'I confirm an independent review and understand that freezing creates immutable regulatory evidence.')}
                </label>
              )}
              <button className="btn btn-primary" style={{ marginTop: '12px' }} onClick={() => void mutate('freeze')} disabled={!freezeReady || acting !== null}><ShieldCheck size={13} /> {acting === 'freeze' ? t('Zmrazuji…', 'Freezing…') : t('Zmrazit období (checker)', 'Freeze period (checker)')}</button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
