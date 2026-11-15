// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Educational explainer: how the OpenBank mobile app (Kotlin Multiplatform, Android + iOS) is
// tested — layers, tools, pipeline, observability and the falsification rule (APP-ADR-0005).
// Static copy; facts as of 2026-11-14. Server component: language from the admin cookie.

import { cookies } from 'next/headers'
import Link from 'next/link'
import { Smartphone } from 'lucide-react'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import {
  ExplainerSection, FalsificationLoop, FlowChain, KeyNumbers, LayerCards, ModeLegend,
  PipelineStages, StackPyramid, StatusList, type LayerExplanation, type ModeLabels,
} from '@/components/testing/explainer/StackExplainer'

export const dynamic = 'force-dynamic'

export default async function MobileTestingExplainerPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const modes: ModeLabels = {
    blocks: t('blokuje merge', 'blocks merge'),
    nightly: t('noční', 'nightly'),
    advisory: t('jen hlásí', 'reports only'),
    planned: t('plánováno', 'planned'),
  }

  const layers: LayerExplanation[] = [
    {
      name: t('Statická analýza', 'Static analysis'), tool: 'detekt · ktlint · SwiftLint · actionlint', mode: 'blocks',
      what: t('Styl, chybové vzory a workflow soubory ještě před kompilací.', 'Style, bug patterns and workflow files before anything compiles.'),
      why: t('Nejlevnější chyba je ta, kterou ani nespustíme. ktlint běží bez baseline, takže dluh neroste.', 'The cheapest bug is one we never run. ktlint has no baseline, so debt cannot grow.'),
      when: t('Každé PR, workflow app lint.', 'Every PR, the app lint workflow.'),
    },
    {
      name: t('Unit testy sdíleného kódu', 'Shared-code unit tests'), tool: 'kotlin.test · coroutines-test · Kover', mode: 'blocks',
      what: t('Doménová logika v shared a composeApp: částky, stavy plateb, formátování, view modely.', 'Domain logic in shared and composeApp: amounts, payment states, formatting, view models.'),
      why: t('Jeden Kotlin kód běží na Androidu i iOS, takže jeden test chrání obě platformy. Kover drží minimální pokrytí.', 'One Kotlin codebase runs on Android and iOS, so one test protects both. Kover holds a coverage floor.'),
      when: t('Každé PR, těžká linka (JVM + iOS simulátor).', 'Every PR, heavy lane (JVM + iOS simulator).'),
    },
    {
      name: t('Property testy peněz', 'Money property tests'), tool: 'kotest-property', mode: 'blocks',
      what: t('Tisíce náhodných vstupů pro částky, FX, IBAN a SPAYD místo pár ručně vybraných příkladů.', 'Thousands of generated inputs for amounts, FX, IBAN and SPAYD instead of a few hand-picked examples.'),
      why: t('Zaokrouhlení a znaménka se rozbíjejí na okrajích, které člověk nenapadne.', 'Rounding and signs break at edges nobody thinks of.'),
      when: t('Každé PR (T1).', 'Every PR (T1).'),
      caught: t('Úrok ztrácel znaménko a zaokrouhloval „k sudé“ místo half-up.', 'Interest lost its sign and rounded half-even instead of half-up.'),
    },
    {
      name: t('Odolnost platební cesty', 'Money-path resilience'), tool: 'Ktor MockEngine', mode: 'blocks',
      what: t('Timeouty, resety spojení, 5xx, 409 a duplicitní odpovědi na platebních voláních.', 'Timeouts, connection resets, 5xx, 409 and duplicate replies on payment calls.'),
      why: t('Při výpadku sítě nesmí appka ukázat „odesláno“ ani poslat platbu dvakrát: ověřujeme opakované použití idempotency klíče.', 'On a flaky network the app must never show “sent” early or pay twice: we assert the idempotency key is reused.'),
      when: t('Každé PR (T2).', 'Every PR (T2).'),
    },
    {
      name: t('Screenshoty a přístupnost', 'Screenshots and accessibility'), tool: 'Roborazzi · Robolectric · ATF', mode: 'blocks',
      what: t('Každá komponenta design systému ve světlém i tmavém režimu proti schválenému obrázku, plus kontrola cílů dotyku a popisků.', 'Every design-system component in light and dark against an approved image, plus touch-target and label checks.'),
      why: t('Vizuál Lípa je schválený kontrakt. Přístupnost se kontroluje strojem, ne očima.', 'The approved Lípa look is a contract. Accessibility is checked by a machine, not by eye.'),
      when: t('Každé PR, bez emulátoru (JVM).', 'Every PR, no emulator (JVM).'),
      caught: t('Formulářové pole mělo 42 dp místo minimálních 48 dp.', 'The form field was 42 dp, below the 48 dp minimum.'),
    },
    {
      name: t('Swift unit testy', 'Swift unit tests'), tool: 'XCTest', mode: 'blocks',
      what: t('Nativní iOS kód: share extension, widget, keychain.', 'Native iOS code: share extension, widget, keychain.'),
      why: t('Swift se dosud jen kompiloval, nikdy nespustil.', 'Swift code used to be compiled but never run.'),
      when: t('Každé PR, v Xcode jobu (T5).', 'Every PR, inside the Xcode job (T5).'),
    },
    {
      name: t('Uživatelské cesty', 'User journeys'), tool: 'Maestro · stub edge', mode: 'nightly',
      what: t('Přihlášení, přehled, platba a další toky naklikané na Androidu i iOS proti stub backendu.', 'Login, home, payment and other flows tapped through on Android and iOS against a stub backend.'),
      why: t('Jen tak ověříme, že obrazovky drží pohromadě. Stub existuje jen v debug buildu a gate hlídá, že se nedostane do release.', 'The only way to prove screens fit together. The stub lives in debug builds only and a gate proves it never ships in release.'),
      when: t('Noční + před vydáním; verdikt blokuje TestFlight (T4).', 'Nightly + pre-release; the verdict blocks TestFlight (T4).'),
    },
    {
      name: t('Upgrade test', 'Upgrade test'), tool: t('APK N-1 → HEAD · persisted-key guard', 'APK N-1 → HEAD · persisted-key guard'), mode: 'nightly',
      what: t('Nainstaluje předchozí verzi, vytvoří stav, aktualizuje na novou a porovná, co přežilo.', 'Installs the previous version, creates state, upgrades to the new one and diffs what survived.'),
      why: t('Klient po aktualizaci nesmí přijít o přihlášení ani rozpracovanou platbu. Přejmenování uloženého klíče hlídá gate už v PR.', 'A customer must not lose their login or a pending payment on update. Renaming a persisted key is caught by a gate in the PR.'),
      when: t('Noční; guard klíčů každé PR na lehké lince (T9).', 'Nightly; the key guard runs every PR on the light lane (T9).'),
    },
    {
      name: t('Výkon startu', 'Startup performance'), tool: 'Macrobenchmark · Baseline Profile', mode: 'nightly',
      what: t('Studený start a jank na reálném buildu, porovnaný s naměřenou baseline.', 'Cold start and jank on a release-like build, compared with a measured baseline.'),
      why: t('SLO: TTID p75 pod 1,5 s. Rozpočet = šum p95 + 10 %, aby gate nekřičel naplano.', 'SLO: TTID p75 under 1.5 s. Budget = p95 noise + 10 %, so the gate does not cry wolf.'),
      when: t('Noční (T7).', 'Nightly (T7).'),
      caught: t('Benchmark modul se nikdy nekompiloval, takže neměřil nic.', 'The benchmark module never compiled, so it measured nothing.'),
    },
    {
      name: t('Fuzzing parserů', 'Parser fuzzing'), tool: 'Jazzer', mode: 'nightly',
      what: t('Náhodné a mutované vstupy do parserů SPAYD/QR, deep linků, push zpráv a BLE kodeků.', 'Random and mutated input into SPAYD/QR, deep-link, push and BLE codec parsers.'),
      why: t('To jsou vstupy od cizích lidí a zařízení. Parser nesmí spadnout ani alokovat gigabajty.', 'These inputs come from strangers and devices. A parser must not crash or allocate gigabytes.'),
      when: t('Noční, časový limit 10 min (T8).', 'Nightly, 10 min time box (T8).'),
      caught: t('Délka v BLE CBOR zprávě přetekla Int a vedla k OutOfMemoryError.', 'A length in a BLE CBOR message overflowed Int and caused an OutOfMemoryError.'),
    },
    {
      name: t('Bezpečnost a dodavatelský řetězec', 'Security and supply chain'), tool: 'osv-scanner · Gitleaks · MobSF · CycloneDX', mode: 'blocks',
      what: t('CVE v dodávaných knihovnách, uniklé tajemství, licence, statická analýza APK, SBOM.', 'CVEs in shipped libraries, leaked secrets, licences, static APK analysis, SBOM.'),
      why: t('Banka odpovídá i za kód, který nenapsala.', 'A bank is accountable for code it did not write.'),
      when: t('CVE, secrets a licence každé PR; MobSF a plný SBOM sweep nočně.', 'CVEs, secrets and licences every PR; MobSF and the full SBOM sweep nightly.'),
    },
    {
      name: t('Matice reálných zařízení', 'Real-device matrix'), tool: t('device cloud (vendor zatím nevybrán)', 'device cloud (vendor not chosen)'), mode: 'planned',
      what: t('Stejné Maestro cesty na fyzických telefonech.', 'The same Maestro journeys on physical phones.'),
      why: t('Emulátor neodhalí chyby konkrétních výrobců a OS verzí.', 'An emulator does not reveal vendor- and OS-specific bugs.'),
      when: t('Před vydáním (T10), čeká na rozhodnutí o nákladech.', 'Pre-release (T10), waiting on a cost decision.'),
    },
  ]

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<><span>OpenBank</span><span className="breadcrumb-sep" aria-hidden="true">/</span><span>{t('Systém', 'System')}</span><span className="breadcrumb-sep" aria-hidden="true">/</span><Link href="/system/tests">{t('Test Intelligence', 'Test Intelligence')}</Link><span className="breadcrumb-sep" aria-hidden="true">/</span><span className="breadcrumb-current">{t('Mobilní aplikace', 'Mobile app')}</span></>}
        icon={<Smartphone size={19} aria-hidden="true" style={{ color: 'var(--accent)' }} />}
        title={t('Jak testujeme mobilní aplikaci', 'How we test the mobile app')}
        subtitle={t('Co, čím, proč a kdy ověřujeme v Kotlin Multiplatform aplikaci pro Android a iOS a jak se výsledky dostanou do observability.', 'What we verify in the Kotlin Multiplatform app for Android and iOS, with which tools, why and when, and how results reach observability.')}
      />

      <ExplainerSection title={t('V kostce', 'At a glance')} subtitle={t('Jedna kódová základna, dvě platformy, peníze klientů. Proto testujeme ve vrstvách: hodně levných kontrol na každé PR, pár drahých nočně, a nic nebereme jako hotové, dokud jsme to neviděli selhat.', 'One codebase, two platforms, customers’ money. So we test in layers: many cheap checks on every PR, a few expensive ones nightly, and nothing counts as done until we have seen it fail.')}>
        <KeyNumbers items={[
          { value: '180+', label: t('souborů unit testů', 'unit-test files'), hint: t('shared + composeApp, commonTest i Android', 'shared + composeApp, common and Android') },
          { value: '111', label: t('schválených screenshotů', 'approved screenshots'), hint: t('komponenty design systému, světlý + tmavý režim', 'design-system components, light + dark') },
          { value: '10', label: t('položek strategie T1–T10', 'strategy items T1–T10'), hint: t('APP-ADR-0005', 'APP-ADR-0005') },
          { value: '4', label: t('reálné chyby nalezené hned při zavedení', 'real defects found on introduction'), hint: t('BLE overflow, znaménko úroku, mrtvý benchmark, 42 dp pole', 'BLE overflow, interest sign, dead benchmark, 42 dp field') },
        ]} />
      </ExplainerSection>

      <ExplainerSection title={t('Testovací pyramida', 'The test pyramid')} subtitle={t('Dole je to levné, rychlé a je toho hodně; nahoře drahé, pomalé a vzácné. Barva říká, jestli vrstva blokuje merge, běží nočně, jen hlásí, nebo je zatím v plánu.', 'At the bottom tests are cheap, fast and many; at the top costly, slow and few. Colour shows whether a layer blocks the merge, runs nightly, only reports, or is still planned.')}>
        <ModeLegend labels={modes} title={t('Režim:', 'Mode:')} />
        <div style={{ marginTop: 10 }}>
          <StackPyramid
            title={t('Testovací pyramida mobilní aplikace', 'Mobile app test pyramid')}
            costlyLabel={t('dražší, pomalejší', 'costlier, slower')}
            cheapLabel={t('levnější, rychlejší', 'cheaper, faster')}
            layers={[
              { label: t('Reálná zařízení', 'Real devices'), tools: 'T10', mode: 'planned' },
              { label: t('Cesty a upgrade', 'Journeys and upgrade'), tools: 'Maestro · N-1 → HEAD', mode: 'nightly' },
              { label: t('Výkon a fuzzing', 'Performance and fuzzing'), tools: 'Macrobenchmark · Jazzer', mode: 'nightly' },
              { label: t('Screenshoty, přístupnost, Swift', 'Screenshots, accessibility, Swift'), tools: 'Roborazzi · ATF · XCTest', mode: 'blocks' },
              { label: t('Odolnost a property testy', 'Resilience and property tests'), tools: 'Ktor MockEngine · kotest-property', mode: 'blocks' },
              { label: t('Unit testy', 'Unit tests'), tools: 'kotlin.test · Kover', mode: 'blocks' },
              { label: t('Statická analýza a supply chain', 'Static analysis and supply chain'), tools: 'detekt · ktlint · osv-scanner · Gitleaks', mode: 'blocks' },
            ]}
          />
        </div>
      </ExplainerSection>

      <ExplainerSection title={t('Každá vrstva: co, čím, proč, kdy', 'Each layer: what, with what, why, when')}>
        <LayerCards layers={layers} labels={modes} captions={{ what: t('Co:', 'What:'), why: t('Proč:', 'Why:'), when: t('Kdy:', 'When:'), caught: t('Už chytilo:', 'Already caught:') }} />
      </ExplainerSection>

      <ExplainerSection title={t('Cesta změny pipeline', 'How a change travels through the pipeline')} subtitle={t('CI běží na vlastních runnerech na jednom Macu. Těžká linka (Gradle, Xcode) zpracuje jeden job naráz, lehká linka nikdy nespouští Gradle, takže drobné kontroly nečekají ve frontě za buildem.', 'CI runs on self-hosted runners on one Mac. The heavy lane (Gradle, Xcode) runs one job at a time; the light lane never runs Gradle, so small checks never queue behind a build.')}>
        <PipelineStages labels={modes} stages={[
          { title: t('Pull request · těžká linka', 'Pull request · heavy lane'), when: t('každý push do PR', 'every push to a PR'), items: [
            { label: t('build Android + iOS framework', 'Android build + iOS framework'), mode: 'blocks' },
            { label: t('unit + property + odolnost', 'unit + property + resilience'), mode: 'blocks' },
            { label: t('screenshoty + přístupnost', 'screenshots + accessibility'), mode: 'blocks' },
            { label: 'XCTest', mode: 'blocks' },
            { label: t('detekt + ktlint + Kover', 'detekt + ktlint + Kover'), mode: 'blocks' },
          ] },
          { title: t('Pull request · lehká linka', 'Pull request · light lane'), when: t('souběžně, bez Gradle', 'in parallel, no Gradle'), items: [
            { label: t('guard uložených klíčů', 'persisted-key guard'), mode: 'blocks' },
            { label: t('edge kontrakt vs živé OpenAPI', 'edge contract vs live OpenAPI'), mode: 'blocks' },
            { label: t('Conventional Commits, actionlint', 'Conventional Commits, actionlint'), mode: 'blocks' },
            { label: t('CVE, secrets, licence', 'CVEs, secrets, licences'), mode: 'blocks' },
          ] },
          { title: t('Noc', 'Night'), when: t('cron, mimo pracovní dobu', 'cron, off hours'), items: [
            { label: 'Maestro', mode: 'nightly' },
            { label: t('upgrade test', 'upgrade test'), mode: 'nightly' },
            { label: 'Macrobenchmark', mode: 'nightly' },
            { label: 'Jazzer', mode: 'nightly' },
            { label: t('MobSF + plný SBOM sweep', 'MobSF + full SBOM sweep'), mode: 'advisory' },
          ] },
          { title: t('Vydání', 'Release'), when: t('TestFlight / Play', 'TestFlight / Play'), items: [
            { label: t('verdikt nočních běhů', 'nightly verdict'), mode: 'blocks' },
            { label: t('stub edge není v release APK', 'no stub edge in the release APK'), mode: 'blocks' },
            { label: t('baseline profil + limit velikosti 14 MB', 'baseline profile + 14 MB size budget'), mode: 'blocks' },
            { label: t('reálná zařízení', 'real devices'), mode: 'planned' },
          ] },
        ]} />
      </ExplainerSection>

      <ExplainerSection title={t('Od testu k observabilitě', 'From a test to observability')} subtitle={t('Výsledky nekončí v logu CI. Každý běh se zapíše jako metrika a dashboard s alerty upozorní, když testy zčervenají, začnou být nestabilní, nebo – což je nejzrádnější – přestanou běžet úplně.', 'Results do not end in a CI log. Every run becomes a metric, and a dashboard with alerts tells us when tests go red, turn flaky or – most treacherous of all – silently stop running.')}>
        <FlowChain
          title={t('Tok výsledků testů do Grafany', 'Test results flowing into Grafana')}
          steps={[
            { label: t('CI běh', 'CI run'), detail: t('JUnit XML, verdikty gate', 'JUnit XML, gate verdicts') },
            { label: t('agregace', 'aggregation'), detail: t('ci-quality-aggregate: počty, flaky, délky', 'ci-quality-aggregate: counts, flaky, durations') },
            { label: 'OTLP', detail: t('ci.* gauge, bearer token', 'ci.* gauges, bearer token') },
            { label: t('ingest gateway', 'ingest gateway'), detail: t('allow-list atributů, limit velikosti', 'attribute allow-list, size cap') },
            { label: 'Prometheus', detail: t('remote write, 30 dní', 'remote write, 30 days') },
            { label: 'Grafana', detail: t('OpenBank – CI quality + alerty', 'OpenBank – CI quality + alerts') },
          ]}
        />
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 8, lineHeight: 1.5 }}>
          {t('Alerty: chybějící data déle než 26 h, tiše přeskočené testy, selhání nočního gate, fuzzingu nebo cesty, nestabilní testy a strop kardinality. Nestabilní test dostane vlastní issue, nikdy tiché opakování.', 'Alerts: data missing for over 26 h, silently skipped tests, failed nightly gate, fuzz or journey, flaky tests and a cardinality ceiling. A flaky test gets its own issue, never a silent retry.')}
        </p>
      </ExplainerSection>

      <ExplainerSection title={t('Pravidlo falzifikace', 'The falsification rule')} subtitle={t('Gate, který nikdy nebyl vidět selhat, nic nechrání – jen tiskne zelenou. Proto každá nová kontrola musí v PR doložit, že na podstrčenou chybu zčervená.', 'A gate never seen failing protects nothing – it just prints green. So every new check must show in its PR that it turns red on a planted defect.')}>
        <FalsificationLoop
          title={t('Smyčka falzifikace gate', 'Gate falsification loop')}
          center={t('gate je důvěryhodný', 'the gate is trusted')}
          steps={[
            t('napiš test nebo gate', 'write the test or gate'),
            t('podstrč chybu → musí selhat', 'plant a defect → it must fail'),
            t('vrať chybu → musí projít', 'revert the defect → it must pass'),
            t('zapiš selhávající řádek do PR', 'record the failing line in the PR'),
          ]}
        />
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 8, lineHeight: 1.5 }}>
          {t('Tohle pravidlo samo odhalilo tři „zelené“ kontroly, které nic nedělaly: test, jehož runCatching spolkl chybu, kontrolu, která našla zakomentovaný řádek, a sken APK, kterému SIGPIPE vracel falešné „čisto“.', 'This rule alone exposed three “green” checks that did nothing: a test whose runCatching swallowed the failure, a check that matched a commented-out line, and an APK scan that SIGPIPE turned into a false “clean”.')}
        </p>
      </ExplainerSection>

      <ExplainerSection title={t('Kde jsme dnes', 'Where we are today')}>
        <StatusList labels={{ blocks: t('v main', 'on main'), nightly: t('v PR', 'in review'), advisory: t('jen hlásí', 'reports only'), planned: t('nezačato', 'not started') }} items={[
          { mode: 'blocks', label: t('Základ:', 'Foundation:'), detail: t('statická analýza, unit testy, Kover, screenshoty, edge kontrakt, supply-chain gate a gate z minulých incidentů.', 'static analysis, unit tests, Kover, screenshots, edge contract, supply-chain gates and gates codified from past incidents.') },
          { mode: 'blocks', label: t('Observabilita:', 'Observability:'), detail: t('ingest gateway, dashboard a alerty jsou nasazené v gitops; data začnou téct po nastavení tokenu.', 'the ingest gateway, dashboard and alerts are in gitops; data starts flowing once the token is set.') },
          { mode: 'nightly', label: t('T1–T9:', 'T1–T9:'), detail: t('implementováno a falzifikováno v PR #800–#812 (app repo), čeká na review a merge.', 'implemented and falsified in PRs #800–#812 (app repo), awaiting review and merge.') },
          { mode: 'planned', label: 'T10:', detail: t('reálná zařízení – čeká na výběr dodavatele a schválení nákladů.', 'real devices – waiting on a vendor choice and cost approval.') },
        ]} />
      </ExplainerSection>
    </div>
  )
}
