// SPDX-License-Identifier: Apache-2.0
'use client'

import { useId, useRef, useState, type FormEvent } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AUTHORITY_UUID, authorityTimestamp, parseAuthorityHistory, type AuthorityHistory } from '@/lib/context/authorityHistory'

export function AuthorityHistoryInvestigation() {
  const { t } = useLanguage()
  const titleId = useId(), markerId = useId().replace(/:/g, '')
  const [delegation, setDelegation] = useState(''), [caseId, setCaseId] = useState('')
  const [effectiveAt, setEffectiveAt] = useState(''), [knownAt, setKnownAt] = useState('')
  const [history, setHistory] = useState<AuthorityHistory | null>(null)
  const [selected, setSelected] = useState(0)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const generation = useRef(0)
  function change(set: (value: string) => void, value: string) {
    generation.current++; setHistory(null); setSelected(0); setState('idle'); set(value)
  }
  async function load(event: FormEvent) {
    event.preventDefault()
    const version = ++generation.current
    setHistory(null); setSelected(0); setState('loading')
    try {
      const id = delegation.trim().toLowerCase()
      if (!AUTHORITY_UUID.test(id) || !caseId.trim() || caseId.trim().length > 200) throw new Error('Invalid scope')
      const query = new URLSearchParams({ caseId: caseId.trim() })
      if (effectiveAt.trim()) query.set('effectiveAt', authorityTimestamp(effectiveAt.trim()))
      if (knownAt.trim()) query.set('knownAt', authorityTimestamp(knownAt.trim()))
      const response = await fetch(`/api/context/authorizations/${encodeURIComponent(id)}?${query}`, { cache: 'no-store' })
      if (version !== generation.current) return
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) throw new Error('Unavailable evidence')
      const result = parseAuthorityHistory(await response.json())
      if (version !== generation.current) return
      if (result.root !== `delegation:${id}`) throw new Error('Mismatched scope')
      setHistory(result); setState('idle')
    } catch { if (version === generation.current) setState('error') }
  }
  const observation = history?.observations[selected]
  const evidence = observation?.evidence
  const nodes = evidence ? [
    { x: 10, y: 94, label: t('Poskytovatel', 'Grantor'), id: evidence.grantorPartyId },
    { x: 280, y: 94, label: t('Delegace', 'Delegation'), id: evidence.delegationId },
    { x: 550, y: 32, label: t('Příjemce', 'Grantee'), id: evidence.granteePartyId },
    ...(evidence.resourceId ? [{ x: 550, y: 166, label: evidence.resourceType ?? t('Zdroj', 'Resource'), id: evidence.resourceId }] : []),
  ] : []
  return <section className="card" style={{ padding: 20, marginBottom: 20, minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' }} aria-labelledby={titleId}>
    <h2 id={titleId}>{t('Historie delegací', 'Delegation evidence history')}</h2>
    <p style={{ color: 'var(--text-secondary)' }}>{t('Pozorování ze služby delegací. Oprávnění konkrétní obchodní akce: NEZNÁMÉ. Chybějící historie neznamená zamítnutí ani povolení.', 'Source delegation observations. Authorization of a specific business action: UNKNOWN. Missing history means neither denial nor permission.')}</p>
    <form onSubmit={load} style={{ display: 'grid', gap: 10, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 220px), 1fr))' }}>
      <label>{t('ID delegace', 'Delegation ID')}<input className="input" required maxLength={36} value={delegation} onChange={e => change(setDelegation, e.target.value)} placeholder="UUID" /></label>
      <label>{t('ID přiděleného případu', 'Assigned case ID')}<input className="input" required maxLength={200} value={caseId} onChange={e => change(setCaseId, e.target.value)} /></label>
      <label>{t('Události do (ISO, nepovinné)', 'Events through (ISO, optional)')}<input className="input" maxLength={40} value={effectiveAt} onChange={e => change(setEffectiveAt, e.target.value)} placeholder="YYYY-MM-DDTHH:mm:ssZ" /></label>
      <label>{t('Zaznamenáno do (ISO, nepovinné)', 'Known through (ISO, optional)')}<input className="input" maxLength={40} value={knownAt} onChange={e => change(setKnownAt, e.target.value)} placeholder="YYYY-MM-DDTHH:mm:ssZ" /></label>
      <button className="btn btn-primary" disabled={state === 'loading'}>{t('Načíst historii', 'Load history')}</button>
    </form>
    {state === 'loading' && <p role="status">{t('Načítání evidence…', 'Loading evidence…')}</p>}
    {state === 'denied' && <p role="alert">{t('Přístup k tomuto případu nebyl povolen.', 'Access to this case was not permitted.')}</p>}
    {state === 'error' && <p role="alert">{t('Historii nelze bezpečně ověřit. Zkontrolujte rozsah a časové údaje.', 'History could not be verified safely. Check the scope and timestamps.')}</p>}
    {history && <>
      <p>{t('Události do', 'Events through')}: {history.effectiveAt} · {t('Zaznamenáno do', 'Known through')}: {history.knownAt}</p>
      {history.truncated && <p role="status">{t('Částečný výsledek: zobrazeno nejvýše 100 pozorování.', 'Partial result: at most 100 observations are shown.')}</p>}
      {!history.observations.length && <p role="status">{t('V tomto časovém rozsahu není dostupná historie. Oprávnění zůstává neznámé.', 'No history is available in this time scope. Authorization remains unknown.')}</p>}
      {evidence && <>
        <div style={{ overflowX: 'auto', marginTop: 16, border: '1px solid var(--border)', borderRadius: 12 }}>
          <svg viewBox="0 0 810 260" role="img" aria-label={t('Graf pozorování delegace', 'Delegation observation graph')} style={{ width: '100%', minWidth: 620, display: 'block', color: 'var(--text-primary)' }}>
            <defs><marker id={markerId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0 0L10 5L0 10Z" fill="var(--accent, #6366f1)" /></marker></defs>
            <g fill="none" stroke="var(--accent, #6366f1)" strokeWidth="2" markerEnd={`url(#${markerId})`}>
              <path d="M260 130H278" /><path d="M530 130C540 130 540 68 548 68" />
              {evidence.resourceId && <path d="M530 130C540 130 540 202 548 202" />}
            </g>
            {nodes.map(node => <g key={node.label + node.id}><rect x={node.x} y={node.y} width={250} height={72} rx={12} fill="var(--bg-secondary, #f1f5f9)" stroke="var(--accent, #6366f1)" /><text x={node.x + 12} y={node.y + 25} fill="currentColor" fontSize={14} fontWeight={600}>{node.label}</text><text x={node.x + 12} y={node.y + 49} fill="currentColor" fontSize={9.5}>{node.id}</text></g>)}
          </svg>
        </div>
        <p>{t('Vybrané pozorování', 'Selected observation')}: <strong>{evidence.eventType}</strong> · {t('Revize', 'Revision')} {evidence.revision}</p>
        <p>{t('Platnost podle zdroje', 'Source validity')}: {evidence.validFrom ?? t('neznámá', 'unknown')} → {evidence.validTo ?? t('konec neuveden', 'end not stated')}</p>
        <p>{t('Schvalovací pravidlo', 'Approval policy')}: {evidence.approvalPolicy ?? t('neuvedeno', 'not stated')}{evidence.requiredApprovals !== null && ` · ${evidence.requiredApprovals}`}</p>
        <p>{t('Deklarované schopnosti', 'Declared capabilities')}: {evidence.capabilities.join(', ') || t('neuvedeny', 'not stated')}</p>
      </>}
      <ol aria-label={t('Časová osa pozorování', 'Observation timeline')} style={{ paddingLeft: 20 }}>
        {history.observations.map((item, index) => <li key={item.evidenceRef} style={{ marginBottom: 12 }}>
          <button type="button" className="btn" style={{ whiteSpace: 'normal', maxWidth: '100%', textAlign: 'left' }} aria-pressed={selected === index} onClick={() => setSelected(index)}>{item.evidence.eventType} · {t('Revize', 'Revision')} {item.evidence.revision} · {item.evidence.occurredAt}</button>
          <div style={{ fontSize: 12, overflowWrap: 'anywhere', color: 'var(--text-secondary)' }}>{t('Zaznamenáno', 'Recorded')}: {item.recordedAt}<br />{t('Zdroj evidence', 'Evidence source')}: {item.evidenceRef}<br />SHA-256: {item.contentHash}</div>
        </li>)}
      </ol>
    </>}
  </section>
}
