# Contributing to Kdrant

Thanks for your interest in contributing.

## Getting started

- JDK 17+ is required. Docker is only needed to run the integration tests.
- Build and test everything: `./gradlew build`.

## Pull requests

- Keep changes focused and covered by tests.
- Follow the existing style (`.editorconfig`: 4-space indentation, 120-column lines, no wildcard
  imports).
- If you change the public API, run `./gradlew apiDump` and commit the updated `*.api` and
  `*.klib.api` files. CI runs `apiCheck` and will fail on an untracked API change.
- **Regenerate the dumps on macOS.** The `.klib.api` files cover every native target, and the Apple
  targets can only be built on a Mac. Running `apiDump` on Linux or Windows writes a dump with the
  iOS and macOS declarations silently missing, which then reads as a removal in the next diff. The
  `Apple targets` CI job runs `apiCheck` on macOS for exactly this reason; if you have no Mac, open
  the pull request and let that job produce the failure, then take the dump from its logs.
- Add an entry under `[Unreleased]` in `CHANGELOG.md`.

## Commit messages

- Use a clear, imperative subject line (for example, "Add scroll as a Flow").

## Reporting issues

- Include the Qdrant version, a minimal reproduction, and the observed vs expected behavior.

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
