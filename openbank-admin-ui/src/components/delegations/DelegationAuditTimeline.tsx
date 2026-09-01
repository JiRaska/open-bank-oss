// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Check, Circle, RefreshCw, ShieldCheck, UserRound } from 'lucide-react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { DelegationStatusBadge } from '@/components/delegations/GrantView'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { DelegationAuditTimelineEntry, DelegationAuditTimelineResponse } from '@/lib/delegations/auditTimeline'
import styles from './DelegationAuditTimeline.module.css'

type LoadFailure = 'forbidden' | 'unauthorized' | 'unavailable' | 'invalid'

const REASON_EVENTS = new Set(['DelegationRevoked', 'DelegationSuspended'])

function eventLabel(eventType: string, language: 'cs' | 'en'): string {
  const labels: Record<string, [string, string]> = {
    DelegationOffered: ['Delegace nabídnuta', 'Delegation offered'],
    DelegationActivated: ['Delegace přijata a aktivována', 'Delegation accepted and activated'],
    DelegationDeclined: ['Nabídka odmítnuta', 'Offer declined'],
    DelegationRevoked: ['Delegace odvolána', 'Delegation revoked'],
    DelegationSuspended: ['Delegace pozastavena', 'Delegation suspended'],
    DelegationReinstated: ['Delegace obnovena', 'Delegation reinstated'],
    DelegationRenounced: ['Příjemce se delegace vzdal', 'Delegation renounced by recipient'],
    DelegationExpired: ['Platnost delegace skončila', 'Delegation expired'],
  }
  return labels[eventType]?.[language === 'cs' ? 0 : 1] ?? eventType
}

function formatTime(value: string, locale: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })
}

function actorLabel(entry: DelegationAuditTimelineEntry, language: 'cs' | 'en'): string {
  if (entry.actorId) return entry.actorType ? `${entry.actorType}: ${entry.actorId}` : entry.actorId
  if (entry.actorType) {
    return language === 'cs' ? `${entry.actorType} (bez ID aktéra)` : `${entry.actorType} (actor ID absent)`
  }
  return language === 'cs' ? 'Aktér v události neuveden' : 'Actor not recorded in the event'
}

function timeSourceLabel(entry: DelegationAuditTimelineEntry, language: 'cs' | 'en'): string {
  if (entry.timeSource === 'event') return language === 'cs' ? 'čas od producenta' : 'producer event time'
  if (entry.timeSource === 'ingest') return language === 'cs' ? 'náhradní čas přijetí' : 'ingest time substitute'
  return language === 'cs' ? 'původ času neuveden' : 'time provenance unavailable'
}

function sourceLabel(entry: DelegationAuditTimelineEntry, language: 'cs' | 'en'): string {
  if (!entry.sourceService) return language === 'cs' ? 'producent neuveden' : 'producer not attributed'
  if (entry.sourceAttribution === 'event') {
    return language === 'cs' ? `${entry.sourceService} · uvedeno producentem` : `${entry.sourceService} · producer-declared`
  }
  if (entry.sourceAttribution === 'topic') {
    return language === 'cs' ? `${entry.sourceService} · odvozeno z topicu` : `${entry.sourceService} · inferred from topic`
  }
  return entry.sourceService
}

function TimelineSkeleton({ language }: { language: 'cs' | 'en' }) {
  return (
    <div className={styles.loading} role="status" aria-live="polite">
      <p className={styles.loadingLabel}>{language === 'cs' ? 'Načítám auditní evidenci…' : 'Loading audit evidence…'}</p>
      {[0, 1, 2].map(item => (
        <div className={styles.skeletonRow} key={item} aria-hidden="true">
          <div className={`skeleton ${styles.skeletonDot}`} />
          <div className={`skeleton ${styles.skeletonCard}`} />
        </div>
      ))}
    </div>
  )
}

function FailureState({ failure, retry, language }: { failure: LoadFailure; retry: () => void; language: 'cs' | 'en' }) {
  const forbidden = failure === 'forbidden'
  const unauthorized = failure === 'unauthorized'
  return (
    <DataUnavailable
      dense
      kind={unauthorized ? 'unauthorized' : failure === 'unavailable' ? 'unreachable' : 'error'}
      service="audit-service"
      feature={language === 'cs' ? 'auditní časová osa delegace' : 'delegation audit timeline'}
      lang={language}
      title={forbidden
        ? language === 'cs' ? 'Auditní evidence je vyhrazena dohledovým rolím' : 'Audit evidence is restricted to oversight roles'
        : undefined}
      detail={forbidden
        ? language === 'cs'
          ? 'Detail práv zůstává dostupný, ale neměnnou auditní stopu smí číst jen role s oprávněním k auditu. Toto není prázdná historie.'
          : 'The rights detail remains available, but only an audit-authorized role may read the immutable trail. This is not an empty history.'
        : failure === 'invalid'
          ? language === 'cs'
            ? 'Audit-service odpověděla, ale evidence neodpovídala tomuto grantu nebo neměla platný kontrakt. Nic z ní proto nezobrazujeme.'
            : 'Audit-service answered, but the evidence did not match this grant or its contract was invalid. None of it is displayed.'
          : undefined}
    >
      {!unauthorized && (
        <button type="button" className="btn btn-secondary btn-sm" onClick={retry}>
          <RefreshCw size={13} aria-hidden="true" />
          {language === 'cs' ? 'Zkusit znovu' : 'Try again'}
        </button>
      )}
    </DataUnavailable>
  )
}

function ProjectionComparison({ currentStatus, auditStatus, language }: { currentStatus: string; auditStatus: string | null; language: 'cs' | 'en' }) {
  const normalizedCurrent = currentStatus.toUpperCase()
  const state = auditStatus === null ? 'unknown' : auditStatus === normalizedCurrent ? 'match' : 'mismatch'
  return (
    <div className={styles.comparison} data-state={state} role={state === 'mismatch' ? 'status' : undefined}>
      {state === 'match' && <><Check size={14} aria-hidden="true" style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />
        {language === 'cs'
          ? <>Živý stav <strong>{normalizedCurrent}</strong> odpovídá poslednímu auditnímu přechodu.</>
          : <>Live status <strong>{normalizedCurrent}</strong> matches the latest audited transition.</>}
      </>}
      {state === 'mismatch' && (language === 'cs'
        ? <>Živý stav je <strong>{normalizedCurrent}</strong>, poslední auditní přechod vede na <strong>{auditStatus}</strong>. Může jít o zpoždění projekce nebo neúplnou historickou stopu; stav ověřte.</>
        : <>Live status is <strong>{normalizedCurrent}</strong>, while the latest audited transition leads to <strong>{auditStatus}</strong>. This may be projection lag or incomplete legacy history; verify the state.</>)}
      {state === 'unknown' && (language === 'cs'
        ? 'Auditní evidence neobsahuje srovnatelný stavový přechod. Živý stav z ní nelze potvrdit ani vyvrátit.'
        : 'The audit evidence has no comparable status transition. It can neither confirm nor contradict the live status.')}
    </div>
  )
}

function TimelineEvent({ entry, language, locale }: { entry: DelegationAuditTimelineEntry; language: 'cs' | 'en'; locale: string }) {
  const expectsReason = REASON_EVENTS.has(entry.eventType)
  return (
    <li className={styles.item}>
      <span className={styles.marker} aria-hidden="true"><Circle size={7} fill="currentColor" /></span>
      <article className={styles.eventCard} aria-labelledby={`audit-event-${entry.evidenceId}`}>
        <div className={styles.eventHeader}>
          <div>
            <h3 className={styles.eventTitle} id={`audit-event-${entry.evidenceId}`}>{eventLabel(entry.eventType, language)}</h3>
            <div className={styles.eventType}>{entry.eventType}</div>
          </div>
          <time className={styles.time} dateTime={entry.occurredAt}>{formatTime(entry.occurredAt, locale)}</time>
        </div>

        <div className={styles.facts}>
          {entry.statusAfter && <span className={styles.statusFact}>
            {language === 'cs' ? 'Stav po události' : 'Status after event'}
            <DelegationStatusBadge status={entry.statusAfter} />
          </span>}
          <span className={styles.fact}><UserRound size={11} aria-hidden="true" style={{ verticalAlign: 'text-bottom', marginRight: 4 }} />{actorLabel(entry, language)}</span>
        </div>

        {entry.reason && (
          <p className={styles.reason}>
            <strong>{language === 'cs' ? 'Důvod:' : 'Reason:'}</strong> {entry.reason}
            {entry.reasonTruncated && <> {language === 'cs' ? '(zkráceno; úplné znění zůstává v audit-service)' : '(truncated; full text remains in audit-service)'}</>}
          </p>
        )}
        {expectsReason && entry.reasonState !== 'recorded' && (
          <p className={`${styles.reason} ${styles.warningText}`}>
            {entry.reasonState === 'unreadable'
              ? language === 'cs' ? 'Důvod v auditním payloadu nelze bezpečně přečíst.' : 'The reason in the audit payload could not be read safely.'
              : language === 'cs' ? 'Důvod nebyl v auditní události zaznamenán.' : 'No reason was recorded in the audit event.'}
          </p>
        )}

        <details className={styles.evidence}>
          <summary>{language === 'cs' ? 'Důkazní detaily' : 'Evidence details'}</summary>
          <dl className={styles.evidenceGrid}>
            <dt>{language === 'cs' ? 'ID auditního záznamu' : 'Audit entry ID'}</dt><dd>{entry.evidenceId}</dd>
            <dt>{language === 'cs' ? 'Zdroj' : 'Source'}</dt><dd>{sourceLabel(entry, language)}</dd>
            <dt>{language === 'cs' ? 'Původ času' : 'Time provenance'}</dt><dd>{timeSourceLabel(entry, language)}</dd>
            <dt>{language === 'cs' ? 'Zapsáno' : 'Recorded at'}</dt><dd>{entry.recordedAt ? formatTime(entry.recordedAt, locale) : language === 'cs' ? 'neuvedeno' : 'not exposed'}</dd>
            <dt>{language === 'cs' ? 'Korelace' : 'Correlation'}</dt><dd>{entry.correlationId ?? (language === 'cs' ? 'neuvedena' : 'not recorded')}</dd>
          </dl>
        </details>
      </article>
    </li>
  )
}

export function DelegationAuditTimeline({ grantId, currentStatus }: { grantId: string; currentStatus: string }) {
  const { language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [result, setResult] = useState<DelegationAuditTimelineResponse | null>(null)
  const [failure, setFailure] = useState<LoadFailure | null>(null)
  const [loading, setLoading] = useState(true)
  const requestGeneration = useRef(0)

  const load = useCallback(async () => {
    const generation = ++requestGeneration.current
    setLoading(true)
    setFailure(null)
    setResult(previous => previous?.grantId === grantId ? previous : null)
    try {
      const response = await fetch(`/api/delegations/${grantId}/audit`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(8_000),
      })
      if (generation !== requestGeneration.current) return
      if (!response.ok) {
        let errorCode = ''
        try {
          const errorBody = await response.clone().json() as { error?: unknown }
          if (typeof errorBody.error === 'string') errorCode = errorBody.error
        } catch {
          // A non-JSON failure is still a failure; status mapping below remains fail-closed.
        }
        if (generation !== requestGeneration.current) return
        const nextFailure: LoadFailure = errorCode === 'invalid_upstream_response'
          ? 'invalid'
          : response.status === 403
          ? 'forbidden'
          : response.status === 401 ? 'unauthorized' : response.status === 502 ? 'unavailable' : 'invalid'
        if (nextFailure === 'forbidden' || nextFailure === 'unauthorized') setResult(null)
        setFailure(nextFailure)
        return
      }
      const body = await response.json() as DelegationAuditTimelineResponse
      if (body.grantId !== grantId || !Array.isArray(body.entries)) {
        setResult(null)
        setFailure('invalid')
        return
      }
      setResult(body)
    } catch {
      if (generation === requestGeneration.current) setFailure('unavailable')
    } finally {
      if (generation === requestGeneration.current) setLoading(false)
    }
  }, [grantId])

  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => { if (!cancelled) void load() })
    return () => {
      cancelled = true
      requestGeneration.current += 1
    }
  }, [load])

  const hasEntries = Boolean(result?.entries.length)

  return (
    <section className={`card ${styles.card}`} aria-labelledby="delegation-audit-title">
      <header className={styles.header}>
        <div>
          <div className={styles.titleRow}>
            <ShieldCheck size={16} color="var(--accent)" aria-hidden="true" />
            <h2 id="delegation-audit-title" style={{ margin: 0, fontSize: 15, fontWeight: 750 }}>
              {language === 'cs' ? 'Neměnná auditní časová osa' : 'Immutable audit timeline'}
            </h2>
          </div>
          <p className={styles.subtitle}>
            {language === 'cs'
              ? 'Události z append-only audit-service. Shoda níže porovnává auditní přechod se živým stavem; sama neprovádí kontrolu celé hash chain.'
              : 'Events from append-only audit-service. The comparison below checks the audited transition against live state; it does not itself verify the full hash chain.'}
          </p>
        </div>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => void load()}
          disabled={loading}
          aria-busy={loading}
          aria-label={language === 'cs' ? 'Obnovit auditní časovou osu' : 'Refresh audit timeline'}
        >
          <RefreshCw size={13} aria-hidden="true" className={loading ? 'animate-spin' : ''} />
          {language === 'cs' ? 'Obnovit' : 'Refresh'}
        </button>
      </header>

      {loading && !result && <TimelineSkeleton language={language} />}

      {failure && !result && <FailureState failure={failure} retry={() => void load()} language={language} />}

      {result && result.entries.length === 0 && !loading && !failure && (
        <DataUnavailable
          dense
          kind="no_data"
          feature={language === 'cs' ? 'auditní historie delegace' : 'delegation audit history'}
          lang={language}
          detail={language === 'cs'
            ? 'Audit-service odpověděla úspěšně, ale pro tento grant zatím nemá žádnou událost. Neznamená to, že delegace nikdy nezměnila stav — starší události mohly vzniknout před zapojením topicu do auditu.'
            : 'Audit-service answered successfully but has no event for this grant yet. This does not prove the grant never changed state — older events may predate the topic’s audit subscription.'}
        />
      )}

      {result && hasEntries && (
        <>
          <ProjectionComparison currentStatus={currentStatus} auditStatus={result.latestStatusAfter} language={language} />
          {failure && (
            <div className={styles.retainedWarning} role="status" aria-live="polite">
              {language === 'cs'
                ? 'Obnovení se nezdařilo. Zobrazen je poslední úspěšně načtený snapshot; novější události mohou chybět.'
                : 'Refresh failed. The last successfully loaded snapshot remains visible; newer events may be missing.'}
            </div>
          )}
          {result.mayBeTruncated && (
            <div className={styles.truncatedWarning}>
              {language === 'cs'
                ? 'Zobrazeno je nejvýše 100 nejnovějších událostí; starší evidence může existovat v audit-service.'
                : 'At most the 100 newest events are shown; older evidence may remain in audit-service.'}
            </div>
          )}
          {loading && <p className={styles.retainedWarning} role="status" aria-live="polite">{language === 'cs' ? 'Ověřuji novější události…' : 'Checking for newer events…'}</p>}
          <ol className={styles.timeline} aria-label={language === 'cs' ? 'Události delegace od nejnovější' : 'Delegation events, newest first'}>
            {result.entries.map(entry => <TimelineEvent key={entry.evidenceId} entry={entry} language={language} locale={locale} />)}
          </ol>
        </>
      )}

      {!loading && !failure && !result && (
        <DataUnavailable dense kind="error" feature="audit timeline" lang={language} />
      )}
    </section>
  )
}
