#!/usr/bin/env bash
# ClusterFuzzLite shim — build logic stays maintainer-owned in fuzz/ossfuzz/ossfuzz-build.sh
# (the exact pattern the google/oss-fuzz projects/openbank/build.sh copy will use, see
# fuzz/ossfuzz/README.md).
exec bash "$SRC/open-bank-oss/fuzz/ossfuzz/ossfuzz-build.sh"
