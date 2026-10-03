// SPDX-License-Identifier: Apache-2.0
'use client'

import { useRef, useState, type FormEvent } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { amlReferences, parseAmlCaseNetwork, sharedAmlReferences, type AmlCaseEvidenceHistory } from '@/lib/context/amlCaseEvidence'

export function AmlCaseInvestigation({ initialCaseId = '' }: { initialCaseId?: string }) {
  const { t } = useLanguage()
  const [caseId, setCaseId] = useState(initialCaseId)
  const [history, setHistory] = useState<AmlCaseEvidenceHistory | null>(null)
  const [related, setRelated] = useState<AmlCaseEvidenceHistory[]>([])
  const [selected, setSelected] = useState(0)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const generation = useRef(0)

  async function load(event: FormEvent) {
    event.preventDefault()
    const version = ++generation.current
    setHistory(null); setRelated([]); setSelected(0); setState('loading')
    try {
      const id = caseId.trim().toLowerCase()
      if (!AUTHORITY_UUID.test(id)) throw new Error('Invalid case ID')
      const response = await fetch(`/api/context/aml-cases/${encodeURIComponent(id)}?view=network`, { cache: 'no-store' })
      if (version !== generation.current) return
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) throw new Error('Unavailable evidence')
      const result = parseAmlCaseNetwork(await response.json())
      if (version !== generation.current) return
      if (result.root.root !== `aml-case:${id}`) throw new Error('Mismatched case')
      setHistory(result.root); setRelated(result.related); setState('idle')
    } catch { if (version === generation.current) setState('error') }
  }

  const observation = history?.observations[selected]
  const refs = history ? [...new Set([
    ...related.flatMap(item => sharedAmlReferences(history, item).slice(0, 1)),
    ...amlReferences(history),
  ])].slice(0, 8) : []
  const graphWidth = Math.max(820, refs.length * 170, related.length * 200)
  const rootX = graphWidth / 2 - 105
  const refX = (index: number) => graphWidth * (index + 1) / (refs.length + 1) - 75
  const relatedX = (index: number) => graphWidth * (index + 1) / (related.length + 1) - 88
  const refLabel = (ref: string) => ref.startsWith('party:') ? t('Klient', 'Party') : ref.startsWith('account:') ? t('Účet', 'Account') : t('Transakce', 'Transaction')

  return <section className="card" style={{ padding: 20, marginBottom: 20, minWidth: 0, overflowWrap: 'anywhere' }} aria-label={t('Investigace AML případu', 'AML case investigation')}>
    <h2 style={{ marginTop: 0 }}>{t('Context Graph · AML evidence', 'Context Graph · AML evidence')}</h2>
    <p style={{ color: 'var(--text-secondary)' }}>{t('Vazby pocházejí z událostí AML případu. Ukazují pozorovanou souvislost, nikoli prokázaný podvod ani aktuální vlastnictví.', 'Links come from AML case events. They show observed context, not proven fraud or current ownership.')}</p>
    <form onSubmit={load} style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
      <input className="input" style={{ flex: '1 1 290px' }} required maxLength={36} value={caseId} onChange={event => { generation.current++; setCaseId(event.target.value); setHistory(null); setRelated([]); setState('idle') }} placeholder="Case UUID" aria-label={t('ID AML případu', 'AML case ID')} />
      <button className="btn btn-primary" disabled={state === 'loading'}>{t('Prozkoumat vazby', 'Explore connections')}</button>
    </form>
    {state === 'loading' && <p role="status">{t('Načítám evidenci…', 'Loading evidence…')}</p>}
    {state === 'denied' && <p role="alert">{t('Přístup k tomuto AML případu nebyl povolen.', 'Access to this AML case was not permitted.')}</p>}
    {state === 'error' && <p role="alert">{t('Evidenci případu nelze bezpečně ověřit.', 'Case evidence could not be verified safely.')}</p>}
    {history && <>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Události do', 'Events through')}: {history.effectiveAt} · {t('Zaznamenáno do', 'Known through')}: {history.knownAt}</p>
      {history.truncated && <p role="status">{t('Částečný výsledek: nejvýše 100 událostí.', 'Partial result: at most 100 events.')}</p>}
      {!history.observations.length && <p role="status">{t('V tomto rozsahu není dostupná evidence.', 'No evidence is available in this scope.')}</p>}
      {refs.length > 0 && <div style={{ overflowX: 'auto', borderRadius: 12, border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
        <svg viewBox={`0 0 ${graphWidth} ${related.length ? 410 : 250}`} role="img" aria-label={t('Graf pozorovaných AML vazeb', 'Observed AML relationship graph')} style={{ width: '100%', minWidth: 620, display: 'block' }}>
          <defs><pattern id="aml-grid" width="24" height="24" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#73839d" opacity=".28" /></pattern></defs>
          <rect width={graphWidth} height={related.length ? 410 : 250} fill="url(#aml-grid)" />
          <g fill="none" stroke="#6ea4c9" strokeWidth="2" opacity=".72">
            {refs.map((ref, index) => <path key={`root-${ref}`} d={`M${rootX + 105} 80 L${refX(index) + 75} 155`} />)}
            {related.flatMap((item, index) => sharedAmlReferences(history, item).map(ref => {
              const refIndex = refs.indexOf(ref)
              return refIndex < 0 ? null : <path key={`${item.root}-${ref}`} d={`M${refX(refIndex) + 75} 215 L${relatedX(index) + 88} 305`} />
            }))}
          </g>
          <g><rect x={rootX} y="20" width="210" height="60" rx="14" fill="var(--surface-1, #111827)" stroke="#7775e7" strokeWidth="2" /><text x={rootX + 12} y="44" fill="#7775e7" fontSize="14" fontWeight="700">{t('AML případ', 'AML case')}</text><text x={rootX + 12} y="64" fill="var(--text-primary)" fontSize="11">{history.root.slice(9, 27)}…</text></g>
          {refs.map((ref, index) => <g key={ref}><rect x={refX(index)} y="155" width="150" height="60" rx="12" fill="var(--surface-1, #111827)" stroke="#23b0cc" strokeWidth="2" /><text x={refX(index) + 10} y="179" fill="#23b0cc" fontSize="13" fontWeight="700">{refLabel(ref)}</text><text x={refX(index) + 10} y="199" fill="var(--text-primary)" fontSize="10">{ref.slice(ref.indexOf(':') + 1, ref.indexOf(':') + 19)}…</text></g>)}
          {related.map((item, index) => <g key={item.root}><rect x={relatedX(index)} y="305" width="176" height="65" rx="13" fill="var(--surface-1, #111827)" stroke="#e4a445" strokeWidth="2" /><text x={relatedX(index) + 10} y="329" fill="#e4a445" fontSize="13" fontWeight="700">{t('Související případ', 'Related case')}</text><text x={relatedX(index) + 10} y="350" fill="var(--text-primary)" fontSize="10">{item.root.slice(9, 27)}…</text></g>)}
        </svg>
      </div>}
      <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Zobrazeny jsou jen případy s vlastním schváleným přiřazením a aktuálním ověřením ve zdroji. Shodný identifikátor je vodítko, ne důkaz podvodu. Hledání je omezeno na čtyři související případy.', 'Only independently assigned, currently source-verified cases are shown. A shared identifier is a lead, not proof of fraud. Discovery is bounded to four related cases.')}</p>
      {related.map(item => <p key={item.root} style={{ fontSize: 12 }}><strong>{t('Související případ', 'Related case')}: {item.root.slice(9)}</strong> · {sharedAmlReferences(history, item).map(refLabel).join(', ')} · {item.observations.length} {t('pozorování', 'observations')}{item.truncated ? ` · ${t('částečná historie', 'partial history')}` : ''}</p>)}
      {observation && <div style={{ marginTop: 16 }}><strong>{t('Vybrané pozorování', 'Selected observation')}</strong>
        <p>{observation.evidence.eventType} · {observation.evidence.previousStatus ? `${observation.evidence.previousStatus} → ` : ''}{observation.evidence.status} · {observation.evidence.occurredAt}</p>
        <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Zdroj evidence', 'Evidence source')}: {observation.evidenceRef}<br />{t('Zaznamenáno', 'Recorded')}: {observation.recordedAt}<br />SHA-256: {observation.contentHash}</p>
      </div>}
      <ol aria-label={t('Časová osa AML událostí', 'AML event timeline')} style={{ paddingLeft: 20 }}>
        {history.observations.map((item, index) => <li key={item.evidenceRef} style={{ marginBottom: 8 }}><button type="button" className="btn" aria-pressed={selected === index} onClick={() => setSelected(index)}>{item.evidence.eventType} · {item.evidence.status} · {item.evidence.occurredAt}</button></li>)}
      </ol>
    </>}
  </section>
}
