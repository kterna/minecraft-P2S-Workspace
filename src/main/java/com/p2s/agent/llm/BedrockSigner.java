package com.p2s.agent.llm;

import okhttp3.HttpUrl;
import okhttp3.Request;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** AWS Signature Version 4 for Bedrock Runtime POST requests. */
final class BedrockSigner {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    static void sign(Request.Builder request, HttpUrl url, byte[] payload, String region, String key, String secret,
                     String sessionToken, Instant time) {
        String timestamp = STAMP.format(time);
        String date = timestamp.substring(0, 8);
        String scope = date + "/" + region + "/bedrock/aws4_request";
        String host = url.host().contains(":") ? "[" + url.host() + "]" : url.host();
        if (url.port() != HttpUrl.defaultPort(url.scheme())) host += ":" + url.port();
        Map<String, String> signed = new TreeMap<>();
        signed.put("host", host);
        signed.put("x-amz-date", timestamp);
        if (!sessionToken.isBlank()) signed.put("x-amz-security-token", sessionToken);
        for (Map.Entry<String, String> entry : signed.entrySet()) request.header(entry.getKey(), entry.getValue());
        List<String> query = new ArrayList<>();
        for (int i = 0; i < url.querySize(); i++) {
            String value = url.queryParameterValue(i);
            query.add(encode(url.queryParameterName(i), false) + "=" + encode(value == null ? "" : value, false));
        }
        Collections.sort(query);
        String headerNames = String.join(";", signed.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value.trim()).append('\n'));
        try {
            String canonical = "POST\n" + encode(url.encodedPath(), true) + "\n" + String.join("&", query) + "\n"
                    + canonicalHeaders + "\n" + headerNames + "\n" + sha256(payload);
            String toSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] dateKey = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
            byte[] signingKey = hmac(hmac(hmac(dateKey, region), "bedrock"), "aws4_request");
            request.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + key + "/" + scope + ", SignedHeaders="
                    + headerNames + ", Signature=" + HEX.formatHex(hmac(signingKey, toSign)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign Bedrock request", ex);
        }
    }

    private static String sha256(byte[] value) throws GeneralSecurityException {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value, boolean keepSlash) {
        StringBuilder out = new StringBuilder();
        for (byte signed : value.getBytes(StandardCharsets.UTF_8)) {
            int b = Byte.toUnsignedInt(signed);
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') ||
                    "-_.~".indexOf(b) >= 0 || (keepSlash && b == '/')) out.append((char)b);
            else out.append('%').append("0123456789ABCDEF".charAt(b >>> 4)).append("0123456789ABCDEF".charAt(b & 15));
        }
        return out.toString();
    }
}
