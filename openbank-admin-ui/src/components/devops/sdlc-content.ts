// SPDX-License-Identifier: Apache-2.0
import { bi, type Bilingual as Copy } from '@/lib/i18n/bilingual'
export type TrainLane = 'batch' | 'fast' | 'strict'
export type TrainProgress = Record<TrainLane, number>

export const TRAIN_LANES = {
  batch: {
    title: bi('Dávkový vlak', 'Batch train'),
    subtitle: bi('Společná zkušební jízda', 'A shared test ride'),
    description: bi('Několik připravených změn se sveze společně. Bot sestaví jejich kombinaci nad aktuální hlavní větví a nechá ji znovu prověřit.', 'Several ready changes travel together. The bot combines them over the current main branch and has that combination checked again.'),
    stops: [bi('Nápad', 'Idea'), bi('Kontroly PR', 'PR checks'), bi('Nástup', 'Boarding'), bi('Společná jízda', 'Combined checks'), bi('Main', 'Main')],
    steps: [
      bi('Lvíček přináší návrh změny. Pull request (PR) je balíček úprav s vysvětlením, proč vznikl, a s testy nového chování.', 'A lion brings a proposed change. A pull request (PR) is a package of edits explaining why they exist, with tests for the new behaviour.'),
      bi('Každý cestující potřebuje vlastní jízdenku: úspěšné povinné i dodatečné kontroly. Musí být zapnuté automatické sloučení a splněná pravidla přijetí.', 'Every passenger needs their own ticket: successful required and additional checks. Auto-merge must be enabled and admission rules satisfied.'),
      bi('Výpravčí vybere připravené PR, obvykle od nejstarších. Složí je nad aktuální main, obnoví potřebné odvozené soubory a ověří přesný obsah společného ověřovacího PR — carrieru.', 'The dispatcher selects ready PRs, normally oldest first. It combines them over current main, regenerates derived files where needed and verifies the exact content of a shared test PR — the carrier.'),
      bi('Vlak má vlastní CI. Testuje se společný výsledek: dvě zelené změny si totiž mohou navzájem překážet. Cestující čekají na verdikt celé soupravy. Mezitím může probíhat CI dalších PR i rezervované přísné koleje; souběh kontrol ještě nedává soupravám právo současně měnit main.', 'The train has its own CI. It tests the combined result: two green changes can still interfere with each other. The passengers wait for the whole train’s verdict. Other PR checks and a reserved strict lane can run CI meanwhile; concurrent checks do not give trains permission to change main simultaneously.'),
      bi('Po úspěšném ověření bot slučuje zdrojové PR jednotlivě v pořadí vlaku. Před každým znovu ověří jejich aktuální podobu a záměr sloučit. Cizí posun main nebo změna PR odbavení zastaví.', 'After successful verification the bot merges the source PRs individually in train order. Before each merge it rechecks the current head and merge intent. An outside change to main or a changed PR stops settlement.'),
    ],
  },
  fast: {
    title: bi('Rychlá kolej', 'Fast lane'),
    subtitle: bi('Pro nezávislou změnu', 'For an independent change'),
    description: bi('Jeden cestující může kratší cestou, když se jeho soubory nepřekrývají s ostatními kandidáty ani s novými změnami na main a nejsou v chráněných cestách.', 'One passenger can take a shorter route when their files overlap neither other candidates nor new changes on main, and do not touch protected paths.'),
    stops: [bi('Nápad', 'Idea'), bi('Kontroly PR', 'PR checks'), bi('Nezávislost', 'Independence'), bi('Závěrečné ověření', 'Final check'), bi('Main', 'Main')],
    steps: [
      bi('Tentokrát cestuje jeden návrh změny. Rychlost vyplývá z nezávislosti změny, ne z jejího názvu či malé velikosti.', 'One proposed change is travelling this time. Its independence earns the shorter route, not its name or small size.'),
      bi('Vlastní CI i pravidla přijetí platí stejně jako na ostatních kolejích. Červená kontrola rychlou kolej nepustí.', 'The same own-PR CI and admission rules apply as on other lanes. A red check blocks the fast lane too.'),
      bi('Bot porovná skutečně změněné soubory s novými změnami na main a všemi ostatními kandidáty. Sdílené knihovny, GitOps, API smlouvy a další chráněné cesty rychlou kolej vylučují.', 'The bot compares the actual changed files with new changes on main and all other candidates. Shared libraries, GitOps, API contracts and other protected paths exclude this lane.'),
      bi('Společný carrier není potřeba. Bot rozhodne z aktuálních podkladů; i tato kolej může čekat na rezervaci přísné koleje.', 'No shared carrier is needed. The bot decides from current evidence; this lane can still wait for a strict-lane reservation.'),
      bi('Po závěrečném ověření získá PR stav merge-train success a může se sloučit. Výsledek pořád patří konkrétní ověřené podobě změny.', 'After final verification the PR gets merge-train success and can merge. The result still belongs to the specific verified revision of the change.'),
    ],
  },
  strict: {
    title: bi('Přísná kolej', 'Strict lane'),
    subtitle: bi('Když měníme i výpravčího', 'When the dispatcher changes too'),
    description: bi('Změny workflow, generátorů a citlivých pravidel mají zvláštní cestu. Ověřují se s aktuální main, aniž by získaly možnost samy si udělit povolení.', 'Workflow, generator and sensitive-rule changes take a special route. They are verified with current main without gaining the power to authorize themselves.'),
    stops: [bi('Citlivá změna', 'Sensitive change'), bi('Kontroly PR', 'PR checks'), bi('Volba cesty', 'Route selection'), bi('CI s main', 'CI with main'), bi('Main', 'Main')],
    steps: [
      bi('Mění se například workflow nebo generátor. Je to jako upravovat návěstidla, zatímco vlaky používají koleje: potřebujeme zvláštní ověření.', 'A workflow or generator is changing. It is like changing signals while trains use the tracks: special verification is needed.'),
      bi('Také tento PR musí splnit kontroly a pravidla přijetí. U codex/ je rozhodnutí vlastníka vázané na přesnou aktuální revizi; po změně obsahu je potřeba nové.', 'This PR also needs its checks and admission rules. For codex/ the owner’s decision binds to the exact current revision; a changed head needs a fresh decision.'),
      bi('Kde je to dovoleno, vznikne strict carrier čistě pomocí Gitu, bez generátorů. Změny rozhodující o kontrolách jedou samostatně. Workflow změny či potřebná regenerace používají aktualizaci vlastní větve.', 'Where allowed, a strict carrier is constructed with Git only, without generators. Changes that judge checks ride alone. Workflow changes or required regeneration use the branch-update path.'),
      bi('CI ověří kombinaci s aktuální main. Rezervace tratě omezuje souběžné odbavení, aby se ověřovaná kombinace stále neměnila. Rezervace má hranice při nečinnosti a dlouhém běhu.', 'CI verifies the combination with current main. A track reservation limits concurrent settlement so that combination does not keep changing. Reservations have stall and runtime bounds.'),
      bi('Sloučit lze jen ověřenou podobu s platným záměrem a kontrolami. Následují další koleje podle pravidel přednosti a střídání.', 'Only the verified revision with valid intent and checks may merge. Other lanes then take their turns according to priority and fairness rules.'),
    ],
  },
} satisfies Record<TrainLane, { title: Copy; subtitle: Copy; description: Copy; stops: Copy[]; steps: Copy[] }>

export const CI_CHECKS = [
  [bi('Dá se aplikace sestavit?', 'Can the application be built?'), bi('Sestavení, kontrola stylu a statická analýza hledají chyby ještě před spuštěním.', 'Build, style checks and static analysis look for problems before execution.')],
  [bi('Chová se správně?', 'Does it behave correctly?'), bi('Testy ověřují výsledky. Coverage ratchet hlídá, aby pokrytí neklesalo.', 'Tests verify outcomes. The coverage ratchet guards against decreasing coverage.')],
  [bi('Domluví se s ostatními?', 'Will it work with its neighbours?'), bi('API smlouvy, události a databázové změny mají vlastní kontroly kompatibility.', 'API contracts, events and database changes have their own compatibility checks.')],
  [bi('Je změna bezpečná a dohledatelná?', 'Is the change safe and traceable?'), bi('Kontrolují se tajné údaje, zranitelnosti, manifesty, pravidla architektury i vazba na důvod změny.', 'Checks cover secrets, vulnerabilities, manifests, architecture rules and the reason for the change.')],
]
