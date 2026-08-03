package com.concentus.integration.content;

import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OCR for image attachments and scanned PDFs, via Tesseract.
 *
 * <p>The one component here that depends on something outside the JVM. tess4j is a JNA binding to
 * the native {@code libtesseract} library and its language data, neither of which ships in the jar
 * — so this class is written to be <b>absent-safe</b>: the engine is created lazily, any failure to
 * load is recorded once, and {@link #isAvailable()} then reports false forever after. A deployment
 * without Tesseract installed starts normally and simply produces no OCR text, rather than failing
 * at boot or throwing on the first scanned invoice.
 *
 * <p>See {@code apps/backend/Dockerfile} for the packages that make it available, and
 * {@code integration.attachments.ocr-enabled} to switch it off.
 */
@Component
public class ImageOcrExtractor implements AttachmentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrExtractor.class);

    /** Scans are usually 200–300 DPI; rendering PDF pages at this reads reliably without ballooning. */
    private static final int RENDER_DPI = 300;
    private static final int MAX_CHARS = 100_000;

    private final boolean enabled;
    private final String dataPath;
    private final String languages;
    private final AtomicReference<Tesseract> engine = new AtomicReference<>();
    private volatile boolean unavailable;

    public ImageOcrExtractor(@Value("${integration.attachments.ocr-enabled:true}") boolean enabled,
                             @Value("${integration.attachments.ocr-data-path:}") String dataPath,
                             @Value("${integration.attachments.ocr-languages:spa+eng}") String languages) {
        this.enabled = enabled;
        this.dataPath = dataPath == null ? "" : dataPath.trim();
        this.languages = languages == null || languages.isBlank() ? "eng" : languages.trim();
    }

    @Override
    public String id() {
        return "ocr";
    }

    @Override
    public boolean supports(DetectedType type) {
        return type == DetectedType.IMAGE;
    }

    @Override
    public boolean isAvailable() {
        return enabled && !unavailable && engine() != null;
    }

    @Override
    public String extract(byte[] bytes, String filename) throws Exception {
        Tesseract tesseract = engine();
        if (tesseract == null) return "";
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            log.debug("Could not decode '{}' as an image.", filename);
            return "";
        }
        return truncate(runOcr(tesseract, image));
    }

    /** Renders and reads a scanned PDF's pages. Called by {@link PdfTextExtractor}. */
    public String extractFromPdf(PDDocument document, int maxPages) {
        Tesseract tesseract = engine();
        if (tesseract == null) return "";
        PDFRenderer renderer = new PDFRenderer(document);
        StringBuilder out = new StringBuilder();
        int pages = Math.min(document.getNumberOfPages(), Math.max(1, maxPages));
        for (int page = 0; page < pages; page++) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(page, RENDER_DPI, ImageType.GRAY);
                String text = runOcr(tesseract, image);
                if (!text.isBlank()) {
                    out.append("--- page ").append(page + 1).append(" ---\n").append(text).append('\n');
                }
            } catch (Exception e) {
                // One unreadable page must not lose the pages that did read.
                log.debug("OCR of page {} failed: {}", page + 1, e.getMessage());
            }
        }
        if (document.getNumberOfPages() > pages) {
            out.append("…(OCR stopped after ").append(pages).append(" of ")
               .append(document.getNumberOfPages()).append(" pages)\n");
        }
        return out.toString();
    }

    private String runOcr(Tesseract tesseract, BufferedImage image) {
        try {
            // Tesseract's native handle is not thread-safe, and several job workers can be
            // extracting at once; serialising on the instance is cheaper than a pool of engines.
            synchronized (this) {
                return tesseract.doOCR(image);
            }
        } catch (Throwable t) {
            // Throwable, not Exception: a missing native library surfaces as UnsatisfiedLinkError.
            markUnavailable(t);
            return "";
        }
    }

    /**
     * The engine, built on first use.
     *
     * <p>Lazily, because constructing it loads the native library — doing that in the constructor
     * would make an optional feature's missing dependency into a startup failure.
     */
    private Tesseract engine() {
        if (!enabled || unavailable) return null;
        Tesseract existing = engine.get();
        if (existing != null) return existing;
        synchronized (this) {
            if (unavailable) return null;
            existing = engine.get();
            if (existing != null) return existing;
            try {
                Tesseract tesseract = new Tesseract();
                String resolved = resolveDataPath();
                if (resolved != null) tesseract.setDatapath(resolved);
                tesseract.setLanguage(languages);
                // Page segmentation 3 (fully automatic) and LSTM-only, which reads invoice
                // layouts markedly better than the legacy engine.
                tesseract.setPageSegMode(3);
                tesseract.setOcrEngineMode(1);
                engine.set(tesseract);
                log.info("OCR enabled (languages: {}).", languages);
                return tesseract;
            } catch (Throwable t) {
                markUnavailable(t);
                return null;
            }
        }
    }

    private String resolveDataPath() {
        if (!dataPath.isBlank()) return dataPath;
        // The usual install locations. Left unset when none exists, so Tesseract falls back to
        // its own TESSDATA_PREFIX handling rather than being pointed at a directory that isn't there.
        for (String candidate : new String[]{"/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/share/tessdata",
                "/opt/homebrew/share/tessdata"}) {
            if (Files.isDirectory(Path.of(candidate))) return candidate;
        }
        return null;
    }

    private void markUnavailable(Throwable t) {
        if (!unavailable) {
            unavailable = true;
            engine.set(null);
            log.warn("OCR is unavailable, so image attachments and scanned PDFs will not be read: {}. "
                    + "Install tesseract-ocr and its language data, or set "
                    + "integration.attachments.ocr-enabled=false to silence this.", t.toString());
        }
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n…(truncated)" : text;
    }
}
