// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useRef, useEffect, useState } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useSession } from 'next-auth/react'
import { useInitialRoles } from '@/components/auth/InitialRolesContext'
import { ChevronDown, Lock, Star, X as XIcon, PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import { ALL_NAV, coreNav, currentNavigation, isNavLocked, navigationAreas, visibleNavigation, type NavItem } from '@/lib/navigation/catalog'
import { useNavigationPreferences } from '@/lib/navigation/preferences'
import { personaForRoles, personaLabel, workspaceFor } from '@/lib/auth/persona'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useDomainOwnership } from '@/components/layout/DomainOwnershipContext'
import styles from './Sidebar.module.css'

export function Sidebar({ mobileOpen, onClose }: { mobileOpen?: boolean; onClose?: () => void }) {
  const pathname = usePathname()
  const { data: session } = useSession()
  const initialRoles = useInitialRoles()
  const ownership = useDomainOwnership()
  const { t, language } = useLanguage()
  // The server already rendered this shell with the operator's roles (RootLayout reads
  // the session cookie); until the client useSession() round-trip resolves, derive the
  // SAME roles so hydration matches and the nav never re-paints under the user (the
  // admin-operator-access journey measured a stable 0.12+ workspace CLS from that
  // re-paint). When the session lands with identical roles the re-render is a no-op.
  const roles: string[] = session?.user?.roles ?? initialRoles ?? []
  const roleSignature = roles.join('\u0000')
  const filter = (items: NavItem[]) => items.filter(i => visibleNavigation(roles).some(item => item.href === i.href))
  const isLocked = (item: NavItem) => isNavLocked(item, roles)
  const currentItem = currentNavigation(pathname)
  const currentHref = currentItem?.href
  const areas = navigationAreas(roles, ownership)
  const preferences = useNavigationPreferences()
  const [areaPickerOpen, setAreaPickerOpen] = useState(false)
  const routeArea = areas.find(area => area.items.some(item => item.href === currentHref))
  const preferredHref = workspaceFor(personaForRoles(roles))[0]?.href
  const preferredArea = areas.find(area => area.items.some(item => item.href === preferredHref))
  const selectedArea = areas.find(area => area.id === preferences.area) ?? routeArea ?? preferredArea ?? areas[0]
  const activeArea = routeArea ?? selectedArea
  const shownArea = selectedArea
  const pickerRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!areaPickerOpen) return
    const close = (event: PointerEvent) => {
      if (!pickerRef.current?.contains(event.target as Node)) setAreaPickerOpen(false)
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [areaPickerOpen])
  useEffect(() => {
    if (routeArea) preferences.selectArea(routeArea.id)
  // Follow route changes, while allowing an explicit area selection before navigating.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname])
  // ADR-0229 D4 (first cut): the persona's quick links pinned at the top — the full menu below
  // follows canonical business ownership. Each link inherits its permission from the same destination's nav entry.
  const persona = personaForRoles(roles)
  // A workspace link inherits its permission from the nav entry with the same href. A link with
  // NO matching entry would inherit `permission: undefined` and so render for every role — so a
  // renamed or removed destination drops out of the workspace instead of becoming an ungated
  // shortcut. `filter` below only removes items that HAVE a permission the operator lacks.
  const workspace = workspaceFor(persona).flatMap(link => {
    const nav = ALL_NAV.find(n => n.href === link.href)
    if (!nav) return []
    return [{
      nameCs: link.nameCs, nameEn: link.nameEn, href: link.href,
      icon: nav.icon, permission: nav.permission, lockedPermission: nav.lockedPermission, deniedRole: nav.deniedRole, external: nav.external,
    }]
  })

  // Persist the nav scroll position across route changes. App-Router keeps this
  // component mounted, but a hard reload or a remount would otherwise snap the
  // list back to the top — annoying when the active item is far down. We restore
  // from sessionStorage on mount and save on every scroll (rAF-throttled).
  const navRef = useRef<HTMLElement>(null)
  const asideRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const aside = asideRef.current
    if (!aside) return
    const narrowViewport = window.matchMedia('(max-width: 860px)')
    const syncInert = () => { aside.inert = narrowViewport.matches && !mobileOpen }
    syncInert()
    narrowViewport.addEventListener('change', syncInert)
    return () => {
      narrowViewport.removeEventListener('change', syncInert)
      aside.inert = false
    }
  }, [mobileOpen])
  useEffect(() => {
    if (!mobileOpen) return
    const frame = requestAnimationFrame(() => asideRef.current?.focus())
    return () => { cancelAnimationFrame(frame) }
  }, [mobileOpen])
  useEffect(() => {
    if (!mobileOpen) return
    const frame = requestAnimationFrame(() => {
      const aside = asideRef.current
      if (!aside || document.activeElement !== document.body) return
      aside.querySelector<HTMLElement>('a, button:not([disabled]), input:not([disabled])')?.focus()
    })
    return () => { cancelAnimationFrame(frame) }
  }, [mobileOpen, roleSignature])
  useEffect(() => {
    const el = navRef.current
    if (!el) return
    const saved = sessionStorage.getItem('ob.sidebar.scroll')
    if (saved) el.scrollTop = Number(saved)
    let raf = 0
    const onScroll = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => sessionStorage.setItem('ob.sidebar.scroll', String(el.scrollTop)))
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => { cancelAnimationFrame(raf); el.removeEventListener('scroll', onScroll) }
  }, [])

  return (
    <aside
      ref={asideRef}
      id="admin-sidebar"
      tabIndex={-1}
      aria-label={t('Hlavní navigace', 'Main navigation')}
      style={{ width: preferences.width, flexBasis: preferences.width }}
      className={`${styles.sidebar} ${preferences.compact ? styles.compact : ''} ${mobileOpen ? styles.mobileOpen : ''}`}
      onTransitionEnd={event => {
        if (mobileOpen && event.propertyName === 'transform' && !event.currentTarget.contains(document.activeElement)) {
          event.currentTarget.focus()
        }
      }}
      onKeyDown={event => {
        if (areaPickerOpen && event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          setAreaPickerOpen(false)
          pickerRef.current?.querySelector('button')?.focus()
          return
        }
        if (!mobileOpen) return
        if (event.key === 'Escape') {
          event.preventDefault()
          onClose?.()
          return
        }
        if (event.key !== 'Tab') return
        const focusable = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('a, button:not([disabled]), input:not([disabled])'))
        const first = focusable[0]
        const last = focusable.at(-1)
        if (!first || !last) return
        if (document.activeElement === event.currentTarget) {
          event.preventDefault()
          ;(event.shiftKey ? last : first).focus()
        } else if (event.shiftKey && document.activeElement === first) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
      }}
    >
      {/* Brand */}
      <div className={styles.brand}>
        <div className={styles.brandLockup}>
          <div className={styles.brandMark}>
            <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="3"/><path d="M3 9h18M9 21V9"/>
            </svg>
          </div>
          <div className={styles.brandText}>
            <div className={styles.brandName}>OpenBank</div>
            <div className={styles.brandSubtitle}>{t('Administrace', 'Admin Portal')}</div>
          </div>
        </div>
      </div>

      <div className={styles.areaPicker} ref={pickerRef}>
        <button type="button" className={styles.areaTrigger} aria-expanded={areaPickerOpen}
          aria-controls="navigation-areas" onClick={() => setAreaPickerOpen(value => !value)}>
          <span><small>{t('Pracovní oblast', 'Workspace area')}</small><strong>{shownArea ? t(shownArea.nameCs, shownArea.nameEn) : t('Můj prostor', 'My workspace')}</strong></span>
          <ChevronDown size={16} aria-hidden="true" />
        </button>
        {areaPickerOpen && <div id="navigation-areas" className={styles.areaList}>
          {areas.map(area => <button type="button" key={area.id} aria-label={t(area.nameCs, area.nameEn)} aria-pressed={shownArea?.id === area.id}
            onClick={() => { preferences.selectArea(area.id); setAreaPickerOpen(false); if (navRef.current) navRef.current.scrollTop = 0 }}>
            <span>{t(area.nameCs, area.nameEn)}</span><small>{area.items.length}</small>
          </button>)}
        </div>}
      </div>
      <nav ref={navRef} className={`ob-sidebar-nav ${styles.nav}`}>
        <NavSection items={filter(coreNav.filter(item => !item.owner))} currentHref={currentHref} />
        <SectionLabel>{t('Můj prostor', 'My workspace')} · {personaLabel(persona, language === 'cs' ? 'cs' : 'en')}</SectionLabel>
        <NavSection items={filter(workspace).filter(item => !isLocked(item))} currentHref={currentHref}
          announceCurrent={!shownArea?.items.some(item => item.href === currentHref)} />
        <SectionLabel>{t('Připnuté pohledy', 'Pinned views')}</SectionLabel>
        {preferences.pins.filter(pin => filter(ALL_NAV).some(item => item.href === pin.pathname && !isLocked(item))).map(pin =>
          <div key={pin.href} className={styles.pinRow}>
            {ALL_NAV.find(item => item.href === pin.pathname)?.external
              ? <a href={pin.href} title={pin.label} className={styles.navItem}><Star size={14} aria-hidden="true" /><span className={styles.navLabel}>{pin.label}</span></a>
              : <Link href={pin.href} title={pin.label} className={styles.navItem}><Star size={14} aria-hidden="true" /><span className={styles.navLabel}>{pin.label}</span></Link>}
            <button type="button" className={styles.unpin} aria-label={`${t('Odepnout', 'Unpin')} ${pin.label}`} onClick={() => preferences.togglePin(pin)}><XIcon /></button>
          </div>)}
        {!preferences.pins.length && <p className={styles.hint}>{t('Připněte si stránku nebo její aktuální filtry.', 'Pin a page or its current filters.')}</p>}
        {shownArea && <section aria-label={t(shownArea.nameCs, shownArea.nameEn)}>
          <SectionLabel>{t(shownArea.nameCs, shownArea.nameEn)}</SectionLabel>
          <NavSection items={shownArea.items} currentHref={currentHref} isLocked={isLocked} />
        </section>}
        {activeArea && activeArea.id !== shownArea?.id && <p className={styles.hint}>{t('Otevřená stránka patří do oblasti', 'Current page belongs to')} {t(activeArea.nameCs, activeArea.nameEn)}</p>}
        <button type="button" className={styles.allAreas} onClick={() => setAreaPickerOpen(value => !value)}>
          {t('Všechny oblasti', 'All areas')} <ChevronDown size={14} aria-hidden="true" />
        </button>
      </nav>

      <div className={styles.preferences}>
        <button type="button" aria-pressed={preferences.compact} onClick={preferences.toggleCompact}>
          {preferences.compact ? <PanelLeftOpen size={14} aria-hidden="true" /> : <PanelLeftClose size={14} aria-hidden="true" />}
          {t('Kompaktní menu', 'Compact menu')}
        </button>
        <label>{t('Šířka', 'Width')}<input type="range" min="248" max="360" step="8" value={preferences.width}
          aria-label={t('Šířka navigace', 'Navigation width')} onChange={event => preferences.setWidth(Number(event.target.value))} /></label>
      </div>
      {/* Footer */}
      <div className={styles.footer}>
        <div className={styles.footerVersion}>{t('OpenBank Admin portál', 'OpenBank Admin Portal')}</div>
        <div className={styles.footerScope}>EBA · PSD2 · CNB · GDPR</div>
      </div>
    </aside>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className={styles.sectionLabel}>
      {children}
    </div>
  )
}

function NavSection({ items, currentHref, announceCurrent = true, isLocked }: { items: NavItem[]; currentHref?: string; announceCurrent?: boolean; isLocked?: (item: NavItem) => boolean }) {
  const { language } = useLanguage()

  if (!items.length) return null
  return (
    <>
      {items.map(item => {
        const active = announceCurrent && item.href === currentHref
        const locked = isLocked?.(item) ?? false
        const Icon = item.icon
        const displayName = language === 'cs' ? item.nameCs : item.nameEn

        if (locked) {
          return (
            <div key={item.href} title={language === 'cs' ? 'Přístup není povolen pro demo účet' : 'Not available for demo account'} className={`${styles.navItem} ${styles.locked}`}>
              <Icon aria-hidden="true" size={16} className={styles.navIcon} />
              <span className={styles.navLabel}>{displayName}</span>
              <Lock aria-hidden="true" size={11} className={styles.lockIcon} />
            </div>
          )
        }

        const row = (
            <div className={`${styles.navItem} ${active ? styles.active : ''}`}>
              <Icon aria-hidden="true" size={16} className={styles.navIcon} />
              <span className={styles.navLabel}>{displayName}</span>

            </div>
        )

        // A tool path is served by its own Ingress, not by the App Router — a
        // next/link would client-side navigate and 404 (ADR-0234). Plain <a>,
        // same origin, so the session cookie reaches the edge gate.
        return item.external ? (
          <a key={item.href} href={item.href} aria-current={active ? 'page' : undefined} title={displayName} style={{ textDecoration: 'none' }}>{row}</a>
        ) : (
          <Link key={item.href} href={item.href} aria-current={active ? 'page' : undefined} title={displayName} style={{ textDecoration: 'none' }}>{row}</Link>
        )
      })}
    </>
  )
}
