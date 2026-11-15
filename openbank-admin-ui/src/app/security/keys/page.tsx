// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

/**
 * Klíče a certifikáty (#16292) — evidence i edukace zároveň.
 *
 * Pro všechna oddělení: k čemu který klíč je, co se rozbije, když vyprší, a změřený počet
 * dní do expirace. Pro experty (keys:expert): seznam každého změřeného certifikátu.
 *
 * Šedá nikdy neznamená zelenou: co se nedá změřit, je „neověřeno“ nebo „nesledováno“.
 * Stránka nikdy neukazuje klíč, keystore ani tělo certifikátu — API je vůbec nemá.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { KeyRound, RefreshCw, ShieldCheck, ShieldAlert, Atom, Network, Microscope, BookOpen } from 'lucide-react'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { KeyCustodianExplain } from '@/components/security/KeyCustodianExplain'
import { PageHeader } from '@/components/ui/PageHeader'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { KEY_CATALOG, type KeyEntry } from '@/lib/security/keys/catalog'
import { byUrgency, dependents, ATTENTION_DAYS, CRITICAL_DAYS, type EntryStatus, type KeyStatus } from '@/lib/security/keys/status'
import type { KeysReport } from '@/lib/security/keys/report'

const TONE: Record<KeyStatus, { color: string; bg: string; border: string }> = {
  ok:            { color: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)' },
  attention:     { color: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)' },
  critical:      { color: 'var(--danger-text)',  bg: 'var(--danger-bg)',  border: 'var(--danger-border)' },
  expired:       { color: 'var(--danger-text)',  bg: 'var(--danger-bg)',  border: 'var(--danger-border)' },
  unknown:       { color: 'var(--text-secondary)', bg: 'var(--surface-3)', border: 'var(--border-strong)' },
  untracked:     { color: 'var(--text-secondary)', bg: 'var(--surface-3)', border: 'var(--border-strong)' },
  rotation:      { color: 'var(--info-text)',    bg: 'var(--info-bg)',    border: 'var(--info-border)' },
  'short-lived': { color: 'var(--info-text)',    bg: 'var(--info-bg)',    border: 'var(--info-border)' },
}

export default function KeysAndCertificatesPage() {
  const { language, t } = useLanguage()
  const [report, setReport] = useState<KeysReport | null>(null)
  const [failed, setFailed] = useState(false)
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/security/keys', { cache: 'no-store' })
      if (!res.ok) throw new Error(String(res.status))
      setReport(await res.json() as KeysReport)
      setFailed(false)
    } catch {
      setFailed(true)
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => { void load() }, [load])

  const name = (e: KeyEntry) => (language === 'cs' ? e.nameCs : e.nameEn)
  const statusOf = useMemo(() => new Map((report?.entries ?? []).map((s) => [s.id, s])), [report])
  const label = (s: KeyStatus) => ({
    ok: t('V pořádku', 'Healthy'),
    attention: t(`Do ${ATTENTION_DAYS} dní`, `Within ${ATTENTION_DAYS} days`),
    critical: t(`Do ${CRITICAL_DAYS} dní`, `Within ${CRITICAL_DAYS} days`),
    expired: t('Vypršel', 'Expired'),
    unknown: t('Neověřeno', 'Not verified'),
    untracked: t('Nesledováno', 'Not tracked'),
    rotation: t('Bez expirace, rotuje se', 'No expiry, rotated'),
    'short-lived': t('Krátkodobé (minuty)', 'Short-lived (minutes)'),
  }[s])
  const days = (s?: EntryStatus) => (s?.daysLeft == null ? null : s.daysLeft < 0
    ? t(`před ${-s.daysLeft} dny`, `${-s.daysLeft} days ago`)
    : t(`${s.daysLeft} dní`, `${s.daysLeft} days`))

  const urgent = [...(report?.entries ?? [])].filter((s) => ['expired', 'critical', 'attention', 'unknown'].includes(s.status)).sort(byUrgency)
  const counts = (report?.entries ?? []).reduce<Record<string, number>>((acc, s) => ({ ...acc, [s.status]: (acc[s.status] ?? 0) + 1 }), {})
  const roots = KEY_CATALOG.filter((e) => !e.parent && KEY_CATALOG.some((c) => c.parent === e.id))
  const blast = selected ? dependents(selected) : []
  const quantumKx = KEY_CATALOG.filter((e) => e.quantum === 'key-exchange')
  const quantumSig = KEY_CATALOG.filter((e) => e.quantum === 'signature')
  const quantumSym = KEY_CATALOG.filter((e) => e.quantum === 'symmetric')

  const Badge = ({ s }: { s: KeyStatus }) => (
    <span style={{ fontSize: 12, fontWeight: 600, padding: '2px 10px', borderRadius: 999, whiteSpace: 'nowrap', color: TONE[s].color, background: TONE[s].bg, border: `1px solid ${TONE[s].border}` }}>
      {label(s)}
    </span>
  )
  const box = { border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px', background: 'var(--surface-1)' } as const

  return (
    <AuthGuard permission="keys:view">
      <div style={{ padding: '28px 32px', maxWidth: 1400 }}>
        <PageHeader
          icon={<KeyRound size={20} aria-hidden="true" />}
          title={t('Klíče a certifikáty', 'Keys & Certificates')}
          subtitle={t(
            'Jaké klíče a certifikáty banka má, k čemu slouží, co se stane, když vyprší — a kolik dní jim zbývá. Žádný klíč se tu nikdy nezobrazuje.',
            'Which keys and certificates the bank holds, what each is for, what happens when it expires, and how many days are left. No key is ever shown here.',
          )}
          actions={
            <button type="button" onClick={() => void load()} disabled={loading} aria-busy={loading} className="btn btn-secondary btn-sm">
              <RefreshCw aria-hidden="true" size={13} /> {t('Obnovit', 'Refresh')}
            </button>
          }
        />

        {failed && <DataUnavailable kind="error" feature={t('Klíče a certifikáty', 'Keys & Certificates')} lang={language} />}

        {report && (
          <>
            {/* ── Souhrn ─────────────────────────────────────────────────── */}
            <section aria-label={t('Souhrn', 'Summary')} style={{ ...box, margin: '20px 0', display: 'flex', gap: 28, flexWrap: 'wrap', alignItems: 'center', background: 'var(--surface-2)' }}>
              {urgent.length === 0
                ? <ShieldCheck size={40} aria-hidden="true" style={{ color: 'var(--success)' }} />
                : <ShieldAlert size={40} aria-hidden="true" style={{ color: 'var(--warning)' }} />}
              <div style={{ flex: 1, minWidth: 260 }}>
                <strong style={{ fontSize: 18 }}>
                  {urgent.length === 0
                    ? t('Nic nehoří. Vše měřitelné má víc než 30 dní.', 'Nothing urgent. Everything measurable has more than 30 days.')
                    : t(`${urgent.length} položek potřebuje pozornost`, `${urgent.length} items need attention`)}
                </strong>
                <p style={{ margin: '6px 0 0', fontSize: 13, color: 'var(--text-secondary)' }}>
                  {t('Změřeno', 'Measured')}: {new Date(report.measuredAt).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}
                  {' · '}cert-manager: {report.reachable.certManager ? t('odpovídá', 'reachable') : t('NEODPOVÍDÁ', 'NOT REACHABLE')}
                  {' · '}Kafka CA: {report.reachable.strimzi ? t('odpovídá', 'reachable') : t('NEODPOVÍDÁ', 'NOT REACHABLE')}
                </p>
              </div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {(Object.keys(TONE) as KeyStatus[]).filter((s) => counts[s]).map((s) => (
                  <span key={s} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}><Badge s={s} /><strong>{counts[s]}</strong></span>
                ))}
              </div>
            </section>

            {urgent.length > 0 && (
              <section aria-label={t('Potřebuje pozornost', 'Needs attention')} style={{ ...box, marginBottom: 20, borderColor: 'var(--warning-border)' }}>
                <h2 style={{ fontSize: 15, margin: '0 0 10px' }}>{t('Potřebuje pozornost', 'Needs attention')}</h2>
                <ul style={{ margin: 0, paddingLeft: 18, display: 'grid', gap: 6 }}>
                  {urgent.map((s) => {
                    const e = KEY_CATALOG.find((x) => x.id === s.id)!
                    return (
                      <li key={s.id}>
                        <Badge s={s.status} /> <strong>{name(e)}</strong>{days(s) ? ` — ${days(s)}` : ''}
                        <span style={{ color: 'var(--text-secondary)' }}>
                          {' · '}{s.status === 'unknown'
                            ? t('měření chybí — bez dat nelze tvrdit, že je v pořádku', 'measurement missing — without data it cannot be called healthy')
                            : (language === 'cs' ? e.impactCs : e.impactEn)}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              </section>
            )}

            {/* ── Strom důvěry + dopad ───────────────────────────────────── */}
            <section aria-label={t('Strom důvěry', 'Trust tree')} style={{ ...box, marginBottom: 20 }}>
              <h2 style={{ fontSize: 15, margin: '0 0 4px', display: 'flex', gap: 8, alignItems: 'center' }}><Network size={16} aria-hidden="true" />{t('Strom důvěry a dopad výpadku', 'Trust tree and blast radius')}</h2>
              <p style={{ margin: '0 0 14px', fontSize: 13, color: 'var(--text-secondary)' }}>
                {t('Klikněte na autoritu: zvýrazní se vše, co s ní padá.', 'Click an authority: everything that falls with it lights up.')}
              </p>
              {roots.map((r) => (
                <div key={r.id} style={{ display: 'grid', gap: 10 }}>
                  <button type="button" onClick={() => setSelected(selected === r.id ? null : r.id)} aria-pressed={selected === r.id}
                    style={{ ...box, textAlign: 'left', cursor: 'pointer', display: 'flex', gap: 10, alignItems: 'center', borderColor: selected === r.id ? 'var(--danger-border)' : 'var(--border)' }}>
                    <KeyRound size={16} aria-hidden="true" /><strong style={{ flex: 1 }}>{name(r)}</strong>
                    {statusOf.get(r.id) && <Badge s={statusOf.get(r.id)!.status} />}
                    <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>{days(statusOf.get(r.id))}</span>
                  </button>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 10, paddingLeft: 28, borderLeft: '2px solid var(--border)' }}>
                    {KEY_CATALOG.filter((c) => c.parent === r.id).map((c) => {
                      const hit = blast.some((b) => b.id === c.id)
                      const st = statusOf.get(c.id)
                      return (
                        <div key={c.id} style={{ ...box, padding: '12px 14px', borderColor: hit ? 'var(--danger-border)' : 'var(--border)', background: hit ? 'var(--danger-bg)' : 'var(--surface-1)' }}>
                          <strong style={{ fontSize: 14 }}>{name(c)}</strong>
                          <div style={{ marginTop: 6, display: 'flex', gap: 8, alignItems: 'center' }}>
                            {st && <Badge s={st.status} />}
                            {st && st.count > 1 && <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>{t(`${st.count} certifikátů, nejbližší ${days(st)}`, `${st.count} certificates, earliest ${days(st)}`)}</span>}
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </div>
              ))}
            </section>

            {/* ── Katalog: edukace ───────────────────────────────────────── */}
            <h2 style={{ fontSize: 15, margin: '0 0 10px', display: 'flex', gap: 8, alignItems: 'center' }}><BookOpen size={16} aria-hidden="true" />{t('Co máme a proč', 'What we hold and why')}</h2>
            <section aria-label={t('Katalog klíčů', 'Key catalogue')} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14, marginBottom: 20 }}>
              {KEY_CATALOG.map((e) => {
                const st = statusOf.get(e.id)
                return (
                  <article key={e.id} style={{ ...box, display: 'flex', flexDirection: 'column', gap: 8 }}>
                    <header style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                      <strong style={{ flex: 1 }}>{name(e)}</strong>
                      {st && <Badge s={st.status} />}
                    </header>
                    {days(st) && <div style={{ fontSize: 24, fontWeight: 700, color: st ? TONE[st.status].color : undefined }}>{days(st)}</div>}
                    <p style={{ margin: 0, fontSize: 13 }}><strong>{t('K čemu: ', 'What for: ')}</strong>{language === 'cs' ? e.purposeCs : e.purposeEn}</p>
                    <p style={{ margin: 0, fontSize: 13, color: 'var(--text-secondary)' }}><strong>{t('Když vyprší: ', 'If it expires: ')}</strong>{language === 'cs' ? e.impactCs : e.impactEn}</p>
                    <Can permission="agent:view"><KeyCustodianExplain entryId={e.id} /></Can>
                    <footer style={{ marginTop: 'auto', fontSize: 12, color: 'var(--text-tertiary)' }}>
                      {t('Vlastník', 'Owner')}: {e.owner} · {e.algorithm}
                    </footer>
                  </article>
                )
              })}
            </section>

            {/* ── Postkvantová připravenost ──────────────────────────────── */}
            <section aria-label={t('Postkvantová připravenost', 'Post-quantum readiness')} style={{ ...box, marginBottom: 20 }}>
              <h2 style={{ fontSize: 15, margin: '0 0 4px', display: 'flex', gap: 8, alignItems: 'center' }}><Atom size={16} aria-hidden="true" />{t('Postkvantová připravenost (krypto inventář)', 'Post-quantum readiness (crypto inventory)')}</h2>
              <p style={{ margin: '0 0 14px', fontSize: 13, color: 'var(--text-secondary)' }}>
                {t(
                  'Kvantový počítač by prolomil dnešní asymetrickou kryptografii (ECDSA, RSA). Nejvíc spěchá šifrování provozu: útočník může data nahrát dnes a rozšifrovat později.',
                  'A quantum computer would break today\'s asymmetric cryptography (ECDSA, RSA). Traffic encryption is the most urgent: an attacker can record data today and decrypt it later.',
                )}
              </p>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
                {[
                  { list: quantumKx, s: 'critical' as KeyStatus, h: t('Ulož teď, rozšifruj později', 'Harvest now, decrypt later'), d: t('Výměna klíčů v TLS. Řešení: hybridní X25519MLKEM768 na veřejné hraně, s klasickým X25519 jako zálohou.', 'TLS key exchange. Fix: hybrid X25519MLKEM768 at the public edge, with classical X25519 as fallback.') },
                  { list: quantumSig, s: 'attention' as KeyStatus, h: t('Podpisy', 'Signatures'), d: t('Padělatelné až s kvantovým počítačem. Pečeť a CA mají dlouhou životnost, plánujte migraci.', 'Forgeable only once a quantum computer exists. Seals and CAs live long; plan migration.') },
                  { list: quantumSym, s: 'ok' as KeyStatus, h: t('Symetrické klíče', 'Symmetric keys'), d: t('AES/HMAC s 256bitovým klíčem zůstávají dostatečné.', 'AES/HMAC with 256-bit keys remain adequate.') },
                ].map((g) => (
                  <div key={g.h} style={{ ...box, padding: '12px 14px', borderColor: TONE[g.s].border }}>
                    <strong>{g.h}</strong> <span style={{ color: 'var(--text-tertiary)' }}>({g.list.length})</span>
                    <p style={{ margin: '6px 0', fontSize: 12, color: 'var(--text-secondary)' }}>{g.d}</p>
                    <ul style={{ margin: 0, paddingLeft: 16, fontSize: 13 }}>
                      {g.list.map((e) => (
                        <li key={e.id}>{name(e)} <span style={{ color: 'var(--text-tertiary)' }}>· {{ automatic: t('výměna automaticky', 'swaps automatically'), routine: t('výměna rutinní', 'routine swap'), ceremony: t('výměna = ceremonie', 'swap = ceremony') }[e.agility]}</span></li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </section>

            {/* ── Expert ─────────────────────────────────────────────────── */}
            {report.expert && (
              <section aria-label={t('Expertní pohled', 'Expert view')} style={{ ...box }}>
                <h2 style={{ fontSize: 15, margin: '0 0 10px', display: 'flex', gap: 8, alignItems: 'center' }}><Microscope size={16} aria-hidden="true" />{t('Expert: každý změřený certifikát', 'Expert: every measured certificate')}</h2>
                {report.expert.length === 0
                  ? <DataUnavailable kind="no_data" feature={t('Metriky expirace', 'Expiry metrics')} lang={language} dense />
                  : (
                    <div style={{ overflowX: 'auto' }}>
                      <table className="table" style={{ width: '100%', fontSize: 13 }}>
                        <thead><tr><th>{t('Stav', 'Status')}</th><th>{t('Dní', 'Days')}</th><th>Namespace</th><th>{t('Název', 'Name')}</th><th>{t('Vydavatel', 'Issuer')}</th><th>{t('Zdroj', 'Source')}</th></tr></thead>
                        <tbody>
                          {report.expert.map((c) => (
                            <tr key={`${c.source}/${c.namespace}/${c.name}`}>
                              <td><Badge s={c.status} /></td><td>{c.daysLeft}</td><td>{c.namespace}</td><td><code>{c.name}</code></td><td>{c.issuer}</td><td>{c.source}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
              </section>
            )}
          </>
        )}
      </div>
    </AuthGuard>
  )
}
