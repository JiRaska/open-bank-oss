// SPDX-License-Identifier: Apache-2.0

'use client'

import { FormEvent, useRef, useState } from 'react'
import { Network } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { IctIncident } from '@/lib/security/incidentEvidence'
import { parseIncidentImpact, type IncidentImpact } from '@/lib/context/incidentImpact'
import { IncidentImpactMap } from './IncidentImpactMap'

export function IncidentImpactInvestigation({ incidents }: { incidents: IctIncident[] }) {
  const { t, language } = useLanguage()
  const [reference, setReference] = useState('')
  const [caseId, setCaseId] = useState('')
  const [impact, setImpact] = useState<IncidentImpact | null>(null)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const selectedIncident = incidents.find(item => item.id === reference)
  const validWindow = selectedIncident &&
    (!selectedIncident.containedAt || Date.parse(selectedIncident.containedAt) >= Date.parse(selectedIncident.detectedAt)) &&
    (!selectedIncident.resolvedAt || Date.parse(selectedIncident.resolvedAt) >= Date.parse(selectedIncident.detectedAt)) &&
    (!selectedIncident.containedAt || !selectedIncident.resolvedAt ||
      Date.parse(selectedIncident.resolvedAt) >= Date.parse(selectedIncident.containedAt))
  const localTime = (value: string) => new Date(value).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')

  const requestVersion = useRef(0)
  function clearResult() { requestVersion.current++; setImpact(null); setState('idle') }

  async function load(event: FormEvent) {
    event.preventDefault(); const version = ++requestVersion.current; setState('loading'); setImpact(null)
    try {
      const query = new URLSearchParams({ caseId: caseId.trim(), purpose: 'INCIDENT_IMPACT' })
      const response = await fetch(`/api/context/incidents/${encodeURIComponent(reference)}/impact?${query}`)
      if (version !== requestVersion.current) return
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) { setState('error'); return }
      const body: unknown = await response.json()
      if (version !== requestVersion.current) return
      setImpact(parseIncidentImpact(body)); setState('idle')
    } catch { if (version === requestVersion.current) setState('error') }
  }

  return <section className="card" style={{ padding: 18, marginBottom: 20 }} aria-labelledby="incident-impact-title">
    <h2 id="incident-impact-title" style={{ marginTop: 0, fontSize: 16 }}><Network size={17} aria-hidden="true" /> {t('Mapa obchodního dopadu', 'Business impact map')}</h2>
    <p style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{t('Ukazuje pouze agregované typy zásahu; identifikátory klientů se v tomto pohledu nevracejí.', 'Shows aggregate impact types only; customer identifiers are never returned by this view.')}</p>
    <form onSubmit={load} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 180px), 1fr))', gap: 8 }}>
      <select required className="input" value={reference} onChange={e => { clearResult(); setReference(e.target.value) }} aria-label={t('Incident', 'Incident')}><option value="">{t('Vyberte incident', 'Select incident')}</option>{incidents.map(item => <option key={item.id} value={item.id}>{item.severity} · {item.title}</option>)}</select>
      <input required className="input" value={caseId} onChange={e => { clearResult(); setCaseId(e.target.value) }} placeholder={t('ID přiděleného případu', 'Assigned case ID')} aria-label={t('ID případu', 'Case ID')} />
      <button className="btn btn-primary" disabled={state === 'loading'}>{t('Vyhodnotit dopad', 'Evaluate impact')}</button>
    </form>
    {state === 'denied' && <p role="alert" style={{ color: 'var(--danger)' }}>{t('Pro tento případ nemáte aktivní přidělení.', 'You do not have an active assignment for this case.')}</p>}
    {state === 'error' && <p role="alert">{t('Dopad nelze bezpečně ověřit.', 'Impact could not be verified safely.')}</p>}
    {impact && selectedIncident && <div style={{ marginTop: 16, padding: 14, border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface-2)' }}>
      <strong>{t('Časové okno ze zdrojového registru', 'Source register timeline')}</strong>
      {validWindow ? <ol aria-label={t('Časová osa incidentu', 'Incident timeline')} style={{ display: 'flex', flexWrap: 'wrap', gap: 24, margin: '12px 0 0', paddingLeft: 20 }}>
        <li>{t('Zjištěn', 'Detected')}: <time dateTime={selectedIncident.detectedAt}>{localTime(selectedIncident.detectedAt)}</time></li>
        <li>{t('Zadržen', 'Contained')}: {selectedIncident.containedAt ? <time dateTime={selectedIncident.containedAt}>{localTime(selectedIncident.containedAt)}</time> : t('Nezaznamenáno', 'Not recorded')}</li>
        <li>{t('Vyřešen', 'Resolved')}: {selectedIncident.resolvedAt ? <time dateTime={selectedIncident.resolvedAt}>{localTime(selectedIncident.resolvedAt)}</time> : t('Nezaznamenáno', 'Not recorded')}</li>
      </ol> : <p role="status">{t('Časy ve zdrojovém záznamu si odporují; časové okno je neznámé.', 'Source timestamps conflict; the incident window is unknown.')}</p>}
      <p style={{ color: 'var(--text-secondary)', fontSize: 12, marginBottom: 0 }}>{t('Časové okno samo neprokazuje dopad na klienta ani obchodní případ.', 'A time window alone does not prove impact on a customer or business case.')}</p>
    </div>}
    {impact?.projectionStatus === 'MISSING' && <p role="status">{t('Pro incident zatím není projekce. Dopad je neznámý.', 'No projection exists for this incident yet. Impact is unknown.')}</p>}
    {impact?.projectionStatus === 'PARTIAL' && <p role="status">{t('Částečný výsledek: počty představují pouze načtený výřez.', 'Partial result: counts cover only the retrieved slice.')}</p>}
    {impact?.projectionStatus === 'UNKNOWN' && <p role="status">{t('Zdroj neposkytuje informaci o úplnosti projekce.', 'The source does not report projection completeness.')}</p>}
    {impact && impact.projectionStatus !== 'MISSING' && <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 12 }}><strong>{t(`Zobrazeno ${impact.total}`, `Observed ${impact.total}`)}</strong>{Object.entries(impact.affectedByType).map(([type, count]) => <span className="tag" key={type}>{type}: {count}</span>)}</div>}
    {impact && <IncidentImpactMap impact={impact} />}
    {impact && impact.projectionStatus !== 'MISSING' && <p style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{t('Počty vycházejí z projekce zdrojových událostí. Nepotvrzují úplné pokrytí ani skutečný dopad na jednotlivé klienty.', 'Counts reflect the source-event projection. They do not establish full coverage or confirmed impact on individual customers.')}</p>}
  </section>
}
