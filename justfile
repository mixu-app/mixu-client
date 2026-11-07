# mixu-client quality gates. Run via `just check`, never bare tool invocations.
set shell := ["bash", "-euo", "pipefail", "-c"]

check: lint-md gradle-check

lint-md:
    markdownlint-cli2 --config .markdownlint-cli2.jsonc

# Phase exception: no Gradle wrapper exists yet (documentation-first phase).
# The gate fails loudly once a wrapper lands and its own checks break.
gradle-check:
    @if [ -x ./gradlew ]; then ./gradlew --no-daemon check; else \
        echo "PHASE EXCEPTION: no ./gradlew yet; Gradle gate not active. See AGENTS.md."; fi
