# Contributing Guide

## Implementation Principles

- Keep classes small and single-purpose.
- Prefer composition over inheritance.
- Introduce interface boundaries before large moves.
- Avoid behavior changes in structural refactors unless explicitly scoped.

## Refactor Workflow

1. Add or strengthen tests around behavior you are about to move.
2. Extract interface/adapter boundaries.
3. Move code in small slices.
4. Re-run compile/test/lint after each slice.
5. Update docs and ADRs in the same change.

## Verification Gates

Run these before completion:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

For UI/runtime-impacting changes, also validate on emulator/device.

## Documentation Gate

If module responsibilities, dependency directions, or long-lived decisions changed:

- update `docs/ARCHITECTURE.md`
- add or update an ADR in `docs/adr/`
