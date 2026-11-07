# mixu-client agent guide

## Scope and authority

- Governs only this repository. Workspace rules in `../AGENTS.md` apply; the
  roster is `../workspace.toml`.
- Public repository, **MPL-2.0**: modifications to existing files must be
  published when a binary is distributed. New files may stay proprietary. Every
  new source file starts with `// SPDX-License-Identifier: MPL-2.0`.
- Never commit credentials, book files, price catalogs, prompt text, model
  routing, or presigned URLs. This repository is world-readable forever.
- Requirements live in `mixu-docs`; this repository implements them. Cite IDs
  (`REA-*`, `GRF-*`, `ACC-*`, `AI-*`, `UX-*`, `NFR-*`) instead of restating
  behaviour.

## Current phase

- Documentation-first, pre-implementation. No product code until a scoped
  CarryCtx task authorizes it.
- `just check` = markdownlint + Gradle when a wrapper exists. Until the wrapper
  lands, the Gradle recipe prints a **documented phase exception**; once it
  lands the same recipe must fail on broken code. Never weaken a recipe to keep
  a broken tree green.

## Submodule mounts (read-only)

| Mount | Repository |
|---|---|
| `libs/mixu-graph` | temporal graph model, projection, queries |
| `libs/mixu-spoiler` | knowledge-horizon filtering |
| `libs/mixu-renderer` | Compose canvas, layout, E-Ink policy |
| `contracts` | graph pack schemas, API contracts, error codes |

- `git clone --recurse-submodules` must yield a complete tree: one repository to
  learn, links for the rest.
- **Never edit inside a mount.** Change the owning repository, then move the
  gitlink (`git update-index --cacheinfo 160000,<sha>,<path>`). Never replace a
  mount with a symlink, and never let a pin track a moving branch.
- Mounted paths are excluded from this repository's lint gate; do not re-enable
  them, or the same file is linted twice with two different configs.
- The client **composes** these libraries: no forked projection logic, no
  filtering inside a composable or a renderer.

## Module boundaries (planned)

- `shared/domain`: models and pure logic; no Android or Readium types.
- `shared/persistence`: the only place that opens SQLDelight databases.
- `platform/reader-android`: the only place that constructs a Readium navigator.
- `shared/network`: typed client and contract DTOs; no credentials, no billing.
- Feature modules do not reach into each other; cross-feature navigation goes
  through the app shell.

## Consumed libraries (separate repos, Apache-2.0)

- `mixu-graph` owns projection and query semantics; `mixu-spoiler` owns horizon
  filtering; `mixu-renderer` owns the canvas and E-Ink display policy.
- The client **composes** them. Do not fork their logic into app code, and do
  not filter inside a renderer or a composable.
- `mixu-contracts` is authoritative for wire and pack shapes; consume it pinned
  at an exact commit.

## Non-negotiable behaviour

1. Guest reading is complete: import, read, bookmark, note, installed graph packs
   work with no account and no network.
2. Three progress values stay separate (`reading_position`,
   `knowledge_horizon`, `graph_view_position`); a backward jump never resets the
   horizon, and the reveal slider never moves the reader.
3. Nothing unfiltered reaches the UI: no labels, counts, slots or previews that
   require a later reveal. Filtering happens before rendering.
4. Narrator statements, witness claims, inference and confirmation stay
   distinguishable; identity merges never rewrite an earlier view.
5. One APK for normal and E-Ink devices; E-Ink mode reduces animation, never
   forces itself.
6. Locators, not page numbers, are persisted.
7. No proactive suspect ranking, contradiction hints or puzzle solving.

## Testing

- A change touching projection, search, labels, counts or evidence navigation
  needs a `mixu-fixtures` case and a test that fails without the fix.
- Assert on identifiers, not on user-facing strings.
- Accessibility and E-Ink behaviour are test targets, not afterthoughts.

## Gates

```sh
just check      # markdownlint + gradle (phase exception until wrapper lands)
```

Missing tooling is installed (pacman / sdkmanager / uv / bun), never skipped.
Adding a dependency requires a `THIRD-PARTY.md` row in the same change; a
GPL/AGPL/SSPL dependency is rejected here.

## CarryCtx

- `cd` into this repository before `carryctx` or Git; state is per repository.
- Branches `ctx-XXXX/<type>-<slug>`; record progress and checkpoints as you go.
- Export `CARRYCTX_TASK` and `CARRYCTX_AGENT` before committing so the hook
  writes the right prefix.
- No commit, push or release without authorization from the owning task or the
  user.

## Review checklist

1. Guest reading still complete offline?
2. Anything leaked past the horizon (label, count, slot, search hit)?
3. A DTO diverged from `mixu-contracts`?
4. Was a file edited inside a submodule mount instead of its owning repository?
5. A page number used where a locator is required?
6. E-Ink path introduced animation or redundant redraw?
7. Is there a test that fails without the change?
