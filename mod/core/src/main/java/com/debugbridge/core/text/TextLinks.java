package com.debugbridge.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a plain message into text and URL segments so the version modules can
 * render URLs as clickable chat components (the startup "Web UI:
 * http://localhost:NNNN" message in particular). Pure string work — the
 * version-specific {@code ClickEvent} styling stays in each module, the
 * detection lives here once.
 */
public final class TextLinks {

    /** One run of the message: either plain text or a bare http(s) URL. */
    public record Segment(String text, boolean isLink) {}

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** Punctuation that ends a sentence around a URL rather than belonging to it. */
    private static final String TRAILING = ".,;:!?)]\"'";

    private TextLinks() {}

    /**
     * Splits {@code message} into ordered segments that concatenate back to the
     * original string. Trailing sentence punctuation after a URL is returned as
     * text, not as part of the link.
     */
    public static List<Segment> split(String message) {
        List<Segment> out = new ArrayList<>();
        Matcher m = URL.matcher(message);
        int last = 0;
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            while (end > start && TRAILING.indexOf(message.charAt(end - 1)) >= 0) {
                end--;
            }
            if (start > last) {
                out.add(new Segment(message.substring(last, start), false));
            }
            out.add(new Segment(message.substring(start, end), true));
            last = end;
        }
        if (last < message.length()) {
            out.add(new Segment(message.substring(last), false));
        }
        return out;
    }
}
