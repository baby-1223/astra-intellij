# Astra

A local-first API client for JetBrains IDEs. Design, send, and automate HTTP requests without leaving your IDE — no account, no cloud sync, no telemetry.

[![Build](https://github.com/baby-1223/astra-intellij/actions/workflows/build.yml/badge.svg)](https://github.com/baby-1223/astra-intellij/actions/workflows/build.yml)

## Features

- **Request workspace** — organize requests in collections and nested folders, with multi-tab editing and instant title sync.
- **Full request editor** — method, URL, query params, headers, body (raw / form), authorization, pre-request scripts, and tests.
- **Response viewer** — body, headers, cookies, test results, and per-request history with status, timing, and size.
- **Environments and globals** — variables resolved at send time (`{{variable}}` syntax), switchable per environment.
- **Collection and folder runner** — iterate a collection or folder with configurable iteration count, delay, and data files (CSV / JSON), with a Postman-style results table.
- **Import and export** — Postman collections and environments, OpenAPI / Swagger JSON, and cURL commands.
- **Local storage** — the workspace is stored in the project's `.idea/astra.xml`, so it can be shared and version-controlled with the project.

## Requirements

- IntelliJ IDEA 2024.2 or later (any JetBrains IDE based on platform 242+)
- JDK 21 to build from source

## Installation

### From JetBrains Marketplace

Search for **Astra** in `Settings → Plugins → Marketplace`, or install from the plugin page once published.

### From a release build

1. Download `astra-intellij-<version>-signed.zip` from [Releases](https://github.com/baby-1223/astra-intellij/releases).
2. In the IDE: `Settings → Plugins → ⚙ → Install Plugin from Disk…`
3. Select the zip and restart the IDE.

## Getting started

1. Open the **Astra** tool window on the right side of the IDE.
2. Create a collection, then add a request (or import a Postman collection / OpenAPI spec / cURL command).
3. Enter the URL and click **Send**.
4. Inspect the response, or run the whole collection with the collection runner.

## Keyboard shortcuts

| Action | Shortcut |
| --- | --- |
| New request tab | `Cmd/Ctrl+N` |
| Close request tab | `Cmd/Ctrl+W` |
| Previous / next request tab | `Alt+←` / `Alt+→` |
| Run collection (runner dialog) | `Cmd/Ctrl+Enter` |
| Close dialog | `Esc` |

## Building from source

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; use your JDK 21 path elsewhere
./gradlew unitTest buildPlugin
```

The plugin zip is written to `build/distributions/`.

Run tests only:

```bash
./gradlew unitTest
```

## Releasing

Releases are automated by GitHub Actions ([build.yml](.github/workflows/build.yml)):

1. Bump `version` in `build.gradle.kts` and `plugin.xml`.
2. Commit and tag: `git tag v0.1.2 && git push origin main --tags`.

On a `v*` tag the workflow runs tests, builds the plugin, signs it, publishes to JetBrains Marketplace, and creates a GitHub Release with the signed zip attached. Signing and publishing require the `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, and `PUBLISH_TOKEN` repository secrets; without them the build still succeeds but the artifacts are unsigned and not published.

## Project layout

```
src/main/kotlin/com/astra/intellij/
├── core/        HTTP client, runner, import/export, variable resolution
├── model/       Workspace data model and tree operations
├── ui/          Tool window, editors, response view, dialogs
└── workspace/   Persistent state service
```

## License

[Apache License 2.0](LICENSE)
