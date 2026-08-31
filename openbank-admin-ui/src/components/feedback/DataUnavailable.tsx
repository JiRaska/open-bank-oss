// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One reusable "this data isn't here right now" panel for the whole admin UI.
//
// Why this exists: nearly every page fetches from the BFF (`/api/svc/...`) or an
// admin-ui-internal API (`/api/test-results`, `/api/services/.../sbom`). In the
// sandbox much of the fleet isn't deployed, so those calls legitimately
// fail (404 "Unknown service", 502 upstream, or an internal route with no data).
// Historically each page rendered a raw "HTTP 404", which an operator reads as
// "the app is broken". This component turns every such outcome into a calm,
// explained state — and means we stop re-implementing (and re-breaking) the same
// empty/offline UX on every page. Pair it with `classifyBffFailure()` for BFF
// calls, or pass `kind="no_data"` / `kind="error"` for internal data sources.

import { ServerOff, ShieldAlert, AlertTriangle, Inbox, SearchX, Moon } from 'lucide-react'
import type { ReactNode } from 'react'
import type { BffFailure } from '@/lib/services/bff'

// BffFailure plus the two non-BFF states an internal data source can be in.
export type UnavailableKind = BffFailure | 'no_data'

interface Props {
  kind: UnavailableKind
  /** Service label to name in not_deployed / unreachable messages, e.g. "Transaction-service". */
  service?: string
  /** What the user was looking at, for the no_data / error copy, e.g. "Pokrytí testy". */
  feature?: string
  lang?: 'cs' | 'en'
  /** Optional overrides if a page needs bespoke copy. */
  title?: string
  detail?: string
  /** Extra content (e.g. a "View on GitHub" link or a retry button). */
  children?: ReactNode
  /** Compact padding for inline/card contexts. */
  dense?: boolean
}

type Copy = { icon: ReactNode; title: string; detail: string }

function copyFor(kind: UnavailableKind, service: string, feature: string, lang: 'cs' | 'en'): Copy {
  const cs = lang === 'cs'
  switch (kind) {
    case 'not_deployed':
      return {
        icon: <ServerOff size={28} style={{ color: 'var(--text-tertiary)' }} />,
        title: cs ? `${service} není v tomto prostředí nasazená` : `${service} is not deployed in this environment`,
        detail: cs
          ? `Tato obrazovka vyžaduje běžící ${service}. V tomto prostředí zatím nasazená není — jakmile ji ArgoCD nasadí, data se načtou automaticky.`
          : `This screen needs a running ${service}. It isn't deployed here yet — once ArgoCD rolls it out, the data loads automatically.`,
      }
    case 'scaled_to_zero':
      return {
        icon: <Moon size={28} style={{ color: 'var(--text-tertiary)' }} />,
        title: cs ? `${service} právě spí (scale-to-zero)` : `${service} is idle (scaled to zero)`,
        detail: cs
          ? `Služba je nasazená, ale je uspaná do nuly replik kvůli úspoře zdrojů (KEDA scale-to-zero). Probudí se automaticky, jakmile přijde požadavek — zkuste obrazovku načíst za chvíli.`
          : `The service is deployed but scaled to zero to save resources (KEDA). It wakes automatically on the next request — reload in a moment.`,
      }
    case 'unreachable':
      return {
        icon: <ServerOff size={28} style={{ color: 'var(--warning)' }} />,
        title: cs ? `${service} neodpovídá` : `${service} is not responding`,
        detail: cs
          ? 'Služba je nasazená, ale na požadavek neodpověděla (timeout nebo chyba upstreamu). Zkuste to prosím za chvíli znovu.'
          : 'The service is deployed but did not answer (timeout or upstream error). Please try again shortly.',
      }
    case 'unauthorized':
      return {
        icon: <ShieldAlert size={28} style={{ color: 'var(--danger)' }} />,
        title: cs ? 'Vypršela relace' : 'Session expired',
        detail: cs
          ? 'Vaše přihlášení vypršelo nebo chybí oprávnění. Přihlaste se prosím znovu.'
          : 'Your session has expired or you lack permission. Please sign in again.',
      }
    case 'not_found':
      return {
        icon: <SearchX size={28} style={{ color: 'var(--text-tertiary)' }} />,
        title: cs ? 'Nic nenalezeno' : 'Nothing found',
        detail: cs
          ? 'Pro zadané parametry nebyl nalezen žádný odpovídající záznam.'
          : 'No matching record was found for the given parameters.',
      }
    case 'no_data':
      return {
        icon: <Inbox size={28} style={{ color: 'var(--text-tertiary)' }} />,
        title: cs ? `Zatím žádná data${feature ? `: ${feature}` : ''}` : `No data yet${feature ? `: ${feature}` : ''}`,
        detail: cs
          ? 'Zdroj dat je dostupný, ale zatím neobsahuje žádné záznamy.'
          : 'The data source is available but does not contain any records yet.',
      }
    case 'error':
    default:
      return {
        icon: <AlertTriangle size={28} style={{ color: 'var(--danger)' }} />,
        title: cs ? `Načtení selhalo${feature ? `: ${feature}` : ''}` : `Failed to load${feature ? `: ${feature}` : ''}`,
        detail: cs
          ? 'Při zpracování požadavku došlo k neočekávané chybě. Zkuste to prosím znovu.'
          : 'An unexpected error occurred while processing the request. Please try again.',
      }
  }
}

/**
 * Calm, explained empty/offline/error state. Use everywhere instead of rendering
 * a raw HTTP status. See `classifyBffFailure()` to derive `kind` from a BFF
 * Response.
 */
export function DataUnavailable({
  kind,
  service = 'Service',
  feature = '',
  lang = 'en',
  title,
  detail,
  children,
  dense = false,
}: Props) {
  const c = copyFor(kind, service, feature, lang)
  // A missing result is a normal screen state and should not interrupt a screen-reader user.
  // A service/session failure happens after a data request, so announce it once without moving
  // focus away from the operator's filters or retry action. Expired credentials are the exception:
  // they block the workflow and deserve the assertive alert channel.
  const liveRole = kind === 'no_data' || kind === 'not_found'
    ? undefined
    : kind === 'unauthorized' ? 'alert' : 'status'
  return (
    <div
      role={liveRole}
      aria-live={liveRole === 'alert' ? 'assertive' : liveRole === 'status' ? 'polite' : undefined}
      style={{
        padding: dense ? '24px' : '40px',
        textAlign: 'center',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '10px',
      }}
    >
      {c.icon}
      <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>{title ?? c.title}</div>
      <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', maxWidth: '460px', lineHeight: 1.5 }}>
        {detail ?? c.detail}
      </div>
      {children}
    </div>
  )
}
