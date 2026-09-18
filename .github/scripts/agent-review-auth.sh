#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# Source this after budget admission and before the single model invocation.
case "${REVIEW_AUTH_MODE:-api}" in
  api)
    unset CLAUDE_CODE_OAUTH_TOKEN
    if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
      echo '::error::dedicated review API key is missing; no credential fallback' >&2
      return 1
    fi
    ;;
  subscription)
    unset ANTHROPIC_API_KEY
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
      echo '::error::review subscription token is missing; no credential fallback' >&2
      return 1
    fi
    ;;
  *)
    echo '::error::unsupported review authentication mode' >&2
    return 1
    ;;
esac
