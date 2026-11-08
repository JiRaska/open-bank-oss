// SPDX-License-Identifier: Apache-2.0
import type { EvidenceKind, EvidenceState } from '@/lib/types/test-intelligence'
import type { Tone } from '@/components/ui'
import type { Language } from '@/lib/i18n/LanguageContext'

const bi = (cs: string, en: string): [string, string] => [cs, en]

export const KIND_LABELS: Record<EvidenceKind, [string, string]> = {
  unit: bi('Logika', 'Logic'), integration: bi('Spolupráce', 'Integration'), contract: bi('Dohody API', 'API contracts'),
  e2e: bi('Celá cesta', 'End-to-end'), trace: bi('Průchod službami', 'Service traces'), mutation: bi('Odhalování chyb', 'Mutation'),
  simulation: bi('Simulace', 'Simulation'), performance: bi('Výkon', 'Performance'), synthetic: bi('Provozní sondy', 'Live probes'), visual: bi('Vzhled', 'Visual'),
}
export const KIND_HELP: Record<EvidenceKind, [string, string]> = {
  unit: bi('Ověřuje jednotlivé pravidlo nebo výpočet odděleně od okolí.', 'Checks an individual rule or calculation in isolation.'),
  integration: bi('Ověřuje spolupráci částí systému, například aplikace a databáze.', 'Checks how parts work together, such as the application and its database.'),
  contract: bi('Ověřuje, že si služby rozumějí ve formátu požadavků a odpovědí.', 'Checks that services agree on requests and responses.'),
  e2e: bi('Ověřuje celý scénář od počáteční akce po očekávaný výsledek.', 'Checks a complete scenario from its first action to the expected outcome.'),
  trace: bi('Ověřuje zaznamenaný průchod požadavku jednotlivými službami.', 'Checks the recorded path of a request across services.'),
  mutation: bi('Záměrně mění kód a zjišťuje, zda testy tuto chybu odhalí.', 'Intentionally changes code to see whether the tests detect the defect.'),
  simulation: bi('Ověřuje scénáře v řízeném, opakovatelném prostředí.', 'Checks scenarios in a controlled, repeatable environment.'),
  performance: bi('Měří odezvu a chybovost při konkrétním zatížení a podmínkách.', 'Measures latency and errors under a specific load and conditions.'),
  synthetic: bi('Pravidelně ověřuje deklarovaný scénář v běžícím prostředí.', 'Periodically checks a declared scenario in a running environment.'),
  visual: bi('Porovnává zachycený vzhled s odsouhlasenou předlohou.', 'Compares captured appearance with an approved reference.'),
}
export function kindLabel(kind: EvidenceKind, language: Language): string { return KIND_LABELS[kind][language === 'cs' ? 0 : 1] }
export function stateLabel(state: EvidenceState | 'not-applicable', language: Language): string {
  const labels: Record<EvidenceState | 'not-applicable', [string, string]> = {
    passed: bi('Prošlo', 'Passed'), failed: bi('Selhalo', 'Failed'), skipped: bi('Přeskočeno', 'Skipped'), stale: bi('Historický výsledek', 'Historical result'),
    'not-run': bi('Bez doloženého běhu', 'No recorded run'), unknown: bi('Chybí ověření', 'Unverified'), blocked: bi('Blokováno', 'Blocked'), 'not-applicable': bi('Nevztahuje se', 'Not applicable'),
  }
  return labels[state][language === 'cs' ? 0 : 1]
}

export function stateTone(state: EvidenceState | 'not-applicable'): Tone {
  return state === 'passed' ? 'success' : state === 'failed' ? 'danger' : state === 'stale' ? 'warning' : state === 'blocked' ? 'accent' : 'neutral'
}
