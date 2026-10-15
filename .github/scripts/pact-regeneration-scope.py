# SPDX-License-Identifier: Apache-2.0
"""Only Admin UI changes plus one proven-neutral cochange can omit JVM Pact regeneration."""
import sys
from pathlib import Path


NEUTRAL_REALM_TEMPLATE = "openbank-infra/gitops/components/keycloak/realm-template.json"
UI_BUILD_FILES = {
    "openbank-admin-ui/build.gradle",
    "openbank-admin-ui/build.gradle.kts",
}


def jvm_required(paths, ui_is_jvm_module=False):
    if not paths:
        return True
    if ui_is_jvm_module:
        return True
    has_ui_path = False
    for path in paths:
        parts = path.split("/")
        if (len(parts) < 2 or any(part in ("", ".", "..") for part in parts)
                or any(ord(character) < 32 for character in path)):
            return True
        if path in UI_BUILD_FILES:
            # A build-file addition or deletion can change whether settings.gradle.kts includes
            # the Admin UI as a JVM project, so it always requires the JVM consumer check.
            return True
        if path.startswith("openbank-admin-ui/"):
            has_ui_path = True
        elif path != NEUTRAL_REALM_TEMPLATE:
            return True
    # The selected consumerPactTest source set compiles only *PactConsumerTest.kt classes and
    # explicitly listed helpers. The Kotlin tests that read this Keycloak realm template are
    # outside that source/test filter; no realm JSON is a consumer-Pact runtime input.
    # Keep this exact cochange exception narrow: a realm-only change still requires JVM proof.
    return not has_ui_path


def main():
    raw = sys.stdin.buffer.read()
    # The caller must use git diff --name-only -z. Malformed inventory is never
    # an authorization to skip checks, including undecodable filenames.
    try:
        if not raw or not raw.endswith(b"\0"):
            required = True
        else:
            root = Path(__file__).resolve().parents[2]
            # A present Gradle build script removes the UI-only trust boundary. The Kotlin
            # marker is auto-included by settings.gradle.kts; treat Groovy conservatively too.
            ui_is_jvm_module = any((root / path).exists() for path in UI_BUILD_FILES)
            required = jvm_required(raw[:-1].decode("utf-8").split("\0"), ui_is_jvm_module)
    except UnicodeDecodeError:
        required = True
    print("true" if required else "false")


if __name__ == "__main__":
    main()
