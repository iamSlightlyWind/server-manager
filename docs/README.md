# Documentation Hub

This folder contains architecture and contributor documentation for Server Manager.

## Read First

1. `ARCHITECTURE.md` - current layer map and module responsibilities.
2. `CONTRIBUTING.md` - implementation workflow and verification gates.
3. `adr/` - Architecture Decision Records that explain why key choices were made.

## Current Refactor Status

The codebase is in an incremental SOLID refactor. During this period:

- Prefer small, single-purpose classes.
- Add interfaces at module boundaries.
- Keep behavior stable while moving responsibilities.
- Update docs in the same change as code.

## Required Update Rule

Any PR or session that changes architecture boundaries must update:

- `ARCHITECTURE.md` for ownership/flow changes.
- An ADR file if the change affects long-lived technical decisions.
