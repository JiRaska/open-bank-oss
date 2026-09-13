// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { Smartphone, HelpCircle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'

// Reads notification-service's `GET /api/v1/devices?partyId=` (DeviceResource, ROLE_VIEWER-readable)
// through the ADR-0056 BFF proxy — same operator-token relay + backend RBAC as AdverseStatePanel, no
// new BFF route needed since notification-service is already in the proxy's SERVICE_MAP.
//
// DeviceResource deliberately never returns the push token itself (PII-adjacent, write-only) — only
// platform/appVersion/osVersion/status and three timestamps. There is no "last login" anywhere in the
// fleet (no session/auth tracking service); `refreshedAt` is the closest proxy — it advances on every
// foreground token refresh (ADR-0135 §2), so it reads as "last time the app was open", not a login event.
// The panel says so rather than labelling it "last login".

interface DeviceView {
  id: string
  platform: string
  appInstance: string
  appVersion: string | null
  osVersion: string | null
  status: string
  registeredAt: string
  refreshedAt: string | null
  lastUsedAt: string | null
}

interface DevicesResponse {
  items: DeviceView[]
  total: number
}

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; devices: DeviceView[] }
  | { kind: 'unknown'; why: BffFailure }

export function DevicesPanel({ partyId }: { partyId: string }) {
  const { t, language } = useLanguage()
  const [state, setState] = useState<State>({ kind: 'loading' })

  // No loading reset here either — same reasoning as AdverseStatePanel: the parent mounts this with
  // `key={partyId}`, so a new selection is a fresh component already starting from `loading`.
  useEffect(() => {
    let live = true
    ;(async () => {
      try {
        const res = await fetch(svcUrl('notification-service', '/api/v1/devices', { partyId }), {
          cache: 'no-store',
        })
        if (!res.ok) {
          const why = await classifyBffFailure(res)
          if (live) setState({ kind: 'unknown', why })
          return
        }
        const body = (await res.json()) as DevicesResponse
        if (live) setState({ kind: 'ok', devices: body.items ?? [] })
      } catch {
        if (live) setState({ kind: 'unknown', why: 'unreachable' })
      }
    })()
    return () => {
      live = false
    }
  }, [partyId])

  const fmt = (iso: string | null) =>
    iso
      ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' })
          .format(new Date(iso.replace(' ', 'T') + (iso.endsWith('Z') ? '' : 'Z')))
      : t('nikdy', 'never')

  return (
    <div className="card" style={{ padding: '16px 20px', marginBottom: '20px' }}>
      <h2 className="section-title" style={{ marginBottom: '4px' }}>
        {t('Zařízení', 'Devices')}
      </h2>
      <p style={{ margin: '0 0 12px', fontSize: '11px', color: 'var(--text-secondary)' }}>
        {t(
          'Zdroj: notification-service, registr push tokenů. Token samotný se nikdy nevrací. „Naposledy aktivní“ je čas posledního obnovení tokenu appkou na popředí — appka nemusí evidovat přihlášení, jde o nejbližší dostupný signál, ne o skutečné přihlášení.',
          'Source: notification-service push-token registry. The token itself is never returned. "Last active" is the last foreground token refresh — there is no login/session tracking anywhere in the fleet, this is the closest available signal, not a real login event.',
        )}
      </p>

      {state.kind === 'loading' && (
        <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('Načítám…', 'Loading…')}</span>
      )}

      {state.kind === 'ok' && state.devices.length === 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: 'var(--text-secondary)' }}>
          <Smartphone size={16} />
          <span>{t('Žádná registrovaná zařízení', 'No registered devices')}</span>
        </div>
      )}

      {state.kind === 'ok' && state.devices.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table className="table">
            <thead>
              <tr>
                <th>{t('Platforma', 'Platform')}</th>
                <th>{t('Aplikace', 'App')}</th>
                <th>{t('Stav', 'Status')}</th>
                <th>{t('Registrováno', 'Registered')}</th>
                <th>{t('Naposledy aktivní', 'Last active')}</th>
              </tr>
            </thead>
            <tbody>
              {state.devices.map(d => (
                <tr key={d.id}>
                  <td style={{ fontWeight: 600 }}>{d.platform}</td>
                  <td style={{ fontSize: '11px' }}>
                    {d.appVersion ?? '—'}{d.osVersion ? ` · ${d.osVersion}` : ''}
                  </td>
                  <td><StatusBadge status={d.status} withDot /></td>
                  <td>{fmt(d.registeredAt)}</td>
                  <td>{fmt(d.refreshedAt ?? d.lastUsedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {state.kind === 'unknown' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: 'var(--text-secondary)' }}>
          <HelpCircle size={16} />
          <span>
            {t(
              `Zařízení nelze zjistit (notification-service: ${state.why}).`,
              `Devices unavailable (notification-service: ${state.why}).`,
            )}
          </span>
        </div>
      )}
    </div>
  )
}
