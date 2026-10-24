#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
if ! command -v aws >/dev/null 2>&1; then
  echo "::warning title=CodeArtifact mirror skipped::aws CLI not on this runner — falling back to public Maven Central (NAT cost + 429 risk). Bake awscli into the runner image (runner-image/Dockerfile)."
  exit 0
fi
CA_TOKEN=$(aws codeartifact get-authorization-token \
  --domain openbank-sandbox \
  --domain-owner 265175468565 \
  --region eu-north-1 \
  --query authorizationToken --output text) || {
  echo "::warning title=CodeArtifact mirror skipped::token fetch failed (IRSA not wired on this runner?) — falling back to public Maven Central."
  exit 0
}
CA_URL="https://openbank-sandbox-265175468565.d.codeartifact.eu-north-1.amazonaws.com/maven/maven-central-proxy/"
mkdir -p "${GRADLE_USER_HOME}/init.d"
cat > "${GRADLE_USER_HOME}/init.d/codeartifact-mirror.gradle" <<GRADLE_INIT
allprojects {
  repositories.all { repo ->
    if (repo instanceof MavenArtifactRepository) {
      def url = repo.url.toString()
      if (url.contains("repo1.maven.org") || url.contains("repo.maven.apache.org") ||
          url.contains("plugins.gradle.org")) {
        repo.setUrl("${CA_URL}")
        repo.credentials {
          username "aws"
          password "${CA_TOKEN}"
        }
      }
    }
  }
}
GRADLE_INIT
