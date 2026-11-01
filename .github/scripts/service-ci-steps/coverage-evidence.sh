#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Shared by the staged build/report helpers. A readable partial ProjectData is not
# valid coverage: the Kover reader logs load failures but can still return exit 0.
coverage_paths() {
  : "${SERVICE:?SERVICE is required}"
  [[ "$SERVICE" =~ ^openbank-[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo 'Invalid coverage module' >&2; return 1; }
  coverage_xml="$SERVICE/build/reports/kover/report.xml"
}

coverage_preserve_invalid() {
  python3 - "$SERVICE" "$1" <<'PY'
import hashlib
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path
service, phase = Path(sys.argv[1]), sys.argv[2]
if phase not in {'build', 'report', 'incomplete'}:
    raise SystemExit('Invalid coverage diagnostic phase')
raw_dir = service / 'build/kover/bin-reports'
target = service / 'build/coverage-diagnostics' / phase
for directory in [service, service / 'build', service / 'build/kover', raw_dir,
                  service / 'build/coverage-diagnostics', target]:
    if directory.is_symlink():
        raise SystemExit('Refusing symlinked coverage evidence directory')
target.mkdir(parents=True, exist_ok=True)
def publish(destination, content, source=None):
    if destination.is_symlink() or (destination.exists() and not destination.is_file()):
        raise SystemExit('Refusing non-regular coverage diagnostic destination')
    descriptor, temporary = tempfile.mkstemp(prefix='.coverage-', dir=target)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(content)
        if source is not None:
            shutil.copystat(source, temporary)
        # Replacing a destination does not follow a symlink created after the check.
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)

files = []
for source in sorted(raw_dir.glob('*.ic')):
    if source.is_symlink() or not source.is_file():
        raise SystemExit('Refusing non-regular raw coverage evidence')
    raw = source.read_bytes()
    saved = target / source.name
    publish(saved, raw, source)
    files.append({'name': source.name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
publish(target / 'invalid.json', (json.dumps({
    'reason': 'coverage-run-incomplete' if phase == 'incomplete' else 'kover-coverage-load-failed',
    'phase': phase, 'files': files,
}, sort_keys=True) + '\n').encode())
PY
}

coverage_run() {
  local phase="$1" log command_rc capture_rc grep_rc
  shift
  coverage_paths || return
  log="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kover-evidence.XXXXXX")" || return
  local -a statuses
  if "$@" 2>&1 | tee "$log"; then
    statuses=("${PIPESTATUS[@]}")
  else
    statuses=("${PIPESTATUS[@]}")
  fi
  command_rc="${statuses[0]}"
  capture_rc="${statuses[1]}"
  if grep -Eq '\(Coverage[[:space:]]+WARN\): Failed to load coverage data from file:' "$log"; then
    rm -f "$coverage_xml"
    coverage_preserve_invalid "$phase" || echo '::error::Could not preserve invalid raw coverage evidence' >&2
    rm -f "$log"
    echo '::error::Kover could not load coverage data; percentages are invalid evidence' >&2
    [[ "$command_rc" -eq 0 ]] || return "$command_rc"
    return 65
  else
    grep_rc=$?
  fi
  rm -f "$log"
  [[ "$command_rc" -eq 0 ]] || return "$command_rc"
  [[ "$capture_rc" -eq 0 ]] || return "$capture_rc"
  [[ "$grep_rc" -eq 1 ]] || return "$grep_rc"
}

# Separate failure-only snapshot also runs after a renderer timeout/hard kill.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  [[ "${1:-}" == preserve-incomplete ]] || { echo 'Invalid coverage helper command' >&2; exit 2; }
  coverage_paths
  coverage_preserve_invalid incomplete
fi
