# 05 — Provoz

## Build

```bash
./gradlew :openbank-vop-service:build
./gradlew :openbank-vop-service:test :openbank-vop-service:ktlintCheck :openbank-vop-service:detekt
```

Testy potřebují Docker (Testcontainers Postgres). **Paralelní build více služeb lokálně vyrobí falešný `QuarkusBindException`** — všechny služby sdílejí management port 8086, takže dva starty `@QuarkusTest` se srazí. Buildujte je postupně; CI dává každé službě vlastní job a tohle nikdy nevidí.

## Deploy

ArgoCD, komponenta `payments`. Rollout s canary (10 % → 30 % → 100 %, analýza `openbank-money-path-canary`) + dvě Services kvůli DNS.

```bash
gh workflow run auto-deploy.yml -f services=vop-service
```

| | |
|---|---|
| Namespace | `payments` |
| Porty | 8149 (aplikace), 8086 (mgmt), 8181 (OPA sidecar) |
| Databáze | `vop-db` (CNPG, `instances: 2`) — secret `vop-db-app` **generuje CNPG**, není to ExternalSecret |
| Valkey | `redis.payments.svc:6379` — pod rate limitem |
| OPA bundle | ConfigMap `vop-opa-bundle`, generuje `gen-vop-opa-bundle.sh` |
| `AUTHZ_ENFORCE` | **`true`** od prvního dne — nová služba nemá starého volajícího k rozbití, takže žádná ze shadow expozic „would DENY“, kvůli které starší rails zůstávají na `false` (issue #750) |

**Po jakékoli změně politiky přegenerujte a commitněte obojí.** `subPath` mounty **nedělají** hot-reload — pody restartuje až pod-roll anotace, kterou generátor razítkuje. Zastaralá anotace znamená, že pody tiše dál servírují starou politiku.

> **Footgun napříč flotilou:** 25 z 26 `gen-*opa-bundle*.sh` hashuje `rules.yaml` do svého checksumu. Jakákoli úprava `rules.yaml` — včetně přidání služby do `money_path_services` — přerazítkuje bundly ~22 služeb. Od #1187 gate generátory objevuje dynamicky a tohle chytí. Pusťte je všechny, commitněte celek a pak je pusťte znovu pro potvrzení čistého stromu.

## Konfigurace

| Klíč | Výchozí | Poznámka |
|---|---|---|
| `openbank.vop.domestic-iban-prefixes` | `CZ` | Prefixy zemí IBAN, na které umíme odpovědět sami. Cokoli jiného → `no_data`/`no_scheme_connectivity`. |
| `openbank.vop.max-edit-distance` | `1` | Levenshteinův rozpočet pro `close_match` tolerantní k překlepu. |
| `openbank.vop.rate-limit.requests-per-minute` | `60` | Na volajícího. |
| `openbank.vop.rate-limit.enabled` | `true` | Vypínat jen pro lokální vývoj. |

## Rate limit je bezpečnostní kontrola, ne řízení propustnosti

Tohle je potřeba pochopit dřív, než se toho někdo dotkne.

VoP pravdivě odpovídá „drží tohle jméno tenhle IBAN?“ komukoli autentizovanému — to je smysl nařízení, a plátce musí smět ověřit příjemce, kterého nevlastní. **Autorizace tedy orákulum omezit nemůže. Rate je to, co odděluje plátce od enumerátora.** Zároveň zastropuje zesílení 1→2 směrem na account-service a party-service, které samy jsou money-path.

- **Nad Valkey, ne in-process** — vop-service běží ve více replikách; lokální čítač by útočníkovi dal `limit × repliky` a resetoval se při každém rolování podů.
- **Fail CLOSED** — když je Valkey nedostupný, nemůžeme prokázat, že je volající pod limitem, takže 429. To **není** rozpor s fail-open VoP: 429 způsobí, že volající vykreslí `no_data`, a platba stejně projde s varováním.
- **Zbytkové riziko:** limit je na *principal*. N spolupracujících principalů pořád zesílí N-krát; globální strop není. A platforma nemá WAF ani edge rate limiting vůbec ([audit](../../../../docs/audits/2026-07-16-platform-audit.md) §4.3), takže tohle je kontrola jen na aplikační vrstvě — omezí autentizovaného volajícího, ne objemový útok.

## SLO

Money-path ⇒ obě SLO jsou povinná a gatovaná (`check-slo-registry.py`, issue #669), v `pyrra-slo-money-path.yaml`:

| SLO | Cíl | Okno |
|---|---|---|
| `openbank-vop-availability` | 99,9 % bezchybných spanů | 30 d |
| `openbank-vop-latency` | 99 % rychlejších než 1 s | 30 d |

**Čtěte SLO dostupnosti správně.** VoP selhává open, takže chyba tady **neshodí** platbu. Tohle SLO měří zdraví **regulatorní kontroly** — vyčerpaný rozpočet znamená, že plátci nejsou **varováni**, což je selhání compliance, i když platební cesta vypadá zeleně.

Latenční SLO je to napjaté: jedno ověření je dvouskokové dohledání na kritické cestě plátce. Je to metrika, která nejspíš odhalí cenu v latenci, kterou ADR-0171 přijímá jako známý negativ.

## Runbooky

### Skokový nárůst `no_data`

1. Který důvod? `LOOKUP_UNAVAILABLE` ⇒ account-service nebo party-service není zdravá — zkontrolujte nejdřív jejich SLO; VoP je jen posel.
2. `NO_SCHEME_CONNECTIVITY` pro **domácí** IBANy ⇒ `VOP_DOMESTIC_IBAN_PREFIXES` je špatně nebo prázdné. Skutečná chyba.
3. `ACCOUNT_NOT_FOUND` ve velkém ⇒ buď pokus o enumeraci (zkontrolujte rozložení `requested_by`), nebo skutečný datový problém.

**Platby pořád tečou.** VoP selhává open; tohle je incident compliance (plátci nevarováni), ne výpadek plateb. Neopravujte to držením plateb.

### 429 napříč všemi

Skoro jistě Valkey, ne zneužití — limiter selhává closed. Zkontrolujte `redis.payments.svc`. Plátci vidí `no_data` a můžou dál platit; expozice je, že po tu dobu je obrana proti enumeraci vypnutá.

### Stížnost na `close_match` („ukázalo to špatné jméno“)

V rámci návrhu očekávané: `close_match` skutečné jméno vracet **má**, aby plátce mohl blízkou neshodu opravit. Pokud se spouští moc často, `max-edit-distance` je příliš volný — **laďte z metrik výsledků, ne podle jedné stížnosti**. Rozšíření tiše mění skutečné neshody v uklidňující oranžová varování, což je riziko tamperingu, které threat model pojmenovává.

### Podezření na enumeraci jmen

`requested_by` + `verified_at` v `vop_verification` jsou indexované právě na tohle. Principal s vysokým podílem `no_match` hádá, neplatí. **Automatický detektor zatím není** — otevřená položka v threat modelu.
