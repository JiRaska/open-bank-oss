#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
: "${SERVICE:?SERVICE is required}"
[[ "$SERVICE" =~ ^openbank-[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo 'Invalid coverage module' >&2; exit 1; }
# Render only the enforcing build's existing data. This helper is never the build/test command.
[[ -s "$SERVICE/build/kover/bin-reports/test.ic" ]] || { echo 'Missing enforcing test coverage data' >&2; exit 1; }
plan_dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/coverage-report.XXXXXX")"
trap 'rm -rf "$plan_dir"' EXIT
cat > "$plan_dir/tasks.init.gradle" <<'GRADLE'
gradle.beforeProject { module ->
    if (module.path != ':' + System.getenv('SERVICE')) return
    module.tasks.register('openbankCoverageReportTestTasks') {
        doLast {
            def paths = module.tasks.withType(org.gradle.api.tasks.testing.Test).collect { it.path }.sort()
            println('OPENBANK_COVERAGE_TEST_TASKS=' + groovy.json.JsonOutput.toJson(paths))
        }
    }
}
GRADLE
./gradlew --no-daemon --configure-on-demand --init-script "$plan_dir/tasks.init.gradle" \
  ":$SERVICE:openbankCoverageReportTestTasks" --console=plain > "$plan_dir/tasks.txt"
# Only actual Test tasks from the selected project may be excluded. Do not guess that a library
# owns service-only provider/native tasks, or let malformed discovery turn into an empty proof.
python3 - "$SERVICE" "$plan_dir/tasks.txt" > "$plan_dir/exclusions.txt" <<'PY'
import json
import re
import sys
from pathlib import Path
prefix = ':' + sys.argv[1] + ':'
marker = 'OPENBANK_COVERAGE_TEST_TASKS='
rows = [line[len(marker):] for line in Path(sys.argv[2]).read_text().splitlines() if line.startswith(marker)]
if len(rows) != 1:
    raise SystemExit('Missing or ambiguous coverage task discovery')
paths = json.loads(rows[0])
if (not isinstance(paths, list) or not paths or any(not isinstance(p, str) for p in paths)
        or len(set(paths)) != len(paths)
        or any(not p.startswith(prefix) or not re.fullmatch(r'[A-Za-z0-9_]+', p[len(prefix):]) for p in paths)
        or prefix + 'test' not in paths):
    raise SystemExit('Invalid coverage Test task discovery')
print('\n'.join(paths))
PY
exclusions=()
while IFS= read -r task; do exclusions+=(--exclude-task "$task"); done < "$plan_dir/exclusions.txt"
./gradlew --no-daemon --configure-on-demand ":$SERVICE:koverXmlReport" "${exclusions[@]}" \
  --build-cache --console=plain -Dquarkus.smallrye-openapi.store-schema-directory=build/openapi-generated
