// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Bell, Search, HelpCircle, LogOut, ChevronDown } from 'lucide-react'
import { useSession, signOut } from 'next-auth/react'
import Link from 'next/link'
import { useEffect, useState } from 'react'
import { ROLE_LABELS } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CommandPalette } from '@/components/search/CommandPalette'

interface BuildInfo { version: string; gitSha: string; buildDate: string }

export function Header() {
  const { data: session } = useSession()
  const [menuOpen, setMenuOpen] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const { language, setLanguage, t } = useLanguage()
  const [build, setBuild] = useState<BuildInfo | null>(null)

  useEffect(() => {
    let mounted = true
    fetch('/api/build-info', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (mounted && d) setBuild(d) })
      .catch(() => {})
    return () => { mounted = false }
  }, [])

  // ⌘K / Ctrl+K opens the palette anywhere in the app.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen(o => !o)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const user = session?.user
  const roles: string[] = user?.roles ?? []
  // Show highest-privilege role badge
  const primaryRole = ['ROLE_ADMIN','ROLE_COMPLIANCE','ROLE_PAYMENTS','ROLE_AUDITOR','ROLE_OPERATOR','ROLE_VIEWER']
    .find(r => roles.includes(r))
  const roleInfo = primaryRole ? ROLE_LABELS[primaryRole] : null
  const initials = user?.name
    ? user.name.split(' ').map((n: string) => n[0]).join('').toUpperCase().slice(0, 2)
    : user?.email?.[0]?.toUpperCase() ?? 'U'

  return (
    <header style={{
      height: '52px',
      background: 'var(--surface)',
      borderBottom: '1px solid var(--border)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '0 20px',
      flexShrink: 0,
      boxShadow: 'var(--shadow-xs)',
      position: 'relative',
      zIndex: 10,
    }}>
      {/* Search — ADR-0228 D3: the painted placeholder is now a real palette. */}
      <button
        onClick={() => setPaletteOpen(true)}
        aria-label={t('Rychlé hledání (⌘K)', 'Quick search (⌘K)')}
        style={{
          display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-tertiary)',
          background: 'none', border: 'none', cursor: 'pointer', padding: '6px 10px',
          borderRadius: '8px', font: 'inherit',
        }}
        onMouseEnter={e => { e.currentTarget.style.background = 'var(--surface-3)' }}
        onMouseLeave={e => { e.currentTarget.style.background = 'none' }}
      >
        <Search size={14} />
        <span style={{ fontSize: '13px' }}>{t('Rychlé hledání…', 'Quick search…')}</span>
        <kbd style={{
          fontSize: '10px', padding: '1px 5px',
          background: 'var(--surface-3)', border: '1px solid var(--border)',
          borderRadius: '4px', color: 'var(--text-tertiary)', fontFamily: 'inherit',
        }}>⌘K</kbd>
      </button>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />

      {/* Actions */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
        {build && (
          <Link
            href="/docs/release-notes/admin-ui"
            title={
              `admin-ui ${build.version}` +
              `\ngit: ${build.gitSha}` +
              `\nbuilt: ${build.buildDate}` +
              `\n\n${t('Klikni pro poznámky k vydání', 'Click for release notes')}`
            }
            style={{
              fontFamily: 'JetBrains Mono, ui-monospace, monospace',
              fontSize: '10px',
              padding: '3px 8px',
              marginRight: '6px',
              borderRadius: '10px',
              background: 'var(--surface-3)',
              color: 'var(--text-tertiary)',
              border: '1px solid var(--border)',
              letterSpacing: '0.02em',
              cursor: 'pointer',
              textDecoration: 'none',
            }}
            onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
            onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--border)')}
          >
            v{build.version}
            {build.gitSha && build.gitSha !== 'unknown' && (
              <span style={{ opacity: 0.65, marginLeft: '6px' }}>
                {build.gitSha.slice(0, 7)}
              </span>
            )}
          </Link>
        )}
        <button
          onClick={() => setLanguage(language === 'en' ? 'cs' : 'en')}
          style={{
            width: '32px', height: '32px', display: 'flex', alignItems: 'center', justifyContent: 'center',
            borderRadius: '6px', border: 'none', background: 'transparent',
            color: 'var(--text-secondary)', cursor: 'pointer', transition: 'background 0.12s',
            fontSize: '12px', fontWeight: 600,
          }}
          onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-3)')}
          onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
        >
          {language.toUpperCase()}
        </button>
        <HeaderLink href="/docs" label={t('Nápověda a dokumentace', 'Help and documentation')}><HelpCircle size={15} /></HeaderLink>
        <HeaderLink href="/approvals" label={t('Schvalování', 'Approvals')}><Bell size={15} /></HeaderLink>
        <div style={{ width: '1px', height: '20px', background: 'var(--border)', margin: '0 6px' }} />

        {/* User menu */}
        <div style={{ position: 'relative' }}>
          <div
            onClick={() => setMenuOpen(v => !v)}
            style={{
              display: 'flex', alignItems: 'center', gap: '8px',
              padding: '4px 8px 4px 4px', borderRadius: '20px', cursor: 'pointer',
              transition: 'background 0.12s',
              background: menuOpen ? 'var(--surface-3)' : 'transparent',
            }}
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-3)')}
            onMouseLeave={e => { if (!menuOpen) e.currentTarget.style.background = 'transparent' }}
          >
            <div style={{
              width: '26px', height: '26px', background: 'var(--accent)', borderRadius: '50%',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: '11px', fontWeight: 700, color: '#fff', flexShrink: 0,
            }}>{initials}</div>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start' }}>
              <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', lineHeight: 1.2 }}>
                {user?.name ?? user?.email ?? 'User'}
              </span>
              {roleInfo && (
                <span style={{
                  fontSize: '10px', fontWeight: 600, lineHeight: 1.2,
                  color: roleInfo.color,
                }}>
                  {roleInfo.label}
                </span>
              )}
            </div>
            <ChevronDown size={12} style={{ color: 'var(--text-tertiary)', marginLeft: '2px' }} />
          </div>

          {/* Dropdown */}
          {menuOpen && (
            <div style={{
              position: 'absolute', top: 'calc(100% + 6px)', right: 0,
              width: '240px', background: 'var(--surface)', border: '1px solid var(--border)',
              borderRadius: '10px', boxShadow: 'var(--shadow-lg)', padding: '8px',
              zIndex: 100,
            }}>
              {/* User info */}
              <div style={{ padding: '8px 10px 12px', borderBottom: '1px solid var(--border)', marginBottom: '6px' }}>
                <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
                  {user?.name ?? 'User'}
                </div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
                  {user?.email}
                </div>
                {/* Role badges */}
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                  {roles.filter(r => r.startsWith('ROLE_')).map(r => {
                    const info = ROLE_LABELS[r]
                    if (!info) return null
                    return (
                      <span key={r} style={{
                        fontSize: '10px', fontWeight: 600, padding: '2px 7px',
                        borderRadius: '10px', background: info.bg, color: info.color,
                        border: `1px solid ${info.color}22`,
                      }}>{info.label}</span>
                    )
                  })}
                </div>
              </div>

              {/* Sign out — federated (ADR-0080 P1 / F-AUTH-04): clear the local session AND
                  the Keycloak SSO session, otherwise navigating Back silently re-authenticates. */}
              <button
                onClick={async () => {
                  setMenuOpen(false)
                  let kcLogoutUrl: string | null = null
                  try {
                    const r = await fetch('/api/auth/federated-logout', { cache: 'no-store' })
                    if (r.ok) kcLogoutUrl = (await r.json()).url
                  } catch { /* fall back to local logout below */ }
                  await signOut({ redirect: false })
                  window.location.href = kcLogoutUrl ?? '/auth/login'
                }}
                style={{
                  width: '100%', display: 'flex', alignItems: 'center', gap: '8px',
                  padding: '8px 10px', borderRadius: '6px', border: 'none',
                  background: 'transparent', color: '#dc2626', fontSize: '13px',
                  fontWeight: 500, cursor: 'pointer', transition: 'background 0.12s',
                }}
                onMouseEnter={e => (e.currentTarget.style.background = '#fef2f2')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
              >
                <LogOut size={14} />
                {t('Odhlásit se', 'Sign out')}
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

function HeaderLink({ href, label, children }: { href: string; label: string; children: React.ReactNode }) {
  return (
    <Link href={href} aria-label={label} title={label} style={{
      width: '32px', height: '32px', display: 'flex', alignItems: 'center', justifyContent: 'center',
      borderRadius: '6px', border: 'none', background: 'transparent',
      color: 'var(--text-secondary)', cursor: 'pointer', transition: 'background 0.12s', textDecoration: 'none',
    }}
      onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-3)')}
      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
    >
      {children}
    </Link>
  )
}
