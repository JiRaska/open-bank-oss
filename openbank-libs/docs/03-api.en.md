# 03 — API & contracts

How services consume each libs package. Examples are abbreviated but runnable.

## domain/money — Money + CurrencyCode

```kotlin
import com.openbank.libs.domain.money.Money
import com.openbank.libs.domain.money.CurrencyCode

val price = Money.of("199.99", "EUR")
val tax = price.multiply(0.21.toBigDecimal())
val total = price.add(tax)             // 241.99 EUR
val zero = Money.zero(CurrencyCode.EUR)
require(total.currency == price.currency)  // type-safe; cross-currency add throws
```

**When to use:** anywhere code operates on a monetary amount. Never use `BigDecimal amount + String currencyCode` pairs — `Money` prevents you from adding 100 EUR + 100 CZK.

## domain/account — Iban

```kotlin
import com.openbank.libs.domain.account.Iban

val iban = Iban.of("CZ65 0800 0000 1920 0014 5399")
iban.value          // "CZ6508000000192000145399" (no spaces)
iban.formatted()    // "CZ65 0800 0000 1920 0014 5399"
iban.countryCode    // "CZ"
iban.bankCode       // "0800"
Iban.isValid("nonsense")  // false (no throw)
```

## domain/identifiers — typesafe IDs

```kotlin
import com.openbank.libs.domain.identifiers.AccountId
import com.openbank.libs.domain.identifiers.TransactionId

@Path("/api/v1/accounts/{id}")
class AccountResource(private val service: AccountService) {
    @GET
    suspend fun get(@PathParam("id") id: AccountId): Account = service.find(id)
    // AccountId.parse(String) called by Jackson via @JsonCreator
}

// Compile-time safety:
fun debit(account: AccountId, tx: TransactionId) { ... }
// debit(txId, accountId)  // compile error — wrong types
```

JPA persistence for entity columns (via `IdConverters.kt`):

```kotlin
@Entity
class AccountEntity {
    @Id
    @Convert(converter = AccountIdConverter::class)
    @Column(name = "id", nullable = false)
    lateinit var id: AccountId
}
```

## audit — AuditEvent + AuditEventPublisher

```kotlin
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult

@ApplicationScoped
class AccountService(private val auditPublisher: AuditEventPublisher) {
    suspend fun closeAccount(id: AccountId, actor: SecurityContext) {
        // ... business logic ...

        auditPublisher.publish(AuditEvent(
            actorId = actor.currentUserId?.toString() ?: "anonymous",
            actorType = actor.actorType,
            operation = "account.close",
            resourceType = "account",
            resourceId = id.toString(),
            result = AuditResult.SUCCESS,
            traceId = MDC.get("correlationId") as? String,
            payload = mapOf("reason" to "customer-request"),
        ))
    }
}
```

In dev mode, libs provides `LoggingAuditEventPublisher` (a no-Kafka fallback). In prod a service overrides it:

```kotlin
@ApplicationScoped
@Alternative
@Priority(100)
class KafkaAuditEventPublisher(
    @Channel("audit-events-out") private val emitter: Emitter<Record<String, String>>,
    private val objectMapper: ObjectMapper,
) : AuditEventPublisher {
    override suspend fun publish(event: AuditEvent) {
        emitter.send(Record.of(event.eventId.toString(), objectMapper.writeValueAsString(event)))
    }
}
```

## idempotency — IdempotencyStore

```kotlin
import com.openbank.libs.idempotency.IdempotencyStore

@Path("/api/v1/accounts")
class AccountResource(private val idempotency: IdempotencyStore, private val service: AccountService) {
    @POST
    suspend fun open(req: OpenAccountRequest, @HeaderParam("Idempotency-Key") key: String): Account {
        idempotency.get(key)?.let { cached ->
            return objectMapper.readValue(cached.responseBody, Account::class.java)
        }
        val account = service.open(req)
        idempotency.save(key, 201, objectMapper.writeValueAsString(account), ttlSeconds = 86400)
        return account
    }
}
```

A per-service producer wires `RedisIdempotencyStore` (see [02-architecture, principle 4](./02-architecture.md)):

```kotlin
@ApplicationScoped
class IdempotencyConfig {
    @Produces @ApplicationScoped
    fun idempotencyStore(redis: ReactiveRedisDataSource): IdempotencyStore =
        RedisIdempotencyStore(redis)
}
```

## persistence/outbox — shared outbox

```kotlin
import com.openbank.libs.persistence.outbox.AbstractOutboxEntity
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxRepository

// 1. Service-specific entity (extends the abstract base):
@Entity
@Table(name = "account_outbox")
class AccountOutboxEntity : AbstractOutboxEntity()

// 2. Service-specific repository (PanacheRepository<E>):
@ApplicationScoped
class AccountOutboxRepositoryImpl : OutboxRepository, PanacheRepository<AccountOutboxEntity> {
    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = ...
    override suspend fun markSent(eventId: UUID, sentAt: Instant) { ... }
    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) { ... }
}

// 3. Service-specific dispatcher (keeps @Scheduled + @CircuitBreaker):
@ApplicationScoped
class AccountOutboxDispatcher(
    private val repo: AccountOutboxRepositoryImpl,
    private val publisher: AccountOutboxEventPublisher,
) {
    @Scheduled(every = "5s", delayed = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    suspend fun dispatchBatch() {
        OutboxDispatch.dispatchOnce(repo) { payload ->
            publisher.publish(payload)
        }
    }
}
```

**Why the service still owns the entity + repo + dispatcher:** Quarkus `@Scheduled` + Fault Tolerance annotations require a CDI proxy per service. The common dispatch *loop* is shared (`OutboxDispatch.dispatchOnce`), not the entire dispatcher.

## security — PiiMask

```kotlin
import com.openbank.libs.security.PiiMask
import com.openbank.libs.security.MaskStrategy

PiiMask.email("john.doe@example.com")         // "j******e@example.com"
PiiMask.iban("CZ6508000000192000145399")      // "CZ65****************5399"
PiiMask.pan("4532015112830366")               // "4532********0366"  (PCI-DSS)
PiiMask.phone("+420123456789")                // "+420*****6789"
PiiMask.name("Jiří Raška")                    // "J. R."
PiiMask.nationalId("8501010987")              // "850101****"
PiiMask.apply(MaskStrategy.EMAIL, anyEmail)   // strategy dispatcher
```

Annotation for DTO fields (used by the admin-ui proxy + audit-event sanitiser):

```kotlin
// Masking is applied explicitly where the value is rendered or logged.
// There is no masking annotation and no serialization filter that would honour
// one — a field merely marked would serialise in full (#4011).
data class CustomerDto(
    val name: String,
    val email: String,
    val iban: String,
)

fun CustomerDto.masked() = copy(
    email = PiiMask.email(email),
    iban = PiiMask.iban(iban),
)
```

## security — Roles + SecurityContext

```kotlin
import com.openbank.libs.security.Roles
import com.openbank.libs.security.currentUserId
import com.openbank.libs.security.actorType
import com.openbank.libs.security.requireAnyRole

@Path("/api/v1/disputes")
class DisputeResource(@Context private val sc: SecurityContext) {
    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.COMPLIANCE)
    fun open(req: OpenDisputeRequest): Dispute {
        // explicit secondary check beyond @RolesAllowed:
        sc.requireAnyRole(Roles.COMPLIANCE)  // throws SecurityException when missing

        return service.open(
            req,
            actorId = sc.currentUserId ?: error("unauthenticated"),
            actorType = sc.actorType,  // "ROLE_COMPLIANCE", "ROLE_OPERATOR", ...
        )
    }
}
```

## security — BearerTokenClientHeadersFactory

S2S call to another OpenBank service:

```kotlin
import com.openbank.libs.security.BearerTokenClientHeadersFactory
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@RegisterRestClient(configKey = "ledger-api")
@RegisterClientHeaders(BearerTokenClientHeadersFactory::class)
@Path("/api/v1/journals")
interface LedgerClient {
    @POST
    suspend fun postJournalEntry(entry: JournalEntry): Response
}
```

The factory automatically adds:
- `Authorization: Bearer <service-token>` (from the `ServiceTokenProvider` CDI bean)
- `X-Correlation-ID`, `X-Request-ID` (from the incoming request)

## web — automatically active

No code required. Auto-discovery via Jandex:

| Filter / Resource | What it does |
|---|---|
| `CorrelationIdFilter` | Per-request `X-Correlation-ID` in MDC, on the response |
| `RateLimitFilter` | Returns 429 when `openbank.rate-limit.max-concurrent-requests` (default 200) is exceeded |
| `ApiVersionResponseFilter` | `X-API-Version`, `X-Service-Name`, `Deprecation` headers |
| `GET /api/v1/info` | Service name, version, **`stack`** (Kotlin/Quarkus/JDK/Gradle/libs) — used by admin UI Tech Inventory |
| `GET /api/v1/config` | Live snapshot of rate-limit + circuit-breaker + retry + timeout config |

## util — BuildInfo

```kotlin
import com.openbank.libs.util.BuildInfo

BuildInfo.kotlinVersion       // "2.3.20"
BuildInfo.quarkusVersion      // "3.33.2"
BuildInfo.quarkusLts          // true
BuildInfo.quarkusSupportUntil // "2027-03-25"
BuildInfo.gradleVersion       // "9.5.1"
BuildInfo.buildTime           // "2026-05-29T17:34:46Z"
BuildInfo.gitCommit           // "62b312b" (or "unknown" in Docker build without .git)
BuildInfo.libsVersion         // "0.1.0-SNAPSHOT"
BuildInfo.javaVersion         // "25.0.3+9-LTS"
BuildInfo.javaVendor          // "Eclipse Adoptium"
BuildInfo.osArch              // "aarch64"
BuildInfo.cpuCount            // 12
BuildInfo.maxHeapMib          // 3994

BuildInfo.toStack()  // returns LinkedHashMap suitable for JSON serialization
```

`ServiceInfoResource` automatically returns `toStack()` in the `/api/v1/info` response.
