// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
'use client'

import Link from 'next/link'
import { QrCode, Camera, CheckCircle, Circle, Smartphone, ShieldCheck, Info, Printer, ArrowLeftRight, ScanLine, Hash } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

const ACCENT = 'var(--accent)'
const ACCENT_TEXT = 'var(--accent-text)'
const ANDROID = 'var(--success)'
const IOS = 'var(--accent)'
const NEUTRAL = 'var(--text-secondary)'
const INK = 'var(--text-primary)'
const SUB = 'var(--text-secondary)'

const EXAMPLE_URI = 'spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*MSG:Faktura%20123'

export default function SpaydUriPage() {
  const { t } = useLanguage()

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">{t('QR fotoaparátem', 'Camera QR')}</span>
        </>}
        title={t('QR platba fotoaparátem telefonu (SPAYD-URI)', 'QR payment with the phone camera (SPAYD-URI)')}
        subtitle={t(
          'Stejný SPAYD, jen zabalený do URI. Fotoaparát telefonu pak nabídne bankovní aplikaci místo textu a s více bankami výběr z nainstalovaných. Nerozbíjející posun: staré QR fungují dál.',
          'The same SPAYD, wrapped in a URI. The phone camera then offers a banking app instead of text, and with several banks a choice among the installed ones. A non-breaking step: existing QR codes keep working.',
        )}
        icon={<QrCode aria-hidden="true" size={18} style={{ color: ACCENT }} />}
        actions={
          <button type="button" className="docs-print-action" onClick={() => window.print()}>
            <Printer size={14} aria-hidden="true" />
            {t('Exportovat PDF', 'Export PDF')}
          </button>
        }
      />

      {/* Status strip */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
        <Pill color="var(--success-text)" bg="var(--success-bg)" border="var(--success-border)" Icon={CheckCircle} label={t('Čtení obou formátů: v app 0.47.0', 'Reads both forms: in app 0.47.0')} />
        <Pill color="var(--success-text)" bg="var(--success-bg)" border="var(--success-border)" Icon={CheckCircle} label={t('SDK v0.2.0 (Swift + Kotlin)', 'SDK v0.2.0 (Swift + Kotlin)')} />
        <Pill color="var(--warning-text)" bg="var(--warning-bg)" border="var(--warning-border)" Icon={Circle} label={t('Vystavení fotoaparát-QR: pilot, volba uživatele', 'Issuing camera QR: pilot, user choice')} />
        <Pill color="var(--text-primary)" bg="var(--surface-3)" border="var(--border-strong)" Icon={Circle} label={t('Ostatní banky: čeká na ČBA standard', 'Other banks: awaiting a ČBA standard')} />
        <Link href="/docs/qrlesspay" style={{ textDecoration: 'none' }}>
          <Pill color={ACCENT_TEXT} bg="var(--accent-bg)" border="var(--accent-border)" Icon={Hash} label={t('rozšíření QRlessPay', 'QRlessPay extension')} />
        </Link>
      </div>

      {/* Problem */}
      <Section title={t('Problém', 'The problem')}>
        <p style={{ color: SUB, fontSize: 14, lineHeight: 1.6, margin: 0 }}>
          {t(
            'Dnešní platební QR obsahuje text SPD*1.0*ACC:…. Fotoaparát v iOS i Androidu ho zobrazí jako text a nic dalšího neudělá, protože aplikace se dá otevřít jen přes URI se zaregistrovaným schématem. QR platbu proto jde přečíst jen zevnitř bankovní aplikace, zatímco každý jiný QR kód lidé čtou rovnou fotoaparátem.',
            'Today\'s payment QR holds the text SPD*1.0*ACC:…. The camera on iOS and Android shows it as text and does nothing else, because an app can only be opened through a URI with a registered scheme. A payment QR can therefore only be read from inside a banking app, while people scan every other QR code straight from the camera.',
          )}
        </p>
      </Section>

      {/* Format */}
      <Section
        title={t('Formát', 'Format')}
        subtitle={t('SPAYD se nemění ani o bajt. Přibude jen schéma a verze obálky; jen znaky, které URI zakazuje, se kódují (%XX).', 'The SPAYD does not change by a byte. Only a scheme and an envelope version are added; just the characters a URI forbids are percent-encoded.')}
      >
        <div style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 12.5, background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: 10, padding: '12px 14px', overflowX: 'auto', whiteSpace: 'nowrap', color: INK }}>
          <span style={{ color: ACCENT_TEXT, fontWeight: 700 }}>spayd://</span>
          <span style={{ color: 'var(--success-text)', fontWeight: 700 }}>v1/</span>
          <span>{EXAMPLE_URI.replace('spayd://v1/', '')}</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 10, marginTop: 12 }}>
          <Fact title="spayd" body={t('Společné schéma. Registruje ho každá banka; fotoaparát podle něj pozná, že jde o platbu.', 'The common scheme. Every bank registers it; the camera uses it to recognise a payment.')} />
          <Fact title={t('spayd-<kód banky>', 'spayd-<bank code>')} body={t('Relay adresa jedné banky (např. spayd-0800). Slouží k předání platby vybrané aplikaci.', 'One bank\'s relay address (e.g. spayd-0800). Used to hand the payment to the chosen app.')} />
          <Fact title="v1" body={t('Verze obálky. Mění se jen při změně kódování.', 'Envelope version. Changes only if the encoding does.')} />
          <Fact title={t('payload', 'payload')} body={t('Původní SPAYD. Žádný backend, žádný lookup — stejný princip jako tištěný QR.', 'The original SPAYD. No backend, no lookup — the same principle as a printed QR.')} />
        </div>
      </Section>

      {/* Flow */}
      <Section
        title={t('Tok: sken fotoaparátem → výběr banky → potvrzení', 'Flow: camera scan → bank choice → confirmation')}
        subtitle={t('Android ukáže výběr nainstalovaných bankovních aplikací systémově. iOS otevře vždy jen jednu aplikaci, takže výběr nabídne ta aplikace sama a platbu předá dál.', 'Android shows a chooser of installed banking apps itself. iOS always opens a single app, so that app offers the choice and hands the payment on.')}
      >
        <FlowDiagram t={t} />
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
          <Tag>{t('systémový chooser (Android)', 'system chooser (Android)')}</Tag>
          <Tag>{t('relay chooser v aplikaci (iOS)', 'in-app relay chooser (iOS)')}</Tag>
          <Tag>{t('příjemce vždy znovu ověří', 'receiver always re-verifies')}</Tag>
          <Tag>{t('jen předvyplnění + SCA', 'prefill only + SCA')}</Tag>
        </div>
      </Section>

      {/* Rollout */}
      <Section
        title={t('Nerozbíjející zavedení ve třech fázích', 'Non-breaking rollout in three phases')}
        subtitle={t('Obsah QR se změnit musí — fotoaparát nereaguje na nic jiného. Proto nejdřív čtení, pak volitelné vystavení, nakonec změna výchozího formátu.', 'The QR content has to change — the camera reacts to nothing else. So reading comes first, then optional issuing, and only then a new default.')}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12 }}>
          {PHASES.map((p, i) => (
            <div key={i} className="card" style={{ padding: 14, borderLeft: `3px solid ${p.state === 'done' ? ANDROID : p.state === 'now' ? 'var(--warning)' : NEUTRAL}` }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
                <div style={{ fontWeight: 700, fontSize: 13, color: INK }}>{i + 1}. {t(p.titleCs, p.titleEn)}</div>
                <StateBadge state={p.state} t={t} />
              </div>
              <div style={{ fontSize: 12.5, color: SUB, lineHeight: 1.5 }}>{t(p.bodyCs, p.bodyEn)}</div>
            </div>
          ))}
        </div>
        <Callout>
          {t(
            'Jeden QR nese jeden payload, „oba formáty v jednom kódu“ nejde. Proto je volba na obrazovce Můj kód: „Pro bankovní appku“ (výchozí, holý SPAYD, přečte každá banka) a „Pro fotoaparát“ (spayd://). Výchozí formát se přepne až ve fázi 3.',
            'One QR carries one payload; “both formats in one code” is impossible. So the choice sits on the My code screen: “For a banking app” (default, bare SPAYD, every bank reads it) and “For the camera” (spayd://). The default flips only in phase 3.',
          )}
        </Callout>
      </Section>

      {/* Platforms */}
      <Section title={t('Chování platforem', 'Platform behaviour')}>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, minWidth: 640 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: SUB, background: 'var(--surface-2)' }}>
                <th style={th}>{t('Krok', 'Step')}</th>
                <th style={th}>Android</th>
                <th style={th}>iOS</th>
              </tr>
            </thead>
            <tbody>
              {PLATFORM.map((r, i) => (
                <tr key={i} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ ...td, fontWeight: 600, color: INK }}>{t(r.stepCs, r.stepEn)}</td>
                  <td style={{ ...td, color: SUB }}>{t(r.androidCs, r.androidEn)}</td>
                  <td style={{ ...td, color: SUB }}>{t(r.iosCs, r.iosEn)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Callout>
          {t(
            'Cena řešení: relay musí implementovat každá banka. Banka bez něj si na iOS sken „nechá“ pokaždé, když ji systém vybere. Seznam kódů bank a obě schémata proto patří do standardu (rozšíření SPAYD pod ČBA), ne do kódu jedné banky.',
            'The cost: every bank has to implement the relay. A bank without it keeps the scan on iOS whenever the system picks it. The bank-code list and both schemes therefore belong in the standard (a SPAYD extension under ČBA), not in one bank\'s code.',
          )}
        </Callout>
      </Section>

      {/* Security */}
      <Section
        title={t('Bezpečnost', 'Security')}
        subtitle={t('Stejný model jako tištěný QR: obsah je nedůvěryhodný, dokud ho plátce nepotvrdí na obrazovce své banky.', 'Same model as a printed QR: the content is untrusted until the payer confirms it on their bank\'s screen.')}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
          {SECURITY.map((s, i) => (
            <div key={i} className="card" style={{ padding: 14, borderLeft: `3px solid ${ANDROID}` }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 700, fontSize: 13, color: INK, marginBottom: 6 }}>
                <s.Icon size={14} style={{ color: ACCENT }} /> {t(s.titleCs, s.titleEn)}
              </div>
              <div style={{ fontSize: 12.5, color: SUB, lineHeight: 1.5 }}>{t(s.bodyCs, s.bodyEn)}</div>
            </div>
          ))}
        </div>
      </Section>

      {/* Pilot */}
      <Section title={t('Jak to vyzkoušet (pilot)', 'How to try it (pilot)')}>
        <ol style={{ margin: 0, paddingLeft: 18, color: SUB, fontSize: 13, lineHeight: 1.8 }}>
          <li>{t('Telefon A: OpenBank z TestFlightu (0.47.0, build 176+), obrazovka Můj kód, přepnout na „Pro fotoaparát“.', 'Phone A: OpenBank from TestFlight (0.47.0, build 176+), My code screen, switch to “For the camera”.')}</li>
          <li>{t('Telefon B: běžný fotoaparát namířit na QR, ťuknout „Otevřít v OpenBank“. Formulář Poslat se předvyplní.', 'Phone B: point the stock camera at the QR, tap “Open in OpenBank”. The Send form is prefilled.')}</li>
          <li>{t('Výběr banky na iOS: vedle OpenBank nainstalovat ukázkovou aplikaci SDK (registruje spayd-9999). OpenBank pak nabídne „Zaplatit v“.', 'Bank choice on iOS: install the SDK example app next to OpenBank (it registers spayd-9999). OpenBank then offers “Pay with”.')}</li>
          <li>{t('Zatím neměřeno: které aplikace fotoaparátu na Androidu předávají vlastní schéma systému. Zapisovat po aplikaci fotoaparátu, ne po modelu telefonu.', 'Not measured yet: which Android camera apps hand a custom scheme to the system. Record per camera app, not per phone model.')}</li>
        </ol>
      </Section>

      {/* Documents */}
      <Section title={t('Dokumenty a kód', 'Documents and code')}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <a href="https://github.com/JiRaska/qrlesspay-sdk/blob/main/SPAYD-URI.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Specifikace SPAYD-URI', 'SPAYD-URI spec')}</a>
          <a href="https://github.com/JiRaska/qrlesspay-sdk/releases/tag/v0.2.0" target="_blank" rel="noopener noreferrer" style={linkBtn}>SDK v0.2.0</a>
          <a href="https://github.com/JiRaska/qrlesspay-sdk/blob/main/conformance/spayd-uri-vectors.json" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Konformanční vektory', 'Conformance vectors')}</a>
          <Link href="/docs/qrlesspay" style={linkBtn}>QRlessPay</Link>
          <Link href="/docs/customer-app" style={linkBtn}>{t('Customer App dossier', 'Customer App dossier')}</Link>
        </div>
      </Section>
    </div>
  )
}

// ── Flow diagram (inline SVG) ───────────────────────────────────────────────────
function FlowDiagram({ t }: { t: (cs: string, en: string) => string }) {
  const W = 760
  const boxes = (y: number, color: string, bg: string, border: string, items: string[]) =>
    items.map((label, i) => {
      const w = 160, gap = 24, x = 40 + i * (w + gap)
      return (
        <g key={`${y}-${i}`}>
          <rect x={x} y={y} width={w} height={54} rx={10} fill={bg} stroke={border} />
          <text x={x + w / 2} y={y + 24} textAnchor="middle" fontSize={11.5} fontWeight={700} fill={INK}>{label.split('|')[0]}</text>
          <text x={x + w / 2} y={y + 41} textAnchor="middle" fontSize={10.5} fill={SUB}>{label.split('|')[1] ?? ''}</text>
          {i < items.length - 1 && (
            <line x1={x + w + 2} y1={y + 27} x2={x + w + gap - 4} y2={y + 27} stroke={color} strokeWidth={2} markerEnd="url(#su-ah)" />
          )}
        </g>
      )
    })
  return (
    <svg viewBox={`0 0 ${W} 300`} role="img" style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface-2)', borderRadius: 12, border: '1px solid var(--border)' }}>
      <title>{t('Tok skenu SPAYD-URI na Androidu a iOS', 'SPAYD-URI scan flow on Android and iOS')}</title>
      <defs>
        <marker id="su-ah" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto">
          <path d="M0,0 L0,6 L7,3 z" fill="context-stroke" />
        </marker>
      </defs>
      <text x={40} y={34} fontSize={13} fontWeight={700} fill="var(--success-text)">Android</text>
      {boxes(46, ANDROID, 'var(--success-bg)', 'var(--success-border)', [
        t('Fotoaparát|čte spayd:// QR', 'Camera|reads spayd:// QR'),
        t('Systémový výběr|nainstalované banky', 'System chooser|installed banks'),
        t('Vybraná banka|parsuje SPAYD', 'Chosen bank|parses SPAYD'),
        t('Potvrzení + SCA|jako dnes', 'Confirm + SCA|as today'),
      ])}
      <text x={40} y={164} fontSize={13} fontWeight={700} fill={ACCENT_TEXT}>iOS</text>
      {boxes(176, IOS, 'var(--accent-bg)', 'var(--accent-border)', [
        t('Fotoaparát|„Otevřít v …“', 'Camera|“Open in …”'),
        t('Jedna banka|vybere iOS', 'One bank|picked by iOS'),
        t('Relay výběr|canOpenURL, „Zaplatit v“', 'Relay chooser|canOpenURL, “Pay with”'),
        t('Potvrzení + SCA|v cílové bance', 'Confirm + SCA|in the target bank'),
      ])}
      <text x={W / 2} y={276} textAnchor="middle" fontSize={11} fill={SUB}>
        {t('Jediná nainstalovaná banka: výběr se přeskočí a jde se rovnou na potvrzení.', 'Only one bank installed: the choice is skipped and it goes straight to confirmation.')}
      </text>
    </svg>
  )
}

// ── data ────────────────────────────────────────────────────────────────────────
type PhaseState = 'done' | 'now' | 'later'

const PHASES: { titleCs: string; titleEn: string; bodyCs: string; bodyEn: string; state: PhaseState }[] = [
  {
    titleCs: 'Čtení obou formátů', titleEn: 'Read both forms', state: 'done',
    bodyCs: 'Scanner v aplikaci, QRlessPay i sdílený text přijímají SPD*… i spayd://… (SpaydUri.unwrap). Nic se nerozbije, vytištěné QR fungují dál. V OpenBank 0.47.0.',
    bodyEn: 'The in-app scanner, QRlessPay and shared text accept SPD*… and spayd://… (SpaydUri.unwrap). Nothing breaks, printed QR codes keep working. In OpenBank 0.47.0.',
  },
  {
    titleCs: 'Volitelné vystavení', titleEn: 'Optional issuing', state: 'now',
    bodyCs: 'Uživatel volí formát podle toho, kdo bude skenovat. Výchozí zůstává holý SPAYD. Pilot ověřuje chování fotoaparátů a výběr bank.',
    bodyEn: 'The user picks the format by who is about to scan. The default stays the bare SPAYD. The pilot verifies camera behaviour and bank choice.',
  },
  {
    titleCs: 'Nový výchozí formát', titleEn: 'New default', state: 'later',
    bodyCs: 'Až telemetrie ukáže, že staré verze scannerů zmizely, a ostatní banky čtou spayd://. Podmínka: rozšíření SPAYD přijaté ČBA.',
    bodyEn: 'Once telemetry shows old scanner versions are gone and other banks read spayd://. Precondition: the SPAYD extension adopted by ČBA.',
  },
]

const PLATFORM: { stepCs: string; stepEn: string; androidCs: string; androidEn: string; iosCs: string; iosEn: string }[] = [
  { stepCs: 'Registrace', stepEn: 'Registration', androidCs: 'intent-filter na spayd + spayd-<kód>', androidEn: 'intent-filter for spayd + spayd-<code>', iosCs: 'CFBundleURLTypes spayd + spayd-<kód>', iosEn: 'CFBundleURLTypes spayd + spayd-<code>' },
  { stepCs: 'Sken fotoaparátem', stepEn: 'Camera scan', androidCs: 'předá URI systému (záleží na aplikaci fotoaparátu)', androidEn: 'hands the URI to the system (depends on the camera app)', iosCs: 'banner „Otevřít v <aplikace>“', iosEn: 'banner “Open in <app>”' },
  { stepCs: 'Výběr banky', stepEn: 'Bank choice', androidCs: 'systémový chooser nainstalovaných aplikací', androidEn: 'system chooser of installed apps', iosCs: 'žádný systémový; nabídne ho otevřená banka (relay)', iosEn: 'none from the system; the opened bank offers it (relay)' },
  { stepCs: 'Zjištění nainstalovaných bank', stepEn: 'Finding installed banks', androidCs: '<queries> v manifestu + resolveActivity', androidEn: '<queries> in the manifest + resolveActivity', iosCs: 'LSApplicationQueriesSchemes + canOpenURL', iosEn: 'LSApplicationQueriesSchemes + canOpenURL' },
  { stepCs: 'Předání platby', stepEn: 'Hand-off', androidCs: 'stejné bajty na spayd-<kód>', androidEn: 'same bytes on spayd-<code>', iosCs: 'stejné bajty na spayd-<kód>', iosEn: 'same bytes on spayd-<code>' },
]

const SECURITY: { Icon: React.ElementType; titleCs: string; titleEn: string; bodyCs: string; bodyEn: string }[] = [
  { Icon: ShieldCheck, titleCs: 'Jen předvyplnění', titleEn: 'Prefill only', bodyCs: 'URI jde stejnou cestou jako sdílený text: předvyplní formulář Poslat, VoP běží, potvrzení a SCA beze změny.', bodyEn: 'The URI takes the same path as shared text: it prefills the Send form, VoP runs, confirmation and SCA are unchanged.' },
  { Icon: ArrowLeftRight, titleCs: 'Relay předává bajty, ne důvěru', titleEn: 'Relay forwards bytes, not trust', bodyCs: 'Cílová banka SPAYD parsuje znovu. Aplikace s výběrem je jen další nedůvěryhodný zdroj.', bodyEn: 'The target bank parses the SPAYD again. The chooser app is just another untrusted source.' },
  { Icon: ScanLine, titleCs: 'Striktní dekodér', titleEn: 'Strict decoder', bodyCs: 'Odmítá s uvedeným důvodem: nepovolené znaky, řídicí znaky přes escape (%00), vadné kódování, neznámou verzi, chybný kód banky a cokoli, co není SPAYD. Důvody hlídá konformanční korpus v obou SDK.', bodyEn: 'Refuses with a stated reason: forbidden characters, control characters via escapes (%00), bad encoding, unknown version, malformed bank code and anything that is not a SPAYD. The conformance corpus pins the reasons in both SDKs.' },
  { Icon: Smartphone, titleCs: 'Podvržené schéma', titleEn: 'Look-alike scheme', bodyCs: 'spaydx:// nebo spayd-pay:// parser odmítne, a OS nabídne jen zaregistrovaná schémata.', bodyEn: 'spaydx:// or spayd-pay:// is refused by the parser, and the OS only offers registered schemes.' },
  { Icon: Camera, titleCs: 'Bez backendu', titleEn: 'No backend', bodyCs: 'Žádná doména, žádný server, žádný registr. URI se rozbalí na zařízení, stejně jako se čte tištěný QR.', bodyEn: 'No domain, no server, no registry. The URI is unwrapped on the device, just as a printed QR is read.' },
]

// ── small UI helpers ──────────────────────────────────────────────────────────
function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div className="card" style={{ padding: 20, marginBottom: 16 }}>
      <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{title}</h2>
      {subtitle && <p style={{ fontSize: 12.5, color: 'var(--text-secondary)', margin: '4px 0 14px' }}>{subtitle}</p>}
      {!subtitle && <div style={{ height: 12 }} />}
      {children}
    </div>
  )
}

function Pill({ color, bg, border, Icon, label }: { color: string; bg: string; border: string; Icon: React.ElementType; label: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, fontWeight: 700, color, background: bg, border: `1px solid ${border}`, padding: '3px 10px', borderRadius: 20 }}>
      <Icon size={12} /> {label}
    </span>
  )
}

function Tag({ children }: { children: React.ReactNode }) {
  return <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)', background: 'var(--surface-2)', border: '1px solid var(--border)', padding: '3px 9px', borderRadius: 20 }}>{children}</span>
}

function Fact({ title, body }: { title: string; body: string }) {
  return (
    <div style={{ padding: 12, borderRadius: 10, background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
      <div style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 12, fontWeight: 700, color: ACCENT_TEXT, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 12, color: SUB, lineHeight: 1.5 }}>{body}</div>
    </div>
  )
}

function StateBadge({ state, t }: { state: PhaseState; t: (cs: string, en: string) => string }) {
  const map = {
    done: { label: t('hotovo', 'done'), color: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)' },
    now: { label: t('teď', 'now'), color: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)' },
    later: { label: t('později', 'later'), color: 'var(--text-primary)', bg: 'var(--surface-3)', border: 'var(--border-strong)' },
  }[state]
  return (
    <span style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: map.color, background: map.bg, border: `1px solid ${map.border}`, padding: '1px 7px', borderRadius: 20 }}>
      {map.label}
    </span>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="card" style={{ marginTop: 12, padding: 14, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', display: 'flex', gap: 10 }}>
      <Info size={16} style={{ color: ACCENT, flexShrink: 0, marginTop: 1 }} />
      <div style={{ fontSize: 13, color: INK, lineHeight: 1.55 }}>{children}</div>
    </div>
  )
}

const th: React.CSSProperties = { padding: '10px 14px', fontWeight: 700, fontSize: 12 }
const td: React.CSSProperties = { padding: '10px 14px', verticalAlign: 'top' }
const linkBtn: React.CSSProperties = { fontSize: 12.5, fontWeight: 600, color: ACCENT_TEXT, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', padding: '6px 12px', borderRadius: 8, textDecoration: 'none' }
