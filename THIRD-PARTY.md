# Third-party components

Status: **pre-implementation.** No dependency is wired yet; this file records the
baseline so the license audit starts before the first line of code is merged.

| Component | Role | License | Obligation to us |
|---|---|---|---|
| Readium Kotlin Toolkit | EPUB parsing, navigator, locators | BSD-3-Clause | Keep copyright notice and license text; no endorsement claim |
| Kotlin / Kotlin Multiplatform | language and stdlib | Apache-2.0 | Keep NOTICE if present |
| Compose Multiplatform | UI toolkit | Apache-2.0 | Keep NOTICE if present |
| SQLDelight | SQLite bindings and migrations | Apache-2.0 | Keep NOTICE if present |
| Ktor Client | HTTP transport | Apache-2.0 | Keep NOTICE if present |
| AndroidX / Android platform libs | platform APIs | Apache-2.0 | Keep NOTICE if present |

## Rules

- Adding a dependency means adding a row here **in the same pull request**, with
  its license and any NOTICE obligation.
- A GPL/AGPL/SSPL dependency is not acceptable in the client: it would relicense
  the distributed binary and conflict with MPL-2.0 file boundaries.
- Fonts, icons, illustrations, sample text and book covers are **not** covered by
  a code license. Do not bundle a third-party asset without a recorded permission.
- Before a public release, re-verify every row against the resolved dependency
  graph (not against this table) and archive the result with the release.
