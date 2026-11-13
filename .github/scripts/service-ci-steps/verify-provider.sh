#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
consumer_args=()
pending_pacts=true
check_transaction_scope=false
if [ -n "${PACT_CONSUMER:-}" ]; then
  [[ "$PACT_CONSUMER" =~ ^openbank-[a-z0-9-]+$ && "$SERVICE" =~ ^openbank-[a-z0-9-]+$ ]] || {
    echo '::error::Provider and consumer must be openbank-* module names.'
    exit 1
  }
  if [ -n "${HISTORICAL_PACT_URL:-}" ]; then
    echo '::error::A consumer scope cannot be combined with a historical admin-ui proof.'
    exit 1
  fi
  scope_guard="$RUNNER_TEMP/pact-consumer-scope.init.gradle"
  if [ "$SERVICE" = openbank-transaction-service ]; then
    fixture="$SERVICE/src/test/kotlin/com/openbank/transaction/contract/TransactionPactProviderVerificationTest.kt"
    if ! grep -qxF '    @ExtendWith(TransactionPactConsumerScope::class)' "$fixture"; then
      echo '::error::Scoped transaction replay requires the reviewed consumer-scope fixture overlay.'
      exit 1
    fi
    # Set selectors on the actual Test task, including for historical P.
    # pact.filter.consumers does not scope ordinary JUnit 5 broker loading.
    cat > "$scope_guard" <<'GRADLE'
// Gradle also runs init scripts in included builds such as build-logic.
if (gradle.parent != null) {
    return
}
gradle.projectsEvaluated {
    def provider = gradle.rootProject.project(":" + System.getenv("SERVICE"))
    def task = provider.tasks.named("providerPactTest").get()
    if (!(task instanceof org.gradle.api.tasks.testing.Test)) {
        throw new GradleException("providerPactTest is not a Gradle Test task; refusing scoped replay.")
    }
    def pactJars = task.classpath.files.findAll { it.name ==~ /(?:provider|pactbroker)-[0-9].*\.jar/ }
    if (pactJars*.name.sort() != ["pactbroker-4.7.3.jar", "provider-4.7.3.jar"]) {
        throw new GradleException("Pact JVM selector support is unverified; refusing scoped replay.")
    }
    task.filter.setIncludePatterns("com.openbank.transaction.contract.TransactionPactProviderVerificationTest")
    def consumer = System.getenv("PACT_CONSUMER")
    // The legacy broker response is filtered by exact consumer name. The
    // modern endpoint instead receives an explicit named branch selector.
    def scope = [
        "pactbroker.consumers": consumer,
        "pactbroker.consumerversionselectors.rawjson": groovy.json.JsonOutput.toJson([[consumer: consumer, mainBranch: true]]),
        "pactbroker.includeWipPactsSince": ""
    ]
    scope.each { key, expected ->
        def configured = task.systemProperties[key]
        if (configured != null && configured != expected) {
            throw new GradleException("Conflicting Pact selector $key; refusing scoped replay.")
        }
        task.systemProperty(key, expected)
    }
}
GRADLE
    consumer_args=(--init-script "$scope_guard")
    pending_pacts=false
    check_transaction_scope=true
    rm -rf -- "$SERVICE/build/test-results/providerPactTest"
  else
    # Keep existing non-transaction consumers on their current verification path.
    cat > "$scope_guard" <<'GRADLE'
if (gradle.parent != null) {
    return
}
gradle.projectsEvaluated {
    def provider = gradle.rootProject.project(":" + System.getenv("SERVICE"))
    def task = provider.tasks.named("providerPactTest").get()
    if (!(task instanceof org.gradle.api.tasks.testing.Test) ||
            task.systemProperties["pact.filter.consumers"] != System.getenv("PACT_CONSUMER")) {
        throw new GradleException("Consumer scope is not forwarded by the checked-out provider Test task; refusing replay.")
    }
}
GRADLE
    consumer_args=("-Dpact.filter.consumers=${PACT_CONSUMER}" --init-script "$scope_guard")
  fi
fi
pact_url_arg=()
if [ -n "${HISTORICAL_PACT_URL:-}" ]; then
  pact_url_arg=(-Dpact.filter.pacturl="${HISTORICAL_PACT_URL}" -Dpact.filter.consumers=openbank-admin-ui)
fi
./gradlew ":${SERVICE}:providerPactTest" --build-cache \
  --console=plain --stacktrace \
  ${consumer_args[@]+"${consumer_args[@]}"} \
  -Dpactbroker.url="${PACT_BROKER_URL:-}" \
  -Dpactbroker.auth.username="${PACT_BROKER_USERNAME:-}" \
  -Dpactbroker.auth.password="${PACT_BROKER_PASSWORD:-}" \
  -Dpactbroker.enablePending="$pending_pacts" \
  -Dpactbroker.providerBranch="${BRANCH}" \
  -Dpact.verifier.publishResults=true \
  -Dpact.provider.version="${PACT_PROVIDER_VERSION}" \
  -Dpact.provider.branch="${BRANCH}" \
  -Dpact.provider.tag="${BRANCH}" \
  ${pact_url_arg[@]+"${pact_url_arg[@]}"}

if [ "$check_transaction_scope" = true ]; then
  python3 - "$SERVICE" "$PACT_CONSUMER" <<'PYTHON'
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

provider, consumer = sys.argv[1:]
report_dir = Path(provider, "build/test-results/providerPactTest")
expected = re.compile(rf"Pact between {re.escape(consumer)} \([^)]+\) and {re.escape(provider)} - .+")
successful = 0
for report in report_dir.rglob("*.xml"):
    for case in ElementTree.parse(report).iter("testcase"):
        name = case.get("name", "")
        if not name.startswith("Pact between "):
            continue
        if not expected.fullmatch(name):
            sys.exit("::error::Provider replay reported an interaction outside the requested Pact scope.")
        if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
            sys.exit("::error::Provider replay skipped or failed a requested Pact interaction.")
        successful += 1
if not successful:
    sys.exit("::error::Provider replay produced no successful interactions for the requested consumer.")
print(f"Verified {successful} broker interaction(s) for {consumer} against {provider}.")
PYTHON
fi
