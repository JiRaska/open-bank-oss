// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, Lock, Users, FileText, Clock, Sparkles, Hand, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useAuth } from '@/lib/auth/useAuth'
import { ROLES } from '@/lib/auth/roles'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AgentPortrait, getAgentPersona } from '@/components/agent/AgentIdentity'
import { AgentBodyAnalysis, AgentMeshMap } from '@/components/agent/AgentDiagnostics'
import type { AgentDiagnostic, AgentMeshSummary } from '@/lib/governance/agentDiagnostics'
import { OutcomeMetricsCard } from '@/components/agent/AgentOutcomes'
import { PageHeader } from '@/components/ui/PageHeader'

// ── Types (mirror /api/iaops/agents/[agentId]) ─────────────────────────────
interface Schedule { daily: string | null; reactive: string | null }
interface Charter {
  id: string; plane: string; charter: string; owns: string[]; skills: string[]
  dataRead: string[]; pii: string; toolsAllow: string[]; toolsDeny: string[]
  requiresHuman: string[]; tokensPerRun: number | null; runsPerDay: number | null
  caseCapabilities: string[]
  schedule: Schedule | null
}
interface Narrative { title: string; adr: string; plane: string; body: string }
interface ProposalSummary { id: string; title: string; state: string; proposedAt: string; decidedAt: string | null }
interface AgentDetail {
  id: string
  charter: Charter | null
  narrative: Narrative | null
  diagnostics: AgentDiagnostic[]
  mesh: AgentMeshSummary | null
  proposals: { available: boolean; items: ProposalSummary[]; pendingCount: number }
}

// ── Narrative body renderer ─────────────────────────────────────────────────
// docs/agents/<id>.md is authored as "## Section" blocks with soft (mid-paragraph)
// line wraps for source readability and light inline markdown (`code`, **bold**).
// No markdown library — the source is small and controlled (our own charter docs),
// so a compact parser covers it: split into "## "-headed sections, each section into
// blank-line-separated blocks, each block into either a "- " bullet list or a
// flowing paragraph.
interface NarrativeBlock { type: 'p' | 'ul'; text?: string; items?: string[] }
interface NarrativeSection { heading: string; blocks: NarrativeBlock[] }

function parseNarrativeSections(body: string): NarrativeSection[] {
  return body
    .trim()
    .split(/\n(?=##\s)/)
    .map(part => {
      const headingMatch = part.match(/^##\s*(.+?)\s*\n/)
      const heading = headingMatch ? headingMatch[1].trim() : ''
      const content = (headingMatch ? part.slice(headingMatch[0].length) : part).trim()
      const blocks: NarrativeBlock[] = content
        .split(/\n{2,}/)
        .filter(Boolean)
        .map(chunk => {
          if (/^-\s/.test(chunk.trim())) {
            const items = chunk
              .split(/\n(?=-\s)/)
              .map(li => li.replace(/^-\s*/, '').replace(/\n\s*/g, ' ').trim())
            return { type: 'ul' as const, items }
          }
          return { type: 'p' as const, text: chunk.replace(/\n/g, ' ').trim() }
        })
      return { heading, blocks }
    })
    .filter(s => s.blocks.length > 0)
}

// Inline `code` and **bold** — the only two markers used in docs/agents/*.md.
function renderInline(text: string, keyPrefix: string): React.ReactNode[] {
  return text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).map((part, i) => {
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={`${keyPrefix}-${i}`} style={{ fontFamily: 'monospace', fontSize: '11px', background: 'var(--surface-3)', padding: '1px 4px', borderRadius: '4px' }}>{part.slice(1, -1)}</code>
    }
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={`${keyPrefix}-${i}`}>{part.slice(2, -2)}</strong>
    }
    return part
  })
}

function NarrativeSections({ body }: { body: string }) {
  const sections = parseNarrativeSections(body)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      {sections.map((s, si) => (
        <div key={s.heading || si}>
          {s.heading && (
            <div style={{ fontSize: '12px', fontWeight: 700, color: '#6366f1', marginBottom: '6px' }}>{s.heading}</div>
          )}
          {s.blocks.map((b, bi) => b.type === 'ul' ? (
            <ul key={bi} style={{ margin: '0 0 8px', paddingLeft: '16px' }}>
              {(b.items ?? []).map((item, ii) => (
                <li key={ii} style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.6, marginBottom: '4px' }}>
                  {renderInline(item, `${si}-${bi}-${ii}`)}
                </li>
              ))}
            </ul>
          ) : (
            <p key={bi} style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.6, margin: '0 0 8px' }}>
              {renderInline(b.text ?? '', `${si}-${bi}`)}
            </p>
          ))}
        </div>
      ))}
    </div>
  )
}

function Chips({ items, tone }: { items: string[]; tone: 'allow' | 'deny' | 'neutral' }) {
  const map = {
    allow:   { color: '#16a34a', bg: '#dcfce7' },
    deny:    { color: '#dc2626', bg: '#fee2e2' },
    neutral: { color: 'var(--text-secondary)', bg: 'var(--surface-2)' },
  }[tone]
  if (!items.length) return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
      {items.map(i => (
        <span key={i} style={{ fontSize: '10px', fontFamily: 'monospace', padding: '1px 6px', borderRadius: '6px',
          color: map.color, background: map.bg }}>{i}</span>
      ))}
    </div>
  )
}

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ background: 'var(--surface)', border: '1px solid var(--border)',
      borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
      {children}
    </div>
  )
}
const STATE_PILL: Record<string, { color: string; bg: string }> = {
  PROPOSED: { color: '#d97706', bg: '#fef9c3' },
  APPROVED: { color: '#16a34a', bg: '#dcfce7' },
  REJECTED: { color: '#dc2626', bg: '#fee2e2' },
}

function AgentDetailContent() {
  const params = useParams<{ agentId: string }>()
  const agentId = params.agentId
  const { t, language } = useLanguage()
  const { hasRole } = useAuth()
  const [data, setData] = useState<AgentDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch(`/api/iaops/agents/${encodeURIComponent(agentId)}`, { cache: 'no-store' })
      if (res.status === 404) { setUnavailable({ kind: 'not_found' }); return }
      if (!res.ok) { setUnavailable({ kind: 'error' }); return }
      setData(await res.json())
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  if (unavailable) {
    return <DataUnavailable kind={unavailable.kind} service={agentId} feature={t('agent charter', 'agent charter')} lang={language} />
  }

  const persona = getAgentPersona(agentId, language)

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1100px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/iaops" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('IAOps', 'IAOps')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{agentId}</span></div>}
        icon={<Users size={20} aria-hidden="true" />}
        title={persona.name}
        subtitle={persona.role}
        actions={<Link href="/iaops" className="btn btn-secondary btn-sm"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět na přehled agentů', 'Back to agent roster')}</Link>}
      />

      {loading && !data ? (
        <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám agenta…', 'Loading agent…')}</span>
        </div>
      ) : data ? (
        <>
          {/* Header */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '18px', marginBottom: '20px', flexWrap: 'wrap' }}>
            <AgentPortrait agentId={agentId} />
            <div style={{ flex: 1, minWidth: '200px' }}>
              <p style={{ fontSize: '13px', fontWeight: 750, color: 'var(--text-primary)', margin: '1px 0 3px' }}>
                {persona.role}
              </p>
              <p style={{ fontSize: '10px', color: 'var(--text-tertiary)', margin: 0, fontFamily: 'monospace' }}>
                {data.id} · {(data.charter?.plane ?? data.narrative?.plane ?? '—')} · {data.narrative?.adr ?? '—'}
              </p>
            </div>
            {data.proposals.pendingCount > 0 && (
              <span style={{ fontSize: '11px', fontWeight: 700, padding: '4px 10px', borderRadius: '10px',
                background: '#fef9c3', color: '#92400e' }}>
                {t(`${data.proposals.pendingCount} čeká na schválení`, `${data.proposals.pendingCount} pending approval`)}
              </span>
            )}
          </div>

          {!data.charter && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 14px', borderRadius: '8px',
              background: 'var(--surface-2)', border: '1px solid var(--border)', marginBottom: '16px', fontSize: '12px', color: 'var(--text-secondary)' }}>
              {t('agents.yaml nebylo v image nalezeno — zobrazuje se jen narativní charter.', 'agents.yaml was not found in the image — showing the narrative charter only.')}
            </div>
          )}

          <Card>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', gap: '18px' }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '7px', color: 'var(--text-secondary)', marginBottom: '7px' }}>
                  <Sparkles size={14} style={{ color: persona.accent }} />
                  <span style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '0.05em', textTransform: 'uppercase' }}>
                    {t('Co dělá', 'What this colleague does')}
                  </span>
                </div>
                <p style={{ fontSize: '13px', color: 'var(--text-primary)', lineHeight: 1.6, margin: 0 }}>{persona.purpose}</p>
              </div>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '7px', color: 'var(--text-secondary)', marginBottom: '7px' }}>
                  <Hand size={14} style={{ color: persona.accent }} />
                  <span style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '0.05em', textTransform: 'uppercase' }}>
                    {t('Proč na tom záleží', 'Why it matters')}
                  </span>
                </div>
                <p style={{ fontSize: '13px', color: 'var(--text-primary)', lineHeight: 1.6, margin: 0 }}>{persona.value}</p>
              </div>
            </div>
            <div style={{ borderTop: '1px solid var(--border)', marginTop: '16px', paddingTop: '14px' }}>
              <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '8px' }}>
                {t('Schopnosti lidskou řečí', 'Skills in plain language')}
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                {persona.talents.map(talent => (
                  <span key={talent} style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '7px 10px', borderRadius: '9px',
                    background: `${persona.accent}0d`, border: `1px solid ${persona.accent}25`, color: 'var(--text-secondary)', fontSize: '11px', fontWeight: 700 }}>
                    <Sparkles size={11} style={{ color: persona.accent }} /> {talent}
                  </span>
                ))}
              </div>
            </div>
          </Card>

          {agentId === 'flaky-test-hunter' && hasRole(ROLES.ADMIN) && (
            <Card>
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px', justifyContent: 'space-between', flexWrap: 'wrap' }}>
                <div style={{ minWidth: '240px', flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                    <ShieldCheck size={15} style={{ color: '#0f766e' }} />
                    <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Omezená operátorská kontrola', 'Bounded operator check')}</span>
                  </div>
                  <p style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.55, margin: 0 }}>
                    {t(
                      'Prověří testové zdroje napříč platformou. Pouze případná automatická oprava je omezená na testové soubory tohoto agenta a vznikne jen jako návrh; agent nic nemerguje.',
                      'Scans test source across the platform. Only a possible automated repair is limited to this agent\'s test source and is a proposal only; the agent never merges.',
                    )}
                  </p>
                </div>
                <Link href="/iaops/flaky-test-hunter" className="btn btn-primary btn-sm">
                  {t('Otevřít řízené spuštění', 'Open governed check')}
                </Link>
              </div>
            </Card>
          )}

          {data.charter && data.diagnostics.length > 0 && (
            <AgentBodyAnalysis agentId={agentId} diagnostics={data.diagnostics} language={language} />
          )}

          {data.mesh && (
            <AgentMeshMap agentId={agentId} mesh={data.mesh} language={language} />
          )}

          {/* Tools + operating profile (agents.yaml — enforced fields) */}
          {data.charter && (
            <Card>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                <Lock size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Nástroje a provoz (agents.yaml)', 'Tools and operation (agents.yaml)')}</span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '14px', fontSize: '11px' }}>
                <div>
                  <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Smí', 'Allowed')}</div>
                  <Chips items={data.charter.toolsAllow} tone="allow" />
                </div>
                <div>
                  <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Zakázáno', 'Denied')}</div>
                  <Chips items={data.charter.toolsDeny} tone="deny" />
                </div>
                {data.charter.skills.length > 0 && (
                  <div>
                    <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Skilly (run.skill)', 'Skills (run.skill)')}</div>
                    <Chips items={data.charter.skills} tone="neutral" />
                  </div>
                )}
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '16px', paddingTop: '10px', marginTop: '12px', borderTop: '1px solid var(--border)', color: 'var(--text-tertiary)', fontSize: '11px' }}>
                <span>{t('Limit', 'Budget')}: <strong style={{ color: 'var(--text-secondary)' }}>
                  {data.charter.tokensPerRun ? `${(data.charter.tokensPerRun / 1000).toFixed(0)}k tok/run` : '—'}
                </strong></span>
                <span><strong style={{ color: 'var(--text-secondary)' }}>{data.charter.runsPerDay ?? '—'}</strong> {t('běhů/den', 'runs/day')}</span>
                <span>
                  {t('Spouští se', 'Runs')}: <strong style={{ color: 'var(--text-secondary)' }}>
                    {data.charter.schedule
                      ? [
                          data.charter.schedule.daily && t(`denně ${data.charter.schedule.daily}`, `daily ${data.charter.schedule.daily}`),
                          data.charter.schedule.reactive && t(`reaktivně (${data.charter.schedule.reactive})`, `reactive (${data.charter.schedule.reactive})`),
                        ].filter(Boolean).join(' · ') || '—'
                      : t('na vyžádání / dle scheduleru operátora', 'on demand / per operator schedule')}
                  </strong>
                </span>
              </div>
            </Card>
          )}

          {/* Full narrative charter (docs/agents/<id>.md) — mission, why, human-oversight story, known gaps */}
          {data.narrative && (
            <Card>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '14px' }}>
                <FileText size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Charter — role a chování', 'Charter — role and behaviour')}</span>
              </div>
              <NarrativeSections body={data.narrative.body} />
            </Card>
          )}

          {data.charter && (data.charter.dataRead.length > 0 || data.charter.requiresHuman.length > 0) && (
            <Card>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                <Users size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Datový přístup a dohled', 'Data access and oversight')}</span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '14px' }}>
                <div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '4px' }}>{t('Čte (PII', 'Reads (PII')} {data.charter.pii})</div>
                  <Chips items={data.charter.dataRead} tone="neutral" />
                </div>
                <div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '4px' }}>{t('Vyžaduje člověka', 'Requires human')}</div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                    {data.charter.requiresHuman.map(r => (
                      <span key={r} style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '6px', background: '#fef9c3', color: '#92400e' }}>{r}</span>
                    ))}
                  </div>
                </div>
              </div>
            </Card>
          )}

          {/* Outcome metrics (#4462) — every figure carries its own denominator. */}
          {data.proposals.available && <OutcomeMetricsCard items={data.proposals.items} />}

          {/* Proposal history */}
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
              <Clock size={14} style={{ color: '#6366f1' }} />
              <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Historie návrhů', 'Proposal history')}</span>
            </div>
            <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 14px' }}>
              {t('HITL fronta tohoto agenta (ADR-0031 D4). Rozhodni v ', 'This agent\'s HITL queue (ADR-0031 D4). Decide in ')}
              <Link href="/approvals" style={{ color: '#6366f1' }}>{t('Schvalování', 'Approvals')}</Link>.
            </p>
            {!data.proposals.available ? (
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', padding: '10px 0' }}>
                {t('agent-service není dostupný nebo operátor není přihlášen — historie návrhů nelze načíst.', 'agent-service is unreachable or the operator is not authenticated — proposal history could not be loaded.')}
              </div>
            ) : data.proposals.items.length === 0 ? (
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', padding: '10px 0' }}>
                {t('Tento agent zatím nevytvořil žádný návrh.', 'This agent has not created any proposals yet.')}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                {data.proposals.items.map(p => {
                  const pill = STATE_PILL[p.state] ?? { color: 'var(--text-secondary)', bg: 'var(--surface-2)' }
                  return (
                    <div key={p.id} style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '10px 0', borderBottom: '1px solid var(--border)' }}>
                      <span style={{ flex: 1, fontSize: '12px', color: 'var(--text-primary)' }}>{p.title}</span>
                      <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '8px', color: pill.color, background: pill.bg }}>
                        {p.state}
                      </span>
                      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', whiteSpace: 'nowrap' }}>
                        {new Date(p.proposedAt).toLocaleDateString(language === 'cs' ? 'cs-CZ' : 'en-US')}
                      </span>
                    </div>
                  )
                })}
              </div>
            )}
          </Card>
        </>
      ) : null}
    </div>
  )
}

export default function AgentDetailPage() {
  return (
    <AuthGuard permission="system:view">
      <AgentDetailContent />
    </AuthGuard>
  )
}
