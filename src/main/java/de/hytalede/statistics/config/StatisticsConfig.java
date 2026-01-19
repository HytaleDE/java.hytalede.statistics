package de.hytalede.statistics.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Strongly typed statistics configuration.
 */
public record StatisticsConfig(
        URI endpoint,
        String bearerToken,
        String vanityUrl
) {
    public static final Duration FIXED_INTERVAL = Duration.ofMinutes(5);
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(20); // 15s base + 5s safety buffer

    public StatisticsConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(vanityUrl, "vanityUrl");

        endpoint = normalizeBaseApiEndpoint(endpoint);

        if (endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute URL (e.g. https://hyrp.de/api/v1/)");
        }

        String path = endpoint.getPath();
        if (path == null || !path.contains("/api/v1/")) {
            throw new IllegalArgumentException("endpoint must include /api/v1/ (e.g. https://hyrp.de/api/v1/)");
        }

        if (bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken must not be blank");
        }

        vanityUrl = vanityUrl.trim().toLowerCase();
        if (!vanityUrl.matches("^[a-z0-9]{3,32}$")) {
            throw new IllegalArgumentException("vanityUrl must match ^[a-z0-9]{3,32}$");
        }
    }

    public Duration interval() {
        Duration override = intervalOverride();
        return override != null ? override : FIXED_INTERVAL;
    }

    public Duration connectTimeout() {
        return CONNECT_TIMEOUT;
    }

    public Duration readTimeout() {
        return READ_TIMEOUT;
    }

    public URI telemetryEndpoint() {
        return endpoint.resolve("server-api/telemetry");
    }

    public URI pingEndpoint() {
        return endpoint.resolve("ping");
    }

    private static URI normalizeBaseApiEndpoint(URI endpoint) {
        String value = endpoint.toString().trim();
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        return URI.create(value);
    }

    private static Duration intervalOverride() {
        String secondsRaw = System.getProperty("statistics.intervalSeconds");
        if (secondsRaw == null || secondsRaw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(secondsRaw.trim());
            if (seconds < 1) {
                throw new IllegalArgumentException("statistics.intervalSeconds must be >= 1");
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("statistics.intervalSeconds must be a whole number (seconds)", ex);
        }
    }
}
