# mixu-client quality gates. Run via `just check`, never bare tool invocations.
set shell := ["bash", "-euo", "pipefail", "-c"]

check: lint-md check-pins gradle-check

lint-md:
    markdownlint-cli2 --config .markdownlint-cli2.jsonc

check-pins:
    ./script/check-pins.sh

gradle-check:
    ./gradlew --no-daemon check
