// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState, useCallback } from 'react'
import { Bot, Play, ChevronDown, ChevronRight, Zap, CheckCircle2, XCircle, RefreshCw, Server, Cpu } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'

interface ToolDef {
  name: string
  description: string
  inputSchema: {
    type: string
    properties?: Record<string, { type: string; description: string }>
    required?: string[]
  }
  // #744: downstream service + product domain, set by agent-service on tools/list. Optional so an
  // older agent-service (no field yet) degrades gracefully into the "(unmapped)" group below.
  service?: string
  domain?: string
}

interface ToolResult {
  content: { type: string; text: string }[]
  isError: boolean
}

interface ModelInfo { id: string; provider: string; sensitivity: string }
interface ModelGateway { default: string; models: ModelInfo[] }

async function mcpCall(method: string, params?: unknown) {
  const res = await fetch('/api/agent/mcp', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  })
  const json = await res.json()
  if (json.error) throw new Error(`${json.error.code}: ${json.error.message}`)
  return json.result
}

export default function AgentPage() {
  const { t, language } = useLanguage()
  const [tools, setTools]           = useState<ToolDef[]>([])
  const [loading, setLoading]       = useState(true)
  const [error, setError]           = useState<string | null>(null)
  const [expanded, setExpanded]     = useState<string | null>(null)
  const [serverInfo, setServerInfo] = useState<{ name: string; version: string; protocolVersion: string } | null>(null)
  const [gateway, setGateway]       = useState<ModelGateway | null>(null)

  const loadTools = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const init = await mcpCall('initialize', {
        protocolVersion: '2024-11-05',
        clientInfo: { name: 'openbank-admin-ui', version: '0.1.0' },
        capabilities: {},
      })
      setServerInfo({ name: init.serverInfo.name, version: init.serverInfo.version, protocolVersion: init.protocolVersion })
      const list = await mcpCall('tools/list')
      setTools(list.tools ?? [])
      try {
        const gw = await fetch('/api/agent/chat').then(r => r.json())
        if (!gw.error) setGateway({ default: gw.default, models: gw.models ?? [] })
      } catch { /* gateway is best-effort; tools still render */ }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to connect to agent-service')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { loadTools() }, [loadTools])

  return <AuthGuard permission="agent:view">
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb">
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span>{t('Systém', 'System')}</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">Agent (MCP)</span>
          </div>}
        title={t('Agent služba', 'Agent Service')}
        subtitle={t('MCP server zpřístupňující nástroje OpenBank AI agentům · JSON-RPC 2.0 přes HTTP', 'MCP server exposing OpenBank tools to AI agents · JSON-RPC 2.0 over HTTP')}
        icon={<Bot aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<button type="button" className="btn btn-secondary" onClick={loadTools} disabled={loading} aria-busy={loading} aria-label={t('Obnovit nástroje agenta', 'Refresh agent tools')}>
          <RefreshCw size={13} aria-hidden="true" className={cn(loading && 'animate-spin')} />
          {t('Obnovit', 'Refresh')}
        </button>}
      />

      {/* Server info chips */}
      {serverInfo && (
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '20px' }}>
          {[
            { label: 'Server',   value: serverInfo.name },
            { label: 'Version',  value: serverInfo.version },
            { label: 'Protocol', value: serverInfo.protocolVersion },
            { label: 'Endpoint', value: '/api/agent/mcp', mono: true },
          ].map(c => (
            <div key={c.label} style={{
              display: 'flex', alignItems: 'center', gap: '6px',
              padding: '5px 10px',
              background: 'var(--accent-light)',
              border: '1px solid var(--accent-border)',
              borderRadius: '6px',
            }}>
              <span style={{ fontSize: '11px', color: 'var(--accent)', opacity: 0.7 }}>{c.label}:</span>
              <span style={{
                fontSize: '12px', fontWeight: 600, color: 'var(--accent)',
                fontFamily: c.mono ? 'JetBrains Mono, monospace' : 'inherit',
              }}>{c.value}</span>
            </div>
          ))}
        </div>
      )}

      {/* Model gateway — provider-agnostic registry (ADR-0031 D6) */}
      {!loading && !error && gateway && (
        <div style={{ marginBottom: '30px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
            <Cpu size={14} style={{ color: 'var(--accent)' }} />
            <span style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>{t('Model brána', 'Model Gateway')}</span>
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>· {t('přidejte libovolný model přes konfiguraci, bez změny kódu', 'add any model via config, no code change')}</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: '12px' }}>
            {gateway.models.map(m => (
              <div key={m.id} className="card" style={{ padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'JetBrains Mono, monospace' }}>{m.id}</span>
                  {m.id === gateway.default && (
                    <span style={{ fontSize: '10px', fontWeight: 600, padding: '2px 6px', borderRadius: '4px', textTransform: 'uppercase', background: 'var(--accent-light)', color: 'var(--accent)', border: '1px solid var(--accent-border)' }}>{t('výchozí', 'default')}</span>
                  )}
                </div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  provider <span style={{ fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>{m.provider}</span>
                  {' · '}{m.sensitivity.toLowerCase().replace('_', '-')}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Unavailable state — agent-service is part of the planned fleet and is
          not deployed in every environment, so a connect failure is "not
          deployed", not an app fault. Render the calm shared panel rather than
          a raw JSON-RPC error string. */}
      {error && (
        <div className="card" style={{ padding: 0, marginBottom: '20px' }}>
          <DataUnavailable
            kind="not_deployed"
            service={t('Agent-service (MCP)', 'Agent-service (MCP)')}
            feature={t('MCP nástroje', 'MCP tools')}
            lang={language}
            detail={
              language === 'cs'
                ? 'Tato obrazovka vyžaduje běžící agent-service (MCP server na portu 8109). V tomto prostředí zatím nasazená není — jakmile ji ArgoCD nasadí, nástroje i model gateway se načtou automaticky.'
                : 'This screen needs a running agent-service (MCP server on port 8109). It isn’t deployed here yet — once ArgoCD rolls it out, the tools and model gateway load automatically.'
            }
          />
        </div>
      )}

      {/* Loading */}
      {loading && !error && (
        <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px 0', color: 'var(--text-tertiary)', fontSize: '13px' }}>
          <RefreshCw size={15} aria-hidden="true" className="animate-spin" style={{ color: 'var(--accent)' }} />
          {t('Připojování k MCP serveru…', 'Connecting to MCP server…')}
        </div>
      )}

      {!loading && !error && (
        <div style={{ marginBottom: '30px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
            <Server size={14} style={{ color: 'var(--accent)' }} />
            <span style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>
              {t('Pokrytí služeb', 'Service Coverage')}
            </span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '12px' }}>
            {(() => {
              // #744: group the live tools by the service agent-service tagged them with, so every
              // reachable service gets a card with its real tool count — no name-prefix heuristic,
              // no invisible service. Verb-first tools (get_account, list_transactions, …) land in
              // their service via the server-provided `service` field. Tools from an older
              // agent-service without the field fall into "(unmapped)" rather than vanishing.
              const groups = new Map<string, { domain?: string; tools: ToolDef[] }>();
              for (const tl of tools) {
                const svc = tl.service ?? '(unmapped)';
                const g = groups.get(svc) ?? { domain: tl.domain, tools: [] };
                g.tools.push(tl);
                groups.set(svc, g);
              }
              const ordered = [...groups.entries()].sort((a, b) =>
                (a[1].domain ?? 'zzz').localeCompare(b[1].domain ?? 'zzz') || a[0].localeCompare(b[0]));
              return ordered.map(([svc, g]) => (
                <div key={svc} className="card" style={{ padding: '14px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <div>
                      <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'JetBrains Mono, monospace' }}>
                        {svc}
                      </div>
                      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                        {g.domain ?? t('Nezařazeno', 'Unmapped')} • {g.tools.length} {t('nástrojů', 'tools')}
                      </div>
                    </div>
                    <span style={{
                      fontSize: '10px', fontWeight: 600, padding: '3px 6px', borderRadius: '4px', textTransform: 'uppercase',
                      background: 'var(--success-bg)', color: 'var(--success)', border: '1px solid var(--success-border)'
                    }}>
                      {t('Dostupné', 'Available')}
                    </span>
                  </div>
                  <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                    <span style={{ opacity: 0.7 }}>{t('Nástroje:', 'Tools:')}</span>{' '}
                    <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>
                      {g.tools.map(tool => tool.name).join(', ')}
                    </span>
                  </div>
                </div>
              ));
            })()}
          </div>
        </div>
      )}

      {/* Tools list */}
      {!loading && !error && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
            <Zap size={13} style={{ color: 'var(--accent)' }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
              {tools.length} {t('nástrojů k dispozici', 'tools available')}
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
            {tools.map(tool => (
              <ToolCard
                key={tool.name}
                tool={tool}
                expanded={expanded === tool.name}
                onToggle={() => setExpanded(p => p === tool.name ? null : tool.name)}
              />
            ))}
          </div>
        </div>
      )}

      {/* MCP server registry — rendered once at page level, outside ToolCard loop */}
      <div style={{ marginTop: '40px', borderTop: '1px solid var(--border)', paddingTop: '28px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
          <Server size={14} style={{ color: 'var(--accent)' }} />
          <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('MCP servery v platformě', 'MCP servers in the platform')}</span>
        </div>
        <p style={{ fontSize: '12.5px', color: 'var(--text-tertiary)', margin: '0 0 16px', maxWidth: 640 }}>
          {t('Platforma poskytuje i konzumuje MCP. Každý AI agent vybere server podle potřeby — banking nástroje přes openbank-agent-service, observability přes Grafana MCP.', 'The platform both provides and consumes MCP. Each AI agent picks a server by need — banking tools via openbank-agent-service, observability via Grafana MCP.')}
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: '12px' }}>
          {([
            {
              nameCs: 'OpenBank Agent Service',
              nameEn: 'OpenBank Agent Service',
              isLive: true,
              descCs: 'Primární MCP server — vystavuje banking nástroje (účty, platby, party, audit, sca) AI agentům. JSON-RPC 2.0 přes HTTP, OPA gate na každém tool callu (ADR-0031). Dostupný na portu 8109 v namespace platform.',
              descEn: 'Primary MCP server — exposes banking tools (accounts, payments, party, audit, sca) to AI agents. JSON-RPC 2.0 over HTTP, OPA gate on every tool call (ADR-0031). Available on port 8109 in the platform namespace.',
              endpoint: 'platform.svc:8109/mcp',
              adr: 'ADR-0031',
              color: '#6366f1',
            },
            {
              nameCs: 'Grafana MCP Server',
              nameEn: 'Grafana MCP Server',
              isLive: true,
              descCs: 'Grafana Labs open-source MCP server (grafana/mcp-grafana) zpřístupňuje dashboardy, datasources, alerting a Prometheus query AI agentům. Umožňuje AI copilotovi klást přirozené dotazy jako „jaká je latence SEPA plateb za poslední hodinu?" přímo do Prometheus. Běží v observability namespace, připojuje se k Grafana na portu 3000.',
              descEn: 'Grafana Labs open-source MCP server (grafana/mcp-grafana) exposes dashboards, datasources, alerting and Prometheus query to AI agents. Lets an AI copilot ask natural questions like "what is the SEPA payment latency over the last hour?" directly into Prometheus. Running in the observability namespace, connected to Grafana on port 3000.',
              endpoint: 'observability.svc:3000 → MCP',
              adr: 'ADR-0088',
              color: '#f59e0b',
            },
          ] as const).map((srv) => (
            <div key={srv.nameCs} className="card" style={{ padding: '16px', borderLeft: `3px solid ${srv.color}` }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8, gap: 8 }}>
                <span style={{ fontSize: '13.5px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(srv.nameCs, srv.nameEn)}</span>
                <span style={{
                  fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', padding: '2px 8px', borderRadius: 20,
                  color: '#059669', background: '#ecfdf5', border: '1px solid #6ee7b7', flexShrink: 0,
                }}>
                  {t('Live', 'Live')}
                </span>
              </div>
              <p style={{ fontSize: '12.5px', color: 'var(--text-secondary)', lineHeight: 1.6, margin: '0 0 10px' }}>
                {t(srv.descCs, srv.descEn)}
              </p>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <span style={{ fontSize: '11px', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)', background: 'var(--surface-2)', border: '1px solid var(--border)', padding: '2px 8px', borderRadius: 5 }}>{srv.endpoint}</span>
                <span style={{ fontSize: '11px', fontWeight: 600, color: srv.color, background: `${srv.color}15`, border: `1px solid ${srv.color}40`, padding: '2px 8px', borderRadius: 5 }}>{srv.adr}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  </AuthGuard>
}

function ToolCard({ tool, expanded, onToggle }: { tool: ToolDef; expanded: boolean; onToggle: () => void }) {
  const { t } = useLanguage()
  const [args, setArgs]     = useState<Record<string, string>>({})
  const [result, setResult] = useState<ToolResult | null>(null)
  const [running, setRunning] = useState(false)

  const properties = tool.inputSchema.properties ?? {}
  const required   = tool.inputSchema.required ?? []
  const panelId = `agent-tool-${tool.name.replace(/[^a-zA-Z0-9_-]/g, '-')}`

  const run = async () => {
    setRunning(true); setResult(null)
    try {
      const res: ToolResult = await mcpCall('tools/call', { name: tool.name, arguments: args })
      setResult(res)
    } catch (e) {
      setResult({ content: [{ type: 'text', text: e instanceof Error ? e.message : 'Error' }], isError: true })
    } finally { setRunning(false) }
  }

  return (
    <div className="card" style={{ overflow: 'hidden' }}>
      {/* Header */}
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={expanded ? panelId : undefined}
        onClick={onToggle}
        style={{
          width: '100%', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          padding: '12px 16px', border: 'none', cursor: 'pointer', textAlign: 'left',
          background: expanded ? 'var(--surface-2)' : 'var(--surface)',
          borderBottom: expanded ? '1px solid var(--border)' : 'none',
          transition: 'background 0.12s', gap: '12px',
          fontFamily: 'inherit',
        }}
        onMouseEnter={e => { if (!expanded) (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)' }}
        onMouseLeave={e => { if (!expanded) (e.currentTarget as HTMLElement).style.background = 'var(--surface)' }}
      >
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px', minWidth: 0 }}>
          <span style={{ color: 'var(--text-tertiary)', marginTop: '1px', flexShrink: 0 }}>
            {expanded ? <ChevronDown size={13} aria-hidden="true"/> : <ChevronRight size={13} aria-hidden="true"/>}
          </span>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'JetBrains Mono, monospace' }}>
              {tool.name}
            </div>
            <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px' }}>
              {tool.description}
            </div>
          </div>
        </div>
        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', flexShrink: 0, marginTop: '2px' }}>
          {Object.keys(properties).length} param{Object.keys(properties).length !== 1 ? 's' : ''}
        </span>
      </button>

      {/* Expanded body */}
      {expanded && (
        <div id={panelId} role="region" aria-label={t('Parametry a výsledek nástroje', 'Tool parameters and result')} style={{ padding: '16px', background: 'var(--surface)', display: 'flex', flexDirection: 'column', gap: '14px' }}>
          {/* Parameters */}
          {Object.entries(properties).length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)' }}>
                {t('Parametry', 'Parameters')}
              </div>
              {Object.entries(properties).map(([name, prop]) => (
                <div key={name} className="field">
                  <label style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span style={{ fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-primary)' }}>{name}</span>
                    {required.includes(name) && <span style={{ color: 'var(--danger)', fontSize: '11px' }}>{t('povinné', 'required')}</span>}
                    <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>— {prop.description}</span>
                  </label>
                  <input
                    aria-label={t(`${name} parametr`, `${name} parameter`)}
                    className="input"
                    type="text"
                    value={args[name] ?? ''}
                    onChange={e => setArgs(p => ({ ...p, [name]: e.target.value }))}
                    placeholder={prop.type}
                    style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px' }}
                  />
                </div>
              ))}
            </div>
          )}

          {/* Run button */}
          <Can permission="agent:execute" fallback={<div className="text-xs text-muted-foreground">{t('Spuštění nástrojů vyžaduje oprávnění agenta.', 'Tool execution requires agent authorization.')}</div>}>
            <div>
              <button
                type="button"
                aria-busy={running}
                className="btn btn-primary"
                onClick={run}
                disabled={running}
                style={{ background: running ? 'var(--text-tertiary)' : 'var(--accent)' }}
              >
                {running
                  ? <><RefreshCw size={13} aria-hidden="true" className="animate-spin"/> {t('Probíhá…', 'Running…')}</>
                  : <><Play size={13} aria-hidden="true"/> {t('Spustit nástroj', 'Run tool')}</>
                }
              </button>
            </div>
          </Can>

          {/* Result */}
          {result && (
            <div style={{
              borderRadius: 'var(--r-md)',
              border: `1px solid ${result.isError ? 'var(--danger-border)' : 'var(--success-border)'}`,
              background: result.isError ? 'var(--danger-bg)' : 'var(--success-bg)',
              overflow: 'hidden',
            }}>
              <div style={{
                padding: '8px 12px',
                borderBottom: `1px solid ${result.isError ? 'var(--danger-border)' : 'var(--success-border)'}`,
                display: 'flex', alignItems: 'center', gap: '7px',
                background: result.isError ? '#fef2f2' : '#f0fdf4',
              }}>
                {result.isError
                  ? <XCircle size={13} style={{ color: 'var(--danger)' }}/>
                  : <CheckCircle2 size={13} style={{ color: 'var(--success)' }}/>
                }
                <span style={{ fontSize: '12px', fontWeight: 600, color: result.isError ? 'var(--danger)' : 'var(--success)' }}>
                  {result.isError ? t('Chyba', 'Error') : t('Výsledek', 'Result')}
                </span>
              </div>
              <pre style={{
                padding: '12px',
                fontSize: '12px',
                fontFamily: 'JetBrains Mono, monospace',
                color: 'var(--text-primary)',
                overflowX: 'auto',
                maxHeight: '280px',
                overflowY: 'auto',
                margin: 0,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}>
                {result.content.map(c => {
                  try { return JSON.stringify(JSON.parse(c.text), null, 2) } catch { return c.text }
                }).join('\n')}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
