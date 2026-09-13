// SPDX-License-Identifier: Apache-2.0

'use client'

import { FormEvent, useMemo, useState } from 'react'
import { Network, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { parseContextNeighborhood, type ContextNeighborhood } from '@/lib/context/graph'

export function ComplaintContextInvestigation() {
  const { t } = useLanguage()
  const [reference, setReference] = useState('')
  const [caseId, setCaseId] = useState('')
  const [purpose, setPurpose] = useState('PAYMENT_COMPLAINT')
  const [graph, setGraph] = useState<ContextNeighborhood | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'missing' | 'error'>('idle')
  const positions = useMemo(() => (graph?.nodes ?? []).map((node, index, nodes) => {
    if (node.key === graph?.root) return { ...node, x: 400, y: 220 }
    const peers = nodes.filter(candidate => candidate.key !== graph?.root)
    const peerIndex = peers.findIndex(candidate => candidate.key === node.key)
    const angle = (peerIndex / Math.max(peers.length, 1)) * Math.PI * 2 - Math.PI / 2
    return { ...node, x: 400 + Math.cos(angle) * 270, y: 220 + Math.sin(angle) * 150 }
  }), [graph])
  const byKey = new Map(positions.map(node => [node.key, node]))
  const selectedNode = graph?.nodes.find(node => node.key === selected)
  const timeline = useMemo(() => (graph?.nodes ?? [])
    .filter(node => node.type === 'PAYMENT_STAGE' || node.type === 'RAIL_EVIDENCE')
    .map(node => ({
      ...node,
      relation: graph?.edges.find(edge => edge.to === node.key)?.relation ?? node.type,
    }))
    .sort((left, right) => left.sourceVersion - right.sourceVersion), [graph])

  async function investigate(event: FormEvent) {
    event.preventDefault(); setState('loading'); setGraph(null); setSelected(null)
    try {
      const params = new URLSearchParams({ caseId: caseId.trim(), purpose: purpose.trim() })
      const response = await fetch(`/api/context/complaints/${encodeURIComponent(reference.trim())}?${params}`)
      if (response.status === 403) { setState('denied'); return }
      if (response.status === 404) { setState('missing'); return }
      if (!response.ok) { setState('error'); return }
      setGraph(parseContextNeighborhood(await response.json())); setState('idle')
    } catch { setState('error') }
  }

  return <section className="card" style={{ padding: 20, marginBottom: 24 }} aria-labelledby="complaint-context-title">
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <Network size={20} aria-hidden="true" />
      <h2 id="complaint-context-title" style={{ margin: 0, fontSize: 18 }}>{t('Vyšetřovací kontext', 'Investigation context')}</h2>
      <span className="tag"><ShieldCheck size={12} /> {t('Řízený přístup', 'Controlled access')}</span>
    </div>
    <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
      {t('Graf se načte pouze pro aktivně přidělený případ a účel. Každé čtení se audituje.',
        'The graph loads only for an actively assigned case and purpose. Every read is audited.')}
    </p>
    <form onSubmit={investigate} style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(160px, 1fr)) auto', gap: 10 }}>
      <input className="input" required maxLength={200} value={reference} onChange={e => setReference(e.target.value)} placeholder={t('Reference reklamace', 'Complaint reference')} aria-label={t('Reference reklamace', 'Complaint reference')} />
      <input className="input" required maxLength={200} value={caseId} onChange={e => setCaseId(e.target.value)} placeholder={t('ID případu', 'Case ID')} aria-label={t('ID případu', 'Case ID')} />
      <input className="input" required maxLength={80} value={purpose} onChange={e => setPurpose(e.target.value.toUpperCase())} placeholder={t('Účel', 'Purpose')} aria-label={t('Účel vyšetřování', 'Investigation purpose')} />
      <button className="btn btn-primary" disabled={state === 'loading'}>{state === 'loading' ? t('Načítám…', 'Loading…') : t('Zobrazit graf', 'Show graph')}</button>
    </form>
    {state === 'denied' && <p role="alert" style={{ color: 'var(--danger)' }}>{t('Pro tento případ nemáte aktivní oprávnění.', 'You do not have an active assignment for this case.')}</p>}
    {state === 'missing' && <p role="status">{t('Pro tuto referenci zatím není projekce.', 'No projection exists for this reference yet.')}</p>}
    {state === 'error' && <p role="alert" style={{ color: 'var(--danger)' }}>{t('Kontextová služba není dostupná.', 'The context service is unavailable.')}</p>}
    {graph && <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 3fr) minmax(240px, 1fr)', gap: 16, marginTop: 18 }}>
      <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}>
        <svg viewBox="0 0 800 440" style={{ display: 'block', minWidth: 620 }} role="group" aria-label={t('Graf souvislostí reklamace', 'Complaint relationship graph')}>
          {graph.edges.map(edge => { const from = byKey.get(edge.from); const to = byKey.get(edge.to); return from && to
            ? <line key={edge.id} x1={from.x} y1={from.y} x2={to.x} y2={to.y} stroke="var(--accent)" strokeWidth="2"><title>{edge.relation}</title></line> : null })}
          {positions.map(node => <g key={node.key} role="button" tabIndex={0} onClick={() => setSelected(node.key)} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setSelected(node.key) }} style={{ cursor: 'pointer' }}>
            <circle cx={node.x} cy={node.y} r={node.key === graph.root ? 42 : 31} fill="var(--surface)" stroke={node.classification === 'RESTRICTED' ? 'var(--danger)' : 'var(--accent)'} strokeWidth={selected === node.key ? 4 : 2} />
            <text x={node.x} y={node.y + 4} textAnchor="middle" fill="var(--text-primary)" fontSize="13">{node.type}</text>
          </g>)}
        </svg>
      </div>
      <aside style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 14, overflowWrap: 'anywhere' }}>
        {selectedNode ? <><strong>{selectedNode.label}</strong><p>{selectedNode.type} · {selectedNode.classification}</p><p>{t('Zdroj', 'Source')}: {selectedNode.sourceSystem}</p><p>{t('Platné od', 'Valid from')}: {new Date(selectedNode.validFrom).toLocaleString()}</p></> : <p>{t('Vyberte uzel pro podklad.', 'Select a node to inspect its evidence.')}</p>}
        {graph.truncated && <p role="status" style={{ color: 'var(--warning-text)' }}>{t('Výsledek dosáhl bezpečnostního limitu.', 'The result reached its safety limit.')}</p>}
      </aside>
      {timeline.length > 0 && <section style={{ gridColumn: '1 / -1', border: '1px solid var(--border)', borderRadius: 10, padding: 14 }} aria-labelledby="payment-lifecycle-title">
        <h3 id="payment-lifecycle-title" style={{ margin: '0 0 10px', fontSize: 15 }}>{t('Časová osa platby', 'Payment timeline')}</h3>
        <ol style={{ display: 'grid', gap: 8, margin: 0, paddingInlineStart: 24 }}>
          {timeline.map(item => <li key={item.key}>
            <strong>{item.relation.replaceAll('_', ' ')}</strong>
            {' · '}{new Date(item.validFrom).toLocaleString()}
            <span style={{ color: 'var(--text-secondary)' }}> · {item.sourceSystem}</span>
          </li>)}
        </ol>
      </section>}
    </div>}
  </section>
}
