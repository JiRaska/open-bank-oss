// SPDX-License-Identifier: Apache-2.0
import type { InsightPanel } from './ContextualInsights'

export const PAYMENT_INSIGHTS: InsightPanel[] = [
  { id: 6, titleCs: 'Úspěšnost plateb', titleEn: 'Payment success rate', descriptionCs: 'Vývoj úspěšnosti v pětiminutových oknech napříč obdobím.', descriptionEn: 'Success-rate trend in five-minute windows across the period.', height: 'trend' },
  { id: 2, titleCs: 'SEPA Instant do 10 sekund', titleEn: 'SEPA Instant under 10 seconds', descriptionCs: 'Plnění zákaznického času pro okamžité platby.', descriptionEn: 'Customer-time target for instant payments.' },
  { id: 1, titleCs: 'Doba zpracování', titleEn: 'Processing duration', descriptionCs: 'Medián a pomalý konec zpracování plateb.', descriptionEn: 'Median and slow tail of payment processing.', height: 'trend' },
  { id: 4, titleCs: 'Dokončení podle typu', titleEn: 'Completion by payment type', descriptionCs: 'Pomáhá odlišit plošný problém od jedné platební koleje.', descriptionEn: 'Separates a broad incident from a single payment rail.', height: 'trend' },
]

export const LEDGER_INSIGHTS: InsightPanel[] = [
  { id: 2, titleCs: 'Nevyřízený outbox', titleEn: 'Pending outbox', descriptionCs: 'Zápisy, které ještě čekají na bezpečné předání.', descriptionEn: 'Entries still waiting for safe delivery.' },
  { id: 4, titleCs: 'Chybovost hlavní knihy', titleEn: 'Ledger error rate', descriptionCs: 'Technické chyby při práci s účetními zápisy.', descriptionEn: 'Technical failures while processing ledger entries.' },
  { id: 9, titleCs: 'Latence zaúčtování p95', titleEn: 'Posting latency p95', descriptionCs: 'Jak dlouho čeká pomalejší část zaúčtování.', descriptionEn: 'How long the slower share of postings takes.', height: 'trend' },
  { id: 11, titleCs: 'Odeslané a dokončené platby', titleEn: 'Submitted and completed payments', descriptionCs: 'Tok rozpracovaných plateb bez tvrzení o účetním nesouladu.', descriptionEn: 'Flow of in-flight payments without claiming an accounting mismatch.', height: 'trend' },
]

export const HEALTH_INSIGHTS: InsightPanel[] = [
  { id: 1, titleCs: 'Dostupnost platebních cest', titleEn: 'Payment-rail availability', descriptionCs: 'Třicetidenní dostupnost podle platební koleje.', descriptionEn: 'Thirty-day availability by payment rail.' },
  { id: 2, titleCs: 'Zbývající chybový rozpočet', titleEn: 'Error budget remaining', descriptionCs: 'Kolik prostoru zbývá před porušením SLO.', descriptionEn: 'Room remaining before the SLO is breached.' },
  { id: 3, titleCs: 'Rychlost čerpání rozpočtu', titleEn: 'Error-budget burn rate', descriptionCs: 'Krátké i delší okno odhalí rychle rostoucí dopad.', descriptionEn: 'Short and long windows expose a fast-growing impact.', height: 'trend' },
]

export const EVENT_INSIGHTS: InsightPanel[] = [
  { id: 3, titleCs: 'Mrtvé zprávy dnes', titleEn: 'Dead letters today', descriptionCs: 'Události, které nebylo možné bezpečně zpracovat.', descriptionEn: 'Events that could not be processed safely.' },
  { id: 4, titleCs: 'Úspěšnost předávání', titleEn: 'Dispatch success rate', descriptionCs: 'Spolehlivost doručení událostí mezi službami.', descriptionEn: 'Reliability of event delivery between services.' },
  { id: 5, titleCs: 'Backlog podle služby', titleEn: 'Backlog by service', descriptionCs: 'Kde se čekající nebo chybné události hromadí.', descriptionEn: 'Where pending or failed events accumulate.', height: 'trend' },
  { id: 2, titleCs: 'Vývoj dead-letter toku', titleEn: 'Dead-letter trend', descriptionCs: 'Ukazuje, zda jde o jednorázovou nebo pokračující závadu.', descriptionEn: 'Shows whether a failure is isolated or ongoing.', height: 'trend' },
]

export const AI_INSIGHTS: InsightPanel[] = [
  { id: 2, titleCs: 'Náklady LLM za 24 hodin', titleEn: 'LLM spend over 24 hours', descriptionCs: 'Odvozené provozní náklady všech modelových volání.', descriptionEn: 'Derived operational cost of all model calls.' },
  { id: 4, titleCs: 'Chybovost za 15 minut', titleEn: '15-minute error rate', descriptionCs: 'Aktuální spolehlivost agentních volání.', descriptionEn: 'Current reliability of agent calls.' },
  { id: 5, titleCs: 'Přeskočeno bez API klíče', titleEn: 'Skipped without API key', descriptionCs: 'Požadavky, které nebyly skutečně provedeny.', descriptionEn: 'Requests that were not actually executed.' },
  { id: 10, titleCs: 'Odezva modelů', titleEn: 'Model latency', descriptionCs: 'Medián a pomalý konec úspěšných volání.', descriptionEn: 'Median and slow tail of successful calls.', height: 'trend' },
]
