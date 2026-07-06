# OSS-Fuzz integration (proposal package)

Continuous fuzzing of the parsers in `openbank-libs-domain` via
[OSS-Fuzz](https://github.com/google/oss-fuzz) (Jazzer/JVM). Everything OSS-Fuzz
needs lives here; the final step is a small PR to `google/oss-fuzz` adding
`projects/openbank/` with the three files below (maintainer action — the
`primary contact` email must be verified by Google).

## Targets

| Fuzzer | Fuzzed code | Why |
|---|---|---|
| `Pacs008ReaderFuzzer` | `com.openbank.libs.iso20022.Pacs008Reader` | Parses ISO 20022 XML from OUTSIDE the trust boundary (inbound clearing). Property: any input → `ReceivedCreditTransfer` or typed `Pacs008ParseException`; anything else (XXE, deep-nesting stack overflow, entity-expansion OOM, leaked NumberFormatException) is a finding. |
| `RodneCisloFuzzer` | `com.openbank.libs.identity.RodneCislo` | KYC identity parser. Properties: totality (never a raw throwable), `parse`/`isValid` agreement, canonicalization idempotence. |

Add new fuzzers for any code that parses external bytes (IBAN normalization,
statement imports, webhook payloads) — one object with a
`fuzzerTestOneInput(FuzzedDataProvider)` per target.

## Local run

```bash
./gradlew :openbank-libs-domain:classes           # from the repo root
cd fuzz/ossfuzz && gradle shadowJar               # standalone build (deliberately
                                                  # not in the root settings)
# https://github.com/CodeIntelligenceTesting/jazzer/releases
jazzer --cp=build/libs/ossfuzz-all.jar --target_class=com.openbank.fuzz.Pacs008ReaderFuzzer
```

## Files for google/oss-fuzz `projects/openbank/`

**project.yaml**
```yaml
homepage: "https://github.com/JiRaska/open-bank-oss"
main_repo: "https://github.com/JiRaska/open-bank-oss"
language: jvm
fuzzing_engines: [libfuzzer]
sanitizers: [address]
primary_contact: "<maintainer email — must be Google-verifiable>"
auto_ccs: []
```

**Dockerfile**
```dockerfile
FROM gcr.io/oss-fuzz-base/base-builder-jvm
# libs-domain targets JVM 25; the base image's JDK may lag — install Temurin 25.
RUN curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse \
      -o /tmp/jdk25.tar.gz && mkdir -p /opt/jdk25 \
      && tar -xzf /tmp/jdk25.tar.gz -C /opt/jdk25 --strip-components=1
ENV JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH
RUN git clone --depth 1 https://github.com/JiRaska/open-bank-oss.git $SRC/open-bank-oss
COPY build.sh $SRC/
WORKDIR $SRC/open-bank-oss
```

**build.sh** — see `ossfuzz-build.sh` in this directory (kept in OUR repo so the
oss-fuzz copy is a two-line shim; OSS-Fuzz best practice for keeping build logic
maintainer-owned).

## Why not a GHA fuzz lane instead?

OSS-Fuzz gives ~free continuous CPU, corpus management, dedup, and coordinated
disclosure of findings (90-day embargo). The same Jazzer targets also run in CI
via `cifuzz` once the project is accepted (optional follow-up: the
`google/oss-fuzz` CIFuzz action on PRs, 300 CPU-seconds per change).
