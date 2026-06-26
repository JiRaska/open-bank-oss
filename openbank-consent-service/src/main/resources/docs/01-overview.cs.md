# Přehled

## Co služba dělá

`openbank-consent-service` je **systém záznamu pro souhlasy** v platformě OpenBank — explicitní oprávnění, která zákazník (party) dává třetí straně nebo agentovi pro přístup k datům účtu nebo iniciaci plateb. Drží:

- **Agregát Consent** — kdo souhlas udělil (`partyId`), komu (`granteeId` + `granteeType`), množinu `scopes`, volitelný seznam `accountIbans` (null = všechny účty), okno platnosti (`validFrom`/`validTo`) a stav životního cyklu `status`.
- **Scopy souhlasu** — PSD2 AISP (`ACCOUNTS_READ`, `BALANCES_READ`, `TRANSACTIONS_READ`, `STATEMENTS_READ` plus ČOBS-specifické `PAYMENT_ACCOUNTS_READ`, `STANDING_ORDERS_READ`, `DIRECT_DEBITS_READ`), PISP (`PAYMENTS_INITIATE`, `PAYMENTS_STATUS_READ`, `DOMESTIC_PAYMENT_INITIATE`, `SIPO_PAYMENT_INITIATE`), CBPII (`FUNDS_CONFIRMATION`) a rozšiřující scopy pro AI agenty (`AGENT_QUERY`, `AGENT_INITIATE`, `AGENT_NOTIFY`, `AGENT_ANALYZE`).
- **Typy příjemce** — `TPP` (eIDAS-certifikovaný poskytovatel třetí strany), `BANK_AGENT`, `CUSTOMER_AGENT`, `INTERNAL_SERVICE`.
- **Stavový automat životního cyklu** — `PENDING_SCA → ACTIVE → (EXPIRED | REVOKED)`, plus `REJECTED` a `SUPERSEDED`.

Hlavní runtime úlohou je **validační endpoint**: pro dané id souhlasu, příjemce, požadovaný scope a cílový účet vrátí, zda je přístup povolen. To volají downstream PSD2/agentní rozhraní před poskytnutím dat.

## Co služba **NEDĚLÁ**

- ❌ Neprovádí silné ověření zákazníka (SCA) — *deleguje* na `openbank-sca-service` a pouze kontroluje, že odkazovaná SCA výzva je `COMPLETED` pro účel `CONSENT_GRANT` (ADR 0021).
- ❌ Nemluví drátovým protokolem PSD2/Berlin-Group — `psd2-service` překládá externí volání TPP na interní příkazy souhlasu.
- ❌ Nedrží data o účtech, zůstatcích ani transakcích — pouze odkazuje na `accountIbans` a autorizuje k nim přístup pro čtení/iniciaci.
- ❌ Neprovádí platby — uděluje *právo* iniciovat; samotný převod provádějí platební služby.
- ❌ Neregistruje ani neprověřuje TPP / eIDAS certifikáty — to je upstream (TPP registr / `psd2-service`).

## Pozice v doméně

```
   ┌──────────┐  create / activate    ┌──────────────────┐  getChallenge   ┌──────────────┐
   │  TPP /   │ ───────────────────►  │  consent-service │ ──────────────► │ sca-service  │
   │ psd2-svc │   (REST, OIDC)        └────────┬─────────┘  (REST klient)  └──────────────┘
   │ / agent  │                                │
   └────┬─────┘  validate(scope)               │ outbox → Kafka
        │ ◄─── allow / deny                     ▼      openbank.consent.events
        ▼                              ┌──────────────────────────────┐
   čtení účtů/zůstatků/                │ audit-service / admin-ui /    │
   transakcí                           │ downstream konzumenti         │
   (gated souhlasem)                   └──────────────────────────────┘
        │
        ▼
    PostgreSQL (openbank_consents)
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Vytvořit souhlas (čeká na SCA) | `POST /api/v1/consents` | — |
| Aktivovat souhlas po SCA | `POST /api/v1/consents/{id}/activate?scaSessionId=…` | `ConsentGranted` |
| Odmítnout souhlas | `POST /api/v1/consents/{id}/reject?reason=…` | `ConsentRejected` |
| Odvolat aktivní souhlas | `DELETE /api/v1/consents/{id}?partyId=…` | `ConsentRevoked` |
| Validovat přístup v době požadavku | `POST /api/v1/consents/{id}/validate` | — |
| Získat souhlas dle id | `GET /api/v1/consents/{id}` | — |
| Seznam souhlasů pro party | `GET /api/v1/consents/party/{partyId}` | — |
| Seznam souhlasů pro příjemce | `GET /api/v1/consents/grantee/{granteeId}` | — |

> `ConsentExpired` je rovněž definována jako doménová událost pro přechod do expirace.

## Volající

- **psd2-service** — překládá požadavky TPP na volání create/validate/revoke.
- **brána AI agentů / MCP** — validuje scopy `AGENT_*` před poskytnutím dotazů nebo iniciací plateb (ADR 0031/0034).
- **admin-ui** — operátoři a compliance prohlížejí/odvolávají souhlasy jménem zákazníka.
- **platební a čtecí rozhraní účtů/zůstatků/transakcí** — volají `validate` před poskytnutím dat třetí straně.
- **sca-service** — je voláno *z* consent-service (read-only) pro potvrzení dokončení výzvy.

## Závislosti

- **PostgreSQL** (`openbank_consents`, tabulky v `public`) — souhlasy, scopy, účty, outbox.
- **Kafka** (`openbank-kafka`, topic `openbank.consent.events`) — události životního cyklu přes outbox.
- **Redis (Valkey)** — idempotenční cache pro vytvoření souhlasu.
- **Keycloak** — validace OIDC tokenu.
- **openbank-sca-service** — ověření SCA výzvy (REST klient, odolný).
- **OPA sidecar** — autorizační rozhodnutí (ADR 0034; defaultně advisory).
- **openbank-libs** — `DomainEvent`, `IdempotencyStore`, `@Authorize`/`PolicyDecisionPoint`, `ApiError`/`ErrorCode`, outbox base, ServiceInfo/Docs resources.

## Obchodní hodnota

- **Regulatorní brána pro Open Banking** — žádná třetí strana se nedotkne dat zákazníka bez aktivního, scope-omezeného a časově ohraničeného souhlasu, čímž je naplněn PSD2 čl. 64–67 a SCA RTS.
- **Auditovatelný životní cyklus** — každé udělení/odmítnutí/odvolání emituje doménovou událost ukládanou `audit-service` po zákonnou dobu retence.
- **Jediný scope-aware autorizační bod** — jediné volání `validate` rozhodne o přístupu, takže downstream služby nepotřebují vlastní logiku souhlasů.
- **Připraveno na AI agenty** — stejný model souhlasu se rozšiřuje na delegovaný přístup agentů s explicitními, odvolatelnými scopy.
