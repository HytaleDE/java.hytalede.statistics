package de.hytalede.statistics.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Optional plugin entry for telemetry when {@code sendPluginList} is enabled.
 */
public record PluginInfo(
        @JsonProperty("name") String name,
        @JsonProperty("version") String version
) {
    public PluginInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }
}

