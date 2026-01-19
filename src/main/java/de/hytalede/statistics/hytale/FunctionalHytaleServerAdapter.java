package de.hytalede.statistics.hytale;

import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A lightweight {@link HytaleServerAdapter} whose values are provided via method references or lambdas.
 * This allows the plugin host to wire the real Hytale server API without introducing a hard dependency
 * on that API inside this module.
 */
public final class FunctionalHytaleServerAdapter implements HytaleServerAdapter {
    private final IntSupplier onlinePlayers;
    private final IntSupplier maxPlayers;
    private final Supplier<String> version;
    private final Supplier<List<String>> plugins;

    private FunctionalHytaleServerAdapter(Builder builder) {
        this.onlinePlayers = builder.onlinePlayers;
        this.maxPlayers = builder.maxPlayers;
        this.version = builder.version;
        this.plugins = builder.plugins;
    }

    @Override
    public int getOnlinePlayerCount() {
        return onlinePlayers.getAsInt();
    }

    @Override
    public int getMaxPlayers() {
        return maxPlayers.getAsInt();
    }

    @Override
    public String getServerVersion() {
        return version.get();
    }

    @Override
    public List<String> getEnabledPlugins() {
        return plugins.get();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private IntSupplier onlinePlayers;
        private IntSupplier maxPlayers;
        private Supplier<String> version = () -> "unknown";
        private Supplier<List<String>> plugins = List::of;

        private Builder() {
        }

        public Builder onlinePlayers(IntSupplier supplier) {
            this.onlinePlayers = Objects.requireNonNull(supplier, "onlinePlayers");
            return this;
        }

        public Builder maxPlayers(IntSupplier supplier) {
            this.maxPlayers = Objects.requireNonNull(supplier, "maxPlayers");
            return this;
        }

        public Builder version(Supplier<String> supplier) {
            this.version = Objects.requireNonNull(supplier, "version");
            return this;
        }

        public Builder plugins(Supplier<List<String>> supplier) {
            this.plugins = Objects.requireNonNull(supplier, "plugins");
            return this;
        }

        public FunctionalHytaleServerAdapter build() {
            if (onlinePlayers == null) {
                throw new IllegalStateException("onlinePlayers supplier must be provided");
            }
            if (maxPlayers == null) {
                throw new IllegalStateException("maxPlayers supplier must be provided");
            }
            return new FunctionalHytaleServerAdapter(this);
        }
    }
}
