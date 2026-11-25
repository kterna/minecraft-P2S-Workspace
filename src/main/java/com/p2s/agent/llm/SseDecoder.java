package com.p2s.agent.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Server-Sent Events framing per the WHATWG event-stream rules: LF, CR or CRLF line ends,
 * comment lines, multi-line {@code data} fields, optional space after the colon, and
 * dispatch on a blank line. A trailing event without a final blank line is still
 * dispatched, because several OpenAI-compatible gateways close the stream that way.
 */
public final class SseDecoder {
    private SseDecoder() {
    }

    /** Called per event; return false to stop reading. */
    @FunctionalInterface
    public interface Handler {
        boolean onEvent(String event, String data) throws IOException;
    }

    public static void decode(Reader source, Handler handler) throws IOException {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        StringBuilder data = new StringBuilder();
        String event = "";
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) {
                    if (!handler.onEvent(event, data.toString())) {
                        return;
                    }
                }
                data.setLength(0);
                event = "";
                hasData = false;
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "data" -> {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                }
                case "event" -> event = value;
                default -> {
                    // id / retry and unknown fields do not affect the payload.
                }
            }
        }
        if (hasData) {
            handler.onEvent(event, data.toString());
        }
    }
}
