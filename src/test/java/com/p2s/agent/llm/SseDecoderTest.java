package com.p2s.agent.llm;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SseDecoderTest {
    private static List<String> decode(String body) throws Exception {
        List<String> events = new ArrayList<>();
        SseDecoder.decode(new StringReader(body), (event, data) -> {
            events.add(event + "|" + data);
            return true;
        });
        return events;
    }

    @Test
    void handlesCommentsMultiLineDataAndOptionalSpace() throws Exception {
        String body = ": keep-alive\n"
                + "data:{\"a\":1}\n\n"
                + "event: delta\n"
                + "data: line one\n"
                + "data: line two\n\n";
        assertEquals(List.of("|{\"a\":1}", "delta|line one\nline two"), decode(body));
    }

    @Test
    void acceptsCrLfAndBareCrLineEnds() throws Exception {
        assertEquals(List.of("|x", "|y"), decode("data: x\r\n\r\ndata: y\r\r"));
    }

    @Test
    void dispatchesTrailingEventWithoutBlankLine() throws Exception {
        assertEquals(List.of("|[DONE]"), decode("data: [DONE]"));
    }

    @Test
    void handlerCanStopReading() throws Exception {
        List<String> seen = new ArrayList<>();
        SseDecoder.decode(new StringReader("data: 1\n\ndata: 2\n\n"), (event, data) -> {
            seen.add(data);
            return false;
        });
        assertEquals(List.of("1"), seen);
    }
}
