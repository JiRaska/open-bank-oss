// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Static, pre-login, single-language legal notice — same class as the /auth/*
// screens (i18n.guard / layout-shell.guard EXEMPT). GDPR Art. 13 requires this
// notice to be reachable WITHOUT an account (middleware.ts carries the bypass),
// since the data subjects it describes are the operators who are about to log in.

export const metadata = {
  title: "Ochrana osobních údajů — OpenBank Admin",
}

export default function PrivacyPage() {
  return (
    <main
      id="main"
      style={{
        maxWidth: "720px", margin: "0 auto", padding: "48px 24px 96px",
        fontFamily: "var(--font-sans, 'Inter', system-ui, sans-serif)",
        color: "var(--text-primary, #0f172a)", lineHeight: 1.6,
      }}
    >
      <h1 style={{ fontSize: "24px", fontWeight: 700, marginBottom: "8px" }}>
        Ochrana osobních údajů — OpenBank Admin
      </h1>
      <p style={{ color: "var(--text-tertiary, #94a3b8)", fontSize: "13px", marginBottom: "32px" }}>
        Operations Portal · admin.open-bank.tech
      </p>

      <h2 style={{ fontSize: "16px", fontWeight: 700, marginTop: "28px", marginBottom: "8px" }}>
        Kdo je správcem
      </h2>
      <p>
        OpenBank Foundation (v přípravě), provozovatel demonstrační bankovní platformy
        na doméně open-bank.tech. Kontakt: <a href="mailto:hello@open-bank.tech">hello@open-bank.tech</a>.
      </p>

      <h2 style={{ fontSize: "16px", fontWeight: 700, marginTop: "28px", marginBottom: "8px" }}>
        Jaké údaje zpracováváme a proč
      </h2>
      <p>
        OpenBank Admin je interní operátorská konzole — přístup mají pouze zaměstnanci a
        spolupracovníci přihlášení přes firemní Keycloak SSO účet. Při přihlášení a používání
        konzole zpracováváme:
      </p>
      <ul>
        <li>identitu z Keycloak (jméno, e-mail, přidělené role) — pro autentizaci a řízení přístupu (RBAC);</li>
        <li>relační cookie (NextAuth session) — nezbytnou pro udržení přihlášení, žádné sledovací ani marketingové cookies;</li>
        <li>záznam provedených akcí (audit log) — právním základem je plnění povinností dle EBA ICT Risk
          Guidelines a PSD2, a oprávněný zájem na vysledovatelnosti operací nad bankovními daty.</li>
      </ul>

      <h2 style={{ fontSize: "16px", fontWeight: 700, marginTop: "28px", marginBottom: "8px" }}>
        Doba uchování
      </h2>
      <p>
        Relační cookie zaniká odhlášením nebo vypršením platnosti tokenu. Audit záznamy se
        uchovávají po dobu vyžadovanou regulatorními předpisy pro finanční sektor.
      </p>

      <h2 style={{ fontSize: "16px", fontWeight: 700, marginTop: "28px", marginBottom: "8px" }}>
        Vaše práva
      </h2>
      <p>
        Máte právo na přístup, opravu, výmaz (v rozsahu, který nekoliduje s výše uvedenou
        regulatorní povinností uchování) a na podání stížnosti u Úřadu pro ochranu osobních
        údajů. Žádosti směřujte na <a href="mailto:hello@open-bank.tech">hello@open-bank.tech</a>.
      </p>

      <h2 style={{ fontSize: "16px", fontWeight: 700, marginTop: "28px", marginBottom: "8px" }}>
        Bezpečnostní kontakt
      </h2>
      <p>
        Nahlášení bezpečnostní zranitelnosti: <a href="mailto:security@open-bank.tech">security@open-bank.tech</a>{" "}
        (viz <a href="/.well-known/security.txt">/.well-known/security.txt</a>).
      </p>

      <p style={{ marginTop: "40px" }}>
        <a href="/auth/login">← Zpět na přihlášení</a>
      </p>
    </main>
  )
}
