package de.hytalede.statistics.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class HttpIo {
    private HttpIo() {
    }

    public record LimitedText(String text, boolean truncated) {
    }

    /**
     * Reads a UTF-8 stream up to {@code maxBytes} bytes.
     *
     * <p>The limit is enforced on raw bytes (not code points) to guarantee bounded memory.
     */
    public static LimitedText readUtf8Limited(InputStream inputStream, int maxBytes) throws IOException {
        if (inputStream == null) {
            return new LimitedText(null, false);
        }
        if (maxBytes <= 0) {
            try (inputStream) {
                // drain nothing
            }
            return new LimitedText("", false);
        }

        byte[] buffer = new byte[1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        boolean truncated = false;

        try (inputStream) {
            while (true) {
                int remaining = maxBytes - out.size();
                if (remaining <= 0) {
                    // Try to detect whether more data exists.
                    truncated = inputStream.read() != -1;
                    break;
                }

                int read = inputStream.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) {
                    break;
                }
                out.write(buffer, 0, read);
            }
        }

        String text = out.toString(StandardCharsets.UTF_8);
        return new LimitedText(text, truncated);
    }
}
