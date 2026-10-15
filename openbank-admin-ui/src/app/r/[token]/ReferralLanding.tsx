// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect } from 'react'
import { useParams } from 'next/navigation'
import { Gift, ShieldCheck, UserPlus, Sparkles } from 'lucide-react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import styles from './referral-landing.module.css'

// Issued by openbank-referral-service: base64url (no padding), 16–128 chars.
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{16,128}$/

// Where the invite code is parked so the authenticated customer app can pick it up
// and POST /customer/v1/referrals/attributions after sign-up/login.
export const REFERRAL_TOKEN_STORAGE_KEY = 'openbank.referralToken'

// There is no public sign-up page in this console — /auth/login is the only pre-auth
// route (isPublicSurface). The invite code rides along as `ref` so attribution can
// happen after the visitor authenticates.
const signInHref = (token: string) => `/auth/login?ref=${token}`

const STEPS = [
  {
    icon: UserPlus,
    title: 'Create your account',
    body: 'Continue to sign up — it only takes a few minutes. Your invite code is saved with this visit, so nothing gets lost.',
  },
  {
    icon: Sparkles,
    title: 'Your friend gets rewarded',
    body: 'Once your account is open, the invitation is attributed to the friend who shared this link with you.',
  },
  {
    icon: Gift,
    title: 'You get rewarded too',
    body: 'Member-get-member rewards land on both sides of the invitation. Details follow in your onboarding.',
  },
] as const

export function ReferralLanding() {
  const params = useParams<{ token: string }>()
  const token = typeof params.token === 'string' ? params.token : ''
  const valid = TOKEN_PATTERN.test(token)

  // Persist the invite code for the post-login attribution call. Deliberately NOT
  // read during render (sessionStorage is unavailable during SSR and in some privacy
  // modes); a valid token is simply written once on mount.
  useEffect(() => {
    if (valid) sessionStorage.setItem(REFERRAL_TOKEN_STORAGE_KEY, token)
  }, [valid, token])

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <a className={styles.brand} href="/auth/login">OpenBank</a>
      </header>

      {valid ? (
        <>
          <section className={styles.hero} aria-labelledby="invite-title">
            <div className={styles.heroCopy}>
              <p className={styles.eyebrow}><ShieldCheck size={16} aria-hidden="true" />Personal invitation</p>
              <h1 id="invite-title">You&apos;ve been invited to OpenBank.</h1>
              <p className={styles.intro}>
                A friend thinks you&apos;d like banking here — and OpenBank rewards you both once you open
                your account. Your invitation is personal: it was created just for this link.
              </p>
              <div className={styles.actions}>
                <a className={styles.cta} href={signInHref(token)}>Continue to sign up</a>
                <a className={styles.secondary} href={signInHref(token)}>
                  Already a customer? Log in
                </a>
              </div>
            </div>
            <aside className={styles.note}>
              <Gift size={20} aria-hidden="true" />
              <p>
                Your invite code is stored in this browser session. After you sign up or log in, we use it
                to connect you with the friend who invited you — no code to copy, nothing to remember.
              </p>
            </aside>
          </section>

          <section className={styles.steps} aria-label="How the invitation works">
            <div className={styles.stepGrid}>
              {STEPS.map(({ icon: Icon, title, body }, index) => (
                <article key={title}>
                  <span className={styles.stepIcon}><Icon size={20} aria-hidden="true" /></span>
                  <span className={styles.stepNumber}>0{index + 1}</span>
                  <h2>{title}</h2>
                  <p>{body}</p>
                </article>
              ))}
            </div>
          </section>
        </>
      ) : (
        <section className={styles.invalid} aria-labelledby="invalid-title">
          <DataUnavailable
            kind="error"
            title="Invalid or expired invitation link"
            detail="This invitation link isn't valid — it may have been mistyped, or it may have expired. Ask the friend who invited you to share a fresh link."
          />
        </section>
      )}

      <footer className={styles.footer}>
        <span>admin.open-bank.tech</span>
      </footer>
    </main>
  )
}
