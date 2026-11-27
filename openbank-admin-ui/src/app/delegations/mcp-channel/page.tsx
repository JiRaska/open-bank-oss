// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0384: channel health plus the sandbox-only administrator policy under ADR-0385.

'use client'

import { useEffect, useState } from 'react'
import { Bot } from 'lucide-react'
import { useAuth } from '@/lib/auth/useAuth'
import { McpPasskeyAgeException } from '@/components/parties/McpPasskeyAgeException'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { McpChannelHealth, isMcpChannelPayload, type McpChannelPayload } from '@/components/delegations/McpChannelHealth'

export default function McpChannelPage() {
  const { t, language } = useLanguage()
  const { hasPermission } = useAuth()
  const [data, setData] = useState<McpChannelPayload | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/delegations/mcp-channel', { signal: controller.signal, cache: 'no-store' })
      .then(r => (r.ok ? r.json() : null))
      .then(body => {
        if (isMcpChannelPayload(body)) setData(body)
        else setFailed(true)
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => controller.abort()
  }, [])

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('MCP kanál', 'MCP channel')}
        subtitle={t(
          'Zdraví zákaznického kanálu MCP (ADR-0384). Bez dimenze klienta-osoby: per-party historie patří do skladu.',
          'Customer MCP channel health (ADR-0384). No party dimension: per-party history belongs to the warehouse.',
        )}
        icon={<Bot className="h-6 w-6" />}
      />
      {hasPermission('mcp-native-passkey-age-exception:manage') && <McpPasskeyAgeException />}
      {failed || data?.state === 'unavailable' ? (
        <DataUnavailable kind="unreachable" service="Prometheus" lang={language} />
      ) : data?.state === 'ok' ? (
        <McpChannelHealth data={data} />
      ) : (
        <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>
      )}
    </div>
  )
}
