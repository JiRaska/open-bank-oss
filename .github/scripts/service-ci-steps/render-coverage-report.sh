#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
# shellcheck source-path=SCRIPTDIR
source "$(dirname "${BASH_SOURCE[0]}")/coverage-evidence.sh"
coverage_paths
rm -f "$coverage_xml"
mkdir -p "$(dirname "$coverage_xml")"
# Render only the enforcing build's existing data. This helper is never the build/test command.
[[ -s "$SERVICE/build/kover/bin-reports/test.ic" ]] || { echo 'Missing enforcing test coverage data' >&2; exit 1; }
plan_dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/coverage-report.XXXXXX")"
trap 'rm -rf "$plan_dir"' EXIT
# Render away from every published path. A killed process cannot publish partial XML.
OPENBANK_KOVER_REPORT="$(cd "$plan_dir" && pwd)/report.xml"
export OPENBANK_KOVER_REPORT
cat > "$plan_dir/tasks.init.gradle" <<'GRADLE'
gradle.beforeProject { module ->
    if (module.path != ':' + System.getenv('SERVICE')) return
    module.afterEvaluate {
        module.extensions.getByName('kover').reports.total.xml.xmlFile.set(new File(System.getenv('OPENBANK_KOVER_REPORT')))
    }
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
coverage_run report ./gradlew --no-daemon --configure-on-demand --init-script "$plan_dir/tasks.init.gradle" ":$SERVICE:koverXmlReport" "${exclusions[@]}" \
  --build-cache --console=plain -Dquarkus.smallrye-openapi.store-schema-directory=build/openapi-generated
# Validate before atomic publication; an absent/malformed report remains unknown.
python3 - "$OPENBANK_KOVER_REPORT" "$coverage_xml" <<'PYVALIDATE'
import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
source, target = map(Path, sys.argv[1:])
root = ET.parse(source).getroot()
for kind in ['LINE', 'BRANCH']:
    counters = [node for node in root.findall('counter') if node.get('type') == kind]
    if len(counters) != 1 or any(int(counters[0].get(key, '-1')) < 0 for key in ['covered', 'missed']):
        raise SystemExit('Missing, ambiguous or invalid Kover report counters')
# Stage on the destination filesystem so os.replace is atomic across runner layouts.
descriptor, staged = tempfile.mkstemp(prefix='.validated-report-', dir=target.parent)
try:
    with os.fdopen(descriptor, 'wb') as output:
        output.write(source.read_bytes())
    os.replace(staged, target)
finally:
    Path(staged).unlink(missing_ok=True)
PYVALIDATE
