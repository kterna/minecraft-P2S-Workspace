package com.p2s.agent.llm;

import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Amazon event stream frames: length, headers length, prelude CRC32, headers, payload, CRC32. */
final class BedrockEventStream {
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    record Event(String type, JsonObject payload, boolean error) {}

    static Event read(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        byte[] prelude = input.readNBytes(12);
        if (prelude.length == 0) return null;
        if (prelude.length != 12) throw new EOFException("Truncated AWS event prelude");
        ByteBuffer lengths = ByteBuffer.wrap(prelude);
        int length = lengths.getInt();
        int headerBytes = lengths.getInt();
        CRC32 crc = new CRC32();
        crc.update(prelude, 0, 8);
        if (lengths.getInt() != (int) crc.getValue() || length < 16 || length > MAX_FRAME
                || headerBytes < 0 || headerBytes > Math.min(length - 16, 128 * 1024)) {
            throw new IOException("Invalid AWS event prelude");
        }
        byte[] content = input.readNBytes(length - 16);
        if (content.length != length - 16) throw new EOFException("Truncated AWS event");
        crc.update(prelude, 8, 4);
        crc.update(content);
        if (input.readInt() != (int) crc.getValue()) throw new IOException("AWS event checksum mismatch");

        ByteBuffer headers = ByteBuffer.wrap(content, 0, headerBytes);
        Map<String, String> fields = new HashMap<>();
        while (headers.hasRemaining()) {
            String name = string(headers, Byte.toUnsignedInt(headers.get()));
            int type = Byte.toUnsignedInt(headers.get());
            if (type == 7) {
                fields.put(name, string(headers, Short.toUnsignedInt(headers.getShort())));
            } else {
                int bytes = switch (type) {
                    case 0, 1 -> 0;
                    case 2 -> 1;
                    case 3 -> 2;
                    case 4 -> 4;
                    case 5, 8 -> 8;
                    case 6 -> Short.toUnsignedInt(headers.getShort());
                    case 9 -> 16;
                    default -> throw new IOException("Unknown AWS event header type " + type);
                };
                if (bytes > headers.remaining()) throw new IOException("Truncated AWS event header");
                headers.position(headers.position() + bytes);
            }
        }
        String type = fields.getOrDefault(":event-type", fields.getOrDefault(":exception-type", fields.getOrDefault(":error-code", "")));
        boolean error = !"event".equals(fields.get(":message-type")) || type.endsWith("Exception");
        String text = new String(content, headerBytes, content.length - headerBytes, StandardCharsets.UTF_8);
        JsonObject payload = Json.parseObject(text);
        if (payload == null) throw new IOException("Invalid AWS event JSON: " + type);
        return new Event(type, payload, error);
    }

    private static String string(ByteBuffer buffer, int length) throws IOException {
        if (length > buffer.remaining()) throw new IOException("Truncated AWS event header");
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
