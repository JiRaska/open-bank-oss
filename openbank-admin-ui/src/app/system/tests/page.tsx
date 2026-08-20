// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  FlaskConical, RefreshCw, CheckCircle2, XCircle, Minus,
  ShieldCheck, Dna, Star,
} from 'lucide-react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import type { TestResultsResponse, ServiceTestResult } from '@/lib/types/test-results'
import type { QualityReport, MutationScore, ContractVerification, ServiceQualityScore } from '@/lib/types/quality-report'

// ── Shared helpers ────────────────────────────────────────────────────────────

function PassRateBar({ passed, total }: { passed: number; total: number }) {
  if (total === 0) return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
  const pct = Math.round((passed / total) * 100)
  const color = pct === 100 ? '#16a34a' : pct >= 80 ? '#d97706' : '#dc2626'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <div style={{ flex: 1, height: '6px', background: 'var(--surface-3)', borderRadius: '3px', overflow: 'hidden', minWidth: '80px' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: '3px', transition: 'width 0.3s ease' }} />
      </div>
      <span style={{ fontSize: '11px', fontWeight: 700, color, minWidth: '34px', textAlign: 'right' }}>{pct}%</span>
    </div>
  )
}

function ScoreBar({ score, threshold = 70 }: { score: number | null; threshold?: number }) {
  if (score === null) return <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>
  const color = score >= threshold ? '#16a34a' : score >= threshold * 0.8 ? '#d97706' : '#dc2626'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <div style={{ flex: 1, height: '6px', background: 'var(--surface-3)', borderRadius: '3px', overflow: 'hidden', minWidth: '80px' }}>
        <div style={{ width: `${score}%`, height: '100%', background: color, borderRadius: '3px', transition: 'width 0.3s ease' }} />
      </div>
      <span style={{ fontSize: '11px', fontWeight: 700, color, minWidth: '34px', textAlign: 'right' }}>{score}%</span>
    </div>
  )
}

function StatusBadge({ status }: { status: 'passed' | 'failed' | 'pending' }) {
  const cfg = {
    passed: { color: '#16a34a', bg: '#dcfce7', label: '✓' },
    failed: { color: '#dc2626', bg: '#fee2e2', label: '✗' },
    pending: { color: '#6b7280', bg: 'var(--surface-2)', label: '–' },
  }[status]
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: '22px', height: '22px', borderRadius: '4px', background: cfg.bg, color: cfg.color, fontSize: '12px', fontWeight: 700 }}>
      {cfg.label}
    </span>
  )
}

// ── Tests tab ─────────────────────────────────────────────────────────────────

function ServiceRow({ svc }: { svc: ServiceTestResult }) {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const hasTests = svc.tests > 0
  const hasFail = svc.failed + svc.errors > 0
  const statusIcon = !hasTests
    ? <Minus size={14} style={{ color: 'var(--text-tertiary)' }} />
    : hasFail
      ? <XCircle size={14} style={{ color: '#dc2626' }} />
      : <CheckCircle2 size={14} style={{ color: '#16a34a' }} />
  return (
    <tr style={{ borderBottom: '1px solid var(--border)', background: !hasTests ? 'transparent' : hasFail ? 'rgba(220,38,38,0.03)' : 'transparent' }}>
      <td style={{ padding: '10px 16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
        {statusIcon}
        <span style={{ fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-primary)', fontWeight: 500 }}>
          {svc.service.replace('openbank-', '')}
        </span>
      </td>
      <td style={{ padding: '10px 16px', textAlign: 'center', fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
        {hasTests ? svc.tests : <span style={{ color: 'var(--text-tertiary)' }}>0</span>}
      </td>
      <td style={{ padding: '10px 16px', textAlign: 'center', fontSize: '13px', color: '#16a34a', fontWeight: hasTests ? 600 : 400 }}>
        {hasTests ? svc.passed : '—'}
      </td>
      <td style={{ padding: '10px 16px', textAlign: 'center', fontSize: '13px', color: hasFail ? '#dc2626' : 'var(--text-tertiary)', fontWeight: hasFail ? 700 : 400 }}>
        {hasTests ? (svc.failed + svc.errors || '—') : '—'}
      </td>
      <td style={{ padding: '10px 16px', minWidth: '140px' }}>
        <PassRateBar passed={svc.passed} total={svc.tests} />
      </td>
      <td style={{ padding: '10px 16px', textAlign: 'center', fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'monospace' }}>
        {hasTests ? `${svc.durationMs}ms` : '—'}
      </td>
      <td style={{ padding: '10px 16px', textAlign: 'center', fontSize: '11px', color: 'var(--text-tertiary)' }}>
        {svc.lastRunAt ? new Date(svc.lastRunAt).toLocaleString(dateLocale) : '—'}
      </td>
      <td style={{ padding: '10px 16px', textAlign: 'center', fontSize: '11px', color: 'var(--text-tertiary)' }}>
        {t(`${svc.unit.tests} unit`, `${svc.unit.tests} unit`)} / {t(`${svc.integration.tests} int.`, `${svc.integration.tests} int.`)}
      </td>
    </tr>
  )
}

function TestsTab({ data, error, loading }: { data: TestResultsResponse | null; error: string | null; loading: boolean }) {
  const { t } = useLanguage()
  const totals = data?.totals
  const passRate = totals && totals.tests > 0 ? Math.round((totals.passed / totals.tests) * 100) : null
  const coverageColor = passRate == null ? 'var(--text-secondary)' : passRate === 100 ? '#16a34a' : passRate >= 80 ? '#d97706' : '#dc2626'
  const coverageBg = passRate == null ? 'var(--surface-2)' : passRate === 100 ? '#dcfce7' : passRate >= 80 ? '#fef9c3' : '#fee2e2'

  const sorted = data?.services.slice().sort((a, b) => {
    if (a.failed + a.errors > 0 && b.failed + b.errors === 0) return -1
    if (b.failed + b.errors > 0 && a.failed + a.errors === 0) return 1
    if (a.tests === 0 && b.tests > 0) return 1
    if (b.tests === 0 && a.tests > 0) return -1
    return a.service.localeCompare(b.service)
  })

  return (
    <>
      {error && (
        <div style={{ marginBottom: '20px' }}>
          <DataUnavailable
            kind="error"
            feature={t('Pokrytí testy', 'Test coverage')}
            lang={t('cs', 'en') as 'cs' | 'en'}
            detail={t(
              'Zdroj výsledků testů (CI databáze) není v tomto prostředí dostupný.',
              'The test-results source (CI database) is not available in this environment.',
            )}
            dense
          />
        </div>
      )}

      {totals && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '12px', marginBottom: '24px' }}>
          {[
            { label: t('Celkový pass rate', 'Overall pass rate'), value: passRate != null ? `${passRate}%` : '—', sub: `${totals.passed}/${totals.tests} ${t('testů', 'tests')}`, color: coverageColor, bg: coverageBg },
            { label: t('Testy celkem', 'Total tests'), value: totals.tests.toString(), sub: '', color: 'var(--text-primary)', bg: 'var(--surface-2)' },
            { label: t('Úspěšné', 'Passed'), value: totals.passed.toString(), sub: '', color: '#16a34a', bg: '#dcfce7' },
            { label: t('Selhání', 'Failed'), value: totals.failed.toString(), sub: '', color: totals.failed > 0 ? '#dc2626' : 'var(--text-secondary)', bg: totals.failed > 0 ? '#fee2e2' : 'var(--surface-2)' },
            { label: t('Služby s testy', 'Services with tests'), value: `${totals.servicesWithTests}/${totals.services}`, sub: `${totals.services - totals.servicesWithTests} ${t('bez testů', 'without tests')}`, color: totals.servicesWithTests < totals.services ? '#d97706' : '#16a34a', bg: totals.servicesWithTests < totals.services ? '#fef9c3' : '#dcfce7' },
          ].map(stat => (
            <div key={stat.label} className="card" style={{ padding: '16px', background: stat.bg }}>
              <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{stat.label}</div>
              <div style={{ fontSize: '22px', fontWeight: 800, color: stat.color, marginBottom: '2px' }}>{stat.value}</div>
              {stat.sub && <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{stat.sub}</div>}
            </div>
          ))}
        </div>
      )}

      {loading && !data ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {Array.from({ length: 8 }).map((_, idx) => <div key={idx} className="skeleton" style={{ height: '42px' }} />)}
        </div>
      ) : sorted && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
            <thead>
              <tr style={{ background: 'var(--surface-2)', borderBottom: '1px solid var(--border)' }}>
                {[
                  t('Služba', 'Service'),
                  t('Testy', 'Tests'),
                  t('OK', 'Passed'),
                  t('FAIL', 'Failed'),
                  t('Pass rate', 'Pass rate'),
                  t('Trvání', 'Duration'),
                  t('Poslední spuštění', 'Last run'),
                  t('Typ', 'Type'),
                ].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: h === t('Služba', 'Service') ? 'left' : 'center', fontWeight: 600, color: 'var(--text-secondary)', fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map(svc => <ServiceRow key={svc.service} svc={svc} />)}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ marginTop: '12px', fontSize: '11px', color: 'var(--text-tertiary)', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
        {t(
          'Data se čtou z build/test-results/test/*.xml každé služby. Spusť testy příkazem: ./gradlew test.',
          'Data is read from build/test-results/test/*.xml per service. Run tests with: ./gradlew test.',
        )}
      </div>
    </>
  )
}

// ── Contract tab ──────────────────────────────────────────────────────────────

function ContractTab({ contracts, error }: { contracts: ContractVerification[]; error: boolean }) {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  if (error) {
    return (
      <DataUnavailable
        kind="no_data"
        feature={t('Smluvní testy (Pact)', 'Contract tests (Pact)')}
        lang={t('cs', 'en') as 'cs' | 'en'}
        detail={t(
          'Výsledky kontraktních testů nejsou k dispozici. Spusť ./gradlew test v balance-service a ledger-service.',
          'Contract test results are not available. Run ./gradlew test in balance-service and ledger-service.',
        )}
        dense
      />
    )
  }

  if (contracts.length === 0) {
    return (
      <div className="card" style={{ padding: '32px', textAlign: 'center', color: 'var(--text-secondary)' }}>
        <ShieldCheck size={32} style={{ margin: '0 auto 12px', color: 'var(--text-tertiary)' }} />
        <p style={{ fontSize: '13px' }}>
          {t(
            'Žádné kontraktní testy k dispozici. Výsledky se zobrazí po spuštění CI pipeline.',
            'No contract test results available. Results appear after running the CI pipeline.',
          )}
        </p>
        <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '8px' }}>
          {t('Paktové soubory: pacts/', 'Pact files: pacts/')}
        </p>
      </div>
    )
  }

  const providers = [...new Set(contracts.map(c => c.provider))].sort()
  const consumers = [...new Set(contracts.map(c => c.consumer))].sort()

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {t('Provider × Consumer matice', 'Provider × Consumer matrix')}
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                <th style={{ padding: '10px 16px', textAlign: 'left', fontWeight: 600, color: 'var(--text-secondary)', minWidth: '180px' }}>
                  {t('Provider ↓ / Consumer →', 'Provider ↓ / Consumer →')}
                </th>
                {consumers.map(consumer => (
                  <th key={consumer} style={{ padding: '10px 16px', textAlign: 'center', fontWeight: 600, color: 'var(--text-secondary)', fontFamily: 'monospace', fontSize: '11px' }}>
                    {consumer.replace('openbank-', '')}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {providers.map(provider => (
                <tr key={provider} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 16px', fontFamily: 'monospace', fontSize: '11px', fontWeight: 600, color: 'var(--text-primary)' }}>
                    {provider.replace('openbank-', '')}
                  </td>
                  {consumers.map(consumer => {
                    const contract = contracts.find(c => c.provider === provider && c.consumer === consumer)
                    return (
                      <td key={consumer} style={{ padding: '10px 16px', textAlign: 'center' }}>
                        {contract ? (
                          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
                            <StatusBadge status={contract.status} />
                            {contract.verifiedAt && (
                              <span style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>
                                {new Date(contract.verifiedAt).toLocaleDateString(dateLocale)}
                              </span>
                            )}
                          </div>
                        ) : (
                          <span style={{ color: 'var(--text-tertiary)', fontSize: '11px' }}>—</span>
                        )}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {contracts.map(contract => (
        <div key={`${contract.consumer}-${contract.provider}`} className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '10px', background: 'var(--surface-2)' }}>
            <StatusBadge status={contract.status} />
            <span style={{ fontFamily: 'monospace', fontSize: '12px', fontWeight: 600 }}>
              {contract.consumer.replace('openbank-', '')}
            </span>
            <span style={{ color: 'var(--text-tertiary)' }}>→</span>
            <span style={{ fontFamily: 'monospace', fontSize: '12px', fontWeight: 600 }}>
              {contract.provider.replace('openbank-', '')}
            </span>
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginLeft: 'auto' }}>
              {contract.pactFile}
            </span>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
            <tbody>
              {contract.interactions.map((interaction, idx) => (
                <tr key={idx} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '8px 16px', width: '24px' }}>
                    <StatusBadge status={interaction.status} />
                  </td>
                  <td style={{ padding: '8px 16px', color: 'var(--text-primary)' }}>
                    {interaction.description}
                  </td>
                  {interaction.failure && (
                    <td style={{ padding: '8px 16px', color: '#dc2626', fontSize: '11px', fontFamily: 'monospace' }}>
                      {interaction.failure}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}

      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
        {t(
          'Kontrakty se generují z Pact consumer testů a ukládají do pacts/ (git-pact, ADR-0063). Verifikace probíhá v CI providera.',
          'Contracts are generated from Pact consumer tests and stored in pacts/ (git-pact, ADR-0063). Verification runs in the provider CI job.',
        )}
      </div>
    </div>
  )
}

// ── Mutation tab ──────────────────────────────────────────────────────────────

function MutationGauge({ score, threshold = 70 }: { score: number | null; threshold?: number }) {
  if (score === null) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', width: '72px', height: '72px', borderRadius: '50%', border: '4px solid var(--border)', color: 'var(--text-tertiary)', fontSize: '11px' }}>
        —
      </div>
    )
  }
  const color = score >= threshold ? '#16a34a' : score >= threshold * 0.8 ? '#d97706' : '#dc2626'
  const circumference = 2 * Math.PI * 28
  const dashOffset = circumference * (1 - score / 100)
  return (
    <div style={{ position: 'relative', width: '72px', height: '72px' }}>
      <svg width="72" height="72" viewBox="0 0 72 72" style={{ transform: 'rotate(-90deg)' }}>
        <circle cx="36" cy="36" r="28" fill="none" stroke="var(--border)" strokeWidth="6" />
        <circle cx="36" cy="36" r="28" fill="none" stroke={color} strokeWidth="6"
          strokeDasharray={circumference} strokeDashoffset={dashOffset}
          strokeLinecap="round" style={{ transition: 'stroke-dashoffset 0.5s ease' }} />
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '13px', fontWeight: 800, color }}>
        {score}%
      </div>
    </div>
  )
}

function MutationTab({ mutations, error }: { mutations: MutationScore[]; error: boolean }) {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  if (error) {
    return (
      <DataUnavailable
        kind="no_data"
        feature={t('Mutační testy (pitest)', 'Mutation tests (pitest)')}
        lang={t('cs', 'en') as 'cs' | 'en'}
        detail={t(
          'Výsledky pitest nejsou k dispozici. Spusť ./gradlew pitest nebo počkej na týdenní CI job.',
          'Pitest results are not available. Run ./gradlew pitest or wait for the weekly CI job.',
        )}
        dense
      />
    )
  }

  if (mutations.length === 0) {
    return (
      <div className="card" style={{ padding: '32px', textAlign: 'center', color: 'var(--text-secondary)' }}>
        <Dna size={32} style={{ margin: '0 auto 12px', color: 'var(--text-tertiary)' }} />
        <p style={{ fontSize: '13px' }}>
          {t(
            'Žádné výsledky mutačního testování. Pitest běží týdně v CI (workflow pitest.yml).',
            'No mutation test results. Pitest runs weekly in CI (pitest.yml workflow).',
          )}
        </p>
        <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '8px' }}>
          {t('Manuální spuštění: ./gradlew pitest', 'Manual run: ./gradlew pitest')}
        </p>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '12px' }}>
        {mutations.map(mut => (
          <div key={mut.service} className="card" style={{ padding: '20px', display: 'flex', alignItems: 'center', gap: '16px' }}>
            <MutationGauge score={mut.score} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontFamily: 'monospace', fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '6px' }}>
                {mut.service.replace('openbank-', '')}
              </div>
              <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '4px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {mut.targetPackage}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '4px', fontSize: '10px', color: 'var(--text-tertiary)' }}>
                <span style={{ color: '#16a34a' }}>✓ {mut.killed}</span>
                <span style={{ color: '#dc2626' }}>✗ {mut.survived}</span>
                <span>◌ {mut.noCoverage}</span>
              </div>
              {mut.reportedAt && (
                <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '4px' }}>
                  {new Date(mut.reportedAt).toLocaleDateString(dateLocale)}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
        {t(
          'Pitest testuje domain vrstvu (finanční aritmetika). Práh: 70%. Týdenní CI job: .github/workflows/pitest.yml (ADR-0063).',
          'Pitest tests the domain layer (financial arithmetic). Threshold: 70%. Weekly CI job: .github/workflows/pitest.yml (ADR-0063).',
        )}
      </div>
    </div>
  )
}

// ── Quality score tab ─────────────────────────────────────────────────────────

function QualityScoreCell({ score }: { score: number | null }) {
  if (score === null) return <span style={{ color: 'var(--text-tertiary)', fontSize: '12px' }}>—</span>
  const color = score >= 80 ? '#16a34a' : score >= 60 ? '#d97706' : '#dc2626'
  return <span style={{ fontWeight: 700, fontSize: '13px', color }}>{score}%</span>
}

function QualityTab({ scores }: { scores: ServiceQualityScore[] }) {
  const { t } = useLanguage()

  const sorted = scores.slice().sort((a, b) => {
    if (a.composite !== null && b.composite !== null) return b.composite - a.composite
    if (a.composite !== null) return -1
    if (b.composite !== null) return 1
    return a.service.localeCompare(b.service)
  })

  if (scores.length === 0) {
    return (
      <div className="card" style={{ padding: '32px', textAlign: 'center', color: 'var(--text-secondary)' }}>
        <Star size={32} style={{ margin: '0 auto 12px', color: 'var(--text-tertiary)' }} />
        <p style={{ fontSize: '13px' }}>
          {t(
            'Kompozitní skóre bude dostupné po spuštění CI pipeline (testy + pitest + Pact).',
            'Composite score will be available after running the CI pipeline (tests + pitest + Pact).',
          )}
        </p>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
          <thead>
            <tr style={{ background: 'var(--surface-2)', borderBottom: '1px solid var(--border)' }}>
              {[
                { label: t('Služba', 'Service'), align: 'left' as const },
                { label: t('Testy', 'Tests'), align: 'center' as const },
                { label: t('Pokrytí', 'Coverage'), align: 'center' as const },
                { label: t('Mutace', 'Mutation'), align: 'center' as const },
                { label: t('Kontrakty', 'Contracts'), align: 'center' as const },
                { label: t('Celkové skóre', 'Overall score'), align: 'center' as const },
              ].map(col => (
                <th key={col.label} style={{ padding: '10px 16px', textAlign: col.align, fontWeight: 600, color: 'var(--text-secondary)', fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                  {col.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sorted.map(row => (
              <tr key={row.service} style={{ borderBottom: '1px solid var(--border)' }}>
                <td style={{ padding: '10px 16px', fontFamily: 'monospace', fontSize: '11px', fontWeight: 600, color: 'var(--text-primary)' }}>
                  {row.service.replace('openbank-', '')}
                </td>
                <td style={{ padding: '10px 16px', textAlign: 'center' }}><QualityScoreCell score={row.unitScore} /></td>
                <td style={{ padding: '10px 16px', textAlign: 'center' }}><QualityScoreCell score={row.coverageScore} /></td>
                <td style={{ padding: '10px 16px', textAlign: 'center' }}><QualityScoreCell score={row.mutationScore} /></td>
                <td style={{ padding: '10px 16px', textAlign: 'center' }}><QualityScoreCell score={row.contractScore} /></td>
                <td style={{ padding: '10px 16px', textAlign: 'center' }}>
                  {row.composite !== null
                    ? <ScoreBar score={row.composite} threshold={80} />
                    : <span style={{ color: 'var(--text-tertiary)', fontSize: '12px' }}>—</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
        {t(
          'Kompozitní skóre = průměr dostupných složek: pass rate testů (25 %) + kover pokrytí (25 %) + pitest mutace (25 %) + kontrakty (25 %).',
          'Composite score = average of available components: test pass rate (25%) + kover coverage (25%) + pitest mutation (25%) + contracts (25%).',
        )}
      </div>
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

type Tab = 'tests' | 'contract' | 'mutation' | 'quality'

export default function TestCoveragePage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [tab, setTab] = useState<Tab>('tests')

  const [testData, setTestData] = useState<TestResultsResponse | null>(null)
  const [testError, setTestError] = useState<string | null>(null)
  const [testLoading, setTestLoading] = useState(true)

  const [qualityData, setQualityData] = useState<QualityReport | null>(null)
  const [qualityError, setQualityError] = useState(false)
  const [qualityLoading, setQualityLoading] = useState(true)

  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const load = useCallback(async () => {
    setTestLoading(true)
    setQualityLoading(true)
    setTestError(null)
    setQualityError(false)

    const [testRes, qualRes] = await Promise.allSettled([
      fetch('/api/test-results', { cache: 'no-store' }),
      fetch('/api/quality-report', { cache: 'no-store' }),
    ])

    if (testRes.status === 'fulfilled' && testRes.value.ok) {
      setTestData(await testRes.value.json())
    } else {
      setTestError('unavailable')
    }
    setTestLoading(false)

    if (qualRes.status === 'fulfilled' && qualRes.value.ok) {
      setQualityData(await qualRes.value.json() as QualityReport)
    } else {
      setQualityError(true)
    }
    setQualityLoading(false)
    setLastRefresh(new Date())
  }, [])

  useEffect(() => { load() }, [load])

  const tabs: { id: Tab; label: string; icon: React.ReactNode; count?: number }[] = [
    { id: 'tests', label: t('Testy', 'Tests'), icon: <FlaskConical size={13} /> },
    { id: 'contract', label: t('Kontrakty', 'Contracts'), icon: <ShieldCheck size={13} />, count: qualityData?.contracts.length },
    { id: 'mutation', label: t('Mutace', 'Mutation'), icon: <Dna size={13} />, count: qualityData?.mutations.length },
    { id: 'quality', label: t('Skóre kvality', 'Quality Score'), icon: <Star size={13} />, count: qualityData?.serviceScores.length },
  ]

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>{t('OpenBank', 'OpenBank')}</span><span className="breadcrumb-sep">/</span><span>{t('Systém', 'System')}</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Kvalita kódu', 'Code Quality')}</span></div>}
        icon={<FlaskConical size={20} aria-hidden="true" />}
        title={t('Kvalita kódu', 'Code Quality')}
        subtitle={t('Výsledky testů, kontraktní verifikace (Pact), mutační testování (pitest) a kompozitní skóre kvality.', 'Test results, contract verification (Pact), mutation testing (pitest), and composite quality score.')}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {t('Aktualizováno', 'Updated')} {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <button type="button" onClick={load} disabled={testLoading || qualityLoading} aria-busy={testLoading || qualityLoading} className="btn btn-secondary btn-sm">
            <RefreshCw size={13} aria-hidden="true" style={{ animation: (testLoading || qualityLoading) ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      <div role="group" aria-label={t('Přepínač pohledů kvality kódu', 'Code quality view')} style={{ display: 'flex', gap: '2px', marginBottom: '20px', borderBottom: '1px solid var(--border)' }}>
        {tabs.map(tabDef => (
          <button
            key={tabDef.id}
            type="button"
            aria-pressed={tab === tabDef.id}
            onClick={() => setTab(tabDef.id)}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px',
              padding: '8px 14px',
              fontSize: '13px', fontWeight: tab === tabDef.id ? 600 : 400,
              color: tab === tabDef.id ? 'var(--accent)' : 'var(--text-secondary)',
              background: 'none', border: 'none',
              borderBottom: tab === tabDef.id ? '2px solid var(--accent)' : '2px solid transparent',
              cursor: 'pointer', marginBottom: '-1px', transition: 'color 0.15s',
            }}
          >
            <span aria-hidden="true">{tabDef.icon}</span>
            {tabDef.label}
            {tabDef.count !== undefined && tabDef.count > 0 && (
              <span style={{ fontSize: '10px', background: 'var(--surface-3)', borderRadius: '10px', padding: '1px 6px', color: 'var(--text-secondary)', fontWeight: 500 }}>
                {tabDef.count}
              </span>
            )}
          </button>
        ))}
      </div>

      {tab === 'tests' && (
        <TestsTab data={testData} error={testError} loading={testLoading} />
      )}
      {tab === 'contract' && (
        qualityLoading
          ? <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>{Array.from({ length: 3 }).map((_, idx) => <div key={idx} className="skeleton" style={{ height: '48px' }} />)}</div>
          : <ContractTab contracts={qualityData?.contracts ?? []} error={qualityError} />
      )}
      {tab === 'mutation' && (
        qualityLoading
          ? <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '12px' }}>{Array.from({ length: 4 }).map((_, idx) => <div key={idx} className="skeleton" style={{ height: '100px' }} />)}</div>
          : <MutationTab mutations={qualityData?.mutations ?? []} error={qualityError} />
      )}
      {tab === 'quality' && (
        qualityLoading
          ? <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>{Array.from({ length: 6 }).map((_, idx) => <div key={idx} className="skeleton" style={{ height: '42px' }} />)}</div>
          : <QualityTab scores={qualityData?.serviceScores ?? []} />
      )}
    </div>
  )
}
