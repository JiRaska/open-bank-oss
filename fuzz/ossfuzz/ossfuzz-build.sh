#!/usr/bin/env bash
# OSS-Fuzz build script for the OpenBank Jazzer targets. The copy in
# google/oss-fuzz projects/openbank/build.sh is a two-line shim that execs this
# file, so build logic stays maintainer-owned in this repo.
#
# Environment provided by the OSS-Fuzz base-builder-jvm image:
#   $SRC  - source checkout root      $OUT - fuzzer output dir
#   $JAZZER_API_PATH - jazzer-api jar $LIB_FUZZING_ENGINE_JAVA - driver
set -euo pipefail

cd "$SRC/open-bank-oss"

# 1) Compile the fuzzed classes (domain module only — no Quarkus, no containers).
./gradlew :openbank-libs-domain:classes --no-daemon -q

# 2) Compile the fuzz harnesses against them (standalone module).
(cd fuzz/ossfuzz && gradle shadowJar --no-daemon -q)

# 3) Package per OSS-Fuzz JVM conventions.
cp fuzz/ossfuzz/build/libs/ossfuzz-all.jar "$OUT/openbank.jar"

for fuzzer in Pacs008ReaderFuzzer RodneCisloFuzzer; do
  cat > "$OUT/$fuzzer" <<SH
#!/usr/bin/env bash
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="\$JVM_LD_LIBRARY_PATH" \\
  "\$this_dir/jazzer_driver" --agent_path="\$this_dir/jazzer_agent_deploy.jar" \\
  --cp="\$this_dir/openbank.jar" \\
  --target_class=com.openbank.fuzz.$fuzzer \\
  "\$@"
SH
  chmod +x "$OUT/$fuzzer"
done
