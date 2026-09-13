// SPDX-License-Identifier: Apache-2.0

'use client'

import { FormEvent, useState } from 'react'
import { Network } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { IctIncident } from '@/lib/security/incidentEvidence'

type Impact = { affectedByType: Record<string, number>; total: number; drilldownAvailable: boolean }

export function IncidentImpactInvestigation({ incidents }: { incidents: IctIncident[] }) {
  const { t } = useLanguage()
  const [reference, setReference] = useState('')
  const [caseId, setCaseId] = useState('')
  const [impact, setImpact] = useState<Impact | null>(null)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')

  async function load(event: FormEvent) {
    event.preventDefault(); setState('loading'); setImpact(null)
    try {
      const query = new URLSearchParams({ caseId: caseId.trim(), purpose: 'INCIDENT_IMPACT' })
      const response = await fetch(`/api/context/incidents/${encodeURIComponent(reference)}/impact?${query}`)
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) { setState('error'); return }
      setImpact(await response.json() as Impact); setState('idle')
    } catch { setState('error') }
  }

  return <section className="card" style={{ padding: 18, marginBottom: 20 }} aria-labelledby="incident-impact-title">
    <h2 id="incident-impact-title" style={{ marginTop: 0, fontSize: 16 }}><Network size={17} aria-hidden="true" /> {t('Mapa obchodního dopadu', 'Business impact map')}</h2>
    <p style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{t('Ukazuje pouze agregované typy zásahu; identifikátory klientů se v tomto pohledu nevracejí.', 'Shows aggregate impact types only; customer identifiers are never returned by this view.')}</p>
    <form onSubmit={load} style={{ display: 'grid', gridTemplateColumns: '2fr 2fr auto', gap: 8 }}>
      <select required className="input" value={reference} onChange={e => setReference(e.target.value)} aria-label={t('Incident', 'Incident')}><option value="">{t('Vyberte incident', 'Select incident')}</option>{incidents.map(item => <option key={item.id} value={item.id}>{item.severity} · {item.title}</option>)}</select>
      <input required className="input" value={caseId} onChange={e => setCaseId(e.target.value)} placeholder={t('ID přiděleného případu', 'Assigned case ID')} aria-label={t('ID případu', 'Case ID')} />
      <button className="btn btn-primary" disabled={state === 'loading'}>{t('Vyhodnotit dopad', 'Evaluate impact')}</button>
    </form>
    {state === 'denied' && <p role="alert" style={{ color: 'var(--danger)' }}>{t('Pro tento případ nemáte aktivní přidělení.', 'You do not have an active assignment for this case.')}</p>}
    {state === 'error' && <p role="alert">{t('Dopad nelze bezpečně ověřit.', 'Impact could not be verified safely.')}</p>}
    {impact && <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 12 }}><strong>{t(`Celkem ${impact.total}`, `Total ${impact.total}`)}</strong>{Object.entries(impact.affectedByType).map(([type, count]) => <span className="tag" key={type}>{type}: {count}</span>)}</div>}
  </section>
}
