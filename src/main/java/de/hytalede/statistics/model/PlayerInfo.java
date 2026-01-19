package de.hytalede.statistics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Optional player entry for telemetry when {@code sendPlayerList} is enabled.
 *
 * <p>{@code joined} should be an ISO-8601 UTC timestamp string, e.g. {@code 2026-01-19T13:45:00Z}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerInfo(
        @JsonProperty("uuid") String uuid,
        @JsonProperty("name") String name,
        @JsonProperty("joined") String joined
) {
    public PlayerInfo {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}

