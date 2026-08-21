// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Catalogue of the device sensors and device capabilities the CUSTOMER APP uses
// (openbank-app, KMP/Compose — a separate repository, ADR-0074).
//
// Companion to /docs/customer-app: that page is the plan-vs-reality dossier of the
// app as a whole and derives its facts from an artefact; this one is the curated
// per-sensor view — what each signal is for, how the customer triggers it, where
// it lives in the app and how it is configured.
//
// Every entry carries `source`: the file in openbank-app that implements it. That
// path is the evidence for the entry existing at all, and the thing to re-read
// before changing a description here. A `planned` entry has no implementation by
// definition — its `gap` says what is missing and why.
//
// The rule that governs this whole surface, stated in the app's own KDoc
// (PrivacyPosture.kt, TravelDetector.kt, Uwb.kt) and repeated on the index page:
// a measurement may SELECT, SUGGEST or ADD FRICTION — never authorise. No sensor
// reading ever unlocks anything, approves a payment or lowers an auth boundary.

import type { Status } from '@/lib/docs/status'

export type SensorFamily = 'motion' | 'proximity' | 'environment' | 'privacy' | 'shortcuts'

export type Platform = 'ios' | 'android'

export interface BiText {
  cs: string
  en: string
}

export interface SensorEntry {
  id: string
  family: SensorFamily
  /** Customer-facing name of the feature, not the sensor. */
  title: BiText
  /** The physical sensor or OS signal actually read. */
  signal: BiText
  /** What problem it solves — the use case. */
  useCase: BiText
  /** How the customer triggers it. "Automatic" is an answer, and a common one. */
  invocation: BiText
  /** Gesture / Siri phrase / widget, when there is one. */
  shortcut: BiText | null
  /** Where in the app the behaviour appears. */
  where: BiText
  /** Setting that governs it + its default, or null when there is nothing to configure. */
  setting: BiText | null
  /** Why it is worth having. */
  value: BiText
  status: Status
  platforms: Platform[]
  /** OS permission prompt the customer will see, when any. */
  permission: BiText | null
  /** Implementation path in the openbank-app repository. */
  source: string
  /** Honest limitation or missing piece. Always present — none of these is complete. */
  gap: BiText
}

export const FAMILY_META: Record<SensorFamily, { title: BiText; blurb: BiText; icon: string }> = {
  motion: {
    title: { cs: 'Pohyb a gesta', en: 'Motion & gestures' },
    blurb: {
      cs: 'Akcelerometr, gyroskop a krokoměr. Fyzické gesto místo tapnutí — ťuknutí telefonů, otočení displejem dolů, zatřesení, natočení.',
      en: 'Accelerometer, gyroscope and pedometer. A physical gesture instead of a tap — knocking phones together, turning the screen down, a shake, a twist.',
    },
    icon: 'Activity',
  },
  proximity: {
    title: { cs: 'Blízkost a rádio', en: 'Proximity & radio' },
    blurb: {
      cs: 'Bluetooth LE a ultra-širokopásmové rádio (UWB). Kdo je poblíž, kterým směrem mířím a co je právě připojené k telefonu.',
      en: 'Bluetooth LE and ultra-wideband (UWB). Who is nearby, which one am I pointing at, and what is currently connected to the phone.',
    },
    icon: 'Bluetooth',
  },
  environment: {
    title: { cs: 'Prostředí a stav zařízení', en: 'Environment & device state' },
    blurb: {
      cs: 'Okolní světlo, teplota a režim úspory energie. Aplikace se přizpůsobí tomu, kde a v jakém stavu telefon je.',
      en: 'Ambient light, thermal state and power saving. The app adapts to where the phone is and what state it is in.',
    },
    icon: 'Sun',
  },
  privacy: {
    title: { cs: 'Soukromí a integrita', en: 'Privacy & integrity' },
    blurb: {
      cs: 'Signály, které chrání obsah na obrazovce a identitu: nahrávání obrazovky, snímky, jailbreak, biometrie, kamera, schránka.',
      en: 'Signals that protect what is on screen and who is using it: screen recording, screenshots, jailbreak, biometrics, camera, clipboard.',
    },
    icon: 'ShieldCheck',
  },
  shortcuts: {
    title: { cs: 'Zkratky a doplňky', en: 'Shortcuts & extensions' },
    blurb: {
      cs: 'Jak se dá funkce vyvolat mimo aplikaci: Siri, appka Zkratky, widgety, Live Activities, Ovládací centrum, hodinky.',
      en: 'How a feature is reached from outside the app: Siri, the Shortcuts app, widgets, Live Activities, Control Center, the watch.',
    },
    icon: 'Command',
  },
}

export const FAMILY_ORDER: SensorFamily[] = ['motion', 'proximity', 'environment', 'privacy', 'shortcuts']

export const SENSORS: SensorEntry[] = [
  // ---------------------------------------------------------------- motion
  {
    id: 'bump-to-pay',
    family: 'motion',
    title: { cs: 'Ťuknutí telefonů (bump-to-pay)', en: 'Bump to pay' },
    signal: { cs: 'Akcelerometr, ~50 Hz, jen na platební obrazovce', en: 'Accelerometer, ~50 Hz, payment screen only' },
    useCase: {
      cs: 'Vybrat příjemce mezi několika lidmi v místnosti u platby poblíž, bez QR kódu a bez seznamu jmen.',
      en: 'Pick the payee among several people in the room for a nearby payment, with no QR code and no list of names.',
    },
    invocation: {
      cs: 'Oba telefony se lehce ťuknou o sebe ve chvíli, kdy je otevřená platba poblíž.',
      en: 'The two phones are knocked lightly together while the nearby-payment screen is open.',
    },
    shortcut: { cs: 'Fyzické gesto — ťuknutí', en: 'Physical gesture — a knock' },
    where: { cs: 'Domů → platba poblíž (NearPay)', en: 'Home → nearby payment (NearPay)' },
    setting: {
      cs: 'Bez nastavení. Senzor běží jen po dobu otevřené platební obrazovky a zastaví se při jejím zavření.',
      en: 'No setting. The sensor runs only while the payment screen is open and is stopped on dispose.',
    },
    value: {
      cs: 'Řeší jedinou věc, kterou BLE neumí: RSSI je údaj o síle signálu, ne o vzdálenosti, takže seznam šesti lidí u stolu nejde spolehlivě seřadit. Shodný náraz v čase na obou zařízeních je jednoznačný.',
      en: 'Solves the one thing BLE cannot: RSSI is a power reading, not a distance, so a six-peer list at a table is unorderable. A knock matching in time on both devices is unambiguous.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/MotionMonitor.kt',
    gap: {
      cs: 'iOS a Android se neshodnou na znaménku os akcelerometru (vektor gravitace vs. reakční síla); iOS actual proto znaménko obrací, aby sdílený kód viděl jednu konvenci. Bez fyzického zařízení nejde práh doladit měřením.',
      en: 'iOS and Android disagree on accelerometer sign convention (gravity vector vs. reaction force); the iOS actual negates so shared code sees one convention. Without physical devices the threshold cannot be tuned by measurement.',
    },
  },
  {
    id: 'face-down-lock',
    family: 'motion',
    title: { cs: 'Zamknutí při otočení displejem dolů', en: 'Screen-down auto-lock' },
    signal: { cs: 'Akcelerometr — klid + orientace displejem dolů', en: 'Accelerometer — stillness plus a face-down orientation' },
    useCase: {
      cs: 'Odemčená obrazovka s zůstatkem položená na stole ukazuje zůstatek celé místnosti, dokud nezhasne displej. Aplikace se zamyká při odchodu na pozadí, tenhle případ je opačný — zůstává v popředí.',
      en: 'An unlocked balance screen left on a desk shows the balance to the room until the display times out. The app re-locks on backgrounding; this is the opposite case — it stays in the foreground.',
    },
    invocation: {
      cs: 'Telefon se položí displejem dolů. Nikdo tak telefon nepokládá omylem, takže se signál plní rychle.',
      en: 'The phone is placed screen-down. Nobody rests a phone face-down by accident, so the signal is obeyed quickly.',
    },
    shortcut: { cs: 'Fyzické gesto — otočení displejem dolů', en: 'Physical gesture — turn the phone face-down' },
    where: { cs: 'Kdekoli v odemčené aplikaci v popředí', en: 'Anywhere in the unlocked app while in the foreground' },
    setting: { cs: 'Bez nastavení — funkce jen přidává zámek, nikdy neodemyká.', en: 'No setting — the feature only ever adds a lock, never unlocks.' },
    value: {
      cs: 'Falešně pozitivní výsledek stojí jedno Face ID. Varianta „telefon leží displejem nahoru“ byla zkoušena a odstraněna: klid nerozliší „položeno na stůl“ od „držím a čtu“, a zámek, který sepne čtenáři pod rukama, je přesně to, kvůli čemu lidé bezpečnostní funkce vypínají.',
      en: 'A false positive costs one Face ID prompt. Locking a merely face-up phone was tried and removed: stillness cannot tell "put down on a desk" from "held still while reading", and a lock that fires under a reader is what makes people switch a security feature off.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/PrivacyPosture.kt',
    gap: {
      cs: 'Rozhoduje jediný signál. Falešnou pozitivitu snižuje připojené Bluetooth příslušenství (viz „Důvěryhodné Bluetooth příslušenství“), ale jen na Androidu.',
      en: 'One signal decides. The false-positive rate is cut by a connected Bluetooth accessory (see "Trusted Bluetooth accessory"), but on Android only.',
    },
  },
  {
    id: 'tilt-to-reveal',
    family: 'motion',
    title: { cs: 'Naklonění pro zobrazení částek', en: 'Tilt to reveal amounts' },
    signal: { cs: 'Gyroskop — velikost úhlové rychlosti (rad/s)', en: 'Gyroscope — magnitude of angular velocity (rad/s)' },
    useCase: {
      cs: 'Skryté částky (ať už schované ručně, nebo automaticky v ostrém světle) odhalit na okamžik, bez tapnutí na obrazovku.',
      en: 'Briefly reveal hidden amounts — hidden manually or automatically in bright light — without tapping the screen.',
    },
    invocation: { cs: 'Krátké záměrné otočení telefonem v ruce.', en: 'A short deliberate twist of the phone in the hand.' },
    shortcut: { cs: 'Fyzické gesto — twist', en: 'Physical gesture — a twist' },
    where: { cs: 'Domů — karta se zůstatkem a částky v přehledu', en: 'Home — the balance card and amounts in the overview' },
    setting: { cs: 'Bez nastavení; navazuje na skrývání částek.', en: 'No setting; rides on the amount-hiding behaviour.' },
    value: {
      cs: 'Vrací kontrolu tam, kde ji automatika vzala — skrytí je automatické, odhalení vždy záměrné.',
      en: 'Gives control back where automation took it — hiding is automatic, revealing is always deliberate.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/TiltMonitor.kt',
    gap: {
      cs: 'Čte se jen VELIKOST úhlové rychlosti, nikdy znaménkové hodnoty os — po incidentu, kdy gesto na jedné platformě fungovalo obráceně, je velikost jediný signál, který neshoda ve znaméncích nedokáže pokazit. Směr otočení proto rozlišit nelze.',
      en: 'Only the MAGNITUDE of angular velocity is read, never signed per-axis values — after an incident where a gesture fired backwards on one platform, magnitude is the only signal a sign disagreement cannot corrupt. Direction of the twist is therefore not distinguishable.',
    },
  },
  {
    id: 'shake-to-refresh',
    family: 'motion',
    title: { cs: 'Zatřesení pro obnovení', en: 'Shake to refresh' },
    signal: { cs: 'Akcelerometr — vzorec zatřesení', en: 'Accelerometer — a shake pattern' },
    useCase: { cs: 'Znovu načíst seznam transakcí bez hledání tlačítka.', en: 'Reload the transaction list without hunting for a button.' },
    invocation: { cs: 'Zatřesení telefonem na obrazovce Transakce.', en: 'Shake the phone on the Transactions screen.' },
    shortcut: { cs: 'Fyzické gesto — zatřesení', en: 'Physical gesture — a shake' },
    where: { cs: 'Transakce', en: 'Transactions' },
    setting: { cs: 'Bez nastavení.', en: 'No setting.' },
    value: {
      cs: 'Obnovení bez UI prvku navíc; obrazovky, které skutečné obnovení nepředají, gesto prostě nemají (výchozí no-op) místo aby spadly.',
      en: 'A refresh with no extra UI element; screens that do not wire a real refresh simply do not get the gesture (a no-op default) rather than crashing.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'composeApp/src/commonMain/kotlin/tech/openbank/app/ui/TransactionsScreen.kt',
    gap: {
      cs: 'Zapojeno jen na Transakcích; ostatní seznamy (karty, dokumenty) gesto nemají.',
      en: 'Wired on Transactions only; other lists (cards, documents) do not have the gesture.',
    },
  },
  {
    id: 'step-nudge',
    family: 'motion',
    title: { cs: 'Kroky jako herní prvek', en: 'Step count as a game element' },
    signal: { cs: 'Krokoměr — kroky od místní půlnoci', en: 'Pedometer — steps since local midnight' },
    useCase: {
      cs: 'Drobná odměna v herní vrstvě aplikace za pasivní aktivitu, nejnižší úroveň — nemá vliv na nic bankovního.',
      en: 'A small reward in the app’s game layer for passive activity, the lowest tier — it affects nothing in banking.',
    },
    invocation: { cs: 'Automaticky, ale výhradně po zapnutí přepínače v Nastavení.', en: 'Automatic, but only after the customer turns the Settings toggle on.' },
    shortcut: null,
    where: { cs: 'Domů — herní prvky; přepínač v Profil → Nastavení', en: 'Home — game elements; toggle in Profile → Settings' },
    setting: {
      cs: 'Profil → Nastavení → „Kroky“. VÝCHOZÍ VYPNUTO. Zobrazuje se jen když je zapnutý zároveň i přepínač „Herní prvky“.',
      en: 'Profile → Settings → "Steps". OFF BY DEFAULT. Only shown when the "Game elements" toggle is on as well.',
    },
    value: {
      cs: 'Stejné pravidlo jako u gamifikace: hravé funkce jsou opt-in. Zapnutí znamená systémový dotaz na oprávnění, takže je nepřijatelné zapnout to za zákazníka.',
      en: 'Same rule as gamification: playful features are opt-in. Turning it on triggers an OS permission prompt, so enabling it on the customer’s behalf is not acceptable.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: {
      cs: 'ANO — ACTIVITY_RECOGNITION (Android 29+), systémový dialog o pohybových datech (iOS, NSMotionUsageDescription).',
      en: 'YES — ACTIVITY_RECOGNITION (Android 29+), the OS motion-data dialog (iOS, NSMotionUsageDescription).',
    },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/StepCounter.kt',
    gap: {
      cs: 'Android hlásí monotónní počet od startu zařízení, takže denní součet stojí na uložené základní hodnotě per den; iOS umí „kroky od data“ nativně. Dvě různé cesty ke stejnému číslu.',
      en: 'Android reports a monotonic count since boot, so the daily total rests on a persisted per-day baseline; iOS reports "steps since a date" natively. Two different routes to the same number.',
    },
  },
  // ------------------------------------------------------------- proximity
  {
    id: 'nearpay-ble',
    family: 'proximity',
    title: { cs: 'Platba poblíž přes BLE (QRlessPay)', en: 'Nearby payment over BLE (QRlessPay)' },
    signal: { cs: 'Bluetooth LE — inzerce a skenování bez GATT spojení', en: 'Bluetooth LE — advertising and scanning, no GATT connection' },
    useCase: {
      cs: 'Zaplatit člověku vedle sebe bez skenování QR kódu: příjemce inzeruje, plátce ho vidí v seznamu a ťukne.',
      en: 'Pay the person next to you without scanning a QR code: the receiver advertises, the payer sees them in a list and taps.',
    },
    invocation: {
      cs: 'Příjemce otevře svou QR kartu (tím začne inzerovat), plátce otevře platbu poblíž a vybere ho ze seznamu.',
      en: 'The receiver opens their QR card (which starts the advertisement), the payer opens nearby payment and picks them from the list.',
    },
    shortcut: { cs: 'Siri / Zkratky: „Ukázat můj QR kód“', en: 'Siri / Shortcuts: "Show my QR code"' },
    where: { cs: 'Domů → QR karta (příjem) a platba poblíž (odeslání)', en: 'Home → QR card (receive) and nearby payment (send)' },
    setting: {
      cs: 'Bez přepínače: inzerce běží jen po dobu otevřené obrazovky. Vysílá se pouze to, co je na QR kartě vidět (jméno, případně částka) — nikdy číslo účtu.',
      en: 'No toggle: advertising runs only while that screen is open. Only what the QR card already shows is broadcast (display name, optional amount) — never an account number.',
    },
    value: {
      cs: 'Bez spojení a levné na baterii — vše se vejde do inzerátu. Funguje iOS↔iOS, iOS↔Android i Android↔Android, protože skener přijímá obě kódování (local name u iOS, service data u Androidu).',
      en: 'Connectionless and battery-cheap — everything rides in the advertisement. Works iOS↔iOS, iOS↔Android and Android↔Android because the scanner accepts both encodings (local name on iOS, service data on Android).',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Bluetooth (BLUETOOTH_CONNECT na Androidu, systémový dotaz na iOS)', en: 'Bluetooth (BLUETOOTH_CONNECT on Android, the OS prompt on iOS)' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/ProximityBeacon.kt',
    gap: {
      cs: 'Řazení podle RSSI není vzdálenost — proto existuje ťuknutí telefonů a UWB. Detail protokolu a bezpečnostních vrstev je na /docs/qrlesspay (ADR-0095).',
      en: 'RSSI ordering is not distance — which is why the knock gesture and UWB exist. Protocol and security layers are documented at /docs/qrlesspay (ADR-0095).',
    },
  },
  {
    id: 'uwb-point-to-pay',
    family: 'proximity',
    title: { cs: 'Namíření pro platbu (UWB)', en: 'Point to pay (UWB)' },
    signal: { cs: 'Ultra-širokopásmové rádio — čas letu, ~10 cm + směrový vektor', en: 'Ultra-wideband — time of flight, ~10 cm plus a direction vector' },
    useCase: {
      cs: 'Mezi několika lidmi v dosahu vybrat toho, na koho zrovna mířím telefonem.',
      en: 'Among several people in range, select the one the phone is being aimed at.',
    },
    invocation: { cs: 'Namíření telefonu na příjemce při platbě poblíž.', en: 'Aim the phone at the payee during a nearby payment.' },
    shortcut: null,
    where: { cs: 'Platba poblíž — pořadí a směrová šipka v seznamu příjemců', en: 'Nearby payment — ordering and the aim arrow in the payee list' },
    setting: { cs: 'Bez nastavení; závisí na hardwaru.', en: 'No setting; hardware-dependent.' },
    value: {
      cs: 'Nejen UX: výměna při UWB měření je kryptograficky rozprostřená (STS), takže útočník s relayem nedokáže vzdálené zařízení vydávat za blízké. RSSI jde zesílit a přehrát, mez času letu ne. UWB je proto skutečný DŮKAZ blízkosti — a přesto jen vybírá příjemce, platbu autorizuje výhradně SCA.',
      en: 'Not just UX: the UWB ranging exchange is cryptographically scrambled (STS), so a relay attacker cannot make a far device appear near. RSSI can be amplified and replayed; a time-of-flight bound cannot. UWB is a genuine proximity PROOF — and still only selects a payee; SCA alone authorises the payment.',
    },
    status: 'partial',
    platforms: ['ios', 'android'],
    permission: { cs: 'Systémový dotaz na Nearby Interaction (iOS)', en: 'The Nearby Interaction prompt (iOS)' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/Uwb.kt',
    gap: {
      cs: 'Omezeno hardwarem a OS (iPhone 11+ mimo SE, hrstka Android vlajkových lodí). Bez UWB čipu se seznam vrací k pořadí podle RSSI — degradovaný „jakoby“ režim vědomě neexistuje, falešná šipka na zařízení, které neumí měřit, by byla horší než žádná.',
      en: 'Hardware- and OS-gated (iPhone 11+ excluding SE, a handful of Android flagships). With no UWB chip the list falls back to RSSI ordering — there is deliberately no degraded "pretend" mode; a fake arrow on a device that cannot range would be worse than no arrow.',
    },
  },
  {
    id: 'trusted-bt-device',
    family: 'proximity',
    title: { cs: 'Důvěryhodné Bluetooth příslušenství', en: 'Trusted Bluetooth accessory' },
    signal: { cs: 'Právě PŘIPOJENÉ klasické Bluetooth profily (HEADSET / A2DP)', en: 'Currently CONNECTED classic Bluetooth profiles (HEADSET / A2DP)' },
    useCase: {
      cs: 'Snížit falešné poplachy zamykání displejem dolů: připojená sluchátka nebo autorádio jsou známkou toho, že telefon je pořád u zákazníka.',
      en: 'Cut false positives of the screen-down auto-lock: connected headphones or a car system indicate the phone is still with the customer.',
    },
    invocation: { cs: 'Automaticky — nic se nezapíná, pouze potlačí nejbližší spuštění zámku.', en: 'Automatic — nothing to switch on; it suppresses the auto-lock’s next trigger.' },
    shortcut: null,
    where: { cs: 'Neviditelné — projeví se jako nezamknutí při otočení displejem dolů', en: 'Invisible — it shows up as the screen-down lock not firing' },
    setting: {
      cs: 'Bez nastavení a záměrně bez výběru zařízení: počítá se jakékoli právě připojené klasické příslušenství, aby přibližný signál nepotřeboval vlastní obrazovku nastavení.',
      en: 'No setting and deliberately no device picker: any currently-connected classic accessory counts, so an approximate signal needs no settings UI of its own.',
    },
    value: {
      cs: 'Historie párování byla zvážena a zamítnuta — zůstává pravdivá dlouho poté, co zařízení naposledy bylo poblíž, takže jako kontrola „právě teď“ je bezcenná. Neposouvá žádnou hranici autentizace: PIN i biometrie platí dál.',
      en: 'Pairing history was investigated and rejected — it stays true long after a device was last nearby, so as a "right now" check it is useless. It moves no auth boundary: PIN and biometrics still gate everything real.',
    },
    status: 'partial',
    platforms: ['android'],
    permission: { cs: 'Žádný nový dotaz — BLUETOOTH_CONNECT je už deklarované kvůli platbě poblíž.', en: 'No new prompt — BLUETOOTH_CONNECT is already declared for nearby payment.' },
    source: 'shared/src/androidMain/kotlin/tech/openbank/app/proximity/',
    gap: {
      cs: 'Jen Android — iOS nemá API pro výčet právě připojeného klasického příslušenství. I na Androidu vidí jen klasické profily, ne BLE-only hodinky: takový zákazník signál nikdy nedostane a funkce se pro něj chová, jako by nebyla.',
      en: 'Android only — iOS has no API to enumerate currently-connected classic accessories. Even on Android it sees classic profiles only, not BLE-only wearables: such a customer never flips the signal and the feature degrades to absent.',
    },
  },
  {
    id: 'nfc',
    family: 'proximity',
    title: { cs: 'NFC / bezkontaktní platba telefonem', en: 'NFC / contactless payment' },
    signal: { cs: 'NFC — v aplikaci zatím žádný kód', en: 'NFC — no code in the app today' },
    useCase: { cs: 'Placení telefonem u terminálu.', en: 'Paying at a terminal with the phone.' },
    invocation: { cs: 'Neimplementováno.', en: 'Not implemented.' },
    shortcut: null,
    where: { cs: '—', en: '—' },
    setting: { cs: '—', en: '—' },
    value: {
      cs: 'Uvedeno záměrně jako chybějící: platba poblíž tenhle případ NEŘEŠÍ (řeší platbu člověku, ne terminálu) a bez explicitního záznamu by se z toho stala tichá mezera.',
      en: 'Listed deliberately as missing: nearby payment does NOT cover this case (it pays a person, not a terminal), and without an explicit record it becomes a silent gap.',
    },
    status: 'planned',
    platforms: ['android'],
    permission: null,
    source: '—',
    gap: {
      cs: 'V repozitáři openbank-app není žádná NFC implementace. Na iOS je HCE pro platby vyhrazené Apple Pay, takže reálná cesta vede jen přes Android.',
      en: 'There is no NFC implementation in the openbank-app repository. On iOS, payment HCE is reserved for Apple Pay, so the only real route is Android.',
    },
  },
  // ----------------------------------------------------------- environment
  {
    id: 'light-auto-hide',
    family: 'environment',
    title: { cs: 'Skrytí částek v ostrém světle', en: 'Auto-hide amounts in bright light' },
    signal: { cs: 'Čidlo okolního světla (lux)', en: 'Ambient-light sensor (lux)' },
    useCase: {
      cs: 'Ostré světlo je dobrá aproximace „jsem venku mezi lidmi“ — částky se schovají samy.',
      en: 'Bright light is a decent proxy for "outdoors, among people" — amounts hide themselves.',
    },
    invocation: { cs: 'Automaticky podle osvětlení.', en: 'Automatic, from the light level.' },
    shortcut: { cs: 'Zpět je vidět naklonením telefonu (viz Naklonění pro zobrazení částek).', en: 'Revealed again by a twist (see Tilt to reveal amounts).' },
    where: { cs: 'Domů — částky v přehledu', en: 'Home — amounts in the overview' },
    setting: {
      cs: 'Bez přepínače. Automatika umí částky pouze SKRÝT, nikdy je sama neodhalí — ruční odhalení proto nemůže být vzápětí zrušeno senzorem.',
      en: 'No toggle. The automation can only ever turn hiding ON, never off — so a manual reveal cannot be instantly undone by the sensor.',
    },
    value: {
      cs: 'Ochrana v momentě, kdy si o ni zákazník neřekne, protože si nebezpečí neuvědomí.',
      en: 'Protection at the moment the customer would not ask for it, because they do not notice the exposure.',
    },
    status: 'partial',
    platforms: ['android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/proximity/LightSensorMonitor.kt',
    gap: {
      cs: 'Na iOS neexistuje veřejné API pro čidlo okolního světla — Apple hodnotu v luxech nikdy nezpřístupnil. `UIScreen.main.brightness` odráží nastavení jasu, ne prostředí, takže by šlo o zavádějící náhradu; iOS proto hlásí senzor jako nedostupný a nic nepředstírá.',
      en: 'iOS exposes no public ambient-light API — Apple never surfaced the raw lux value. `UIScreen.main.brightness` reflects the brightness setting, not the environment, so it would be a misleading proxy; iOS therefore reports the sensor unavailable and fakes nothing.',
    },
  },
  {
    id: 'brightness-boost',
    family: 'environment',
    title: { cs: 'Zesílení jasu na slunci', en: 'Brightness boost in sunlight' },
    signal: { cs: 'Čidlo okolního světla → přepsání jasu displeje', en: 'Ambient-light sensor → screen-brightness override' },
    useCase: { cs: 'Přečíst zůstatek nebo QR kód na přímém slunci.', en: 'Read a balance or a QR code in direct sun.' },
    invocation: { cs: 'Automaticky v ostrém světle; po odeznění se řízení vrací systému.', en: 'Automatic in bright light; control is handed back to the system afterwards.' },
    shortcut: null,
    where: { cs: 'Domů a QR karta', en: 'Home and the QR card' },
    setting: { cs: 'Bez nastavení — dočasné přepsání, ne trvalá změna jasu.', en: 'No setting — a temporary override, not a permanent brightness change.' },
    value: {
      cs: 'Zvoleno místo „režimu vysokého kontrastu“: nepotřebuje nové barevné tokeny ani revizi každé obrazovky a na slunci je stejně účinnější — úpravy kontrastu nepřebijí přímé slunce na nezesíleném panelu.',
      en: 'Chosen over a "high contrast mode": it needs no new colour tokens and no per-screen audit, and is the more effective lever anyway — contrast tweaks cannot out-compete direct sun on an unboosted panel.',
    },
    status: 'partial',
    platforms: ['android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'composeApp/src/commonMain/kotlin/tech/openbank/app/platform/ScreenBrightnessOverride.kt',
    gap: {
      cs: 'Spouštěč je čidlo světla, takže na iOS ze stejného důvodu nenastane (viz předchozí položka).',
      en: 'The trigger is the light sensor, so on iOS it never fires, for the same reason as the entry above.',
    },
  },
  {
    id: 'low-power-throttle',
    family: 'environment',
    title: { cs: 'Šetření při režimu úspory energie', en: 'Backing off under Low Power Mode' },
    signal: { cs: 'Battery Saver / Low Power Mode — jednorázový dotaz, ne posluchač', en: 'Battery Saver / Low Power Mode — a snapshot check, not a listener' },
    useCase: { cs: 'Nevybíjet skoro vybitý telefon pravidelným dotazováním na pozadí obrazovky Domů.', en: 'Stop draining an almost-empty phone with Home’s steady background poll.' },
    invocation: { cs: 'Automaticky, podle systémového přepínače zákazníka.', en: 'Automatic, from the customer’s own system toggle.' },
    shortcut: null,
    where: { cs: 'Domů — interval obnovování dat', en: 'Home — the data refresh interval' },
    setting: { cs: 'Řídí se systémovým nastavením telefonu, aplikace vlastní přepínač nemá.', en: 'Governed by the phone’s own system setting; the app has no toggle of its own.' },
    value: {
      cs: 'Respektuje rozhodnutí, které zákazník už udělal na úrovni OS, místo aby ho aplikace obcházela.',
      en: 'Respects a decision the customer already made at the OS level instead of working around it.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'composeApp/src/commonMain/kotlin/tech/openbank/app/platform/PowerState.kt',
    gap: {
      cs: 'Jednorázový dotaz, ne posluchač — přepnutí režimu uprostřed session se projeví až u dalšího cyklu.',
      en: 'A snapshot, not a listener — toggling the mode mid-session only takes effect on the next cycle.',
    },
  },
  {
    id: 'thermal-throttle',
    family: 'environment',
    title: { cs: 'Šetření při přehřátí', en: 'Backing off under thermal stress' },
    signal: { cs: 'Teplotní stav OS — nad nejmírnějším stupněm', en: 'OS thermal state — past the mildest tier' },
    useCase: { cs: 'Nepřilévat práci telefonu, který už systém sám škrtí.', en: 'Stop adding work to a phone the OS is already throttling.' },
    invocation: { cs: 'Automaticky.', en: 'Automatic.' },
    shortcut: null,
    where: { cs: 'Domů — interval obnovování dat', en: 'Home — the data refresh interval' },
    setting: { cs: 'Bez nastavení.', en: 'No setting.' },
    value: {
      cs: 'Práh je „OS už nejspíš opravdu škrtí“, ne „telefon je vlažný“: Android THERMAL_STATUS_MODERATE (2. ze 7), iOS Serious (2. ze 4). Stupnice si číselně neodpovídají, každý práh je proto volen samostatně, ne jako převodní tabulka.',
      en: 'The threshold is "the OS is likely actually throttling", not "slightly warm": Android THERMAL_STATUS_MODERATE (2nd of 7), iOS Serious (2nd of 4). The scales do not line up numerically, so each threshold is chosen independently rather than as a mapping.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'composeApp/src/commonMain/kotlin/tech/openbank/app/platform/ThermalState.kt',
    gap: { cs: 'Stejně jako u úspory energie jde o jednorázový dotaz, ne o živý posluchač.', en: 'Like the power case, a snapshot check rather than a live listener.' },
  },
  {
    id: 'travel-detector',
    family: 'environment',
    title: { cs: 'Rozpoznání cesty do zahraničí', en: 'Travel detection' },
    signal: { cs: 'Systémové časové pásmo — nic víc', en: 'The system time zone — nothing else' },
    useCase: {
      cs: 'Nabídnout zapnutí karty pro zahraničí dřív, než ji terminál odmítne.',
      en: 'Offer to enable the card abroad before a terminal declines it.',
    },
    invocation: { cs: 'Automaticky po změně pásma; nabídku pak zákazník ťukne, nebo ignoruje.', en: 'Automatic when the zone changes; the customer then taps the suggestion, or ignores it.' },
    shortcut: null,
    where: { cs: 'Domů — nabídka nad přehledem; Karty', en: 'Home — a suggestion above the overview; Cards' },
    setting: { cs: 'Bez nastavení — nic se nemění samo, jde jen o návrh.', en: 'No setting — nothing changes on its own; it is only a suggestion.' },
    value: {
      cs: 'Časové pásmo telefon dostane od operátora krátce po přistání, nestojí žádné oprávnění, žádný dotaz na polohu ani položku v privacy manifestu, a nikdy neopustí zařízení. GPS by byla přesnější, jenže přesnost tahle funkce nepotřebuje — jen se rozhoduje, zda NABÍDNOUT.',
      en: 'The phone picks the zone up from the carrier shortly after landing; it costs no permission, no location prompt, no privacy-manifest entry, and never leaves the device. GPS would be more precise, but precision is not what this needs — it only decides whether to OFFER.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění — a to je celý smysl volby signálu.', en: 'No permission — which is the entire point of the signal choice.' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/travel/TravelDetector.kt',
    gap: {
      cs: 'Neznámé pásmo znamená „je pryč, ale nevíme kde“ — na nabídku karty do zahraničí to stačí, na měnové tipy ne.',
      en: 'An unfamiliar zone means "away, but we cannot name where" — enough for the card-abroad hint, not for currency suggestions.',
    },
  },
  // -------------------------------------------------------------- privacy
  {
    id: 'screen-recording',
    family: 'privacy',
    title: { cs: 'Detekce nahrávání a zrcadlení obrazovky', en: 'Screen-recording and mirroring detection' },
    signal: { cs: 'Systémové API pro záznam obrazovky (AirPlay, ReplayKit, nahrávání z Ovládacího centra)', en: 'The OS screen-capture API (AirPlay, ReplayKit, Control Center recording)' },
    useCase: { cs: 'Rozmazat citlivý obsah, jakmile začne nahrávání, které zákazník nezamýšlel.', en: 'Blur sensitive content the moment an unintended recording starts.' },
    invocation: { cs: 'Automaticky, s živou reakcí na změnu stavu.', en: 'Automatic, with a live change callback.' },
    shortcut: null,
    where: { cs: 'Všechny obrazovky s citlivým obsahem', en: 'Every screen with sensitive content' },
    setting: { cs: 'Bez nastavení — kontrola podle PSD2 čl. 98 a RTS příloha I §4, ne komfortní funkce.', en: 'No setting — a control under PSD2 Art. 98 and RTS Annex I §4, not a convenience feature.' },
    value: { cs: 'Chrání i proti sdílení obrazovky, které si zákazník sám zapnul a zapomněl na něj.', en: 'Also protects against a screen share the customer started themselves and forgot about.' },
    status: 'partial',
    platforms: ['ios'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/security/AppHardening.kt',
    gap: {
      cs: 'Android implementace je stub (vždy „bezpečno“); skutečné kontroly patří k úkolu Play Integrity (ADR-0066 S4b).',
      en: 'The Android implementation is a stub (always "safe"); the real checks belong to the Play Integrity task (ADR-0066 S4b).',
    },
  },
  {
    id: 'screenshots',
    family: 'privacy',
    title: { cs: 'Blokování snímků obrazovky', en: 'Screenshot blocking' },
    signal: { cs: 'FLAG_SECURE (Android) / vrstva secure text field (iOS)', en: 'FLAG_SECURE (Android) / a secure text-field layer (iOS)' },
    useCase: { cs: 'Zabránit tomu, aby zůstatek nebo údaje o kartě skončily v galerii telefonu.', en: 'Keep a balance or card details out of the phone’s photo library.' },
    invocation: { cs: 'Automaticky, dokud si zákazník snímky výslovně nepovolí.', en: 'Automatic, until the customer explicitly allows screenshots.' },
    shortcut: null,
    where: { cs: 'Celá aplikace', en: 'The whole app' },
    setting: {
      cs: 'Profil → Nastavení → „Povolit snímky obrazovky“. VÝCHOZÍ VYPNUTO (bezpečná strana), zapnutí je vědomé rozhodnutí zákazníka.',
      en: 'Profile → Settings → "Allow screenshots". OFF BY DEFAULT (the secure side); turning it on is the customer’s deliberate choice.',
    },
    value: { cs: 'Výchozí stav chrání, ale nebere zákazníkovi možnost — třeba doklad o platbě pro kamaráda.', en: 'The default protects without removing the option — a payment proof for a friend, say.' },
    status: 'partial',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'composeApp/src/commonMain/kotlin/tech/openbank/app/ui/HomeProfileOverlays.kt',
    gap: {
      cs: 'Android to vynucuje na úrovni OS (FLAG_SECURE); iOS umí obsah jen zakrýt nebo varovat, zablokovat snímek ne. Stejné nastavení tedy na každé platformě znamená jinou míru ochrany.',
      en: 'Android enforces it at the OS level (FLAG_SECURE); iOS can only redact or warn, never block. The same setting therefore means a different strength of protection per platform.',
    },
  },
  {
    id: 'jailbreak',
    family: 'privacy',
    title: { cs: 'Detekce jailbreaku a rootu', en: 'Jailbreak and root detection' },
    signal: { cs: 'Kontroly cest v souborovém systému + sonda sandboxu (fork)', en: 'Filesystem path checks plus a fork() sandbox probe' },
    useCase: { cs: 'Na kompromitovaném zařízení neuložit přihlašovací token.', en: 'Refuse to store credentials on a compromised device.' },
    invocation: { cs: 'Automaticky při startu, ještě před uložením jakéhokoli tokenu.', en: 'Automatic at startup, before any token is stored.' },
    shortcut: null,
    where: { cs: 'Start aplikace — varování o integritě', en: 'App startup — an integrity warning' },
    setting: { cs: 'Bez nastavení.', en: 'No setting.' },
    value: { cs: 'Posouvá kontrolu před okamžik, kdy by se dalo co ukrást.', en: 'Moves the check ahead of the moment there is anything to steal.' },
    status: 'partial',
    platforms: ['ios'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/security/AppHardening.kt',
    gap: {
      cs: 'Kontroly jsou heuristické, ne vyčerpávající (zachytí běžné jailbreaky). Android je zatím stub — vždy hlásí bezpečné zařízení (ADR-0066 S4b).',
      en: 'The checks are heuristic, not exhaustive (they catch common jailbreaks). Android is still a stub — it always reports a safe device (ADR-0066 S4b).',
    },
  },
  {
    id: 'biometrics-sca',
    family: 'privacy',
    title: { cs: 'Biometrie, Secure Enclave a passkey', en: 'Biometrics, Secure Enclave and passkeys' },
    signal: { cs: 'Face ID / Touch ID, klíč v Secure Enclave, WebAuthn assertion', en: 'Face ID / Touch ID, a Secure Enclave key, a WebAuthn assertion' },
    useCase: {
      cs: 'Přihlášení bez hesla a podpis platby podle PSD2 dynamic linking — částka a IBAN příjemce jsou vidět PŘED biometrickým potvrzením (RTS čl. 5).',
      en: 'Password-free sign-in and a PSD2 dynamic-linking payment signature — amount and creditor IBAN are shown BEFORE the biometric approval (RTS Art. 5).',
    },
    invocation: { cs: 'Přidržení tlačítka potvrzení u platby; passkey přímo na přihlašovací obrazovce.', en: 'Hold the confirm control on a payment; passkey straight from the login screen.' },
    shortcut: { cs: 'Passkey — nabídne se rovnou v poli přihlášení (conditional UI).', en: 'Passkey — offered inline in the login field (conditional UI).' },
    where: { cs: 'Přihlášení; potvrzení platby (SCA)', en: 'Login; payment confirmation (SCA)' },
    setting: { cs: 'Bez přepínače v aplikaci — biometrii spravuje systém.', en: 'No in-app toggle — biometrics are managed by the OS.' },
    value: {
      cs: 'Podpisový klíč je v Secure Enclave, biometricky uzamčený a nevyexportovatelný — z telefonu ho nelze zkopírovat ani na kompromitovaném zařízení.',
      en: 'The signing key lives in the Secure Enclave, biometrically gated and non-extractable — it cannot be copied off the phone even on a compromised device.',
    },
    status: 'live',
    platforms: ['ios'],
    permission: { cs: 'Systémový biometrický dotaz', en: 'The OS biometric prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/security/',
    gap: {
      cs: 'Popsáno v dossieru zákaznické aplikace (/docs/customer-app), kde je i stav pinningu certifikátů; Android má vlastní cestu (Keystore) mimo tuto položku.',
      en: 'Covered in the customer-app dossier (/docs/customer-app), which also carries certificate-pinning status; Android has its own route (Keystore) outside this entry.',
    },
  },
  {
    id: 'camera',
    family: 'privacy',
    title: { cs: 'Kamera — QR kód a sken dokladu', en: 'Camera — QR codes and ID scanning' },
    signal: { cs: 'Kamera; rozpoznávání textu na zařízení (VisionKit / VNRecognizeText)', en: 'Camera; on-device text recognition (VisionKit / VNRecognizeText)' },
    useCase: {
      cs: 'Načíst platbu z QR kódu (SPAYD) a projít onboardingem naskenováním občanky — včetně parseru MRZ (ICAO TD1).',
      en: 'Read a payment from a QR code (SPAYD) and complete onboarding by scanning an ID card — including an MRZ parser (ICAO TD1).',
    },
    invocation: { cs: 'Ťuknutí na sken QR na Domů; při onboardingu krok se skenem dokladu.', en: 'Tap QR scan on Home; during onboarding, the ID-scan step.' },
    shortcut: { cs: 'Siri / Zkratky: „Ukázat můj QR kód“ pro opačný směr (příjem).', en: 'Siri / Shortcuts: "Show my QR code" for the receiving direction.' },
    where: { cs: 'Domů → QR; Onboarding → sken dokladu', en: 'Home → QR; Onboarding → ID scan' },
    setting: { cs: 'Bez přepínače; oprávnění ke kameře řeší systém.', en: 'No toggle; camera permission is handled by the OS.' },
    value: {
      cs: 'Rozpoznávání běží VÝHRADNĚ na zařízení — během skenování neodchází nikam žádná data. Sandbox režim navíc umí data z dokladu anonymizovat.',
      en: 'Recognition runs ON-DEVICE ONLY — no data leaves the phone while scanning. The sandbox mode can additionally anonymise the scanned document data.',
    },
    status: 'live',
    platforms: ['ios'],
    permission: { cs: 'Systémový dotaz na kameru', en: 'The OS camera prompt' },
    source: 'iosApp/ + composeApp/src/commonMain/kotlin/tech/openbank/app/ui/',
    gap: {
      cs: 'OCR i MRZ stojí na Vision, tedy iOS. Android sken dokladu touto cestou nemá.',
      en: 'OCR and MRZ both rest on Vision, i.e. iOS. Android has no ID scan on this route.',
    },
  },
  {
    id: 'clipboard-iban',
    family: 'privacy',
    title: { cs: 'Chytré vložení IBAN ze schránky', en: 'Clipboard smart-paste for IBAN' },
    signal: { cs: 'Schránka — čte se až po ťuknutí na ikonu vložení', en: 'The clipboard — read only after the paste icon is tapped' },
    useCase: {
      cs: 'Zkopírované potvrzení z SMS nebo e-mailu má IBAN uprostřed věty; aplikace ho z textu vytáhne sama.',
      en: 'A copied SMS or e-mail confirmation carries the IBAN inside a sentence; the app extracts it from the text.',
    },
    invocation: { cs: 'Ťuknutí na ikonu vložení u pole IBAN.', en: 'Tap the paste icon next to the IBAN field.' },
    shortcut: null,
    where: { cs: 'Platba → pole IBAN', en: 'Payment → the IBAN field' },
    setting: { cs: 'Bez nastavení; schránka se nečte na pozadí ani při otevření obrazovky.', en: 'No setting; the clipboard is not read in the background or on screen open.' },
    value: {
      cs: 'Odpadá požadavek, aby zákazník zkopíroval přesně jen samotný IBAN — nejčastější zdroj překlepů při ručním přepisování.',
      en: 'Removes the requirement to have copied exactly the bare IBAN — the commonest source of typos when retyping by hand.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění (čtení je vázané na tapnutí)', en: 'No permission (the read is tied to a tap)' },
    source: 'composeApp/src/commonMain/kotlin/tech/openbank/app/ui/PaymentScreen.kt',
    gap: {
      cs: 'Extrahuje IBAN, ne částku ani variabilní symbol — ty se stále vyplňují ručně.',
      en: 'Extracts the IBAN, not the amount or the variable symbol — those are still filled in by hand.',
    },
  },
  {
    id: 'haptics',
    family: 'privacy',
    title: { cs: 'Haptická odezva', en: 'Haptic feedback' },
    signal: { cs: 'Vibrační motor — vzorce pro příchozí a odchozí platbu', en: 'The vibration motor — incoming and outgoing payment patterns' },
    useCase: { cs: 'Potvrdit akci hmatem, ne jen graficky.', en: 'Confirm an action by touch, not only visually.' },
    invocation: { cs: 'Automaticky u potvrzení platby a dalších akcí.', en: 'Automatic on payment confirmation and other actions.' },
    shortcut: null,
    where: { cs: 'Celá aplikace; přepínač Profil → Nastavení', en: 'The whole app; toggle in Profile → Settings' },
    setting: {
      cs: 'Profil → Nastavení → „Haptická odezva“. VÝCHOZÍ ZAPNUTO. Jediná globální brána, ne stupně „jemné“/„silné“.',
      en: 'Profile → Settings → "Haptic feedback". ON BY DEFAULT. A single global gate, not "subtle"/"strong" tiers.',
    },
    value: {
      cs: 'Stupně síly by znamenaly odhadnout hodnoty amplitudy bez fyzického zařízení, na kterém by je šlo cítit — po jednom takovém omylu se to znovu nedělá. Zapnuto/vypnuto je poctivější než vymyšlená kalibrace.',
      en: 'Strength tiers would mean guessing amplitude values with no physical device to feel them on — after getting that wrong once, not repeated. On/off is more honest than an invented calibration.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/haptics/HapticSettings.kt',
    gap: {
      cs: 'Android implementace byla původně prázdný stub — vibrace tam nefungovaly vůbec, dokud nebyly doplněny. Ukázkový případ, proč „funkce existuje“ neznamená „funkce běží na obou platformách“.',
      en: 'The Android implementation was originally an empty stub — vibration did not work there at all until it was filled in. A worked example of why "the feature exists" does not mean "the feature runs on both platforms".',
    },
  },
  {
    id: 'usage-nudge',
    family: 'privacy',
    title: { cs: 'Jemné upozornění na časté otevírání', en: 'Gentle usage-frequency nudge' },
    signal: { cs: 'Počet otevření aplikace za den — „senzor“ používání, ne hardwarový', en: 'App-open count per day — a usage "sensor", not a hardware one' },
    useCase: { cs: 'Nabídnout zákazníkovi vlastní pohled na to, jak často aplikaci otevírá.', en: 'Offer the customer a view of how often they open the app.' },
    invocation: { cs: 'Automaticky, nejvýše jednou denně, po překročení prahu.', en: 'Automatic, at most once a day, once a threshold is crossed.' },
    shortcut: null,
    where: { cs: 'Domů — proužek se stejným tvarem a umístěním jako pruh schválení', en: 'Home — a banner in the same shape and place as the approvals banner' },
    setting: { cs: 'Bez nastavení; nikdy nic nezamyká ani neblokuje.', en: 'No setting; it never locks or blocks anything.' },
    value: {
      cs: 'Časté otevírání samo o sobě nevypovídá nic o finančním zdraví — proto to nesmí vyznít jako obvinění a nesmí z toho plynout žádné omezení.',
      en: 'Frequent opening says nothing about financial health on its own — so it must never read as an accusation, and nothing restrictive may follow from it.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění; data zůstávají na zařízení.', en: 'No permission; the data stays on the device.' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/api/UsageTracker.kt',
    gap: {
      cs: '„Dnes“ je celé číslo dne od epochy, ne formátované datum — pro UX připomínku je hranice dne bez ohledu na časové pásmo přijatelné zjednodušení, pro cokoli účetního by nebyla.',
      en: '"Today" is a day-of-epoch integer, not a formatted date — a timezone-naive day boundary is acceptable for a UX nudge and would not be for anything of record.',
    },
  },
  // ------------------------------------------------------------ shortcuts
  {
    id: 'siri-shortcuts',
    family: 'shortcuts',
    title: { cs: 'Siri a appka Zkratky', en: 'Siri and the Shortcuts app' },
    signal: { cs: 'App Intents — čtyři akce registrované systémem', en: 'App Intents — four system-registered actions' },
    useCase: { cs: 'Otevřít to, kvůli čemu se aplikace nejčastěji spouští, hlasem nebo jedním tapnutím.', en: 'Reach the things the app is most often opened for, by voice or one tap.' },
    invocation: {
      cs: 'Hlasem přes Siri, nebo jako akce v appce Zkratky. Fráze musí obsahovat název aplikace — Siri to potřebuje k rozlišení.',
      en: 'By voice via Siri, or as an action in the Shortcuts app. Every phrase has to contain the app name — Siri needs it to disambiguate.',
    },
    shortcut: {
      cs: 'Čtyři akce: „Poslat peníze“, „Ukázat můj QR kód“, „Otevřít karty“, „Čekající schválení“.',
      en: 'Four actions: "Send money", "Show my QR code", "Open cards", "Pending approvals".',
    },
    where: { cs: 'Mimo aplikaci — Siri, Zkratky, Spotlight', en: 'Outside the app — Siri, Shortcuts, Spotlight' },
    setting: {
      cs: 'Bez nastavení: fráze se registrují samy, zákazník nemusí nic zapínat ani vytvářet.',
      en: 'No setup: the phrases register themselves; the customer configures nothing.',
    },
    value: { cs: 'Zkracuje cestu k akci na jedno vyslovení, aniž by cokoli obcházelo přihlášení.', en: 'Cuts the path to an action to a single phrase, without bypassing sign-in.' },
    status: 'live',
    platforms: ['ios'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'iosApp/iosApp/OpenBankAppIntents.swift',
    gap: {
      cs: 'Jen iOS — Android ekvivalent (App Actions / zkratky) neexistuje. Akce aplikaci otevřou, samy nic neprovedou.',
      en: 'iOS only — there is no Android equivalent (App Actions / shortcuts). The actions open the app; they do not perform anything on their own.',
    },
  },
  {
    id: 'widgets',
    family: 'shortcuts',
    title: { cs: 'Widgety, Live Activities a Ovládací centrum', en: 'Widgets, Live Activities and Control Center' },
    signal: { cs: 'WidgetKit — widget se zůstatkem, dvě Live Activities, ovládací prvek karet', en: 'WidgetKit — a balance widget, two Live Activities, a cards control' },
    useCase: {
      cs: 'Zůstatek na ploše; průběh platby a rozúčtování na zamčené obrazovce; karty z Ovládacího centra.',
      en: 'Balance on the home screen; payment and split progress on the lock screen; cards from Control Center.',
    },
    invocation: { cs: 'Přidání widgetu zákazníkem; Live Activity se objeví sama během operace.', en: 'The customer adds the widget; a Live Activity appears on its own during an operation.' },
    shortcut: { cs: 'Ovládací centrum — prvek pro karty', en: 'Control Center — the cards control' },
    where: { cs: 'Plocha, zamčená obrazovka, Dynamic Island, Ovládací centrum', en: 'Home screen, lock screen, Dynamic Island, Control Center' },
    setting: { cs: 'Widget si zákazník přidá sám; v aplikaci se nic nenastavuje.', en: 'The customer adds the widget themselves; nothing is configured in the app.' },
    value: { cs: 'Informace bez otevření aplikace — a tedy i bez odemykání.', en: 'Information without opening the app — and so without unlocking it.' },
    status: 'partial',
    platforms: ['ios'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'iosApp/OpenBankWidgetExt/',
    gap: {
      cs: 'Jen iOS; Android widgety nejsou. Součástí stromu je i companion pro hodinky (iosApp/OpenBankWatch), který sem patří rozsahem, ale není v tomto katalogu rozepsaný.',
      en: 'iOS only; there are no Android widgets. The tree also carries a watch companion (iosApp/OpenBankWatch), in scope by nature but not itemised in this catalogue.',
    },
  },
  {
    id: 'debug-gesture',
    family: 'shortcuts',
    title: { cs: 'Skryté diagnostické menu (7 tapnutí)', en: 'Hidden diagnostics menu (7 taps)' },
    signal: { cs: 'Gesto — sedm tapnutí na logo', en: 'A gesture — seven taps on the logo' },
    useCase: { cs: 'Diagnostika v sandbox/debug buildech: verze, konfigurace, stav edge.', en: 'Diagnostics in sandbox/debug builds: version, configuration, edge state.' },
    invocation: { cs: 'Sedm tapnutí na logo.', en: 'Seven taps on the logo.' },
    shortcut: { cs: 'Gesto — 7× tapnutí', en: 'Gesture — 7 taps' },
    where: { cs: 'Jen sandbox/debug buildy', en: 'Sandbox/debug builds only' },
    setting: { cs: 'Nelze zapnout v produkčním buildu.', en: 'Cannot be enabled in a production build.' },
    value: {
      cs: 'Bezpečnostní hranicí je typ buildu, ne gesto — gesto pouze otevírá plochu, která je už tak povolená. V produkci neexistuje, takže není co uhodnout.',
      en: 'The security boundary is the build type, not the gesture — the gesture merely opens a surface that is already permitted. In production it does not exist, so there is nothing to guess.',
    },
    status: 'live',
    platforms: ['ios', 'android'],
    permission: { cs: 'Žádné oprávnění', en: 'No permission prompt' },
    source: 'shared/src/commonMain/kotlin/tech/openbank/app/debug/DebugGate.kt',
    gap: {
      cs: 'Zobrazované hodnoty procházejí jedním allowlistem pro redakci (tajné hlavičky maskované, těla nezobrazená) — ADR-0070.',
      en: 'Displayed values pass through a single redaction allowlist (secret headers masked, bodies withheld) — ADR-0070.',
    },
  },
]

export function sensorsByFamily(family: SensorFamily): SensorEntry[] {
  return SENSORS.filter(s => s.family === family)
}

export function statusCounts(entries: SensorEntry[] = SENSORS): Record<Status, number> {
  const by: Record<Status, number> = { live: 0, partial: 0, planned: 0 }
  for (const e of entries) by[e.status]++
  return by
}
