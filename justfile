# mixu-client quality gates. Run via `just check`, never bare tool invocations.
set shell := ["bash", "-euo", "pipefail", "-c"]

check: lint-md check-pins gradle-check

lint-md:
    markdownlint-cli2 --config .markdownlint-cli2.jsonc

check-pins:
    ./script/check-pins.sh

gradle-check:
    ./gradlew --no-daemon check

# Build the local debug APK for device UI acceptance.
assemble-debug:
    ./gradlew --no-daemon :app:assembleDebug

format-kotlin:
    ./gradlew --no-daemon :app:ktlintFormat

# Build the release APK using the existing environment-supplied signing configuration.
assemble-release:
    ./gradlew --no-daemon :app:assembleRelease
