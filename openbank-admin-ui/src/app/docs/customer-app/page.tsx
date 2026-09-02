// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Customer-app dossier (ADR-0074) — a living, bilingual plan-vs-reality view of
// the KMP/Compose customer app, across three lenses (governance / technology /
// security). Mirrors /docs/cloud-architecture's live/partial/planned overlay and
// its stance: document the gap honestly instead of selling the target.
//
// Data: /api/app-status — DERIVED facts come from the app's AppConfig.kt +
// version.txt (never transcribed), the curatorial layer from app-status.yaml,
// and ADR status is resolved at view time against the live ADR index. The
// README once claimed F0/useFakeData=true while the code said false; deriving
// from code is why this page cannot repeat that drift.

import { useEffect, useMemo, useState } from 'react'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import Link from 'next/link'
import {
  Smartphone, ChevronLeft, CheckCircle2, CircleDashed, Circle,
  ShieldCheck, Cpu, Scale, AlertTriangle, RefreshCw, FileWarning,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Status = 'live' | 'partial' | 'planned'
type Lens = 'governance' | 'technology' | 'security'
type AdrStatus = 'Accepted' | 'Proposed' | 'Superseded' | 'Deprecated' | 'Rejected' | 'Unknown'

interface BiText { cs: string; en: string }
interface ResolvedAdr { id: string; slug: string | null; title: string | null; status: AdrStatus }
interface Capability {
  id: string
  title: BiText
  lens: Lens[]
  status: Status
  gap: BiText
  resolvedAdrs: ResolvedAdr[]
  decisionMissing: boolean
}
interface AppStatus {
  app: { name: string; displayName?: BiText; owner?: string; repo?: string }
  asOf: string | null
  derived: {
    version?: string | null
    useFakeData?: boolean | null
    edgeBaseUrl?: string | null
    keycloakIssuer?: string | null
    oauthClientId?: string | null
    oauthScopes?: string[] | null
    certPinningConfigured?: boolean | null
    certPinningActive?: boolean | null
    sourceAvailable?: boolean
  }
  capabilities: Capability[]
  gaps?: string[]
  available?: boolean
}

const STATUS_META: Record<Status, { cs: string; en: string; color: string; bg: string; border: string; Icon: React.ElementType }> = {
  live:    { cs: 'Live (běží dnes)',            en: 'Live (running today)',         color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2 },
  partial: { cs: 'Částečně (nasazeno, neúplné)', en: 'Partial (deployed, incomplete)', color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: CircleDashed },
  planned: { cs: 'Plánováno',                   en: 'Planned',                      color: '#94a3b8', bg: '#f8fafc', border: '#cbd5e1', Icon: Circle },
}

const LENS_META: Record<Lens, { cs: string; en: string; color: string; Icon: React.ElementType }> = {
  governance: { cs: 'Governance',   en: 'Governance', color: '#0891b2', Icon: Scale },
  technology: { cs: 'Technologie',  en: 'Technology', color: '#2563eb', Icon: Cpu },
  security:   { cs: 'Bezpečnost',   en: 'Security',   color: '#dc2626', Icon: ShieldCheck },
}

function adrStatusColor(s: AdrStatus): string {
  switch (s) {
    case 'Accepted':   return '#059669'
    case 'Proposed':   return '#2563eb'
    case 'Deprecated': return '#d97706'
    case 'Rejected':   return '#dc2626'
    default:           return '#94a3b8'
  }
}

export default function CustomerAppDossierPage() {
  const { t, language } = useLanguage()
  const [data, setData] = useState<AppStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [lensFilter, setLensFilter] = useState<Lens | 'all'>('all')

  useEffect(() => {
    let alive = true
    fetch('/api/app-status', { cache: 'no-store' })
      .then((r) => r.json())
      .then((d: AppStatus) => { if (alive) { setData(d); setLoading(false) } })
      .catch(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  const bi = (b?: BiText) => (b ? (language === 'cs' ? b.cs : b.en) : '')

  const caps = useMemo(() => data?.capabilities ?? [], [data])
  const filtered = useMemo(
    () => (lensFilter === 'all' ? caps : caps.filter((c) => c.lens.includes(lensFilter))),
    [caps, lensFilter],
  )
  const counts = useMemo(() => {
    const by: Record<Status, number> = { live: 0, partial: 0, planned: 0 }
    for (const c of caps) by[c.status]++
    return by
  }, [caps])
  const decisionMissing = caps.filter((c) => c.decisionMissing)

  const d = data?.derived

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <Link href="/docs" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, textDecoration: 'none', color: 'inherit' }}>
              <ChevronLeft size={13} /> {t('Dokumentace', 'Documentation')}
            </Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Aplikace', 'Customer App')}</span>
          </>}
        title={t('Aplikace — plán vs realita', 'Customer App — plan vs reality')}
        subtitle={t(
              'Jak je zákaznická aplikace (KMP/Compose) tvořena, integrována a zabezpečena — pohledem governance, technologií a bezpečnosti. Fakta odvozená z kódu, ne přepsaná ručně (ADR-0074).',
              'How the customer app (KMP/Compose) is built, integrated and secured — through the governance, technology and security lenses. Facts derived from code, not hand-transcribed (ADR-0074).',
            )}
        icon={<Smartphone aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
      />

      {loading && <div role="status" aria-live="polite" className="card" style={{ padding: 24, color: 'var(--text-secondary)' }}>{t('Načítám…', 'Loading…')}</div>}

      {!loading && data?.available === false && (
        <div className="card" style={{ padding: 24, borderTop: '3px solid #d97706' }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', color: '#d97706', fontWeight: 700 }}>
            <FileWarning size={18} /> {t('Artefakt app-status.json není k dispozici', 'app-status.json artefact unavailable')}
          </div>
          <p style={{ color: 'var(--text-secondary)', marginTop: 8, fontSize: 13 }}>
            {t(
              'Spusťte `node scripts/generate-app-status.mjs` (derivuje z openbank-app/AppConfig.kt + app-status.yaml). Stránka záměrně nic nevymýšlí — honest by construction.',
              'Run `node scripts/generate-app-status.mjs` (derives from openbank-app/AppConfig.kt + app-status.yaml). The page intentionally fabricates nothing — honest by construction.',
            )}
          </p>
        </div>
      )}

      {!loading && data?.available !== false && (
        <>
          {/* Honesty banner: where the facts come from + freshness + decision-missing */}
          <div className="card" style={{ padding: 16, marginBottom: 16, borderTop: '3px solid #0891b2' }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)', maxWidth: 620 }}>
                {t(
                  'Stav níže je odvozen z kódu aplikace (AppConfig.kt, version.txt) a doplněn kurátorskou vrstvou (app-status.yaml). ADR statusy se resolvují živě proti registru ADR.',
                  'The state below is derived from the app code (AppConfig.kt, version.txt) and joined with a curatorial layer (app-status.yaml). ADR statuses resolve live against the ADR registry.',
                )}
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                {data?.asOf && (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--text-secondary)' }}>
                    <RefreshCw size={12} /> {t('Kurátorská vrstva k', 'Curated as of')} {data.asOf}
                  </span>
                )}
                {(['live', 'partial', 'planned'] as Status[]).map((s) => {
                  const m = STATUS_META[s]
                  return (
                    <span key={s} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 700, color: m.color, background: m.bg, border: `1px solid ${m.border}`, padding: '2px 8px', borderRadius: 20 }}>
                      <m.Icon size={12} /> {counts[s]}
                    </span>
                  )
                })}
              </div>
            </div>
            {decisionMissing.length > 0 && (
              <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: '#dc2626', fontWeight: 600 }}>
                <AlertTriangle size={15} />
                {t(
                  `${decisionMissing.length} schopnost(í) bez governing ADR — rozhodnutí chybí: `,
                  `${decisionMissing.length} capability(ies) without a governing ADR — decision missing: `,
                )}
                <span style={{ fontWeight: 400, color: 'var(--text-secondary)' }}>{decisionMissing.map((c) => c.id).join(', ')}</span>
              </div>
            )}
          </div>

          {/* Derived-from-code facts */}
          {d && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12, marginBottom: 20 }}>
              <Fact label={t('Verze', 'Version')} value={d.version ?? '—'} mono />
              <Fact
                label={t('Zdroj dat', 'Data source')}
                value={d.useFakeData == null ? '—' : d.useFakeData ? t('seed (fake)', 'seeded (fake)') : t('živý edge', 'live edge')}
                tone={d.useFakeData === false ? 'good' : d.useFakeData === true ? 'warn' : 'muted'}
              />
              <Fact label={t('Customer edge', 'Customer edge')} value={d.edgeBaseUrl ?? '—'} mono />
              <Fact
                label={t('TLS pinning', 'TLS pinning')}
                value={d.certPinningActive ? t('aktivní', 'active') : t('vypnutý (bez hashů)', 'off (no hashes)')}
                tone={d.certPinningActive ? 'good' : 'warn'}
              />
              {d.oauthScopes && <Fact label={t('OAuth scopes', 'OAuth scopes')} value={d.oauthScopes.join(' · ')} mono />}
              {d.oauthClientId && <Fact label={t('OAuth klient', 'OAuth client')} value={d.oauthClientId} mono />}
              {d.keycloakIssuer && <Fact label={t('Keycloak realm', 'Keycloak realm')} value={d.keycloakIssuer} mono />}
            </div>
          )}

          {/* Lens filter */}
          <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
            <LensChip active={lensFilter === 'all'} onClick={() => setLensFilter('all')} color="#475569" label={t('Vše', 'All')} count={caps.length} />
            {(Object.keys(LENS_META) as Lens[]).map((l) => {
              const m = LENS_META[l]
              return (
                <LensChip
                  key={l}
                  active={lensFilter === l}
                  onClick={() => setLensFilter(l)}
                  color={m.color}
                  Icon={m.Icon}
                  label={language === 'cs' ? m.cs : m.en}
                  count={caps.filter((c) => c.lens.includes(l)).length}
                />
              )
            })}
          </div>

          {/* Capability cards */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 14 }}>
            {filtered.map((c) => {
              const m = STATUS_META[c.status]
              return (
                <div key={c.id} className="card" style={{ padding: 18, borderLeft: `3px solid ${m.color}` }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8, marginBottom: 8 }}>
                    <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--text-primary)' }}>{bi(c.title)}</div>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0, fontSize: 11, fontWeight: 700, color: m.color, background: m.bg, border: `1px solid ${m.border}`, padding: '2px 8px', borderRadius: 20 }}>
                      <m.Icon size={11} /> {language === 'cs' ? m.cs : m.en}
                    </span>
                  </div>

                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10 }}>
                    {c.lens.map((l) => {
                      const lm = LENS_META[l]
                      return (
                        <span key={l} style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 10, fontWeight: 700, color: lm.color, background: `${lm.color}12`, border: `1px solid ${lm.color}30`, padding: '1px 6px', borderRadius: 10 }}>
                          <lm.Icon size={9} /> {language === 'cs' ? lm.cs : lm.en}
                        </span>
                      )
                    })}
                  </div>

                  <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.5, margin: '0 0 12px' }}>{bi(c.gap)}</p>

                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                    {c.decisionMissing && c.resolvedAdrs.length === 0 && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 700, color: '#dc2626', background: '#fef2f2', border: '1px solid #fca5a5', padding: '2px 8px', borderRadius: 20 }}>
                        <AlertTriangle size={11} /> {t('rozhodnutí chybí', 'decision missing')}
                      </span>
                    )}
                    {c.resolvedAdrs.map((a) => {
                      const col = adrStatusColor(a.status)
                      const chip = (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 700, color: col, background: `${col}12`, border: `1px solid ${col}40`, padding: '2px 8px', borderRadius: 20 }}>
                          {a.id} · {a.status}
                        </span>
                      )
                      return a.slug ? (
                        <Link key={a.id} href={`/docs/adr/${a.slug}`} title={a.title ?? a.id} style={{ textDecoration: 'none' }}>{chip}</Link>
                      ) : (
                        <span key={a.id} title={t('ADR nenalezen v registru', 'ADR not found in registry')}>{chip}</span>
                      )
                    })}
                  </div>
                </div>
              )
            })}
          </div>

          {/* Plan-vs-reality matrix */}
          <h2 style={{ fontSize: 15, fontWeight: 700, margin: '28px 0 12px', color: 'var(--text-primary)' }}>
            {t('Matice plán vs realita', 'Plan-vs-reality matrix')}
          </h2>
          <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ textAlign: 'left', color: 'var(--text-secondary)', background: 'var(--surface-2, #f8fafc)' }}>
                  <th style={th}>{t('Schopnost', 'Capability')}</th>
                  <th style={th}>{t('Pohled', 'Lens')}</th>
                  <th style={th}>{t('Stav', 'State')}</th>
                  <th style={th}>{t('Rozhodnutí (ADR)', 'Decision (ADR)')}</th>
                </tr>
              </thead>
              <tbody>
                {caps.map((c) => {
                  const m = STATUS_META[c.status]
                  return (
                    <tr key={c.id} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}><span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{bi(c.title)}</span></td>
                      <td style={td}>{c.lens.map((l) => (language === 'cs' ? LENS_META[l].cs : LENS_META[l].en)).join(', ')}</td>
                      <td style={td}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: m.color, fontWeight: 700 }}>
                          <m.Icon size={12} /> {language === 'cs' ? m.cs : m.en}
                        </span>
                      </td>
                      <td style={td}>
                        {c.resolvedAdrs.length === 0
                          ? <span style={{ color: '#dc2626', fontWeight: 700 }}>{t('chybí', 'missing')}</span>
                          : c.resolvedAdrs.map((a) => a.id).join(', ')}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          <p style={{ fontSize: 12, color: 'var(--text-tertiary, #94a3b8)', marginTop: 14 }}>
            {t(
              'Zdroj: openbank-app/app-status.yaml (kurátorské) + AppConfig.kt/version.txt (derivované) → scripts/generate-app-status.mjs → app-status.json. ADR statusy živě z registru. ADR-0074.',
              'Source: openbank-app/app-status.yaml (curatorial) + AppConfig.kt/version.txt (derived) → scripts/generate-app-status.mjs → app-status.json. ADR statuses live from the registry. ADR-0074.',
            )}
          </p>
        </>
      )}
    </div>
  )
}

const th: React.CSSProperties = { padding: '10px 14px', fontWeight: 700, fontSize: 12 }
const td: React.CSSProperties = { padding: '10px 14px', verticalAlign: 'top' }

function Fact({ label, value, mono, tone }: { label: string; value: string; mono?: boolean; tone?: 'good' | 'warn' | 'muted' }) {
  const color = tone === 'good' ? '#059669' : tone === 'warn' ? '#d97706' : 'var(--text-primary)'
  return (
    <div className="card" style={{ padding: 14 }}>
      <div style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 13, fontWeight: 600, color, fontFamily: mono ? 'var(--font-mono, monospace)' : 'inherit', wordBreak: 'break-all' }}>{value}</div>
    </div>
  )
}

function LensChip({ active, onClick, color, label, count, Icon }: { active: boolean; onClick: () => void; color: string; label: string; count: number; Icon?: React.ElementType }) {
  return (
    <button
      onClick={onClick}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
        fontSize: 13, fontWeight: 600, fontFamily: 'inherit',
        padding: '6px 12px', borderRadius: 20,
        color: active ? '#fff' : color,
        background: active ? color : `${color}10`,
        border: `1px solid ${active ? color : `${color}30`}`,
      }}
    >
      {Icon && <Icon size={13} />}
      {label}
      <span style={{ fontSize: 11, opacity: 0.85 }}>{count}</span>
    </button>
  )
}
