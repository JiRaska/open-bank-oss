#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Recovery after setup-trivy fails: same release, API asset transport, verified before execution.
set -euo pipefail
readonly version=0.72.0
case "$(uname -s)/$(uname -m)" in
  Linux/aarch64|Linux/arm64) arch=ARM64 ;;
  Linux/x86_64|Linux/amd64) arch=64bit ;;
  *) echo 'Unsupported Trivy runner platform' >&2; exit 1 ;;
esac
readonly asset="trivy_${version}_Linux-${arch}.tar.gz"
readonly manifest="trivy_${version}_checksums.txt"
work="$(mktemp -d "${RUNNER_TEMP:?}/trivy-release.XXXXXX")"
trap 'rm -rf "$work"' EXIT
fetched=false
for attempt in 1 2 3; do
  if gh release download "v${version}" --repo github.com/aquasecurity/trivy \
    --pattern "$asset" --pattern "$manifest" --dir "$work" --clobber; then
    fetched=true
    break
  fi
  echo "Trivy release asset download attempt ${attempt} failed" >&2
  if [ "$attempt" -lt 3 ]; then sleep "$((attempt * 2))"; fi
done
if [ "$fetched" != true ]; then
  echo 'Trivy installation failed; vulnerability scans have NOT run' >&2
  exit 1
fi
checksum="$(awk -v asset="$asset" '$2 == asset { print $1 }' "$work/$manifest")"
# Require exactly one manifest entry; missing, duplicated and malformed hashes fail closed.
printf '%s\n' "$checksum" | grep -Eq '^[0-9a-f]{64}$'
[ "$(printf '%s\n' "$checksum" | wc -l | tr -d ' ')" = 1 ]
printf '%s  %s\n' "$checksum" "$work/$asset" | sha256sum -c -
tar -xzf "$work/$asset" -C "$work" trivy
binary_dir="${HOME:?}/.local/bin"
mkdir -p "$binary_dir"
install -m 0755 "$work/trivy" "$binary_dir/trivy"
printf '%s\n' "$binary_dir" >> "${GITHUB_PATH:?}"
"$binary_dir/trivy" --version
