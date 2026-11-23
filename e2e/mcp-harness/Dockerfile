# SPDX-License-Identifier: Apache-2.0
# Keep the browser image version in sync with playwright-core in package-lock.json.
ARG PLAYWRIGHT_VERSION=1.65.1
FROM mcr.microsoft.com/playwright:v${PLAYWRIGHT_VERSION}-noble@sha256:1507cbfc550a63a6b7396791ca4f00df17beb56342d2447b3f106c306f525579

WORKDIR /opt/openbank/mcp-harness
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY src/ ./src/
COPY run-sandbox-journey.sh ./

USER pwuser
ENTRYPOINT ["sh", "./run-sandbox-journey.sh"]
CMD ["--read-only", "--verify-revocation"]
