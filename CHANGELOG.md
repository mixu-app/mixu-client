# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once a tagged release
exists.

## [Unreleased]

### Added

- `docs/testing/eink-test-protocol.md`: manual E-Ink verification protocol and
  structured evidence runbook covering hardware matrix, six core test cases
  (cold start latency, page-turn ghosting, typography contrast, case board pan/redraw,
  touch targets/focus, and battery/thermal soak), log templates, and an M0/M1 baseline run.
- Repository scaffolding: `AGENTS.md` (agent rules), `repo.toml` (identity and
  contract edges), `.carryctx/config.toml` (shared CarryCtx baseline), `justfile`
  gates, CI workflow, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`.
- `README.md` documenting the planned module layout, the non-negotiable
  boundaries (guest reading, one E-Ink APK, no cloud credentials, spoiler
  filtering), and the local toolchain prerequisites.

### Notes

- No product code exists yet; the Gradle gate is a documented phase exception
  until a wrapper lands.
