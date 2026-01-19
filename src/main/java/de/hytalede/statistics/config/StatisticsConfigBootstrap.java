package de.hytalede.statistics.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates a default statistics config if it doesn't exist yet.
 */
public final class StatisticsConfigBootstrap {
    private static final String DEFAULT_CONFIG_RESOURCE = "/statistics.json";

    private StatisticsConfigBootstrap() {
    }

    /**
     * Ensures {@code configPath} exists. If missing, copies the default template from the classpath.
     *
     * @return true if the file exists after this method (either already existed or was created), false otherwise
     */
    public static boolean ensureExists(Path configPath, Logger logger) {
        if (configPath == null) {
            throw new IllegalArgumentException("configPath must not be null");
        }
        Logger log = logger == null ? Logger.getLogger(StatisticsConfigBootstrap.class.getName()) : logger;

        if (Files.exists(configPath)) {
            return true;
        }

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (InputStream in = StatisticsConfigBootstrap.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
                if (in == null) {
                    log.severe("Default config resource missing: " + DEFAULT_CONFIG_RESOURCE
                            + " (cannot create " + configPath.toAbsolutePath() + ")");
                    return false;
                }
                Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Created default statistics config: " + configPath.toAbsolutePath());
            log.info("Please open the file and fill in endpoint, bearerToken and vanityUrl before starting.");
            return true;
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Failed to create statistics config at " + configPath.toAbsolutePath(), ex);
            return false;
        }
    }
}

