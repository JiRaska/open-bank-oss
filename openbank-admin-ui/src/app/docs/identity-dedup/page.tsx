// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Fingerprint, ShieldCheck, GitMerge, KeyRound, Layers, AlertTriangle, Lock, ArrowRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'

// Status pill mirrored from the docs status vocabulary (live / partial / planned).
function Status({ kind, t }: { kind: 'live' | 'partial' | 'planned'; t: (cs: string, en: string) => string }) {
  const map = {
    live: { bg: '#16a34a15', fg: '#16a34a', br: '#16a34a30', label: t('ŽIVÉ', 'LIVE') },
    partial: { bg: '#d9770615', fg: '#d97706', br: '#d9770630', label: t('ČÁSTEČNÉ', 'PARTIAL') },
    planned: { bg: '#64748b15', fg: '#64748b', br: '#64748b30', label: t('PLÁNOVÁNO', 'PLANNED') },
  }[kind]
  return (
    <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', background: map.bg, color: map.fg, borderRadius: '20px', border: `1px solid ${map.br}`, whiteSpace: 'nowrap' }}>
      {map.label}
    </span>
  )
}

export default function IdentityDedupPage() {
  const { t } = useLanguage()

  const principles: { icon: React.ReactNode; color: string; title: [string, string]; body: [string, string] }[] = [
    {
      icon: <KeyRound size={18} />, color: '#6366f1',
      title: ['Jediná autorita identity (golden record)', 'Single identity authority (golden record)'],
      body: [
        'pid-service je jediný zdroj pravdy o tom, kdo je kdo. Jeden živý člověk = jedna party. Ostatní služby (party, účty, platby) drží jen referenci partyId, nikdy vlastní rozhodnutí o identitě.',
        'pid-service is the single source of truth for who is who. One living person = one party. Every other service (party, accounts, payments) holds only a partyId reference, never its own identity verdict.',
      ],
    },
    {
      icon: <Lock size={18} />, color: '#16a34a',
      title: ['Rodné číslo nikdy neopustí pid', 'The national ID never leaves pid'],
      body: [
        'Plaintext rodného čísla se v pid zpracuje v rámci jednoho requestu a zahodí — nikdy se neukládá ani neloguje. Do DB jde jen klíčovaný blind index HMAC-SHA256(pepper, RČ). Pepper žije v OpenBao, ne v databázi.',
        'The plaintext national ID (RČ) is processed within a single request and discarded — never stored, never logged. Only the keyed blind index HMAC-SHA256(pepper, RČ) is persisted. The pepper lives in OpenBao, never in the database.',
      ],
    },
    {
      icon: <GitMerge size={18} />, color: '#2563eb',
      title: ['Auto-merge jen deterministicky', 'Auto-merge only on deterministic keys'],
      body: [
        'Sloučení dvou identit proběhne automaticky jen při shodě tvrdého klíče (blind index RČ, EUDI PID id). Pravděpodobnostní shoda NIKDY nesloučí sama — je jen dalším zdrojem kandidátů do fronty ke schválení.',
        'Two identities are merged automatically only on a hard-key match (RČ blind index, EUDI PID id). A probabilistic match NEVER auto-merges — it is only an additional candidate source feeding the review queue.',
      ],
    },
    {
      icon: <ShieldCheck size={18} />, color: '#0891b2',
      title: ['Neutrální odpověď klientovi', 'Neutral response to the client'],
      body: [
        'Klient se nikdy nedozví „tato osoba už u nás je". Onboarding buď tiše pokračuje (reuse / create), nebo vrátí neutrální „čeká na ověření". Existence cizí identity se nedá vytěžit přes onboarding.',
        'The client never learns "this person already banks here". Onboarding either silently proceeds (reuse / create) or returns a neutral "pending verification". The existence of another identity cannot be probed through onboarding.',
      ],
    },
    {
      icon: <AlertTriangle size={18} />, color: '#dc2626',
      title: ['Ambiguita → čtyři oči, ne hádání', 'Ambiguity → four-eyes, not guessing'],
      body: [
        'Kolize blind indexu s rozdílnými atributy nebo pravděpodobnostní shoda v šedé zóně se nikdy neuhodne — routuje se na manuální verifikaci (čtyři oči). Raději nechat člověka rozhodnout než špatně sloučit.',
        'A blind-index collision with divergent attributes, or a gray-zone probabilistic match, is never guessed — it routes to manual verification (four-eyes). Better a human decides than a wrong merge.',
      ],
    },
    {
      icon: <Fingerprint size={18} />, color: '#7c3aed',
      title: ['EUDI-native, ne doc-scan první', 'EUDI-native, not doc-scan first'],
      body: [
        'Cílový model (ADR-0094): pid = EUDI Person Identification Data hub. eIDAS 2.0 peněženka (OpenID4VP/VCI) dává deterministický PID identifikátor a selektivní disclosure — onboarding na úrovni High bez skenu dokladu.',
        'The target model (ADR-0094): pid = EUDI Person Identification Data hub. An eIDAS 2.0 wallet (OpenID4VP/VCI) yields a deterministic PID identifier and selective disclosure — LoA-High onboarding without a document scan.',
      ],
    },
  ]

  // Three-tier resolution ladder.
  const tiers: { tier: string; color: string; signal: [string, string]; technique: [string, string]; merge: [string, string]; status: 'live' | 'partial' | 'planned' }[] = [
    {
      tier: '1', color: '#16a34a',
      signal: ['Rodné číslo (RČ)', 'National ID (RČ)'],
      technique: ['Blind index — HMAC-SHA256(pepper, kanonické RČ). Rovnost indexů = stejná osoba.', 'Blind index — HMAC-SHA256(pepper, canonical RČ). Index equality = same person.'],
      merge: ['Deterministicky reuse', 'Deterministic reuse'],
      status: 'live',
    },
    {
      tier: '2', color: '#2563eb',
      signal: ['Jméno + datum narození', 'Name + date of birth'],
      technique: ['Match-key — normalizované (diakritika, case, pořadí) jméno+datum. Přesná shoda klíče.', 'Match-key — normalized (diacritics, case, order) name+birthdate. Exact key match.'],
      merge: ['Reuse, jinak kandidát', 'Reuse, else candidate'],
      status: 'live',
    },
    {
      tier: "2'", color: '#d97706',
      signal: ['Fuzzy atributy', 'Fuzzy attributes'],
      technique: ['Probabilistický record linkage (Splink / Fellegi-Sunter). Skóre m/u, šedá zóna vs práh.', 'Probabilistic record linkage (Splink / Fellegi-Sunter). m/u scores, gray-zone vs threshold.'],
      merge: ['Jen kandidát → čtyři oči', 'Candidate only → four-eyes'],
      status: 'planned',
    },
    {
      tier: '0', color: '#7c3aed',
      signal: ['EUDI PID id / BankID', 'EUDI PID id / BankID'],
      technique: ['Kryptograficky podepsaný identifikátor z peněženky (eIDAS High). Nejtvrdší klíč.', 'Cryptographically signed wallet identifier (eIDAS High). The hardest key.'],
      merge: ['Deterministicky reuse', 'Deterministic reuse'],
      status: 'planned',
    },
  ]

  const ink = 'var(--text-primary)'
  const sub = 'var(--text-secondary)'

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Identita a deduplikace', 'Identity & Deduplication')}</span>
          </>}
        title={t('Identita a deduplikace', 'Identity & Deduplication')}
        subtitle={t(
              'Jak je moderně postavená jednotná identita klienta: principy, privacy-preserving deduplikace, tříúrovňový resolver a konkrétní ukázka (ADR-0072, ADR-0094)',
              'How unified customer identity is built the modern way: principles, privacy-preserving deduplication, a three-tier resolver and a worked example (ADR-0072, ADR-0094)',
            )}
        icon={<Fingerprint aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<PrintDocumentButton />}
      />

      {/* ---- TL;DR banner ---- */}
      <div className="card" style={{ padding: '18px 22px', marginBottom: '20px', borderLeft: '3px solid var(--accent)', display: 'flex', gap: '14px', alignItems: 'flex-start' }}>
        <Fingerprint size={22} style={{ color: 'var(--accent)', flexShrink: 0, marginTop: '2px' }} />
        <div style={{ fontSize: '13.5px', color: ink, lineHeight: 1.6 }}>
          {t(
            'Cíl: jeden živý člověk = jedna party, napříč všemi kanály a v čase. Než vznikne nová party, customer-edge se zeptá pidu „znáš tuto osobu?" přes resolver, který porovnává identity, aniž by kdy viděl plaintext rodného čísla. Tvrdá shoda → znovupoužití existující party. Žádná shoda → vytvoř a zaregistruj. Nejednoznačnost → člověk (čtyři oči).',
            'Goal: one living person = one party, across every channel and over time. Before a new party is created, customer-edge asks pid "do you know this person?" through a resolver that compares identities without ever seeing the plaintext national ID. A hard match → reuse the existing party. No match → create and register. Ambiguity → a human (four-eyes).',
          )}
        </div>
      </div>

      {/* ---- Principles grid ---- */}
      <div style={{ fontSize: '12px', fontWeight: 700, letterSpacing: '0.04em', color: sub, textTransform: 'uppercase', margin: '8px 0 12px' }}>
        {t('Principy', 'Principles')}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(330px, 1fr))', gap: '14px', marginBottom: '28px' }}>
        {principles.map((p, i) => (
          <div key={i} className="card" style={{ padding: '18px', borderTop: `3px solid ${p.color}` }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: p.color, marginBottom: '8px' }}>
              {p.icon}
              <span style={{ fontSize: '14px', fontWeight: 700, color: ink }}>{t(...p.title)}</span>
            </div>
            <div style={{ fontSize: '12.5px', color: sub, lineHeight: 1.55 }}>{t(...p.body)}</div>
          </div>
        ))}
      </div>

      {/* ---- Resolution flow SVG ---- */}
      <div style={{ fontSize: '12px', fontWeight: 700, letterSpacing: '0.04em', color: sub, textTransform: 'uppercase', margin: '8px 0 12px' }}>
        {t('Tok rozhodnutí při onboardingu', 'Onboarding resolution flow')}
      </div>
      <div className="card" style={{ padding: '20px', marginBottom: '28px', overflowX: 'auto' }}>
        <ResolutionFlow t={t} />
      </div>

      {/* ---- Blind index pipeline SVG ---- */}
      <div style={{ fontSize: '12px', fontWeight: 700, letterSpacing: '0.04em', color: sub, textTransform: 'uppercase', margin: '8px 0 12px' }}>
        {t('Jak funguje blind index (privacy by design)', 'How the blind index works (privacy by design)')}
      </div>
      <div className="card" style={{ padding: '20px', marginBottom: '12px', overflowX: 'auto' }}>
        <BlindIndexPipeline t={t} />
      </div>
      <p style={{ fontSize: '12.5px', color: sub, lineHeight: 1.6, marginBottom: '28px' }}>
        {t(
          'Dvě žádosti se stejným rodným číslem dají vždy stejný index, takže pid je spolehlivě spáruje — ale databáze ani logy nikdy neobsahují samotné rodné číslo. Bez pepperu (uloženého v OpenBao) z indexu rodné číslo nezískáte. Kolize indexu s rozdílným datem narození / pohlavím = podezření → manuální verifikace (RN_COLLISION).',
          'Two requests carrying the same national ID always produce the same index, so pid reliably pairs them — yet neither the database nor the logs ever contain the national ID itself. Without the pepper (held in OpenBao) the index cannot be reversed. An index collision with a divergent birthdate / gender = suspicious → manual verification (RN_COLLISION).',
        )}
      </p>

      {/* ---- Three-tier table ---- */}
      <div style={{ fontSize: '12px', fontWeight: 700, letterSpacing: '0.04em', color: sub, textTransform: 'uppercase', margin: '8px 0 12px' }}>
        {t('Tříúrovňový resolver (žebřík od nejtvrdšího klíče)', 'Three-tier resolver (ladder from the hardest key)')}
      </div>
      <div className="card" style={{ padding: '0', marginBottom: '28px', overflowX: 'auto' }}>
        <table className="table" style={{ width: '100%', minWidth: '720px' }}>
          <thead>
            <tr>
              <th style={{ width: '60px' }}>{t('Úroveň', 'Tier')}</th>
              <th>{t('Signál', 'Signal')}</th>
              <th>{t('Technika', 'Technique')}</th>
              <th style={{ width: '160px' }}>{t('Sloučení', 'Merge')}</th>
              <th style={{ width: '110px' }}>{t('Stav', 'Status')}</th>
            </tr>
          </thead>
          <tbody>
            {tiers.map((tr, i) => (
              <tr key={i}>
                <td>
                  <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: '30px', height: '30px', borderRadius: '8px', background: `${tr.color}15`, color: tr.color, fontWeight: 800, fontSize: '13px' }}>{tr.tier}</span>
                </td>
                <td style={{ fontWeight: 600, color: ink }}>{t(...tr.signal)}</td>
                <td style={{ color: sub, fontSize: '12.5px', lineHeight: 1.5 }}>{t(...tr.technique)}</td>
                <td style={{ color: sub, fontSize: '12.5px' }}>{t(...tr.merge)}</td>
                <td><Status kind={tr.status} t={t} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ---- Worked example ---- */}
      <div style={{ fontSize: '12px', fontWeight: 700, letterSpacing: '0.04em', color: sub, textTransform: 'uppercase', margin: '8px 0 12px' }}>
        {t('Ukázka: jak se to chová na třech žadatelích', 'Worked example: behaviour across three applicants')}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '14px', marginBottom: '28px' }}>
        <WorkedCase
          t={t} accent="#16a34a"
          title={['1 · Stejná osoba, jiný zápis jména', '1 · Same person, different name spelling']}
          steps={[
            ['Eva Nováková, 90-01-01, RČ 9001011234 — onboarding A vytvoří party P1; pid zapíše blind index.', 'Eva Nováková, 90-01-01, RČ 9001011234 — onboarding A creates party P1; pid writes the blind index.'],
            ['Později „Eva Novakova" (bez diakritiky), stejné RČ — onboarding B zavolá pid /resolve.', 'Later "Eva Novakova" (no diacritics), same RČ — onboarding B calls pid /resolve.'],
            ['Tier-1 blind index sedí na P1 → MATCH_EXISTING.', 'Tier-1 blind index matches P1 → MATCH_EXISTING.'],
          ]}
          verdict={['MATCH_EXISTING → reuse P1 + přilinkuj nový Keycloak sub (KEYCLOAK_ID).', 'MATCH_EXISTING → reuse P1 + re-link the new Keycloak sub (KEYCLOAK_ID).']}
          verdictColor="#16a34a"
        />
        <WorkedCase
          t={t} accent="#2563eb"
          title={['2 · Dva jmenovci, různé RČ', '2 · Two namesakes, different national IDs']}
          steps={[
            ['Jan Svoboda, 88-05-05, RČ 8805051111 — party P2.', 'Jan Svoboda, 88-05-05, RČ 8805051111 — party P2.'],
            ['Jiný Jan Svoboda, 88-05-05, RČ 8805059999 — /resolve.', 'A different Jan Svoboda, 88-05-05, RČ 8805059999 — /resolve.'],
            ['Tier-1: indexy se liší. Tier-2 match-key (jméno+datum) by kolidoval, ale tvrdý klíč RČ je rozhodující → různé osoby.', 'Tier-1: indexes differ. Tier-2 match-key (name+birthdate) would collide, but the hard RČ key is decisive → different people.'],
          ]}
          verdict={['NO_MATCH → vytvoř party P3 + zaregistruj identitu do pidu (dual-write).', 'NO_MATCH → create party P3 + register the identity into pid (dual-write).']}
          verdictColor="#2563eb"
        />
        <WorkedCase
          t={t} accent="#dc2626"
          title={['3 · Kolize indexu, rozdílné atributy', '3 · Index collision, divergent attributes']}
          steps={[
            ['Žádost se stejným blind indexem jako P1, ale jiné datum narození / pohlaví.', 'A request with the same blind index as P1, but a different birthdate / gender.'],
            ['pid to NEuhodne — buď překlep RČ, nebo recyklace/podvod.', 'pid does NOT guess — either an RČ typo, or recycling/fraud.'],
            ['Routuje se na manuální verifikaci (RN_COLLISION); klient dostane neutrální „čeká na ověření".', 'Routes to manual verification (RN_COLLISION); the client gets a neutral "pending verification".'],
          ]}
          verdict={['NEEDS_MANUAL_VERIFICATION → fronta čtyř očí, žádný auto-merge.', 'NEEDS_MANUAL_VERIFICATION → four-eyes queue, no auto-merge.']}
          verdictColor="#dc2626"
        />
      </div>

      {/* ---- Live status footer ---- */}
      <div className="card" style={{ padding: '18px 22px', background: 'var(--surface-2)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
          <Layers size={16} style={{ color: 'var(--accent)' }} />
          <span style={{ fontSize: '13px', fontWeight: 700, color: ink }}>{t('Stav nasazení', 'Deployment status')}</span>
        </div>
        <ul style={{ margin: 0, paddingLeft: '18px', fontSize: '12.5px', color: sub, lineHeight: 1.7 }}>
          <li>{t('Tier-1 (RČ blind index) a Tier-2 (match-key) — ', 'Tier-1 (RČ blind-index) and Tier-2 (match-key) — ')}<Status kind="live" t={t} /> {t(' pid nasazen, pepper v OpenBao, dedup ověřen end-to-end.', ' pid deployed, pepper in OpenBao, dedup verified end-to-end.')}</li>
          <li>{t('Register-at-onboarding dual-write (plní pid pro nové onboardingy) a re-link Keycloak sub — ', 'Register-at-onboarding dual-write (populates pid for new onboardings) and Keycloak-sub re-link — ')}<Status kind="live" t={t} />.</li>
          <li>{t('Tier-2′ probabilistický matcher (Splink) jako zdroj kandidátů a fronta čtyř očí — ', "Tier-2' probabilistic matcher (Splink) as a candidate source and the four-eyes queue — ")}<Status kind="planned" t={t} />.</li>
          <li>{t('EUDI peněženka (OpenID4VP/VCI) + Temporal orchestrace onboardingu (ADR-0094) — ', 'EUDI wallet (OpenID4VP/VCI) + Temporal onboarding orchestration (ADR-0094) — ')}<Status kind="planned" t={t} />.</li>
        </ul>
      </div>
    </div>
  )
}

/* ============================ Resolution flow diagram ============================ */

function ResolutionFlow({ t }: { t: (cs: string, en: string) => string }) {
  const ink = 'var(--text-primary)'
  // A vertical decision flow, ~960 wide.
  return (
    <svg viewBox="0 0 960 600" style={{ width: '100%', minWidth: '720px', height: 'auto' }} fontFamily="inherit">
      <defs>
        <marker id="arr" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="strokeWidth">
          <path d="M0,0 L7,3 L0,6 Z" fill="#94a3b8" />
        </marker>
      </defs>

      {/* Step 1: applicant */}
      <FlowBox x={360} y={16} w={240} h={52} fill="#6366f115" stroke="#6366f1"
        title={t('Nový žadatel', 'New applicant')}
        sub={t('jméno · datum nar. · RČ?', 'name · birthdate · RČ?')} />
      <line x1={480} y1={68} x2={480} y2={96} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr)" />

      {/* Step 2: edge gate */}
      <FlowBox x={330} y={96} w={300} h={56} fill="#0891b215" stroke="#0891b2"
        title={t('customer-edge · resolver gate', 'customer-edge · resolver gate')}
        sub={t('flag IDENTITY_RESOLUTION_ENABLED', 'flag IDENTITY_RESOLUTION_ENABLED')} />
      <text x={642} y={128} fontSize={10.5} fill="#64748b">{t('fail-open', 'fail-open')}</text>
      <line x1={480} y1={152} x2={480} y2={180} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr)" />

      {/* Step 3: pid resolve */}
      <FlowBox x={350} y={180} w={260} h={52} fill="#1e293b" stroke="#0f172a"
        title="POST pid /resolve" titleColor="#fff"
        sub={t('porovnání bez plaintextu RČ', 'compares without plaintext RČ')} subColor="#cbd5e1" />

      {/* tier ladder */}
      <line x1={480} y1={232} x2={480} y2={256} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr)" />
      <TierChip x={150} y={256} color="#16a34a" label={t('Tier-1 · RČ blind index', 'Tier-1 · RČ blind index')} tag={t('tvrdý klíč', 'hard key')} />
      <TierChip x={385} y={256} color="#2563eb" label={t('Tier-2 · match-key', 'Tier-2 · match-key')} tag={t('jméno+datum', 'name+date')} />
      <TierChip x={620} y={256} color="#d97706" label={t("Tier-2′ · Splink", "Tier-2' · Splink")} tag={t('kandidát', 'candidate')} dashed />
      <text x={810} y={283} fontSize={10.5} fill="#64748b">{t('v pořadí ↓', 'in order ↓')}</text>

      {/* split to 3 outcomes */}
      <line x1={480} y1={296} x2={480} y2={324} stroke="#94a3b8" strokeWidth={1.5} />
      <line x1={170} y1={324} x2={790} y2={324} stroke="#94a3b8" strokeWidth={1.5} />
      {[170, 480, 790].map((x) => (
        <line key={x} x1={x} y1={324} x2={x} y2={352} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr)" />
      ))}

      {/* outcome boxes */}
      <OutcomeBox cx={170} y={352} fill="#16a34a"
        verdict="MATCH_EXISTING"
        action={t('reuse party + re-link sub', 'reuse party + re-link sub')} />
      <OutcomeBox cx={480} y={352} fill="#2563eb"
        verdict="NO_MATCH"
        action={t('create party + register do pid', 'create party + register into pid')} />
      <OutcomeBox cx={790} y={352} fill="#dc2626"
        verdict="NEEDS_MANUAL_VERIFICATION"
        action={t('neutrální 202 · čtyři oči', 'neutral 202 · four-eyes')} />

      {/* dual-write loop back to pid */}
      <path d="M 600 392 C 720 392 740 300 660 206" fill="none" stroke="#2563eb" strokeWidth={1.4} strokeDasharray="4 3" markerEnd="url(#arr)" />
      <text x={690} y={300} fontSize={10} fill="#2563eb" transform="rotate(-58 690 300)">{t('dual-write blind index', 'dual-write blind index')}</text>

      {/* legend */}
      <g transform="translate(150,470)">
        <text x={0} y={0} fontSize={11} fontWeight={700} fill={ink}>{t('Klíč:', 'Key:')}</text>
        <rect x={44} y={-10} width={12} height={12} rx={3} fill="#16a34a" /><text x={62} y={0} fontSize={11} fill="#64748b">{t('deterministická shoda (auto)', 'deterministic match (auto)')}</text>
        <rect x={290} y={-10} width={12} height={12} rx={3} fill="#d97706" /><text x={308} y={0} fontSize={11} fill="#64748b">{t('pravděpodobnostní (jen kandidát)', 'probabilistic (candidate only)')}</text>
        <rect x={560} y={-10} width={12} height={12} rx={3} fill="#dc2626" /><text x={578} y={0} fontSize={11} fill="#64748b">{t('člověk rozhoduje', 'human decides')}</text>
      </g>
    </svg>
  )
}

function FlowBox({ x, y, w, h, fill, stroke, title, sub, titleColor, subColor }: {
  x: number; y: number; w: number; h: number; fill: string; stroke: string; title: string; sub?: string; titleColor?: string; subColor?: string
}) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx={9} fill={fill} stroke={stroke} strokeWidth={1.4} />
      <text x={x + w / 2} y={y + (sub ? h / 2 - 4 : h / 2 + 4)} textAnchor="middle" fontSize={13} fontWeight={700} fill={titleColor || stroke}>{title}</text>
      {sub && <text x={x + w / 2} y={y + h / 2 + 13} textAnchor="middle" fontSize={11} fill={subColor || '#64748b'}>{sub}</text>}
    </g>
  )
}

function TierChip({ x, y, color, label, tag, dashed }: { x: number; y: number; color: string; label: string; tag: string; dashed?: boolean }) {
  return (
    <g>
      <rect x={x} y={y} width={190} height={40} rx={8} fill={`${color}12`} stroke={color} strokeWidth={1.3} strokeDasharray={dashed ? '5 3' : undefined} />
      <text x={x + 95} y={y + 17} textAnchor="middle" fontSize={11.5} fontWeight={700} fill={color}>{label}</text>
      <text x={x + 95} y={y + 31} textAnchor="middle" fontSize={10} fill="#64748b">{tag}</text>
    </g>
  )
}

function OutcomeBox({ cx, y, fill, verdict, action }: { cx: number; y: number; fill: string; verdict: string; action: string }) {
  const w = 230
  const x = cx - w / 2
  return (
    <g>
      <rect x={x} y={y} width={w} height={40} rx={8} fill={`${fill}15`} stroke={fill} strokeWidth={1.5} />
      <text x={cx} y={y + 17} textAnchor="middle" fontSize={11} fontWeight={800} fill={fill}>{verdict}</text>
      <text x={cx} y={y + 31} textAnchor="middle" fontSize={10.5} fill="#475569">{action}</text>
    </g>
  )
}

/* ============================ Blind index pipeline diagram ============================ */

function BlindIndexPipeline({ t }: { t: (cs: string, en: string) => string }) {
  return (
    <svg viewBox="0 0 960 230" style={{ width: '100%', minWidth: '720px', height: 'auto' }} fontFamily="inherit">
      <defs>
        <marker id="arr2" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="strokeWidth">
          <path d="M0,0 L7,3 L0,6 Z" fill="#94a3b8" />
        </marker>
      </defs>

      <PipeBox x={10} y={70} w={170} fill="#fef2f2" stroke="#dc2626"
        top={t('Plaintext RČ', 'Plaintext RČ')} mono="900101/1234" foot={t('jen v paměti requestu', 'request memory only')} footColor="#dc2626" />
      <line x1={180} y1={95} x2={214} y2={95} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr2)" />

      <PipeBox x={214} y={70} w={160} fill="var(--surface-3)" stroke="#94a3b8"
        top={t('Kanonizace', 'Canonicalize')} mono="9001011234" foot={t('odstraň lomítko, validuj', 'strip slash, validate')} />
      <line x1={374} y1={95} x2={408} y2={95} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr2)" />

      <PipeBox x={408} y={70} w={184} fill="#eef2ff" stroke="#6366f1"
        top="HMAC-SHA256" mono={t('pepper ⊕ RČ', 'pepper ⊕ RČ')} foot={t('pepper z OpenBao', 'pepper from OpenBao')} footColor="#6366f1" />
      <line x1={592} y1={95} x2={626} y2={95} stroke="#94a3b8" strokeWidth={1.5} markerEnd="url(#arr2)" />

      <PipeBox x={626} y={70} w={184} fill="#f0fdf4" stroke="#16a34a"
        top={t('Blind index', 'Blind index')} mono="a17f…e3c9" foot={t('uloženo do DB', 'persisted to DB')} footColor="#16a34a" />

      {/* pepper key callout */}
      <g>
        <rect x={430} y={8} width={140} height={30} rx={7} fill="#1e293b" />
        <text x={500} y={27} textAnchor="middle" fontSize={11} fontWeight={700} fill="#fff">🔑 OpenBao pepper</text>
        <line x1={500} y1={38} x2={500} y2={68} stroke="#6366f1" strokeWidth={1.4} strokeDasharray="4 3" markerEnd="url(#arr2)" />
      </g>

      {/* invariants */}
      <text x={92} y={150} textAnchor="middle" fontSize={10.5} fill="#dc2626" fontWeight={600}>{t('✗ nikdy neuloženo', '✗ never stored')}</text>
      <text x={718} y={150} textAnchor="middle" fontSize={10.5} fill="#16a34a" fontWeight={600}>{t('✓ jednosměrné, klíčované', '✓ one-way, keyed')}</text>

      <g transform="translate(120,188)">
        <rect x={0} y={-14} width={720} height={34} rx={8} fill="var(--surface-2)" stroke="var(--border)" />
        <text x={360} y={7} textAnchor="middle" fontSize={11.5} fill="var(--text-secondary)">
          {t('Stejné RČ → stejný index → spárování. Bez pepperu index nelze obrátit zpět na RČ.', 'Same RČ → same index → a match. Without the pepper the index cannot be reversed to the RČ.')}
        </text>
      </g>
    </svg>
  )
}

function PipeBox({ x, y, w, fill, stroke, top, mono, foot, footColor }: {
  x: number; y: number; w: number; fill: string; stroke: string; top: string; mono: string; foot: string; footColor?: string
}) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={50} rx={9} fill={fill} stroke={stroke} strokeWidth={1.4} />
      <text x={x + w / 2} y={y + 16} textAnchor="middle" fontSize={11.5} fontWeight={700} fill={stroke}>{top}</text>
      <text x={x + w / 2} y={y + 34} textAnchor="middle" fontSize={12} fontWeight={700} fill="var(--text-primary)" fontFamily="ui-monospace, monospace">{mono}</text>
      <text x={x + w / 2} y={y + 66} textAnchor="middle" fontSize={10} fill={footColor || '#64748b'}>{foot}</text>
    </g>
  )
}

/* ============================ Worked-case card ============================ */

function WorkedCase({ t, accent, title, steps, verdict, verdictColor }: {
  t: (cs: string, en: string) => string
  accent: string
  title: [string, string]
  steps: [string, string][]
  verdict: [string, string]
  verdictColor: string
}) {
  return (
    <div className="card" style={{ padding: '18px', borderTop: `3px solid ${accent}`, display: 'flex', flexDirection: 'column' }}>
      <div style={{ fontSize: '13.5px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px' }}>{t(...title)}</div>
      <ol style={{ margin: 0, paddingLeft: '18px', fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.55, flex: 1 }}>
        {steps.map((s, i) => (
          <li key={i} style={{ marginBottom: '6px' }}>{t(...s)}</li>
        ))}
      </ol>
      <div style={{ marginTop: '12px', padding: '9px 11px', borderRadius: '8px', background: `${verdictColor}12`, border: `1px solid ${verdictColor}30`, display: 'flex', alignItems: 'flex-start', gap: '7px' }}>
        <ArrowRight size={14} style={{ color: verdictColor, flexShrink: 0, marginTop: '2px' }} />
        <span style={{ fontSize: '12px', fontWeight: 600, color: verdictColor, lineHeight: 1.45 }}>{t(...verdict)}</span>
      </div>
    </div>
  )
}
