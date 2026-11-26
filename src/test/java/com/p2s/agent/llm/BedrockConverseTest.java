package com.p2s.agent.llm;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class BedrockConverseTest {
    private final LlmRequestConfig cfg = ProviderTransportTest.config(LlmApi.BEDROCK_CONVERSE);
    private final HttpTransport transport = LlmTransports.create(cfg);

    @Test
    void sigV4UsesScopeCanonicalQueryAndSessionToken() {
        var url = HttpUrl.get("https://bedrock-runtime.us-east-1.amazonaws.com/model/example%2F1/converse?z=2&a=1");
        Request.Builder request = new Request.Builder().url(url);
        BedrockSigner.sign(request, url, "{}".getBytes(StandardCharsets.UTF_8), "us-east-1", "EXAMPLE", "synthetic-secret",
                "synthetic-token", Instant.parse("2026-01-02T03:04:05Z"));
        Request signed = request.build();
        assertEquals("20260102T030405Z", signed.header("x-amz-date"));
        assertEquals("synthetic-token", signed.header("x-amz-security-token"));
        assertEquals("AWS4-HMAC-SHA256 Credential=EXAMPLE/20260102/us-east-1/bedrock/aws4_request, "
                + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=8882602809d960a19a47e24fdf33a9ba8ddbd515bafcd5d74f12b0cb39153495", signed.header("Authorization"));
    }

    @Test
    void requestUsesBedrockToolSchemaAndParsesFullReply() {
        var http = transport.prepareWithExtras(ProviderTransportTest.CONTEXT, cfg);
        assertEquals("https://example.invalid/v1/model/demo/converse-stream", http.url());
        assertEquals(Json.parseObject("""
                {"messages":[{"role":"user","content":[{"text":"hi"}]}],"system":[{"text":"sys"}],
                 "toolConfig":{"tools":[{"toolSpec":{"name":"read","description":"Read a block","inputSchema":{"json":{"type":"object","properties":{}}}}}]}}
                """), http.body());
        var response = transport.parseResponse("""
                {"output":{"message":{"role":"assistant","content":[{"text":"hello"}]}},"stopReason":"end_turn","usage":{"inputTokens":8}}
                """, ignored -> {}, cfg);
        assertEquals("hello", response.text());
        assertEquals(8, response.usage().input());
    }

    @Test
    void binaryToolStreamRequiresCompletionAndRetainsSignedReasoning() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        frame(bytes, "contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"text\":\"plan\"}}}");
        frame(bytes, "contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"signature\":\"sealed\"}}}");
        frame(bytes, "contentBlockStop", "{\"contentBlockIndex\":0}");
        frame(bytes, "contentBlockStart", "{\"contentBlockIndex\":1,\"start\":{\"toolUse\":{\"toolUseId\":\"c\",\"name\":\"read\"}}}");
        frame(bytes, "contentBlockDelta", "{\"contentBlockIndex\":1,\"delta\":{\"toolUse\":{\"input\":\"{}\"}}}");
        frame(bytes, "contentBlockStop", "{\"contentBlockIndex\":1}");
        assertTrue(parse(bytes.toByteArray()).failed());
        frame(bytes, "messageStop", "{\"stopReason\":\"tool_use\"}");
        frame(bytes, "metadata", "{\"usage\":{\"inputTokens\":5,\"outputTokens\":2}}");
        var reply = parse(bytes.toByteArray());
        assertEquals(AgentMessage.StopReason.TOOL_USE, reply.stopReason());
        assertEquals("plan", reply.thinking());
        assertEquals(5, reply.usage().input());
        var result = new AgentMessage.ToolResult("c", "read", new JsonObject(), false, 0L);
        var saved = OpenAiMessageCodec.toWire("", List.of(AgentMessage.User.of("hi"), reply, result));
        var restored = OpenAiMessageCodec.fromWire(saved).messages();
        var context = new com.p2s.agent.core.StreamFunction.Context("", restored, ProviderTransportTest.CONTEXT.tools());
        String json = transport.requestBody(context, cfg).toString();
        assertTrue(json.contains("\"signature\":\"sealed\""));
        assertTrue(json.contains("\"toolResult\""));
        assertFalse(transport.requestBody(context, cfg.toBuilder().model("other").build()).toString().contains("sealed"));
        byte[] corrupt = bytes.toByteArray();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> parse(corrupt));
        assertThrows(IOException.class, () -> parse(Arrays.copyOf(corrupt, corrupt.length - 6)));
    }

    private AgentMessage.Assistant parse(byte[] data) throws IOException {
        return transport.parseStream(new ByteArrayInputStream(data), ignored -> {}, AbortSignal.none(), cfg);
    }

    private static void frame(ByteArrayOutputStream stream, String event, String data) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(headers);
        for (String[] field : List.of(new String[]{":message-type", "event"}, new String[]{":event-type", event})) {
            fields.writeByte(field[0].length());
            fields.writeBytes(field[0]);
            fields.writeByte(7);
            fields.writeShort(field[1].length());
            fields.writeBytes(field[1]);
        }
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(frame);
        output.writeInt(16 + headers.size() + payload.length);
        output.writeInt(headers.size());
        output.writeInt(crc(frame.toByteArray()));
        headers.writeTo(frame);
        output.write(payload);
        output.writeInt(crc(frame.toByteArray()));
        frame.writeTo(stream);
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }
}
