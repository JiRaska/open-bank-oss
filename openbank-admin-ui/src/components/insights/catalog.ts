// SPDX-License-Identifier: Apache-2.0
import type { InsightPanel } from './ContextualInsights'

export const PAYMENT_INSIGHTS: InsightPanel[] = [
  { id: 6, titleCs: 'Úspěšnost požadavků', titleEn: 'Request success rate', descriptionCs: 'Technická úspěšnost požadavků podle platební koleje za poslední hodinu.', descriptionEn: 'Technical request success by payment rail over the last hour.', height: 'trend' },
  { id: 2, titleCs: 'SEPA Instant latence p95', titleEn: 'SEPA Instant p95 latency', descriptionCs: 'Pozorovaná serverová odezva včetně syntetických kontrol.', descriptionEn: 'Observed server latency including synthetic probes.' },
  { id: 1, titleCs: 'Latence platebních služeb', titleEn: 'Payment-service latency', descriptionCs: 'Medián a pomalý konec serverové odezvy napříč platebními kolejemi.', descriptionEn: 'Median and slow tail of server latency across payment rails.', height: 'trend' },
  { id: 4, titleCs: 'Provoz podle platební koleje', titleEn: 'Traffic by payment rail', descriptionCs: 'Pomáhá odlišit plošný problém od jedné platební koleje.', descriptionEn: 'Separates a broad incident from a single payment rail.', height: 'trend' },
]

export const LEDGER_INSIGHTS: InsightPanel[] = [
  { id: 2, titleCs: 'Nevyřízený outbox', titleEn: 'Pending outbox', descriptionCs: 'Zápisy, které ještě čekají na bezpečné předání.', descriptionEn: 'Entries still waiting for safe delivery.' },
  { id: 4, titleCs: 'Chybovost hlavní knihy', titleEn: 'Ledger error rate', descriptionCs: 'Technické chyby při práci s účetními zápisy.', descriptionEn: 'Technical failures while processing ledger entries.' },
  { id: 9, titleCs: 'Latence zaúčtování p95', titleEn: 'Posting latency p95', descriptionCs: 'Jak dlouho čeká pomalejší část zaúčtování.', descriptionEn: 'How long the slower share of postings takes.', height: 'trend' },
  { id: 11, titleCs: 'API a události hlavní knihy', titleEn: 'Ledger API and journal events', descriptionCs: 'Pozorovaný provoz API vedle publikace účetních událostí.', descriptionEn: 'Observed API traffic alongside journal-event publication.', height: 'trend' },
]

export const HEALTH_INSIGHTS: InsightPanel[] = [
  { id: 1, titleCs: 'Dostupnost platebních cest', titleEn: 'Payment-rail availability', descriptionCs: 'Třicetidenní dostupnost podle platební koleje.', descriptionEn: 'Thirty-day availability by payment rail.' },
  { id: 2, titleCs: 'Zbývající chybový rozpočet', titleEn: 'Error budget remaining', descriptionCs: 'Kolik prostoru zbývá před porušením SLO.', descriptionEn: 'Room remaining before the SLO is breached.' },
  { id: 3, titleCs: 'Rychlost čerpání rozpočtu', titleEn: 'Error-budget burn rate', descriptionCs: 'Krátké i delší okno odhalí rychle rostoucí dopad.', descriptionEn: 'Short and long windows expose a fast-growing impact.', height: 'trend' },
]

export const EVENT_INSIGHTS: InsightPanel[] = [
  { id: 3, titleCs: 'Aktuálně mrtvé zprávy', titleEn: 'Current dead-lettered items', descriptionCs: 'Události nyní odložené v koncovém chybovém stavu.', descriptionEn: 'Events currently parked in a terminal failure state.' },
  { id: 4, titleCs: 'Služby bez backlogu', titleEn: 'Backlog-free services', descriptionCs: 'Podíl instrumentovaných outboxů bez čekajících nebo chybných zpráv.', descriptionEn: 'Share of instrumented outboxes without pending or failed messages.' },
  { id: 5, titleCs: 'Backlog podle služby', titleEn: 'Backlog by service', descriptionCs: 'Kde se čekající nebo chybné události hromadí.', descriptionEn: 'Where pending or failed events accumulate.', height: 'trend' },
  { id: 2, titleCs: 'Mrtvé zprávy podle služby', titleEn: 'Dead letters by service', descriptionCs: 'Ukazuje, ve kterých službách jsou události trvale odložené.', descriptionEn: 'Shows which services currently have terminally parked events.', height: 'trend' },
]

export const AI_INSIGHTS: InsightPanel[] = [
  { id: 2, titleCs: 'Náklady LLM za 24 hodin', titleEn: 'LLM spend over 24 hours', descriptionCs: 'Odvozené provozní náklady všech modelových volání.', descriptionEn: 'Derived operational cost of all model calls.' },
  { id: 4, titleCs: 'Chybovost za 15 minut', titleEn: '15-minute error rate', descriptionCs: 'Aktuální spolehlivost agentních volání.', descriptionEn: 'Current reliability of agent calls.' },
  { id: 5, titleCs: 'Přeskočeno bez API klíče', titleEn: 'Skipped without API key', descriptionCs: 'Požadavky, které nebyly skutečně provedeny.', descriptionEn: 'Requests that were not actually executed.' },
  { id: 10, titleCs: 'Odezva modelů', titleEn: 'Model latency', descriptionCs: 'Medián a pomalý konec úspěšných volání.', descriptionEn: 'Median and slow tail of successful calls.', height: 'trend' },
]
