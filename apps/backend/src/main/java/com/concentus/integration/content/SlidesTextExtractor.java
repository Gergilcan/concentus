package com.concentus.integration.content;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PowerPoint decks (.pptx), via POI.
 *
 * <p>A deck was already recognised — {@link DetectedType#PPTX} has existed since attachments were
 * sniffed — and then reported as unreadable, which is the worst of both: the file is named, its
 * type is known, and nothing comes out. Decks are where a surprising amount of company knowledge
 * actually lives: the onboarding deck, the quarterly review, the one slide that explains the
 * pricing tiers.
 *
 * <p><b>Slides are numbered in the text.</b> "Slide 4" is how a person refers to a place in a
 * deck, and a retrieved passage that says so can be checked in seconds; without it the reader has
 * a paragraph and a filename and has to hunt. It costs one line per slide.
 *
 * <p><b>Speaker notes are included.</b> They are frequently where the sentence explaining the
 * slide lives — the slide itself being four words and a diagram — and a deck extracted without
 * them often yields chunks too sparse to retrieve on at all.
 *
 * <p>Tables come out pipe-delimited, matching {@link WordTextExtractor} and {@link HtmlToText}, so
 * a model sees one shape whatever the source document was. Only the OOXML format: the older binary
 * {@code .ppt} needs poi-scratchpad, and is detected as {@link DetectedType#LEGACY_OFFICE} and
 * reported as unread rather than mis-parsed.
 */
@Component
public class SlidesTextExtractor implements AttachmentTextExtractor {

    private static final int MAX_CHARS = 150_000;

    @Override
    public String id() {
        return "pptx";
    }

    @Override
    public boolean supports(DetectedType type) {
        return type == DetectedType.PPTX;
    }

    @Override
    public String extract(byte[] bytes, String filename) throws Exception {
        StringBuilder out = new StringBuilder();
        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int number = 0;
            for (XSLFSlide slide : deck.getSlides()) {
                number++;
                StringBuilder body = new StringBuilder();
                appendShapes(slide.getShapes(), body);

                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder spoken = new StringBuilder();
                    appendShapes(notes.getShapes(), spoken);
                    if (!spoken.isEmpty()) {
                        body.append("Notes: ").append(spoken.toString().strip()).append('\n');
                    }
                }

                if (body.isEmpty()) continue;
                // A blank line between slides so the chunker's paragraph split lands on slide
                // boundaries — a chunk that stops mid-deck reads as two half-thoughts.
                out.append("\n\nSlide ").append(number);
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) out.append(" — ").append(title.strip());
                out.append('\n').append(body);
            }
        }
        String text = out.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return ExtractedText.clamp(text, MAX_CHARS);
    }

    /** Text shapes and tables, in the order the deck holds them. Recurses into grouped shapes. */
    private static void appendShapes(List<XSLFShape> shapes, StringBuilder out) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTable table) {
                for (XSLFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XSLFTableCell cell : row.getCells()) {
                        String value = cell.getText();
                        cells.add(value == null ? "" : value.replace("|", "/").strip());
                    }
                    out.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
            } else if (shape instanceof org.apache.poi.xslf.usermodel.XSLFGroupShape group) {
                appendShapes(group.getShapes(), out);
            } else if (shape instanceof XSLFTextShape text) {
                String value = text.getText();
                if (value != null && !value.isBlank()) out.append(value.strip()).append('\n');
            }
        }
    }
}
