// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Bell, Search, HelpCircle, LogOut, ChevronDown, Menu, X } from 'lucide-react'
import { useSession, signOut } from 'next-auth/react'
import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { hasPermission, ROLE_LABELS } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CommandPalette } from '@/components/search/CommandPalette'
import styles from './Header.module.css'

interface BuildInfo { version: string; gitSha: string; buildDate: string }

export function Header({ mobileNavOpen, onMenuToggle }: { mobileNavOpen?: boolean; onMenuToggle?: () => void }) {
  const { data: session } = useSession()
  const [menuOpen, setMenuOpen] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const mobileMenuRef = useRef<HTMLButtonElement>(null)
  const userMenuButtonRef = useRef<HTMLButtonElement>(null)
  const userMenuRef = useRef<HTMLDivElement>(null)
  const wasUserMenuOpen = useRef(false)
  const wasMobileNavOpen = useRef(false)
  const { language, setLanguage, t } = useLanguage()
  const [build, setBuild] = useState<BuildInfo | null>(null)

  useEffect(() => {
    if (wasMobileNavOpen.current && !mobileNavOpen) mobileMenuRef.current?.focus()
    wasMobileNavOpen.current = Boolean(mobileNavOpen)
  }, [mobileNavOpen])

  // Keep the small account menu keyboard-complete: focus enters the menu,
  // Escape/outside click closes it, and focus returns to the opener. Without
  // this, keyboard users can land behind the menu and lose their place.
  useEffect(() => {
    if (menuOpen) {
      wasUserMenuOpen.current = true
      const frame = requestAnimationFrame(() => userMenuRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus())
      const onKeyDown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          setMenuOpen(false)
        }
      }
      const onPointerDown = (event: PointerEvent) => {
        if (!userMenuRef.current?.contains(event.target as Node) && !userMenuButtonRef.current?.contains(event.target as Node)) setMenuOpen(false)
      }
      window.addEventListener('keydown', onKeyDown)
      document.addEventListener('pointerdown', onPointerDown)
      return () => {
        cancelAnimationFrame(frame)
        window.removeEventListener('keydown', onKeyDown)
        document.removeEventListener('pointerdown', onPointerDown)
      }
    }
    if (wasUserMenuOpen.current) userMenuButtonRef.current?.focus()
    wasUserMenuOpen.current = menuOpen
  }, [menuOpen])

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
  const primaryRole = ['ROLE_ADMIN', 'ROLE_SUPERVISOR', 'ROLE_COMPLIANCE', 'ROLE_KYC_REVIEWER', 'ROLE_KYC_OPENER', 'ROLE_KYC', 'ROLE_PAYMENTS', 'ROLE_AUDITOR', 'ROLE_OPERATOR', 'ROLE_VIEWER']
    .find(r => roles.includes(r))
  const roleInfo = primaryRole ? ROLE_LABELS[primaryRole] : null
  const canReadDocs = hasPermission(roles, 'docs:view')
  const canViewApprovals = hasPermission(roles, 'approvals:view')
  const initials = user?.name
    ? user.name.split(' ').map((n: string) => n[0]).join('').toUpperCase().slice(0, 2)
    : user?.email?.[0]?.toUpperCase() ?? 'U'

  return (
    <header className={styles.header}>
      <button
        type="button"
        ref={mobileMenuRef}
        className={styles.mobileMenu}
        aria-label={mobileNavOpen ? t('Zavřít navigaci', 'Close navigation') : t('Otevřít navigaci', 'Open navigation')}
        aria-expanded={mobileNavOpen}
        aria-controls="admin-sidebar"
        onClick={onMenuToggle}
      >
        {mobileNavOpen ? <X size={18} aria-hidden="true" /> : <Menu size={18} aria-hidden="true" />}
      </button>
      {/* Search — ADR-0228 D3: the painted placeholder is now a real palette. */}
      <button
        type="button"
        onClick={() => setPaletteOpen(true)}
        aria-label={t('Rychlé hledání (⌘K)', 'Quick search (⌘K)')}
        className={styles.searchTrigger}
      >
        <Search size={14} aria-hidden="true" />
        <span style={{ fontSize: '13px' }}>{t('Rychlé hledání…', 'Quick search…')}</span>
        <kbd style={{
          fontSize: '10px', padding: '1px 5px',
          background: 'var(--surface-3)', border: '1px solid var(--border)',
          borderRadius: '4px', color: 'var(--text-secondary)', fontFamily: 'inherit',
        }}>⌘K</kbd>
      </button>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />

      {/* Actions */}
      <div className={styles.actions}>
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
              color: 'var(--text-secondary)',
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
          type="button"
          aria-label={t('Přepnout na angličtinu', 'Switch to Czech')}
          title={t('Přepnout jazyk', 'Switch language')}
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
        {canReadDocs && <HeaderLink href="/docs" label={t('Nápověda a dokumentace', 'Help and documentation')}><HelpCircle size={15} aria-hidden="true" /></HeaderLink>}
        {canViewApprovals && <HeaderLink href="/approvals" label={t('Schvalování', 'Approvals')}><Bell size={15} aria-hidden="true" /></HeaderLink>}
        <div style={{ width: '1px', height: '20px', background: 'var(--border)', margin: '0 6px' }} />

        {/* User menu */}
        <div style={{ position: 'relative' }}>
          <button
            type="button"
            ref={userMenuButtonRef}
            onClick={() => setMenuOpen(v => !v)}
            aria-expanded={menuOpen}
            aria-haspopup="menu"
            aria-controls={menuOpen ? 'admin-user-menu' : undefined}
            aria-label={t('Otevřít uživatelskou nabídku', 'Open user menu')}
            style={{
              display: 'flex', alignItems: 'center', gap: '8px',
              padding: '4px 8px 4px 4px', border: 'none', borderRadius: '20px', cursor: 'pointer',
              transition: 'background 0.12s',
              background: menuOpen ? 'var(--surface-3)' : 'transparent',
              font: 'inherit', textAlign: 'left',
            }}
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-3)')}
            onMouseLeave={e => { if (!menuOpen) e.currentTarget.style.background = 'transparent' }}
          >
            <div style={{
              width: '26px', height: '26px', background: 'var(--ob-accent-hover)', borderRadius: '50%',
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
            <ChevronDown aria-hidden="true" size={12} style={{ color: 'var(--text-tertiary)', marginLeft: '2px' }} />
          </button>

          {/* Dropdown */}
          {menuOpen && (
            <div id="admin-user-menu" ref={userMenuRef} role="menu" aria-label={t('Uživatelská nabídka', 'User menu')} style={{
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
                type="button"
                role="menuitem"
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
                <LogOut size={14} aria-hidden="true" />
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
    <Link href={href} aria-label={label} title={label} className={styles.headerLink}>
      {children}
    </Link>
  )
}
