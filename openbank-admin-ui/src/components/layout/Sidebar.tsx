// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useRef, useEffect } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useSession } from 'next-auth/react'
import {
  LayoutDashboard, CreditCard, ArrowLeftRight, BookOpen, Settings,
  HeartPulse, SlidersHorizontal, Bot, Users, ShieldCheck, Banknote,
  ScrollText, Bell, Map, FileCode, Shield, Library, FileText, Flag,
  DollarSign, Globe, Repeat, Zap, AlertOctagon, ScanLine,
  Layers, TrendingUp, MessageSquareWarning, Package, Receipt, Server, ShieldAlert, FlaskConical, Cloud,
  PiggyBank, GitBranch, Lock, ClipboardList, Scale, Smartphone,
  ClipboardCheck, Activity, Boxes, Bluetooth, Fingerprint, FileSignature, Network, Waypoints, Workflow,
  Megaphone, Radar,
  Target,
  Share2, Bug,
} from 'lucide-react'
import { hasPermission, Permission } from '@/lib/auth/roles'
import { personaForRoles, personaLabel, workspaceFor } from '@/lib/auth/persona'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import styles from './Sidebar.module.css'

// `external: true` marks a destination that is NOT a Next.js route — today the
// internal tool UIs served as sub-paths of this same host by their own Ingress
// (ADR-0234). They must render as a plain <a>: a next/link would try a
// client-side navigation into the App Router and 404, because no page.tsx backs
// the path. Same origin, so the session cookie still rides along and the edge
// gate can read it.
// `deniedRole` mirrors the per-tool deny-list in src/app/api/gate/route.ts. The
// gate is what ENFORCES it; this only decides how the entry renders, so a denied
// tool shows as a disabled item with the demo-account tooltip instead of a live
// link that 403s. A link that visibly fails reads as a broken platform, which for
// the public demo account is the outcome worth avoiding.
type NavItem = { nameCs: string; nameEn: string; href: string; icon: React.ElementType; permission?: Permission; lockedPermission?: Permission; deniedRole?: string; badge?: string; external?: boolean }

const coreNav: NavItem[] = [
  { nameCs: 'Přehled',      nameEn: 'Dashboard',    href: '/dashboard',    icon: LayoutDashboard },
  { nameCs: 'Účty',         nameEn: 'Accounts',     href: '/accounts',     icon: CreditCard,      permission: 'accounts:view' },
  { nameCs: 'Transakce',    nameEn: 'Transactions', href: '/transactions', icon: ArrowLeftRight,  permission: 'transactions:view' },
  { nameCs: 'Hlavní kniha', nameEn: 'Ledger',       href: '/ledger',       icon: BookOpen,        permission: 'accounts:view' },
  { nameCs: 'Závěrky',      nameEn: 'Closings',     href: '/day-end',      icon: Scale,           permission: 'accounts:view' },
]

const revenueNav: NavItem[] = [
  { nameCs: 'Úvěry',        nameEn: 'Lending',      href: '/lending',      icon: TrendingUp,    permission: 'payments:view' },
  { nameCs: 'Poplatky',     nameEn: 'Fees',         href: '/fees',         icon: Receipt,         permission: 'payments:view' },
]

const customerNav: NavItem[] = [
  { nameCs: 'Strany',      nameEn: 'Parties',    href: '/parties',    icon: Users,          permission: 'parties:view' },
  { nameCs: 'KYC',         nameEn: 'KYC',         href: '/kyc',        icon: ShieldCheck,    permission: 'kyc:view' },
  { nameCs: 'Onboarding',  nameEn: 'Onboarding',  href: '/onboarding', icon: ClipboardList,  permission: 'onboarding:view' },
  { nameCs: 'Ověření identity', nameEn: 'Identity Cases', href: '/identity-cases', icon: Fingerprint, permission: 'identity-cases:view' },
  { nameCs: 'Delegovaný přístup', nameEn: 'Delegated Access', href: '/delegations', icon: Share2, permission: 'delegations:view' },
]

const paymentsNav: NavItem[] = [
  { nameCs: 'Katalog produktů',  nameEn: 'Product Catalog',  href: '/product-catalog',   icon: Package,   permission: 'payments:view' },
  { nameCs: 'Produktové studio', nameEn: 'Product Studio',   href: '/product-studio',    icon: Boxes,     permission: 'catalog:read' },
  { nameCs: 'Platby',            nameEn: 'Payments',         href: '/payments',          icon: Banknote,  permission: 'payments:view' },
  { nameCs: 'Trvalé příkazy',    nameEn: 'Standing Orders',  href: '/standing-orders',   icon: Repeat,    permission: 'payments:view' },
  { nameCs: 'Inkasa (SDD)',      nameEn: 'Direct Debits',    href: '/sdd',               icon: Repeat,    permission: 'payments:view' },
  { nameCs: 'FX',                nameEn: 'FX',               href: '/fx',                icon: DollarSign,permission: 'payments:view' },
  { nameCs: 'SWIFT',             nameEn: 'SWIFT',            href: '/swift',             icon: Globe,     permission: 'payment-rails:view' },
  { nameCs: 'Karty',             nameEn: 'Cards',            href: '/cards',             icon: CreditCard,permission: 'cards:view' },
  { nameCs: 'Clearing',          nameEn: 'Clearing',         href: '/clearing',          icon: Layers,    permission: 'payment-rails:view' },
  { nameCs: 'Úroky',             nameEn: 'Interest',         href: '/interest',          icon: TrendingUp,permission: 'interest:view' },
