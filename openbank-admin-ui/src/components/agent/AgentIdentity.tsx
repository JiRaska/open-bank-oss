// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { CSSProperties } from 'react'
import type { LucideIcon } from 'lucide-react'
import {
  Activity, BadgeCheck, BookOpenCheck, Bot, Braces,
  ChartNoAxesCombined, ClipboardCheck, GitPullRequest, KeyRound,
  LifeBuoy, MessageCircleQuestion, Network, Radar, Scale, SearchCheck,
  ShieldCheck, TestTubeDiagonal,
} from 'lucide-react'
import styles from './AgentIdentity.module.css'

type Copy = { cs: string; en: string }

interface PersonaDefinition {
  name: Copy
  role: Copy
  purpose: Copy
  value: Copy
  talents: [Copy, Copy, Copy]
  accent: string
  glow: string
  shell: string
  variant: 'guard' | 'lens' | 'runner' | 'guide'
  icon: LucideIcon
}

export interface AgentPersona {
  name: string
  role: string
  purpose: string
  value: string
  talents: [string, string, string]
  accent: string
  glow: string
  shell: string
  variant: PersonaDefinition['variant']
  icon: LucideIcon
}

// This is a presentation glossary, not an authority model. Permissions, limits and
// operational truth continue to come exclusively from agents.yaml; these labels only
// translate each charter into language a non-technical colleague can scan quickly.
const PERSONAS: Record<string, PersonaDefinition> = {
  'compliance-officer': {
    name: { cs: 'Ada', en: 'Ada' }, role: { cs: 'Strážkyně pravidel', en: 'Rules guardian' },
    purpose: { cs: 'Hlídá AML, sankce, GDPR a PSD2 a připravuje podklady pro lidské rozhodnutí.', en: 'Watches AML, sanctions, GDPR and PSD2 and prepares evidence for a human decision.' },
    value: { cs: 'Pomáhá bance najít regulatorní riziko dřív, než se z něj stane incident.', en: 'Helps the bank spot regulatory risk before it becomes an incident.' },
    talents: [{ cs: 'Kontrola souladu', en: 'Compliance checks' }, { cs: 'Skládání důkazů', en: 'Evidence gathering' }, { cs: 'Návrh dalšího kroku', en: 'Next-step proposals' }],
    accent: '#4f46e5', glow: '#c7d2fe', shell: '#eef2ff', variant: 'guard', icon: ShieldCheck,
  },
  'ledger-domain-engineer': {
    name: { cs: 'Leda', en: 'Leda' }, role: { cs: 'Inženýrka účetní knihy', en: 'Ledger engineer' },
    purpose: { cs: 'Pečuje o doménové služby účetní knihy a připravuje bezpečně kontrolovatelné změny.', en: 'Maintains ledger domain services and prepares safely reviewable changes.' },
    value: { cs: 'Zrychluje technické změny, ale nikdy nepřeskočí kontrolu člověkem.', en: 'Speeds up engineering changes without ever bypassing human review.' },
    talents: [{ cs: 'Kontrola před vydáním', en: 'Pre-release checks' }, { cs: 'Příprava změny', en: 'Change preparation' }, { cs: 'Bezpečné předání do PR', en: 'Safe PR hand-off' }],
    accent: '#0f766e', glow: '#99f6e4', shell: '#f0fdfa', variant: 'runner', icon: Braces,
  },
  'ui-assistant': {
    name: { cs: 'Mia', en: 'Mia' }, role: { cs: 'Průvodkyně konzolí', en: 'Console guide' },
    purpose: { cs: 'Odpovídá operátorům nad bezpečnými daty a pomáhá najít správnou funkci nebo vysvětlení.', en: 'Answers operators from safe data and helps them find the right function or explanation.' },
    value: { cs: 'Méně hledání v systémech, rychlejší orientace a srozumitelné odpovědi.', en: 'Less searching across systems, faster orientation and clearer answers.' },
    talents: [{ cs: 'Odpovědi na dotazy', en: 'Operator Q&A' }, { cs: 'Orientace v bance', en: 'Bank navigation' }, { cs: 'Srozumitelné vysvětlení', en: 'Plain-language guidance' }],
    accent: '#2563eb', glow: '#bfdbfe', shell: '#eff6ff', variant: 'guide', icon: MessageCircleQuestion,
  },
  'rca-investigator': {
    name: { cs: 'Holmes', en: 'Holmes' }, role: { cs: 'Vyšetřovatel incidentů', en: 'Incident investigator' },
    purpose: { cs: 'Spojuje alerty, metriky, logy a stopy do pravděpodobné příčiny problému.', en: 'Connects alerts, metrics, logs and traces into a likely cause of an incident.' },
    value: { cs: 'Zkracuje čas od alarmu k pochopení, co se opravdu pokazilo.', en: 'Shortens the path from an alarm to understanding what actually failed.' },
    talents: [{ cs: 'Čtení provozních signálů', en: 'Signal analysis' }, { cs: 'Hledání souvislostí', en: 'Correlation' }, { cs: 'Hypotéza příčiny', en: 'Root-cause hypothesis' }],
    accent: '#0891b2', glow: '#a5f3fc', shell: '#ecfeff', variant: 'lens', icon: SearchCheck,
  },
  'customer-copilot': {
    name: { cs: 'Kora', en: 'Cora' }, role: { cs: 'Klientská průvodkyně', en: 'Customer companion' },
    purpose: { cs: 'Pomáhá klientovi s jeho vlastními daty a připravuje návrhy, které vždy dokončí člověk přes SCA.', en: 'Helps customers with their own data and prepares proposals always completed by a human through SCA.' },
    value: { cs: 'Dává klientovi rychlou pomoc bez toho, aby model mohl sám pohnout penězi.', en: 'Gives customers fast help without allowing a model to move money itself.' },
    talents: [{ cs: 'Vysvětlení účtu', en: 'Account guidance' }, { cs: 'Návrh služby', en: 'Service proposals' }, { cs: 'Bezpečné předání do SCA', en: 'Safe SCA hand-off' }],
    accent: '#0284c7', glow: '#bae6fd', shell: '#f0f9ff', variant: 'guide', icon: LifeBuoy,
  },
  'mcp-anonymous': {
    name: { cs: 'Mako', en: 'Mako' }, role: { cs: 'Tlumočník bankovních dat', en: 'Bank data interpreter' },
    purpose: { cs: 'Zpřístupňuje jen data povolená PSD2 souhlasem a vytváří pouze kontrolovatelné návrhy.', en: 'Exposes only PSD2-consented data and creates reviewable proposals only.' },
    value: { cs: 'Umožňuje agentům spolupracovat s bankou přes úzké a auditovatelné rozhraní.', en: 'Lets agents work with the bank through a narrow, auditable interface.' },
    talents: [{ cs: 'Ověření souhlasu', en: 'Consent checks' }, { cs: 'Bezpečné čtení dat', en: 'Safe data access' }, { cs: 'Předání návrhu platby', en: 'Payment proposal hand-off' }],
    accent: '#0d9488', glow: '#99f6e4', shell: '#f0fdfa', variant: 'guard', icon: Network,
  },
  'ap2-anonymous': {
    name: { cs: 'Ari', en: 'Ari' }, role: { cs: 'Ověřovatel mandátů', en: 'Mandate verifier' },
    purpose: { cs: 'Ověřuje podpis, limity, příjemce a platnost digitálního mandátu AP2.', en: 'Checks the signature, limits, payee and validity of an AP2 digital mandate.' },
    value: { cs: 'Dodá důkaz pro rozhodnutí o platbě; sám platbu nikdy neprovede.', en: 'Provides evidence for a payment decision; it never executes the payment.' },
    talents: [{ cs: 'Kontrola podpisu', en: 'Signature checks' }, { cs: 'Ověření limitů', en: 'Constraint checks' }, { cs: 'Důkaz pro SCA', en: 'SCA evidence' }],
    accent: '#7c3aed', glow: '#ddd6fe', shell: '#f5f3ff', variant: 'lens', icon: BadgeCheck,
  },
  'finops-agent': {
    name: { cs: 'Fina', en: 'Fina' }, role: { cs: 'Hlídačka nákladů', en: 'Cost steward' },
    purpose: { cs: 'Pozoruje cloudové a AI náklady, hledá anomálie a navrhuje úspory.', en: 'Monitors cloud and AI spend, finds anomalies and proposes savings.' },
    value: { cs: 'Ukazuje, kde bance nepozorovaně rostou náklady a jak je bezpečně snížit.', en: 'Shows where costs are quietly growing and how the bank can safely reduce them.' },
    talents: [{ cs: 'Detekce anomálií', en: 'Anomaly detection' }, { cs: 'Rozpad nákladů', en: 'Cost attribution' }, { cs: 'Návrh úspory', en: 'Savings proposals' }],
    accent: '#ca8a04', glow: '#fde68a', shell: '#fefce8', variant: 'guide', icon: ChartNoAxesCombined,
  },
  'devops-agent': {
    name: { cs: 'Dora', en: 'Dora' }, role: { cs: 'Správkyně provozu', en: 'Delivery operator' },
    purpose: { cs: 'Hlídá CI, nasazení, kapacitu a opakované provozní problémy napříč flotilou.', en: 'Watches CI, deployments, capacity and recurring operational problems across the fleet.' },
    value: { cs: 'Pomáhá týmům doručovat spolehlivěji a všimnout si regrese dřív.', en: 'Helps teams deliver more reliably and notice regressions earlier.' },
    talents: [{ cs: 'Zdraví CI/CD', en: 'CI/CD health' }, { cs: 'DORA trendy', en: 'DORA trends' }, { cs: 'Návrh nápravy', en: 'Remediation proposals' }],
    accent: '#ea580c', glow: '#fed7aa', shell: '#fff7ed', variant: 'runner', icon: Activity,
  },
  'control-liveness-sentinel': {
    name: { cs: 'Sena', en: 'Sena' }, role: { cs: 'Noční hlídka kontrol', en: 'Control watchkeeper' },
    purpose: { cs: 'Kontroluje, že důležité bankovní kontroly opravdu běží a nezůstaly tiše stát.', en: 'Checks that important bank controls are truly running and have not silently stalled.' },
    value: { cs: 'Odhalí nečinnou kontrolu dřív, než vznikne slepé místo v dohledu.', en: 'Finds a dormant control before it creates a blind spot in oversight.' },
    talents: [{ cs: 'Kontrola živosti', en: 'Liveness checks' }, { cs: 'Hlídání stáří výsledku', en: 'Freshness monitoring' }, { cs: 'Včasné varování', en: 'Early warning' }],
    accent: '#475569', glow: '#cbd5e1', shell: '#f8fafc', variant: 'lens', icon: Radar,
  },
  'governance-auditor': {
    name: { cs: 'Gabi', en: 'Gabi' }, role: { cs: 'Auditorka governance', en: 'Governance auditor' },
    purpose: { cs: 'Porovnává deklarovaná pravidla se skutečnými důkazy v kódu a provozu.', en: 'Compares declared governance rules with real evidence in code and operations.' },
    value: { cs: 'Brání tomu, aby dokumentace slibovala kontrolu, která ve skutečnosti nefunguje.', en: 'Prevents documentation from promising a control that does not actually work.' },
    talents: [{ cs: 'Ověření pravidel', en: 'Rule verification' }, { cs: 'Hledání mezer', en: 'Gap detection' }, { cs: 'Auditní nález', en: 'Audit findings' }],
    accent: '#4338ca', glow: '#c7d2fe', shell: '#eef2ff', variant: 'guard', icon: Scale,
  },
  'release-steward': {
    name: { cs: 'Rela', en: 'Rela' }, role: { cs: 'Výpravčí releasů', en: 'Release dispatcher' },
    purpose: { cs: 'Kontroluje připravenost změny k vydání a skládá dohromady release důkazy.', en: 'Checks whether a change is ready to ship and assembles release evidence.' },
    value: { cs: 'Snižuje riziko, že do produkce odjede neúplná nebo neověřená změna.', en: 'Reduces the risk of shipping an incomplete or unverified change.' },
    talents: [{ cs: 'Kontrola checklistu', en: 'Checklist validation' }, { cs: 'Sběr důkazů', en: 'Evidence assembly' }, { cs: 'Doporučení vydání', en: 'Release recommendation' }],
    accent: '#dc2626', glow: '#fecaca', shell: '#fef2f2', variant: 'runner', icon: GitPullRequest,
  },
  'docs-truth-agent': {
    name: { cs: 'Doku', en: 'Doku' }, role: { cs: 'Knihovník pravdy', en: 'Truth librarian' },
    purpose: { cs: 'Hledá rozpory mezi dokumentací, rozhodnutími a tím, co systém skutečně dělá.', en: 'Finds contradictions between documentation, decisions and what the system really does.' },
    value: { cs: 'Udržuje návody a tvrzení důvěryhodné pro audit i každodenní práci.', en: 'Keeps guidance and claims trustworthy for audits and daily work.' },
    talents: [{ cs: 'Porovnání zdrojů', en: 'Source comparison' }, { cs: 'Odhalení zastarání', en: 'Staleness detection' }, { cs: 'Návrh opravy dokumentace', en: 'Documentation fixes' }],
    accent: '#0369a1', glow: '#bae6fd', shell: '#f0f9ff', variant: 'guide', icon: BookOpenCheck,
  },
  'authz-policy-auditor': {
    name: { cs: 'Astra', en: 'Astra' }, role: { cs: 'Strážkyně oprávnění', en: 'Access guardian' },
    purpose: { cs: 'Kontroluje, zda role, politiky a skutečné přístupy odpovídají principu nejmenších oprávnění.', en: 'Checks whether roles, policies and actual access follow least privilege.' },
    value: { cs: 'Nachází příliš široký přístup dřív, než ho někdo může zneužít.', en: 'Finds overly broad access before it can be misused.' },
    talents: [{ cs: 'Analýza rolí', en: 'Role analysis' }, { cs: 'Kontrola politik', en: 'Policy review' }, { cs: 'Odhalení nadoprávnění', en: 'Over-privilege detection' }],
    accent: '#6d28d9', glow: '#ddd6fe', shell: '#f5f3ff', variant: 'guard', icon: KeyRound,
  },
  'flaky-test-hunter': {
    name: { cs: 'Flek', en: 'Fleck' }, role: { cs: 'Lovec nespolehlivých testů', en: 'Flaky-test tracker' },
    purpose: { cs: 'Poznává testy, které selhávají náhodně, a připravuje důkazy pro opravu.', en: 'Identifies tests that fail unpredictably and prepares evidence for a fix.' },
    value: { cs: 'Vrací týmům důvěru v CI a šetří čas ztracený falešnými poplachy.', en: 'Restores trust in CI and saves time lost to false alarms.' },
    talents: [{ cs: 'Hledání vzorců selhání', en: 'Failure patterning' }, { cs: 'Ověření opakováním', en: 'Reproduction checks' }, { cs: 'Návrh stabilizace', en: 'Stabilisation proposals' }],
    accent: '#be123c', glow: '#fecdd3', shell: '#fff1f2', variant: 'lens', icon: TestTubeDiagonal,
  },
  'case-coordinator': {
    name: { cs: 'Kord', en: 'Chord' }, role: { cs: 'Koordinátor případu', en: 'Case coordinator' },
    purpose: { cs: 'Svolává správné agenty ke složitému případu, hlídá rozpočet a hledá shodu.', en: 'Brings the right agents into a complex case, watches the budget and seeks convergence.' },
    value: { cs: 'Dává multiagentní práci jedno vlákno, jasný cíl a kontrolovatelný konec.', en: 'Gives multi-agent work one thread, a clear objective and a reviewable end.' },
    talents: [{ cs: 'Sestavení týmu', en: 'Team assembly' }, { cs: 'Řízení rozpočtu', en: 'Budget control' }, { cs: 'Vyhodnocení shody', en: 'Convergence checks' }],
    accent: '#0f766e', glow: '#99f6e4', shell: '#f0fdfa', variant: 'guide', icon: ClipboardCheck,
  },
  // ADR-0284 D9. Both charters are enabled:false and have no runtime yet; the persona exists
  // because this console renders every governed agent, and a chartered agent with no persona
  // shows up as "Nový kolega" — which reads as an oversight rather than as a deliberate
  // pre-registration.
  'kyb-analyst': {
    name: { cs: 'Rejda', en: 'Ledger' }, role: { cs: 'Analytik firemního onboardingu', en: 'Business onboarding analyst' },
    purpose: { cs: 'Přečte případ, který skončil na ručním posouzení, a navrhne rozhodnutí s doloženými podklady.', en: 'Reads a case that landed in manual review and proposes a disposition with its evidence cited.' },
    value: { cs: 'Připraví spis, rozhoduje člověk — agent nemá právo do případu zapsat.', en: 'Prepares the file; a human decides — the agent has no write path into a case.' },
    talents: [{ cs: 'Čtení rejstříku', en: 'Register reading' }, { cs: 'Způsob jednání', en: 'Representation rules' }, { cs: 'Skuteční majitelé', en: 'Beneficial owners' }],
    accent: '#7c3aed', glow: '#ddd6fe', shell: '#f5f3ff', variant: 'lens', icon: BadgeCheck,
  },
  'business-copilot': {
    name: { cs: 'Firm', en: 'Firm' }, role: { cs: 'Firemní průvodce v aplikaci', en: 'In-app business copilot' },
    purpose: { cs: 'Odpovídá za firmu, za kterou klient právě jedná, a akce vrací jako návrh do SCA toku.', en: 'Answers for the entity the customer is acting for, and returns actions as a proposal into the SCA flow.' },
    value: { cs: 'Jedna firma na jedno sezení — nikdy ne další firmy ani osobní účet.', en: 'One entity per session — never their other companies, never their personal party.' },
    talents: [{ cs: 'Firemní zůstatky', en: 'Business balances' }, { cs: 'Návrh platby', en: 'Payment proposals' }, { cs: 'Kontext mandátu', en: 'Mandate context' }],
    accent: '#c2410c', glow: '#fed7aa', shell: '#fff7ed', variant: 'guide', icon: Scale,
  },
}

const FALLBACK: PersonaDefinition = {
  name: { cs: 'Nový kolega', en: 'New colleague' }, role: { cs: 'Specializovaný agent', en: 'Specialist agent' },
  purpose: { cs: 'Pracuje v rozsahu svého charteru a všechny citlivé kroky předává člověku.', en: 'Works within its charter and hands every sensitive step to a human.' },
  value: { cs: 'Automatizuje úzký úkol bez rozšiřování vlastních oprávnění.', en: 'Automates a narrow task without expanding its own authority.' },
  talents: [{ cs: 'Čtení povolených dat', en: 'Allowed-data access' }, { cs: 'Příprava podkladů', en: 'Evidence preparation' }, { cs: 'Předání člověku', en: 'Human hand-off' }],
  accent: '#64748b', glow: '#cbd5e1', shell: '#f8fafc', variant: 'guide', icon: Bot,
}

export function getAgentPersona(agentId: string, language: 'cs' | 'en'): AgentPersona {
  const p = PERSONAS[agentId] ?? FALLBACK
  const local = (copy: Copy) => copy[language]
  return {
    name: local(p.name), role: local(p.role), purpose: local(p.purpose), value: local(p.value),
    talents: p.talents.map(local) as [string, string, string],
    accent: p.accent, glow: p.glow, shell: p.shell, variant: p.variant, icon: p.icon,
  }
}

export function AgentPortrait({ agentId, compact = false }: { agentId: string; compact?: boolean }) {
  const p = PERSONAS[agentId] ?? FALLBACK
  const Icon = p.icon
  const cssVars = {
    '--agent-accent': p.accent,
    '--agent-glow': p.glow,
    '--agent-shell': p.shell,
  } as CSSProperties
  const variantClass = {
    guard: styles.variantGuard,
    lens: styles.variantLens,
    runner: styles.variantRunner,
    guide: styles.variantGuide,
  }[p.variant]

  return (
    <div className={`${styles.portrait} ${compact ? styles.compact : ''}`} style={cssVars} aria-hidden="true">
      <div className={`${styles.robot} ${variantClass}`}>
        <span className={styles.antenna} />
        <span className={styles.head}>
          <span className={styles.visor}><span className={styles.eye} /><span className={styles.eye} /></span>
        </span>
        <span className={`${styles.arm} ${styles.armLeft}`} />
        <span className={`${styles.arm} ${styles.armRight}`} />
        <span className={styles.body}>
          <span className={styles.chest}><Icon size={14} strokeWidth={2.3} /></span>
        </span>
        <span className={styles.base} />
      </div>
    </div>
  )
}
