// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Bot, Info } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// ── AgentInsightsPanel — the canonical surface for AI-agent output ─────────────
//
// Every operator page that has a backing AI agent (devops-agent, finops-agent,
// the AML/sanctions oversight agents, future per-domain agents…) renders its
// active findings through THIS component, positioned directly below the page's
// metric/KPI cards (see the admin-ui CLAUDE.md "Agent-output rule"). The agent
// proposes; a human disposes — so the panel optionally surfaces HITL
// approve/reject controls (ADR-0031 D4), and never produces or mutates data
// itself beyond invoking the caller's decision handlers.
//
// The component is intentionally i18n-agnostic: every human-language string is
// passed in already translated by the page (which owns the `t()` context). Each
// page maps its native finding type → the shared `AgentFinding` view-model, so
// the rendering stays identical across pages while the data sources differ.

export type AgentSeverity = 'info' | 'warning' | 'critical'

export interface AgentFindingTag {
  /** Already-translated short label, e.g. "DORA: Lead time" or "$420/mo". */
  label: string
  tone?: 'accent' | 'cyan' | 'success' | 'neutral'
}

export interface AgentFinding {
  id: string
  /** Already-translated title / summary. */
  title: string
  /** Detector code shown as a monospace chip, e.g. "D1_CI_PIPELINE_HEALTH". */
  detector?: string | null
  severity?: AgentSeverity | null
  /** Free-text status; known lifecycle keywords are colour-coded (OPEN/PROPOSED/APPROVED/…). */
  status?: string | null
  /** Already-translated root-cause / diagnosis paragraph. */
  rootCause?: string | null
  /** ISO timestamp; rendered with the browser locale. */
  detectedAt?: string | null
  /** Link to a proposed remediation (PR, runbook). */
  proposalUrl?: string | null
  /** Already-translated label for the proposal link, e.g. "View proposal →". */
  proposalLabel?: string | null
  /** Small chips shown before the status pill (DORA metric, est. saving, remediation kind…). */
  tags?: AgentFindingTag[]
}

export interface AgentInsightsPanelProps {
  /** Already-translated panel title, e.g. "DevOps Insights (AI)". */
  title: string
  /** Already-translated descriptive subtitle. */
  subtitle?: string
  findings: AgentFinding[]
  /** Already-translated message shown when there are no active findings. */
  emptyMessage: string
  /** Header icon; defaults to the Bot glyph used across agent surfaces. */
  icon?: React.ReactNode
  /** Panel border accent colour (default amber — the "needs attention" tone). */
  accentColor?: string
  /** Already-translated right-aligned source/meta line (e.g. "Source: Alertmanager · 09:21"). */
  sourceLabel?: string
  /** Already-translated small notice badge next to the title (e.g. "bridge not deployed"). */
  notice?: string
  /** HITL handlers (ADR-0031 D4). When provided, Approve/Reject buttons render per finding. */
  onApprove?: (id: string) => void
  onReject?: (id: string) => void
  /** Already-translated button labels; defaults to Approve / Reject in English. */
  decideLabels?: { approve: string; reject: string }
  /** Id currently being decided (disables that row's buttons). */
  decidingId?: string | null
}

const SEVERITY_CFG: Record<AgentSeverity, { color: string; bg: string }> = {
  info:     { color: '#2563eb', bg: '#dbeafe' },
  warning:  { color: '#d97706', bg: '#fef3c7' },
  critical: { color: '#dc2626', bg: '#fee2e2' },
}

// Lifecycle status colours — keyed by the uppercased status keyword, so both
// "open" and "OPEN" resolve identically across the different agent backends.
const STATUS_CFG: Record<string, { color: string; bg: string }> = {
  OPEN:      { color: '#2563eb', bg: '#dbeafe' },
  DIAGNOSED: { color: '#0891b2', bg: '#cffafe' },
  PROPOSED:  { color: '#d97706', bg: '#fef3c7' },
  APPROVED:  { color: '#16a34a', bg: '#dcfce7' },
  REJECTED:  { color: '#dc2626', bg: '#fee2e2' },
  RESOLVED:  { color: 'var(--text-secondary)', bg: 'var(--surface-2)' },
}

const TAG_CFG: Record<NonNullable<AgentFindingTag['tone']>, { color: string; bg: string }> = {
  accent:  { color: '#6366f1', bg: '#ede9fe' },
  cyan:    { color: '#0891b2', bg: '#cffafe' },
  success: { color: '#16a34a', bg: '#dcfce7' },
  neutral: { color: 'var(--text-secondary)', bg: 'var(--surface-3)' },
}

export function AgentInsightsPanel({
  title, subtitle, findings, emptyMessage, icon, accentColor = '#d97706',
  sourceLabel, notice, onApprove, onReject, decideLabels, decidingId,
}: AgentInsightsPanelProps) {
  const { language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const approveLabel = decideLabels?.approve ?? 'Approve'
  const rejectLabel = decideLabels?.reject ?? 'Reject'
  const showHitl = Boolean(onApprove || onReject)

  return (
    <div style={{ background: 'var(--surface)', border: `1px solid ${accentColor}40`,
      borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '12px', marginBottom: subtitle ? '6px' : '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
          <span style={{ color: '#6366f1', display: 'flex' }}>{icon ?? <Bot size={16} />}</span>
          <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{title}</span>
          {notice && (
            <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px',
              background: '#fef9c3', color: '#92400e' }}>
              {notice}
            </span>
          )}
        </div>
        {sourceLabel && (
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', flexShrink: 0, textAlign: 'right' }}>
            {sourceLabel}
          </span>
        )}
      </div>
      {subtitle && (
        <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 16px' }}>{subtitle}</p>
      )}

      {findings.length === 0 ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px', borderRadius: '8px',
          background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <Info size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
          <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{emptyMessage}</span>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {findings.map(f => {
            const sev = f.severity ? SEVERITY_CFG[f.severity] : null
            const sc = f.status ? (STATUS_CFG[f.status.toUpperCase()] ?? STATUS_CFG['OPEN']) : null
            return (
              <div key={f.id} style={{ padding: '12px 14px', borderRadius: '10px',
                border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap', marginBottom: f.rootCause ? '6px' : 0 }}>
                  {f.detector && (
                    <span style={{ fontSize: '10px', fontWeight: 800, padding: '2px 7px', borderRadius: '6px',
                      background: '#ede9fe', color: '#6366f1', fontFamily: 'monospace', flexShrink: 0 }}>
                      {f.detector}
                    </span>
                  )}
                  {sev && (
                    <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '6px',
                      color: sev.color, background: sev.bg, flexShrink: 0, textTransform: 'uppercase' }}>
                      {f.severity}
                    </span>
                  )}
                  <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', flex: 1, minWidth: '120px' }}>
                    {f.title}
                  </span>
                  {f.tags?.map((tag, i) => {
                    const tc = TAG_CFG[tag.tone ?? 'cyan']
                    return (
                      <span key={i} style={{ fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '6px',
                        color: tc.color, background: tc.bg, flexShrink: 0 }}>
                        {tag.label}
                      </span>
                    )
                  })}
                  {sc && (
                    <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px',
                      color: sc.color, background: sc.bg, flexShrink: 0 }}>
                      {f.status}
                    </span>
                  )}
                </div>

                {f.rootCause && (
                  <p style={{ fontSize: '11px', color: 'var(--text-secondary)', margin: '0 0 8px', lineHeight: 1.5 }}>
                    {f.rootCause}
                  </p>
                )}

                {(f.proposalUrl || f.detectedAt || showHitl) && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    {f.proposalUrl && (
                      <a href={f.proposalUrl} target="_blank" rel="noopener noreferrer"
                        style={{ fontSize: '11px', fontWeight: 700, color: '#6366f1', textDecoration: 'none' }}>
                        {f.proposalLabel ?? 'View proposal →'}
                      </a>
                    )}
                    {f.detectedAt && (
                      <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginLeft: 'auto' }}>
                        {new Date(f.detectedAt).toLocaleString(dateLocale)}
                      </span>
                    )}
                    {showHitl && (
                      <>
                        {onApprove && (
                          <button
                            onClick={() => onApprove(f.id)}
                            disabled={decidingId === f.id}
                            style={{ fontSize: '11px', fontWeight: 700, padding: '4px 12px', borderRadius: '8px',
                              border: 'none', background: '#16a34a', color: '#fff', marginLeft: f.detectedAt ? 0 : 'auto',
                              cursor: decidingId === f.id ? 'wait' : 'pointer', opacity: decidingId === f.id ? 0.6 : 1 }}>
                            {approveLabel}
                          </button>
                        )}
                        {onReject && (
                          <button
                            onClick={() => onReject(f.id)}
                            disabled={decidingId === f.id}
                            style={{ fontSize: '11px', fontWeight: 700, padding: '4px 12px', borderRadius: '8px',
                              border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
                              cursor: decidingId === f.id ? 'wait' : 'pointer', opacity: decidingId === f.id ? 0.6 : 1 }}>
                            {rejectLabel}
                          </button>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
