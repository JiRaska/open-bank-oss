// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Package, Search, RefreshCw, Edit, Play, Square, Plus, X,
  Eye, EyeOff, CreditCard, Globe, TrendingDown,
  Clock, FileText, Tag, Users, ExternalLink, History, CheckCircle2,
  Layers, Banknote, Shield,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'

const STATUS_COLOR: Record<string, { bg: string; text: string; border: string }> = {
  ACTIVE:     { bg: 'var(--success-bg)',  text: 'var(--success-text)',  border: 'var(--success-border)' },
  INACTIVE:   { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
  DRAFT:      { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
  DEPRECATED: { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  ARCHIVED:   { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
}

const TYPE_ICON: Record<string, React.ReactNode> = {
  SAVINGS:      <Banknote size={13} />,
  CURRENT:      <CreditCard size={13} />,
  LOAN:         <TrendingDown size={13} />,
  MORTGAGE:     <Shield size={13} />,
  CREDIT_CARD:  <CreditCard size={13} />,
  TERM_DEPOSIT: <Clock size={13} />,
  OVERDRAFT:    <TrendingDown size={13} />,
  INVESTMENT:   <Layers size={13} />,
}

const TYPE_COLOR: Record<string, string> = {
  SAVINGS: 'var(--success)', CURRENT: 'var(--accent)', LOAN: 'var(--warning)',
  MORTGAGE: 'var(--info)', CREDIT_CARD: 'var(--accent)', TERM_DEPOSIT: 'var(--success)',
  OVERDRAFT: 'var(--warning)', INVESTMENT: 'var(--info)',
}

interface Fee { id: string; name: string; type: string; amount: number; currency: string; frequency: string; description?: string; waivable?: boolean; waiveCondition?: string }
interface InterestTier { fromAmount: number; toAmount: number | null; rateAnnual: number }
interface CardConfig { enabled: boolean; minCards: number; maxCards: number; networks: string[]; tiers: string[]; virtualCardAllowed: boolean; contactlessEnabled: boolean; eligibilityMinAge?: number; eligibilitySegments: string[]; monthlyFeePerCard: number; cardCurrency?: string }
interface MultiCurrencyConfig { enabled: boolean; supportedCurrencies: string[]; defaultCurrency: string; fxMarginPct?: number; fxMarginBuyPct?: number; fxMarginSellPct?: number; crossCurrencyTransferAllowed: boolean }
interface OverdraftConfig { type: string; maxLimitAmount: number; interestRateAnnual: number; gracePeriodDays: number; unarrangedDailyFee?: number; unarrangedRateAnnual?: number; autoApprovalEnabled: boolean }
interface TermDepositConfig { termMonths: number; minTermMonths?: number; maxTermMonths?: number; interestRateAnnual: number; payoutFrequency: string; autoRenewEnabled: boolean; earlyWithdrawalPenaltyPct: number; earlyWithdrawalNoticeDays: number }
interface SavingsConfig { interestTiers: InterestTier[]; withdrawalNotice: string; freeWithdrawalsPerMonth: number; excessWithdrawalFee: number; bonusRateCondition?: string; bonusRateAnnual?: number }
interface TermsAndConditions { id: string; version: string; url: string; effectiveFrom: string; effectiveTo?: string; language: string; summary?: string }
interface ProductVersion { version: string; validFrom: string; validTo?: string; isPublic: boolean; changeNote?: string; createdAt: string }

interface Product {
  id: string; code: string; name: string; type: string; currency: string; status: string
  isPublic: boolean; version: string; validFrom?: string; validTo?: string
  baseRate?: number; fee?: number; fees?: Fee[]; description?: string; shortDescription?: string
  minBalance?: number; maxBalance?: number
  cardConfig?: CardConfig; multiCurrencyConfig?: MultiCurrencyConfig
  overdraftConfig?: OverdraftConfig; termDepositConfig?: TermDepositConfig; savingsConfig?: SavingsConfig
  termsAndConditions?: TermsAndConditions[]; versionHistory?: ProductVersion[]
  tags?: string[]; eligibilitySegments?: string[]
  createdAt?: string; updatedAt?: string
  revision?: number
}

// Go through the BFF proxy (not a dedicated /api/product-catalog route): the proxy
// detects KEDA scale-to-zero (ADR-0057) → a calm "idle" state instead of an
// uncaught 500, and relays the operator token (product-catalog is now auth-gated).
class ApiError extends Error {
  kind: BffFailure
  constructor(kind: BffFailure, message: string) {
    super(message)
    this.kind = kind
  }
}

async function apiFetch(path: string, opts?: RequestInit) {
  const res = await fetch(svcUrl('product-catalog', `/api/v1/products${path}`), {
    cache: 'no-store', signal: AbortSignal.timeout(8000), ...opts,
  })
  if (!res.ok) {
    const kind = await classifyBffFailure(res.clone())
    const text = await res.text().catch(() => '')
    let msg: string
    try { const parsed = JSON.parse(text); msg = parsed?.message ?? parsed?.error ?? text } catch { msg = text || res.statusText }
    throw new ApiError(kind, msg || res.statusText)
  }
  return res.json()
}

function StatusBadge({ status }: { status: string }) {
  const c = STATUS_COLOR[status] ?? STATUS_COLOR.INACTIVE
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 700, background: c.bg, color: c.text, border: `1px solid ${c.border}`, letterSpacing: '0.03em' }}>
      {status === 'ACTIVE' && <CheckCircle2 size={9} />}
      {status}
    </span>
  )
}

function TypeBadge({ type }: { type: string }) {
  const color = TYPE_COLOR[type] ?? 'var(--accent)'
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '2px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: 600, background: `${color}18`, color, border: `1px solid ${color}30` }}>
      {TYPE_ICON[type] ?? <Package size={11} />}
      {type.replace('_', ' ')}
    </span>
  )
}

function SectionHeader({ icon, label }: { icon: React.ReactNode; label: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '10px', paddingBottom: '6px', borderBottom: '1px solid var(--border)' }}>
      <span style={{ color: 'var(--accent)' }}>{icon}</span>
      <span style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>{label}</span>
    </div>
  )
}

function InfoRow({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', padding: '5px 0', borderBottom: '1px solid var(--border)' }}>
      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', flexShrink: 0, marginRight: '12px' }}>{label}</span>
      <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', textAlign: 'right', fontFamily: mono ? 'var(--font-mono)' : undefined }}>{value}</span>
    </div>
  )
}

function ProductDetailPanel({ product, onClose, onEdit, onToggleStatus }: { product: Product; onClose: () => void; onEdit: () => void; onToggleStatus: () => void }) {
  const { t } = useLanguage()
  const [tab, setTab] = useState<'overview' | 'cards' | 'multicurrency' | 'overdraft' | 'deposit' | 'savings' | 'fees' | 'tac' | 'history'>('overview')

  type TabId = 'overview' | 'cards' | 'multicurrency' | 'overdraft' | 'deposit' | 'savings' | 'fees' | 'tac' | 'history'
  const tabs: Array<{ id: TabId; label: string; icon: React.ReactNode; show: boolean }> = [
    { id: 'overview' as TabId, label: t('Přehled', 'Overview'), icon: <Package size={12} />, show: true },
    { id: 'fees' as TabId, label: t('Poplatky', 'Fees'), icon: <Banknote size={12} />, show: (product.fees?.length ?? 0) > 0 },
    { id: 'cards' as TabId, label: t('Karty', 'Cards'), icon: <CreditCard size={12} />, show: !!product.cardConfig?.enabled },
    { id: 'multicurrency' as TabId, label: t('Multi-měna', 'Multi-currency'), icon: <Globe size={12} />, show: !!product.multiCurrencyConfig?.enabled },
    { id: 'overdraft' as TabId, label: t('Debet', 'Overdraft'), icon: <TrendingDown size={12} />, show: !!product.overdraftConfig },
    { id: 'deposit' as TabId, label: t('Termín', 'Term deposit'), icon: <Clock size={12} />, show: !!product.termDepositConfig },
    { id: 'savings' as TabId, label: t('Spoření', 'Savings'), icon: <Layers size={12} />, show: !!product.savingsConfig },
    { id: 'tac' as TabId, label: t('VOP', 'T&C'), icon: <FileText size={12} />, show: (product.termsAndConditions?.length ?? 0) > 0 },
    { id: 'history' as TabId, label: t('Verze', 'Versions'), icon: <History size={12} />, show: (product.versionHistory?.length ?? 0) > 0 },
  ].filter(tab => tab.show)

  const sc = STATUS_COLOR[product.status] ?? STATUS_COLOR.INACTIVE

  return (
    <div style={{ position: 'fixed', top: 0, right: 0, width: '520px', height: '100vh', background: 'var(--surface-1)', borderLeft: '1px solid var(--border)', zIndex: 900, display: 'flex', flexDirection: 'column', boxShadow: '-8px 0 32px rgba(0,0,0,0.18)', animation: 'slideInRight 0.2s ease-out' }}>
      <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexShrink: 0 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px', flexWrap: 'wrap' }}>
            <TypeBadge type={product.type} />
            <StatusBadge status={product.status} />
            {!product.isPublic && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '3px', padding: '2px 7px', borderRadius: '10px', fontSize: '10px', fontWeight: 700, background: 'var(--surface-3)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>
                <EyeOff size={9} /> Interní
              </span>
            )}
            <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)', background: 'var(--surface-2)', padding: '2px 6px', borderRadius: '4px', border: '1px solid var(--border)' }}>v{product.version}</span>
          </div>
          <div style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.02em', marginBottom: '2px' }}>{product.name}</div>
          <div style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)' }}>{product.code}</div>
          {product.shortDescription && <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '4px' }}>{product.shortDescription}</div>}
        </div>
        <div style={{ display: 'flex', gap: '6px', flexShrink: 0, marginLeft: '12px' }}>
          <button
            onClick={onEdit}
            disabled={product.status === 'ACTIVE'}
            title={product.status === 'ACTIVE' ? t('Nejprve deaktivujte', 'Deactivate before editing') : t('Upravit', 'Edit')}
            style={{ background: 'var(--surface-3)', border: '1px solid var(--border)', borderRadius: '6px', padding: '5px 10px', cursor: product.status === 'ACTIVE' ? 'not-allowed' : 'pointer', opacity: product.status === 'ACTIVE' ? 0.55 : 1, display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: 'var(--text-secondary)' }}
          >
            <Edit size={12} /> {t('Upravit', 'Edit')}
          </button>
          <button onClick={onToggleStatus} style={{ background: sc.bg, border: `1px solid ${sc.border}`, borderRadius: '6px', padding: '5px 10px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: sc.text }}>
            {product.status === 'ACTIVE' ? <><Square size={11} /> {t('Deaktivovat', 'Deactivate')}</> : <><Play size={11} /> {t('Aktivovat', 'Activate')}</>}
          </button>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)', padding: '4px' }}>
            <X size={18} />
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: '2px', padding: '8px 12px', borderBottom: '1px solid var(--border)', flexWrap: 'wrap', flexShrink: 0 }}>
        {tabs.map(tabItem => (
          <button key={tabItem.id} onClick={() => setTab(tabItem.id as typeof tab)}
            style={{ display: 'flex', alignItems: 'center', gap: '4px', padding: '5px 10px', fontSize: '11px', fontWeight: 600, borderRadius: '5px', border: 'none', cursor: 'pointer', background: tab === tabItem.id ? 'var(--accent)' : 'transparent', color: tab === tabItem.id ? '#fff' : 'var(--text-secondary)', transition: 'all 0.1s' }}>
            {tabItem.icon}{tabItem.label}
          </button>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px' }}>

        {tab === 'overview' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div>
              <SectionHeader icon={<Package size={13} />} label="Základní informace" />
              <InfoRow label="Kód" value={product.code} mono />
              <InfoRow label="Typ" value={<TypeBadge type={product.type} />} />
              <InfoRow label="Měna" value={product.currency} mono />
              <InfoRow label="Status" value={<StatusBadge status={product.status} />} />
              <InfoRow label="Viditelnost" value={product.isPublic ? <span style={{ color: 'var(--success-text)', display: 'flex', alignItems: 'center', gap: '4px' }}><Eye size={11} /> Veřejný</span> : <span style={{ color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '4px' }}><EyeOff size={11} /> Interní</span>} />
              <InfoRow label="Verze" value={`v${product.version}`} mono />
              {product.validFrom && <InfoRow label="Platnost od" value={product.validFrom} mono />}
              {product.validTo && <InfoRow label="Platnost do" value={product.validTo} mono />}
            </div>

            <div>
              <SectionHeader icon={<TrendingDown size={13} />} label="Sazby & limity" />
              {product.baseRate != null && product.baseRate > 0 && <InfoRow label="Základní sazba" value={`${(product.baseRate * 100).toFixed(3)} % p.a.`} mono />}
              {product.minBalance != null && <InfoRow label="Min. zůstatek" value={`${product.minBalance.toLocaleString('cs-CZ')} ${product.currency}`} mono />}
              {product.maxBalance != null && <InfoRow label="Max. zůstatek" value={`${product.maxBalance.toLocaleString('cs-CZ')} ${product.currency}`} mono />}
            </div>

            {(product.tags?.length ?? 0) > 0 && (
              <div>
                <SectionHeader icon={<Tag size={13} />} label="Štítky" />
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px' }}>
                  {product.tags!.map(tag => (
                    <span key={tag} style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', background: 'var(--surface-3)', color: 'var(--text-secondary)', border: '1px solid var(--border)' }}>{tag}</span>
                  ))}
                </div>
              </div>
            )}

            {(product.eligibilitySegments?.length ?? 0) > 0 && (
              <div>
                <SectionHeader icon={<Users size={13} />} label="Cílové segmenty" />
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px' }}>
                  {product.eligibilitySegments!.map(seg => (
                    <span key={seg} style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600, background: 'var(--accent)18', color: 'var(--accent)', border: '1px solid var(--accent)30' }}>{seg}</span>
                  ))}
                </div>
              </div>
            )}

            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
              {product.cardConfig?.enabled && <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 9px', borderRadius: '8px', fontSize: '11px', fontWeight: 600, background: 'var(--info)18', color: 'var(--info)', border: '1px solid var(--info)30' }}><CreditCard size={11} /> {product.cardConfig.minCards}–{product.cardConfig.maxCards} karet</span>}
              {product.multiCurrencyConfig?.enabled && <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 9px', borderRadius: '8px', fontSize: '11px', fontWeight: 600, background: 'var(--success)18', color: 'var(--success)', border: '1px solid var(--success)30' }}><Globe size={11} /> {product.multiCurrencyConfig.supportedCurrencies.length} měn</span>}
              {product.overdraftConfig && <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 9px', borderRadius: '8px', fontSize: '11px', fontWeight: 600, background: 'var(--warning)18', color: 'var(--warning)', border: '1px solid var(--warning)30' }}><TrendingDown size={11} /> Debet {(product.overdraftConfig.interestRateAnnual * 100).toFixed(2)} %</span>}
              {product.termDepositConfig && <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 9px', borderRadius: '8px', fontSize: '11px', fontWeight: 600, background: 'var(--success)18', color: 'var(--success)', border: '1px solid var(--success)30' }}><Clock size={11} /> {product.termDepositConfig.termMonths}M · {(product.termDepositConfig.interestRateAnnual * 100).toFixed(2)} %</span>}
            </div>

            {product.description && (
              <div style={{ padding: '10px 12px', borderRadius: '6px', background: 'var(--surface-2)', border: '1px solid var(--border)', fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                {product.description}
              </div>
            )}
          </div>
        )}

        {tab === 'fees' && (
          <div>
            <SectionHeader icon={<Banknote size={13} />} label={`Poplatky (${product.fees?.length ?? 0})`} />
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {product.fees?.map(f => (
                <div key={f.id} style={{ padding: '10px 12px', borderRadius: '7px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '4px' }}>
                    <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>{f.name}</span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: '13px', fontWeight: 800, color: f.amount === 0 ? 'var(--success-text)' : 'var(--text-primary)' }}>
                      {f.amount === 0 ? 'Zdarma' : f.frequency === 'PERCENTAGE' ? `${f.amount} %` : `${f.amount.toLocaleString('cs-CZ', { minimumFractionDigits: 2 })} ${f.currency}`}
                    </span>
                  </div>
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'var(--surface-3)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>{f.type}</span>
                    <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'var(--surface-3)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>{f.frequency}</span>
                    {f.waivable && <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'var(--success-bg)', color: 'var(--success-text)', border: '1px solid var(--success-border)' }}>Odpustitelný</span>}
                  </div>
                  {f.description && <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '4px' }}>{f.description}</div>}
                  {f.waiveCondition && <div style={{ fontSize: '11px', color: 'var(--success-text)', marginTop: '3px' }}>Podmínka odpuštění: {f.waiveCondition}</div>}
                </div>
              ))}
            </div>
          </div>
        )}

        {tab === 'cards' && product.cardConfig && (
          <div>
            <SectionHeader icon={<CreditCard size={13} />} label="Konfigurace karet" />
            <InfoRow label="Povoleno" value={product.cardConfig.enabled ? 'Ano' : 'Ne'} />
            <InfoRow label="Min. karet" value={String(product.cardConfig.minCards)} mono />
            <InfoRow label="Max. karet" value={String(product.cardConfig.maxCards)} mono />
            <InfoRow label="Sítě" value={product.cardConfig.networks.join(', ')} />
            <InfoRow label="Úrovně" value={product.cardConfig.tiers.join(', ')} />
            <InfoRow label="Virtuální karta" value={product.cardConfig.virtualCardAllowed ? 'Ano' : 'Ne'} />
            <InfoRow label="Bezkontaktní" value={product.cardConfig.contactlessEnabled ? 'Ano' : 'Ne'} />
            {product.cardConfig.eligibilityMinAge != null && <InfoRow label="Min. věk" value={`${product.cardConfig.eligibilityMinAge} let`} mono />}
            {product.cardConfig.monthlyFeePerCard > 0 && <InfoRow label="Poplatek/karta/měs." value={`${product.cardConfig.monthlyFeePerCard.toFixed(2)} ${product.currency}`} mono />}
            <InfoRow label="Segmenty" value={product.cardConfig.eligibilitySegments.join(', ')} />
          </div>
        )}

        {tab === 'multicurrency' && product.multiCurrencyConfig && (
          <div>
            <SectionHeader icon={<Globe size={13} />} label="Multi-měnová konfigurace" />
            <InfoRow label="Povoleno" value={product.multiCurrencyConfig.enabled ? 'Ano' : 'Ne'} />
            <InfoRow label="Výchozí měna" value={product.multiCurrencyConfig.defaultCurrency} mono />
            <div style={{ marginBottom: '8px' }}>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{t('Podporované měny', 'Supported currencies')}</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                {product.multiCurrencyConfig.supportedCurrencies.map(c => (
                  <span key={c} style={{ padding: '2px 8px', borderRadius: '5px', fontSize: '11px', fontWeight: 700, fontFamily: 'var(--font-mono)', background: 'var(--accent)18', color: 'var(--accent)', border: '1px solid var(--accent)30' }}>{c}</span>
                ))}
              </div>
            </div>
            {product.multiCurrencyConfig.fxMarginBuyPct != null && <InfoRow label="FX marže nákup" value={`${product.multiCurrencyConfig.fxMarginBuyPct} %`} mono />}
            {product.multiCurrencyConfig.fxMarginSellPct != null && <InfoRow label="FX marže prodej" value={`${product.multiCurrencyConfig.fxMarginSellPct} %`} mono />}
            {product.multiCurrencyConfig.fxMarginPct != null && !product.multiCurrencyConfig.fxMarginBuyPct && <InfoRow label="FX marže" value={`${product.multiCurrencyConfig.fxMarginPct} %`} mono />}
            <InfoRow label="Přeshraniční převody" value={product.multiCurrencyConfig.crossCurrencyTransferAllowed ? 'Povoleno' : 'Zakázáno'} />
          </div>
        )}

        {tab === 'overdraft' && product.overdraftConfig && (
          <div>
            <SectionHeader icon={<TrendingDown size={13} />} label="Konfigurace debetu" />
            <InfoRow label="Typ" value={product.overdraftConfig.type} />
            <InfoRow label="Max. limit" value={`${product.overdraftConfig.maxLimitAmount.toLocaleString('cs-CZ')} ${product.currency}`} mono />
            <InfoRow label="Sazba (sjednaný)" value={`${(product.overdraftConfig.interestRateAnnual * 100).toFixed(2)} % p.a.`} mono />
            <InfoRow label="Ochranná lhůta" value={`${product.overdraftConfig.gracePeriodDays} dní`} mono />
            <InfoRow label="Auto-schválení" value={product.overdraftConfig.autoApprovalEnabled ? 'Ano' : 'Ne'} />
            {product.overdraftConfig.unarrangedDailyFee != null && <InfoRow label="Poplatek (nesjednaný/den)" value={`${product.overdraftConfig.unarrangedDailyFee.toFixed(2)} ${product.currency}`} mono />}
            {product.overdraftConfig.unarrangedRateAnnual != null && <InfoRow label="Sazba (nesjednaný)" value={`${(product.overdraftConfig.unarrangedRateAnnual * 100).toFixed(2)} % p.a.`} mono />}
          </div>
        )}

        {tab === 'deposit' && product.termDepositConfig && (
          <div>
            <SectionHeader icon={<Clock size={13} />} label="Termínovaný vklad" />
            <InfoRow label="Délka (výchozí)" value={`${product.termDepositConfig.termMonths} měsíců`} mono />
            {product.termDepositConfig.minTermMonths != null && <InfoRow label="Min. délka" value={`${product.termDepositConfig.minTermMonths} měsíců`} mono />}
            {product.termDepositConfig.maxTermMonths != null && <InfoRow label="Max. délka" value={`${product.termDepositConfig.maxTermMonths} měsíců`} mono />}
            <InfoRow label="Sazba" value={`${(product.termDepositConfig.interestRateAnnual * 100).toFixed(3)} % p.a.`} mono />
            <InfoRow label="Výplata úroku" value={product.termDepositConfig.payoutFrequency} />
            <InfoRow label="Auto-obnova" value={product.termDepositConfig.autoRenewEnabled ? 'Ano' : 'Ne'} />
            <InfoRow label="Penalizace předčasného výběru" value={`${product.termDepositConfig.earlyWithdrawalPenaltyPct} % úroku`} mono />
            {product.termDepositConfig.earlyWithdrawalNoticeDays > 0 && <InfoRow label="Výpovědní lhůta" value={`${product.termDepositConfig.earlyWithdrawalNoticeDays} dní`} mono />}
          </div>
        )}

        {tab === 'savings' && product.savingsConfig && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            {product.savingsConfig.interestTiers.length > 0 && (
              <div>
                <SectionHeader icon={<Layers size={13} />} label="Úroková pásma" />
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                    {['Od', 'Do', 'Sazba p.a.'].map(h => <th key={h} style={{ padding: '5px 8px', textAlign: 'left', fontSize: '10px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>)}
                  </tr></thead>
                  <tbody>{product.savingsConfig.interestTiers.map((tier, i) => (
                    <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={{ padding: '6px 8px', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{tier.fromAmount.toLocaleString('cs-CZ')}</td>
                      <td style={{ padding: '6px 8px', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{tier.toAmount != null ? tier.toAmount.toLocaleString('cs-CZ') : '∞'}</td>
                      <td style={{ padding: '6px 8px', fontFamily: 'var(--font-mono)', fontSize: '13px', fontWeight: 700, color: 'var(--success-text)' }}>{(tier.rateAnnual * 100).toFixed(3)} %</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            )}
            <div>
              <SectionHeader icon={<Clock size={13} />} label="Výběry" />
              <InfoRow label="Výpovědní lhůta" value={product.savingsConfig.withdrawalNotice.replace('DAYS_', '') + (product.savingsConfig.withdrawalNotice !== 'NONE' ? ' dní' : ' — žádná')} />
              <InfoRow label="Výběry zdarma/měs." value={String(product.savingsConfig.freeWithdrawalsPerMonth)} mono />
              {product.savingsConfig.excessWithdrawalFee > 0 && <InfoRow label="Poplatek za nadlimitní výběr" value={`${product.savingsConfig.excessWithdrawalFee.toFixed(2)} ${product.currency}`} mono />}
            </div>
            {product.savingsConfig.bonusRateAnnual != null && (
              <div>
                <SectionHeader icon={<CheckCircle2 size={13} />} label="Bonusová sazba" />
                <InfoRow label="Bonus" value={`+${(product.savingsConfig.bonusRateAnnual * 100).toFixed(3)} % p.a.`} mono />
                {product.savingsConfig.bonusRateCondition && <InfoRow label="Podmínka" value={product.savingsConfig.bonusRateCondition} />}
              </div>
            )}
          </div>
        )}

        {tab === 'tac' && (
          <div>
            <SectionHeader icon={<FileText size={13} />} label={`Obchodní podmínky (${product.termsAndConditions?.length ?? 0} verzí)`} />
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {product.termsAndConditions?.map(tac => {
                const isCurrent = !tac.effectiveTo
                return (
                  <div key={tac.id} style={{ padding: '10px 12px', borderRadius: '7px', background: isCurrent ? 'var(--success-bg)' : 'var(--surface-2)', border: `1px solid ${isCurrent ? 'var(--success-border)' : 'var(--border)'}` }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '5px' }}>
                      <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>v{tac.version}</span>
                      {isCurrent && <span style={{ fontSize: '10px', fontWeight: 700, color: 'var(--success-text)', background: 'var(--success-bg)', border: '1px solid var(--success-border)', borderRadius: '8px', padding: '1px 7px' }}>Aktuální</span>}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '4px' }}>
                      Platnost: {tac.effectiveFrom}{tac.effectiveTo ? ` → ${tac.effectiveTo}` : ' → dosud'}
                    </div>
                    {tac.summary && <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{tac.summary}</div>}
                    <a href={tac.url} target="_blank" rel="noopener noreferrer"
                      style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 }}>
                      <ExternalLink size={11} /> {t('Otevřít dokument', 'Open document')}
                    </a>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {tab === 'history' && (
          <div>
            <SectionHeader icon={<History size={13} />} label={`Historie verzí (${product.versionHistory?.length ?? 0})`} />
            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              {product.versionHistory?.map((v, i) => {
                const isCurrent = i === 0
                return (
                  <div key={v.version} style={{ display: 'flex', gap: '10px', padding: '8px 12px', borderRadius: '6px', background: isCurrent ? 'var(--accent)0d' : 'var(--surface-2)', border: `1px solid ${isCurrent ? 'var(--accent)30' : 'var(--border)'}` }}>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px', flexShrink: 0 }}>
                      <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: isCurrent ? 'var(--accent)' : 'var(--border)', border: `2px solid ${isCurrent ? 'var(--accent)' : 'var(--border)'}` }} />
                      {i < (product.versionHistory?.length ?? 0) - 1 && <div style={{ width: '1px', flex: 1, background: 'var(--border)', minHeight: '16px' }} />}
                    </div>
                    <div style={{ flex: 1 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '2px' }}>
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: isCurrent ? 'var(--accent)' : 'var(--text-primary)' }}>v{v.version}</span>
                        {!v.isPublic && <span style={{ fontSize: '9px', padding: '1px 5px', borderRadius: '4px', background: 'var(--surface-3)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>Interní</span>}
                        {isCurrent && <span style={{ fontSize: '9px', padding: '1px 5px', borderRadius: '4px', background: 'var(--accent)18', color: 'var(--accent)', border: '1px solid var(--accent)30' }}>Aktuální</span>}
                      </div>
                      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {v.validFrom}{v.validTo ? ` → ${v.validTo}` : ' → dosud'}
                      </div>
                      {v.changeNote && <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '2px' }}>{v.changeNote}</div>}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

export default function ProductCatalogPage() {
  const { t, language } = useLanguage()
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel (idle /
  // waking / unreachable) instead of a red "service is down" banner.
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [visibilityFilter, setVisibilityFilter] = useState('ALL')

  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [editingProduct, setEditingProduct] = useState<Product | null>(null)
  const [formData, setFormData] = useState<Partial<Product>>({})
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    setActionError(null)
    try {
      const data = await apiFetch('')
      let items: Product[] = Array.isArray(data) ? data : (data.items ?? data.content ?? data.products ?? [])
      items = items.sort((a, b) => {
        if (a.updatedAt && b.updatedAt) return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
        return a.code.localeCompare(b.code)
      })
      setProducts(items)
      if (selectedProduct) {
        const refreshed = items.find(p => p.id === selectedProduct.id)
        if (refreshed) setSelectedProduct(refreshed)
      }
    } catch (e) {
      setProducts([])
      setUnavailable({ kind: e instanceof ApiError ? e.kind : 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [selectedProduct])

  useEffect(() => { load() }, [])

  const filtered = useMemo(() => products.filter(p => {
    if (typeFilter !== 'ALL' && p.type !== typeFilter) return false
    if (statusFilter !== 'ALL' && p.status !== statusFilter) return false
    if (visibilityFilter === 'PUBLIC' && !p.isPublic) return false
    if (visibilityFilter === 'INTERNAL' && p.isPublic) return false
    if (search) {
      const q = search.toLowerCase()
      return p.code?.toLowerCase().includes(q) || p.name?.toLowerCase().includes(q) || p.tags?.some(t => t.toLowerCase().includes(q))
    }
    return true
  }), [products, typeFilter, statusFilter, visibilityFilter, search])

  const uniqueTypes = useMemo(() => Array.from(new Set(products.map(p => p.type).filter(Boolean))), [products])
  const uniqueStatuses = useMemo(() => Array.from(new Set(products.map(p => p.status).filter(Boolean))), [products])

  const openCreateModal = () => {
    setEditingProduct(null)
    setFormData({ code: '', name: '', type: 'CURRENT', currency: 'EUR', status: 'DRAFT', isPublic: true, version: '1.0.0', baseRate: 0, fee: 0 })
    setActionError(null)
    setModalOpen(true)
  }

  const openEditModal = (p: Product) => {
    if (p.status === 'ACTIVE') {
      setActionError(t('Aktivní produkt nejprve deaktivujte.', 'Deactivate the active product before editing.'))
      return
    }
    setEditingProduct(p)
    setFormData({ ...p })
    setActionError(null)
    setModalOpen(true)
  }

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setActionError(null)
    try {
      if (editingProduct?.id) {
        if (editingProduct.revision === undefined) {
          throw new Error(t('Katalog se právě aktualizuje; načtěte stránku znovu.', 'Catalog is upgrading; reload the page.'))
        }
        await apiFetch(`/${editingProduct.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': `"${editingProduct.revision}"` }, body: JSON.stringify(formData) })
      } else {
        await apiFetch('', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formData) })
      }
      await load()
      setModalOpen(false)
    } catch (err: any) {
      setActionError(err.message ?? 'Failed to save product')
    } finally {
      setSaving(false)
    }
  }

  const handleToggleStatus = async (p: Product) => {
    if (!p.id) return
    setActionError(null)
    try {
      if (p.revision === undefined) {
        throw new Error(t('Katalog se právě aktualizuje; načtěte stránku znovu.', 'Catalog is upgrading; reload the page.'))
      }
      const action = p.status === 'ACTIVE' ? 'deactivate' : 'activate'
      await apiFetch(`/${p.id}/${action}`, { method: 'POST', headers: { 'If-Match': `"${p.revision}"` } })
      await load()
    } catch (err: any) {
      setActionError(err.message ?? 'Failed to change status')
    }
  }

  return (
    <AuthGuard permission="payments:view">
      <div style={{ display: 'flex', height: '100%' }}>
        <div style={{ flex: 1, minWidth: 0, padding: '28px 32px', overflowY: 'auto' }}>

          <div className="page-header">
            <div>
              <div className="breadcrumb">
                <span>OpenBank</span><span className="breadcrumb-sep">/</span>
                <span className="breadcrumb-current">{t('Katalog produktů', 'Product Catalog')}</span>
              </div>
              <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Package size={18} style={{ color: 'var(--accent)' }} />
                {t('Katalog produktů', 'Product Catalog')}
              </h1>
              <p className="page-subtitle">{t('Správa bankovních produktů, sazeb, poplatků a obchodních podmínek', 'Manage banking products, rates, fees and terms')}</p>
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button className="btn btn-primary" onClick={openCreateModal} disabled={loading}>
                <Plus size={14} /> {t('Nový produkt', 'New Product')}
              </button>
              <button className="btn btn-secondary" onClick={load} disabled={loading}>
                <RefreshCw size={13} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
                {t('Obnovit', 'Refresh')}
              </button>
            </div>
          </div>

          {unavailable && (
            <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
              <DataUnavailable
                kind={unavailable.kind}
                service={t('Katalog produktů', 'Product Catalog')}
                feature={t('Produkty', 'Products')}
                lang={language}
                dense
              />
            </div>
          )}

          {actionError && (
            <div className="card" style={{ padding: '12px 16px', color: 'var(--danger-text)', marginBottom: '16px', border: '1px solid var(--danger-border)', background: 'var(--danger-bg)', fontSize: '13px' }}>
              <strong>Chyba:</strong> {actionError}
            </div>
          )}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px', marginBottom: '20px' }}>
            {[
              { label: 'Celkem', value: products.length, color: 'var(--text-primary)' },
              { label: 'Aktivní', value: products.filter(p => p.status === 'ACTIVE').length, color: 'var(--success-text)' },
              { label: 'Veřejné', value: products.filter(p => p.isPublic).length, color: 'var(--accent)' },
              { label: 'Draft', value: products.filter(p => p.status === 'DRAFT').length, color: 'var(--warning-text)' },
            ].map(k => (
              <div key={k.label} className="stat-card">
                <div className="stat-value" style={{ color: k.color }}>{loading ? '—' : k.value}</div>
                <div className="stat-label">{k.label}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', gap: '8px', marginBottom: '16px', flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1, minWidth: '220px', maxWidth: '300px' }}>
              <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input className="input" style={{ paddingLeft: '30px', width: '100%' }} placeholder={t('Kód, název, štítek…', 'Code, name, tag…')} value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            {[
              { label: t('Typ', 'Type'), value: typeFilter, set: setTypeFilter, options: [['ALL', t('Všechny typy', 'All types')], ...uniqueTypes.map(v => [v, v])] },
              { label: t('Status', 'Status'), value: statusFilter, set: setStatusFilter, options: [['ALL', t('Všechny', 'All')], ...uniqueStatuses.map(s => [s, s])] },
              { label: t('Viditelnost', 'Visibility'), value: visibilityFilter, set: setVisibilityFilter, options: [['ALL', t('Vše', 'All')], ['PUBLIC', t('Veřejné', 'Public')], ['INTERNAL', t('Interní', 'Internal')]] },
            ].map(f => (
              <div key={f.label} style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{f.label}:</span>
                <select className="input" style={{ width: 'auto', padding: '5px 10px', fontSize: '12px' }} value={f.value} onChange={e => f.set(e.target.value)}>
                  {f.options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </div>
            ))}
          </div>


          <div className="card" style={{ overflow: 'hidden' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th style={{ width: '130px' }}>{t('Kód', 'Code')}</th>
                  <th>{t('Název', 'Name')}</th>
                  <th style={{ width: '120px' }}>{t('Typ', 'Type')}</th>
                  <th style={{ width: '60px' }}>{t('Měna', 'Currency')}</th>
                  <th style={{ width: '90px' }}>{t('Status', 'Status')}</th>
                  <th style={{ width: '80px' }}>{t('Sazba', 'Rate')}</th>
                  <th style={{ width: '80px' }}>{t('Verze', 'Version')}</th>
                  <th style={{ width: '80px' }}>{t('Funkce', 'Features')}</th>
                  <th style={{ width: '70px', textAlign: 'right' }}>{t('Akce', 'Actions')}</th>
                </tr>
              </thead>
              <tbody>
                {loading && Array.from({ length: 6 }).map((_, i) => (
                  <tr key={i}>{Array.from({ length: 9 }).map((_, j) => (
                    <td key={j}><div className="skeleton" style={{ height: '13px', width: j === 1 ? '120px' : '60px' }} /></td>
                  ))}</tr>
                ))}
                {!loading && filtered.length === 0 && (
                  <tr><td colSpan={9} style={{ textAlign: 'center', padding: '40px', color: 'var(--text-tertiary)', fontSize: '13px' }}>
                    {t('Žádné produkty nenalezeny', 'No products found')}
                  </td></tr>
                )}
                {!loading && filtered.map(p => (
                  <tr key={p.id}
                    onClick={() => setSelectedProduct(selectedProduct?.id === p.id ? null : p)}
                    style={{ cursor: 'pointer', background: selectedProduct?.id === p.id ? 'var(--accent)0d' : undefined, borderLeft: selectedProduct?.id === p.id ? '3px solid var(--accent)' : '3px solid transparent' }}>
                    <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 700, fontSize: '11px', color: 'var(--text-primary)' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
                        {!p.isPublic && <span title="Interní produkt"><EyeOff size={10} style={{ color: 'var(--text-tertiary)' }} /></span>}
                        {p.code}
                      </div>
                    </td>
                    <td>
                      <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{p.name}</div>
                      {p.shortDescription && <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '1px' }}>{p.shortDescription}</div>}
                    </td>
                    <td><TypeBadge type={p.type} /></td>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700 }}>{p.currency}</td>
                    <td><StatusBadge status={p.status} /></td>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: p.baseRate && p.baseRate > 0 ? 'var(--success-text)' : 'var(--text-tertiary)' }}>
                      {p.baseRate && p.baseRate > 0 ? `${(p.baseRate * 100).toFixed(2)} %` : '—'}
                    </td>
                    <td>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-tertiary)', background: 'var(--surface-2)', padding: '2px 6px', borderRadius: '4px', border: '1px solid var(--border)' }}>v{p.version}</span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '3px' }}>
                        {p.cardConfig?.enabled && <span title={`${p.cardConfig.minCards}–${p.cardConfig.maxCards} karet`} style={{ color: 'var(--info)' }}><CreditCard size={12} /></span>}
                        {p.multiCurrencyConfig?.enabled && <span title={`${p.multiCurrencyConfig.supportedCurrencies.length} měn`} style={{ color: 'var(--success)' }}><Globe size={12} /></span>}
                        {p.overdraftConfig && <span title={`Debet ${(p.overdraftConfig.interestRateAnnual * 100).toFixed(1)} %`} style={{ color: 'var(--warning)' }}><TrendingDown size={12} /></span>}
                        {p.termDepositConfig && <span title={`${p.termDepositConfig.termMonths}M`} style={{ color: 'var(--success)' }}><Clock size={12} /></span>}
                        {(p.termsAndConditions?.length ?? 0) > 0 && <span title="VOP" style={{ color: 'var(--text-tertiary)' }}><FileText size={12} /></span>}
                      </div>
                    </td>
                    <td style={{ textAlign: 'right' }} onClick={e => e.stopPropagation()}>
                      <div style={{ display: 'flex', gap: '3px', justifyContent: 'flex-end' }}>
                        <button className="btn btn-secondary btn-sm" disabled={p.status === 'ACTIVE'} onClick={() => openEditModal(p)} style={{ padding: '4px' }} title={p.status === 'ACTIVE' ? t('Nejprve deaktivujte', 'Deactivate before editing') : t('Upravit', 'Edit')}>
                          <Edit size={13} />
                        </button>
                        <button className="btn btn-secondary btn-sm" onClick={() => handleToggleStatus(p)} style={{ padding: '4px', color: p.status === 'ACTIVE' ? 'var(--warning-text)' : 'var(--success-text)' }} title={p.status === 'ACTIVE' ? t('Deaktivovat', 'Deactivate') : t('Aktivovat', 'Activate')}>
                          {p.status === 'ACTIVE' ? <Square size={13} /> : <Play size={13} />}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {selectedProduct && (
          <ProductDetailPanel
            product={selectedProduct}
            onClose={() => setSelectedProduct(null)}
            onEdit={() => openEditModal(selectedProduct)}
            onToggleStatus={() => handleToggleStatus(selectedProduct)}
          />
        )}
      </div>

      {modalOpen && (
        <div style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%', background: 'rgba(15,23,42,0.65)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '20px' }}>
          <div className="card" style={{ width: '560px', maxWidth: '100%', maxHeight: '90vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 20px', borderBottom: '1px solid var(--border)' }}>
              <h2 style={{ fontSize: '15px', fontWeight: 700 }}>
                {editingProduct ? t('Upravit produkt', 'Edit Product') : t('Nový produkt', 'New Product')}
              </h2>
              <button onClick={() => setModalOpen(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)' }}><X size={18} /></button>
            </div>
            <form onSubmit={handleSave} style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div className="grid-2">
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Kód *</label>
                  <input className="input" required disabled={!!editingProduct} value={formData.code ?? ''} onChange={e => setFormData(p => ({ ...p, code: e.target.value }))} placeholder="SAVINGS_STANDARD" />
                </div>
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Typ *</label>
                  <select className="input" required value={formData.type ?? ''} onChange={e => setFormData(p => ({ ...p, type: e.target.value }))}>
                    {['SAVINGS', 'CURRENT', 'LOAN', 'MORTGAGE', 'CREDIT_CARD', 'TERM_DEPOSIT', 'OVERDRAFT', 'INVESTMENT'].map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Název *', 'Name *')}</label>
                <input className="input" required value={formData.name ?? ''} onChange={e => setFormData(p => ({ ...p, name: e.target.value }))} placeholder={t('Název produktu', 'Product name')} />
              </div>
              <div>
                <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Krátký popis', 'Short description')}</label>
                <input className="input" value={formData.shortDescription ?? ''} onChange={e => setFormData(p => ({ ...p, shortDescription: e.target.value }))} placeholder={t('Zobrazí se v přehledu', 'Shown in the overview')} />
              </div>
              <div className="grid-3">
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Měna *', 'Currency *')}</label>
                  <input className="input" required value={formData.currency ?? 'EUR'} onChange={e => setFormData(p => ({ ...p, currency: e.target.value }))} placeholder="EUR" />
                </div>
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Status', 'Status')}</label>
                  <select className="input" disabled value={formData.status ?? 'DRAFT'} onChange={e => setFormData(p => ({ ...p, status: e.target.value }))}>
                    {['DRAFT', 'ACTIVE', 'INACTIVE', 'DEPRECATED', 'ARCHIVED'].map(s => <option key={s} value={s}>{s}</option>)}
                  </select>
                </div>
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Verze', 'Version')}</label>
                  <input className="input" value={formData.version ?? '1.0.0'} onChange={e => setFormData(p => ({ ...p, version: e.target.value }))} placeholder="1.0.0" />
                </div>
              </div>
              <div className="grid-2">
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Základní sazba (desetinné číslo)', 'Base rate (decimal)')}</label>
                  <input type="number" step="0.0001" className="input" value={formData.baseRate ?? 0} onChange={e => setFormData(p => ({ ...p, baseRate: parseFloat(e.target.value) || 0 }))} />
                </div>
                <div>
                  <label style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Platnost od', 'Valid from')}</label>
                  <input type="date" className="input" value={formData.validFrom ?? ''} onChange={e => setFormData(p => ({ ...p, validFrom: e.target.value }))} />
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '7px', cursor: 'pointer', fontSize: '13px', color: 'var(--text-secondary)' }}>
                  <input type="checkbox" checked={formData.isPublic ?? true} onChange={e => setFormData(p => ({ ...p, isPublic: e.target.checked }))} style={{ width: '15px', height: '15px' }} />
                  {t('Veřejný produkt (zobrazit na kurzovním lístku / webu)', 'Public product (show on rate sheet / website)')}
                </label>
              </div>
              {actionError && (
                <div style={{ padding: '10px 12px', background: 'var(--danger-bg)', color: 'var(--danger-text)', borderRadius: '6px', fontSize: '12px', border: '1px solid var(--danger-border)' }}>
                  {actionError}
                </div>
              )}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', paddingTop: '4px' }}>
                <button type="button" className="btn btn-secondary" onClick={() => setModalOpen(false)} disabled={saving}>Zrušit</button>
                <button type="submit" className="btn btn-primary" disabled={saving}>
                  {saving ? 'Ukládám…' : t('Uložit produkt', 'Save Product')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </AuthGuard>
  )
}
