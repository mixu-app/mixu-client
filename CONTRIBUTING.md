# Contributing to mixu-client

Thanks for wanting to help. This project is in a **documentation-first phase**:
the most useful contribution right now is review of the design, a reproduction of
a reading problem, or a focused improvement to a test or fixture — not a large
unsolicited feature branch.

## Before you write code

1. Read `AGENTS.md` (repository rules), then `mixu-docs` →
   `docs/product/product-brief.md` and the requirement section you intend to
   affect.
2. Open an issue describing the problem **before** the PR for anything larger
   than a typo. Say which requirement ID (`REA-*`, `GRF-*`, `UX-*`, `NFR-*`) it
   touches.
3. Wait for a scoped task. Unrequested refactors are usually rejected even when
   they are good, because they make review and blame noisy.

## What we especially need

- Readium integration findings on real devices, including E-Ink hardware.
- CJK typography and mixed CJK/Latin name rendering edge cases.
- Accessibility: TalkBack labels, text scaling, contrast, reduced motion.
- Large-graph performance on mid-range phones.
- Spoiler regressions: a reproducible case where something is revealed too early.

## Rules that are not negotiable

- **No book text in the repository.** No EPUB files, no excerpts, no extracted
  graphs of in-copyright works. Test with a fixture from
  [`mixu-fixtures`](https://github.com/mixu-app/mixu-fixtures).
- **No credentials.** No tokens, keys, bucket names, presigned URLs, or account
  ids — not in code, tests, screenshots, or issues.
- **Spoiler safety is test-gated.** A change touching projection, search,
  labels, counts or evidence navigation must include a test that fails without
  the fix.
- **Guest mode stays complete.** Nothing may make an account a prerequisite for
  local reading.
- **Comments and documents in English; user-facing strings in Chinese.** See
  `mixu-docs` → `docs/development/language-policy.md`.

## Process

- Fork, branch from `main`, keep one subject per PR.
- Sign off every commit: `git commit -s` (Developer Certificate of Origin). There
  is no separate CLA.
- Run `just check` locally. CI runs the same recipes; a PR that only passes
  because a gate was skipped will be sent back.
- Reference the issue (`Closes #123`) and state the requirement IDs affected.

## Review

A change is accepted when: the behaviour matches the requirement, offline and
E-Ink paths still work, spoiler tests cover it, and the docs that describe it are
updated in the same change. Review comments about scope are not personal; they
keep the project shippable.

## Code of conduct

Participation is covered by [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
