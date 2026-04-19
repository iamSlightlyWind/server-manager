# Architecture Overview

## Layer Map

- `ui/`: Compose navigation, screens, reusable UI components, UI state/viewmodel orchestration.
- `metrics/`: machine telemetry collection and metric model definitions.
- `services/`: service-list and service-action operations executed remotely.
- `network/`: route selection and network diagnostics.
- `ssh/`: remote command execution and SSH session lifecycle.
- `data/`: machine/domain persistence and parent-link relationships.
- `security/`: credential storage and access.

## Current Dependency Direction

- UI depends on container-provided services only.
- `metrics` depends on abstractions:
  - `network.RouteResolver`
  - `ssh.RemoteCommandExecutor`
- `services` depends on `ssh.RemoteCommandExecutor`.
- Infrastructure implementations:
  - `network.SmartRouter` implements `RouteResolver`.
  - `ssh.SshSessionManager` implements `RemoteCommandExecutor`.

## SOLID Refactor Targets

1. SRP: split large files into smaller classes with one reason to change.
2. OCP: isolate parser/transport variations behind interfaces.
3. LSP: keep interface contracts implementation-agnostic.
4. ISP: prefer focused interfaces (`RouteResolver`, `RemoteCommandExecutor`) over broad service objects.
5. DIP: high-level modules depend on abstractions, not concrete classes.

## Current Known Monoliths

- `ui/ServerManagerNavHost.kt`
- `metrics/MetricsCollector.kt`

These files should be decomposed in upcoming phases while preserving behavior.
