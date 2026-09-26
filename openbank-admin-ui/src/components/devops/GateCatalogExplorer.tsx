// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useMemo, useState } from 'react'
import { ArrowRight, ExternalLink, Search } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import styles from './GateCatalogExplorer.module.css'

type Gate = {
  id: string; name: string; group: string; mode: 'enforced' | 'advisory'; when: string
  needsBase: 'required' | 'optional' | null; selftest: boolean; selftestExempt: boolean; rationale: string | null
  reviewAfter: string | null; minSubjects: number | null; budgetSeconds: number | null
  run: string; line: number
}
type Catalog = {
  source: string; ref: string
  totals: { all: number; enforced: number; advisory: number; selftested: number; selftestExempt: number; prOnly: number; withRationale: number; groups: Record<string, number> }
  gates: Gate[]
}

const REPO = 'https://github.com/JiRaska/open-bank-oss'

export function GateCatalogExplorer() {
  const { t } = useLanguage()
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [error, setError] = useState(false)
  const [query, setQuery] = useState('')
  const [group, setGroup] = useState('all')
  const [mode, setMode] = useState('all')
  const [selected, setSelected] = useState('')
  const [limit, setLimit] = useState(24)

  useEffect(() => {
    fetch('/api/devops/gate-catalog')
      .then(response => { if (!response.ok) throw new Error('catalog unavailable'); return response.json() })
      .then((data: Catalog) => {
        setCatalog(data)
        const initial = new URLSearchParams(window.location.search).get('gate')
        setSelected(initial && data.gates.some(gate => gate.id === initial) ? initial : 'test-intelligence-ecosystem')
      })
      .catch(() => setError(true))
  }, [])

  const filtered = useMemo(() => (catalog?.gates ?? []).filter(gate => {
    const phrase = `${gate.id} ${gate.name} ${gate.rationale ?? ''}`.toLocaleLowerCase()
    return (group === 'all' || gate.group === group)
      && (mode === 'all' || gate.mode === mode)
      && phrase.includes(query.toLocaleLowerCase().trim())
  }), [catalog, group, mode, query])
  const active = catalog?.gates.find(gate => gate.id === selected)

  const choose = (gate: Gate) => {
    setSelected(gate.id)
    const url = new URL(window.location.href)
    url.searchParams.set('gate', gate.id)
    url.hash = 'gate-catalog'
    window.history.replaceState(null, '', url)
  }

  return (
    <section id="gate-catalog" className={styles.catalog} aria-labelledby="gate-catalog-title">
      <div className={styles.heading}>
        <div>
          <span className={styles.eyebrow}>{t('ZDROJ PRAVDY · CI MANIFEST', 'SOURCE OF TRUTH · CI MANIFEST')}</span>
          <h2 id="gate-catalog-title">{t('Každá skutečná CI brána, ne jen osm témat', 'Every actual CI gate, not just eight themes')}</h2>
          <p>{t('Osm otázek výše je učební mapa. Tento katalog vzniká při buildu přímo z .github/gates/gates.yaml; počet i definice se mění s kódem.', 'The eight questions above are a learning map. This catalog is generated at build time from .github/gates/gates.yaml; counts and definitions evolve with the code.')}</p>
        </div>
        <a href={`${REPO}/blob/${catalog?.ref ?? 'main'}/.github/gates/gates.yaml`} target="_blank" rel="noreferrer">
          {t('Celý manifest', 'Full manifest')} <ExternalLink size={15} aria-hidden="true" />
        </a>
      </div>

      {error ? <p role="status">{t('Katalog v tomto buildu není dostupný. Otevřete zdrojový manifest.', 'Catalog unavailable in this build. Open the source manifest.')}</p> : !catalog ? <p role="status">{t('Načítám definice bran…', 'Loading gate definitions…')}</p> : <>
        <div className={styles.numbers} aria-label={t('Počty deklarovaných bran', 'Declared gate counts')}>
          <div><strong>{catalog.totals.all}</strong><span>{t('deklarovaných bran', 'declared gates')}</span></div>
          <div><strong>{catalog.totals.enforced}</strong><span>{t('runner blokuje při nenulovém výsledku', 'runner blocks on non-zero exit')}</span></div>
          <div><strong>{catalog.totals.advisory}</strong><span>{t('runner pouze varuje', 'runner only warns')}</span></div>
          <div><strong>{catalog.totals.selftested}</strong><span>{t('se self-testem', 'with a self-test')} · {catalog.totals.selftestExempt} {t('s výjimkou', 'exempt')}</span></div>
        </div>
        <p className={styles.resultCount}>{catalog.totals.prOnly} {t('bran běží pouze pro PR; samostatné zdůvodnění v manifestu má', 'gates run only on PRs; a separate rationale is declared for')} {catalog.totals.withRationale} / {catalog.totals.all} {t('bran.', 'gates.')}</p>
        <p className={styles.resultCount}>{t('Toto je deklarovaný katalog. Pozorovaný stav a poslední selhání najdete v', 'This is the declared catalog. Observed status and last failures are in the')} <a href="/devops">{t('DevOps cockpit', 'DevOps cockpit')}</a>.</p>

        <div className={styles.lesson}>
          <h3>{t('Jak vznikne verdikt?', 'How is a verdict reached?')}</h3>
          <ol>
            <li>{t('Manifest určí příkaz, režim a kdy se spouští.', 'The manifest declares the command, mode, and trigger.')}</li>
            <li>{t('Runner vybere bránu; PR-only bránu při pushi přeskočí. Scope si některé kontroly řeší uvnitř skriptu.', 'The runner selects the gate; PR-only gates are skipped on push. Some checks determine scope inside their scripts.')}</li>
            <li>{t('Self-test musí prokázat, že kontrola umí selhat. Špatný self-test znamená UNFALSIFIED a blokaci.', 'A self-test must prove the check can fail. A wrong self-test means UNFALSIFIED and blocks.')}</li>
            <li>{t('Pak běží kontrola. Nenulový exit blokuje u enforced, u advisory vytvoří varování. Některé skripty samy vracejí 0 pro nález bez --enforce.', 'Then the check runs. A non-zero exit blocks for enforced, but warns for advisory. Some scripts return 0 for findings unless passed --enforce.')}</li>
            <li>{t('Minimální počet subjektů brání prázdně zelenému testu; časový budget platí v CI, ne lokálně.', 'A subject floor prevents vacuous green checks; time budgets apply in CI, not locally.')}</li>
          </ol>
          <p>{t('Osm skupin níže jsou paralelní časové shardy, nikoli oborová taxonomie. Katalog jsou deklarace, ne tvrzení, že všech 233 bran běží v každém PR.', 'The eight groups below are parallel wall-time shards, not a domain taxonomy. This is a declaration catalog, not a claim that every gate runs in every PR.')}</p>
        </div>

        <div className={styles.filters}>
          <label className={styles.search}><Search aria-hidden="true" size={17} /><span className="sr-only">{t('Hledat bránu', 'Search gates')}</span><input value={query} onChange={event => { setQuery(event.target.value); setLimit(24) }} placeholder={t('Hledat název, ID nebo důvod…', 'Search name, ID, or rationale…')} /></label>
          <label>{t('Spouštěcí shard', 'Execution shard')}<select value={group} onChange={event => { setGroup(event.target.value); setLimit(24) }}><option value="all">{t('Všechny', 'All')}</option>{Object.entries(catalog.totals.groups).map(([name, count]) => <option value={name} key={name}>{name} ({count})</option>)}</select></label>
          <label>{t('Režim', 'Mode')}<select value={mode} onChange={event => { setMode(event.target.value); setLimit(24) }}><option value="all">{t('Oba', 'Both')}</option><option value="enforced">enforced</option><option value="advisory">advisory</option></select></label>
        </div>
        <p className={styles.resultCount} aria-live="polite">{t('Zobrazeno', 'Showing')} {Math.min(limit, filtered.length)} / {filtered.length} {t('nalezených bran', 'matching gates')}</p>
        <div className={styles.layout}>
          <div className={styles.list} role="group" aria-label={t('Seznam CI bran', 'CI gate list')}>
            {filtered.length === 0 && <p>{t('Žádná brána neodpovídá filtru.', 'No gates match the filters.')}</p>}
            {filtered.slice(0, limit).map(gate => <button type="button" key={gate.id} onClick={() => choose(gate)} aria-pressed={selected === gate.id} aria-controls="gate-catalog-detail" className={selected === gate.id ? styles.selected : ''}>
              <span><strong>{gate.name}</strong><small>{gate.id}</small></span><span className={styles.badges}><em>{gate.group}</em><em>{gate.mode}</em></span>
            </button>)}
            {limit < filtered.length && <button type="button" className={styles.more} onClick={() => setLimit(value => value + 24)}>{t('Zobrazit dalších 24', 'Show 24 more')} <ArrowRight size={15} aria-hidden="true" /></button>}
          </div>
          <aside id="gate-catalog-detail" className={styles.detail} aria-label={t('Detail CI brány', 'CI gate detail')}>
            {!active ? <p>{t('Vyberte bránu a zjistěte, co chrání, kdy běží a jak ji spustit.', 'Select a gate to see what it protects, when it runs, and how to run it.')}</p> : <>
              <span className={styles.eyebrow}>{active.id}</span>
              <h3>{active.name}</h3>
              <div className={styles.meta}>
                <div><span>{t('Co chrání', 'What it protects')}</span><p>{active.rationale ?? t('Samostatné zdůvodnění není v manifestu uvedeno; název a zdrojový příkaz jsou níže.', 'No separate rationale is declared in the manifest; inspect the name and source command below.')}</p></div>
                <div><span>{t('Kdy a s jakým dopadem', 'When and with what impact')}</span><p>{active.when === 'pull_request' ? t('Jen pull request; na push do main se přeskočí.', 'Pull requests only; skipped on push to main.') : t('Deklarováno jako always; vnitřní rozsah může zúžit samotný skript.', 'Declared always; the script may narrow its own scope.')} {active.mode === 'enforced' ? t('Nenulový exit zastaví CI.', 'A non-zero exit fails CI.') : t('Nenulový exit vytvoří varování.', 'A non-zero exit produces a warning.')}</p></div>
                <div><span>{t('Prokazatelnost', 'Falsifiability')}</span><p>{active.selftest ? t('Self-test běží před kontrolou a musí prokázat dosažitelnou červenou.', 'Self-test runs before the check and must prove red is reachable.') : active.selftestExempt ? t('Self-test má v manifestu výjimku.', 'A self-test exemption is declared in the manifest.') : t('Self-test není deklarován.', 'No self-test declared.')}</p></div>
              </div>
              <div className={styles.attributes}>
                <span>{t('Min. subjektů', 'Subject floor')}: {active.minSubjects ?? '—'}</span>
                <span>{t('CI budget', 'CI budget')}: {active.budgetSeconds != null ? `${active.budgetSeconds} s` : '—'}</span>
                <span>{t('PR base', 'PR base')}: {active.needsBase ?? '—'}</span>
              </div>
              <span className={styles.commandLabel}>{t('Spustit lokálně z kořene repozitáře', 'Run locally from repository root')}</span>
              <code className={styles.command}>python3 .github/scripts/run-gates.py --only {active.id}</code>
              <details><summary>{t('Zdrojový příkaz kontroly', 'Underlying check command')}</summary><pre>{active.run}</pre></details>
              <a href={`${REPO}/blob/${catalog.ref}/${catalog.source}#L${active.line}`} target="_blank" rel="noreferrer">{t('Otevřít přesnou definici', 'Open exact definition')} <ExternalLink size={14} aria-hidden="true" /></a>
            </>}
          </aside>
        </div>
      </>}
    </section>
  )
}
