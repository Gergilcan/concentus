package com.concentus.integration.content;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Plain text and CSV. Decoded as UTF-8, replacing anything undecodable rather than throwing. */
@Component
public class PlainTextExtractor implements AttachmentTextExtractor {

    private static final int MAX_CHARS = 100_000;

    @Override
    public String id() {
        return "text";
    }

    @Override
    public boolean supports(DetectedType type) {
        return type == DetectedType.TEXT || type == DetectedType.CSV;
    }

    @Override
    public String extract(byte[] bytes, String filename) {
        // Lenient decoding on purpose: a CSV exported from a Spanish accounting package is often
        // Latin-1, and refusing to read it would send an otherwise-valid quote to review.
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('�') >= 0) {
            text = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n…(truncated)" : text;
    }
}
