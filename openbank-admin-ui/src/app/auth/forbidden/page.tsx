// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"

import { ArrowRight, Languages, LockKeyhole, ShieldCheck } from "lucide-react"
import { useRouter, useSearchParams } from "next/navigation"
import { Suspense } from "react"
import { useLanguage } from "@/lib/i18n/LanguageContext"
import { sameOriginPath } from "@/lib/auth/safeCallbackPath"
import styles from "../recovery.module.css"

const COPY = {
  en: {
    eyebrow: "Access boundary",
    title: "This area is not in your role",
    intro: "OpenBank stopped the request before protected data was shown or changed.",
    explanation: "Access is assigned by responsibility. If this task is part of your job, ask your bank administrator to review your role instead of sharing another operator’s account.",
    requested: "Protected destination",
    dashboard: "Return to your dashboard",
    boundary: "This is a normal security control — not a system failure.",
    switchLanguage: "Přepnout do češtiny",
    locale: "Čeština",
  },
  cs: {
    eyebrow: "Hranice přístupu",
    title: "Tato oblast není součástí vaší role",
    intro: "OpenBank požadavek zastavil dříve, než byla zobrazena nebo změněna chráněná data.",
    explanation: "Přístup se přiděluje podle odpovědnosti. Pokud úkol patří do vaší práce, požádejte administrátora banky o kontrolu role — nesdílejte účet jiného operátora.",
    requested: "Chráněný cíl",
    dashboard: "Vrátit se na svůj dashboard",
    boundary: "Jde o běžný bezpečnostní mechanismus, nikoli poruchu systému.",
    switchLanguage: "Switch to English",
    locale: "English",
  },
} as const

function ForbiddenContent() {
  const params = useSearchParams()
  const router = useRouter()
  const { language, setLanguage } = useLanguage()
  const copy = COPY[language]
  const requestedPath = sameOriginPath(params.get("path"))

  return (
    <main className={styles.page}>
      <section className={styles.card} aria-labelledby="forbidden-title">
        <header className={styles.header}>
          <a className={styles.brand} href="/dashboard">OpenBank <span>Admin</span></a>
          <button type="button" className={styles.languageButton} onClick={() => setLanguage(language === "en" ? "cs" : "en")} aria-label={copy.switchLanguage}>
            <Languages size={16} aria-hidden="true" />{copy.locale}
          </button>
        </header>
        <div className={`${styles.icon} ${styles.lockIcon}`} aria-hidden="true"><LockKeyhole size={27} /></div>
        <p className={styles.eyebrow}>{copy.eyebrow}</p>
        <h1 id="forbidden-title">{copy.title}</h1>
        <p className={styles.intro}>{copy.intro}</p>
        <div className={styles.explanation}>{copy.explanation}</div>
        {requestedPath && <div className={styles.destination}><span>{copy.requested}</span><code>{requestedPath}</code></div>}
        <button type="button" className={styles.primaryButton} onClick={() => router.push("/dashboard")}>
          <ShieldCheck size={18} aria-hidden="true" /><span>{copy.dashboard}</span><ArrowRight size={18} aria-hidden="true" />
        </button>
        <p className={styles.help}>{copy.boundary}</p>
      </section>
    </main>
  )
}

export default function ForbiddenPage() {
  return <Suspense><ForbiddenContent /></Suspense>
}
