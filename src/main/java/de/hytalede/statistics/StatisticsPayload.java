package de.hytalede.statistics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * JSON payload that will be POSTed to the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatisticsPayload(
        @JsonProperty("vanityUrl") String vanityUrl,
        @JsonProperty("capturedAt") String capturedAt,
        @JsonProperty("source") String source,
        @JsonProperty("playersOnline") Integer playersOnline,
        @JsonProperty("maxPlayers") Integer maxPlayers,
        @JsonProperty("uptimePercent") Double uptimePercent,
        @JsonProperty("latencyMs") Long latencyMs,
        @JsonProperty("voteTotal") Integer voteTotal,
        @JsonProperty("votesDelta") Integer votesDelta,
        @JsonProperty("rank") Integer rank
) {
    public StatisticsPayload {
        Objects.requireNonNull(vanityUrl, "vanityUrl");
        vanityUrl = vanityUrl.trim().toLowerCase();
        if (!vanityUrl.matches("^[a-z0-9]{3,32}$")) {
            throw new IllegalArgumentException("vanityUrl must match ^[a-z0-9]{3,32}$");
        }

        if (playersOnline != null && playersOnline < 0) {
            throw new IllegalArgumentException("playersOnline must be >= 0");
        }
        if (maxPlayers != null && maxPlayers < 0) {
            throw new IllegalArgumentException("maxPlayers must be >= 0");
        }
        if (latencyMs != null && latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be >= 0");
        }
        if (uptimePercent != null && (uptimePercent < 0 || uptimePercent > 100)) {
            throw new IllegalArgumentException("uptimePercent must be between 0 and 100");
        }
    }
}
