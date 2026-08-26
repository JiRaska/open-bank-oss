// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"

import { ArrowRight, CheckCircle2, KeyRound, Languages, Loader2, ShieldCheck, Sparkles } from "lucide-react"
import { signIn } from "next-auth/react"
import { useSearchParams } from "next/navigation"
import { Suspense, useState } from "react"
import { useLanguage } from "@/lib/i18n/LanguageContext"
import { safeCallbackPath } from "@/lib/auth/safeCallbackPath"
import styles from "./login.module.css"

const COPY = {
  en: {
    skip: "Skip to sign in",
    eyebrow: "OpenBank Operations",
    headline: "Operate with confidence.",
    intro: "One calm, governed workspace for the people who keep a bank moving.",
    benefits: ["A shared operational picture", "Decisions with context", "Security built into every action"],
    secure: "Secure access",
    welcome: "Welcome back",
    guidance: "Continue with your bank identity. Your access is limited to the roles and responsibilities assigned to you.",
    signIn: "Continue with Keycloak SSO",
    signingIn: "Connecting securely…",
    trustTitle: "Protected by zero-trust controls",
    trustBody: "Role-based access, four-eyes approvals and audit evidence remain active throughout your session.",
    help: "Need access? Contact your bank administrator.",
    privacy: "Privacy and data protection",
    sessionExpired: "Your session expired. Sign in again to continue safely.",
    genericError: "We could not complete sign-in. Please try again or contact your administrator.",
    switchLanguage: "Přepnout do češtiny",
    locale: "Čeština",
  },
  cs: {
    skip: "Přeskočit k přihlášení",
    eyebrow: "OpenBank Operations",
    headline: "Řiďte banku s jistotou.",
    intro: "Jedno klidné a řízené pracovní prostředí pro všechny, kdo zajišťují chod banky.",
    benefits: ["Společný provozní přehled", "Rozhodnutí v souvislostech", "Bezpečnost v každém kroku"],
    secure: "Zabezpečený přístup",
    welcome: "Vítejte zpět",
    guidance: "Pokračujte pomocí bankovní identity. Uvidíte pouze agendy odpovídající vašim rolím a odpovědnostem.",
    signIn: "Pokračovat přes Keycloak SSO",
    signingIn: "Navazuji zabezpečené spojení…",
    trustTitle: "Chráněno principy zero trust",
    trustBody: "Řízení přístupu podle rolí, čtyřočkové schvalování a auditní stopa zůstávají aktivní po celou relaci.",
    help: "Potřebujete přístup? Obraťte se na administrátora banky.",
    privacy: "Soukromí a ochrana dat",
    sessionExpired: "Vaše relace vypršela. Pro bezpečné pokračování se znovu přihlaste.",
    genericError: "Přihlášení se nepodařilo dokončit. Zkuste to znovu nebo kontaktujte administrátora.",
    switchLanguage: "Switch to English",
    locale: "English",
  },
} as const

function LoginContent() {
  const params = useSearchParams()
  const { language, setLanguage } = useLanguage()
  const [pending, setPending] = useState(false)
  const copy = COPY[language]
  const error = params.get("error")
  const callbackUrl = safeCallbackPath(params.get("callbackUrl"))

  const handleSignIn = async () => {
    if (pending) return
    setPending(true)
    try {
      await signIn("keycloak", { callbackUrl })
    } catch {
      setPending(false)
    }
  }

  return (
    <main className={styles.page}>
      <a className={styles.skipLink} href="#sign-in-panel">{copy.skip}</a>
      <section className={styles.story} aria-labelledby="login-story-title">
        <div className={styles.storyGlow} aria-hidden="true" />
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true"><Sparkles size={20} /></span>
          <span><strong>OpenBank</strong><small>Admin</small></span>
        </div>
        <div className={styles.storyBody}>
          <p className={styles.eyebrow}>{copy.eyebrow}</p>
          <h1 id="login-story-title">{copy.headline}</h1>
          <p className={styles.intro}>{copy.intro}</p>
          <ul className={styles.benefits}>
            {copy.benefits.map(benefit => <li key={benefit}><CheckCircle2 size={18} aria-hidden="true" />{benefit}</li>)}
          </ul>
        </div>
        <p className={styles.storyFootnote}>Governed banking operations · Built for clarity</p>
      </section>

      <section className={styles.access}>
        <header className={styles.accessHeader}>
          <div className={styles.secureBadge}><ShieldCheck size={16} aria-hidden="true" />{copy.secure}</div>
          <button type="button" className={styles.languageButton} onClick={() => setLanguage(language === "en" ? "cs" : "en")} aria-label={copy.switchLanguage}>
            <Languages size={16} aria-hidden="true" />{copy.locale}
          </button>
        </header>
        <div id="sign-in-panel" className={styles.panel} tabIndex={-1}>
          <span className={styles.keyIcon} aria-hidden="true"><KeyRound size={23} /></span>
          <h2>{copy.welcome}</h2>
          <p className={styles.guidance}>{copy.guidance}</p>
          {error && <div className={styles.error} role="alert">{error === "SessionExpired" ? copy.sessionExpired : copy.genericError}</div>}
          <button type="button" className={styles.signInButton} onClick={handleSignIn} disabled={pending} aria-busy={pending}>
            {pending ? <Loader2 className={styles.spinner} size={18} aria-hidden="true" /> : <KeyRound size={18} aria-hidden="true" />}
            <span>{pending ? copy.signingIn : copy.signIn}</span>
            {!pending && <ArrowRight className={styles.arrow} size={18} aria-hidden="true" />}
          </button>
          <div className={styles.trustNote}>
            <ShieldCheck size={20} aria-hidden="true" />
            <div><strong>{copy.trustTitle}</strong><p>{copy.trustBody}</p></div>
          </div>
          <p className={styles.help}>{copy.help}</p>
          <a className={styles.privacy} href="/privacy">{copy.privacy}</a>
        </div>
      </section>
    </main>
  )
}

export default function LoginPage() {
  return <Suspense><LoginContent /></Suspense>
}
