// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Renders the charter-backed identity of whoever proposed a queued item (issue #5904).
// The three outcomes of lib/governance/agentIdentity are rendered as three visibly
// different things; see that module for why `unresolved` and `unverifiable` must not
// collapse into one badge.

import { Bot, ShieldQuestion, UserRound } from 'lucide-react'
import type { AgentIdentity } from '@/lib/governance/agentIdentity'

interface Props {
  identity: AgentIdentity
  /** True while the charter registry is still being fetched — neither absence nor failure yet. */
  loading?: boolean
  lang?: 'cs' | 'en'
}

export function AgentIdentityBadge({ identity, loading = false, lang = 'en' }: Props) {
  const cs = lang === 'cs'

  if (loading) {
    return (
      <span
        data-testid="agent-identity"
        data-state="loading"
        className="badge badge-sm badge-neutral"
      >
        {cs ? 'Ověřuji charter…' : 'Checking charter…'}
      </span>
    )
  }

  if (identity.status === 'chartered') {
    const { charter } = identity
    return (
      <span
        data-testid="agent-identity"
        data-state="chartered"
        data-agent-id={charter.id}
        title={charter.charter}
        className="badge badge-sm badge-warning"
      >
        <Bot size={11} aria-hidden="true" />
        {charter.id}
        <span className="badge-detail">
          · {cs ? 'charter' : 'charter'} · {charter.plane}
        </span>
      </span>
    )
  }

  if (identity.status === 'unresolved') {
    return (
      <span
        data-testid="agent-identity"
        data-state="unresolved"
        className="badge badge-sm badge-neutral"
      >
        <UserRound size={11} aria-hidden="true" />
        {cs ? 'Bez charteru v agents.yaml' : 'No charter in agents.yaml'}
      </span>
    )
  }

  return (
    <span
      data-testid="agent-identity"
      data-state="unverifiable"
      className="badge badge-sm badge-danger"
    >
      <ShieldQuestion size={11} aria-hidden="true" />
      {cs ? 'Identitu nelze ověřit — registr charterů nedostupný' : 'Identity unverifiable — charter registry unavailable'}
    </span>
  )
}
