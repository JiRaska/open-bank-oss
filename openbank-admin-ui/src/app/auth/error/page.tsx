// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"

import { ArrowRight, Languages, Loader2, RefreshCw, ShieldAlert } from "lucide-react"
import { signIn } from "next-auth/react"
import { useSearchParams } from "next/navigation"
import { Suspense, useState } from "react"
import { useLanguage } from "@/lib/i18n/LanguageContext"
import { safeCallbackPath } from "@/lib/auth/safeCallbackPath"
import styles from "../recovery.module.css"

const COPY = {
  en: {
    eyebrow: "Secure sign-in recovery",
    title: "We could not sign you in",
    intro: "Your bank session was not started. No operational action was performed.",
    retry: "Try secure sign-in again",
    retrying: "Reconnecting securely…",
    switchLanguage: "Přepnout do češtiny",
    locale: "Čeština",
    help: "If this keeps happening, share the time of the attempt with your bank administrator — never share a password or token.",
    Configuration: "The identity service is not configured correctly. Please contact your bank administrator.",
    AccessDenied: "The identity provider did not grant access. Confirm that you selected the correct bank account.",
    Verification: "The verification link is invalid or has expired. Start a new secure sign-in.",
    Default: "An unexpected identity-service response interrupted sign-in. It is safe to try again.",
  },
  cs: {
    eyebrow: "Bezpečné obnovení přihlášení",
    title: "Přihlášení se nepodařilo",
    intro: "Bankovní relace nebyla zahájena a nebyla provedena žádná provozní akce.",
    retry: "Zkusit bezpečné přihlášení znovu",
    retrying: "Obnovuji zabezpečené spojení…",
    switchLanguage: "Switch to English",
    locale: "English",
    help: "Pokud se problém opakuje, sdělte administrátorovi banky čas pokusu — nikdy neposílejte heslo ani token.",
    Configuration: "Služba identity není správně nastavena. Obraťte se na administrátora banky.",
    AccessDenied: "Poskytovatel identity přístup nepovolil. Ověřte, že jste zvolili správný bankovní účet.",
    Verification: "Ověřovací odkaz je neplatný nebo vypršel. Zahajte nové bezpečné přihlášení.",
    Default: "Přihlášení přerušila neočekávaná odpověď služby identity. Můžete to bezpečně zkusit znovu.",
  },
} as const

function ErrorContent() {
  const params = useSearchParams()
  const { language, setLanguage } = useLanguage()
  const [pending, setPending] = useState(false)
  const copy = COPY[language]
  const error = params.get("error")
  const errorKey = error === "Configuration" || error === "AccessDenied" || error === "Verification" ? error : "Default"
  const callbackUrl = safeCallbackPath(params.get("callbackUrl"))

  const retry = async () => {
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
      <section className={styles.card} aria-labelledby="auth-error-title">
        <header className={styles.header}>
          <a className={styles.brand} href="/auth/login">OpenBank <span>Admin</span></a>
          <button type="button" className={styles.languageButton} onClick={() => setLanguage(language === "en" ? "cs" : "en")} aria-label={copy.switchLanguage}>
            <Languages size={16} aria-hidden="true" />{copy.locale}
          </button>
        </header>
        <div className={`${styles.icon} ${styles.errorIcon}`} aria-hidden="true"><ShieldAlert size={27} /></div>
        <p className={styles.eyebrow}>{copy.eyebrow}</p>
        <h1 id="auth-error-title">{copy.title}</h1>
        <p className={styles.intro}>{copy.intro}</p>
        <div className={styles.explanation} role="alert">{copy[errorKey]}</div>
        <button type="button" className={styles.primaryButton} onClick={retry} disabled={pending} aria-busy={pending}>
          {pending ? <Loader2 className={styles.spinner} size={18} aria-hidden="true" /> : <RefreshCw size={18} aria-hidden="true" />}
          <span>{pending ? copy.retrying : copy.retry}</span>
          {!pending && <ArrowRight size={18} aria-hidden="true" />}
        </button>
        <p className={styles.help}>{copy.help}</p>
      </section>
    </main>
  )
}

export default function AuthErrorPage() {
  return <Suspense><ErrorContent /></Suspense>
}
