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

# 2) Compile the fuzz harnesses against them. The fuzz module is a standalone build with
# its own settings.gradle.kts and NO wrapper of its own, so drive it with the ROOT wrapper
# (which provides the Gradle distribution) — a bare `gradle` is not on the OSS-Fuzz
# base-builder-jvm image. `-p` points the root wrapper at the standalone project dir, which
# uses fuzz/ossfuzz/settings.gradle.kts.
./gradlew -p fuzz/ossfuzz shadowJar --no-daemon -q

# 3) Package per OSS-Fuzz JVM conventions.
cp fuzz/ossfuzz/build/libs/ossfuzz-all.jar "$OUT/openbank.jar"

for fuzzer in Pacs008ReaderFuzzer RodneCisloFuzzer; do
  # The "LLVMFuzzerTestOneInput" comment below is a REQUIRED magic marker, not doc:
  # OSS-Fuzz/CIFuzz recognizes a file in $OUT as a fuzz target only if its CONTENT
  # contains that string (infra/utils.py is_fuzz_target_local, FUZZ_TARGET_SEARCH_STRING)
  # — without it check_build fails with "No fuzz targets found". Same canonical wrapper
  # (incl. the ASan-headroom -Xmx/-Xss settings) as google/oss-fuzz JVM projects.
  cat > "$OUT/$fuzzer" <<SH
#!/usr/bin/env bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+(\$| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="\$JVM_LD_LIBRARY_PATH" \\
  "\$this_dir/jazzer_driver" --agent_path="\$this_dir/jazzer_agent_deploy.jar" \\
  --cp="\$this_dir/openbank.jar" \\
  --target_class=com.openbank.fuzz.$fuzzer \\
  --jvm_args="\$mem_settings" \\
  "\$@"
SH
  chmod +x "$OUT/$fuzzer"
done
