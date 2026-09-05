// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// The Lípa console (ADR-0282). One workspace that teaches the programme and administers it,
// because the two cannot usefully be separated here: almost every control in it is governed
// somewhere else, and an operator who does not know why cannot tell a refusal from a fault.
//
// What "administration" means here, precisely. The earn and benefit catalogues are code, reviewed
// in a pull request — the same discipline segments and campaign templates already use. So this page
// does not edit them and does not pretend to: it shows what is in force, and its change action
// produces a reviewable draft. The editable surface is deliberately empty, and that is the lesson,
// not a gap.

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import {
  ArrowRight, Bot, CircleAlert, Coins, FileText, Gift, Landmark, Leaf, Scale, Search, ShieldCheck, Sparkles,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { Mermaid } from '@/components/docs/Mermaid'
import {
  AI_RED_LINES, AI_ROLES, CONNECTIONS, LEGAL, LIFECYCLE_DIAGRAM, PRINCIPLES, type Bilingual,
} from '@/lib/loyalty/lipaContent'
import type { LoyaltyCatalogueResponse, LoyaltyState } from '@/app/api/loyalty/route'
import type { LoyaltyPartyResponse } from '@/app/api/loyalty/party/[partyId]/route'

const TABS = ['principles', 'catalogues', 'party', 'finance', 'ai'] as const
type Tab = (typeof TABS)[number]

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** A service state that is not `ok` becomes the shared unavailable panel, never a blank table. */
function unavailableKind(state: LoyaltyState): UnavailableKind | null {
  if (state === 'ok') return null
  if (state === 'not_deployed') return 'not_deployed'
  if (state === 'unauthorized') return 'unauthorized'
  return 'unreachable'
}

export default function LoyaltyPage() {
  const { t, language } = useLanguage()
  const [tab, setTab] = useState<Tab>('principles')
  const [catalogue, setCatalogue] = useState<LoyaltyCatalogueResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [partyId, setPartyId] = useState('')
  const [party, setParty] = useState<LoyaltyPartyResponse | null>(null)
  const [partyLoading, setPartyLoading] = useState(false)
  const [partyError, setPartyError] = useState<string | null>(null)

  const say = useCallback((text: Bilingual) => t(text.cs, text.en), [t])
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const num = useCallback((n: number) => n.toLocaleString(locale), [locale])

  useEffect(() => {
    let cancelled = false
    fetch('/api/loyalty', { cache: 'no-store' })
      .then(r => r.json() as Promise<LoyaltyCatalogueResponse>)
      .then(body => { if (!cancelled) setCatalogue(body) })
      .catch(() => { if (!cancelled) setCatalogue({ state: 'unreachable', benefits: [], earnSources: [], provisioning: null }) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  const lookUpParty = useCallback(async () => {
    const id = partyId.trim()
    if (!UUID_RE.test(id)) {
      setPartyError(t('Zadejte platné UUID klienta.', 'Enter a valid customer UUID.'))
      setParty(null)
      return
    }
    setPartyError(null)
    setPartyLoading(true)
    try {
      const response = await fetch(`/api/loyalty/party/${id}`, { cache: 'no-store' })
      setParty(await response.json() as LoyaltyPartyResponse)
    } catch {
      setParty({ state: 'unreachable', partyId: id, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [] })
    } finally {
      setPartyLoading(false)
    }
  }, [partyId, t])

  const serviceKind = catalogue ? unavailableKind(catalogue.state) : null

  const tabLabel: Record<Tab, string> = {
    principles: t('Principy', 'Principles'),
    catalogues: t('Katalogy', 'Catalogues'),
    party: t('Klient', 'Customer'),
    finance: t('Finance a právo', 'Finance and law'),
    ai: t('Umělá inteligence', 'AI'),
  }

  return (
    <AuthGuard permission="loyalty:view">
      <div className="space-y-6">
        <PageHeader
          title={t('Lípa — věrnostní program', 'Lípa — the loyalty programme')}
          subtitle={t(
            'Lístky se získávají za finanční zdraví, ne za útratu. Tahle sekce vysvětluje proč a ukazuje, co je právě v platnosti.',
            'Lístky are earned for financial health, not for spending. This section explains why, and shows what is in force.',
          )}
          icon={<Leaf className="h-6 w-6" />}
        />

        <nav className="flex flex-wrap gap-2" aria-label={t('Sekce Lípy', 'Lípa sections')}>
          {TABS.map(id => (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id)}
              aria-current={tab === id ? 'page' : undefined}
              className={tab === id
                ? 'rounded-xl bg-slate-900 px-4 py-2 text-sm font-semibold text-white'
                : 'rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:border-violet-300 hover:text-violet-700'}
            >
              {tabLabel[id]}
            </button>
          ))}
        </nav>

        {tab === 'principles' && (
          <section className="space-y-6" aria-label={tabLabel.principles}>
            <div className="rounded-2xl border border-emerald-200 bg-emerald-50/60 p-5">
              <h2 className="flex items-center gap-2 text-base font-semibold text-emerald-900">
                <Leaf className="h-4 w-4" />
                {t('Co je Lístek', 'What a Lístek is')}
              </h2>
              <p className="mt-2 max-w-3xl text-sm leading-relaxed text-emerald-950">
                {t(
                  'Lístek je uzavřená jednotka závazku banky. Klient ho získá za doložené finanční zdraví, vymění za benefit, který banka sama doručí, a po dvou letech mu propadne. Není to měna ani platební prostředek a záměrně se jím nikdy nestane.',
                  'A Lístek is a closed-loop unit of bank obligation. A customer earns it for evidenced financial health, redeems it for a benefit the bank itself delivers, and it expires after two years. It is not a currency or a means of payment, and by design it never becomes one.',
                )}
              </p>
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              {PRINCIPLES.map(p => (
                <article key={p.id} className="rounded-2xl border border-slate-200 bg-white p-5">
                  <h3 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                    <ShieldCheck className="h-4 w-4 text-emerald-600" />
                    {say(p.title)}
                  </h3>
                  <p className="mt-2 text-sm text-slate-700">{say(p.rule)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-slate-500">
                    {t('Proč', 'Why')}
                  </p>
                  <p className="text-sm text-slate-700">{say(p.why)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-rose-600">
                    {t('Co by to porušilo', 'What would break it')}
                  </p>
                  <p className="text-sm text-slate-700">{say(p.breaks)}</p>
                </article>
              ))}
            </div>

            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <h2 className="text-base font-semibold text-slate-900">
                {t('Životní cyklus Lístku', 'The life of a Lístek')}
              </h2>
              <p className="mt-1 text-sm text-slate-600">
                {t(
                  'Všimněte si dvou konců, které nejsou chyba: strop nic nezapíše a nedostatek Lístků nic neodepíše. Obojí je legitimní odpověď, ne selhání.',
                  'Note the two endings that are not errors: the cap writes nothing, and an unaffordable redemption burns nothing. Both are legitimate answers, not faults.',
                )}
              </p>
              <div className="mt-4 overflow-x-auto">
                <Mermaid chart={LIFECYCLE_DIAGRAM} />
              </div>
            </div>

            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <h2 className="text-base font-semibold text-slate-900">
                {t('Jak Lípa souvisí se zbytkem platformy', 'How Lípa connects to the rest of the platform')}
              </h2>
              <ul className="mt-3 space-y-3">
                {CONNECTIONS.map(c => (
                  <li key={c.id} className="rounded-xl border border-slate-100 bg-slate-50/60 p-4">
                    <div className="flex items-center justify-between gap-3">
                      <h3 className="text-sm font-semibold text-slate-900">{say(c.system)}</h3>
                      {c.href && (
                        <Link href={c.href} className="inline-flex items-center gap-1 text-xs font-semibold text-violet-700 hover:underline">
                          {t('Otevřít', 'Open')}<ArrowRight className="h-3 w-3" />
                        </Link>
                      )}
                    </div>
                    <p className="mt-1 text-sm text-slate-700">{say(c.what)}</p>
                    <p className="mt-1 text-sm text-slate-500">
                      <span className="font-semibold">{t('Hranice: ', 'Boundary: ')}</span>{say(c.limit)}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          </section>
        )}

        {tab === 'catalogues' && (
          <section className="space-y-5" aria-label={tabLabel.catalogues}>
            <div className="flex items-start gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-4" role="note">
              <FileText className="mt-0.5 h-4 w-4 shrink-0 text-amber-700" />
              <p className="text-sm text-amber-900">
                {t(
                  'Oba katalogy jsou kód. Nejsou tu editovatelné a nikdy nebudou: změna položky prochází pull requestem a schválením, stejně jako segment nebo šablona kampaně. Tahle stránka ukazuje, co je v platnosti.',
                  'Both catalogues are code. They are not editable here and never will be: changing an entry goes through a pull request and a review, the same as a segment or a campaign template. This page shows what is in force.',
                )}
              </p>
            </div>

            {loading && <p className="text-sm text-slate-500">{t('Načítám…', 'Loading…')}</p>}
            {!loading && serviceKind && (
              <DataUnavailable
                kind={serviceKind}
                service="Loyalty-service"
                feature={t('Katalogy Lípy', 'Lípa catalogues')}
                lang={language === 'cs' ? 'cs' : 'en'}
              />
            )}

            {!loading && !serviceKind && catalogue && (
              <>
                <div className="rounded-2xl border border-slate-200 bg-white p-5">
                  <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900">
                    <Coins className="h-4 w-4 text-emerald-600" />
                    {t('Za co se Lístky získávají', 'What earns Lístky')}
                  </h2>
                  <p className="mt-1 text-sm text-slate-600">
                    {t(
                      'Uvedení důvodu v katalogu neznamená, že už pro něj existuje zdroj události. Co je skutečně zapojené, odpovídají konzumenty služby, ne tenhle seznam.',
                      'A source listed here is not a claim that a producer exists for it. What is actually wired is answered by the service consumers, not by this list.',
                    )}
                  </p>
                  <table className="mt-4 w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
                        <th className="py-2">{t('Důvod', 'Source')}</th>
                        <th className="py-2 text-right">{t('Lístků', 'Lístky')}</th>
                        <th className="py-2 text-right">{t('Platnost', 'Validity')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {catalogue.earnSources.map(s => (
                        <tr key={s.id} className="border-b border-slate-100 last:border-none">
                          <td className="py-2 font-medium text-slate-800">{s.id}</td>
                          <td className="py-2 text-right tabular-nums">{num(s.leaves)}</td>
                          <td className="py-2 text-right text-slate-600">
                            {t(`${num(s.validityDays)} dní`, `${num(s.validityDays)} days`)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div className="rounded-2xl border border-slate-200 bg-white p-5">
                  <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900">
                    <Gift className="h-4 w-4 text-violet-600" />
                    {t('Za co se Lístky vyměňují', 'What Lístky buy')}
                  </h2>
                  <p className="mt-1 text-sm text-slate-600">
                    {t(
                      'Cena je v Lístcích a v ničem jiném. Sloupec Motor říká, která služba benefit doručí — Lípa sama nedoručuje nic.',
                      'The price is in Lístky and in nothing else. The engine column says which service delivers the benefit — Lípa itself delivers nothing.',
                    )}
                  </p>
                  <table className="mt-4 w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
                        <th className="py-2">{t('Benefit', 'Benefit')}</th>
                        <th className="py-2">{t('Motor', 'Engine')}</th>
                        <th className="py-2 text-right">{t('Cena', 'Price')}</th>
                        <th className="py-2 text-right">{t('Platnost', 'Validity')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {catalogue.benefits.map(b => (
                        <tr key={b.id} className="border-b border-slate-100 last:border-none align-top">
                          <td className="py-2">
                            <span className="font-medium text-slate-800">{b.id}</span>
                            <span className="block text-xs text-slate-500">{b.description}</span>
                          </td>
                          <td className="py-2 text-slate-700">{b.engine}</td>
                          <td className="py-2 text-right tabular-nums">{num(b.priceLeaves)}</td>
                          <td className="py-2 text-right text-slate-600">
                            {t(`${num(b.validityDays)} dní`, `${num(b.validityDays)} days`)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </section>
        )}

        {tab === 'party' && (
          <section className="space-y-5" aria-label={tabLabel.party}>
            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <h2 className="text-base font-semibold text-slate-900">{t('Zůstatek klienta', 'A customer balance')}</h2>
              <p className="mt-1 max-w-3xl text-sm text-slate-600">
                {t(
                  'Je to přesně to, co vidí klient ve své aplikaci. Operátor tu nemá žádné pole navíc — reciproční průhlednost je smysl téhle obrazovky, ne její vedlejší efekt.',
                  'This is exactly what the customer sees in their own app. The operator gets no extra field here — reciprocal transparency is the point of this screen, not a side effect.',
                )}
              </p>
              <div className="mt-4 flex flex-wrap items-center gap-2">
                <label htmlFor="lipa-party" className="sr-only">{t('UUID klienta', 'Customer UUID')}</label>
                <input
                  id="lipa-party"
                  value={partyId}
                  onChange={e => setPartyId(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') void lookUpParty() }}
                  placeholder={t('UUID klienta', 'Customer UUID')}
                  className="w-96 max-w-full rounded-xl border border-slate-300 px-3 py-2 font-mono text-sm"
                />
                <button
                  type="button"
                  onClick={() => void lookUpParty()}
                  className="inline-flex items-center gap-2 rounded-xl bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition hover:bg-violet-700"
                >
                  <Search className="h-4 w-4" />
                  {t('Vyhledat', 'Look up')}
                </button>
                {party && (
                  <Link href={`/customer-360?partyId=${party.partyId}`} className="inline-flex items-center gap-1 text-sm font-semibold text-violet-700 hover:underline">
                    {t('Otevřít Customer 360', 'Open Customer 360')}<ArrowRight className="h-3 w-3" />
                  </Link>
                )}
              </div>
              {partyError && <p role="alert" className="mt-2 text-sm text-rose-700">{partyError}</p>}
            </div>

            {partyLoading && <p className="text-sm text-slate-500">{t('Načítám…', 'Loading…')}</p>}

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
                    <div key={stat.label} className="rounded-2xl border border-slate-200 bg-white p-4">
                      <p className="text-xs uppercase tracking-wide text-slate-500">{stat.label}</p>
                      <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">{stat.value}</p>
                    </div>
                  ))}
                </div>

                <div className="rounded-2xl border border-slate-200 bg-white p-5">
                  <h3 className="text-sm font-semibold text-slate-900">{t('Historie', 'History')}</h3>
                  {party.history.length === 0 && (
                    <p className="mt-2 text-sm text-slate-500">
                      {t('Tento klient zatím nemá žádný pohyb.', 'This customer has no entries yet.')}
                    </p>
                  )}
                  {party.history.length > 0 && (
                    <table className="mt-3 w-full text-sm">
                      <thead>
                        <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
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
                          <tr key={row.id} className="border-b border-slate-100 last:border-none">
                            <td className="py-2 text-slate-600">{new Date(row.occurredAt).toLocaleString(locale)}</td>
                            <td className="py-2 font-medium text-slate-800">{row.type}</td>
                            <td className="py-2 text-slate-700">{row.earnSourceId ?? row.benefitId ?? '—'}</td>
                            <td className="py-2 text-right tabular-nums">{num(row.leaves)}</td>
                            <td className="py-2 text-right tabular-nums">{num(row.remainingLeaves)}</td>
                            <td className="py-2 text-right text-slate-600">
                              {row.expiresAt ? new Date(row.expiresAt).toLocaleDateString(locale) : '—'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </>
            )}
          </section>
        )}

        {tab === 'finance' && (
          <section className="space-y-5" aria-label={tabLabel.finance}>
            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900">
                <Landmark className="h-4 w-4 text-slate-700" />
                {t('Závazek banky', 'What the bank owes')}
              </h2>
              <p className="mt-1 max-w-3xl text-sm text-slate-600">
                {t(
                  'Nespotřebované Lístky jsou závazek. Číslo níže je vstup do denního zaúčtování rezervy, ne zaúčtování samo — to vlastní billing, protože ten je na peněžní cestě a Lípa ne.',
                  'Unspent Lístky are an obligation. The figure below is the input to the daily provisioning journal, not the journal — billing owns that, because billing is on the money path and Lípa is not.',
                )}
              </p>
              {catalogue?.provisioning ? (
                <div className="mt-4 grid gap-4 sm:grid-cols-3">
                  <div className="rounded-xl border border-slate-100 bg-slate-50/60 p-4">
                    <p className="text-xs uppercase tracking-wide text-slate-500">
                      {t('Nesplacený závazek', 'Outstanding obligation')}
                    </p>
                    <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">
                      {num(catalogue.provisioning.outstandingLeaves)}
                    </p>
                    <p className="text-xs text-slate-500">
                      {t('Lístků, nikoli korun. Lípa Lístek neoceňuje.', 'Lístky, not korunas. Lípa does not price a Lístek.')}
                    </p>
                  </div>
                  <div className="rounded-xl border border-slate-100 bg-slate-50/60 p-4">
                    <p className="text-xs uppercase tracking-wide text-slate-500">
                      {t('Roční strop na klienta', 'Annual cap per customer')}
                    </p>
                    <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">
                      {num(catalogue.provisioning.annualCapPerParty)}
                    </p>
                    <p className="text-xs text-slate-500">
                      {t('Ohraničuje ekonomickou expozici programu.', 'It bounds the economic exposure of the programme.')}
                    </p>
                  </div>
                  <div className="rounded-xl border border-slate-100 bg-slate-50/60 p-4">
                    <p className="text-xs uppercase tracking-wide text-slate-500">
                      {t('Verze pravidla', 'Rule version')}
                    </p>
                    <p className="mt-1 text-2xl font-semibold text-slate-900">{catalogue.provisioning.ruleVersion}</p>
                    <p className="text-xs text-slate-500">
                      {t('Zmrazí se na každém zápisu, takže změna sazby historii nepřepíše.', 'Frozen onto every entry, so changing a rate never rewrites history.')}
                    </p>
                  </div>
                </div>
              ) : (
                <p className="mt-3 text-sm text-slate-500">
                  {t('Číslo závazku není k dispozici, protože služba tu neodpovídá.', 'The obligation figure is unavailable because the service does not answer here.')}
                </p>
              )}
            </div>

            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900">
                <Scale className="h-4 w-4 text-slate-700" />
                {t('Právní rámec', 'The legal position')}
              </h2>
              <ul className="mt-3 space-y-3">
                {LEGAL.map(item => (
                  <li key={item.id} className="rounded-xl border border-slate-100 bg-slate-50/60 p-4">
                    <h3 className="text-sm font-semibold text-slate-900">{say(item.regime)}</h3>
                    <p className="mt-1 text-sm text-slate-800">{say(item.position)}</p>
                    <p className="mt-1 text-sm text-slate-600">
                      <span className="font-semibold">{t('Na čem to stojí: ', 'What holds it: ')}</span>{say(item.holds)}
                    </p>
                    {item.open && (
                      <p className="mt-1 flex items-start gap-1.5 text-sm text-amber-800">
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
          <section className="space-y-5" aria-label={tabLabel.ai}>
            <div className="rounded-2xl border border-violet-200 bg-violet-50/60 p-5">
              <h2 className="flex items-center gap-2 text-base font-semibold text-violet-900">
                <Sparkles className="h-4 w-4" />
                {t('Kde umělá inteligence pomáhá a kde končí', 'Where AI helps and where it stops')}
              </h2>
              <ul className="mt-3 space-y-1.5">
                {AI_RED_LINES.map(line => (
                  <li key={line.en} className="flex items-start gap-2 text-sm text-violet-950">
                    <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-violet-700" />
                    {say(line)}
                  </li>
                ))}
              </ul>
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              {AI_ROLES.map(role => (
                <article key={role.id} className="rounded-2xl border border-slate-200 bg-white p-5">
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                      <Bot className="h-4 w-4 text-violet-600" />
                      {say(role.name)}
                    </h3>
                    <span className="rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs font-semibold text-slate-600">
                      {role.status === 'available' ? t('dostupné', 'available') : t('návrh', 'proposed')}
                    </span>
                  </div>
                  <p className="mt-2 text-sm text-slate-700">{say(role.does)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-rose-600">
                    {t('Nesmí', 'Cannot')}
                  </p>
                  <p className="text-sm text-slate-700">{say(role.cannot)}</p>
                  <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-slate-500">
                    {t('Rozhoduje', 'Decides instead')}
                  </p>
                  <p className="text-sm text-slate-700">{say(role.decides)}</p>
                </article>
              ))}
            </div>

            <p className="text-sm text-slate-500">
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
