<!-- markdownlint-disable-file MD041 -->
<!-- Conventional Commit subject: <type>(<scope>): <description> -->
Priority: ... | Area: ... | Labels: ... | Milestone: ... | Task: CTX-XXXX

## What changed

<!-- One paragraph. Link the issue: Closes #NNN -->

## Why

<!-- Requirement ID, ADR/OQ, or the defect this fixes. -->

## Verification & Testing

- [ ] `just check` passes cleanly (`markdownlint-cli2`, `check-pins.sh`, `ktlint`, unit tests, `lintDebug`)
- [ ] Relevant unit / property tests added or updated
- [ ] Tested across Phone, Tablet (1-col & 2-col spread), and E-Ink display policies

## Boundary check

- [ ] No credentials, account IDs, bucket names, or presigned URLs
- [ ] No in-copyright book text, EPUB payloads, or personal notes
- [ ] Public/private split respected (client works 100% offline or with self-hosted stateless API)
- [ ] Documentation updated (or explicitly not needed)
- [ ] If this touches projection, search, or retrieval: spoiler regression tests pass

## Rollback

<!-- How to revert safely. -->
