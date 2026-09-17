// SPDX-License-Identifier: Apache-2.0
'use client'

import { useRef, useState, type FormEvent } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { parseAmlCaseEvidence, type AmlCaseEvidenceHistory } from '@/lib/context/amlCaseEvidence'

export function AmlCaseInvestigation({ initialCaseId = '' }: { initialCaseId?: string }) {
  const { t } = useLanguage()
  const [caseId, setCaseId] = useState(initialCaseId)
  const [history, setHistory] = useState<AmlCaseEvidenceHistory | null>(null)
  const [selected, setSelected] = useState(0)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const generation = useRef(0)

  async function load(event: FormEvent) {
    event.preventDefault()
    const version = ++generation.current
    setHistory(null); setSelected(0); setState('loading')
    try {
      const id = caseId.trim().toLowerCase()
      if (!AUTHORITY_UUID.test(id)) throw new Error('Invalid case ID')
      const response = await fetch(`/api/context/aml-cases/${encodeURIComponent(id)}`, { cache: 'no-store' })
      if (version !== generation.current) return
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) throw new Error('Unavailable evidence')
      const result = parseAmlCaseEvidence(await response.json())
      if (version !== generation.current) return
      if (result.root !== `aml-case:${id}`) throw new Error('Mismatched case')
      setHistory(result); setState('idle')
    } catch { if (version === generation.current) setState('error') }
  }

  const observation = history?.observations[selected]
  const caseNode = history?.root.slice('aml-case:'.length)
  const party = history?.observations.find(item => item.evidence.partyId)?.evidence.partyId
  const account = history?.observations.find(item => item.evidence.accountId)?.evidence.accountId
  const transaction = history?.observations.find(item => item.evidence.transactionId)?.evidence.transactionId
  const nodes = caseNode && party ? [
    { label: t('AML případ', 'AML case'), value: caseNode, x: 20, y: 85, tone: '#7775e7' },
    { label: t('Klient', 'Party'), value: party, x: 285, y: 85, tone: '#23b0cc' },
    ...(account ? [{ label: t('Účet', 'Account'), value: account, x: 550, y: 24, tone: '#e4a445' }] : []),
    ...(transaction ? [{ label: t('Transakce', 'Transaction'), value: transaction, x: 550, y: 153, tone: '#e4a445' }] : []),
  ] : []

  return <section className="card" style={{ padding: 20, marginBottom: 20, minWidth: 0, overflowWrap: 'anywhere' }} aria-label={t('Investigace AML případu', 'AML case investigation')}>
    <h2 style={{ marginTop: 0 }}>{t('Context Graph · AML evidence', 'Context Graph · AML evidence')}</h2>
    <p style={{ color: 'var(--text-secondary)' }}>{t('Vazby pocházejí z událostí AML případu. Ukazují pozorovanou souvislost, nikoli prokázaný podvod ani aktuální vlastnictví.', 'Links come from AML case events. They show observed context, not proven fraud or current ownership.')}</p>
    <form onSubmit={load} style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
      <input className="input" style={{ flex: '1 1 290px' }} required maxLength={36} value={caseId} onChange={event => { generation.current++; setCaseId(event.target.value); setHistory(null); setState('idle') }} placeholder="Case UUID" aria-label={t('ID AML případu', 'AML case ID')} />
      <button className="btn btn-primary" disabled={state === 'loading'}>{t('Prozkoumat vazby', 'Explore connections')}</button>
    </form>
    {state === 'loading' && <p role="status">{t('Načítám evidenci…', 'Loading evidence…')}</p>}
    {state === 'denied' && <p role="alert">{t('Přístup k tomuto AML případu nebyl povolen.', 'Access to this AML case was not permitted.')}</p>}
    {state === 'error' && <p role="alert">{t('Evidenci případu nelze bezpečně ověřit.', 'Case evidence could not be verified safely.')}</p>}
    {history && <>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Události do', 'Events through')}: {history.effectiveAt} · {t('Zaznamenáno do', 'Known through')}: {history.knownAt}</p>
      {history.truncated && <p role="status">{t('Částečný výsledek: nejvýše 100 událostí.', 'Partial result: at most 100 events.')}</p>}
      {!history.observations.length && <p role="status">{t('V tomto rozsahu není dostupná evidence.', 'No evidence is available in this scope.')}</p>}
      {nodes.length > 0 && <div style={{ overflowX: 'auto', borderRadius: 12, border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
        <svg viewBox="0 0 820 250" role="img" aria-label={t('Graf pozorovaných AML vazeb', 'Observed AML relationship graph')} style={{ width: '100%', minWidth: 620, display: 'block' }}>
          <defs><pattern id="aml-grid" width="24" height="24" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#73839d" opacity=".28" /></pattern></defs>
          <rect width="820" height="250" fill="url(#aml-grid)" />
          <g fill="none" stroke="#6ea4c9" strokeWidth="2" opacity=".72">
            <path d="M265 121H285" />
            {account && <path d="M530 121 C540 121 540 60 550 60" />}
            {transaction && <path d="M530 121 C540 121 540 189 550 189" />}
          </g>
          {nodes.map(node => <g key={node.label + node.value}><rect x={node.x} y={node.y} width="245" height="72" rx="12" fill="var(--surface-1, #111827)" stroke={node.tone} strokeWidth="2" /><text x={node.x + 12} y={node.y + 26} fill={node.tone} fontSize="14" fontWeight="700">{node.label}</text><text x={node.x + 12} y={node.y + 51} fill="var(--text-primary)" fontSize="10">{node.value}</text></g>)}
        </svg>
      </div>}
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
