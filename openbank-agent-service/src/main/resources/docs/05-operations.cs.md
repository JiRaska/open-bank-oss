# Provoz

## Build

```
./gradlew :openbank-agent-service:build           # kompilace + testy
./gradlew detekt ktlintCheck koverVerify build     # lokální brána před PR
```

- **Balení:** fast-jar (nikdy uber-jar — runtime stage COPYuje `quarkus-app/`). Generický build/push: `openbank-infra/scripts/build-push-service.sh openbank-agent-service` (host-side `quarkusBuild`, ne Gradle v Dockeru).
- **Image:** viz [`Dockerfile`](../../../../openbank-agent-service/Dockerfile) — fast-jar runtime stage.
- **Bez DB:** neexistují Flyway migrace k aplikaci při startu, takže obvyklé Flyway checksum/repair runbooky se zde neuplatní.

## Konfigurace (prostředí)

| Proměnná | Default | Účel |
|---|---|---|
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | secret pro klienta `openbank-services` (resource server + odchozí client-credentials) |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | issuer Keycloak realmu |
| `OIDC_TLS_VERIFICATION` | `none` | nastavte na `required` v reálném HTTPS deployi |
| `AGENT_DEFAULT_MODEL` | `mock-echo` | default model id gateway |
| `AGENT_MODEL_ENDPOINT` | `http://litellm.ai-platform.svc:4000/v1` | base URL `openai-compat` backendu. Výchozí je in-cluster LiteLLM gateway, jediný workload, který smí komunikovat s hostovaným LLM providerem (ADR-0174/0175) — stejná hodnota, jakou nastavuje nasazení. Registrovaná model id jsou `model_name` gateway, ne id providera, takže přímá URL providera je neobsluhuje (#5736) |
| `AGENT_MODEL_API_KEY` | *(fallback na `GROQ_API_KEY`)* | klíč pro `openai-compat` backend. V nasazení jde o **virtuální** klíč LiteLLM, nikoli klíč providera (OpenBao/ESO — nikdy v gitu) |
| `GROQ_API_KEY` | *(prázdné)* | legacy/local-dev fallback předchozího, při přímé komunikaci s Groq |
| `AGENT_POLICY_ENFORCEMENT` | `advisory` | `advisory` (jen audit) nebo `block` (vynutit DENY) — ADR-0031 D9 |
| `BUILD_TIME`, `GIT_COMMIT` | `unknown` | provenience pro `/api/v1/info` |

Commitovaná konfigurace dodává pouze **offline `mock-echo`** provider, takže build a testy nepotřebují síť ani API klíč. Reálné backendy se přidávají jako položky `model-gateway.models` bez změny kódu.

## Porty

| Port | Účel |
|---|---|
| 8109 | aplikační HTTP (`/mcp`, `/agent/*`) |
| 8085 | management — `/q/health/*`, `/q/metrics`, `/q/openapi`, `/q/openbank/docs` (root-path `/q`) |

## Health probes

- **Liveness:** `GET /q/health/live` (SmallRye Health).
- **Readiness:** `GET /q/health/ready`.
- Obě na management portu 8085. Kubernetes probes míří na management port.

## Pozorovatelnost

- **Metriky:** Micrometer → Prometheus na `/q/metrics`.
- **Tracing:** OpenTelemetry (Quarkus extension); log formát nese `traceId` a `correlationId`.
- **AI pozorovatelnost:** každý completion modelu a rozhodnutí o volání nástroje je AI-atribuovaný audit event (viz [04 — Data](./04-data.md)); ADR-0031 D6 cílí na Langfuse nad OTel pro sledování approval-without-edit-rate (runtime, zatím ne v tomto modulu).

## Deploy & FinOps tier (ADR-0057)

- Cloud-agnostický substrát, GitOps přes ArgoCD, scale-to-zero defaultně pro nejnižší tier, který spouštěč služby dovolí ([ADR-0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)).
- agent-service **není** money-path služba, takže **není** připnuta k vždy-zapnutému T0 tieru. Je spouštěna na požadavek (admin UI / MCP klient) bez závislosti na event-loopu, takže je kandidátem na **scale-to-zero** tier; skutečný tier je **odvozen z naměřeného provozu**, ne ručně přidělen, a brána declared-vs-measured to drží poctivé. (Přesný tier: odvozen v CI — zde TBD.)
- Bezstavová + bez DB ⇒ studený start je levný a bezpečný; není žádná data k obnově, takže RPO je pro tuto službu fakticky N/A.

## SLO (návrh)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Cíl | Target |
|---|---|
| Dostupnost MCP `tools/list` / `ping` | 99,9 % |
| `/agent/chat` p95 latence | ohraničeno model backendem; smyčka je omezena na 5 iterací × 512 output tokenů |
| Rozhodnutí policy-gate (OPA dosažitelná) | p95 < 50 ms |

Latenci chatové cesty dominuje upstream model; free Groq tier vynucuje rozpočet ~12k tokenů/min, proto jsou výsledky nástrojů oříznuty (`MAX_TOOL_RESULT_CHARS = 3000`).

## Runbooky

### Asistent říká „the model backend is temporarily unavailable"
Gateway degradovala s grácií při chybě modelu. Zkontrolujte: `AGENT_DEFAULT_MODEL`, přítomnost `GROQ_API_KEY` a stav backendu. Zpráva 429 / „rate-limited" znamená, že se spustil rozpočet tokens-per-minute free tieru — zkuste znovu za pár sekund.

### Asistent říká „those tools couldn't be reached"
Celé kolo nástrojů chybovalo (auth/konektivita). Ověřte: secret klienta OIDC `openbank-services`, URL downstream služeb (`quarkus.rest-client.*.url`) a že downstream služby běží. Smyčka po neúspěšném kole přestane nabízet nástroje a odpoví textem — to je záměr, ne pád.

### Každé volání nástroje je DENIED
Očekávané pod `advisory` jen pokud je nástroj nenamapovaný (deny-by-default) nebo na `/mcp` nebyl deklarován `X-Agent-Id`. Pod `block`: zkontrolujte, že OPA sidecar (8181) je dosažitelný a charter bundle z `agents.yaml` je načten. Pokud je **nedosažitelný sám PDP**, brána degraduje na advisory + WARN (asistenta **nezamkne**) — opravte OPA sidecar, abyste obnovili vynucení.

### Zapnutí vynucení
Nastavte `AGENT_POLICY_ENFORCEMENT=block` v env deploye, jakmile je v cíli přítomen OPA sidecar. Není třeba redeploy kódu; kill-switch / charter změny žijí v `agents.yaml` + OPA bundle.

## Verze & release

Vydávaná komponenta (přítomen `version.txt`) — aktuálně `1.5.0`. Verzování vlastní release-please z Conventional Commits; neupravujte ručně `version.txt` ani `CHANGELOG.md`. API-kontraktové bumpy (`openapi.yaml: info.version`) se klasifikují z OpenAPI diffu, nezávisle na release verzi (ADR-0048).
