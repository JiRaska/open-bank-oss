// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Lifecycle & vulnerability strip for an /infrastructure component card (ADR-0079).
// Shows running version, EoL/LTS/support horizon, CVE posture, what's-new, and a
// "Plan upgrade" action that drafts a proposal into the agent approval queue (ADR-0031).

import { useState } from 'react'
import { ShieldAlert, ShieldCheck, Clock, ArrowUpCircle, ExternalLink, ClipboardPlus, Loader2, Check } from 'lucide-react'

export type Urgency = 'current' | 'patch-available' | 'major-available' | 'vulnerable' | 'eol-soon' | 'eol' | 'unknown'

export interface CompLifecycle {
  id: string
  running: { version: string | null; source: string }
  lifecycle:
    | {
        available: boolean
        product: string | null
        cycle: string | null
        isLts: boolean
        eol: string | null
        eolPassed: boolean
        eolDaysLeft: number | null
        support: string | boolean | null
        latestInCycle: string | null
        newestVersion: string | null
        newestCycle: string | null
      }
    | { available: false; product: null; reason: string }
  upgrade: { patchAvailable: boolean; majorAvailable: boolean; target: string | null; releaseNotesUrl: string | null }
  cve: { scanned: boolean; critical: number; high: number; medium: number; low: number; total: number; top: { id: string; severity: string; fixedIn?: string | null }[] }
  urgency: Urgency
}

const URGENCY: Record<Urgency, { cs: string; en: string; color: string; bg: string }> = {
  current:           { cs: 'Aktuální',          en: 'Up to date',     color: '#059669', bg: '#ecfdf5' },
  'patch-available': { cs: 'Dostupná záplata',  en: 'Patch available',color: '#d97706', bg: '#fffbeb' },
  'major-available': { cs: 'Nový major',        en: 'New major',      color: '#2563eb', bg: '#eff6ff' },
  vulnerable:        { cs: 'Zranitelnosti',     en: 'Vulnerable',     color: '#dc2626', bg: '#fef2f2' },
  'eol-soon':        { cs: 'Blíží se EoL',      en: 'EoL approaching',color: '#dc2626', bg: '#fef2f2' },
  eol:               { cs: 'Po konci podpory',  en: 'Past EoL',       color: '#991b1b', bg: '#fef2f2' },
  unknown:           { cs: 'Neznámé',           en: 'Unknown',        color: '#6b7280', bg: '#f3f4f6' },
}

type T = (cs: string, en: string) => string

function fmtDate(iso: string, locale: string): string {
  return new Date(iso).toLocaleDateString(locale, { year: 'numeric', month: 'short' })
}

export function LifecycleStrip({ data, name, t, dateLocale = 'en-GB' }: { data: CompLifecycle; name: string; t: T; dateLocale?: string }) {
  const [draft, setDraft] = useState<'idle' | 'busy' | 'done' | 'err'>('idle')
  const u = URGENCY[data.urgency]
  const lc = data.lifecycle
  const has = lc.available
  const running = data.running.version

  const planUpgrade = async () => {
    setDraft('busy')
    const target = data.upgrade.target
    const cveBit = data.cve.scanned && data.cve.critical + data.cve.high > 0
      ? ` It carries ${data.cve.critical} critical and ${data.cve.high} high CVEs.`
      : ''
    const eolBit = has && 'eolPassed' in lc && lc.eolPassed
      ? ' The running version is PAST end-of-life (unsupported).'
      : has && 'eolDaysLeft' in lc && lc.eolDaysLeft != null && lc.eolDaysLeft <= 90
        ? ` End-of-life is in ${lc.eolDaysLeft} days.` : ''
    try {
      const res = await fetch('/api/agent/mcp', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0', id: 1, method: 'tools/call',
          params: {
            name: 'draft_ticket',
            arguments: {
              title: `Upgrade ${name} ${running ?? '?'}${target ? ` → ${target}` : ''}`,
              rationale: `${name} is running ${running ?? 'an unknown version'}.${eolBit}${cveBit}`.trim(),
              suggested_action: target
                ? `Plan and roll out the upgrade to ${target}. Review release notes, test in staging, then schedule a maintenance window.`
                : `Review the current version against its support lifecycle and plan remediation.`,
            },
          },
        }),
      })
      const j = await res.json()
      setDraft(j?.error ? 'err' : 'done')
    } catch {
      setDraft('err')
    }
  }

  const showUpgrade = data.upgrade.patchAvailable || data.upgrade.majorAvailable || data.urgency === 'eol' || data.urgency === 'eol-soon'

  return (
    <div style={{ marginTop: 8, paddingTop: 8, borderTop: '1px solid var(--border)', display: 'grid', gap: 6 }}>
      {/* version + urgency */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 11, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{t('Verze', 'Version')}</span>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'monospace' }} title={data.running.source}>
          {running ?? t('neznámá', 'unknown')}
        </span>
        <span style={{ marginLeft: 'auto', fontSize: 10, fontWeight: 700, color: u.color, background: u.bg, border: `1px solid ${u.color}33`, padding: '2px 8px', borderRadius: 20 }}>
          {t(u.cs, u.en)}
        </span>
      </div>

      {/* lifecycle line */}
      {has ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: 11, color: 'var(--text-secondary)' }}>
          {lc.isLts && (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3, color: '#059669', fontWeight: 700 }}>
              <ShieldCheck size={12} /> LTS
            </span>
          )}
          {'eol' in lc && lc.eol ? (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3, color: lc.eolPassed || (lc.eolDaysLeft != null && lc.eolDaysLeft <= 90) ? '#dc2626' : 'var(--text-secondary)' }}>
              <Clock size={12} />
              {lc.eolPassed
                ? t('Po EoL', 'Past EoL')
                : `${t('Podpora do', 'Supported to')} ${fmtDate(lc.eol, dateLocale)}${lc.eolDaysLeft != null ? ` · ~${lc.eolDaysLeft} ${t('dní', 'days')}` : ''}`}
            </span>
          ) : (
            <span style={{ color: 'var(--text-tertiary)' }}>{t('EoL nestanoveno', 'No EoL set')}</span>
          )}
          {'latestInCycle' in lc && lc.latestInCycle && (
            <span style={{ marginLeft: 'auto', fontFamily: 'monospace', color: 'var(--text-tertiary)' }}>
              {t('nejnov.', 'latest')} {lc.latestInCycle}{lc.newestVersion && lc.newestVersion !== lc.latestInCycle ? ` · ${lc.newestVersion}` : ''}
            </span>
          )}
        </div>
      ) : (
        <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Lifecycle data: N/A (žádný veřejný feed)', 'Lifecycle data: N/A (no public feed)')}</div>
      )}

      {/* CVE line */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11 }}>
        {!data.cve.scanned ? (
          <span style={{ color: 'var(--text-tertiary)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <ShieldAlert size={12} /> {t('CVE: zatím nesken.', 'CVE: not yet scanned')}
          </span>
        ) : data.cve.total === 0 ? (
          <span style={{ color: '#059669', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <ShieldCheck size={12} /> {t('Žádné známé CVE', 'No known CVEs')}
          </span>
        ) : (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: data.cve.critical + data.cve.high > 0 ? '#dc2626' : 'var(--text-secondary)' }}>
            <ShieldAlert size={12} />
            {data.cve.critical > 0 && <b>{data.cve.critical} CRIT</b>}
            {data.cve.high > 0 && <b>{data.cve.high} HIGH</b>}
            <span style={{ color: 'var(--text-tertiary)' }}>{data.cve.medium}M · {data.cve.low}L</span>
          </span>
        )}
      </div>

      {/* actions */}
      {(showUpgrade || data.upgrade.releaseNotesUrl) && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginTop: 2 }}>
          {data.upgrade.releaseNotesUrl && (
            <a href={data.upgrade.releaseNotesUrl} target="_blank" rel="noreferrer"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--accent)', textDecoration: 'none' }}>
              <ExternalLink size={12} /> {data.upgrade.target ? `${t('Co je nového v', "What's new in")} ${data.upgrade.target}` : t('Release notes', 'Release notes')}
            </a>
          )}
          {showUpgrade && (
            <button onClick={planUpgrade} disabled={draft === 'busy' || draft === 'done'}
              style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 600, padding: '4px 10px', borderRadius: 6, cursor: draft === 'done' ? 'default' : 'pointer',
                border: `1px solid ${draft === 'done' ? '#6ee7b7' : 'var(--border)'}`, background: draft === 'done' ? '#ecfdf5' : 'var(--surface)', color: draft === 'done' ? '#059669' : draft === 'err' ? '#dc2626' : 'var(--text-primary)' }}>
              {draft === 'busy' ? <Loader2 size={12} className="animate-spin" /> : draft === 'done' ? <Check size={12} /> : <ClipboardPlus size={12} />}
              {draft === 'done' ? t('Návrh ve frontě', 'Queued') : draft === 'err' ? t('Chyba', 'Error') : <><ArrowUpCircle size={12} style={{ display: 'none' }} />{t('Naplánovat upgrade', 'Plan upgrade')}</>}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
