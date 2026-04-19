# ADR 0001: Introduce Initial SOLID Boundaries

## Status

Accepted

## Context

The codebase has high-coupling hotspots where business logic depends directly on concrete infrastructure classes. This made testing and decomposition difficult.

## Decision

Introduce two initial boundary interfaces:

- `ssh.RemoteCommandExecutor`
- `network.RouteResolver`

Adopt these interfaces in high-level modules:

- `metrics.MetricsCollector` now depends on abstractions instead of concrete `SshSessionManager` and `SmartRouter`.
- `services.ServicesRepository` now depends on `RemoteCommandExecutor`.

Concrete implementations remain:

- `ssh.SshSessionManager` implements `RemoteCommandExecutor`.
- `network.SmartRouter` implements `RouteResolver`.

Manual DI remains in `AppContainer` for now.

## Consequences

### Positive

- Reduced coupling between orchestration modules and transport/routing implementations.
- Easier unit testing via mock/fake interfaces.
- Cleaner path for future module decomposition.

### Tradeoffs

- Slightly more wiring complexity in container setup.
- Temporary hybrid state while broader refactor continues.
