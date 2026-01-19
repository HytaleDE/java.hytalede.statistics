# HytaleDE Statistics Plugin

Serverside helper that periodically POSTs the active server state to a remote HTTPS endpoint by reading a simple JSON config. The request body matches the required schema and always includes a bearer token for authentication.

## Features
- Java 25 codebase (recommended: Eclipse Temurin 25) with Maven build.
- JSON-driven configuration (`config/statistics.json`).
- Scheduled HTTPS POST every 5 minutes (configurable, never stops when a call fails).
- Graceful logging whenever the endpoint cannot be reached.
- Pluggable `ServerMetricsProvider` so you can wire real Hytale server data without touching the HTTP layer.

## Getting Started
1. **Install dependencies** – Java 25 is required (recommended: Eclipse Temurin 25). Use the included Maven wrapper (`./mvnw` / `mvnw.cmd`) so you don't need Maven installed globally.
   - Make sure `java -version` shows Java 25 (or set `JAVA_HOME` to your Temurin 25 installation).
2. **Konfigurieren** – kopiere `config/statistics.json` und trage deine Basis-API-URL ein (z.B. `https://api.hytl.de/api/v1/`), deinen Token (ohne `Bearer `) und deine `vanityUrl`. POST geht an `endpoint + "server-api/telemetry"`, Ping an `endpoint + "ping"`. Timeouts und Intervall sind hardcoded.
3. **Provide metrics** – implement `HytaleServerAdapter` (or a custom `ServerMetricsProvider`) so the reporter knows how to read live player counts, slot limits, version, and enabled plugins from your server runtime.
4. **Bootstrap the plugin** – instantiate `StatisticsPlugin` with the config path and your adapter, then call `start()` during server startup. Remember to `close()` it when the server stops.

### Quickstart (clone + build)
After cloning, you can build the project via:

```
./mvnw -B test
./mvnw -B package
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
./mvnw -B clean test
```

Artifacts are generated under `target/` (JAR + sources JAR).

### Run (standalone server runner)
The produced JAR has a `Main-Class` and can be started directly:

```
java -jar target/hytalede-statistics-plugin-0.1.0.jar config/statistics.local.json
```


### Run continuously (scheduler, keep process running)
```
./mvnw -q exec:java@server -Dexec.args="config/statistics.local.json"
```

## Maven Coordinates / "Maven URL"
The project currently builds a local JAR via Maven.

If you want to consume it as a dependency **without publishing** yet:

1) Install into your local Maven repo:

```
./mvnw -B install
```

2) Use these coordinates:
- `groupId`: `de.hytalede`
- `artifactId`: `hytalede-statistics-plugin`
- `version`: `0.1.0`

If you publish via **GitHub Packages**, the Maven repository URL typically looks like:
- `https://maven.pkg.github.com/HytaleDE/java.hytalede.statistics`

(Replace org/repo if yours differs.)

## Payload Structure
```json
{
  "vanityUrl": "myserver123",
  "playersOnline": 0,
  "maxPlayers": 0,
  "latencyMs": 0
}
```

The scheduler always keeps running even if previous attempts fail; failures only log a warning when the API host cannot be reached.
