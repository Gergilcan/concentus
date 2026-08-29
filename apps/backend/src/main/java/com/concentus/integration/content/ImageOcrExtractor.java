package com.concentus.integration.content;

import com.concentus.config.Settings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * OCR for image attachments and scanned PDFs, by running the Tesseract program.
 *
 * <p>The one extractor that depends on something outside the JVM, and deliberately not bundled:
 * the previous binding (tess4j) put nine megabytes of Windows DLLs and an English model in every
 * installer for a feature most flows never reach, and still needed the native library installed
 * on macOS and Linux. Now the app looks for the {@code tesseract} program the way {@code winget},
 * {@code brew} and {@code apt} install it — {@link TesseractLocator} — and runs it per image. A
 * machine without it starts normally, reads no images, and says exactly what to install.
 *
 * <p><b>Languages</b> come from {@code knowledge.ocr-languages} ({@code eng+spa+cat} by default,
 * which is the user base). Tesseract refuses the whole run when one requested pack is missing —
 * and the Windows installer ships English alone unless the others were ticked — so the request
 * is intersected with what {@code --list-langs} reports, the missing ones are logged once with
 * where to get them, and the page is still read in the languages that are there.
 *
 * <p><b>Pages</b> of a scanned PDF are rendered by PDFBox and capped by {@code knowledge.ocr-max-pages}
 * (20): a three-hundred-page scan at 300 DPI is minutes of OCR, and the ingest says when it
 * stopped rather than silently reading a prefix. Both settings are read per call, through
 * {@link Settings}, so a change lands on the next attachment.
 */
@Component
public class ImageOcrExtractor implements AttachmentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrExtractor.class);

    public static final String LANGUAGES_KEY = "knowledge.ocr-languages";
    public static final String MAX_PAGES_KEY = "knowledge.ocr-max-pages";
    public static final String DEFAULT_LANGUAGES = "eng+spa+cat";
    public static final int DEFAULT_MAX_PAGES = 20;

    /** Scans are usually 200–300 DPI; rendering PDF pages at this reads reliably without ballooning. */
    private static final int RENDER_DPI = 300;
    private static final int MAX_CHARS = 100_000;
    /** A page at 300 DPI takes Tesseract a few seconds; a minute means something is wrong. */
    private static final int OCR_TIMEOUT_SECONDS = 90;
    private static final int LIST_LANGS_TIMEOUT_SECONDS = 15;
    /** How long a located binary and its language list are trusted before looking again. */
    private static final long CACHE_MS = 30_000;

    private final Settings settings;
    private final boolean enabled;
    private final String dataPath;
    private final Supplier<Optional<Path>> locator;
    private final TesseractProcess.Runner runner;

    private volatile Located located;
    /** Requested-but-missing languages already complained about, so the log says it once. */
    private final Set<String> reportedMissing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Explicit, because a second constructor exists for tests and Spring will not choose between
    // two candidates on its own — it looks for a no-arg one and fails.
    @org.springframework.beans.factory.annotation.Autowired
    public ImageOcrExtractor(Settings settings,
                             @Value("${integration.attachments.ocr-enabled:true}") boolean enabled,
                             @Value("${integration.attachments.ocr-data-path:}") String dataPath) {
        this(settings, enabled, dataPath, TesseractLocator::locate, TesseractProcess::run);
    }

    /**
     * The seam: where the binary is and what running it returns are both supplied, so a test
     * describes a machine instead of needing Tesseract on it.
     */
    public ImageOcrExtractor(Settings settings, boolean enabled, String dataPath,
                             Supplier<Optional<Path>> locator, TesseractProcess.Runner runner) {
        this.settings = settings;
        this.enabled = enabled;
        this.dataPath = dataPath == null ? "" : dataPath.trim();
        this.locator = locator;
        this.runner = runner;
    }

    /** An extractor that is switched off — for fixtures that are not about OCR. */
    public static ImageOcrExtractor off() {
        return new ImageOcrExtractor(Settings.none(), false, "", Optional::empty,
                (argv, stdin, timeout) -> {
                    throw new IOException("OCR is off in this fixture");
                });
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
        return enabled && binary().isPresent();
    }

    /**
     * Why nothing will be read, with the command that changes that. The platform's own installer
     * is named because "install Tesseract" sends a person to a search engine and this does not.
     */
    @Override
    public String unavailableReason() {
        if (isAvailable()) return null;
        if (!enabled) return "OCR is switched off (integration.attachments.ocr-enabled=false).";
        return "OCR needs the Tesseract program, which is not installed on this machine. Install it "
                + "with `" + TesseractLocator.installCommand() + "` — it is picked up on the next "
                + "attachment, no restart needed.";
    }

    @Override
    public String extract(byte[] bytes, String filename) throws Exception {
        Optional<Path> binary = binary();
        if (binary.isEmpty()) return "";
        return truncate(ocr(binary.get(), bytes, filename));
    }

    /**
     * Renders and reads a scanned PDF's pages, up to the configured cap. Called by
     * {@link PdfTextExtractor} once it has found no text layer.
     */
    public String extractFromPdf(PDDocument document, String filename) throws IOException {
        Optional<Path> binary = binary();
        if (binary.isEmpty()) return "";
        int maxPages = Math.max(1, settings.number(MAX_PAGES_KEY, DEFAULT_MAX_PAGES));
        PDFRenderer renderer = new PDFRenderer(document);
        StringBuilder out = new StringBuilder();
        int pages = Math.min(document.getNumberOfPages(), maxPages);
        for (int page = 0; page < pages; page++) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(page, RENDER_DPI, ImageType.GRAY);
                String text = ocr(binary.get(), png(image), filename + " page " + (page + 1));
                if (!text.isBlank()) {
                    out.append("--- page ").append(page + 1).append(" ---\n").append(text).append('\n');
                }
            } catch (IOException e) {
                // One unreadable page must not lose the pages that did read.
                log.info("OCR of '{}' page {} failed: {}", filename, page + 1, e.getMessage());
            }
        }
        if (document.getNumberOfPages() > pages) {
            out.append("…(OCR stopped after ").append(pages).append(" of ")
               .append(document.getNumberOfPages()).append(" pages — raise ").append(MAX_PAGES_KEY)
               .append(" to read more)\n");
        }
        return out.toString();
    }

    /** One image through the program: {@code tesseract stdin stdout -l <langs>}. */
    private String ocr(Path binary, byte[] image, String what) throws IOException {
        List<String> argv = new ArrayList<>(List.of(binary.toString(), "stdin", "stdout",
                "-l", languages(binary)));
        if (!dataPath.isBlank()) argv.addAll(List.of("--tessdata-dir", dataPath));
        TesseractProcess.Result result = runner.run(argv, image, OCR_TIMEOUT_SECONDS);
        if (!result.ok()) {
            throw new IOException("tesseract exited with " + result.exit() + " on " + what + ": "
                    + lastLine(result.stderr()));
        }
        return result.stdout().strip();
    }

    /**
     * The languages to ask for: what was configured, minus what this install cannot serve.
     *
     * <p>Falls back to the configured string untouched when the list cannot be read (an old
     * build, a broken install) — Tesseract then reports its own complaint on the first image,
     * which is more informative than guessing here.
     */
    private String languages(Path binary) {
        String configured = settings.get(LANGUAGES_KEY, DEFAULT_LANGUAGES).trim();
        if (configured.isEmpty()) configured = DEFAULT_LANGUAGES;
        Set<String> available = availableLanguages(binary);
        if (available.isEmpty()) return configured;

        List<String> wanted = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String lang : configured.split("\\+")) {
            String l = lang.trim().toLowerCase(Locale.ROOT);
            if (l.isEmpty()) continue;
            (available.contains(l) ? wanted : missing).add(l);
        }
        if (!missing.isEmpty() && reportedMissing.addAll(missing)) {
            log.warn("Tesseract at {} has no language data for {} (it has {}). Reading in {} "
                    + "instead; to add the rest: {}.", binary, missing, available,
                    wanted.isEmpty() ? "whatever it defaults to" : String.join("+", wanted),
                    TesseractLocator.languagePackHint());
        }
        return wanted.isEmpty() ? configured : String.join("+", wanted);
    }

    /**
     * What {@code --list-langs} reports, cached with the binary. The header line names the
     * tessdata directory and is skipped; the rest is one language code per line. Older builds
     * printed the list on stderr, so both streams are read.
     */
    private Set<String> availableLanguages(Path binary) {
        Located cached = located;
        if (cached != null && cached.path().equals(binary) && cached.languages() != null) {
            return cached.languages();
        }
        Set<String> langs = new LinkedHashSet<>();
        try {
            List<String> argv = new ArrayList<>(List.of(binary.toString(), "--list-langs"));
            if (!dataPath.isBlank()) argv.addAll(List.of("--tessdata-dir", dataPath));
            TesseractProcess.Result result = runner.run(argv, null, LIST_LANGS_TIMEOUT_SECONDS);
            for (String line : (result.stdout() + "\n" + result.stderr()).split("\\R")) {
                String l = line.strip().toLowerCase(Locale.ROOT);
                if (l.matches("[a-z_]{2,16}")) langs.add(l);
            }
        } catch (IOException e) {
            log.debug("Could not list Tesseract's languages: {}", e.getMessage());
        }
        located = new Located(binary, System.currentTimeMillis(), langs);
        return langs;
    }

    /**
     * The program, looked up again every {@link #CACHE_MS}: a lookup is a handful of file checks,
     * and a person who has just installed Tesseract should not have to restart to be believed.
     */
    private Optional<Path> binary() {
        if (!enabled) return Optional.empty();
        Located cached = located;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_MS) return Optional.ofNullable(cached.path());
        Optional<Path> found = locator.get();
        // A relocated binary invalidates the language list too; the same install keeps it.
        Set<String> languages = cached != null && found.isPresent()
                && found.get().equals(cached.path()) ? cached.languages() : null;
        located = new Located(found.orElse(null), now, languages);
        if (found.isPresent() && (cached == null || cached.path() == null)) {
            log.info("OCR available: {}", found.get());
        }
        return found;
    }

    private record Located(Path path, long at, Set<String> languages) {
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String lastLine(String s) {
        if (s == null || s.isBlank()) return "no error output";
        String[] lines = s.strip().split("\\R");
        return lines[lines.length - 1];
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return ExtractedText.clamp(text, MAX_CHARS);
    }
}
