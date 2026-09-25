// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import {
  ArrowRight,
  BookOpenCheck,
  Boxes,
  ClipboardCheck,
  BriefcaseBusiness,
  Check,
  ChevronRight,
  CircleGauge,
  Code2,
  Database,
  FileKey2,
  FileText,
  FlaskConical,
  GitMerge,
  GitPullRequestArrow,
  Lightbulb,
  Layers3,
  Pause,
  Play,
  Rocket,
  ScanSearch,
  Scale,
  ShieldCheck,
  Sparkles,
  TerminalSquare,
  UsersRound,
  Workflow,
} from 'lucide-react'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { GateCatalogExplorer } from '@/components/devops/GateCatalogExplorer'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import styles from './page.module.css'

type Audience = 'developer' | 'devops' | 'business'

type Stage = {
  id: string
  eyebrowCs: string
  eyebrowEn: string
  titleCs: string
  titleEn: string
  summaryCs: string
  summaryEn: string
  systemCs: string
  systemEn: string
  proofCs: string
  proofEn: string
  href: string
  linkCs: string
  linkEn: string
  accent: string
  icon: React.ElementType
  actions: Record<Audience, { cs: string; en: string }>
}

type Gate = {
  id: string
  number: string
  titleCs: string
  titleEn: string
  questionCs: string
  questionEn: string
  checksCs: string
  checksEn: string
  failureCs: string
  failureEn: string
  valueCs: string
  valueEn: string
  modeCs: string
  modeEn: string
  icon: React.ElementType
  href: string
}

const STAGES: Stage[] = [
  {
    id: 'shape', eyebrowCs: '01 · ZÁMĚR', eyebrowEn: '01 · INTENT',
    titleCs: 'Pojmenuj změnu', titleEn: 'Shape the change',
    summaryCs: 'Issue popíše hodnotu, riziko a hotový výsledek dřív, než vznikne kód.',
    summaryEn: 'An issue names the value, risk, and finished outcome before code exists.',
    systemCs: 'Issue → větev → ADR nebo threat model, když rozhodnutí či riziko přesahuje běžnou změnu.',
    systemEn: 'Issue → branch → ADR or threat model when the decision or risk reaches beyond a routine change.',
    proofCs: 'Dohledatelný důvod změny', proofEn: 'Traceable reason for change',
    href: '/docs/adr', linkCs: 'Prozkoumat rozhodnutí', linkEn: 'Explore decisions', accent: '#a78bfa', icon: Lightbulb,
    actions: {
      developer: { cs: 'Sepíšu acceptance kritéria a zvolím správný bounded context.', en: 'I write acceptance criteria and choose the owning bounded context.' },
      devops: { cs: 'Označím dopad na infrastrukturu, data, rozhraní a provozní riziko.', en: 'I flag infrastructure, data, contract, and operational impact.' },
      business: { cs: 'Potvrdím očekávanou hodnotu a co znamená „hotovo“.', en: 'I confirm expected value and what “done” means.' },
    },
  },
  {
    id: 'build', eyebrowCs: '02 · TVORBA', eyebrowEn: '02 · BUILD',
    titleCs: 'Vyvíjej s guardraily', titleEn: 'Build with guardrails',
    summaryCs: 'Kód, test a smlouva vznikají spolu; API, databáze i události mají vlastní bezpečný postup.',
    summaryEn: 'Code, test, and contract evolve together; APIs, databases, and events each have a safe path.',
    systemCs: 'Kotlin/Quarkus nebo Next.js, cílené testy, OpenAPI diff, Flyway migrace a lokální lint.',
    systemEn: 'Kotlin/Quarkus or Next.js, focused tests, OpenAPI diff, Flyway migration, and local lint.',
    proofCs: 'Reprodukovatelná změna s testem', proofEn: 'Reproducible change with a test',
    href: '/system/tests', linkCs: 'Otevřít Test Intelligence', linkEn: 'Open Test Intelligence', accent: '#38bdf8', icon: Code2,
    actions: {
      developer: { cs: 'Implementuji nejmenší ucelený řez a dokazuji nové chování testem.', en: 'I implement the smallest coherent slice and prove new behavior with a test.' },
      devops: { cs: 'Držím build reprodukovatelný, konfiguraci validní a závislosti uzamčené.', en: 'I keep the build reproducible, configuration valid, and dependencies locked.' },
      business: { cs: 'Vidím, které pravidlo nebo zákaznický výsledek se skutečně mění.', en: 'I can see which policy or customer outcome actually changes.' },
    },
  },
  {
    id: 'verify', eyebrowCs: '03 · CI', eyebrowEn: '03 · CI',
    titleCs: 'Dokaž kvalitu', titleEn: 'Prove quality',
    summaryCs: 'Path-scoped pipeline spustí jen relevantní buildy, ale globální governance brány hlídají celý celek.',
    summaryEn: 'The path-scoped pipeline runs relevant builds while global governance gates protect the whole system.',
    systemCs: 'Testy, ktlint, detekt, coverage ratchet, kontrakty, manifesty, Gitleaks, CodeQL a Trivy.',
    systemEn: 'Tests, ktlint, detekt, coverage ratchet, contracts, manifests, Gitleaks, CodeQL, and Trivy.',
    proofCs: 'Zelené a auditovatelné kontroly', proofEn: 'Green, auditable checks',
    href: '/security', linkCs: 'Zobrazit bezpečnostní důkazy', linkEn: 'View security evidence', accent: '#2dd4bf', icon: ShieldCheck,
    actions: {
      developer: { cs: 'Opravuji příčinu selhání, neobcházím gate a nesnižuji coverage.', en: 'I fix the cause of a failure, never bypass a gate, and never lower coverage.' },
      devops: { cs: 'Udržuji fail-closed brány, izolované runnery a čitelnou stopu každého verdiktu.', en: 'I maintain fail-closed gates, isolated runners, and a readable trail for every verdict.' },
      business: { cs: 'Dostávám průkazný verdikt, ne neurčité „u mě to funguje“.', en: 'I get an evidence-backed verdict, not a vague “works on my machine.”' },
    },
  },
  {
    id: 'review', eyebrowCs: '04 · KONTROLA', eyebrowEn: '04 · REVIEW',
    titleCs: 'Zkontroluj rozhodnutí', titleEn: 'Review the decision',
    summaryCs: 'Pull request spojuje kontext, diff a automatické důkazy. Politika žádá lidské review, ale GitHub je zatím nevynucuje.',
    summaryEn: 'A pull request joins context, diff, and automated evidence. Policy asks for human review, but GitHub does not enforce it yet.',
    systemCs: 'Podepsané Conventional Commits a povinné kontroly blokují merge; cíl 1 schválení, 2 u money-path, je zatím nevynucený.',
    systemEn: 'Signed Conventional Commits and required checks block merge; the one-approval target, two for money paths, is not enforced yet.',
    proofCs: 'Dohledatelné rozhodnutí a přiznaná mezera review', proofEn: 'Traceable decision and an explicit review gap',
    href: 'https://github.com/JiRaska/open-bank-oss/blob/main/openbank-libs/governance/rules.yaml', linkCs: 'Přečíst pravidla review', linkEn: 'Read review policy', accent: '#fbbf24', icon: GitPullRequestArrow,
    actions: {
      developer: { cs: 'Vysvětlím proč, ukážu důkaz a zapracuji připomínky reviewera.', en: 'I explain why, show evidence, and resolve reviewer feedback.' },
      devops: { cs: 'Ověřím provozní dopady, rollback, kapacitu a bezpečnost nasazení.', en: 'I verify operational impact, rollback, capacity, and deployment safety.' },
      business: { cs: 'U kritických toků chci nezávislé posouzení; dokud není vynucené, chrání je threat model a CI gate.', en: 'I want independent judgment for critical flows; until enforced, threat models and CI gates provide compensating controls.' },
    },
  },
  {
    id: 'release', eyebrowCs: '05 · RELEASE', eyebrowEn: '05 · RELEASE',
    titleCs: 'Vytvoř důvěryhodný artefakt', titleEn: 'Create a trusted artifact',
    summaryCs: 'Merge spustí verzování podle skutečného typu a rozsahu změny; ruční přepis verzí není součást procesu.',
    summaryEn: 'Merge triggers versioning from the actual change type and scope; manual version edits are not part of the process.',
    systemCs: 'release-please → image se SHA tagem → podpis a SBOM attestace svázané s digestem → scan registru.',
    systemEn: 'release-please → SHA-tagged image → signature and SBOM attestation bound to its digest → registry scan.',
    proofCs: 'Neměnný a podepsaný artefakt', proofEn: 'Immutable, signed artifact',
    href: '/system/inventory', linkCs: 'Prohlédnout inventář', linkEn: 'Browse inventory', accent: '#fb7185', icon: Boxes,
    actions: {
      developer: { cs: 'Commit message přesně pojmenuje změnu a automaticky vytvoří changelog.', en: 'The commit message names the change precisely and generates the changelog.' },
      devops: { cs: 'Podepisuji image a vážu k jeho digestu SBOM i provenance.', en: 'I sign the image and bind its SBOM and provenance to the digest.' },
      business: { cs: 'Dohledám původ image a ověřím dostupné bezpečnostní důkazy; chybějící release bundle není důkaz.', en: 'I can trace the image origin and inspect available security evidence; a missing release bundle is not proof.' },
    },
  },
  {
    id: 'deploy', eyebrowCs: '06 · CD', eyebrowEn: '06 · CD',
    titleCs: 'Propaguj přes GitOps', titleEn: 'Promote through GitOps',
    summaryCs: 'Nasazení je deklarovaná změna požadovaného stavu, ne neviditelný příkaz z laptopu.',
    summaryEn: 'Deployment is a declared desired-state change, not an invisible command from a laptop.',
    systemCs: 'GitOps PR → kontrola manifestu → ArgoCD sync → Kyverno ověření podpisu a SBOM attestace.',
    systemEn: 'GitOps PR → manifest checks → ArgoCD sync → Kyverno signature and SBOM-attestation verification.',
    proofCs: 'GitOps stav s konkrétním SHA tagem', proofEn: 'GitOps state with a specific SHA tag',
    href: '/infrastructure/topology', linkCs: 'Zobrazit topologii', linkEn: 'View topology', accent: '#818cf8', icon: Rocket,
    actions: {
      developer: { cs: 'Sleduji, že moje verze prošla až do cílového prostředí.', en: 'I follow my version all the way into the target environment.' },
      devops: { cs: 'Propaguji SHA tag, sleduji sync a při problému vracím deklarovaný stav.', en: 'I promote a SHA tag, observe sync, and restore declared state when needed.' },
      business: { cs: 'Nasazení má vlastní GitOps PR, automatické kontroly a dohledatelnou historii.', en: 'A deployment has its own GitOps PR, automated checks, and a traceable history.' },
    },
  },
  {
    id: 'learn', eyebrowCs: '07 · PROVOZ', eyebrowEn: '07 · OPERATE',
    titleCs: 'Pozoruj a uč se', titleEn: 'Observe and learn',
    summaryCs: 'Telemetrie uzavírá smyčku: měří dopad, odhaluje regresi a vrací poznatek do dalšího issue.',
    summaryEn: 'Telemetry closes the loop: measure impact, detect regressions, and feed learning into the next issue.',
    systemCs: 'SLO, metriky, logy, traces, DORA, incidenty a rollback evidence.',
    systemEn: 'SLOs, metrics, logs, traces, DORA, incidents, and rollback evidence.',
    proofCs: 'Měřený dopad v produkci', proofEn: 'Measured production impact',
    href: '/observability', linkCs: 'Otevřít observabilitu', linkEn: 'Open observability', accent: '#34d399', icon: CircleGauge,
    actions: {
      developer: { cs: 'Ověřím chování na reálných signálech a poznatek proměním v další změnu.', en: 'I verify behavior with real signals and turn learning into the next change.' },
      devops: { cs: 'Hlídám SLO, progresivní rollout, drift a připravenost na obnovu.', en: 'I watch SLOs, progressive rollout, drift, and recovery readiness.' },
      business: { cs: 'Vidím, zda změna přinesla výsledek bez nepřijatelného rizika.', en: 'I see whether the change delivered value without unacceptable risk.' },
    },
  },
]

const AUDIENCES: Array<{ id: Audience; cs: string; en: string; icon: React.ElementType }> = [
  { id: 'developer', cs: 'Vývojář', en: 'Developer', icon: TerminalSquare },
  { id: 'devops', cs: 'DevOps', en: 'DevOps', icon: Workflow },
  { id: 'business', cs: 'Business', en: 'Business', icon: BriefcaseBusiness },
]

const GATES: Gate[] = [
  {
    id: 'compile', number: 'G1', titleCs: 'Sestavení a statická kvalita', titleEn: 'Build and static quality',
    questionCs: 'Lze změnu čistě sestavit a odpovídá našim pravidlům kódu?', questionEn: 'Can the change build cleanly and follow our code rules?',
    checksCs: 'Kompilace, ktlint, detekt, TypeScript type-check a lint. Fleet lint navíc odhalí dopad sdílených změn mimo dotčenou cestu.',
    checksEn: 'Compilation, ktlint, detekt, TypeScript type-check, and lint. Fleet lint also catches shared-change impact outside the touched path.',
    failureCs: 'PR se nesmí sloučit, dokud se neopraví konkrétní chyba nebo pravidlo.', failureEn: 'The PR cannot merge until the concrete error or rule violation is fixed.',
    valueCs: 'Snižuje počet defektů, které by jinak spotřebovaly dražší testovací a review kapacitu.', valueEn: 'Reduces defects that would otherwise consume more expensive test and review capacity.',
    modeCs: 'Path-scoped + fleet kontrola', modeEn: 'Path-scoped + fleet check', icon: Code2, href: '/system/tests',
  },
  {
    id: 'tests', number: 'G2', titleCs: 'Testy a coverage ratchet', titleEn: 'Tests and coverage ratchet',
    questionCs: 'Je nové chování prokázané a nezmenšila se ochranná síť?', questionEn: 'Is the new behavior proven without shrinking the safety net?',
    checksCs: 'Unit, integrační, kontraktní a UI testy podle dotčené komponenty. Coverage může zůstat nebo růst, nikdy klesnout.',
    checksEn: 'Unit, integration, contract, and UI tests for the affected component. Coverage may hold or rise, never fall.',
    failureCs: 'Selhání testu nebo pokles ratchetu blokuje merge; flaky stav se nezaměňuje za zelený výsledek.', failureEn: 'A failed test or ratchet regression blocks merge; flaky state is not treated as green.',
    valueCs: 'Chrání zákaznické a finanční scénáře před opakovanou regresí.', valueEn: 'Protects customer and financial journeys from repeated regression.',
    modeCs: 'Blokující, rozsah podle změny', modeEn: 'Blocking, change-scoped', icon: FlaskConical, href: '/system/tests',
  },
  {
    id: 'contracts', number: 'G3', titleCs: 'API, data a event kontrakty', titleEn: 'API, data, and event contracts',
    questionCs: 'Rozumějí si producenti, konzumenti a databáze i po změně?', questionEn: 'Will producers, consumers, and databases still agree after the change?',
    checksCs: 'OpenAPI diff a contract test pro API; Flyway migrace pro DB; zpětně kompatibilní verze event schématu.',
    checksEn: 'OpenAPI diff and contract test for APIs; Flyway migration for DB; backward-compatible event-schema versioning.',
    failureCs: 'Nekompatibilní nebo nedokumentovaná změna zastaví PR a vyžádá explicitní návrh migrace.', failureEn: 'An incompatible or undocumented change stops the PR and requires an explicit migration design.',
    valueCs: 'Brání tichému rozbití návazných služeb a ztrátě dat při nasazení.', valueEn: 'Prevents silent downstream breakage and data loss during deployment.',
    modeCs: 'Podmíněná obsahem diffu', modeEn: 'Triggered by diff content', icon: Database, href: '/docs/api',
  },
  {
    id: 'governance', number: 'G4', titleCs: 'Architektura a governance', titleEn: 'Architecture and governance',
    questionCs: 'Zůstává změna uvnitř dohodnuté architektury a pravidel?', questionEn: 'Does the change remain inside the agreed architecture and rules?',
    checksCs: 'Doménová čistota, vlastnictví služby, validní konfigurace, ADR registry, správný release scope a desítky fleet guardů z rules.yaml.',
    checksEn: 'Domain purity, service ownership, valid configuration, ADR registry, correct release scope, and fleet guards derived from rules.yaml.',
    failureCs: 'Gate ukáže porušený invariant; oprava patří do zdroje pravdy, ne do odvozeného artefaktu.', failureEn: 'The gate names the violated invariant; the fix belongs in the source of truth, not a derived artifact.',
    valueCs: 'Udržuje desítky služeb čitelné a provozovatelně konzistentní.', valueEn: 'Keeps dozens of services understandable and operationally consistent.',
    modeCs: 'Globální guardy + ratchety', modeEn: 'Global guards + ratchets', icon: Scale, href: '/docs/adr',
  },
  {
    id: 'security', number: 'G5', titleCs: 'Bezpečnost a supply chain', titleEn: 'Security and supply chain',
    questionCs: 'Nevnáší změna tajemství, známou zranitelnost nebo rizikový vzor?', questionEn: 'Does the change introduce a secret, known vulnerability, or risky pattern?',
    checksCs: 'Gitleaks, CodeQL, dependency review, Trivy a bezpečnostní regrese; workflow závislosti jsou připnuté a prověřené.',
    checksEn: 'Gitleaks, CodeQL, dependency review, Trivy, and security regression checks; workflow dependencies are pinned and verified.',
    failureCs: 'Selhání povinných kontrol, například Gitleaks, blokuje merge. CodeQL běží podle rozsahu změny a není required check.', failureEn: 'Failure of required checks such as Gitleaks blocks merge. CodeQL runs by change scope and is not a required check.',
    valueCs: 'Snižuje pravděpodobnost incidentu a dokládá průběžnou péči o ICT riziko.', valueEn: 'Reduces incident likelihood and demonstrates continuous ICT-risk care.',
    modeCs: 'Povinné kontroly + nepovinná analýza', modeEn: 'Required checks + non-required analysis', icon: ScanSearch, href: '/security',
  },
  {
    id: 'manifests', number: 'G6', titleCs: 'Manifesty a policy-as-code', titleEn: 'Manifests and policy as code',
    questionCs: 'Je požadovaný provozní stav validní, úplný a v souladu s politikami?', questionEn: 'Is the desired operational state valid, complete, and policy-compliant?',
    checksCs: 'Validate manifests, kontroly duplicitních YAML klíčů, GitOps coverage a OPA policy gate.',
    checksEn: 'Validate manifests, duplicate-YAML-key checks, GitOps coverage, and the OPA policy gate.',
    failureCs: 'Neplatný nebo neúplný deklarovaný stav se do main nedostane.', failureEn: 'Invalid or incomplete desired state cannot enter main.',
    valueCs: 'Brání situaci, kdy je aplikace správná, ale prostředí ji nasadí chybně nebo nebezpečně.', valueEn: 'Prevents correct application code from being deployed into an unsafe or broken environment.',
    modeCs: 'Globální blokující brána', modeEn: 'Global blocking gate', icon: FileKey2, href: '/infrastructure',
  },
  {
    id: 'review', number: 'G7', titleCs: 'Review a odpovědnost', titleEn: 'Review and accountability',
    questionCs: 'Co je u PR skutečně vynucené a kde zůstává mezera?', questionEn: 'What does the PR actually enforce, and where is the gap?',
    checksCs: 'Podepsaný commit, vazba na issue a povinné CI kontroly. Politika žádá 1 review, u money-path 2 a threat model; počet schválení GitHub zatím nevynucuje.',
    checksEn: 'Signed commit, issue linkage, and required CI checks. Policy asks for one review, two plus a threat model for money paths; GitHub does not yet enforce approval counts.',
    failureCs: 'Chybějící podpis či povinná kontrola blokuje merge. Chybějící lidské schválení ho dnes samo nezastaví — tato mezera je evidovaná.',
    failureEn: 'A missing signature or required check blocks merge. Missing human approval alone does not stop it today — this gap is tracked.',
    valueCs: 'Ukazuje rozdíl mezi cílovou čtyřočkovou politikou a skutečným vynucením.', valueEn: 'Makes the gap between four-eyes policy and actual enforcement visible.',
    modeCs: 'Review politika dosud nevynucená', modeEn: 'Review policy not yet enforced', icon: UsersRound, href: 'https://github.com/JiRaska/open-bank-oss/blob/main/openbank-libs/governance/rules.yaml',
  },
  {
    id: 'admission', number: 'G8', titleCs: 'Artefakt a admission', titleEn: 'Artifact and admission',
    questionCs: 'Je nasazovaný image přesně ten prověřený a má platný původ?', questionEn: 'Is the deployed image exactly the verified one with valid provenance?',
    checksCs: 'Image má neměnný digest, Cosign podpis a CycloneDX SBOM attestaci. Kyverno při admission vynucuje podpis a SBOM; provenance je samostatný důkaz původu.',
    checksEn: 'The image has an immutable digest, Cosign signature, and CycloneDX SBOM attestation. Kyverno enforces the signature and SBOM at admission; provenance is separate origin evidence.',
    failureCs: 'Nepodepsaný nebo neatestovaný image cluster odmítne, i kdyby předchozí CI bylo zelené.', failureEn: 'The cluster rejects an unsigned or unattested image even when earlier CI was green.',
    valueCs: 'Uzavírá mezeru mezi „otestovali jsme“ a „toto skutečně běží“.', valueEn: 'Closes the gap between “we tested it” and “this is what actually runs.”',
    modeCs: 'Post-merge + runtime enforcement', modeEn: 'Post-merge + runtime enforcement', icon: ShieldCheck, href: '/system/inventory',
  },
]

// Concrete manifest examples for the teaching lenses. Admission is a separate
// post-merge/runtime control, so it is intentionally not mapped to a CI gate.
const GATE_EXAMPLES: Record<string, string[]> = {
  compile: ['domain-purity-gate', 'yamllint'],
  tests: ['test-intelligence-ecosystem', 'scheduler-exercised-in-tests'],
  contracts: ['api-contract-gate', 'db-migration-gate', 'schema-compat-gate'],
  governance: ['adr-registry-integrity-check', 'release-scope-mismatch-gate'],
  security: ['threat-model-coverage', 'workflow-supply-chain'],
  manifests: ['gitops-ref-integrity-guard', 'duplicate-yaml-key-guard'],
  review: ['ruleset-context-parity', 'security-checklist-money-path'],
  admission: [],
}

const DIFFERENTIATORS: Array<{
  id: string
  index: string
  titleCs: string
  titleEn: string
  headlineCs: string
  headlineEn: string
  mechanismCs: string
  mechanismEn: string
  outcomeCs: string
  outcomeEn: string
  linkCs: string
  linkEn: string
  href: string
  icon: React.ElementType
}> = [
  {
    id: 'governance', index: '01', titleCs: 'Governance-as-code', titleEn: 'Governance as code',
    headlineCs: 'Pravidla umějí říct „ne“ sama.', headlineEn: 'Rules can say “no” themselves.',
    mechanismCs: 'rules.yaml je autoritativní zdroj pravidel. CI vynucuje architektonické hranice, kontrakty, release scope i ochranu money-path služeb.',
    mechanismEn: 'rules.yaml is the authoritative rule source. CI enforces architectural boundaries, contracts, release scope, and money-path protection.',
    outcomeCs: 'Stejný standard pro každou službu, každého člověka i agenta.',
    outcomeEn: 'The same standard for every service, person, and agent.',
    linkCs: 'Prohlédnout governance', linkEn: 'Explore governance', href: '/docs/control-tower', icon: Scale,
  },
  {
    id: 'documentation', index: '02', titleCs: 'Documentation-as-code', titleEn: 'Documentation as code',
    headlineCs: 'Dokumentace vzniká ze zdroje a hlídá svůj drift.', headlineEn: 'Documentation is derived and drift-checked.',
    mechanismCs: 'ADR mají strojově čitelný registr a generovaný index; servisní runbooky vznikají z governance a GitOps manifestů. CI odhalí, když se výstup rozchází se zdrojem.',
    mechanismEn: 'ADRs have a machine-readable registry and generated index; service runbooks come from governance and GitOps manifests. CI detects drift from their sources.',
    outcomeCs: 'Vývojář i on-call čtou stejnou aktuální podobu rozhodnutí a provozu.',
    outcomeEn: 'Developers and on-call staff read the same current decisions and operating model.',
    linkCs: 'Otevřít rozhodnutí', linkEn: 'Open decisions', href: '/docs/adr', icon: FileText,
  },
  {
    id: 'contracts', index: '03', titleCs: 'Kontrakty jako součást změny', titleEn: 'Contracts travel with change',
    headlineCs: 'Dopad API změny je vidět před mergem.', headlineEn: 'API impact is visible before merge.',
    mechanismCs: 'OpenAPI diff klasifikuje změnu kontraktu odděleně od release verze. Contract testy hlídají očekávání konzumentů; změny DB a eventů mají vlastní migraci a verzi.',
    mechanismEn: 'OpenAPI diff classifies contract change separately from release version. Contract tests protect consumer expectations; DB and event changes have their own migrations and versions.',
    outcomeCs: 'Méně překvapení mezi službami při nasazení.', outcomeEn: 'Fewer surprises between services at deployment.',
    linkCs: 'Prohlédnout API katalog', linkEn: 'Explore API catalog', href: '/docs/api', icon: Layers3,
  },
  {
    id: 'sbom', index: '04', titleCs: 'SBOM svázaný s image', titleEn: 'SBOM bound to the image',
    headlineCs: 'Víme, co obsahuje právě nasazovaný image.', headlineEn: 'We know what the deployed image contains.',
    mechanismCs: 'CycloneDX SBOM je podepsaná attestace konkrétního digestu image, nikoli volný soubor v CI. Kyverno při admission vynucuje platný podpis i SBOM.',
    mechanismEn: 'The CycloneDX SBOM is a signed attestation for a specific image digest, not a loose CI file. Kyverno requires a valid signature and SBOM at admission.',
    outcomeCs: 'Ověření pokračuje až k artefaktu, který cluster skutečně přijímá.',
    outcomeEn: 'Verification reaches the artifact the cluster actually admits.',
    linkCs: 'Prohlédnout obsah SBOM (ne attestaci)', linkEn: 'Explore SBOM contents (not the attestation)', href: '/system/inventory', icon: Boxes,
  },
  {
    id: 'releases', index: '05', titleCs: 'Release jako důkaz', titleEn: 'Release as evidence',
    headlineCs: 'Verze může nést ověřitelnou stopu.', headlineEn: 'A version can carry a verifiable trail.',
    mechanismCs: 'release-please odvozuje verzi a changelog z podepsaných commitů. Release evidence bundle se SBOM, provenance a kontrolami vzniká best-effort; jeho výpadek release zatím neblokuje.',
    mechanismEn: 'release-please derives version and changelog from signed commits. The release-evidence bundle with SBOM, provenance, and checks is best-effort; its failure does not yet block a release.',
    outcomeCs: 'Existující důkazy lze ověřit; chybějící bundle je přiznaná mezera, nikoli důkaz.',
    outcomeEn: 'Present evidence can be verified; a missing bundle is an explicit gap, not proof.',
    linkCs: 'Prohlédnout důkazy', linkEn: 'Explore evidence', href: '/docs/control-tower', icon: ClipboardCheck,
  },
  {
    id: 'reality', index: '06', titleCs: 'Plán proti realitě', titleEn: 'Plan against reality',
    headlineCs: 'Stav kontroly nedokládá zelená ikonka.', headlineEn: 'A green icon is not proof of a control.',
    mechanismCs: 'Control Tower propojuje regulaci, kontrolu a důkaz. U kontrol s živým signálem čte stav z manifestů; ostatní rozlišuje jako vynucené, částečné, auditní či plánované.',
    mechanismEn: 'Control Tower links regulation, control, and evidence. Controls with a live signal read status from manifests; others are distinguished as enforced, partial, audit, or planned.',
    outcomeCs: 'Business i DevOps vidí doložený stav bez přikrášlování.',
    outcomeEn: 'Business and DevOps see an evidenced state without embellishment.',
    linkCs: 'Otevřít Control Tower', linkEn: 'Open Control Tower', href: '/docs/control-tower', icon: CircleGauge,
  },
]

export default function SdlcPage() {
  const { t } = useLanguage()
  const [activeIndex, setActiveIndex] = useState(0)
  const [audience, setAudience] = useState<Audience>('developer')
  const [activeGateId, setActiveGateId] = useState(GATES[0].id)
  const [playing, setPlaying] = useState(true)
  const [reduceMotion, setReduceMotion] = useState(false)

  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)')
    const sync = () => {
      setReduceMotion(media.matches)
      if (media.matches) setPlaying(false)
    }
    sync()
    media.addEventListener('change', sync)
    return () => media.removeEventListener('change', sync)
  }, [])

  useEffect(() => {
    if (!playing || reduceMotion) return
    const timer = window.setInterval(() => setActiveIndex(current => (current + 1) % STAGES.length), 4200)
    return () => window.clearInterval(timer)
  }, [playing, reduceMotion])

  const active = STAGES[activeIndex]
  const activeGate = GATES.find(gate => gate.id === activeGateId) ?? GATES[0]
  const progress = useMemo(() => `${(activeIndex / (STAGES.length - 1)) * 100}%`, [activeIndex])

  const selectStage = (index: number) => {
    setActiveIndex(index)
    setPlaying(false)
  }

  return (
    <div className={styles.page}>
      <DocsPageHeader
        crumbs={<>
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <span>{t('Platforma', 'Platform')}</span><span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">SDLC / CI·CD</span>
        </>}
        title={t('Od nápadu k bezpečnému provozu', 'From idea to safe operation')}
        subtitle={t(
          'Jedna proklikávací mapa toho, jak OpenBank mění záměr v provozní hodnotu — s důkazem, odpovědností a bezpečností v každém kroku.',
          'One interactive map of how OpenBank turns intent into operational value — with evidence, accountability, and security at every step.',
        )}
        icon={<Sparkles aria-hidden="true" size={20} />}
      />

      <section className={styles.hero} aria-labelledby="journey-title">
        <div className={styles.heroGlow} aria-hidden="true" />
        <div className={styles.heroTopline}>
          <div>
            <span className={styles.kicker}>{t('ŽIVÝ PRŮVODCE DODÁVKOU', 'LIVE DELIVERY GUIDE')}</span>
            <h2 id="journey-title">{t('Každá změna je ověřitelná cesta', 'Every change is a verifiable journey')}</h2>
          </div>
          <button
            type="button"
            className={styles.playControl}
            onClick={() => setPlaying(current => !current)}
            aria-label={playing ? t('Pozastavit animaci', 'Pause animation') : t('Spustit animaci', 'Play animation')}
            aria-pressed={playing}
          >
            {playing ? <Pause aria-hidden="true" size={14} /> : <Play aria-hidden="true" size={14} />}
            {playing ? t('Pozastavit', 'Pause') : t('Přehrát cestu', 'Play journey')}
          </button>
        </div>

        <div className={styles.metrics} aria-label={t('Hlavní principy procesu', 'Core process principles')}>
          <div><strong>7</strong><span>{t('navazujících fází', 'connected stages')}</span></div>
          <div><strong>1</strong><span>{t('dohledatelný řetězec důkazů', 'traceable chain of evidence')}</span></div>
          <div><strong>8</strong><span>{t('učebních otázek · úplný katalog níže', 'learning questions · full catalog below')}</span></div>
        </div>

        <div className={styles.journeyScroll}>
          <div className={styles.journey} style={{ '--journey-progress': progress } as React.CSSProperties}>
            <div className={styles.track} aria-hidden="true"><span /></div>
            {STAGES.map((stage, index) => {
              const Icon = stage.icon
              const selected = activeIndex === index
              const complete = index < activeIndex
              return (
                <button
                  key={stage.id}
                  type="button"
                  className={`${styles.stage} ${selected ? styles.stageActive : ''} ${complete ? styles.stageComplete : ''}`}
                  style={{ '--stage-accent': stage.accent } as React.CSSProperties}
                  onClick={() => selectStage(index)}
                  aria-pressed={selected}
                  aria-controls="sdlc-stage-detail"
                >
                  <span className={styles.stageNode}>
                    {complete ? <Check aria-hidden="true" size={18} /> : <Icon aria-hidden="true" size={18} />}
                  </span>
                  <span className={styles.stageEyebrow}>{t(stage.eyebrowCs, stage.eyebrowEn)}</span>
                  <span className={styles.stageTitle}>{t(stage.titleCs, stage.titleEn)}</span>
                </button>
              )
            })}
          </div>
        </div>
      </section>

      <section id="sdlc-stage-detail" role="region" aria-label={t('Detail fáze', 'Stage detail')} className={styles.detail} aria-live={playing ? 'off' : 'polite'} onFocusCapture={() => setPlaying(false)} onPointerEnter={() => setPlaying(false)} style={{ '--stage-accent': active.accent } as React.CSSProperties}>
        <div className={styles.detailMain}>
          <div className={styles.detailHeading}>
            <span className={styles.detailIcon}><active.icon aria-hidden="true" size={22} /></span>
            <div>
              <span>{t(active.eyebrowCs, active.eyebrowEn)}</span>
              <h3>{t(active.titleCs, active.titleEn)}</h3>
            </div>
          </div>
          <p className={styles.lead}>{t(active.summaryCs, active.summaryEn)}</p>
          <div className={styles.systemFlow}>
            <span>{t('Co se děje v systému', 'What happens in the system')}</span>
            <p>{t(active.systemCs, active.systemEn)}</p>
          </div>
          <Link className={styles.deepLink} href={active.href}>
            {t(active.linkCs, active.linkEn)} <ArrowRight aria-hidden="true" size={15} />
          </Link>
        </div>

        <aside className={styles.rolePanel} aria-label={t('Moje role v této fázi', 'My role in this stage')}>
          <span className={styles.roleLabel}>{t('Co v této fázi dělám já?', 'What do I do in this stage?')}</span>
          <div className={styles.audienceTabs} role="group" aria-label={t('Vyberte perspektivu', 'Choose a perspective')}>
            {AUDIENCES.map(item => {
              const Icon = item.icon
              return (
                <button key={item.id} type="button" onClick={() => setAudience(item.id)} aria-pressed={audience === item.id}>
                  <Icon aria-hidden="true" size={14} /> {t(item.cs, item.en)}
                </button>
              )
            })}
          </div>
          <p className={styles.roleAction}>{t(active.actions[audience].cs, active.actions[audience].en)}</p>
          <div className={styles.proof}>
            <BookOpenCheck aria-hidden="true" size={18} />
            <div><span>{t('Výstup fáze', 'Stage output')}</span><strong>{t(active.proofCs, active.proofEn)}</strong></div>
          </div>
        </aside>
      </section>

      <section className={styles.difference} aria-labelledby="difference-title">
        <div className={styles.differenceIntro}>
          <div>
            <span>{t('TO, CO NÁS ODLIŠUJE', 'WHAT SETS US APART')}</span>
            <h2 id="difference-title">{t('Důvěra není slogan. Je to řetězec ověřitelných kroků.', 'Trust is a chain of verifiable steps.')}</h2>
          </div>
          <p>{t(
            'Naše výhoda je v propojení pravidel, dokumentace, buildu, nasazení a provozních důkazů. Každý mechanismus má konkrétní zdroj a výsledek.',
            'Our advantage is the connection between rules, documentation, builds, deployment, and operational evidence. Every mechanism has a concrete source and outcome.',
          )}</p>
        </div>
        <div className={styles.differenceGrid}>
          {DIFFERENTIATORS.map(item => {
            const Icon = item.icon
            return (
              <article key={item.id} className={styles.differenceCard}>
                <div className={styles.differenceCardTop}>
                  <span>{item.index} / 06</span>
                  <Icon aria-hidden="true" size={20} />
                </div>
                <p className={styles.differenceName}>{t(item.titleCs, item.titleEn)}</p>
                <h3>{t(item.headlineCs, item.headlineEn)}</h3>
                <p className={styles.differenceMechanism}>{t(item.mechanismCs, item.mechanismEn)}</p>
                <div className={styles.differenceOutcome}>
                  <span>{t('CO TO PŘINÁŠÍ', 'WHY IT MATTERS')}</span>
                  <p>{t(item.outcomeCs, item.outcomeEn)}</p>
                </div>
                <Link href={item.href} className={styles.differenceLink}>
                  {t(item.linkCs, item.linkEn)} <ArrowRight aria-hidden="true" size={14} />
                </Link>
              </article>
            )
          })}
        </div>
      </section>

      <section className={styles.gates} aria-labelledby="gates-title">
        <div className={styles.gatesHeader}>
          <div>
            <span>{t('GATE ATLAS', 'GATE ATLAS')}</span>
            <h2 id="gates-title">{t('Osm otázek, které musí změna ustát', 'Eight questions every change must answer')}</h2>
            <p>{t(
              'Toto je osm tematických otázek, nikoli osm CI bran. Klikněte pro smysl kontroly; úplný katalog všech skutečných bran i jejich zdrojové příkazy najdete níže.',
              'These are eight thematic questions, not eight CI gates. Select one for its purpose; the full catalog of actual gates and source commands follows below.',
            )}</p>
          </div>
          <div className={styles.gateLegend} aria-label={t('Typy quality gates', 'Quality gate types')}>
            <span><i className={styles.dotBlocking} />{t('blokuje merge', 'blocks merge')}</span>
            <span><i className={styles.dotScoped} />{t('spouští se dle změny', 'runs by change scope')}</span>
            <span><i className={styles.dotRuntime} />{t('vynucuje i runtime', 'also enforced at runtime')}</span>
          </div>
        </div>

        <div className={styles.gateGrid}>
          {GATES.map(gate => {
            const Icon = gate.icon
            const selected = gate.id === activeGate.id
            return (
              <button key={gate.id} type="button" onClick={() => setActiveGateId(gate.id)} aria-pressed={selected} aria-controls="gate-explainer">
                <span>{gate.number}</span>
                <Icon aria-hidden="true" size={18} />
                <strong>{t(gate.titleCs, gate.titleEn)}</strong>
                <ChevronRight aria-hidden="true" size={15} />
              </button>
            )
          })}
        </div>

        <div id="gate-explainer" className={styles.gateExplainer} role="region" aria-label={t('Vysvětlení quality gate', 'Quality gate explanation')} aria-live="polite">
          <div className={styles.gateQuestion}>
            <span>{activeGate.number} · {t(activeGate.modeCs, activeGate.modeEn)}</span>
            <h3>{t(activeGate.questionCs, activeGate.questionEn)}</h3>
          </div>
          <div className={styles.gateFacts}>
            <div>
              <span>{t('Co kontroluje', 'What it checks')}</span>
              <p>{t(activeGate.checksCs, activeGate.checksEn)}</p>
            </div>
            <div>
              <span>{t('Když je červená', 'When it is red')}</span>
              <p>{t(activeGate.failureCs, activeGate.failureEn)}</p>
            </div>
            <div>
              <span>{t('Proč to zajímá business', 'Why business should care')}</span>
              <p>{t(activeGate.valueCs, activeGate.valueEn)}</p>
            </div>
          </div>
          <Link href={activeGate.href} className={styles.gateLink}>
            {t('Otevřít související důkazy', 'Open related evidence')} <ArrowRight aria-hidden="true" size={14} />
          </Link>
          {GATE_EXAMPLES[activeGate.id].length > 0 && <div className={styles.gateExamples}>
            <span>{t('Příklady skutečných CI bran:', 'Examples of actual CI gates:')}</span>
            {GATE_EXAMPLES[activeGate.id].map(id => <a key={id} href={`/devops/sdlc?gate=${id}#gate-catalog`}>{id} <ArrowRight aria-hidden="true" size={12} /></a>)}
          </div>}
        </div>
      </section>

      <GateCatalogExplorer />

      <section className={styles.principles} aria-labelledby="principles-title">
        <div className={styles.sectionIntro}>
          <span>{t('PROČ TO FUNGUJE', 'WHY IT WORKS')}</span>
          <h2 id="principles-title">{t('Rychlost bez ztráty kontroly', 'Speed without losing control')}</h2>
          <p>{t(
            'Automatizace zkracuje čekání. Guardraily drží standard. Lidé rozhodují tam, kde je potřeba úsudek.',
            'Automation removes waiting. Guardrails hold the standard. People decide where judgment matters.',
          )}</p>
        </div>
        <div className={styles.principleGrid}>
          {[
            { icon: GitMerge, cs: 'Jedna stopa změny', en: 'One change trail', descCs: 'Issue, commit, PR, artefakt, digest a nasazení zůstávají propojené.', descEn: 'Issue, commit, PR, artifact, digest, and deployment stay connected.' },
            { icon: ShieldCheck, cs: 'Bezpečnost uvnitř toku', en: 'Security inside the flow', descCs: 'Kontroly nejsou závěrečný audit; běží průběžně od kódu po admission.', descEn: 'Controls are not a final audit; they run continuously from code to admission.' },
            { icon: UsersRound, cs: 'Srozumitelné rozhodnutí', en: 'A decision everyone understands', descCs: 'Technický důkaz se překládá do rizika, odpovědnosti a obchodního dopadu.', descEn: 'Technical evidence translates into risk, accountability, and business impact.' },
          ].map(item => (
            <article key={item.en}>
              <item.icon aria-hidden="true" size={20} />
              <h3>{t(item.cs, item.en)}</h3>
              <p>{t(item.descCs, item.descEn)}</p>
            </article>
          ))}
        </div>
      </section>

      <section className={styles.nextStep}>
        <div>
          <span>{t('CHCI VIDĚT REALITU', 'SHOW ME THE REAL SYSTEM')}</span>
          <h2>{t('Přejděte z mapy k živým důkazům', 'Move from the map to live evidence')}</h2>
          <p>{t('DORA metriky, zdraví pipeline a aktivní DevOps nálezy jsou na jednom místě.', 'DORA metrics, pipeline health, and active DevOps findings live in one place.')}</p>
        </div>
        <Link href="/devops" className="btn btn-primary">
          {t('Otevřít DevOps cockpit', 'Open DevOps cockpit')} <ChevronRight aria-hidden="true" size={16} />
        </Link>
      </section>
    </div>
  )
}
