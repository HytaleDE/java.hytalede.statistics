package de.hytalede.statistics.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link StatisticsConfig} instances from JSON files.
 *
 * Expected JSON shape:
 * <pre>
 * {
 *   "endpoint": "https://example.com/api/v1/",
 *   "bearerToken": "REPLACE_WITH_TOKEN",
 *   "vanityUrl": "myserver123",
 *   "sendPlayerList": false,
 *   "sendPluginList": false
 * }
 * </pre>
 */
public final class JsonStatisticsConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final Path path;

    public JsonStatisticsConfigLoader(Path path) {
        this.path = path;
    }

    public StatisticsConfig load() throws IOException {
        if (Files.notExists(path)) {
            throw new IOException("Missing statistics config: " + path.toAbsolutePath());
        }
        RawStatisticsConfig raw = MAPPER.readValue(path.toFile(), RawStatisticsConfig.class);
        if (raw == null) {
            throw new IOException("Statistics config is empty: " + path.toAbsolutePath());
        }
        return raw.toDomain();
    }

    /**
     * Mutable POJO to satisfy Jackson.
     */
    public static final class RawStatisticsConfig {
        private String endpoint;
        // Backwards compatibility: previously pingEndpoint was configurable. It is now derived
        // from endpoint (base API URL) + "ping".
        @SuppressWarnings("unused")
        private String pingEndpoint;
        private String bearerToken;
        private String vanityUrl;
        private Boolean sendPlayerList;
        private Boolean sendPluginList;
        // Backwards compatibility: timeouts used to be configurable. They are now hardcoded.
        @SuppressWarnings("unused")
        private Object timeouts;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPingEndpoint() {
            return pingEndpoint;
        }

        public void setPingEndpoint(String pingEndpoint) {
            this.pingEndpoint = pingEndpoint;
        }

        public String getBearerToken() {
            return bearerToken;
        }

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
        }

        public String getVanityUrl() {
            return vanityUrl;
        }

        public void setVanityUrl(String vanityUrl) {
            this.vanityUrl = vanityUrl;
        }

        public Boolean getSendPlayerList() {
            return sendPlayerList;
        }

        public void setSendPlayerList(Boolean sendPlayerList) {
            this.sendPlayerList = sendPlayerList;
        }

        public Boolean getSendPluginList() {
            return sendPluginList;
        }

        public void setSendPluginList(Boolean sendPluginList) {
            this.sendPluginList = sendPluginList;
        }

        public Object getTimeouts() {
            return timeouts;
        }

        public void setTimeouts(Object timeouts) {
            this.timeouts = timeouts;
        }

        StatisticsConfig toDomain() {
            URI endpointUri = URI.create(requireNonBlank(endpoint, "endpoint"));
            String token = requireNonBlank(bearerToken, "bearerToken");
            String vanity = requireNonBlank(vanityUrl, "vanityUrl");
            boolean players = sendPlayerList != null && sendPlayerList;
            boolean plugins = sendPluginList != null && sendPluginList;
            return new StatisticsConfig(endpointUri, token, vanity, players, plugins);
        }

        private static String requireNonBlank(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must be present in statistics config");
            }
            return value.trim();
        }
    }
}

