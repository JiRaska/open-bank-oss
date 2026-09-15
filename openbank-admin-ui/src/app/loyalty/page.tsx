// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Internal marketing workspace (ADR-0282). Catalogue changes remain reviewed code;
// the downloadable brief is a local draft, never a mutation or submission.

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import {
  ArrowRight, Bot, CircleAlert, Coins, FileText, Gift, Landmark, Leaf, Scale, Search, ShieldCheck, Sparkles,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { TableViewport } from '@/components/ui/TableViewport'
import styles from './loyalty.module.css'
import {
  AI_RED_LINES, AI_ROLES, CONNECTIONS, LEGAL, PRINCIPLES, type Bilingual,
} from '@/lib/loyalty/lipaContent'
import type { LoyaltyCatalogueResponse, LoyaltyState } from '@/app/api/loyalty/route'
import type { LoyaltyPartyResponse } from '@/app/api/loyalty/party/[partyId]/route'
import { parseLoyaltyCatalogue, parseLoyaltyParty } from '@/lib/loyalty/evidenceContract'

const TABS = ['overview', 'catalogues', 'party', 'principles', 'finance', 'ai'] as const
type Tab = (typeof TABS)[number]

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const REQUEST_TIMEOUT_MS = 10_000

/** A service state that is not `ok` becomes the shared unavailable panel, never a blank table. */
function unavailableKind(state: LoyaltyState): UnavailableKind | null {
  if (state === 'ok') return null
  if (state === 'not_deployed') return 'not_deployed'
  if (state === 'unauthorized') return 'unauthorized'
  return 'unreachable'
}

export default function LoyaltyPage() {
  const { t, language } = useLanguage()
  const [tab, setTab] = useState<Tab>('overview')
  const [catalogue, setCatalogue] = useState<LoyaltyCatalogueResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [partyId, setPartyId] = useState('')
  const [party, setParty] = useState<LoyaltyPartyResponse | null>(null)
  const [partyLoading, setPartyLoading] = useState(false)
  const [partyError, setPartyError] = useState<string | null>(null)
  const partyRequestId = useRef(0)
  const [refresh, setRefresh] = useState(0)
  const [filter, setFilter] = useState('')
  const [proposal, setProposal] = useState('')

  const say = useCallback((text: Bilingual) => t(text.cs, text.en), [t])
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const num = useCallback((n: number) => n.toLocaleString(locale), [locale])

  useEffect(() => {
    let cancelled = false
    fetch('/api/loyalty', { cache: 'no-store', signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) })
      .then(async r => {
        if (!r.ok) throw new Error('Loyalty catalogue request failed')
        return parseLoyaltyCatalogue(await r.json() as unknown)
      })
      .then(body => { if (!cancelled) setCatalogue(body) })
      .catch(() => { if (!cancelled) setCatalogue({ state: 'unreachable', benefits: [], earnSources: [], provisioning: null }) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [refresh])

  const lookUpParty = useCallback(async () => {
    const requestId = ++partyRequestId.current
    const id = partyId.trim()
    if (!UUID_RE.test(id)) {
      setPartyError(t('Zadejte platné UUID klienta.', 'Enter a valid customer UUID.'))
      setParty(null)
      setPartyLoading(false)
      return
    }
    setPartyError(null)
    setParty(null)
    setPartyLoading(true)
    try {
      const response = await fetch(`/api/loyalty/party/${id}`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      })
      if (!response.ok) throw new Error('Loyalty customer request failed')
      const evidence = parseLoyaltyParty(await response.json() as unknown, id)
      if (partyRequestId.current === requestId) setParty(evidence)
    } catch {
      if (partyRequestId.current === requestId) {
        setParty({ state: 'unreachable', partyId: id, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [] })
      }
    } finally {
      if (partyRequestId.current === requestId) setPartyLoading(false)
    }
  }, [partyId, t])

  const serviceKind = catalogue ? unavailableKind(catalogue.state) : null

  const tabLabel: Record<Tab, string> = {
    overview: t('Přehled', 'Overview'),
    principles: t('Pravidla programu', 'Programme rules'),
    catalogues: t('Katalogy', 'Catalogues'),
    party: t('Klient', 'Customer'),
    finance: t('Finance a právo', 'Finance and law'),
    ai: t('Umělá inteligence', 'AI'),
  }

  return (
    <AuthGuard permission="loyalty:view">
      <div className={styles.workspace}>
        <header className={styles.hero}>
          <div>
            <span className={styles.eyebrow}>{t('Marketing · věrnostní program', 'Marketing · loyalty programme')}</span>
            <h1><Leaf aria-hidden="true" /> {t('Lípa', 'Lípa')}</h1>
            <p>{t('Zdravé finance. Dlouhodobý vztah.', 'Healthy finances. Lasting relationships.')}</p>
            <span>{t('Interní pracoviště pro přehled odměn, podporu klientů a přípravu změn programu.', 'Your internal workspace for rewards, customer support and programme change planning.')}</span>
          </div>
          <div className={styles.heroAside}>
            <span className={styles.badge}>{t('Pro marketingový tým', 'For the marketing team')}</span>
            <p>{t('Odměňujeme finanční zdraví', 'Rewarding financial wellbeing')}</p>
            <span>{t('Lístky za dobré návyky, benefity od banky.', 'Lístky for good habits. Benefits from the bank.')}</span>
          </div>
        </header>

        <nav className={styles.tabs} aria-label={t('Sekce Lípy', 'Lípa sections')}>
          {TABS.map(id => (
            <button key={id} type="button" onClick={() => setTab(id)} aria-current={tab === id ? 'page' : undefined}>
              {tabLabel[id]}
            </button>
          ))}
        </nav>

        {tab === 'overview' && (
          <section className={styles.section} aria-label={tabLabel.overview}>
            <div className={styles.sectionHeading}>
              <div><span className={styles.eyebrow}>{t('Pracovní přehled', 'Workspace overview')}</span>
                <h2>{t('Vše důležité pro práci s Lípou', 'Your programme at a glance')}</h2>
                <p>{t('Nejdřív ověřte nabídku a připravenost. Potom připravte komunikaci.', 'Check the offer and readiness before preparing communications.')}</p>
              </div>
              <span className={styles.badge} role="status">{loading ? t('Ověřuji data…', 'Checking data…') : serviceKind ? t('Živá data nedostupná', 'Live data unavailable') : t('Data načtena', 'Data loaded')}</span>
            </div>
            <div className={styles.summaryGrid}>
              {[
                { label: t('Benefity v katalogu', 'Catalogue benefits'), value: !loading && catalogue?.state === 'ok' ? num(catalogue.benefits.length) : '—' },
                { label: t('Způsoby získání Lístků', 'Ways to earn Lístky'), value: !loading && catalogue?.state === 'ok' ? num(catalogue.earnSources.length) : '—' },
                { label: t('Roční strop na klienta', 'Annual cap per customer'), value: !loading && catalogue?.state === 'ok' && catalogue.provisioning ? num(catalogue.provisioning.annualCapPerParty) : '—' },
              ].map(stat => <div key={stat.label} className={styles.metric}><span>{stat.label}</span><strong>{stat.value}</strong></div>)}
            </div>
            <div className={styles.actionGrid}>
              <button onClick={() => setTab('catalogues')} className={styles.actionCard}><Gift aria-hidden="true" /><h3>{t('Prozkoumat nabídku', 'Explore rewards')}</h3><p>{t('Ověřte ceny benefitů, platnost a způsoby získání Lístků. Připravte podklady pro změnu.', 'Review benefit prices, validity and earning rules. Prepare a change brief.')}</p><span>{t('Otevřít katalogy', 'Open catalogues')} <ArrowRight aria-hidden="true" /></span></button>
              <button onClick={() => setTab('party')} className={styles.actionCard}><Search aria-hidden="true" /><h3>{t('Prověřit konkrétní účet', 'Inspect an account')}</h3><p>{t('Dohledejte zůstatek, pohyby a expiraci pro řešení dotazu nebo kontrolu komunikace.', 'Look up balances, activity and expiry to resolve a query or check a message.')}</p><span>{t('Přejít na vyhledávání', 'Go to lookup')} <ArrowRight aria-hidden="true" /></span></button>
            </div>
            <div className={styles.notice}>
              <ShieldCheck aria-hidden="true" /><div><h3>{t('Před komunikací klientům', 'Before communicating to customers')}</h3>
              <p>{t('Záznam v katalogu není potvrzení dostupnosti. Udělený benefit znamená závazek banky, nikoli potvrzené plnění. Právní posouzení a otevřené podmínky ověřte s odpovědným útvarem.', 'A catalogue entry does not confirm availability. A granted benefit is a bank obligation, not confirmed fulfilment. Check the legal review and open conditions with the responsible team.')}</p>
              <button className={styles.textButton} onClick={() => setTab('finance')}>{t('Zobrazit podmínky a otevřené body', 'Review conditions and open items')} <ArrowRight aria-hidden="true" /></button></div>
            </div>
          </section>
        )}

        {tab === 'principles' && (
          <section className={styles.section} aria-label={tabLabel.principles}>
            <div className={styles.card}>
              <h2 className="flex items-center gap-2 text-base font-semibold text-[var(--success-text)]">
                <Leaf className="h-4 w-4" />
                {t('Co je Lístek', 'What a Lístek is')}
              </h2>
              <p className="mt-2 max-w-3xl text-sm leading-relaxed text-[var(--success-text)]">
                {t(
                  'Lístek je uzavřená jednotka závazku banky. Klient ho získá za doložené finanční zdraví, vymění za benefit, který banka sama doručí, a po dvou letech mu propadne. Není to měna ani platební prostředek a záměrně se jím nikdy nestane.',
                  'A Lístek is a closed-loop unit of bank obligation. A customer earns it for evidenced financial health, redeems it for a benefit the bank itself delivers, and it expires after two years. It is not a currency or a means of payment, and by design it never becomes one.',
                )}
              </p>
            </div>

            <div className={styles.actionGrid}>
              {PRINCIPLES.map(p => (
                <article key={p.id} className={styles.card}>
                  <h3 className="flex items-center gap-2 text-sm font-semibold text-[var(--text-primary)]">
                    <ShieldCheck className="h-4 w-4 text-[var(--success-text)]" />
                    {say(p.title)}
                  </h3>
                  <p className="mt-2 text-sm text-[var(--text-secondary)]">{say(p.rule)}</p>
                  <details className={styles.details}><summary>{t('Souvislosti a omezení', 'Context and limitations')}</summary>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-[var(--text-tertiary)]">
                    {t('Proč', 'Why')}
                  </p>
                  <p className="text-sm text-[var(--text-secondary)]">{say(p.why)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-[var(--danger-text)]">
                    {t('Co by to porušilo', 'What would break it')}
                  </p>
                  <p className="text-sm text-[var(--text-secondary)]">{say(p.breaks)}</p></details>
                </article>
              ))}
            </div>

            <div className={styles.card}>
              <h2 className="text-base font-semibold text-[var(--text-primary)]">
                {t('Životní cyklus Lístku', 'The life of a Lístek')}
              </h2>
              <p className="mt-1 text-sm text-[var(--text-secondary)]">
                {t(
                  'Všimněte si dvou konců, které nejsou chyba: strop nic nezapíše a nedostatek Lístků nic neodepíše. Obojí je legitimní odpověď, ne selhání.',
                  'Note the two endings that are not errors: the cap writes nothing, and an unaffordable redemption burns nothing. Both are legitimate answers, not faults.',
                )}
              </p>
              <ol className={styles.lifecycle}>
                {[
                  [t('Získání', 'Earn'), t('Za doložený zdravý návyk, do ročního stropu.', 'For an evidenced healthy habit, within the annual cap.')],
                  [t('Výběr benefitu', 'Choose a benefit'), t('Klient vybírá z katalogu podle zůstatku Lístků.', 'The customer chooses from the catalogue within their balance.')],
                  [t('Udělení', 'Grant'), t('Odečtou se nejstarší Lístky. Banka eviduje závazek.', 'Oldest Lístky are used first. The bank records an obligation.')],
                  [t('Plnění', 'Fulfilment'), t('Zajišťuje příslušná služba. Udělení samo plnění nepotvrzuje.', 'Handled by the responsible service. A grant alone does not confirm fulfilment.')],
                ].map(([title, description], index) => <li key={title}><span>{index + 1}</span><h3>{title}</h3><p>{description}</p></li>)}
              </ol>
              <p>{t('Nevyužité Lístky expirují po dvou letech.', 'Unused Lístky expire after two years.')}</p>
            </div>

            <div className={styles.card}>
              <h2 className="text-base font-semibold text-[var(--text-primary)]">
                {t('Jak Lípa souvisí se zbytkem platformy', 'How Lípa connects to the rest of the platform')}
              </h2>
              <ul className={styles.list}>
                {CONNECTIONS.map(c => (
                  <li key={c.id} className={styles.inset}>
                    <div className="flex items-center justify-between gap-3">
                      <h3 className="text-sm font-semibold text-[var(--text-primary)]">{say(c.system)}</h3>
                      {c.href && (
                        <Link href={c.href} className="inline-flex items-center gap-1 text-xs font-semibold text-[var(--accent-text)] hover:underline">
                          {t('Otevřít', 'Open')}<ArrowRight className="h-3 w-3" />
                        </Link>
                      )}
                    </div>
                    <p className="mt-1 text-sm text-[var(--text-secondary)]">{say(c.what)}</p>
                    <p className="mt-1 text-sm text-[var(--text-tertiary)]">
                      <span className="font-semibold">{t('Hranice: ', 'Boundary: ')}</span>{say(c.limit)}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          </section>
        )}

        {tab === 'catalogues' && (
          <section className={styles.section} aria-label={tabLabel.catalogues}>
            <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>{t('Nabídka programu', 'Programme offer')}</span><h2>{t('Odměny a benefity', 'Earning and rewards')}</h2></div></div>
            <div className={styles.notice} role="note">
              <FileText className="mt-0.5 h-4 w-4 shrink-0 text-[var(--warning-text)]" />
              <p className="text-sm text-[var(--warning-text)]">
                {t(
                  'Aktuální katalogy slouží jako podklad pro marketing. Změnu nabídky připravte jako návrh níže; před zavedením musí projít odbornou revizí a schválením.',
                  'Use the current catalogues to plan marketing. Prepare an offer change using the brief below; changes require expert review and approval before implementation.',
                )}
              </p>
            </div>

            {loading && <p className="text-sm text-[var(--text-tertiary)]">{t('Načítám…', 'Loading…')}</p>}
            {!loading && serviceKind && (
              <DataUnavailable
                kind={serviceKind}
                service="Loyalty-service"
                feature={t('Katalogy Lípy', 'Lípa catalogues')}
                lang={language === 'cs' ? 'cs' : 'en'}
                detail={t('Aktuální nabídku nelze ověřit. Zkuste načtení znovu; pokud problém trvá, obraťte se na podporu s názvem sekce Lípa. Podklady pro změnu můžete připravit níže.', 'The current offer could not be verified. Retry loading; if the problem persists, contact support and mention the Lípa section. You can still prepare a change brief below.')}
              ><button className={styles.primaryButton} onClick={() => { setLoading(true); setRefresh(value => value + 1) }}>{t('Zkusit znovu', 'Try again')}</button></DataUnavailable>
            )}

            {!loading && !serviceKind && catalogue && (
              <>
                <label className={styles.filter}>{t('Hledat v katalozích', 'Search catalogues')}<input type="search" value={filter} onChange={e => setFilter(e.target.value)} placeholder={t('Název nebo popis…', 'Name or description…')} /></label>
                <div className={styles.card}>
                  <h2 className="flex items-center gap-2 text-base font-semibold text-[var(--text-primary)]">
                    <Coins className="h-4 w-4 text-[var(--success-text)]" />
                    {t('Za co se Lístky získávají', 'What earns Lístky')}
                  </h2>
                  <p className="mt-1 text-sm text-[var(--text-secondary)]">
                    {t(
                      'Katalog uvádí pravidla odměňování. Před kampaní ověřte s vlastníkem programu, že se daná aktivita skutečně vyhodnocuje a odměňuje.',
                      'The catalogue lists earning rules. Before a campaign, confirm with the programme owner that the activity is actually tracked and rewarded.',
                    )}
                  </p>
                  <TableViewport
                    label={t('Posuvná tabulka pravidel získávání Lístků', 'Scrollable Lípa earning rules table')}
                    hint={t('Posuňte tabulku vodorovně pro počet Lístků a dobu platnosti.', 'Scroll horizontally to see the Lístky amount and validity.')}
                  >
                  <table className="mt-4 w-full text-sm">
                    <thead>
                      <tr className="border-b border-[var(--border)] text-left text-xs uppercase tracking-wide text-[var(--text-tertiary)]">
                        <th className="py-2">{t('Důvod', 'Source')}</th>
                        <th className="py-2 text-right">{t('Lístků', 'Lístky')}</th>
                        <th className="py-2 text-right">{t('Platnost', 'Validity')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {catalogue.earnSources.filter(s => s.id.toLowerCase().includes(filter.toLowerCase())).map(s => (
                        <tr key={s.id} className="border-b border-[var(--border)] last:border-none">
                          <td className="py-2 font-medium text-[var(--text-primary)]">{s.id}</td>
                          <td className="py-2 text-right tabular-nums">{num(s.leaves)}</td>
                          <td className="py-2 text-right text-[var(--text-secondary)]">
                            {t(`${num(s.validityDays)} dní`, `${num(s.validityDays)} days`)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  </TableViewport>
                  {catalogue.earnSources.filter(s => s.id.toLowerCase().includes(filter.toLowerCase())).length === 0 && <p role="status">{t('Žádné odpovídající způsoby získání.', 'No matching earning sources.')}</p>}
                </div>

                <div className={styles.card}>
                  <h2 className="flex items-center gap-2 text-base font-semibold text-[var(--text-primary)]">
                    <Gift className="h-4 w-4 text-[var(--accent-text)]" />
                    {t('Za co se Lístky vyměňují', 'What Lístky buy')}
                  </h2>
                  <p className="mt-1 text-sm text-[var(--text-secondary)]">
                    {t(
                      'Cena je uvedena v Lístcích. Příslušný tým musí potvrdit připravenost plnění; Lípa eviduje udělení benefitu, sama jej neposkytuje.',
                      'Prices are in Lístky. The responsible team must confirm fulfilment readiness; Lípa records a benefit grant but does not fulfil it itself.',
                    )}
                  </p>
                  <TableViewport
                    label={t('Posuvná tabulka benefitů Lípy', 'Scrollable Lípa benefits table')}
                    hint={t('Posuňte tabulku vodorovně pro vlastníka plnění, cenu a platnost.', 'Scroll horizontally to see fulfilment owner, price, and validity.')}
                  >
                  <table className="mt-4 w-full text-sm">
                    <thead>
                      <tr className="border-b border-[var(--border)] text-left text-xs uppercase tracking-wide text-[var(--text-tertiary)]">
                        <th className="py-2">{t('Benefit', 'Benefit')}</th>
                        <th className="py-2">{t('Zajišťuje', 'Handled by')}</th>
                        <th className="py-2 text-right">{t('Cena', 'Price')}</th>
                        <th className="py-2 text-right">{t('Platnost', 'Validity')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {catalogue.benefits.filter(b => `${b.id} ${b.description} ${b.engine}`.toLowerCase().includes(filter.toLowerCase())).map(b => (
                        <tr key={b.id} className="border-b border-[var(--border)] last:border-none align-top">
                          <td className="py-2">
                            <span className="font-medium text-[var(--text-primary)]">{b.id}</span>
                            <span className="block text-xs text-[var(--text-tertiary)]">{b.description}</span>
                          </td>
                          <td className="py-2 text-[var(--text-secondary)]">{b.engine}</td>
                          <td className="py-2 text-right tabular-nums">{num(b.priceLeaves)}</td>
                          <td className="py-2 text-right text-[var(--text-secondary)]">
                            {t(`${num(b.validityDays)} dní`, `${num(b.validityDays)} days`)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  </TableViewport>
                  {catalogue.benefits.filter(b => `${b.id} ${b.description} ${b.engine}`.toLowerCase().includes(filter.toLowerCase())).length === 0 && <p role="status">{t('Žádné odpovídající benefity.', 'No matching benefits.')}</p>}
                </div>
              </>
            )}
            <div className={styles.card}>
              <span className={styles.eyebrow}>{t('Příprava změny', 'Change planning')}</span>
              <h2>{t('Připravit zadání pro schválení', 'Prepare a change brief')}</h2>
              <p>{t('Popište cílovou skupinu, zamýšlený benefit, důvod změny a očekávaný dopad. Stažený návrh předejte ke schválení běžným interním postupem; stažením se nic neodesílá ani nemění.', 'Describe the audience, proposed benefit, rationale and expected impact. Submit the downloaded brief through your internal approval process; downloading does not submit or change anything.')}</p>
              <label className={styles.filter} htmlFor="lipa-proposal">{t('Zadání změny (bez osobních údajů klientů)', 'Change brief (no customer personal data)')}</label>
              <textarea id="lipa-proposal" rows={5} value={proposal} onChange={e => setProposal(e.target.value)} />
              <button className={styles.primaryButton} disabled={!proposal.trim()} onClick={() => {
                const url = URL.createObjectURL(new Blob([t('Lípa — návrh změny ke schválení', 'Lípa — change proposal for approval') + '\n\n' + proposal], { type: 'text/plain;charset=utf-8' }))
                const link = document.createElement('a')
                link.href = url
                link.download = 'lipa-proposal.txt'
                link.click()
                setTimeout(() => URL.revokeObjectURL(url), 1000)
              }}>{t('Stáhnout zadání', 'Download brief')}</button>
            </div>
          </section>
        )}

        {tab === 'party' && (
          <section className={styles.section} aria-label={tabLabel.party}>
            <div className={styles.card}>
              <h2 className="text-base font-semibold text-[var(--text-primary)]">{t('Zůstatek klienta', 'A customer balance')}</h2>
              <p className="mt-1 max-w-3xl text-sm text-[var(--text-secondary)]">
                {t(
                  'Ověřte stav programu u konkrétního klienta. Zůstatek, historie a expirace vycházejí ze stejných údajů jako klientská aplikace. Pro širší kontext otevřete Customer 360.',
                  'Check programme activity for an individual customer. Balance, history and expiry use the same evidence as the customer app. Open Customer 360 for the broader context.',
                )}
              </p>
              <div className={styles.lookup}>
                <label htmlFor="lipa-party" className="sr-only">{t('UUID klienta', 'Customer UUID')}</label>
                <input
                  id="lipa-party"
                  value={partyId}
                  onChange={e => setPartyId(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter' && !partyLoading) void lookUpParty() }}
                  placeholder={t('UUID klienta', 'Customer UUID')}
                  className="w-96 max-w-full rounded-xl border border-[var(--border-strong)] px-3 py-2 font-mono text-sm"
                />
                <button
                  type="button"
                  onClick={() => void lookUpParty()}
                  disabled={partyLoading}
                  aria-busy={partyLoading}
                  className="inline-flex items-center gap-2 rounded-xl bg-[var(--accent-strong)] px-4 py-2 text-sm font-semibold text-[var(--text-inverse)] transition hover:bg-[var(--accent-hover)]"
                >
                  <Search className="h-4 w-4" />
                  {t('Vyhledat', 'Look up')}
                </button>
                {party && (
                  <Link href={`/customer-360?partyId=${party.partyId}`} className="inline-flex items-center gap-1 text-sm font-semibold text-[var(--accent-text)] hover:underline">
                    {t('Otevřít Customer 360', 'Open Customer 360')}<ArrowRight className="h-3 w-3" />
                  </Link>
                )}
              </div>
              {partyError && <p role="alert" className="mt-2 text-sm text-[var(--danger-text)]">{partyError}</p>}
            </div>

            {!party && !partyLoading && !partyError && <div className={styles.notice}><Search aria-hidden="true" /><div><h3>{t('Začněte identifikátorem klienta', 'Start with a customer ID')}</h3><p>{t('UUID najdete v detailu klienta. Vyhledávání zobrazí skutečný zůstatek a historii; neprovádí žádné změny.', 'Find the UUID in the customer record. Lookup shows the actual balance and history without changing them.')}</p><Link href="/customer-360">{t('Přejít do Customer 360', 'Go to Customer 360')} →</Link></div></div>}
            {partyLoading && <p className="text-sm text-[var(--text-tertiary)]">{t('Načítám…', 'Loading…')}</p>}

            {party && unavailableKind(party.state) && (
              <DataUnavailable
                kind={unavailableKind(party.state) as UnavailableKind}
                service="Loyalty-service"
                feature={t('Zůstatek Lístků', 'Lístek balance')}
                lang={language === 'cs' ? 'cs' : 'en'}
              />
            )}

            {party && !unavailableKind(party.state) && (
              <>
                <div className="grid gap-4 sm:grid-cols-4">
                  {[
                    { label: t('Zůstatek', 'Balance'), value: num(party.balance) },
                    { label: t('Získáno letos', 'Earned this year'), value: num(party.earnedThisYear) },
                    { label: t('Získáno celkem', 'Earned in total'), value: num(party.earnedTotal) },
                    {
                      label: t('Nejbližší expirace', 'Next expiry'),
                      value: party.nextExpiry ? new Date(party.nextExpiry).toLocaleDateString(locale) : t('žádná', 'none'),
                    },
                  ].map(stat => (
                    <div key={stat.label} className={styles.inset}>
                      <p className="text-xs uppercase tracking-wide text-[var(--text-tertiary)]">{stat.label}</p>
                      <p className={styles.metricValue}>{stat.value}</p>
                    </div>
                  ))}
                </div>

                <div className={styles.card}>
                  <h3 className="text-sm font-semibold text-[var(--text-primary)]">{t('Historie', 'History')}</h3>
                  {party.history.length === 0 && (
                    <p className="mt-2 text-sm text-[var(--text-tertiary)]">
                      {t('Tento klient zatím nemá žádný pohyb.', 'This customer has no entries yet.')}
                    </p>
                  )}
                  {party.history.length > 0 && (
                    <TableViewport
                      label={t('Posuvná tabulka historie Lístků klienta', 'Scrollable customer Lípa history table')}
                      hint={t('Posuňte tabulku vodorovně pro zůstatek a expiraci každého pohybu.', 'Scroll horizontally to see balance and expiry for every entry.')}
                    >
                    <table className="mt-3 w-full text-sm">
                      <thead>
                        <tr className="border-b border-[var(--border)] text-left text-xs uppercase tracking-wide text-[var(--text-tertiary)]">
                          <th className="py-2">{t('Kdy', 'When')}</th>
                          <th className="py-2">{t('Typ', 'Type')}</th>
                          <th className="py-2">{t('Důvod', 'Reason')}</th>
                          <th className="py-2 text-right">{t('Lístků', 'Lístky')}</th>
                          <th className="py-2 text-right">{t('Zbývá', 'Remaining')}</th>
                          <th className="py-2 text-right">{t('Vyprší', 'Expires')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {party.history.map(row => (
                          <tr key={row.id} className="border-b border-[var(--border)] last:border-none">
                            <td className="py-2 text-[var(--text-secondary)]">{new Date(row.occurredAt).toLocaleString(locale)}</td>
                            <td className="py-2 font-medium text-[var(--text-primary)]">{row.type}</td>
                            <td className="py-2 text-[var(--text-secondary)]">{row.earnSourceId ?? row.benefitId ?? '—'}</td>
                            <td className="py-2 text-right tabular-nums">{num(row.leaves)}</td>
                            <td className="py-2 text-right tabular-nums">{num(row.remainingLeaves)}</td>
                            <td className="py-2 text-right text-[var(--text-secondary)]">
                              {row.expiresAt ? new Date(row.expiresAt).toLocaleDateString(locale) : '—'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    </TableViewport>
                  )}
                </div>
              </>
            )}
          </section>
        )}

        {tab === 'finance' && (
          <section className={styles.section} aria-label={tabLabel.finance}>
            <div className={styles.card}>
              <h2 className="flex items-center gap-2 text-base font-semibold text-[var(--text-primary)]">
                <Landmark className="h-4 w-4 text-[var(--text-secondary)]" />
                {t('Závazek banky', 'What the bank owes')}
              </h2>
              <p className="mt-1 max-w-3xl text-sm text-[var(--text-secondary)]">
                {t(
                  'Nespotřebované Lístky jsou závazek. Číslo níže je vstup do denního zaúčtování rezervy, ne zaúčtování samo — to vlastní billing, protože ten je na peněžní cestě a Lípa ne.',
                  'Unspent Lístky are an obligation. The figure below is the input to the daily provisioning journal, not the journal — billing owns that, because billing is on the money path and Lípa is not.',
                )}
              </p>
              {!loading && catalogue?.state === 'ok' && catalogue.provisioning ? (
                <div className={styles.summaryGrid}>
                  <div className={styles.inset}>
                    <p className="text-xs uppercase tracking-wide text-[var(--text-tertiary)]">
                      {t('Nesplacený závazek', 'Outstanding obligation')}
                    </p>
                    <p className={styles.metricValue}>
                      {num(catalogue.provisioning.outstandingLeaves)}
                    </p>
                    <p className="text-xs text-[var(--text-tertiary)]">
                      {t('Lístků, nikoli korun. Lípa Lístek neoceňuje.', 'Lístky, not korunas. Lípa does not price a Lístek.')}
                    </p>
                  </div>
                  <div className={styles.inset}>
                    <p className="text-xs uppercase tracking-wide text-[var(--text-tertiary)]">
                      {t('Roční strop na klienta', 'Annual cap per customer')}
                    </p>
                    <p className={styles.metricValue}>
                      {num(catalogue.provisioning.annualCapPerParty)}
                    </p>
                    <p className="text-xs text-[var(--text-tertiary)]">
                      {t('Ohraničuje ekonomickou expozici programu.', 'It bounds the economic exposure of the programme.')}
                    </p>
                  </div>
                  <div className={styles.inset}>
                    <p className="text-xs uppercase tracking-wide text-[var(--text-tertiary)]">
                      {t('Verze pravidla', 'Rule version')}
                    </p>
                    <p className={styles.metricValue}>{catalogue.provisioning.ruleVersion}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">
                      {t('Zmrazí se na každém zápisu, takže změna sazby historii nepřepíše.', 'Frozen onto every entry, so changing a rate never rewrites history.')}
                    </p>
                  </div>
                </div>
              ) : (
                <p className="mt-3 text-sm text-[var(--text-tertiary)]">
                  {loading ? t('Načítám údaje…', 'Loading figures…') : t('Údaje o závazku nejsou dostupné.', 'Obligation figures are unavailable.')}
                  {!loading && <button className={styles.textButton} onClick={() => { setLoading(true); setRefresh(value => value + 1) }}>{t('Zkusit znovu', 'Try again')}</button>}
                </p>
              )}
            </div>

            <div className={styles.card}>
              <h2 className="flex items-center gap-2 text-base font-semibold text-[var(--text-primary)]">
                <Scale className="h-4 w-4 text-[var(--text-secondary)]" />
                {t('Právní rámec', 'The legal position')}
              </h2>
              <ul className={styles.list}>
                {LEGAL.map(item => (
                  <li key={item.id} className={styles.inset}>
                    <h3 className="text-sm font-semibold text-[var(--text-primary)]">{say(item.regime)}</h3>
                    <p className="mt-1 text-sm text-[var(--text-primary)]">{say(item.position)}</p>
                    <p className="mt-1 text-sm text-[var(--text-secondary)]">
                      <span className="font-semibold">{t('Na čem to stojí: ', 'What holds it: ')}</span>{say(item.holds)}
                    </p>
                    {item.open && (
                      <p className="mt-1 flex items-start gap-1.5 text-sm text-[var(--warning-text)]">
                        <CircleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                        <span><span className="font-semibold">{t('Otevřené: ', 'Still open: ')}</span>{say(item.open)}</span>
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          </section>
        )}

        {tab === 'ai' && (
          <section className={styles.section} aria-label={tabLabel.ai}>
            <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>{t('Rozvoj programu', 'Programme development')}</span><h2>{t('Asistenti pro marketing', 'Marketing assistants')}</h2></div><span className={styles.badge}>{t('Plánované možnosti · nejsou aktivní', 'Planned capabilities · not active')}</span></div>
            <div className={styles.card}>
              <h2 className="flex items-center gap-2 text-base font-semibold text-[var(--accent-text)]">
                <Sparkles className="h-4 w-4" />
                {t('Kde umělá inteligence pomáhá a kde končí', 'Where AI helps and where it stops')}
              </h2>
              <ul className="mt-3 space-y-1.5">
                {AI_RED_LINES.map(line => (
                  <li key={line.en} className="flex items-start gap-2 text-sm text-[var(--accent-text)]">
                    <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-[var(--accent-text)]" />
                    {say(line)}
                  </li>
                ))}
              </ul>
            </div>

            <div className={styles.actionGrid}>
              {AI_ROLES.map(role => (
                <article key={role.id} className={styles.card}>
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="flex items-center gap-2 text-sm font-semibold text-[var(--text-primary)]">
                      <Bot className="h-4 w-4 text-[var(--accent-text)]" />
                      {say(role.name)}
                    </h3>
                    <span className="rounded-full border border-[var(--border)] bg-[var(--surface-2)] px-2 py-0.5 text-xs font-semibold text-[var(--text-secondary)]">
                      {role.status === 'available' ? t('dostupné', 'available') : t('návrh', 'proposed')}
                    </span>
                  </div>
                  <p className="mt-2 text-sm text-[var(--text-secondary)]">{say(role.does)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-[var(--danger-text)]">
                    {t('Nesmí', 'Cannot')}
                  </p>
                  <p className="text-sm text-[var(--text-secondary)]">{say(role.cannot)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-[var(--text-tertiary)]">
                    {t('Rozhoduje', 'Decides instead')}
                  </p>
                  <p className="text-sm text-[var(--text-secondary)]">{say(role.decides)}</p>
                </article>
              ))}
            </div>

            <p className="text-sm text-[var(--text-tertiary)]">
              {t(
                'Všechny čtyři role jsou zatím návrh. Žádná z nich není v systému zapojená a tahle stránka to nezastírá — role označená jako návrh nic nedělá.',
                'All four roles are proposals. None is wired into the system, and this page does not obscure that — a role marked as proposed does nothing.',
              )}
            </p>
          </section>
        )}
      </div>
    </AuthGuard>
  )
}
