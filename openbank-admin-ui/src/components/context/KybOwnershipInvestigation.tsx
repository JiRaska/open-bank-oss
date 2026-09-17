// SPDX-License-Identifier: Apache-2.0
'use client'

import { useRef, useState, type FormEvent } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { KYB_UUID, parseOwnershipHistory, type OwnershipDetail, type OwnershipHistory } from '@/lib/context/kybOwnership'

export function KybOwnershipInvestigation({ initialCaseId = '' }: { initialCaseId?: string }) {
  const { t } = useLanguage()
  const [caseId, setCaseId] = useState(initialCaseId)
  const [history, setHistory] = useState<OwnershipHistory | null>(null)
  const [detail, setDetail] = useState<OwnershipDetail | null>(null)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const [detailState, setDetailState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const generation = useRef(0)

  async function load(event: FormEvent) {
    event.preventDefault()
    const version = ++generation.current
    setHistory(null); setDetail(null); setState('loading'); setDetailState('idle')
    try {
      const id = caseId.trim().toLowerCase()
      if (!KYB_UUID.test(id)) throw new Error('Invalid case')
      const response = await fetch(`/api/context/kyb-cases/${encodeURIComponent(id)}/ownership-observations`, { cache: 'no-store' })
      if (version !== generation.current) return
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) throw new Error('Unavailable references')
      const result = parseOwnershipHistory(await response.json())
      if (version !== generation.current || result.root !== `kyb-case:${id}`) return
      setHistory(result); setState('idle')
    } catch { if (version === generation.current) setState('error') }
  }

  async function select(observationId: string) {
    if (!history) return
    const version = ++generation.current
    setDetail(null); setDetailState('loading')
    try {
      const id = history.root.slice('kyb-case:'.length)
      const response = await fetch(`/api/context/kyb-cases/${encodeURIComponent(id)}/ownership-observations/${encodeURIComponent(observationId)}`, { cache: 'no-store' })
      if (version !== generation.current) return
      if (response.status === 403) { setDetailState('denied'); return }
      if (!response.ok) throw new Error('Unavailable detail')
      const result = await response.json() as OwnershipDetail
      if (version !== generation.current || result.caseId !== id || result.observationId !== observationId) throw new Error('Scope mismatch')
      setDetail(result); setDetailState('idle')
    } catch { if (version === generation.current) setDetailState('error') }
  }

  const owners = detail?.owners.slice(0, 8) ?? []
  const height = Math.max(245, 106 + owners.length * 58)
  return <section className="card" style={{ padding: 20, marginBottom: 20, minWidth: 0, overflowWrap: 'anywhere' }} aria-label={t('Graf vlastnických pozorování', 'Ownership observation graph')}>
    <h2 style={{ marginTop: 0 }}>{t('Context Graph · firemní vlastnictví', 'Context Graph · corporate ownership')}</h2>
    <p style={{ color: 'var(--text-secondary)' }}>{t('Historická pozorování z KYB. Graf zobrazuje evidenci, nikoli potvrzení aktuálního vlastníka. Detail uvidí jen přidělený pracovník s příslušnou rolí.', 'Historical KYB observations. This graph shows evidence, not a claim about current ownership. Detail is limited to an assigned investigator with the right role.')}</p>
    <form onSubmit={load} style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
      <input className="input" required maxLength={36} value={caseId} onChange={event => { generation.current++; setCaseId(event.target.value); setHistory(null); setDetail(null); setState('idle'); setDetailState('idle') }} aria-label={t('ID KYB případu', 'KYB case ID')} placeholder={t('ID firemního případu', 'Corporate case ID')} style={{ flex: '1 1 290px' }} />
      <button className="btn btn-primary" disabled={state === 'loading'}>{t('Prozkoumat historii', 'Explore history')}</button>
    </form>
    {state === 'loading' && <p role="status">{t('Načítám reference…', 'Loading references…')}</p>}
    {state === 'denied' && <p role="alert">{t('Přístup k tomuto případu nebyl povolen.', 'Access to this case was not permitted.')}</p>}
    {state === 'error' && <p role="alert">{t('Historii se nepodařilo bezpečně ověřit.', 'The history could not be verified safely.')}</p>}
    {history && <>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Context zaznamenal do', 'Context recorded through')}: {history.knownAt} · {history.observations.length} {t('verzí', 'versions')}{history.truncated ? ` · ${t('částečný výsledek', 'partial result')}` : ''}</p>
      {!history.observations.length && <p role="status">{t('Zatím žádné pozorování. To neznamená, že firma nemá vlastníky.', 'No observation is available yet. This does not mean the company has no owners.')}</p>}
      {history.observations.length > 0 && <div style={{ overflowX: 'auto', border: '1px solid #2d4970', borderRadius: 16, background: 'radial-gradient(circle at 16% 38%, #16365b 0, transparent 34%), radial-gradient(circle at 79% 45%, #122e4a 0, transparent 40%), #071523', boxShadow: 'inset 0 0 54px #0a2d4d' }}>
        <svg viewBox={`0 0 920 ${height}`} role="img" aria-label={t('Vazby KYB případu na historické pozorování a vlastníky', 'KYB case connected to a historical observation and owners')} style={{ display: 'block', width: '100%', minWidth: 720 }}>
          <defs><pattern id="kyb-grid" width="25" height="25" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#63a4cc" opacity=".23" /></pattern></defs>
          <rect width="920" height={height} fill="url(#kyb-grid)" />
          <path d="M235 112H333" stroke="#61ccf5" strokeWidth="2" opacity=".8" />
          {owners.map((owner, index) => <path key={`${owner.fullName}-${index}`} d={`M573 112 C620 112 610 ${63 + index * 58} 648 ${63 + index * 58}`} fill="none" stroke="#48e2c0" strokeWidth="1.6" opacity=".78" />)}
          <rect x="30" y="71" width="205" height="82" rx="13" fill="#102d49" stroke="#61ccf5" strokeWidth="2" />
          <text x="46" y="101" fill="#80d8ff" fontSize="14" fontWeight="700">{t('KYB případ', 'KYB case')}</text>
          <text x="46" y="127" fill="#d8f4ff" fontSize="11">{history.root.slice(9, 27)}…</text>
          <rect x="333" y="71" width="240" height="82" rx="13" fill="#132642" stroke="#8d9afa" strokeWidth="2" />
          <text x="349" y="101" fill="#b1bafa" fontSize="14" fontWeight="700">{t('Vybrané pozorování', 'Selected observation')}</text>
          <text x="349" y="127" fill="#eef3ff" fontSize="11">{detail ? `${t('Revize', 'Revision')} ${detail.revision}` : t('Zvolte revizi níže', 'Choose a revision below')}</text>
          {owners.map((owner, index) => <g key={`${owner.fullName}-${index}`}><rect x="648" y={37 + index * 58} width="245" height="52" rx="10" fill="#0d3a3b" stroke="#48e2c0" strokeWidth="1.5" /><text x="662" y={61 + index * 58} fill="#aef5e4" fontSize="12" fontWeight="600">{owner.fullName.slice(0, 31)}</text><text x="662" y={77 + index * 58} fill="#9cd9d1" fontSize="10">{owner.corporate ? t('Právnická osoba', 'Legal entity') : t('Fyzická osoba', 'Person')} · {owner.band}</text></g>)}
        </svg>
      </div>}
      <ol aria-label={t('Revize vlastnických pozorování', 'Ownership observation revisions')} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', listStyle: 'none', padding: 0, marginTop: 14 }}>
        {history.observations.map(item => <li key={item.observationId}><button type="button" className="btn" aria-pressed={detail?.observationId === item.observationId} onClick={() => select(item.observationId)}>{t('Revize', 'Revision')} {item.revision}</button></li>)}
      </ol>
      {detailState === 'loading' && <p role="status">{t('Ověřuji detail ve zdrojové službě…', 'Verifying detail with the source service…')}</p>}
      {detailState === 'denied' && <p role="alert">{t('Detail není pro tento případ povolen.', 'Detail is not permitted for this case.')}</p>}
      {detailState === 'error' && <p role="alert">{t('Detail nyní nelze bezpečně ověřit.', 'Detail cannot be verified safely right now.')}</p>}
      {detail && detail.owners.length > owners.length && <p role="status">{t('Graf zobrazuje prvních osm vlastníků této revize; detail zůstává úplný.', 'The graph shows the first eight owners in this revision; the evidence detail remains complete.')}</p>}
      {detail && <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
        <p>{t('Zdroj', 'Source')}: {detail.source} · {t('Zjištěno', 'Fetched')}: {detail.fetchedAt} · SHA-256: {detail.sourceSha256}</p>
        <ol aria-label={t('Vlastníci ve vybrané revizi', 'Owners in the selected revision')} style={{ maxHeight: 280, overflowY: 'auto', paddingLeft: 22 }}>
          {detail.owners.map((owner, index) => <li key={`${owner.fullName}-${index}`} style={{ marginBottom: 5 }}><strong>{owner.fullName}</strong> · {owner.band} · {owner.natureOfControl.join(', ')}</li>)}
        </ol>
        <p>{t('Jde o historické pozorování. Aktuální vlastnictví a platnost ověřte ve zdroji.', 'This is a historical observation. Verify current ownership and validity at the source.')}</p>
      </div>}
    </>}
  </section>
}
