// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { Shield, RefreshCw, Clock, Zap, ChevronDown, ChevronRight, Info, Circle, Loader2 } from 'lucide-react'
import { fetchAllServiceConfigSnapshots } from '@/lib/api'
import type { ServiceConfigSnapshot } from '@/types'
import { PageHeader } from '@/components/ui/PageHeader'

const POLL_INTERVAL = 15_000

export default function ServiceConfigPage() {
  const [expanded, setExpanded] = useState<string | null>(null)
  const [snapshots, setSnapshots] = useState<ServiceConfigSnapshot[]>([])
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  const refresh = useCallback(async () => {
    try {
      const data = await fetchAllServiceConfigSnapshots()
      setSnapshots(data)
      setLastRefresh(new Date())
    } catch (e) {
      // The BFF is unreachable (in the sandbox most of the fleet isn't deployed),
      // which is expected, not exceptional. Keep the last-known-good snapshots and
      // let `finally` drop the spinner so the page degrades to its empty/stale
      // state. Without this catch the rejection escaped the effect entirely as an
      // unhandled promise rejection on every failed poll.
      console.error('Failed to load service config snapshots', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const id = setInterval(refresh, POLL_INTERVAL)
    return () => clearInterval(id)
  }, [refresh])

  const toggle = (name: string) => setExpanded(p => p === name ? null : name)

  const upCount = snapshots.filter(s => s.reachable && s.health?.status === 'UP').length
  const downCount = snapshots.filter(s => !s.reachable || s.health?.status === 'DOWN').length
  const degradedCount = snapshots.filter(s => s.reachable && s.health && s.health.status !== 'UP' && s.health.status !== 'DOWN').length

  return (
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb">
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span>{t('Systém', 'System')}</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Konfigurace', 'Configuration')}</span>
          </div>}
        title={t('Konfigurace služeb', 'Service Configuration')}
        icon={<Shield aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        subtitle={t('Živé resilience politiky načtené z každé služby přes', 'Live resilience policies fetched from each service via') + ' /api/v1/config. ' + t('Automatická obnova každých', 'Auto-refreshes every') + ` ${POLL_INTERVAL / 1000}s.`}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <button
            type="button"
            aria-busy={loading}
            onClick={() => { setLoading(true); refresh() }}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px',
              padding: '6px 12px', borderRadius: 'var(--r-md)',
              border: '1px solid var(--border)', background: 'var(--surface)',
              cursor: 'pointer', fontSize: '12px', color: 'var(--text-secondary)',
            }}
          >
            <RefreshCw size={12} aria-hidden="true" className={loading ? 'spinning' : ''} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />
      {/* Status summary */}
      <div style={{ display: 'flex', gap: '10px', marginBottom: '16px' }}>
        <StatusPill color="#059669" bg="#ecfdf5" count={upCount} label={t('V pořádku', 'Healthy')} />
        {degradedCount > 0 && <StatusPill color="#d97706" bg="#fffbeb" count={degradedCount} label={t('Zhoršené', 'Degraded')} />}
        {downCount > 0 && <StatusPill color="#dc2626" bg="#fef2f2" count={downCount} label={t('Nedostupné', 'Unreachable')} />}
      </div>

      {/* Legend row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px', marginBottom: '20px' }}>
        <LegendCard icon={<Zap size={14}/>}       label={t('Limit požadavků', 'Rate Limit')}     desc={t('Max souběžných požadavků', 'Max concurrent requests')}    accent="#7c3aed" accentBg="#f5f3ff" />
        <LegendCard icon={<Shield size={14}/>}     label="Circuit Breaker"                         desc={t('Aktivuje se při trvalých selháních', 'Trips on sustained failures')} accent="#2563eb" accentBg="#eff6ff" />
        <LegendCard icon={<RefreshCw size={14}/>}  label={t('Opakování', 'Retry')}                 desc={t('Automatické opakování s jitterem', 'Auto-retry with jitter')}     accent="#059669" accentBg="#ecfdf5" />
        <LegendCard icon={<Clock size={14}/>}      label={t('Timeout', 'Timeout')}                 desc={t('Max čekání na odchozí volání', 'Max wait per outbound call')}   accent="#d97706" accentBg="#fffbeb" />
      </div>

      {/* Loading state */}
      {loading && snapshots.length === 0 && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '60px 0', gap: '10px', color: 'var(--text-tertiary)' }}>
          <Loader2 size={16} className="spinning" />
          <span style={{ fontSize: '13px' }}>{t('Načítám konfigurace služeb…', 'Fetching service configurations...')}</span>
        </div>
      )}

      {/* Service list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        {snapshots.map(snap => {
          const cfg = snap.config
          const isOpen = expanded === snap.name
          const panelId = `service-config-${snap.name.replace(/[^a-zA-Z0-9_-]/g, '-')}`
          const healthStatus = !snap.reachable ? 'down' : snap.health?.status === 'UP' ? 'up' : snap.health?.status === 'DOWN' ? 'down' : 'degraded'
          const hasCustomConfig = cfg && (cfg.rateLimit || cfg.circuitBreaker || cfg.retry || cfg.timeout)

          return (
            <div
              key={snap.name}
              className="card"
              style={{ overflow: 'hidden', borderRadius: 'var(--r-lg)' }}
            >
              {/* Row header */}
              <button
                type="button"
                aria-expanded={isOpen}
                aria-controls={isOpen ? panelId : undefined}
                onClick={() => toggle(snap.name)}
                style={{
                  width: '100%',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '12px 16px',
                  background: isOpen ? 'var(--surface-2)' : 'var(--surface)',
                  border: 'none',
                  cursor: 'pointer',
                  textAlign: 'left',
                  transition: 'background 0.12s',
                  borderBottom: isOpen ? '1px solid var(--border)' : 'none',
                }}
                onMouseEnter={e => { if (!isOpen) (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)' }}
                onMouseLeave={e => { if (!isOpen) (e.currentTarget as HTMLElement).style.background = 'var(--surface)' }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <span style={{ color: 'var(--text-tertiary)', display: 'flex' }}>
                    {isOpen ? <ChevronDown size={14} aria-hidden="true"/> : <ChevronRight size={14} aria-hidden="true"/>}
                  </span>
                  <HealthDot status={healthStatus} />
                  <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{snap.name}</span>
                  <span className="tag">:{snap.port}</span>
                  {snap.latencyMs != null && (
                    <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>
                      {snap.latencyMs}ms
                    </span>
                  )}
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  {!snap.reachable && (
                    <span style={{ fontSize: '11px', color: '#dc2626', fontWeight: 600 }}>{t('Nedostupné', 'Unreachable')}</span>
                  )}
                  {cfg?.rateLimit     && <PolicyBadge color="#7c3aed" bg="#f5f3ff" icon={<Zap size={10}/>}      label={`${cfg.rateLimit.maxConcurrent} concurrent`} />}
                  {cfg?.circuitBreaker && <PolicyBadge color="#2563eb" bg="#eff6ff" icon={<Shield size={10}/>}   label="Circuit Breaker" />}
                  {cfg?.retry         && <PolicyBadge color="#059669" bg="#ecfdf5" icon={<RefreshCw size={10}/>} label={`${cfg.retry.maxRetries}× retry`} />}
                  {cfg?.timeout       && <PolicyBadge color="#d97706" bg="#fffbeb" icon={<Clock size={10}/>}     label={`${cfg.timeout.valueMs / 1000}s timeout`} />}
                  {snap.reachable && !hasCustomConfig && (
                    <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontStyle: 'italic' }}>{t('pouze výchozí', 'defaults only')}</span>
                  )}
                </div>
              </button>

              {/* Expanded detail */}
              {isOpen && (
                <div id={panelId} role="region" aria-label={t('Detail konfigurace služby', 'Service configuration details')} style={{ padding: '16px', background: 'var(--surface)' }}>
                  {!snap.reachable ? (
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#dc2626', fontSize: '13px', padding: '8px 0' }}>
                      <Circle size={10} fill="#dc2626" stroke="none" />
                      {t('Služba je nedostupná — nelze načíst živou konfiguraci.', 'Service is unreachable — cannot fetch live configuration.')}
                    </div>
                  ) : !cfg ? (
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-tertiary)', fontSize: '13px', padding: '8px 0' }}>
                      <Info size={14} />
                      {t('Konfigurační endpoint není dostupný — služba možná ještě nepodporuje /api/v1/config.', 'Config endpoint not available — service may not expose /api/v1/config yet.')}
                    </div>
                  ) : (
                    <>
                      {/* Health checks */}
                      {snap.health && snap.health.checks && snap.health.checks.length > 0 && (
                        <div style={{ marginBottom: '14px' }}>
                          <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
                            {t('Zdravotní kontroly', 'Health Checks')}
                          </div>
                          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                            {snap.health.checks.map(check => (
                              <span key={check.name} style={{
                                display: 'inline-flex', alignItems: 'center', gap: '4px',
                                padding: '3px 8px', borderRadius: '4px', fontSize: '11px',
                                background: check.status === 'UP' ? '#ecfdf5' : '#fef2f2',
                                color: check.status === 'UP' ? '#059669' : '#dc2626',
                                border: `1px solid ${check.status === 'UP' ? '#05966933' : '#dc262633'}`,
                              }}>
                                <Circle size={6} fill={check.status === 'UP' ? '#059669' : '#dc2626'} stroke="none" />
                                {check.name}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Resilience policies grid */}
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px' }}>
                        <PolicyPanel
                          title={t('Limit požadavků', 'Rate Limit')} icon={<Zap size={13}/>}
                          accent="#7c3aed" accentBg="#f5f3ff"
                          active={!!cfg.rateLimit}
                        >
                          {cfg.rateLimit
                            ? <ConfigRow label={t('Max souběžně', 'Max concurrent')} value={String(cfg.rateLimit.maxConcurrent)} />
                            : null}
                        </PolicyPanel>

                        <PolicyPanel
                          title="Circuit Breaker" icon={<Shield size={13}/>}
                          accent="#2563eb" accentBg="#eff6ff"
                          active={!!cfg.circuitBreaker}
                        >
                          {cfg.circuitBreaker ? <>
                            <ConfigRow label={t('Podíl selhání', 'Failure ratio')}      value={`${cfg.circuitBreaker.failureRatio * 100}%`} />
                            <ConfigRow label={t('Objem požadavků', 'Request volume')}    value={String(cfg.circuitBreaker.requestVolumeThreshold)} />
                            <ConfigRow label={t('Práh úspěchu', 'Success threshold')}    value={String(cfg.circuitBreaker.successThreshold)} />
                            <ConfigRow label={t('Zpoždění otevření', 'Open delay')}      value={`${cfg.circuitBreaker.delayMs / 1000}s`} />
                          </> : null}
                        </PolicyPanel>

                        <PolicyPanel
                          title={t('Opakování', 'Retry')} icon={<RefreshCw size={13}/>}
                          accent="#059669" accentBg="#ecfdf5"
                          active={!!cfg.retry}
                        >
                          {cfg.retry ? <>
                            <ConfigRow label={t('Max opakování', 'Max retries')} value={String(cfg.retry.maxRetries)} />
                            <ConfigRow label={t('Zpoždění', 'Delay')}            value={`${cfg.retry.delayMs}ms`} />
                            <ConfigRow label="Jitter"                             value={`±${cfg.retry.jitterMs}ms`} />
                          </> : null}
                        </PolicyPanel>

                        <PolicyPanel
                          title={t('Timeout', 'Timeout')} icon={<Clock size={13}/>}
                          accent="#d97706" accentBg="#fffbeb"
                          active={!!cfg.timeout}
                        >
                          {cfg.timeout
                            ? <ConfigRow label={t('Max doba trvání', 'Max duration')} value={`${cfg.timeout.valueMs / 1000}s`} />
                            : null}
                        </PolicyPanel>
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* Info banner */}
      <div style={{
        marginTop: '20px',
        padding: '14px 16px',
        background: 'var(--info-bg)',
        border: '1px solid var(--info-border)',
        borderRadius: 'var(--r-lg)',
        display: 'flex',
        gap: '10px',
        alignItems: 'flex-start',
      }}>
        <Info size={14} style={{ color: 'var(--info)', flexShrink: 0, marginTop: '1px' }} />
        <p style={{ fontSize: '13px', color: 'var(--info)', lineHeight: 1.6 }}>
          {t('Hodnoty jsou načítány', 'Values are fetched')} <strong>{t('živě', 'live')}</strong> {t('z endpointu', 'from each service\'s')}{' '}
          <code style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', background: 'rgba(2,132,199,0.1)', padding: '1px 5px', borderRadius: '3px' }}>/api/v1/config</code>{' '}
          {t('každé služby. Pro změnu hodnot aktualizujte', 'endpoint. To change values, update')}{' '}
          <code style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', background: 'rgba(2,132,199,0.1)', padding: '1px 5px', borderRadius: '3px' }}>application.yaml</code>{' '}
          {t('v příslušné službě a nasaďte znovu. Stav zdraví se aktualizuje každých', 'in the respective service and redeploy. Health status updates every')} {POLL_INTERVAL / 1000} {t('sekund.', 'seconds.')}
        </p>
      </div>
    </div>
  )
}

/* ── Sub-components ─────────────────────────────────────────────────────── */

function StatusPill({ color, bg, count, label }: { color: string; bg: string; count: number; label: string }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: '6px',
      padding: '5px 12px', borderRadius: '20px', fontSize: '12px', fontWeight: 600,
      background: bg, color, border: `1px solid ${color}33`,
    }}>
      <Circle size={7} fill={color} stroke="none" />
      {count} {label}
    </span>
  )
}

function HealthDot({ status }: { status: 'up' | 'down' | 'degraded' }) {
  const color = status === 'up' ? '#059669' : status === 'down' ? '#dc2626' : '#d97706'
  return (
    <span style={{ display: 'flex', position: 'relative' }}>
      <Circle size={8} fill={color} stroke="none" />
      {status === 'up' && (
        <span style={{
          position: 'absolute', inset: '-2px',
          borderRadius: '50%',
          border: `2px solid ${color}`,
          opacity: 0.3,
          animation: 'pulse 2s ease-in-out infinite',
        }} />
      )}
    </span>
  )
}

function LegendCard({ icon, label, desc, accent, accentBg }: {
  icon: React.ReactNode; label: string; desc: string; accent: string; accentBg: string
}) {
  return (
    <div style={{
      background: 'var(--surface)',
      border: '1px solid var(--border)',
      borderRadius: 'var(--r-lg)',
      padding: '14px 16px',
      display: 'flex',
      gap: '12px',
      alignItems: 'flex-start',
      boxShadow: 'var(--shadow-xs)',
    }}>
      <div style={{
        padding: '8px',
        borderRadius: 'var(--r-md)',
        background: accentBg,
        color: accent,
        flexShrink: 0,
        display: 'flex',
      }}>
        {icon}
      </div>
      <div>
        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{label}</div>
        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{desc}</div>
      </div>
    </div>
  )
}

function PolicyBadge({ color, bg, icon, label }: { color: string; bg: string; icon: React.ReactNode; label: string }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: '4px',
      padding: '2px 8px',
      borderRadius: '20px',
      fontSize: '11px', fontWeight: 600,
      background: bg, color,
      border: `1px solid ${color}33`,
    }}>
      {icon}{label}
    </span>
  )
}

function PolicyPanel({ title, icon, accent, accentBg, active, children }: {
  title: string; icon: React.ReactNode; accent: string; accentBg: string; active: boolean; children: React.ReactNode
}) {
  const { t } = useLanguage()
  return (
    <div style={{
      border: `1px solid ${active ? accent + '33' : 'var(--border)'}`,
      borderRadius: 'var(--r-md)',
      overflow: 'hidden',
      opacity: active ? 1 : 0.5,
    }}>
      {/* Panel header */}
      <div style={{
        padding: '9px 12px',
        background: active ? accentBg : 'var(--surface-2)',
        borderBottom: `1px solid ${active ? accent + '22' : 'var(--border)'}`,
        display: 'flex', alignItems: 'center', gap: '7px',
      }}>
        <span style={{ color: active ? accent : 'var(--text-tertiary)', display: 'flex' }}>{icon}</span>
        <span style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: active ? accent : 'var(--text-tertiary)' }}>
          {title}
        </span>
      </div>
      {/* Panel body */}
      <div style={{ padding: '10px 12px', background: 'var(--surface)', display: 'flex', flexDirection: 'column', gap: '6px', minHeight: '60px' }}>
        {active && children
          ? children
          : <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', fontStyle: 'italic' }}>{t('Nenastaveno', 'Not configured')}</span>
        }
      </div>
    </div>
  )
}

function ConfigRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '12px' }}>
      <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
      <span style={{ fontFamily: 'JetBrains Mono, monospace', fontWeight: 600, color: 'var(--text-primary)', fontSize: '12px' }}>{value}</span>
    </div>
  )
}
