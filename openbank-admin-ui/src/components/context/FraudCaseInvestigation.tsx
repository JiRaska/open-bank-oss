// SPDX-License-Identifier: Apache-2.0
'use client'

import { useRef, useState, type FormEvent } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { parseFraudCaseNetwork, type FraudCaseNetwork, type FraudCaseEvidence } from '@/lib/context/fraudCaseNetwork'
import styles from './FraudCaseInvestigation.module.css'

type ReferenceNode = { type: 'SCORE' | 'ACCOUNT' | 'COUNTERPARTY'; sourceId: string }
const short = (id: string) => `${id.slice(0, 8)}…${id.slice(-4)}`

export function FraudCaseInvestigation({ initialCaseId = '' }: { initialCaseId?: string }) {
  const { t } = useLanguage()
  const [caseId, setCaseId] = useState(initialCaseId)
  const [network, setNetwork] = useState<FraudCaseNetwork | null>(null)
  const [selected, setSelected] = useState(-1)
  const [state, setState] = useState<'idle' | 'loading' | 'denied' | 'error'>('idle')
  const generation = useRef(0)

  async function load(event: FormEvent) {
    event.preventDefault()
    const version = ++generation.current
    setNetwork(null); setSelected(-1); setState('loading')
    try {
      const id = caseId.trim().toLowerCase()
      if (!AUTHORITY_UUID.test(id)) throw new Error('Invalid case ID')
      const response = await fetch(`/api/context/fraud-cases/${encodeURIComponent(id)}/network`, { cache: 'no-store' })
      if (version !== generation.current) return
      if (response.status === 403) { setState('denied'); return }
      if (!response.ok) throw new Error('Unavailable evidence')
      const result = parseFraudCaseNetwork(await response.json())
      if (version !== generation.current || result.root.caseId !== id) throw new Error('Mismatched case')
      setNetwork(result); setState('idle')
    } catch { if (version === generation.current) setState('error') }
  }

  const refs: ReferenceNode[] = network ? [
    { type: 'SCORE', sourceId: network.root.scoreId },
    { type: 'ACCOUNT', sourceId: network.root.accountId },
    ...(network.root.counterpartyId ? [{ type: 'COUNTERPARTY' as const, sourceId: network.root.counterpartyId }] : []),
  ] : []
  const width = Math.max(900, refs.length * 230, (network?.related.length ?? 0) * 230)
  const rootX = width / 2
  const refX = (index: number) => width * (index + 1) / (refs.length + 1)
  const relatedX = (index: number) => width * (index + 1) / ((network?.related.length ?? 0) + 1)
  const selectedEvidence: FraudCaseEvidence | null = network && selected >= 0 ? network.related[selected]?.evidence ?? null : network?.root ?? null
  const referenceLabel = (type: ReferenceNode['type']) => type === 'ACCOUNT' ? t('ÚČET', 'ACCOUNT') : type === 'COUNTERPARTY' ? t('PROTISTRANA', 'COUNTERPARTY') : t('SKÓRE · VODÍTKO', 'SCORE · LEAD')

  return <section className={styles.shell} aria-label={t('Fraud Context Graph', 'Fraud Context Graph')}>
    <div className={styles.content}>
      <div className={styles.eyebrow}>Context intelligence / Fraud</div>
      <h2 className={styles.heading}>{t('Spojitosti za případem', 'Connections behind a case')}</h2>
      <p className={styles.intro}>{t('Prozkoumejte pouze otevřené, přidělené případy. Každá hrana znamená přesnou shodu zdrojového identifikátoru, ne prokázaný podvod.', 'Explore only open cases assigned to you. Every edge is an exact source-ID match, not a proven fraud finding.')}</p>
      <form className={styles.form} onSubmit={load}>
        <input className={styles.input} required maxLength={36} value={caseId} onChange={event => { generation.current++; setCaseId(event.target.value); setNetwork(null); setState('idle') }} placeholder={t('UUID Fraud případu', 'Fraud case UUID')} aria-label={t('ID Fraud případu', 'Fraud case ID')} />
        <button className={styles.button} disabled={state === 'loading'}>{state === 'loading' ? t('Ověřuji…', 'Verifying…') : t('Otevřít graf', 'Open graph')}</button>
      </form>
      {state === 'loading' && <p role="status" className={styles.muted}>{t('Ověřuji přiřazení a živou evidenci ve zdroji…', 'Checking assignment and live source evidence…')}</p>}
      {state === 'denied' && <p role="alert" className={styles.alert}>{t('Přístup k tomuto případu nebyl povolen nebo případ již není otevřený.', 'Access was denied or this case is no longer open.')}</p>}
      {state === 'error' && <p role="alert" className={styles.alert}>{t('Evidenci nelze bezpečně ověřit. Zkuste to později.', 'Evidence could not be verified safely. Try again later.')}</p>}
      {network && <>
        <div className={styles.chips}>
          <span className={styles.chip}>{t('Živý zdroj', 'Live source')}</span>
          <span className={styles.chip}>{t('Přístup dle případu', 'Case-scoped access')}</span>
          <span className={styles.chip}>{network.inspectedCandidates} {t('prověřené případy', 'cases inspected')}</span>
        </div>
        {network.candidateTruncated && <p role="status" className={styles.alert}>{t('Částečný pohled: další přidělené případy nebyly v tomto limitu prohledány.', 'Partial view: additional assigned cases were not searched within this limit.')}</p>}
        <div className={styles.stage}>
          <svg className={styles.graph} viewBox={`0 0 ${width} ${network.related.length ? 470 : 310}`} role="img" aria-label={t('Graf explicitních Fraud vazeb', 'Explicit Fraud evidence graph')}>
            <defs>
              <pattern id="fraud-graph-grid" width="26" height="26" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#7aa8c8" opacity=".34" /></pattern>
              <linearGradient id="fraud-root-line" x1="0" x2="1"><stop stopColor="#8d87ff" /><stop offset="1" stopColor="#65ddeb" /></linearGradient>
              <filter id="fraud-glow"><feGaussianBlur stdDeviation="5" result="blur" /><feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge></filter>
            </defs>
            <rect width={width} height={network.related.length ? 470 : 310} fill="url(#fraud-graph-grid)" />
            {refs.map((ref, index) => <path key={`root-${ref.type}`} d={`M${rootX} 112 L${refX(index)} 190`} stroke="url(#fraud-root-line)" strokeWidth="2" opacity=".72" />)}
            {network.related.flatMap((item, index) => item.shared.map(link => {
              const refIndex = refs.findIndex(ref => ref.type === link.type && ref.sourceId === link.sourceId)
              return refIndex < 0 ? null : <path key={`${item.evidence.caseId}-${link.type}`} d={`M${refX(refIndex)} 260 L${relatedX(index)} 365`} stroke="#54d6dc" strokeWidth="2" strokeDasharray="5 6" opacity=".76" />
            }))}
            <g filter="url(#fraud-glow)"><circle cx={rootX} cy="76" r="46" fill="#1a2056" stroke="#9993ff" strokeWidth="2" /><circle cx={rootX} cy="76" r="55" fill="none" stroke="#7778cf" opacity=".35" /><text x={rootX} y="70" textAnchor="middle" fill="#ebe9ff" fontSize="13" fontWeight="800">{t('FRAUD PŘÍPAD', 'FRAUD CASE')}</text><text x={rootX} y="88" textAnchor="middle" fill="#bfc7ff" fontSize="10">{short(network.root.caseId)}</text><title>{network.root.caseId}</title></g>
            {refs.map((ref, index) => <g key={ref.type}><rect x={refX(index) - 91} y="190" width="182" height="70" rx="16" fill="#10283a" stroke="#57d7e6" strokeWidth="1.5" /><text x={refX(index)} y="218" textAnchor="middle" fill="#74e4ee" fontSize="12" fontWeight="800">{referenceLabel(ref.type)}</text><text x={refX(index)} y="239" textAnchor="middle" fill="#d4edf4" fontSize="11">{short(ref.sourceId)}</text><title>{ref.sourceId}</title></g>)}
            {network.related.map((item, index) => <g key={item.evidence.caseId}><rect x={relatedX(index) - 94} y="365" width="188" height="74" rx="16" fill="#24223d" stroke="#f2ba72" strokeWidth="1.5" /><text x={relatedX(index)} y="393" textAnchor="middle" fill="#ffd095" fontSize="12" fontWeight="800">{t('SOUVISEJÍCÍ PŘÍPAD', 'RELATED CASE')}</text><text x={relatedX(index)} y="415" textAnchor="middle" fill="#f6e8d8" fontSize="11">{short(item.evidence.caseId)}</text><title>{item.evidence.caseId}</title></g>)}
          </svg>
        </div>
        {!network.related.length && <p role="status" className={styles.muted}>{t('Mezi prověřenými přidělenými případy nebyla nalezena explicitní shoda. Neznamená to, že žádná další vazba neexistuje.', 'No explicit match was found among the inspected assigned cases. This does not prove that no other connection exists.')}</p>}
        <div className={styles.evidence} aria-label={t('Dostupné případy evidence', 'Available case evidence')}>
          <button type="button" className={styles.evidenceCard} aria-pressed={selected === -1} onClick={() => setSelected(-1)}><strong>{t('Výchozí případ', 'Starting case')}</strong>{short(network.root.caseId)} · {t('revize', 'revision')} {network.root.revision}</button>
          {network.related.map((item, index) => <button type="button" key={item.evidence.caseId} className={styles.evidenceCard} aria-pressed={selected === index} onClick={() => setSelected(index)}><strong>{t('Doložená shoda', 'Evidence match')}</strong>{short(item.evidence.caseId)} · {item.shared.map(ref => referenceLabel(ref.type)).join(', ')}</button>)}
        </div>
        {selectedEvidence && <div className={styles.detail} aria-live="polite"><strong>{t('Zdrojová evidence', 'Source evidence')}</strong><dl><dt>{t('Případ', 'Case')}</dt><dd>{selectedEvidence.caseId}</dd><dt>{t('Skórovací záznam', 'Score record')}</dt><dd>{selectedEvidence.scoreId}</dd><dt>{t('Účet', 'Account')}</dt><dd>{selectedEvidence.accountId}</dd><dt>{t('Protistrana', 'Counterparty')}</dt><dd>{selectedEvidence.counterpartyId ?? '—'}</dd><dt>{t('Otevřeno', 'Opened')}</dt><dd>{selectedEvidence.openedAt}</dd></dl></div>}
        <p className={styles.muted}>{t('Přístup je ověřen pro každý případ zvlášť. Graf zobrazuje nejvýše čtyři kandidáty a může být neúplný; shoda identifikátoru je vodítko, ne zjištění podvodu.', 'Every case is authorized separately. The graph inspects at most four candidates and may be incomplete; an identifier match is a lead, not a fraud finding.')}</p>
      </>}
    </div>
  </section>
}
