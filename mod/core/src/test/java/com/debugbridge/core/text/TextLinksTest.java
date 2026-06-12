package com.debugbridge.core.text;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.text.TextLinks.Segment;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextLinksTest {

    @Test
    void plainMessageIsOneTextSegment() {
        assertEquals(
                List.of(new Segment("Server started on port 9877", false)),
                TextLinks.split("Server started on port 9877"));
    }

    @Test
    void startupWebUiMessageSplitsIntoTextAndLink() {
        assertEquals(
                List.of(new Segment("Web UI: ", false), new Segment("http://localhost:9976", true)),
                TextLinks.split("Web UI: http://localhost:9976"));
    }

    @Test
    void trailingSentencePunctuationStaysOutOfTheLink() {
        assertEquals(
                List.of(
                        new Segment("see ", false),
                        new Segment("https://example.com/a", true),
                        new Segment(".", false)),
                TextLinks.split("see https://example.com/a."));
    }

    @Test
    void multipleLinksKeepInterleavedText() {
        assertEquals(
                List.of(
                        new Segment("a ", false),
                        new Segment("http://x:1", true),
                        new Segment(" b ", false),
                        new Segment("https://y:2", true),
                        new Segment(" c", false)),
                TextLinks.split("a http://x:1 b https://y:2 c"));
    }

    @Test
    void segmentsConcatenateBackToTheOriginal() {
        String msg = "Server started on port 9877 (default 9876 was in use) — Web UI: http://localhost:9977";
        StringBuilder rejoined = new StringBuilder();
        TextLinks.split(msg).forEach(s -> rejoined.append(s.text()));
        assertEquals(msg, rejoined.toString());
    }
}
