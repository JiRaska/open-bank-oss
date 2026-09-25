// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AUTHORITY_UUID } from '@/lib/context/authorityHistory'
import { parseApprovedGuaranteeHistory, type ApprovedGuaranteeHistory } from '@/lib/context/lendingGuarantees'
import styles from './LendingGuaranteeView.module.css'

const short = (id: string) => `${id.slice(0, 8)}…${id.slice(-4)}`

export function LendingGuaranteeView({ loanId }: { loanId: string }) {
  const { t, language } = useLanguage()
  const [history, setHistory] = useState<ApprovedGuaranteeHistory | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'denied' | 'unavailable'>('loading')
  const [selected, setSelected] = useState(0)
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
  </main>
}
