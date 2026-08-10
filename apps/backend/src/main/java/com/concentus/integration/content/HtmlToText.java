package com.concentus.integration.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an HTML mail body into plain text that keeps its structure.
 *
 * <p>Jsoup's own {@code text()} flattens everything to one line, which destroys exactly the
 * information a quote request carries: a price list is a table, and "3 units of X at 40€" only
 * survives if the row stays a row. So tables become pipe-delimited rows and lists become bulleted
 * lines, and the model is given something that still reads like the document the sender wrote.
 *
 * <p>Parsing also strips scripts, styles and comments. That is not for rendering safety — nothing
 * here is rendered — but because a hidden {@code <div style="display:none">} is a favourite place
 * to put instructions aimed at whatever machine reads the mail.
 */
public final class HtmlToText {

    /** Beyond this the text is truncated; a mail thread can be megabytes of quoted history. */
    private static final int MAX_LENGTH = 200_000;

    private HtmlToText() {
    }

    public static String convert(String html) {
        if (html == null || html.isBlank()) return "";
        // Strip to a text-only safelist first: this removes script, style, and every attribute,
        // including the ones used to hide content from a human reader but not from a parser.
        String cleaned = Jsoup.clean(html, Safelist.relaxed()
                .addTags("table", "thead", "tbody", "tr", "td", "th", "ul", "ol", "li", "br", "p"));
        Document document = Jsoup.parse(cleaned);
        document.select("script, style, head, noscript").remove();

        StringBuilder out = new StringBuilder();
        renderChildren(document.body(), out);
        String text = out.toString()
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return ExtractedText.clamp(text, MAX_LENGTH);
    }

    private static void renderChildren(Element parent, StringBuilder out) {
        if (parent == null) return;
        for (Element child : parent.children()) {
            render(child, out);
        }
        // A body whose content is loose text with no block elements still has to come through.
        if (parent.children().isEmpty()) {
            appendLine(out, parent.text());
        }
    }

    private static void render(Element element, StringBuilder out) {
        switch (element.tagName().toLowerCase(java.util.Locale.ROOT)) {
            case "table" -> renderTable(element, out);
            case "ul", "ol" -> renderList(element, out);
            case "br" -> out.append('\n');
            case "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote" -> {
                if (hasBlockChildren(element)) {
                    renderChildren(element, out);
                } else {
                    appendLine(out, element.text());
                }
            }
            default -> {
                if (hasBlockChildren(element)) {
                    renderChildren(element, out);
                } else {
                    String text = element.text();
                    if (!text.isBlank()) out.append(text).append(' ');
                }
            }
        }
    }

    /**
     * Renders a table as pipe-delimited rows with a separator under the header.
     *
     * <p>Chosen because it is the shape a language model reads most reliably as tabular data, and
     * because column alignment survives without needing padding that would waste tokens.
     */
    private static void renderTable(Element table, StringBuilder out) {
        out.append('\n');
        Elements rows = table.select("tr");
        boolean headerWritten = false;
        for (Element row : rows) {
            Elements cells = row.select("th, td");
            if (cells.isEmpty()) continue;
            List<String> values = new ArrayList<>();
            for (Element cell : cells) {
                values.add(cell.text().replace("|", "/").trim());
            }
            out.append("| ").append(String.join(" | ", values)).append(" |\n");
            if (!headerWritten && !row.select("th").isEmpty()) {
                out.append("|").append(" --- |".repeat(values.size())).append('\n');
                headerWritten = true;
            }
        }
        out.append('\n');
    }

    private static void renderList(Element list, StringBuilder out) {
        out.append('\n');
        boolean ordered = "ol".equalsIgnoreCase(list.tagName());
        int index = 1;
        for (Element item : list.select("> li")) {
            out.append(ordered ? (index++) + ". " : "- ").append(item.text().trim()).append('\n');
        }
        out.append('\n');
    }

    private static boolean hasBlockChildren(Element element) {
        return !element.select("> table, > ul, > ol, > p, > div, > blockquote").isEmpty();
    }

    private static void appendLine(StringBuilder out, String text) {
        if (text == null || text.isBlank()) return;
        out.append(text.trim()).append('\n');
    }
}
