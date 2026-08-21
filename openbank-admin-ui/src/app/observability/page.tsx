// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import Link from 'next/link'
import { Activity, AlertTriangle, RefreshCw, Zap, Server, Clock, Database, XCircle, GitBranch, Globe } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'

interface MetricResult {
  value: number | null
  label: string
}

interface MetricsData {
  availability: MetricResult
  errorRate: MetricResult
  edgeErrorRate: MetricResult
  p99Latency: MetricResult
  throughput: MetricResult
  failedPayments: MetricResult
  prometheusUp: boolean
}

export default function ObservabilityPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [metrics, setMetrics] = useState<MetricsData | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const queryPrometheus = async (query: string, signal: AbortSignal): Promise<number | null> => {
    try {
      const res = await fetch(`/api/prometheus/api/v1/query?query=${encodeURIComponent(query)}`, { 
        signal
      })
      if (!res.ok) return null
      const json = await res.json()
      if (json.status === 'success' && json.data.result.length > 0) {
        const val = parseFloat(json.data.result[0].value[1])
        return isNaN(val) ? null : val
      }
      return null
    } catch {
      return null
    }
  }

  const load = useCallback(async () => {
    setLoading(true)
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 5000)

    try {
      const promUpRes = await fetch('/api/prometheus/-/ready', { signal: controller.signal }).catch(() => null)
      const prometheusUp = !!(promUpRes && promUpRes.ok)

      if (!prometheusUp) {
        setMetrics({
          availability: { value: null, label: 'Service Availability' },
          errorRate: { value: null, label: 'Error Rate' },
          edgeErrorRate: { value: null, label: 'Edge Error Rate' },
          p99Latency: { value: null, label: 'p99 Latency' },
          throughput: { value: null, label: 'Throughput' },
          failedPayments: { value: null, label: 'Failed Payments' },
          prometheusUp: false
        })
        setLastRefresh(new Date())
        setLoading(false)
        clearTimeout(timeoutId)
        return
      }
      
      // Error rate is computed over a 1h window, not 5m: 5xx are sparse (single-digit
      // counts per day on this platform), so a 5m window is almost always empty and the
      // tile reads a misleading 0.00%. 1h captures the real ratio while staying responsive
      // to an active incident. The ratio is divided server-side so the no-5xx case yields a
      // true 0 (via `or vector(0)` on the numerator) rather than an empty result / N/A.
      // Edge error rate (edgeErrorRateVal) is computed separately from the app error
      // rate. The app ratio uses http_server_requests_seconds_count — it sees 5xx that a
      // backend (incl. customer-edge) actually recorded, but is blind to failures nginx
      // returns for requests that never reach a pod (pod-down 503, upstream-refused 502,
      // upstream-timeout 504). The edge ratio uses nginx_ingress_controller_requests,
      // which is what the customer actually receives at the edge. They are deliberately
      // NOT summed: every external request is counted by both nginx and its backend, so a
      // sum would double-count backend-origin 5xx. Same 1h window + server-side division
      // with `or vector(0)` as the app tile (see comment above).
      const [availability, errorRateVal, edgeErrorRateVal, totalReqs, latency, failedPayments] = await Promise.all([
        queryPrometheus('avg(up) * 100', controller.signal),
        queryPrometheus('100 * (sum(rate(http_server_requests_seconds_count{status=~"5.."}[1h])) or sum(rate(http_requests_total{status=~"5.."}[1h])) or vector(0)) / (sum(rate(http_server_requests_seconds_count[1h])) or sum(rate(http_requests_total[1h])))', controller.signal),
        queryPrometheus('100 * (sum(rate(nginx_ingress_controller_requests{status=~"5.."}[1h])) or vector(0)) / sum(rate(nginx_ingress_controller_requests[1h]))', controller.signal),
        queryPrometheus('sum(rate(http_server_requests_seconds_count[5m])) or sum(rate(http_requests_total[5m]))', controller.signal),
        queryPrometheus('histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) * 1000', controller.signal),
        queryPrometheus('sum(increase(payment_failures_total[5m])) or sum(increase(payment_failed_total[5m]))', controller.signal)
      ])

      setMetrics({
        availability: { value: availability, label: 'Service Availability' },
        errorRate: { value: errorRateVal, label: 'Error Rate' },
        edgeErrorRate: { value: edgeErrorRateVal, label: 'Edge Error Rate' },
        p99Latency: { value: latency, label: 'p99 Latency' },
        throughput: { value: totalReqs, label: 'Throughput' },
        failedPayments: { value: failedPayments, label: 'Failed Payments (5m)' },
        prometheusUp: true
      })
    } catch (e) {
      console.error("Failed to load metrics", e)
    } finally {
      clearTimeout(timeoutId)
      setLastRefresh(new Date())
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const formatValue = (val: number | null | undefined, suffix: string, decimals: number = 2) => {
    if (val === null || val === undefined) return 'N/A'
    return `${val.toFixed(decimals)}${suffix}`
  }

  return (
    <AuthGuard permission="system:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader title={t('Obchodní observabilita', 'Business Observability')} subtitle={t('Přehledové metriky platformy pro business operace', 'High-level platform metrics for business operations')} icon={<Activity size={20} aria-hidden="true" />} actions={<div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {metrics && (
            <span style={{ 
              fontSize: '11px', 
              padding: '4px 8px', 
              borderRadius: '4px',
              fontWeight: 600,
              color: metrics.prometheusUp ? 'var(--success-text)' : 'var(--danger-text)',
              background: metrics.prometheusUp ? 'var(--success-bg)' : 'var(--danger-bg)',
              display: 'flex',
              alignItems: 'center',
              gap: '4px'
            }}>
              <Database size={12} />
              {metrics.prometheusUp ? t('Prometheus připojen', 'Prometheus Connected') : t('Prometheus nedostupný', 'Prometheus Unreachable')}
            </span>
          )}
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginLeft: '8px' }}>
              {t('Aktualizováno', 'Updated')} {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <Link href="/observability/stack" className="btn btn-secondary btn-sm" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Zap size={13} />
            {t('Jak to funguje', 'How it works')}
          </Link>
          <Link href="/observability/traces" className="btn btn-secondary btn-sm" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <GitBranch size={13} />
            {t('Trace Explorer', 'Trace Explorer')}
          </Link>
          <button onClick={load} disabled={loading} type="button" aria-busy={loading}
            aria-label={t('Obnovit observabilitu', 'Refresh observability')} className="btn btn-secondary btn-sm">
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>} />

      {!metrics?.prometheusUp && !loading && (
        <div style={{ 
          padding: '16px 20px', 
          marginBottom: '28px', 
          borderRadius: '8px', 
          background: 'var(--danger-bg)', 
          border: '1px solid var(--danger-border)',
          display: 'flex',
          alignItems: 'center',
          gap: '12px'
        }}>
          <XCircle size={20} style={{ color: 'var(--danger)' }} />
          <div>
            <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--danger-text)', marginBottom: '4px' }}>
              {t('Zdroj metrik nedostupný', 'Metrics Source Unavailable')}
            </div>
            <div style={{ fontSize: '13px', color: 'var(--danger-text)', opacity: 0.9 }}>
              {t('Nelze se připojit k Prometheus. Ujistěte se, že observability stack běží.', 'Could not connect to Prometheus. Please ensure the observability stack is running.')}
            </div>
          </div>
        </div>
      )}

      <div className="grid-4" style={{ marginBottom: '28px' }}>
        <KpiCard
          icon={<Server size={18} />}
          label={t('Dostupnost služeb', 'Service Availability')}
          value={formatValue(metrics?.availability.value, '%', 1)}
          sub={t('Dostupnost celé platformy', 'Platform-wide uptime')}
          color={(metrics?.availability.value ?? 0) >= 99 ? 'var(--success)' : (metrics?.availability.value ?? 0) >= 95 ? 'var(--warning)' : 'var(--danger)'}
        />
        <KpiCard
          icon={<AlertTriangle size={18} />}
          label={t('Chybovost služeb', 'Service Error Rate')}
          value={formatValue(metrics?.errorRate.value, '%', 2)}
          sub={t('Aplikační 5xx / požadavky (1h)', 'App-recorded 5xx / requests (1h)')}
          color={(metrics?.errorRate.value ?? 0) < 1 ? 'var(--success)' : (metrics?.errorRate.value ?? 0) < 5 ? 'var(--warning)' : 'var(--danger)'}
        />
        <KpiCard
          icon={<Clock size={18} />}
          label={t('p99 Latence', 'p99 Latency')}
          value={formatValue(metrics?.p99Latency.value, 'ms', 0)}
          sub={t('99. percentil doby odezvy', '99th percentile response time')}
          color={(metrics?.p99Latency.value ?? 0) < 200 ? 'var(--success)' : (metrics?.p99Latency.value ?? 0) < 500 ? 'var(--warning)' : 'var(--danger)'}
        />
        <KpiCard
          icon={<Activity size={18} />}
          label={t('Propustnost', 'Throughput')}
          value={formatValue(metrics?.throughput.value, ' req/s', 1)}
          sub={t('Globální rychlost požadavků', 'Global request rate')}
          color="var(--info)"
        />
      </div>

      <div className="grid-4" style={{ marginBottom: '28px' }}>
        <KpiCard
          icon={<Globe size={18} />}
          label={t('Chybovost na edge', 'Edge Error Rate')}
          value={formatValue(metrics?.edgeErrorRate.value, '%', 2)}
          sub={t('Co vidí klient: nginx 5xx / 1h', 'Customer-facing: nginx 5xx / total (1h)')}
          color={(metrics?.edgeErrorRate.value ?? 0) < 1 ? 'var(--success)' : (metrics?.edgeErrorRate.value ?? 0) < 5 ? 'var(--warning)' : 'var(--danger)'}
        />
        <KpiCard
          icon={<Zap size={18} />}
          label={t('Neúspěšné platby', 'Failed Payments')}
          value={formatValue(metrics?.failedPayments.value, '', 0)}
          sub={t('Selhání za posledních 5 minut', 'Failures in last 5 minutes')}
          color={(metrics?.failedPayments.value ?? 0) === 0 ? 'var(--success)' : 'var(--danger)'}
        />
      </div>
    </div>
    </AuthGuard>
  )
}

function KpiCard({ icon, label, value, sub, color }: {
  icon: React.ReactNode; label: string; value: string; sub: string; color: string;
}) {
  return (
    <div className="stat-card">
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '12px' }}>
        <div style={{ width: '36px', height: '36px', borderRadius: '10px',
          background: `${color}18`, display: 'flex', alignItems: 'center', justifyContent: 'center', color }}>
          {icon}
        </div>
      </div>
      <div style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '2px' }}>
        {value}
      </div>
      <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '2px' }}>{label}</div>
      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{sub}</div>
    </div>
  )
}
