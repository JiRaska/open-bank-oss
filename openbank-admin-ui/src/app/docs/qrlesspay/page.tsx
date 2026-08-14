// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
'use client'

import Link from 'next/link'
import { Bluetooth, ShieldCheck, Radio, KeyRound, ScanLine, Info, Circle, CheckCircle, ArrowLeftRight, EyeOff, Hash } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const ACCENT = '#6366f1'
const RECV = '#6366f1' // payee / bank A
const PAYER = '#10b981' // payer / bank B
const INK = 'var(--text-primary)'
const SUB = 'var(--text-secondary)'

export default function QrlessPayPage() {
  const { t } = useLanguage()

  return (
    <div>
      <div className="page-header">
        <div className="breadcrumb">
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">QRlessPay</span>
        </div>
        <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Bluetooth size={18} style={{ color: ACCENT }} />
          {t('QRlessPay — platba poblíž bez QR', 'QRlessPay — QR-less proximity pay')}
        </h1>
        <p className="page-subtitle">
          {t(
            'Otevřený BLE profil pro platbu telefon-telefon bez skenování. Banka-agnostický, backend volitelný — cíl je standard (ČBA/EPC).',
            'Open BLE phone-to-phone profile for scan-less pay. Bank-agnostic, backend-optional — aimed at becoming a standard (ČBA/EPC).',
          )}
        </p>
      </div>

      {/* Status strip */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
        <Pill color="#059669" bg="#ecfdf5" border="#6ee7b7" Icon={CheckCircle} label={t('QR SPAYD: živé v app', 'QR SPAYD: live in app')} />
        <Pill color="#d97706" bg="#fffbeb" border="#fcd34d" Icon={Circle} label={t('BLE proximity: v app, spící (čeká na security gates)', 'BLE proximity: in app, dormant (awaiting security gates)')} />
        <Pill color="#94a3b8" bg="#f8fafc" border="#cbd5e1" Icon={Radio} label={t('UWB: volitelné zesílení', 'UWB: optional enhancement')} />
        <Link href="/docs/adr/0095-qrlesspay-ble-proximity-spayd-payments" style={{ textDecoration: 'none' }}>
          <Pill color={ACCENT} bg="var(--accent-bg)" border="var(--accent-border)" Icon={Hash} label="ADR-0095" />
        </Link>
        <Pill color="#7c3aed" bg="#f5f3ff" border="#ddd6fe" Icon={ShieldCheck} label={t('money-path', 'money-path')} />
      </div>

      {/* What it is */}
      <Section title={t('Co to je', 'What it is')}>
        <p style={{ color: SUB, fontSize: 14, lineHeight: 1.6, margin: 0 }}>
          {t(
            'Příjemce s otevřenou obrazovkou „Přijmout poblíž“ vysílá přes Bluetooth LE čitelný beacon (křestní jméno + krátké session-id). Plátce ho vidí jako dlaždici v seznamu „poblíž“, klepne na ni, jeho telefon stáhne přes krátké GATT spojení podepsaný SPAYD a otevře předvyplněný návrh platby. Žádná kamera, žádné sdílení čísla účtu po vzduchu. Zúčtování jede po existujícím IBAN/okamžitém railu, který už je mezibankovní.',
            'A payee on the “Receive nearby” screen broadcasts a readable Bluetooth-LE beacon (first name + a short session-id). The payer sees it as a tile in a “nearby” list, taps it, their phone pulls a signed SPAYD over a short GATT connection and opens a pre-filled payment proposal. No camera, no account number on the air. Settlement rides the existing IBAN / instant-payment rail, which is already interbank.',
          )}
        </p>
      </Section>

      {/* Sequence */}
      <Section
        title={t('Sekvence komunikace — banka A (iOS) → banka B (Android)', 'Communication sequence — Bank A (iOS) → Bank B (Android)')}
        subtitle={t('Dvě fáze: čitelný advert (discovery) a podepsaný přenos přes GATT. Funguje napříč platformami i bankami.', 'Two phases: a readable discovery advert and a signed GATT transfer. Works across platforms and banks.')}
      >
        <SequenceDiagram t={t} />
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
          <Tag>{t('iOS ↔ Android', 'iOS ↔ Android')}</Tag>
          <Tag>{t('banka A ↔ banka B', 'Bank A ↔ Bank B')}</Tag>
          <Tag>{t('connectionless advert + GATT', 'connectionless advert + GATT')}</Tag>
          <Tag>{t('Ed25519 podpis', 'Ed25519 signature')}</Tag>
        </div>
      </Section>

      {/* Security layers */}
      <Section
        title={t('Bezpečnostní vrstvy (obrana do hloubky)', 'Security layers (defense in depth)')}
        subtitle={t('Žádné peníze se nehnou bez potvrzení plátce. Některé vrstvy jsou povinné, jiné volitelné podle HW a banky.', 'No money moves without payer confirmation. Some layers are required, others optional depending on hardware and bank.')}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12 }}>
          {LAYERS.map((l, i) => (
            <div key={i} className="card" style={{ padding: 14, borderLeft: `3px solid ${l.req ? PAYER : '#94a3b8'}` }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 700, fontSize: 13, color: INK }}>
                  <l.Icon size={14} style={{ color: ACCENT }} /> {t(l.threatCs, l.threatEn)}
                </div>
                <span style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: l.req ? '#059669' : '#64748b', background: l.req ? '#ecfdf5' : '#f1f5f9', border: `1px solid ${l.req ? '#6ee7b7' : '#cbd5e1'}`, padding: '1px 7px', borderRadius: 20 }}>
                  {l.req ? t('povinné', 'required') : t('volitelné', 'optional')}
                </span>
              </div>
              <div style={{ fontSize: 12.5, color: SUB, lineHeight: 1.5 }}>{t(l.mitCs, l.mitEn)}</div>
            </div>
          ))}
        </div>
      </Section>

      {/* Comparison vs QR */}
      <Section
        title={t('Srovnání bezpečnosti s optickým QR', 'Security comparison vs optical QR scan')}
        subtitle={t('Identita je remíza (obojí stojí na potvrzení). QR vyhrává fyzické zacílení, QRlessPay soukromí a UX.', 'Identity is a tie (both rest on confirmation). QR wins physical targeting; QRlessPay wins privacy and UX.')}
      >
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, minWidth: 640 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: SUB, background: 'var(--surface-2)' }}>
                <th style={th}>{t('Osa', 'Axis')}</th>
                <th style={th}>{t('Optický QR', 'Optical QR')}</th>
                <th style={th}>QRlessPay</th>
                <th style={th}>{t('Verdikt', 'Verdict')}</th>
              </tr>
            </thead>
            <tbody>
              {COMPARE.map((r, i) => (
                <tr key={i} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ ...td, fontWeight: 600, color: INK }}>{t(r.axisCs, r.axisEn)}</td>
                  <td style={{ ...td, color: SUB }}>{t(r.qrCs, r.qrEn)}</td>
                  <td style={{ ...td, color: SUB }}>{t(r.blCs, r.blEn)}</td>
                  <td style={td}>
                    <span style={{ fontSize: 11, fontWeight: 700, color: r.win === 'qr' ? '#d97706' : r.win === 'bl' ? '#059669' : '#64748b' }}>
                      {r.win === 'qr' ? t('QR', 'QR') : r.win === 'bl' ? 'QRlessPay' : t('remíza', 'tie')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card" style={{ marginTop: 12, padding: 14, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', display: 'flex', gap: 10 }}>
          <Info size={16} style={{ color: ACCENT, flexShrink: 0, marginTop: 1 }} />
          <div style={{ fontSize: 13, color: INK, lineHeight: 1.55 }}>
            {t(
              'Závěr: QRlessPay není automaticky bezpečnější než QR — a v ose identity ani být nemůže, protože udělal stejnou volbu jako SPAYD: ověřitelnost ze samotných bajtů, žádný registr, žádný lookup. Proto je bank atestace mimo protokol (potřebovala by trust anchor). Inovace je UX (bez kamery, výběr ze seznamu, předvyplněno) a soukromí (IBAN není ve vzduchu). Zbývající vrstvy: VOP (až pro CZ), silná proximita (UWB), ověřovací kód a varování na zařízení plátce. Pozicujeme ho jako „stejně bezpečné jako QR, soukromější, příjemnější“.',
              'Bottom line: QRlessPay is not automatically safer than QR — and on the identity axis it cannot be, because it made the same choice SPAYD did: verifiable from the bytes alone, no registry, no lookup. That is why bank attestation is out of the protocol (it would need a trust anchor). The innovation is UX (no camera, pick-from-list, prefilled) and privacy (no IBAN on air). The layers that remain: VOP (once available for CZ), strong proximity (UWB), a verification code, and payer-device warnings. We position it as “as safe as QR, more private, nicer to use”.',
            )}
          </div>
        </div>
      </Section>

      {/* Portability note */}
      <Section title={t('Přenositelnost a omezení', 'Portability & limits')}>
        <ul style={{ margin: 0, paddingLeft: 18, color: SUB, fontSize: 13, lineHeight: 1.7 }}>
          <li>{t('Skenování (role plátce) jede prakticky všude; role příjemce (BLE advertising) závisí na HW — některé levnější Androidy ji nemají.', 'Scanning (payer role) works almost everywhere; the payee role (BLE advertising) is hardware-dependent — some cheaper Androids lack it.')}</li>
          <li>{t('UWB je menšinové (iPhone 11+, jen vlajkové Androidy) → striktně volitelné, baseline je RSSI + potvrzení.', 'UWB is a minority (iPhone 11+, flagship Androids only) → strictly optional; the baseline is RSSI + confirmation.')}</li>
          <li>{t('VOP (shoda jméno↔IBAN) zatím EU jen pro euro/SEPA; CZ domácí platby zatím nepokryté → volitelné + TODO/watch.', 'VOP (name↔IBAN match) is EU-mandated for euro/SEPA only; CZ domestic is not yet covered → optional + TODO/watch.')}</li>
        </ul>
      </Section>

      {/* SDK / Third-party bank integration */}
      <Section
        title={t('SDK a integrace pro banky třetích stran', 'SDK & third-party bank integration')}
        subtitle={t('QRlessPay je otevřený protokol — každá banka může implementovat roli plátce i příjemce nezávisle na OpenBank. SDK plánujeme publikovat pod Apache-2.0.', 'QRlessPay is an open protocol — any bank can implement both payer and payee roles independently of OpenBank. We plan to publish the SDK under Apache-2.0.')}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(295px, 1fr))', gap: 12, marginBottom: 14 }}>
          {[
            {
              titleCs: 'Minimální integrace — příjemce', titleEn: 'Minimal integration — payee',
              color: RECV,
              stepsCs: ['Generuj Ed25519 klíčový pár', 'Sestav SPAYD bundle (IBAN + částka + ref)', 'Podpiš bundle + nonce + exp + session-id', 'Spusť BLE advertising s hash(pubkey) v UUID', 'Naslouchej GATT read + servi podepsaný bundle'],
              stepsEn: ['Generate Ed25519 key pair', 'Build SPAYD bundle (IBAN + amount + ref)', 'Sign bundle + nonce + exp + session-id', 'Start BLE advertising with hash(pubkey) in UUID', 'Listen for GATT read + serve signed bundle'],
            },
            {
              titleCs: 'Minimální integrace — plátce', titleEn: 'Minimal integration — payer',
              color: PAYER,
              stepsCs: ['Skenuj BLE adverty (filtr UUID)', 'GATT connect + read na session-id', 'Ověř Ed25519 podpis + nonce + exp', 'Zobraz předvyplněný platební návrh uživateli', 'Odešli platbu přes IBAN rail (SEPA/instant)'],
              stepsEn: ['Scan BLE adverts (UUID filter)', 'GATT connect + read for session-id', 'Verify Ed25519 signature + nonce + exp', 'Show pre-filled payment proposal to user', 'Send payment over IBAN rail (SEPA/instant)'],
            },
          ].map((card, i) => (
            <div key={i} className="card" style={{ padding: 14, borderLeft: `3px solid ${card.color}` }}>
              <div style={{ fontWeight: 700, fontSize: 13, color: INK, marginBottom: 8 }}>{t(card.titleCs, card.titleEn)}</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                {card.stepsCs.map((s, j) => (
                  <div key={j} style={{ display: 'flex', gap: 7, fontSize: 12, color: SUB }}>
                    <span style={{ color: card.color, fontWeight: 700, flexShrink: 0 }}>{j + 1}.</span>
                    {t(s, card.stepsEn[j])}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div className="card" style={{ padding: 14, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ fontWeight: 700, fontSize: 13, color: INK }}>
            {t('Plánovaná publikace SDK', 'Planned SDK publication')}
          </div>
          <div style={{ fontSize: 12.5, color: SUB, lineHeight: 1.6 }}>
            {t(
              'Jádro protokolu je Kotlin Multiplatform (jedna auditovaná implementace ověřování), nad ním tenké idiomatické vazby: Android (Kotlin, Maven), iOS (Swift, SPM), React Native (TypeScript, npm) a Flutter (Dart, pub.dev) — obě mobilní cross-platform vazby delegují na stejná nativní jádra. Web není podporován (Web Bluetooth neumí roli příjemce). Banky doplní pouze svůj platební backend (IBAN rail) a vlastní potvrzovací UI + SCA. Cíl: standard kompatibilní s ČBA a EPC pro mezibankovní proximity platby bez kódu.',
              'The protocol core is Kotlin Multiplatform (one audited verification implementation) with thin idiomatic bindings on top: Android (Kotlin, Maven), iOS (Swift, SPM), React Native (TypeScript, npm) and Flutter (Dart, pub.dev) — both cross-platform bindings delegate to the same native cores. Web is unsupported (Web Bluetooth cannot do the payee role). Banks plug in only their own payment backend (IBAN rail) plus their own confirmation UI + SCA. Goal: a ČBA and EPC-compatible standard for interbank proximity payments without a QR code.',
            )}
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <Tag>KMP / Apache-2.0</Tag>
            <Tag>{t('Swift · Kotlin · TypeScript · Dart', 'Swift · Kotlin · TypeScript · Dart')}</Tag>
            <Tag>ČBA / EPC</Tag>
            <Tag>{t('Otevřený protokol', 'Open protocol')}</Tag>
            <Tag>{t('Mezibankovní', 'Interbank')}</Tag>
            <a href="https://github.com/JiRaska/open-bank-oss/blob/main/docs/specs/qrlesspay-sdk.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Návrh SDK (spec)', 'SDK proposal (spec)')}</a>
          </div>
        </div>
      </Section>

      {/* References */}
      <Section title={t('Dokumenty', 'Documents')}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Link href="/docs/adr/0095-qrlesspay-ble-proximity-spayd-payments" style={linkBtn}>ADR-0095 — {t('rozhodnutí', 'decision')}</Link>
          <a href="https://github.com/JiRaska/open-bank-oss/blob/main/docs/specs/qrlesspay-v1.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Wire spec v1', 'Wire spec v1')}</a>
          <a href="https://github.com/JiRaska/open-bank-oss/blob/main/docs/threat-models/qrlesspay.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Threat model', 'Threat model')}</a>
          <Link href="/docs/customer-app" style={linkBtn}>{t('Customer App dossier', 'Customer App dossier')}</Link>
        </div>
      </Section>
    </div>
  )
}

// ── Sequence diagram (inline SVG) ───────────────────────────────────────────────
function SequenceDiagram({ t }: { t: (cs: string, en: string) => string }) {
  const RX = 210, PX = 590, MID = (RX + PX) / 2 // lane centers
  // Direction is encoded per step: ① and ③ go receiver→payer, ② (the GATT
  // connect/read request) goes payer→receiver. markerEnd sits on x2; orient="auto"
  // flips the head, and fill="context-stroke" makes it match the line colour.
  const steps: { y: number; x1: number; x2: number; color: string; dashed: boolean; label: string }[] = [
    { y: 158, x1: RX + 6, x2: PX - 6, color: RECV, dashed: false, label: t('① advert: jméno + session-id (bez IBANu)', '① advert: first name + session-id (no IBAN)') },
    { y: 212, x1: PX - 6, x2: RX + 6, color: PAYER, dashed: false, label: t('② GATT připojení + read (požadavek)', '② GATT connect + read (request)') },
    { y: 266, x1: RX + 6, x2: PX - 6, color: RECV, dashed: true, label: t('③ podepsaný SPAYD balík (odpověď)', '③ signed SPAYD bundle (response)') },
  ]
  return (
    <svg viewBox="0 0 760 462" role="img" style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface-2)', borderRadius: 12, border: '1px solid var(--border)' }}>
      <title>{t('Sekvence QRlessPay handshaku', 'QRlessPay handshake sequence')}</title>
      <defs>
        <marker id="qp-ah" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto">
          <path d="M0,0 L0,6 L7,3 z" fill="context-stroke" />
        </marker>
      </defs>
      {/* lifelines (visible in both themes) */}
      <line x1={RX} y1={86} x2={RX} y2={452} stroke="#94a3b8" strokeWidth={1.5} strokeDasharray="3 4" opacity={0.7} />
      <line x1={PX} y1={86} x2={PX} y2={452} stroke="#94a3b8" strokeWidth={1.5} strokeDasharray="3 4" opacity={0.7} />
      {/* activation bars */}
      <rect x={RX - 5} y={146} width={10} height={26} rx={3} fill={RECV} opacity={0.28} />
      <rect x={RX - 5} y={254} width={10} height={26} rx={3} fill={RECV} opacity={0.28} />
      <rect x={PX - 5} y={200} width={10} height={248} rx={3} fill={PAYER} opacity={0.22} />
      {/* actor headers */}
      <g>
        <rect x={RX - 130} y={38} width={260} height={48} rx={12} fill="var(--accent-bg)" stroke="var(--accent-border)" />
        <text x={RX} y={62} textAnchor="middle" fontSize={13.5} fontWeight={700} fill={RECV}>{t('Příjemce · banka A', 'Payee · Bank A')}</text>
        <text x={RX} y={78} textAnchor="middle" fontSize={11} fill={SUB}>{t('iOS · advert + GATT server', 'iOS · advert + GATT server')}</text>
      </g>
      <g>
        <rect x={PX - 130} y={38} width={260} height={48} rx={12} fill="#ecfdf5" stroke="#6ee7b7" />
        <text x={PX} y={62} textAnchor="middle" fontSize={13.5} fontWeight={700} fill={PAYER}>{t('Plátce · banka B', 'Payer · Bank B')}</text>
        <text x={PX} y={78} textAnchor="middle" fontSize={11} fill={SUB}>{t('Android · scan + GATT klient', 'Android · scan + GATT client')}</text>
      </g>
      {/* messages */}
      {steps.map((s, i) => (
        <g key={i}>
          <text x={MID} y={s.y - 9} textAnchor="middle" fontSize={12} fill="var(--text-primary)">{s.label}</text>
          <line x1={s.x1} y1={s.y} x2={s.x2} y2={s.y} stroke={s.color} strokeWidth={2} strokeDasharray={s.dashed ? '6 4' : undefined} markerEnd="url(#qp-ah)" />
        </g>
      ))}
      {/* ④ verify (payer self-note) */}
      <rect x={PX - 150} y={300} width={300} height={64} rx={10} fill="var(--surface)" stroke="var(--border)" />
      <text x={PX} y={322} textAnchor="middle" fontSize={12.5} fontWeight={700} fill="var(--text-primary)">{t('④ Ověření plátcem', '④ Payer verification')}</text>
      <text x={PX} y={340} textAnchor="middle" fontSize={11} fill={SUB}>{t('podpis · blízkost (RSSI/UWB)', 'signature · proximity (RSSI/UWB)')}</text>
      <text x={PX} y={355} textAnchor="middle" fontSize={11} fill={SUB}>{t('VOP jméno↔IBAN (volitelně)', 'VOP name↔IBAN (optional)')}</text>
      {/* ⑤ pay (payer self-note) */}
      <rect x={PX - 150} y={378} width={300} height={56} rx={10} fill="#ecfdf5" stroke="#6ee7b7" />
      <text x={PX} y={400} textAnchor="middle" fontSize={12.5} fontWeight={700} fill={PAYER}>{t('⑤ Návrh platby → potvrzení', '⑤ Payment proposal → confirm')}</text>
      <text x={PX} y={418} textAnchor="middle" fontSize={11} fill={SUB}>{t('úhrada po IBAN / okamžitém railu', 'settled over the IBAN / instant rail')}</text>
    </svg>
  )
}

// ── data ────────────────────────────────────────────────────────────────────────
const LAYERS: { Icon: React.ElementType; req: boolean; threatCs: string; threatEn: string; mitCs: string; mitEn: string }[] = [
  { Icon: KeyRound, req: true, threatCs: 'Integrita + vazba advert↔balík', threatEn: 'Integrity + advert↔bundle binding', mitCs: 'Ed25519 podpis nad SPAYD+nonce+exp+sid; hash pubkey v advertu sváže advert s GATT balíkem (nelze podstrčit).', mitEn: 'Ed25519 signature over SPAYD+nonce+exp+sid; the advert carries a pubkey hash binding it to the GATT bundle (no payload swap).' },
  { Icon: Radio, req: true, threatCs: 'Replay / odposlech', threatEn: 'Replay / eavesdrop', mitCs: 'nonce + exp ≤ 90 s + jednorázové session-id; zachycený balík nelze přehrát.', mitEn: 'nonce + exp ≤ 90 s + single-use session-id; a captured bundle cannot be replayed.' },
  { Icon: ArrowLeftRight, req: true, threatCs: 'Relay (vzdálený podvod)', threatEn: 'Relay (distance fraud)', mitCs: 'Baseline RSSI gate („přilož telefony“); UWB / BT6 secure ranging jako volitelné zesílení tam, kde je HW.', mitEn: 'Baseline RSSI gate (“hold phones close”); UWB / BT6 secure ranging as optional enhancement where hardware exists.' },
  { Icon: ScanLine, req: true, threatCs: 'Záměna IBANu / identita', threatEn: 'IBAN substitution / identity', mitCs: 'Povinné potvrzení plátce (jméno + maskovaný IBAN). Volitelně VOP (CZ zatím ne). Bank atestace je mimo protokol — vyžadovala by trust anchor, tedy mezibankovní koordinaci.', mitEn: 'Mandatory payer confirmation (name + masked IBAN). Optionally VOP (not CZ yet). Bank attestation is out of the protocol — it would need a trust anchor, i.e. interbank coordination.' },
  { Icon: ArrowLeftRight, req: false, threatCs: 'Dvojitá platba / dva stejní odesílatelé', threatEn: 'Duplicate payment / two identical senders', mitCs: 'Varování na zařízení plátce: „tohle jsi zaplatil před chvílí“ a upozornění, když dvě dlaždice nesou stejné jméno (maskovaný IBAN už při výběru). Bez serveru.', mitEn: 'Payer-device warnings: “you paid this a moment ago”, and an alert when two tiles share a display name (masked IBAN shown at the point of choice). No server involved.' },
  { Icon: EyeOff, req: true, threatCs: 'Soukromí', threatEn: 'Privacy', mitCs: 'Po vzduchu jen křestní jméno + efemérní id; IBAN jen přes GATT read, který plátce aktivně vyvolá; rotující id + privacy MAC.', mitEn: 'Only first name + ephemeral id on air; IBAN only via the payer-initiated GATT read; rotating id + privacy MAC.' },
  { Icon: ShieldCheck, req: false, threatCs: 'Vysoká částka / MITM', threatEn: 'High value / MITM', mitCs: 'Volitelný SAS — 4-místný kód z efemérního DH, lidé ho porovnají; defeats MITM bez PKI.', mitEn: 'Optional SAS — a 4-digit code from an ephemeral DH that the humans compare; defeats MITM without PKI.' },
]

const COMPARE: { axisCs: string; axisEn: string; qrCs: string; qrEn: string; blCs: string; blEn: string; win: 'qr' | 'bl' | 'tie' }[] = [
  { axisCs: 'Identita', axisEn: 'Identity', qrCs: 'nepodepsáno, plátce potvrdí', qrEn: 'unsigned, payer confirms', blCs: 'podepsaný payload, ale klíč ≠ identita; plátce potvrdí', blEn: 'signed payload, but key ≠ identity; payer confirms', win: 'tie' },
  { axisCs: 'Fyzické zacílení', axisEn: 'Physical targeting', qrCs: 'silné — míříš na kód, který vidíš', qrEn: 'strong — you aim at a code you can see', blCs: 'slabší — klepneš na jméno, RSSI jde podvrhnout', blEn: 'weaker — you tap a name; RSSI is spoofable', win: 'qr' },
  { axisCs: 'Tampering za letu', axisEn: 'Tampering in transit', qrCs: 'n/a', qrEn: 'n/a', blCs: 'bráněno (Ed25519 + binding)', blEn: 'prevented (Ed25519 + binding)', win: 'bl' },
  { axisCs: 'Klasický útok', axisEn: 'Classic attack', qrCs: 'přelepení QR nálepky', qrEn: 'sticker-swap', blCs: 'impersonace dlaždice (stejné jméno + svůj IBAN)', blEn: 'tile impersonation (same name + own IBAN)', win: 'tie' },
  { axisCs: 'Soukromí účtu', axisEn: 'Account privacy', qrCs: 'IBAN+jméno viditelné na kódu', qrEn: 'IBAN+name visible on the code', blCs: 'na vzduchu jen křestní jméno', blEn: 'only first name on air', win: 'bl' },
  { axisCs: 'UX', axisEn: 'UX', qrCs: 'mířit kamerou, hledat kód', qrEn: 'aim camera, find code', blCs: 'klepnout na jméno, předvyplněno', blEn: 'tap a name, prefilled', win: 'bl' },
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

const th: React.CSSProperties = { padding: '10px 14px', fontWeight: 700, fontSize: 12 }
const td: React.CSSProperties = { padding: '10px 14px', verticalAlign: 'top' }
const linkBtn: React.CSSProperties = { fontSize: 12.5, fontWeight: 600, color: ACCENT, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', padding: '6px 12px', borderRadius: 8, textDecoration: 'none' }
