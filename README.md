# HytaleDE Statistics Plugin

Serverside helper that periodically POSTs the active server state to a remote HTTPS endpoint by reading a simple JSON config. The request body matches the required schema and always includes a bearer token for authentication.

## Features
- Java 25 codebase (recommended: Eclipse Temurin 25) with Maven build.
- JSON-driven configuration (`config/statistics.json`).
- Scheduled HTTPS POST every 5 minutes (configurable, never stops when a call fails).
- Graceful logging whenever the endpoint cannot be reached.
- Pluggable `ServerMetricsProvider` so you can wire real Hytale server data without touching the HTTP layer.

## Hytale server installation

For Hytale Server 0.7.7, download `hytalede-statistics-plugin-1.0.2.jar` from [GitHub Releases](https://github.com/HytaleDE/java.hytalede.statistics/releases) and place it in the server's `mods/` directory. Start the server once to create the plugin configuration, then set `endpoint`, `bearerToken`, and `vanityUrl` in `mods/HytaleDE_Statistics/statistics.json` and restart.

To build the same plugin locally, use `bash ./mvnw -B -Pwith-hytale clean verify` on Linux/macOS or `mvnw.cmd -B -Pwith-hytale clean verify` on Windows. Maven downloads the server API from the official Hytale repository.

## Standalone getting started
1. **Install dependencies** – Java 25 is required (recommended: Eclipse Temurin 25). Use the included Maven wrapper (`bash ./mvnw` / `mvnw.cmd`) so you don't need Maven installed globally.
   - Make sure `java -version` shows Java 25 (or set `JAVA_HOME` to your Temurin 25 installation).
2. **Konfigurieren** – kopiere `config/statistics.json` und trage deine Basis-API-URL ein (z.B. `https://api.hytl.de/api/v1/`), deinen Token (ohne `Bearer `) und deine `vanityUrl`. POST geht an `endpoint + "server-api/telemetry"`, Ping an `endpoint + "ping"`. Timeouts und Intervall sind hardcoded.
3. **Provide metrics** – implement `HytaleServerAdapter` (or a custom `ServerMetricsProvider`) so the reporter knows how to read live player counts, slot limits, version, and enabled plugins from your server runtime.
4. **Bootstrap the plugin** – instantiate `StatisticsPlugin` with the config path and your adapter, then call `start()` during server startup. Remember to `close()` it when the server stops.

### Quickstart (clone + build)
After cloning, you can build the project via:

```
bash ./mvnw -B test
bash ./mvnw -B package
```

## Configuration Reference
| Key | Description |
| --- | --- |
| `endpoint` | Basis-API-URL (z.B. `https://api.hytl.de/api/v1/`). Telemetry: `endpoint + "server-api/telemetry"`, Ping: `endpoint + "ping"`. |
| `bearerToken` | Token (ohne `Bearer `). Den Token bekommst du hier: `https://hytalecommunity.de/serverliste/meineserver/` |
| `vanityUrl` | Teil nach `/server/` (Beispiel: `https://hytalecommunity.de/server/<vanityUrl>`). Erlaubt: `a-z0-9`, Länge 3–32. Wird `trim().toLowerCase()` gesendet. |

## Build + Run (no IDE required)
### Build
```
bash ./mvnw -B clean verify
```

Artifacts are generated under `target/` (JAR + sources JAR).

### Run (standalone server runner)
The produced JAR has a `Main-Class` and can be started directly:

```
java -jar target/hytalede-statistics-plugin-standalone-1.0.2.jar config/statistics.local.json
```


### Run continuously (scheduler, keep process running)
```
bash ./mvnw -q exec:java@server -Dexec.args="config/statistics.local.json"
```

## Payload Structure
```json
{
  "vanityUrl": "myserver123",
  "version": "1.0.0",
  "playersOnline": 0,
  "maxPlayers": 0,
  "latencyMs": 0
}
```

Optional (enabled via config):
- `sendPlayerList`: sends `"players": [{"uuid": "...", "name": "...", "joined": "2026-01-19T13:45:00Z"}]`
- `sendPluginList`: sends `"plugins": [{"name": "ExamplePlugin", "version": "1.2.3"}]`

The scheduler always keeps running even if previous attempts fail; failures only log a warning when the API host cannot be reached.
