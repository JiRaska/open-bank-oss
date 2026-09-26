// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { parseApprovedGuaranteeHistory, type ApprovedGuaranteeHistory } from '@/lib/context/lendingGuarantees'
import { parseSharedGuarantorRelationships, type SharedGuarantorRelationships } from '@/lib/context/sharedGuarantors'
import styles from './LendingGuaranteeView.module.css'

const short = (id: string) => `${id.slice(0, 8)}…${id.slice(-4)}`

export function LendingGuaranteeView({ loanId }: { loanId: string }) {
  const { t, language } = useLanguage()
  const [history, setHistory] = useState<ApprovedGuaranteeHistory | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'denied' | 'unavailable'>('loading')
  const [selected, setSelected] = useState(0)
  const [relationships, setRelationships] = useState<SharedGuarantorRelationships | null>(null)
  const [relationshipStatus, setRelationshipStatus] = useState<'loading' | 'ready' | 'denied' | 'unavailable'>('loading')
  const validLoanId = AUTHORITY_UUID.test(loanId)
  useEffect(() => {
    if (!validLoanId) return
    const controller = new AbortController()
    const load = async () => {
      try {
        const response = await fetch(`/api/context/lending-loans/${loanId.toLowerCase()}/approved-guarantees`, { cache: 'no-store', signal: controller.signal })
        if (controller.signal.aborted) return
        if (response.status === 401 || response.status === 403) { setStatus('denied'); return }
        if (!response.ok) { setStatus('unavailable'); return }
        const result = parseApprovedGuaranteeHistory(await response.json())
        if (result.loanId !== loanId.toLowerCase()) throw new Error('Scope mismatch')
        if (!controller.signal.aborted) { setHistory(result); setStatus('ready') }
      } catch { if (!controller.signal.aborted) setStatus('unavailable') }
    }
    void load()
    return () => controller.abort()
  }, [loanId, validLoanId])

  useEffect(() => {
    if (!validLoanId) return
    const controller = new AbortController()
    const load = async () => {
      try {
        const response = await fetch(`/api/context/lending-loans/${loanId.toLowerCase()}/shared-guarantors`, { cache: 'no-store', signal: controller.signal })
        if (controller.signal.aborted) return
        if (response.status === 401 || response.status === 403) { setRelationshipStatus('denied'); return }
        if (!response.ok) { setRelationshipStatus('unavailable'); return }
        const result = parseSharedGuarantorRelationships(await response.json())
        if (result.rootLoanId !== loanId.toLowerCase()) throw new Error('Scope mismatch')
        if (!controller.signal.aborted) { setRelationships(result); setRelationshipStatus('ready') }
      } catch { if (!controller.signal.aborted) setRelationshipStatus('unavailable') }
    }
    void load()
    return () => controller.abort()
  }, [loanId, validLoanId])

  const selectedFact = history?.guarantees[selected]
  const date = (value: string | null) => value ? new Date(value).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB') : '—'

  return <main className={styles.shell}>
    <Link href="/lending" className={styles.back}>← {t('Zpět do portfolia', 'Back to portfolio')}</Link>
    <div className={styles.eyebrow}>{t('Úvěrové portfolio / zdrojová evidence', 'Lending portfolio / source evidence')}</div>
    <h1>{t('Schválené záruky', 'Approved guarantees')}</h1>
    <p className={styles.intro}>{t('Omezený pohled na doložené záruky přiřazeného úvěru. Spoje ukazují pouze vztahy vrácené zdrojem.', 'A bounded view of documented guarantees for the assigned loan. Connections show only relationships returned by the source.')}</p>
    <div className={styles.scope}><span>{t('Úvěr', 'Loan')}</span><code>{loanId}</code><span className={styles.scopeBadge}>{t('Jeden přiřazený úvěr', 'One assigned loan')}</span></div>
    {validLoanId && status === 'loading' && <p role="status" className={styles.notice}>{t('Ověřuji přístup a načítám evidenci…', 'Checking access and loading evidence…')}</p>}
    {validLoanId && status === 'denied' && <p role="alert" className={styles.error}>{t('K tomuto úvěru nemáte aktuální oprávnění.', 'You do not have current access to this loan.')}</p>}
    {!validLoanId && <p role="alert" className={styles.error}>{t('Neplatné ID úvěru.', 'Invalid loan ID.')}</p>}
    {validLoanId && status === 'unavailable' && <p role="alert" className={styles.error}>{t('Ověřená evidence nyní není dostupná. Zdroj může být vypnutý nebo nedostupný.', 'Verified evidence is unavailable. The source may be disabled or unreachable.')}</p>}
    {validLoanId && history && status === 'ready' && <>
      <div className={styles.meta}>
        <span>{t('Účinné k', 'Effective at')} <strong>{date(history.effectiveAt)}</strong></span>
        <span>{t('Známé k', 'Known at')} <strong>{date(history.knownAt)}</strong></span>
        <span>{t('Vrácené záznamy', 'Returned records')} <strong>{history.guarantees.length}{history.truncated ? '+' : ''}</strong></span>
      </div>
      {history.truncated && <p role="status" className={styles.warning}>{t('Částečný pohled: zdroj vrátil jen omezenou část záruk. Chybějící záznamy nelze považovat za neexistující.', 'Partial view: the source returned only a bounded portion of guarantees. Missing records do not establish absence.')}</p>}
      {history.guarantees.length === 0 ? <p className={styles.notice}>{t('Zdroj nevrátil schválenou záruku pro tento úvěr v daném čase.', 'The source returned no approved guarantee for this loan at this time.')}</p> : <>
        <div className={styles.graph} aria-label={t('Graf doložených vztahů úvěru', 'Graph of documented loan relationships')}>
          <div className={styles.loanNode}><small>{t('PŘIŘAZENÝ ÚVĚR', 'ASSIGNED LOAN')}</small><strong>{short(history.loanId)}</strong></div>
          <div className={styles.branches}>
            {history.guarantees.map((g, index) => <button key={g.guaranteeId} type="button" className={`${styles.branch} ${selected === index ? styles.active : ''}`} onClick={() => setSelected(index)} aria-pressed={selected === index}>
              <span className={styles.line} aria-hidden="true" />
              <span className={styles.guaranteeNode}><small>{t('SCHVÁLENÁ ZÁRUKA', 'APPROVED GUARANTEE')}</small><strong>{short(g.guaranteeId)}</strong><em>{g.capAmount.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')} {g.currency}</em></span>
              <span className={styles.children}><span><small>{t('RUČITEL · ID STRANY', 'GUARANTOR · PARTY ID')}</small><strong>{short(g.guarantorPartyId)}</strong></span><span><small>{t('ZDROJOVÝ DOKUMENT', 'SOURCE DOCUMENT')}</small><strong>{short(g.sourceDocumentId)}</strong></span></span>
            </button>)}
          </div>
        </div>
        {selectedFact && <section className={styles.detail} aria-live="polite"><h2>{t('Důkazní detail', 'Evidence detail')}</h2><dl>
          <dt>{t('Záruka', 'Guarantee')}</dt><dd>{selectedFact.guaranteeId}</dd>
          <dt>{t('Smlouva', 'Contract')}</dt><dd>{selectedFact.contractId}</dd>
          <dt>{t('Revize', 'Revision')}</dt><dd>{selectedFact.revision}</dd>
          <dt>{t('Nahrazuje záruku', 'Supersedes guarantee')}</dt><dd>{selectedFact.supersedesGuaranteeId ?? '—'}</dd>
          <dt>{t('Ručitel · ID strany', 'Guarantor · party ID')}</dt><dd>{selectedFact.guarantorPartyId}</dd>
          <dt>{t('Limit záruky', 'Guarantee cap')}</dt><dd>{selectedFact.capAmount.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')} {selectedFact.currency}</dd>
          <dt>{t('Podíl krytí', 'Coverage fraction')}</dt><dd>{selectedFact.coverageFraction}</dd>
          <dt>{t('Pořadí', 'Seniority')}</dt><dd>{selectedFact.seniority}</dd>
          <dt>{t('Platné od', 'Valid from')}</dt><dd>{date(selectedFact.validFrom)}</dd>
          <dt>{t('Platné do', 'Valid to')}</dt><dd>{date(selectedFact.validTo)}</dd>
          <dt>{t('Rozhodnuto', 'Decided at')}</dt><dd>{date(selectedFact.decidedAt)}</dd>
          <dt>{t('Zdrojový dokument', 'Source document')}</dt><dd>{selectedFact.sourceDocumentId}</dd>
          <dt>SHA-256</dt><dd className={styles.hash}>{selectedFact.sourceSha256}</dd>
        </dl></section>}
      </>}
      <p className={styles.caveat}>{t('Záznamy jsou schválené zdrojové záruky. Graf neodvozuje další úvěry, skutečnou vymahatelnost ani dostupný zůstatek krytí.', 'Records are source-approved guarantees. This graph does not infer other loans, enforceability, or remaining coverage.')}</p>
    </>}
    {validLoanId && <section className={styles.related} aria-labelledby="shared-guarantors-heading">
      <h2 id="shared-guarantors-heading">{t('Úvěry se společným ručitelem', 'Loans with a shared guarantor')}</h2>
      <p className={styles.intro}>{t('Zdrojově doložené vztahy mezi tímto úvěrem a dalšími přiřazenými úvěry. Zobrazené záruky mají společného ručitele; nejde o součet dostupného krytí.', 'Source-backed relationships between this loan and other assigned loans. The shown guarantees share a guarantor; they do not show available combined coverage.')}</p>
      {relationshipStatus === 'loading' && <p role="status" className={styles.notice}>{t('Načítám související úvěry…', 'Loading related loans…')}</p>}
      {relationshipStatus === 'denied' && <p role="alert" className={styles.error}>{t('K souvisejícím úvěrům nemáte aktuální oprávnění.', 'You do not have current access to related loans.')}</p>}
      {relationshipStatus === 'unavailable' && <p role="alert" className={styles.error}>{t('Ověřené vztahy nyní nejsou dostupné. Zdroj může být vypnutý nebo nedostupný.', 'Verified relationships are unavailable. The source may be disabled or unreachable.')}</p>}
      {relationshipStatus === 'ready' && relationships && <>
        <div className={styles.meta}>
          <span>{t('Účinné k', 'Effective at')} <strong>{date(relationships.effectiveAt)}</strong></span>
          <span>{t('Známé k', 'Known at')} <strong>{date(relationships.knownAt)}</strong></span>
          <span>{t('Vrácené související úvěry', 'Returned related loans')} <strong>{relationships.relatedLoans.length}{relationships.relatedLoansTruncated ? '+' : ''}</strong></span>
        </div>
        {relationships.candidateTruncated && <p role="status" className={styles.warning}>{t('Částečný pohled: výběr kandidátních úvěrů byl omezen. Další vztahy mohou chybět.', 'Partial view: candidate loan selection was limited. Other relationships may be missing.')}</p>}
        {relationships.relatedLoansTruncated && <p role="status" className={styles.warning}>{t('Částečný pohled: seznam souvisejících úvěrů byl omezen. Další úvěry mohou chybět.', 'Partial view: the related loan list was limited. Other loans may be missing.')}</p>}
        {relationships.relatedLoans.length === 0 && <p className={styles.notice}>{t('Zdroj nevrátil související přiřazený úvěr v daném čase. Tento výsledek nevylučuje jiné vztahy.', 'The source returned no related assigned loan at this time. This result does not rule out other relationships.')}</p>}
        {relationships.relatedLoans.map(related => <article className={styles.relatedCard} key={related.loanId}>
          <h3>{t('Související přiřazený úvěr', 'Related assigned loan')} <code>{related.loanId}</code></h3>
          <Link href={`/lending/loans/${related.loanId}/guarantees`}>{t('Otevřít evidenci úvěru', 'Open loan evidence')} →</Link>
          {related.truncated && <p role="status" className={styles.warning}>{t('Částečný pohled: záruky tohoto úvěru byly omezeny.', 'Partial view: guarantees for this loan were limited.')}</p>}
          <ul className={styles.relatedEvidence}>{related.guarantees.map(guarantee => <li key={guarantee.guaranteeId}>
            <strong>{t('Záruka', 'Guarantee')} {short(guarantee.guaranteeId)}</strong>
            <span>{t('Ručitel · ID strany', 'Guarantor · party ID')} <code>{guarantee.guarantorPartyId}</code></span>
            <span>{t('Smlouva', 'Contract')} <code>{guarantee.contractId}</code> · {t('Revize', 'Revision')} {guarantee.revision}</span>
            <span>{t('Zdrojový dokument', 'Source document')} <code>{guarantee.sourceDocumentId}</code></span>
            <span>SHA-256 <code className={styles.hash}>{guarantee.sourceSha256}</code></span>
            <span>{t('Limit záruky', 'Guarantee cap')} {guarantee.capAmount.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')} {guarantee.currency} · {t('Podíl krytí', 'Coverage fraction')} {guarantee.coverageFraction}</span>
          </li>)}</ul>
        </article>)}
        <p className={styles.caveat}>{t('Zobrazeny jsou jen vrácené přiřazené úvěry a zdrojové záruky. Chybějící vztahy nelze považovat za neexistující.', 'Only returned assigned loans and source guarantees are shown. Missing relationships do not establish absence.')}</p>
      </>}
    </section>}
  </main>
}
