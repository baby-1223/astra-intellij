# Astra

A local-first API client for JetBrains IDEs. Design, send, and automate HTTP requests without leaving your IDE — no account, no cloud sync, no telemetry.

[![Build](https://github.com/compiler-says-no/astra-intellij/actions/workflows/build.yml/badge.svg)](https://github.com/compiler-says-no/astra-intellij/actions/workflows/build.yml)

## Features

- **Request workspace** — organize requests in collections and nested folders, with multi-tab editing and instant title sync.
- **Full request editor** — method, URL, query params, headers, body (raw / form / multipart with file upload), authorization, pre-request scripts, and tests.
- **Assertions and extraction** — declarative `status` / `time` / `body` / `header` / `jsonpath` assertions, plus `extract token = $.data.token` to store response values into the active environment.
- **Response viewer** — body, headers, cookies, test results, and per-request history with status, timing, and size.
- **Environments and globals** — variables resolved at send time (`{{variable}}` syntax), switchable per environment.
- **Collection and folder runner** — iterate a collection or folder with configurable iteration count, delay, and data files (CSV / JSON), with a Postman-style results table.
- **Import and export** — Postman collections, environments and data dumps, Insomnia collections, OpenAPI / Swagger (JSON and YAML), `.http` / `.rest` files, and cURL commands.
- **IDE integration** — import HTTP and OpenAPI files from the editor, generate requests from Spring controllers, and load variables from `application.yml`.
- **Mock server** — serve generated responses for a collection or OpenAPI document on a local port.
- **Starred collections** — star collections to pin them above the rest, and optionally show only starred items.
- **Stability and data safety** — shared workspace schema with automatic migration, crash-safe unsaved drafts, large-response protection, and credentials kept in the system credential store.
- **Local storage** — the workspace lives in the IDE configuration directory and is shared by every IDE window on the machine.

## Reliability and data safety

- **Shared, versioned workspace** — all IDE windows read and write one application-level workspace. A `schemaVersion` field lets newer plugin builds migrate older payloads instead of discarding them.
- **Crash recovery** — unsaved request tabs are written to `drafts.json` under the IDE config directory and restored after a crash or restart.
- **Credential store** — tokens, passwords, API keys, OAuth client secrets and refresh tokens are stored in the OS credential store; `astra.xml` only contains `secure:` placeholders.
- **Large responses** — previews are capped at 1.5M characters with an explicit truncation notice, while **Save…** exports the complete response body.
- **Charset detection** — responses are decoded from the declared charset, BOM or byte content (UTF-8 / UTF-16 / GB18030 and other JVM charsets), so non-UTF-8 endpoints display correctly.

## What's new in 0.4.1

- The divider between the request editor and the response panel can be dragged freely; the response area is no longer clamped to a fixed minimum height.

## What's new in 0.4.0

- **Spring generation overhaul** — 39 parsing gaps fixed: fully qualified and composed annotations, constants and multi-path mappings, inherited class paths, controller interfaces, cross-file DTOs, Java records, inheritance, non-private fields, `@JsonProperty` names, enum samples and cycle protection.
- `consumes` aware bodies: JSON, XML, plain text, `x-www-form-urlencoded` and `multipart/form-data` (including `@RequestPart` / `MultipartFile`).
- `@ModelAttribute`, unannotated DTO query parameters and merged `@CookieValue` headers.
- Path variables are emitted as `{{id}}` with sample values added to the Spring environment.
- Multiple controllers in one file generate separate collections; repeated generation updates requests in place.
- Spring profiles (`application-{profile}.yml`), `server.ssl.enabled` and non-UTF-8 source files are supported.
- Comments, raw strings, text blocks and nested classes no longer produce phantom endpoints.

## What's new in 0.3.17

- **Generic DTO bodies** — Spring controller generation substitutes type arguments into DTO fields, so `Result<User>`, `List<User>` and `Map<String, User>` produce real JSON instead of `{}`.
- Kotlin primary-constructor data classes and nullable types are supported; Java generic parameter types keep their commas.

## What's new in 0.3.16

- **Fix compatibility** — `SpillingKt` class-not-found on IntelliJ IDEA 2024.3+. Replaced Kotlin coroutine sequence in `SecretCodec` with a plain list.

## What's new in 0.3.15

- **Multipart file upload** — Form Data rows now have a `text` / `file` type. Choose a file and Astra sends its bytes with the original file name and content type. Missing files fail with a clear message instead of sending an empty part.
- **JSONPath assertions** — the `jsonpath` keyword supports `$.a.b`, `$['a']`, `[0]`, `[-1]`, `[*]`, and recursive `$..key`; wildcard matches pass when any value satisfies the assertion.
- **Extraction into variables** — add `extract token = $.data.token` to the Tests tab. Extracted values are written to the active environment and to the runner's variable scope, so later requests can use `{{token}}`.
- **Post-response alias** — `set token jsonpath $.data.token` is accepted alongside `set token json $.data.token`.

## What's new in 0.3.14

- **Code tab** — the request editor now has a **Code** tab right after **Post-response**. Pick a language (cURL, Python, JavaScript, Go, Java, IDEA HTTP Client) and copy a runnable snippet that reflects the current URL, headers, authentication, and body, including resolved environment variables.

## What's new in 0.3.13

- **IDE integration** — right-click a `.http` / `.rest` or OpenAPI document (JSON or YAML) and choose **Import into Astra**; right-click a Spring controller and choose **Generate Astra Requests** to create one request per mapping, including path variables, query parameters, headers, and DTO request bodies.
- **Spring configuration** — **Load Variables into Astra** turns `application.yml` / `application.properties` into an environment and derives `baseUrl` from `server.port` and `server.servlet.context-path`. Generating requests from a controller does this automatically.
- **Mock server** — start a local server from **Manage → Mock Server…** using a collection or an OpenAPI document. Documented endpoints use their examples or schema; undocumented endpoints get plausible generated payloads. Includes route and request logs, configurable port and delay, and CORS headers.
- **Star icon** — the collection star is now a vector five-pointed star drawn with Java2D instead of a font glyph, so it renders consistently on every platform.
- **Tests** — new unit tests cover HTTP file parsing, Spring controller and configuration parsing, OpenAPI YAML import, mock data generation, the mock HTTP server, and legacy workspace normalization.

## What's new in 0.3.12

- **Protocols** — GraphQL (query + variables), gRPC-Web payloads (JSON or base64 protobuf), SSE streaming, and WebSocket sessions with a message template library.
- **Authentication** — OAuth 2.0 (authorization code + PKCE, client credentials, password grant, automatic token refresh) alongside Bearer, Basic, and API Key. Collection- and folder-level auth and scripts are inherited by requests.
- **Response tools** — JSON tree with search, Cookies tab, saved response examples, line-by-line response diff, and large-response rendering caps with full-body export.
- **Request controls** — per-request timeout, redirect policy, transport-level retries, SOCKS5 / HTTP proxy with bypass list and proxy auth, and mTLS client certificates.
- **Runner and automation** — JUnit XML reports for CI, scheduled collection monitors, and Postman-style collection / folder runs.
- **Productivity** — Copy as code (cURL, Python, JavaScript, Go, Java, IntelliJ HTTP Client), `.env` / CSV variable import and `.env` export, and `{{variable}}` completion.
- **Stability and data safety** — shared versioned workspace, crash-safe unsaved drafts, credentials in the system credential store, and automatic charset detection (including GB18030 / UTF-16).
- **Multi-window sync** — all IDE windows share one workspace and refresh when another window saves.

## Star and import / export compatibility

- The star flag is local metadata stored with the workspace, so it survives Astra workspace exports and imports; it is not part of the Postman or Insomnia formats.
- Postman and Insomnia have no star concept: **stars are not written to those exports**, and **collections imported from them are always unstarred**.
- Imports always add new collections; re-importing or syncing between IDE windows never overwrites a collection or clears stars you set locally.

## Requirements

- IntelliJ IDEA 2024.2 or later (any JetBrains IDE based on platform 242+)
- JDK 21 to build from source

## Installation

### From JetBrains Marketplace

Search for **Astra** in `Settings → Plugins → Marketplace`, or install from the plugin page once published.

### From a release build

1. Download `astra-intellij-<version>-signed.zip` from [Releases](https://github.com/compiler-says-no/astra-intellij/releases).
2. In the IDE: `Settings → Plugins → ⚙ → Install Plugin from Disk…`
3. Select the zip and restart the IDE.

## Getting started

1. Open the **Astra** tool window on the right side of the IDE.
2. Create a collection, then add a request (or import a Postman collection / OpenAPI spec / `.http` file / cURL command).
3. Enter the URL and click **Send**.
4. Inspect the response, or run the whole collection with the collection runner.

Working from Java or Kotlin source, right-click a Spring controller and choose **Generate Astra Requests**; Astra creates the collection and loads the application's `application.yml` variables at the same time.

Generation details:

- Java and Kotlin controllers, including interfaces and implementations; composed annotations such as `@CreateUser` are recognised.
- Fully qualified annotations, path constants, multi-path mappings and class-level `@RequestMapping` inherited from a parent type.
- DTOs declared in other project files, Java records, Kotlin data classes, inheritance, `@JsonProperty` / `@SerialName` names and enum fields.
- `@RequestBody` (JSON, XML or plain text depending on `consumes`), `@RequestPart` / `MultipartFile` (form-data), `application/x-www-form-urlencoded`, `@ModelAttribute` and unannotated parameters.
- Path variables stay as `{{id}}` placeholders and are added to the Spring environment with sample values.
- Re-running generation updates the existing collection instead of duplicating it, and never overwrites variables you edited by hand.
- `application-dev.yml`-style profile files and `server.ssl.enabled: true` are respected.

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
