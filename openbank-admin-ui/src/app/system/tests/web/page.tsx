// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Educational explainer: how the admin portal (this Next.js app) is tested — layers, tools,
// the required "Admin UI" check, advisory lanes, deployed synthetics and the evidence pipeline
// that feeds Test Intelligence (ADR-0076, ADR-0273, ADR-0359, ADR-0370). Facts as of 2026-11-14.

import { cookies } from 'next/headers'
import Link from 'next/link'
import { MonitorCheck } from 'lucide-react'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import {
  ExplainerSection, FlowChain, KeyNumbers, LayerCards, ModeLegend,
  PipelineStages, StackPyramid, StatusList, type LayerExplanation, type ModeLabels,
} from '@/components/testing/explainer/StackExplainer'

export const dynamic = 'force-dynamic'

export default async function WebTestingExplainerPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const modes: ModeLabels = {
    blocks: t('blokuje merge', 'blocks merge'),
    nightly: t('po nasazení', 'post-deploy'),
    advisory: t('jen hlásí', 'reports only'),
    planned: t('plánováno', 'planned'),
  }

  const layers: LayerExplanation[] = [
    {
      name: t('Typy a lint', 'Types and lint'), tool: 'TypeScript · ESLint', mode: 'blocks',
      what: t('Typová kontrola celého portálu a pravidla Next.js.', 'Type-check of the whole portal and Next.js rules.'),
      why: t('Chyba v typu BFF odpovědi se má najít při kompilaci, ne u operátora.', 'A wrong BFF response type should fail at compile time, not in front of an operator.'),
      when: t('Každé PR, job ui-build.', 'Every PR, the ui-build job.'),
    },
    {
      name: t('Guard testy', 'Guard tests'), tool: t('Vitest · 320 guardů', 'Vitest · 320 guards'), mode: 'blocks',
      what: t('Pravidla nad zdrojákem: i18n, CSP, žádné surové barvy ani identifikátory, RBAC kontrakt s backendem.', 'Rules over the source: i18n, CSP, no raw colours or identifiers, the RBAC contract with the backend.'),
      why: t('Každá chyba, která se jednou stala, se změní v pravidlo, takže se nemůže vrátit. Ratchet: dluh smí jen klesat.', 'Every mistake that happened once becomes a rule so it cannot return. Ratchet: debt may only shrink.'),
      when: t('Každé PR; RBAC guardy i při změně backendu.', 'Every PR; RBAC guards also when the backend changes.'),
    },
    {
      name: t('Unit a komponentové testy', 'Unit and component tests'), tool: 'Vitest · Testing Library · fast-check', mode: 'blocks',
      what: t('Logika v src/lib, komponenty, BFF route handlery a smoke render všech stránek.', 'Logic in src/lib, components, BFF route handlers and a render smoke of every page.'),
      why: t('Rychlá zpětná vazba v řádu sekund; každá nová stránka musí aspoň projít renderem.', 'Feedback in seconds; every new page must at least render.'),
      when: t('Každé PR.', 'Every PR.'),
    },
    {
      name: t('Pokrytí s ratchetem', 'Coverage with a ratchet'), tool: 'coverage-v8', mode: 'blocks',
      what: t('Globální minimum a vlastní minimum pro 17 modulů s penězi a oprávněními.', 'A global floor plus a floor for each of 17 modules handling money and permissions.'),
      why: t('Dřívější limity byly 36 % a CI coverage vůbec nespouštělo, takže nic nevynucovaly. Snížení limitu teď vyžaduje odkaz na issue.', 'Earlier floors were 36 % and CI never ran coverage, so they enforced nothing. Lowering a floor now needs an issue reference.'),
      when: t('Každé PR, job ui-coverage.', 'Every PR, the ui-coverage job.'),
    },
    {
      name: t('Kontraktní testy', 'Contract tests'), tool: 'Pact · OpenAPI-validated mocks', mode: 'blocks',
      what: t('Mockované odpovědi v E2E se ověřují proti OpenAPI služby; tři consumer pakty s backendem.', 'Mocked responses in E2E are validated against the service OpenAPI; three consumer pacts with the backend.'),
      why: t('Mock, který lže o backendu, dává zelené testy a rozbitou produkci.', 'A mock that lies about the backend gives green tests and a broken production.'),
      when: t('Každé PR; drift paktů v samostatném workflow.', 'Every PR; pact drift in its own workflow.'),
    },
    {
      name: t('E2E v prohlížeči', 'Browser E2E'), tool: t('Playwright · 154 specifikací', 'Playwright · 154 specs'), mode: 'blocks',
      what: t('Celé obrazovky v Chromiu proti produkčnímu buildu s mockovaným BFF: navigace, formuláře, role, mobilní shell.', 'Whole screens in Chromium against the production build with a mocked BFF: navigation, forms, roles, mobile shell.'),
      why: t('Ověří, že stránky drží pohromadě tak, jak je operátor používá.', 'Proves the screens fit together the way an operator uses them.'),
      when: t('Každé PR; nestabilní test jde do karantény s vlastníkem, issue a expirací max. 30 dní.', 'Every PR; a flaky test goes to quarantine with an owner, an issue and an expiry of at most 30 days.'),
    },
    {
      name: t('Přístupnost', 'Accessibility'), tool: 'axe-core', mode: 'blocks',
      what: t('Automatická kontrola WCAG na klíčových workflow, ve světlém i tmavém režimu.', 'Automated WCAG checks on core workflows, in light and dark mode.'),
      why: t('Operátoři pracují celý den; čitelnost a ovladatelnost klávesnicí je bezpečnost práce.', 'Operators work all day; readability and keyboard control are a matter of safe work.'),
      when: t('Každé PR v rámci E2E; pokrytí rout hlídá gate.', 'Every PR inside E2E; a gate tracks route coverage.'),
    },
    {
      name: t('Vizuální regrese', 'Visual regression'), tool: t('Playwright screenshoty v pinnutém kontejneru', 'Playwright screenshots in a pinned container'), mode: 'advisory',
      what: t('Porovnání vzhledu vybraných stránek pixel po pixelu, se zmrazeným časem.', 'Pixel comparison of selected pages, with a frozen clock.'),
      why: t('CSS chyby projdou typy, lintem i jsdom. Jeden PR jich kdysi dodal tři.', 'CSS bugs pass types, lint and jsdom. One PR once shipped three.'),
      when: t('Každé PR, zatím jen hlásí; povinné po 10 bězích bez flaky.', 'Every PR, reporting only; required after 10 runs with no flakes.'),
    },
    {
      name: t('Mutační testování', 'Mutation testing'), tool: 'StrykerJS', mode: 'advisory',
      what: t('Úmyslně rozbije kód (změní > na >=, smaže řádek) a ověří, že nějaký test spadne.', 'Deliberately breaks code (turns > into >=, deletes a line) and checks that some test fails.'),
      why: t('Pokrytí říká, že kód běžel; mutace říkají, že test opravdu něco hlídá.', 'Coverage says the code ran; mutations say the test actually guards something.'),
      when: t('PR nad změněnými soubory; limit nelze snížit bez odkazu na issue.', 'PRs on changed files; the floor cannot drop without an issue reference.'),
    },
    {
      name: t('Výkonové rozpočty', 'Performance budgets'), tool: t('Playwright @perf', 'Playwright @perf'), mode: 'advisory',
      what: t('Velikost JS a LCP pro každou routu proti naměřenému rozpočtu.', 'JS size and LCP per route against a measured budget.'),
      why: t('Portál nesmí potichu ztloustnout; LCP strop 2,5 s.', 'The portal must not silently grow fat; LCP ceiling 2.5 s.'),
      when: t('Každé PR, zatím jen hlásí.', 'Every PR, reporting only for now.'),
    },
    {
      name: t('Syntetika na nasazeném portálu', 'Synthetics on the deployed portal'), tool: 'Chromium + Firefox · Keycloak', mode: 'nightly',
      what: t('Každé 2 h: přesměrování na SSO, přihlášení kanárkového operátora s minimem práv a ověření, že admin API odmítne.', 'Every 2 h: SSO redirect, sign-in of a least-privilege canary operator and proof that an admin API refuses it.'),
      why: t('Testy v CI neověří produkční přihlášení, certifikáty ani oprávnění; tohle ano.', 'CI tests cannot prove production login, certificates or permissions; this does.'),
      when: t('Každé 2 h; denně auth gate přes všechny money-path obrazovky.', 'Every 2 h; daily auth gate across every money-path screen.'),
    },
    {
      name: t('Další prohlížeče a verzový skew', 'More browsers and version skew'), tool: 'WebKit · Firefox · API N-1', mode: 'planned',
      what: t('Noční E2E i ve WebKitu a Firefoxu; portál proti předchozí verzi API.', 'Nightly E2E in WebKit and Firefox too; the portal against the previous API version.'),
      why: t('Dnes E2E běží jen v Chromiu a nasazení UI a backendu není atomické.', 'E2E runs in Chromium only today, and UI and backend deploys are not atomic.'),
      when: t('ADR-0370 U7 a U8, zatím nepostaveno.', 'ADR-0370 U7 and U8, not built yet.'),
    },
  ]

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<><span>OpenBank</span><span className="breadcrumb-sep" aria-hidden="true">/</span><span>{t('Systém', 'System')}</span><span className="breadcrumb-sep" aria-hidden="true">/</span><Link href="/system/tests">{t('Test Intelligence', 'Test Intelligence')}</Link><span className="breadcrumb-sep" aria-hidden="true">/</span><span className="breadcrumb-current">{t('Admin portál', 'Admin portal')}</span></>}
        icon={<MonitorCheck size={19} aria-hidden="true" style={{ color: 'var(--accent)' }} />}
        title={t('Jak testujeme admin portál', 'How we test the admin portal')}
        subtitle={t('Co, čím, proč a kdy ověřujeme ve webovém frontendu pro operátory a jak se výsledky skládají do Test Intelligence.', 'What we verify in the operator web frontend, with which tools, why and when, and how results add up in Test Intelligence.')}
      />

      <ExplainerSection title={t('V kostce', 'At a glance')} subtitle={t('Portál ovládá peníze, schvalování a oprávnění, takže chyba v UI je provozní riziko. Jeden povinný check „Admin UI“ sdružuje vše, co blokuje merge; dražší a zatím nestabilní vrstvy jen hlásí, dokud si důvěru nezaslouží.', 'The portal drives money, approvals and permissions, so a UI bug is an operational risk. One required “Admin UI” check bundles everything that blocks the merge; costlier or still-unproven layers only report until they earn trust.')}>
        <KeyNumbers items={[
          { value: '772', label: t('souborů Vitest testů', 'Vitest test files'), hint: t('z toho 320 guardů', 'of which 320 guards') },
          { value: '154', label: t('Playwright specifikací', 'Playwright specs'), hint: t('Chromium, mockovaný BFF', 'Chromium, mocked BFF') },
          { value: '73 %', label: t('pokrytí příkazů', 'statement coverage'), hint: t('minimum 72 %, plus 17 modulových minim', 'floor 72 %, plus 17 module floors') },
          { value: '2 h', label: t('interval syntetiky v produkci', 'production synthetic interval'), hint: t('SSO, přihlášení, odmítnutí práv', 'SSO, sign-in, permission refusal') },
        ]} />
      </ExplainerSection>

      <ExplainerSection title={t('Testovací pyramida', 'The test pyramid')} subtitle={t('Dole tisíce rychlých kontrol nad kódem, nahoře pár drahých běhů v prohlížeči a proti produkci. Barva říká, jestli vrstva blokuje merge, běží po nasazení, jen hlásí, nebo je v plánu.', 'At the bottom thousands of fast checks over the code, at the top a few costly runs in a browser and against production. Colour shows whether a layer blocks the merge, runs after deploy, only reports, or is planned.')}>
        <ModeLegend labels={modes} title={t('Režim:', 'Mode:')} />
        <div style={{ marginTop: 10 }}>
          <StackPyramid
            title={t('Testovací pyramida admin portálu', 'Admin portal test pyramid')}
            costlyLabel={t('dražší, pomalejší', 'costlier, slower')}
            cheapLabel={t('levnější, rychlejší', 'cheaper, faster')}
            layers={[
              { label: t('Produkční syntetika', 'Production synthetics'), tools: 'Chromium + Firefox · Keycloak', mode: 'nightly' },
              { label: t('Vizuál, výkon, mutace', 'Visual, performance, mutation'), tools: 'Playwright · StrykerJS', mode: 'advisory' },
              { label: t('E2E a přístupnost', 'E2E and accessibility'), tools: 'Playwright · axe-core', mode: 'blocks' },
              { label: t('Kontrakty s backendem', 'Contracts with the backend'), tools: 'Pact · OpenAPI', mode: 'blocks' },
              { label: t('Unit, komponenty, pokrytí', 'Unit, components, coverage'), tools: 'Vitest · Testing Library · coverage-v8', mode: 'blocks' },
              { label: t('Guardy, typy, lint', 'Guards, types, lint'), tools: 'Vitest guards · TypeScript · ESLint', mode: 'blocks' },
            ]}
          />
        </div>
      </ExplainerSection>

      <ExplainerSection title={t('Každá vrstva: co, čím, proč, kdy', 'Each layer: what, with what, why, when')}>
        <LayerCards layers={layers} labels={modes} captions={{ what: t('Co:', 'What:'), why: t('Proč:', 'Why:'), when: t('Kdy:', 'When:'), caught: t('Už chytilo:', 'Already caught:') }} />
      </ExplainerSection>

      <ExplainerSection title={t('Cesta změny pipeline', 'How a change travels through the pipeline')} subtitle={t('Pravidlo povýšení: nová vrstva začíná jako „jen hlásí“ a povinnou se stane až po deseti bězích bez nestability. Gate, který falešně červená, se naučí lidé ignorovat.', 'Promotion rule: a new layer starts as “reports only” and becomes required only after ten runs without flakes. A gate that cries wolf teaches people to ignore it.')}>
        <PipelineStages labels={modes} stages={[
          { title: t('Pull request · povinný check Admin UI', 'Pull request · required Admin UI check'), when: t('jen když se mění portál', 'only when the portal changes'), items: [
            { label: t('typy + lint', 'types + lint'), mode: 'blocks' },
            { label: t('Vitest: unit, guardy, smoke', 'Vitest: unit, guards, smoke'), mode: 'blocks' },
            { label: t('pokrytí + ratchet', 'coverage + ratchet'), mode: 'blocks' },
            { label: t('Playwright E2E + axe', 'Playwright E2E + axe'), mode: 'blocks' },
            { label: t('RBAC kontrakt', 'RBAC contract'), mode: 'blocks' },
          ] },
          { title: t('Pull request · poradní kroky', 'Pull request · advisory steps'), when: t('ve stejném jobu, neblokují', 'same job, never block'), items: [
            { label: t('vizuální regrese', 'visual regression'), mode: 'advisory' },
            { label: t('mutace změněných souborů', 'mutation on changed files'), mode: 'advisory' },
            { label: t('výkonové rozpočty', 'performance budgets'), mode: 'advisory' },
            { label: t('karanténa + report flaky', 'quarantine + flake report'), mode: 'advisory' },
          ] },
          { title: t('Po nasazení', 'After deploy'), when: 'admin.open-bank.tech', items: [
            { label: t('syntetika každé 2 h', 'synthetics every 2 h'), mode: 'nightly' },
            { label: t('denní auth gate', 'daily auth gate'), mode: 'nightly' },
            { label: t('session smoke', 'session smoke'), mode: 'nightly' },
          ] },
          { title: t('Další kroky', 'Next steps'), when: 'ADR-0370', items: [
            { label: t('WebKit a Firefox nočně', 'WebKit and Firefox nightly'), mode: 'planned' },
            { label: t('verzový skew API', 'API version skew'), mode: 'planned' },
          ] },
        ]} />
      </ExplainerSection>

      <ExplainerSection title={t('Od testu k Test Intelligence', 'From a test to Test Intelligence')} subtitle={t('Každý běh zanechá důkaz s původem: který commit, který běh, kdy. Chybějící důkaz se nikdy nezobrazí jako zdravý – buňka zůstane „neověřeno“, dokud ji něco skutečně neověří.', 'Every run leaves evidence with provenance: which commit, which run, when. Missing evidence never shows as healthy – a cell stays “unverified” until something actually verifies it.')}>
        <FlowChain
          title={t('Tok důkazů z CI do Test Intelligence', 'Evidence flowing from CI into Test Intelligence')}
          steps={[
            { label: t('CI běh', 'CI run'), detail: t('JUnit, axe, vizuál, výkon', 'JUnit, axe, visual, performance') },
            { label: t('obálka běhu', 'run envelope'), detail: t('run.json s commitem a pokusem', 'run.json with commit and attempt') },
            { label: t('úložiště důkazů', 'evidence store'), detail: t('zápis jednou, adresa podle commitu', 'write-once, keyed by commit') },
            { label: t('snapshot', 'snapshot'), detail: t('verzovaný, zastará po 14 dnech', 'versioned, stale after 14 days') },
            { label: 'BFF', detail: t('stránkované čtení, limit 16 MiB', 'paged reads, 16 MiB limit') },
            { label: t('Test Intelligence', 'Test Intelligence'), detail: t('matice komponent, AI vysvětlení', 'component matrix, AI explanation') },
          ]}
        />
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 8, lineHeight: 1.5 }}>
          {t('AI vysvětlení na stránce Test Intelligence je jen čtení: nemá nástroje, odpověď se cachuje 5 minut a nikdy nemění verdikt testu.', 'The AI explanation on the Test Intelligence page is read-only: it has no tools, answers are cached for 5 minutes and it never changes a test verdict.')}
        </p>
      </ExplainerSection>

      <ExplainerSection title={t('Kde jsme dnes', 'Where we are today')}>
        <StatusList labels={{ blocks: t('povinné', 'required'), nightly: t('v produkci', 'in production'), advisory: t('jen hlásí', 'reports only'), planned: t('nepostaveno', 'not built') }} items={[
          { mode: 'blocks', label: t('Povinný check:', 'Required check:'), detail: t('typy, lint, Vitest, pokrytí s ratchetem, Playwright E2E s axe, kontrakty a RBAC guardy.', 'types, lint, Vitest, coverage with ratchet, Playwright E2E with axe, contracts and RBAC guards.') },
          { mode: 'nightly', label: t('Produkce:', 'Production:'), detail: t('syntetika každé 2 h a denní auth gate na nasazeném portálu.', 'synthetics every 2 h and a daily auth gate on the deployed portal.') },
          { mode: 'advisory', label: t('Na cestě k povinnosti:', 'On the way to required:'), detail: t('vizuální regrese, mutační testování a výkonové rozpočty.', 'visual regression, mutation testing and performance budgets.') },
          { mode: 'planned', label: t('ADR-0370 U7–U8:', 'ADR-0370 U7–U8:'), detail: t('další prohlížeče a test proti předchozí verzi API.', 'more browsers and a run against the previous API version.') },
        ]} />
      </ExplainerSection>
    </div>
  )
}
