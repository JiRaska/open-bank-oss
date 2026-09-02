// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Final review before a DevOps-agent remediation finding is approved or rejected (#7895,
// ADR-0031 D4, ADR-0119). Approve/Reject in AgentInsightsPanel used to fire the HITL decision on
// a single click with no confirmation; this dialog is the interposed checkpoint that shows the
// exact finding and its impact before an operator authorizes operational remediation. It does
// not call the decision endpoint itself and does not change its payload or RBAC — the caller
// (devops/page.tsx `decide()`) still owns that; `onConfirm` here only tells the caller to go
// ahead, and the caller keeps `failed` true (dialog stays open) when the call fails.

'use client'

import { useEffect, useRef } from 'react'
import { AlertTriangle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'
import type { DevOpsFinding } from '@/app/api/devops/insights/route'
import { DORA_METRIC_LABEL } from '@/lib/devops/doraMetricLabels'

const SEVERITY_LABEL: Record<DevOpsFinding['severity'], { cs: string; en: string; color: string }> = {
  WARNING: { cs: 'Varování', en: 'Warning', color: '#d97706' },
  CRITICAL: { cs: 'Kritické', en: 'Critical', color: '#dc2626' },
}

const REMEDIATION_KIND_LABEL: Record<DevOpsFinding['remediationKind'], { cs: string; en: string }> = {
  PULL_REQUEST: { cs: 'Pull request', en: 'Pull request' },
  RUNBOOK_UPDATE: { cs: 'Aktualizace runbooku', en: 'Runbook update' },
  TICKET: { cs: 'Tiket', en: 'Ticket' },
  NONE: { cs: 'Žádná', en: 'None' },
}

export function RemediationReviewDialog({
  finding, approve, busy, failed, onCancel, onConfirm,
}: {
  finding: DevOpsFinding
  approve: boolean
  busy: boolean
  failed: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const action = approve ? t('Schválit nález', 'Approve finding') : t('Zamítnout nález', 'Reject finding')
  const titleId = `devops-decision-${finding.id}-title`
  const impactId = `devops-decision-${finding.id}-impact`
  const sev = SEVERITY_LABEL[finding.severity]
  const remediation = REMEDIATION_KIND_LABEL[finding.remediationKind]
  const dora = finding.doraMetricImpacted ? DORA_METRIC_LABEL[finding.doraMetricImpacted] : null

  // No text field to carry autoFocus (unlike the /approvals reason textarea) — move focus into
  // the dialog itself so a screen reader announces it and Escape/Tab work from the first render.
  useEffect(() => { dialogRef.current?.focus() }, [])

  return (
    <div
      ref={dialogRef}
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={impactId}
      aria-busy={busy}
      tabIndex={-1}
      onKeyDown={event => {
        if (event.key === 'Escape' && !busy) onCancel()
        trapDialogFocus(event, dialogRef.current)
      }}
      style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)', display: 'grid', placeItems: 'center', padding: 20 }}
    >
      <div className="card" style={{ width: 'min(560px, 100%)', maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}>
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
          <AlertTriangle aria-hidden="true" size={19} style={{ color: approve ? 'var(--warning)' : 'var(--danger)', flexShrink: 0, marginTop: 2 }} />
          <div>
            <h2 id={titleId} style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>{action}: {finding.title}</h2>
            <p id={impactId} style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              {approve
                ? t(
                  'Tímto zaznamenáte lidské schválení navrhované nápravy. Potvrzení může spustit operační zásah.',
                  'This records human approval of the proposed remediation. Confirming may trigger operational action.',
                )
                : t(
                  'Tímto zaznamenáte lidské zamítnutí navrhované nápravy. Náprava se neprovede.',
                  'This records human rejection of the proposed remediation. The remediation will not proceed.',
                )}
            </p>
          </div>
        </div>

        <div style={{ marginTop: 14, padding: '11px 12px', borderRadius: 8, background: 'var(--surface-2)', border: '1px solid var(--border)', fontSize: 12.5, display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div><strong>{t('ID nálezu', 'Finding ID')}:</strong> <span style={{ fontFamily: 'monospace' }}>{finding.id}</span></div>
          <div><strong>{t('Detektor', 'Detector')}:</strong> <span style={{ fontFamily: 'monospace' }}>{finding.detector}</span></div>
          <div><strong>{t('Závažnost', 'Severity')}:</strong> <span style={{ color: sev.color, fontWeight: 700 }}>{t(sev.cs, sev.en)}</span></div>
          <div><strong>{t('Stav', 'Status')}:</strong> {finding.status}</div>
          {finding.rootCause && (
            <div><strong>{t('Kořenová příčina', 'Root cause')}:</strong> {finding.rootCause}</div>
          )}
          <div><strong>{t('Druh nápravy', 'Remediation kind')}:</strong> {t(remediation.cs, remediation.en)}</div>
          {dora && (
            <div><strong>{t('Dopad na DORA', 'DORA impact')}:</strong> {t(dora.cs, dora.en)}</div>
          )}
          {finding.proposalPrUrl && (
            <div>
              <strong>{t('Návrh', 'Proposal')}:</strong>{' '}
              <a href={finding.proposalPrUrl} target="_blank" rel="noopener noreferrer" style={{ color: '#6366f1' }}>
                {t('Zobrazit návrh →', 'View proposal →')}
              </a>
            </div>
          )}
        </div>

        {failed && (
          <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>
            {t(
              'Rozhodnutí se nepodařilo uložit. Pravděpodobně nemáte oprávnění nebo je služba nedostupná.',
              'The decision could not be saved. You may not have permission or the service is unavailable.',
            )}
          </p>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
          <button type="button" className="btn btn-secondary" disabled={busy} onClick={onCancel}>
            {t('Zpět ke kontrole', 'Back to review')}
          </button>
          <button
            type="button"
            className={approve ? 'btn btn-primary' : 'btn btn-danger'}
            disabled={busy}
            aria-busy={busy}
            onClick={onConfirm}
          >
            {busy
              ? t('Ukládám rozhodnutí…', 'Recording decision…')
              : approve ? t('Potvrdit schválení', 'Confirm approval') : t('Potvrdit zamítnutí', 'Confirm rejection')}
          </button>
        </div>
      </div>
    </div>
  )
}
