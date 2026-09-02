// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Cluster & container dossier (ADR-0081). A plan-vs-reality view of how the platform is split by
// namespace, how it's secured (defense in depth), and how the default service image is built and
// hardened — derived from GitOps + a representative Dockerfile (cluster-topology.json), gaps shown
// honestly. Styled in a Kubernetes / container idiom; written to be readable by a non-expert.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import {
  Boxes, Box, Lock, Network, Cpu, Globe, Shield, Key, CheckCircle2, CircleDashed, Circle,
  ChevronRight, RefreshCw, FileText, BadgeCheck, AlertTriangle, Building2, Server,
} from 'lucide-react'

type Status = 'live' | 'partial' | 'planned'

interface Namespace { name: string; group: string; role: string; declared: boolean }
interface Group { id: string; label: string; labelEn: string; color: string; icon: string; blurb: string }
interface Layer { id: string; label: string; icon: string; status: Status; analogy: string; summary: string; controls: string[]; adr: string[]; detailRoute?: string }
interface AnatomyStep { id: string; label: string; status: Status; detail: string; adr: string[] }
interface PvR { item: string; plan: string; reality: string; status: Status }
interface Topology {
  generatedAt: string | null
  counts: { namespaces?: number; networkPolicies?: number; externalSecrets?: number; clusterPolicies?: number }
  groups: Group[]
  namespaces: Namespace[]
  securityLayers: Layer[]
  imageAnatomy: { multiStage?: boolean; buildBase?: string; runtimeBase?: string; steps: AnatomyStep[] }
  planVsReality: PvR[]
}

const STATUS: Record<Status, { cs: string; en: string; color: string; bg: string; border: string; Icon: React.ElementType }> = {
  live: { cs: 'Live', en: 'Live', color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2 },
  partial: { cs: 'Částečně', en: 'Partial', color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: CircleDashed },
  planned: { cs: 'Plánováno', en: 'Planned', color: '#64748b', bg: '#f8fafc', border: '#cbd5e1', Icon: Circle },
}
const ICONS: Record<string, React.ElementType> = {
  bank: Building2, lock: Lock, network: Network, cpu: Cpu, globe: Globe, shield: Shield, key: Key, box: Box, server: Server,
}
const K8S_BLUE = '#326CE5'

function StatusPill({ s, lang }: { s: Status; lang: string }) {
  const m = STATUS[s]
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 700, color: m.color, background: m.bg, border: `1px solid ${m.border}`, padding: '2px 8px', borderRadius: 20 }}>
      <m.Icon size={11} /> {lang === 'cs' ? m.cs : m.en}
    </span>
  )
}

function AdrRefs({ adr }: { adr: string[] }) {
  if (!adr?.length) return null
  return (
    <span style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
      {adr.map(a => (
        <a key={a} href={`/docs/adr#${a}`} style={{ fontSize: 10, fontFamily: 'var(--font-mono, monospace)', color: K8S_BLUE, background: 'rgba(50,108,229,0.08)', border: '1px solid rgba(50,108,229,0.25)', padding: '1px 6px', borderRadius: 4, textDecoration: 'none' }}>
          ADR-{a}
        </a>
      ))}
    </span>
  )
}

// ── Hero: concentric defense-in-depth rings ───────────────────────────────────────────────────
function DefenseRings({ layers, active, onPick, lang }: { layers: Layer[]; active: string | null; onPick: (id: string) => void; lang: string }) {
  const n = layers.length
  const size = 260
  const cx = size / 2
  return (
    <svg viewBox={`0 0 ${size} ${size}`} style={{ width: 240, height: 240, flexShrink: 0 }} role="group" aria-label={lang === 'cs' ? 'Obrana do hloubky' : 'Defense in depth'}>
      {layers.map((l, i) => {
        const r = (cx - 6) * (1 - i / n)
        const m = STATUS[l.status]
        const on = active === l.id
        return (
          <g key={l.id} role="button" tabIndex={0} aria-label={l.label}
            onClick={() => onPick(l.id)}
            onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPick(l.id) } }}
            onFocus={() => onPick(l.id)} style={{ cursor: 'pointer' }}>
            <circle cx={cx} cy={cx} r={r} fill={on ? m.bg : 'transparent'} stroke={m.color} strokeWidth={on ? 3 : 2} opacity={on ? 1 : 0.55} />
          </g>
        )
      })}
      <text x={cx} y={cx + 4} textAnchor="middle" fontSize="11" fontWeight="700" fill="var(--text-secondary)">core</text>
    </svg>
  )
}

// ── Container image anatomy (cutaway) ─────────────────────────────────────────────────────────
function ContainerAnatomy({ anatomy, lang }: { anatomy: Topology['imageAnatomy']; lang: string }) {
  const [open, setOpen] = useState<string | null>(anatomy.steps[0]?.id ?? null)
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.3fr)', gap: 20, alignItems: 'start' }}>
      {/* the box */}
      <div style={{ position: 'relative', background: 'linear-gradient(160deg,#1e293b,#0f172a)', borderRadius: 14, padding: 14, border: '2px solid #334155', boxShadow: '0 10px 30px rgba(0,0,0,0.25)' }}>
        {/* corner rivets */}
        {[[8, 8], [8, 'r'], ['b', 8], ['b', 'r']].map((p, i) => (
          <span key={i} style={{ position: 'absolute', top: p[0] === 'b' ? undefined : 8, bottom: p[0] === 'b' ? 8 : undefined, left: p[1] === 'r' ? undefined : 8, right: p[1] === 'r' ? 8 : undefined, width: 7, height: 7, borderRadius: '50%', background: '#475569' }} />
        ))}
        <div style={{ textAlign: 'center', color: '#cbd5e1', fontFamily: 'var(--font-mono, monospace)', fontSize: 10, letterSpacing: '0.15em', marginBottom: 8 }}>
          OPENBANK · {anatomy.runtimeBase}
        </div>
        <div style={{ display: 'grid', gap: 4 }}>
          {anatomy.steps.map(st => {
            const m = STATUS[st.status]
            const on = open === st.id
            const discarded = st.id === 'build'
            return (
              <button key={st.id} onClick={() => setOpen(st.id)} type="button"
                aria-expanded={on} aria-controls={on ? `cluster-anatomy-panel-${st.id}` : undefined} style={{
                textAlign: 'left', display: 'flex', alignItems: 'center', gap: 8, padding: '8px 10px', borderRadius: 7, cursor: 'pointer',
                background: on ? 'rgba(50,108,229,0.18)' : 'rgba(255,255,255,0.03)',
                border: `1px solid ${on ? K8S_BLUE : 'rgba(255,255,255,0.06)'}`,
                opacity: discarded ? 0.5 : 1,
                borderStyle: discarded ? 'dashed' : 'solid',
              }}>
                <span style={{ width: 7, height: 7, borderRadius: '50%', background: m.color, flexShrink: 0 }} />
                <span style={{ fontSize: 12.5, fontWeight: 600, color: '#e2e8f0', flex: 1 }}>{st.label}</span>
                {st.id === 'sign' && <BadgeCheck aria-hidden="true" size={14} style={{ color: '#34d399' }} />}
                <ChevronRight aria-hidden="true" size={13} style={{ color: '#64748b', transform: on ? 'rotate(90deg)' : 'none', transition: 'transform .15s' }} />
              </button>
            )
          })}
        </div>
        {anatomy.multiStage && (
          <div style={{ marginTop: 8, fontSize: 10, color: '#64748b', textAlign: 'center', fontStyle: 'italic' }}>
            {lang === 'cs' ? `Build stage (${anatomy.buildBase}) se zahodí — distribuuje se jen runtime.` : `Build stage discarded — only the runtime ships.`}
          </div>
        )}
      </div>
      {/* detail of the open slice */}
      <div>
        {anatomy.steps.filter(s => s.id === open).map(st => (
          <div key={st.id} id={`cluster-anatomy-panel-${st.id}`} role="region" aria-label={st.label} className="card" style={{ padding: 16, borderLeft: `3px solid ${STATUS[st.status].color}` }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
              <h4 style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{st.label}</h4>
              <StatusPill s={st.status} lang={lang} />
            </div>
            <p style={{ fontSize: 13.5, lineHeight: 1.6, color: 'var(--text-secondary)', marginBottom: 10 }}>{st.detail}</p>
            <AdrRefs adr={st.adr} />
          </div>
        ))}
      </div>
    </div>
  )
}

export default function ClusterDossierPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [topo, setTopo] = useState<Topology | null>(null)
  const [loading, setLoading] = useState(true)
  const [activeLayer, setActiveLayer] = useState<string | null>(null)
  const [openNs, setOpenNs] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await fetch('/api/cluster/topology', { cache: 'no-store' })
      const d = await r.json()
      setTopo(d)
      setActiveLayer(d.securityLayers?.[0]?.id ?? null)
    } catch { setTopo(null) } finally { setLoading(false) }
  }, [])
  useEffect(() => { void load() }, [load])

  const nsByGroup = useMemo(() => {
    const map: Record<string, Namespace[]> = {}
    for (const n of topo?.namespaces ?? []) (map[n.group] ??= []).push(n)
    return map
  }, [topo])

  const c = topo?.counts ?? {}

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Cluster & kontejner', 'Cluster & container')}</span>
          </>}
        title={t('Cluster & kontejner — topologie a hardening', 'Cluster & container — topology and hardening')}
        subtitle={t(
              'Jak je platforma rozdělená po namespaces, jak je zabezpečená (obrana do hloubky) a jak je poskládaný a zabezpečený výchozí image — plán vs. realita, odvozeno z GitOpsu (ADR-0081).',
              'How the platform is split across namespaces, how it is secured (defense in depth), and how the default service image is built and hardened — plan vs reality, derived from GitOps (ADR-0081).',
            )}
        icon={<Boxes aria-hidden="true" size={20} style={{ color: K8S_BLUE }} />}
        actions={<button onClick={load} disabled={loading} type="button" aria-busy={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw aria-hidden="true" size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />

      {/* derived counts */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 12, marginBottom: 26 }}>
        {[
          { label: t('Namespaces', 'Namespaces'), value: c.namespaces ?? '—', Icon: Boxes, note: t('doménová izolace', 'domain isolation'), tone: '#059669' },
          { label: t('NetworkPolicies', 'NetworkPolicies'), value: c.networkPolicies ?? '—', Icon: Network, note: t('nasazeno, aktivace probíhá', 'deployed, activation in progress'), tone: '#d97706' },
          { label: t('External Secrets', 'External Secrets'), value: c.externalSecrets ?? '—', Icon: Key, note: t('nic v gitu', 'none in git'), tone: '#059669' },
          { label: t('Admission policies', 'Admission policies'), value: c.clusterPolicies ?? '—', Icon: Shield, note: t('image-verify (Audit)', 'image-verify (Audit)'), tone: '#d97706' },
        ].map(s => (
          <div key={s.label} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
              <s.Icon size={16} style={{ color: s.tone }} />
              <span style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-tertiary)', fontWeight: 700 }}>{s.label}</span>
            </div>
            <div style={{ fontSize: 28, fontWeight: 800, color: 'var(--text-primary)', fontFamily: 'var(--font-mono, monospace)', lineHeight: 1 }}>{s.value}</div>
            <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 4 }}>{s.note}</div>
          </div>
        ))}
      </div>

      {/* ── 1) Namespace map ── */}
      <SectionTitle icon={Boxes} title={t('1 · Mapa namespaces — „patra banky"', '1 · Namespace map — "floors of the bank"')} />
      <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 14, maxWidth: 760 }}>
        {t('Každá doména běží ve vlastním zapečeném namespace. Klikni na kostku pro roli a stav izolace.', 'Each domain runs in its own sealed namespace. Click a tile for its role and isolation state.')}
      </p>
      <div style={{ display: 'grid', gap: 18, marginBottom: 32 }}>
        {(topo?.groups ?? []).map(g => {
          const GI = ICONS[g.icon] ?? Box
          const items = nsByGroup[g.id] ?? []
          if (!items.length) return null
          return (
            <div key={g.id}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <span style={{ width: 26, height: 26, borderRadius: 7, background: `${g.color}1a`, border: `1px solid ${g.color}55`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <GI size={14} style={{ color: g.color }} />
                </span>
                <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>{language === 'cs' ? g.label : g.labelEn}</span>
                <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>· {items.length}</span>
              </div>
              <p style={{ fontSize: 12, color: 'var(--text-tertiary)', margin: '0 0 8px 34px' }}>{g.blurb}</p>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(180px,1fr))', gap: 8, paddingLeft: 34 }}>
                {items.map(nsItem => {
                  const on = openNs === nsItem.name
                  const panelId = `cluster-ns-panel-${nsItem.name.replace(/[^a-zA-Z0-9_-]/g, '-')}`
                  return (
                    <button key={nsItem.name} onClick={() => setOpenNs(on ? null : nsItem.name)} type="button"
                      aria-expanded={on} aria-controls={on ? panelId : undefined} style={{
                      textAlign: 'left', cursor: 'pointer', padding: '10px 12px', borderRadius: 9,
                      background: on ? `${g.color}10` : 'var(--surface)', border: `1px solid ${on ? g.color : 'var(--border)'}`,
                      transition: 'all .12s',
                    }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                        <span style={{ width: 8, height: 8, borderRadius: 2, background: g.color, flexShrink: 0, transform: 'rotate(45deg)' }} />
                        <code style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'var(--font-mono, monospace)' }}>{nsItem.name}</code>
                      </div>
                      {on && (
                        <div id={panelId} role="region" aria-label={nsItem.name} style={{ marginTop: 6, fontSize: 11.5, color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                          {nsItem.role}
                          <div style={{ marginTop: 4, fontSize: 10.5, color: 'var(--text-tertiary)' }}>
                            {t('Izolace: ', 'Isolation: ')}{(c.networkPolicies ?? 0) > 0 ? t('NetworkPolicy nasazeny, fleet-wide aktivace probíhá (#854)', 'NetworkPolicies deployed, fleet-wide activation in progress (#854)') : t('zatím bez NetworkPolicy', 'no NetworkPolicy yet')}
                          </div>
                        </div>
                      )}
                    </button>
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>

      {/* ── 2) Defense in depth ── */}
      <SectionTitle icon={Shield} title={t('2 · Obrana do hloubky — „vrstvy ochranky"', '2 · Defense in depth — "layers of security"')} />
      <div style={{ display: 'grid', gridTemplateColumns: '240px minmax(0,1fr)', gap: 24, alignItems: 'center', marginBottom: 32 }}>
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          {topo && <DefenseRings layers={topo.securityLayers} active={activeLayer} onPick={setActiveLayer} lang={language} />}
        </div>
        <div style={{ display: 'grid', gap: 8 }}>
          {(topo?.securityLayers ?? []).map((l, i) => {
            const LI = ICONS[l.icon] ?? Shield
            const on = activeLayer === l.id
            return (
              <div key={l.id} className="card" style={{ padding: 14, borderLeft: `3px solid ${STATUS[l.status].color}`, boxShadow: on ? '0 0 0 1px var(--accent)' : undefined }}>
                <div role="button" tabIndex={0} aria-expanded={on} aria-label={`${l.label} — ${on ? t('Sbalit vrstvu', 'Collapse layer') : t('Rozbalit vrstvu', 'Expand layer')}`}
                  onClick={() => setActiveLayer(current => current === l.id ? null : l.id)}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setActiveLayer(current => current === l.id ? null : l.id) } }}
                  style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: on ? 8 : 0, cursor: 'pointer' }}>
                  <span style={{ fontFamily: 'var(--font-mono,monospace)', fontSize: 11, color: 'var(--text-tertiary)', width: 18 }}>{i + 1}</span>
                  <LI size={16} style={{ color: K8S_BLUE }} />
                  <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)', flex: 1 }}>{l.label}</span>
                  <StatusPill s={l.status} lang={language} />
                  <ChevronRight size={14} style={{ color: 'var(--text-tertiary)', transform: on ? 'rotate(90deg)' : 'none', transition: 'transform .15s' }} />
                </div>
                {on && (
                  <div style={{ paddingLeft: 28 }}>
                    <p style={{ fontSize: 12, fontStyle: 'italic', color: K8S_BLUE, margin: '0 0 6px' }}>🛡️ {l.analogy}</p>
                    <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.55, margin: '0 0 8px' }}>{l.summary}</p>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                      {l.controls.map(ct => (
                        <span key={ct} style={{ fontSize: 11, color: 'var(--text-secondary)', background: 'var(--surface-2)', border: '1px solid var(--border)', padding: '2px 8px', borderRadius: 5 }}>{ct}</span>
                      ))}
                      <AdrRefs adr={l.adr} />
                      {l.detailRoute?.startsWith('/') && <a href={l.detailRoute} style={{ fontSize: 11, color: K8S_BLUE }}>→ {t('detail', 'detail')}</a>}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      {/* ── 3) Container anatomy ── */}
      <div id="image" />
      <SectionTitle icon={Box} title={t('3 · Anatomie image — „zapečetěná zásilka"', '3 · Image anatomy — "a sealed shipment"')} />
      <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 14, maxWidth: 760 }}>
        {t('Jak je poskládaný a zabezpečený výchozí image každé služby. Klikni na vrstvu kontejneru.', 'How every service image is built and hardened. Click a container layer.')}
      </p>
      <div style={{ marginBottom: 32 }}>
        {topo && <ContainerAnatomy anatomy={topo.imageAnatomy} lang={language} />}
      </div>

      {/* ── 4) Plan vs reality ── */}
      <SectionTitle icon={AlertTriangle} title={t('4 · Plán vs. realita', '4 · Plan vs reality')} />
      <div className="card" style={{ padding: 0, overflow: 'hidden', marginBottom: 24 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ textAlign: 'left', color: 'var(--text-secondary)', background: 'var(--surface-2)' }}>
              <th style={{ padding: '8px 14px' }}>{t('Kontrola', 'Control')}</th>
              <th style={{ padding: '8px 14px' }}>{t('Plán', 'Plan')}</th>
              <th style={{ padding: '8px 14px' }}>{t('Realita', 'Reality')}</th>
              <th style={{ padding: '8px 14px' }}>{t('Stav', 'Status')}</th>
            </tr>
          </thead>
          <tbody>
            {(topo?.planVsReality ?? []).map(r => (
              <tr key={r.item} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ padding: '9px 14px', fontWeight: 600, color: 'var(--text-primary)' }}>{r.item}</td>
                <td style={{ padding: '9px 14px', color: 'var(--text-secondary)' }}>{r.plan}</td>
                <td style={{ padding: '9px 14px', color: 'var(--text-secondary)' }}>{r.reality}</td>
                <td style={{ padding: '9px 14px' }}><StatusPill s={r.status} lang={language} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p style={{ fontSize: 11, color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: 6 }}>
        <FileText size={12} />
        {t('Odvozeno z GitOpsu + reprezentativního Dockerfile při buildu (ADR-0081). Žádná data ručně — gapy se zobrazují poctivě.', 'Derived from GitOps + a representative Dockerfile at build (ADR-0081). No hand-typed data — gaps shown honestly.')}
        {topo?.generatedAt && <span> · {new Date(topo.generatedAt).toLocaleString(dateLocale)}</span>}
      </p>
    </div>
  )
}

function SectionTitle({ icon: Icon, title }: { icon: React.ElementType; title: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '8px 0 4px' }}>
      <Icon size={17} style={{ color: K8S_BLUE }} />
      <h2 style={{ fontSize: 17, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{title}</h2>
      <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
    </div>
  )
}
