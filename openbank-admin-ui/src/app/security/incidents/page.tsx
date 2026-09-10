// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, CheckCircle2, RefreshCw, Search, ShieldAlert } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatCard } from '@/components/ui/StatCard'
import { StatusBadge } from '@/components/ui/StatusBadge'
import type { Tone } from '@/components/ui/tone'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  INCIDENT_SEVERITIES, INCIDENT_STATUSES, parseIncidentList,
  type IctIncident, type IncidentSeverity, type IncidentStatus,
} from '@/lib/security/incidentEvidence'

type Failure = 'unauthorized' | 'not_deployed' | 'unreachable' | 'invalid_response' | 'result_limit' | 'error'
type Envelope = { available: true; incidents: unknown } | { available: false; reason: Failure }
const ALL = '__ALL__'
const ACTIVE_STATUSES: readonly IncidentStatus[] = ['OPEN', 'INVESTIGATING', 'CONTAINED']
const SEVERITY_TONE: Record<IncidentSeverity, Tone> = {
  P1_CRITICAL: 'danger', P2_HIGH: 'danger', P3_MEDIUM: 'warning', P4_LOW: 'info',
}

export default function IncidentsPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [incidents, setIncidents] = useState<IctIncident[]>([])
  const [failure, setFailure] = useState<Failure | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadedOnce, setLoadedOnce] = useState(false)
  const [lastVerifiedAt, setLastVerifiedAt] = useState<Date | null>(null)
  const [query, setQuery] = useState('')
  const [severity, setSeverity] = useState(ALL)
  const [status, setStatus] = useState(ALL)
  const requestId = useRef(0)

  const load = useCallback(async () => {
    const current = ++requestId.current
    setLoading(true)
    try {
      const response = await fetch('/api/security/incidents', {
        cache: 'no-store',
        signal: AbortSignal.timeout(10_000),
      })
      const envelope = await response.json() as Envelope
      if (current !== requestId.current) return
      if (!response.ok || !envelope || envelope.available !== true) {
        const reason = envelope?.available === false ? envelope.reason : 'error'
        if (reason === 'unauthorized') {
          setIncidents([])
          setLoadedOnce(false)
          setLastVerifiedAt(null)
        }
        setFailure(reason)
        return
      }
      const verified = parseIncidentList(envelope.incidents)
      if (!verified) { setFailure('invalid_response'); return }
      setIncidents(verified)
      setFailure(null)
      setLastVerifiedAt(new Date())
      setLoadedOnce(true)
    } catch {
      if (current === requestId.current) setFailure('unreachable')
    } finally {
      if (current === requestId.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0)
    return () => { window.clearTimeout(timer); requestId.current += 1 }
  }, [load])

  const active = useMemo(() => incidents.filter(item => ACTIVE_STATUSES.includes(item.status)), [incidents])
  const urgent = useMemo(() => active.filter(item => item.severity === 'P1_CRITICAL' || item.severity === 'P2_HIGH'), [active])
  const unreported = useMemo(() => active.filter(item => !item.reportedToRegulator), [active])
  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase(language)
    return incidents.filter(item => {
      if (severity !== ALL && item.severity !== severity) return false
      if (status !== ALL && item.status !== status) return false
      if (!needle) return true
      return [item.title, item.description, item.category, item.assignedTo ?? '', ...item.affectedServices]
        .some(value => value.toLocaleLowerCase(language).includes(needle))
    })
  }, [incidents, language, query, severity, status])
  const hasFilters = query.trim() !== '' || severity !== ALL || status !== ALL
  const clearFilters = () => { setQuery(''); setSeverity(ALL); setStatus(ALL) }

  const severityLabel = (value: IncidentSeverity) => ({
    P1_CRITICAL: t('P1 · kritická', 'P1 · Critical'), P2_HIGH: t('P2 · vysoká', 'P2 · High'),
    P3_MEDIUM: t('P3 · střední', 'P3 · Medium'), P4_LOW: t('P4 · nízká', 'P4 · Low'),
  }[value])
  const statusLabel = (value: IncidentStatus) => ({
    OPEN: t('Otevřený', 'Open'), INVESTIGATING: t('Vyšetřování', 'Investigating'),
    CONTAINED: t('Omezený', 'Contained'), RESOLVED: t('Vyřešený', 'Resolved'), CLOSED: t('Uzavřený', 'Closed'),
  }[value])
  const failureDetail = failure === 'unauthorized'
    ? t('Vaše role nemá oprávnění zobrazit registr. Požádejte správce o přístup system:view.', 'Your role cannot view this register. Ask an administrator for system:view access.')
    : failure === 'not_deployed'
      ? t('Zdroj incidentů není nasazen. To není důkaz, že incidenty neexistují.', 'The incident source is not deployed. This is not evidence that no incidents exist.')
      : failure === 'invalid_response'
        ? t('Zdroj vrátil data, která nelze ověřit jako platný DORA registr.', 'The source returned data that cannot be verified as a valid DORA register.')
        : failure === 'result_limit'
          ? t('Registr překročil bezpečný limit 500 incidentů. Pro úplnou evidenci použijte užší filtr.', 'The register exceeds the safe 500-incident limit. Use a narrower filter for complete evidence.')
        : t('Zdroj incidentů momentálně neodpovídá. Zobrazená data mohou být starší.', 'The incident source is not responding. Displayed data may be older.')

  return <AuthGuard permission="system:view"><div style={{ padding: '28px 32px', maxWidth: 1400 }}>
    <PageHeader icon={<ShieldAlert size={20} aria-hidden="true" />} title={t('ICT incidenty', 'ICT incidents')}
      subtitle={t('DORA registr · od detekce po regulatorní důkaz', 'DORA register · from detection to regulatory evidence')}
      actions={<button type="button" onClick={() => void load()} disabled={loading} aria-busy={loading} aria-label={t('Obnovit ICT incidenty', 'Refresh ICT incidents')} className="btn btn-secondary btn-sm"><RefreshCw aria-hidden="true" size={13} className={loading ? 'animate-spin' : undefined} /> {t('Obnovit', 'Refresh')}</button>} />

    <div className="grid-4" style={{ marginBottom: 20 }}>
      <StatCard label={t('Celkem v registru', 'Total in register')} value={incidents.length} icon={<ShieldAlert size={16} aria-hidden="true" />} />
      <StatCard label={t('Aktivní', 'Active')} value={active.length} tone={active.length ? 'warning' : 'success'} icon={<AlertTriangle size={16} aria-hidden="true" />} />
      <StatCard label={t('P1 + P2 aktivní', 'Active P1 + P2')} value={urgent.length} tone={urgent.length ? 'danger' : 'success'} hint={t('vyžaduje prioritní triáž', 'requires priority triage')} />
      <StatCard label={t('Bez regulatorního ID', 'Without regulator ID')} value={unreported.length} tone={unreported.length ? 'warning' : 'success'} icon={<CheckCircle2 size={16} aria-hidden="true" />} />
    </div>

    {failure && <div className="card" role="alert" style={{ padding: '14px 16px', marginBottom: 16, borderLeft: '4px solid var(--warning)' }}>
      <strong>{t('Registr se nepodařilo ověřit', 'The register could not be verified')}</strong>
      <p style={{ margin: '4px 0 0', color: 'var(--text-secondary)', fontSize: 13 }}>{failureDetail}</p>
      {loadedOnce && <p style={{ margin: '4px 0 0', color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Ponechávám poslední úspěšně ověřený snapshot.', 'Showing the last successfully verified snapshot.')}</p>}
    </div>}

    {!loadedOnce && loading ? <p role="status" aria-live="polite">{t('Načítám registr ICT incidentů…', 'Loading the ICT incident register…')}</p>
      : !loadedOnce && failure ? <DataUnavailable kind="error" feature={t('DORA registr incidentů', 'DORA incident register')} lang={language} />
        : <section className="card" aria-labelledby="incident-register-title">
          <div style={{ padding: 16, borderBottom: '1px solid var(--border)', display: 'grid', gap: 10 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', alignItems: 'baseline' }}>
              <div><h2 id="incident-register-title" style={{ margin: 0, fontSize: 16 }}>{t('Fronta incidentů', 'Incident queue')}</h2>
                <p style={{ margin: '3px 0 0', fontSize: 12, color: 'var(--text-tertiary)' }}>{lastVerifiedAt && t(`Ověřeno ${lastVerifiedAt.toLocaleTimeString(dateLocale)}`, `Verified ${lastVerifiedAt.toLocaleTimeString(dateLocale)}`)}</p></div>
              <p role="status" aria-live="polite" style={{ margin: 0, fontSize: 12, color: 'var(--text-secondary)' }}>{t(`${filtered.length} z ${incidents.length} incidentů`, `${filtered.length} of ${incidents.length} incidents`)}</p>
            </div>
            <div className="incident-filters" style={{ display: 'grid', gridTemplateColumns: 'minmax(220px, 1fr) auto auto auto', gap: 8 }}>
              <label style={{ position: 'relative' }}><span className="sr-only">{t('Hledat incidenty', 'Search incidents')}</span><Search size={14} aria-hidden="true" style={{ position: 'absolute', left: 11, top: 13, color: 'var(--text-tertiary)' }} /><input className="input" value={query} onChange={event => setQuery(event.target.value)} placeholder={t('Název, služba, kategorie, vlastník…', 'Title, service, category, owner…')} style={{ width: '100%', paddingLeft: 32 }} /></label>
              <select className="input" aria-label={t('Filtrovat podle závažnosti', 'Filter by severity')} value={severity} onChange={event => setSeverity(event.target.value)}><option value={ALL}>{t('Všechny závažnosti', 'All severities')}</option>{INCIDENT_SEVERITIES.map(value => <option key={value} value={value}>{severityLabel(value)}</option>)}</select>
              <select className="input" aria-label={t('Filtrovat podle stavu', 'Filter by status')} value={status} onChange={event => setStatus(event.target.value)}><option value={ALL}>{t('Všechny stavy', 'All statuses')}</option>{INCIDENT_STATUSES.map(value => <option key={value} value={value}>{statusLabel(value)}</option>)}</select>
              {hasFilters && <button type="button" className="btn btn-ghost" onClick={clearFilters}>{t('Vyčistit', 'Clear')}</button>}
            </div>
          </div>

          {incidents.length === 0 ? <DataUnavailable kind="no_data" feature={t('ICT incidenty', 'ICT incidents')} lang={language} detail={t('Registr byl ověřen a neobsahuje žádný incident.', 'The register was verified and contains no incidents.')} />
            : filtered.length === 0 ? <div style={{ padding: 28 }}><DataUnavailable kind="no_data" feature={t('Výsledky filtru', 'Filtered results')} lang={language} detail={t('Žádný incident neodpovídá zvoleným filtrům.', 'No incident matches the selected filters.')} /><button type="button" className="btn btn-secondary" onClick={clearFilters}>{t('Zobrazit celý registr', 'Show full register')}</button></div>
              : <div style={{ overflowX: 'auto' }} tabIndex={0} aria-label={t('Posuvný registr ICT incidentů', 'Scrollable ICT incident register')}><table className="data-table" aria-busy={loading} style={{ minWidth: 980 }}><caption className="sr-only">{t('DORA registr ICT incidentů', 'DORA ICT incident register')}</caption><thead><tr><th>{t('Incident', 'Incident')}</th><th>{t('Závažnost', 'Severity')}</th><th>{t('Stav', 'Status')}</th><th>{t('Zjištěno', 'Detected')}</th><th>{t('Regulátor', 'Regulator')}</th><th>{t('Odpovědnost', 'Ownership')}</th></tr></thead><tbody>{filtered.map(item => <tr key={item.id}>
                <td style={{ minWidth: 280 }}><strong>{item.title}</strong><div style={{ marginTop: 5 }}><StatusBadge status={item.category} tone="neutral" label={item.category.replaceAll('_', ' ')} /></div><details style={{ marginTop: 7 }}><summary style={{ cursor: 'pointer', color: 'var(--accent-text)', fontSize: 12 }}>{t('Co se stalo', 'What happened')}</summary><p style={{ maxWidth: 460, margin: '6px 0 0', color: 'var(--text-secondary)', fontSize: 12 }}>{item.description}</p></details></td>
                <td><StatusBadge status={item.severity} tone={SEVERITY_TONE[item.severity]} label={severityLabel(item.severity)} withDot /></td>
                <td><StatusBadge status={item.status} label={statusLabel(item.status)} withDot /></td>
                <td><time dateTime={item.detectedAt}>{new Date(item.detectedAt).toLocaleString(dateLocale)}</time></td>
                <td>{item.reportedToRegulator ? <div><StatusBadge status="REPORTED" tone="success" label={t('Oznámeno', 'Reported')} withDot /><div style={{ marginTop: 5, fontFamily: 'var(--font-mono)', fontSize: 11 }}>{item.regulatoryReportId}</div></div> : <StatusBadge status="NOT_REPORTED" tone="warning" label={t('Bez ID hlášení', 'No report ID')} withDot />}</td>
                <td><div style={{ fontSize: 12, fontWeight: 650 }}>{item.assignedTo ?? t('Nepřiřazeno', 'Unassigned')}</div><div style={{ marginTop: 5, color: 'var(--text-tertiary)', fontSize: 11 }}>{item.affectedServices.length ? item.affectedServices.join(', ') : t('Bez uvedené služby', 'No service listed')}</div></td>
              </tr>)}</tbody></table></div>}
        </section>}
  </div></AuthGuard>
}
