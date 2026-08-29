package com.concentus.integration.content;

import com.concentus.config.Settings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR through the Tesseract program, on a machine described by the test rather than the real one:
 * where the binary is and what it prints are both supplied, so nothing here needs Tesseract
 * installed — and the absent case is exercised exactly as a machine without it would.
 */
class ImageOcrExtractorTest {

    private static final Path BINARY = Path.of("C:\\Program Files\\Tesseract-OCR\\tesseract.exe");
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** A Tesseract that lists some languages and reads every image as one fixed line. */
    private static final class FakeTesseract implements TesseractProcess.Runner {
        final List<List<String>> calls = new ArrayList<>();
        final List<byte[]> inputs = new ArrayList<>();
        String languages = "List of available languages in C:/tessdata (3):\neng\nosd\nspa\n";
        String reads = "Factura n\u00ba 2024-117\nTotal: 1.250,00 \u20ac";
        int exit = 0;

        @Override
        public TesseractProcess.Result run(List<String> argv, byte[] stdin, int timeoutSec) {
            calls.add(argv);
            inputs.add(stdin);
            if (argv.contains("--list-langs")) return new TesseractProcess.Result(0, languages, "");
            return new TesseractProcess.Result(exit, reads, "Warning: Invalid resolution 0 dpi.");
        }
    }

    private static ImageOcrExtractor with(FakeTesseract tesseract, Map<String, String> settings) {
        return new ImageOcrExtractor(Settings.of(settings), true, "", () -> Optional.of(BINARY), tesseract);
    }

    private static ImageOcrExtractor absent() {
        return new ImageOcrExtractor(Settings.none(), true, "", Optional::empty, new FakeTesseract());
    }

    // ---------------------------------------------------------------- the program is there

    @Test
    void readsAnImageThroughStdinAndReturnsStdoutAlone() throws Exception {
        FakeTesseract tesseract = new FakeTesseract();
        ImageOcrExtractor ocr = with(tesseract, Map.of());

        String text = ocr.extract(PNG, "factura.png");

        // The text, exactly, and none of the stderr chatter — merged streams would put "Warning:
        // Invalid resolution" into the knowledge base as if it were on the page.
        assertThat(text).isEqualTo("Factura n\u00ba 2024-117\nTotal: 1.250,00 \u20ac");
        List<String> argv = tesseract.calls.getLast();
        assertThat(argv.getFirst()).isEqualTo(BINARY.toString());
        assertThat(argv).containsSequence("stdin", "stdout");
        assertThat(tesseract.inputs.getLast()).isEqualTo(PNG);
    }

    @Test
    void asksOnlyForTheLanguagesThisInstallActuallyHas() throws Exception {
        // Tesseract refuses the whole run when one requested pack is missing, and the Windows
        // installer ships English alone unless the extras were ticked. cat is not installed here.
        FakeTesseract tesseract = new FakeTesseract();
        ImageOcrExtractor ocr = with(tesseract, Map.of());

        ocr.extract(PNG, "a.png");

        List<String> argv = tesseract.calls.getLast();
        assertThat(argv).containsSequence("-l", "eng+spa");
    }

    @Test
    void theLanguageSettingIsReadPerCall() throws Exception {
        FakeTesseract tesseract = new FakeTesseract();
        ImageOcrExtractor ocr = with(tesseract, Map.of(ImageOcrExtractor.LANGUAGES_KEY, "spa"));

        ocr.extract(PNG, "a.png");

        assertThat(tesseract.calls.getLast()).containsSequence("-l", "spa");
    }

    @Test
    void whenTheLanguageListCannotBeReadTheRequestGoesThroughUntouched() throws Exception {
        // An old build, a broken install: Tesseract's own complaint on the first image is more
        // informative than a guess made here.
        FakeTesseract tesseract = new FakeTesseract();
        tesseract.languages = "";
        ImageOcrExtractor ocr = with(tesseract, Map.of());

        ocr.extract(PNG, "a.png");

        assertThat(tesseract.calls.getLast()).containsSequence("-l", "eng+spa+cat");
    }

    @Test
    void aFailedRunIsAnErrorWithTesseractsLastWords() {
        // So the extraction report records "could not be read" for THIS file and carries on.
        FakeTesseract tesseract = new FakeTesseract();
        tesseract.exit = 1;
        ImageOcrExtractor ocr = with(tesseract, Map.of());

        assertThatThrownBy(() -> ocr.extract(PNG, "a.png"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited with 1")
                .hasMessageContaining("Invalid resolution");
    }

    @Test
    void aScannedPdfIsRenderedPageByPageAndCappedWithASayso() throws Exception {
        FakeTesseract tesseract = new FakeTesseract();
        ImageOcrExtractor ocr = with(tesseract, Map.of(ImageOcrExtractor.MAX_PAGES_KEY, "2"));
        PdfTextExtractor pdf = new PdfTextExtractor(ocr);

        String text = pdf.extract(blankPdf(3), "escaneo.pdf");

        assertThat(text).contains("--- page 1 ---").contains("--- page 2 ---")
                .doesNotContain("--- page 3 ---")
                .contains("OCR stopped after 2 of 3 pages");
        // Two pages rendered to PNG and read; the list-langs call is the other one.
        long reads = tesseract.calls.stream().filter(a -> a.contains("stdin")).count();
        assertThat(reads).isEqualTo(2);
        assertThat(tesseract.inputs.stream().filter(b -> b != null).allMatch(ImageOcrExtractorTest::isPng))
                .isTrue();
    }

    @Test
    void aPdfWithATextLayerNeverReachesOcr() throws Exception {
        FakeTesseract tesseract = new FakeTesseract();
        PdfTextExtractor pdf = new PdfTextExtractor(with(tesseract, Map.of()));

        String text = pdf.extract(AttachmentExtractionTest.pdfContaining(
                "This document has a perfectly good text layer of its own."), "texto.pdf");

        assertThat(text).contains("perfectly good text layer");
        assertThat(tesseract.calls).isEmpty();
    }

    // ---------------------------------------------------------------- the program is absent

    @Test
    void withoutTesseractItIsUnavailableAndSaysWhatInstallsIt() {
        ImageOcrExtractor ocr = absent();

        assertThat(ocr.isAvailable()).isFalse();
        assertThat(ocr.unavailableReason())
                .contains("Tesseract")
                .contains(TesseractLocator.installCommand())
                .contains("no restart");
    }

    @Test
    void theInstallCommandIsThePlatformsOwn() {
        assertThat(TesseractLocator.installCommand("Windows 11"))
                .isEqualTo("winget install UB-Mannheim.TesseractOCR");
        assertThat(TesseractLocator.installCommand("Mac OS X")).isEqualTo("brew install tesseract");
        assertThat(TesseractLocator.installCommand("Linux")).isEqualTo("apt install tesseract-ocr");
    }

    @Test
    void aScannedPdfWithoutTesseractYieldsNothingRatherThanFailing() throws Exception {
        PdfTextExtractor pdf = new PdfTextExtractor(absent());

        assertThat(pdf.extract(blankPdf(1), "escaneo.pdf")).isBlank();
    }

    @Test
    void switchedOffIsItsOwnReason() {
        ImageOcrExtractor ocr = new ImageOcrExtractor(Settings.none(), false, "",
                () -> Optional.of(BINARY), new FakeTesseract());

        assertThat(ocr.isAvailable()).isFalse();
        assertThat(ocr.unavailableReason()).contains("ocr-enabled=false");
    }

    // ---------------------------------------------------------------- where it is looked for

    // Paths are compared as forward-slash strings: these tests describe Windows from Linux CI and
    // a Mac from a Windows desktop, and java.nio's Path would otherwise take sides.

    @Test
    void onWindowsTheInstallersDefaultFoldersAreSearchedAfterThePath() {
        Map<String, String> env = Map.of(
                "PATH", "C:\\Windows\\system32;C:\\tools",
                "ProgramFiles", "C:\\Program Files",
                "LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local");

        Optional<Path> found = TesseractLocator.locate(env, "Windows 11",
                p -> slashes(p).endsWith("Local/Programs/Tesseract-OCR/tesseract.exe"));

        assertThat(found.map(ImageOcrExtractorTest::slashes))
                .contains("C:/Users/me/AppData/Local/Programs/Tesseract-OCR/tesseract.exe");
        assertThat(TesseractLocator.candidateDirectories(env, "Windows 11"))
                .map(ImageOcrExtractorTest::slashes)
                .containsSequence("C:/Windows/system32", "C:/tools",
                        "C:/Program Files/Tesseract-OCR",
                        "C:/Users/me/AppData/Local/Programs/Tesseract-OCR");
    }

    @Test
    void onMacHomebrewsBinIsSearchedEvenWhenTheDockGaveNoPath() {
        Optional<Path> found = TesseractLocator.locate(Map.of("PATH", "/usr/bin:/bin"), "Mac OS X",
                p -> slashes(p).endsWith("/opt/homebrew/bin/tesseract"));

        assertThat(found.map(ImageOcrExtractorTest::slashes)).contains("/opt/homebrew/bin/tesseract");
    }

    @Test
    void thePathWinsWhenItHasOne() {
        Optional<Path> found = TesseractLocator.locate(Map.of("PATH", "/home/me/bin:/usr/bin"), "Linux",
                p -> slashes(p).endsWith("/home/me/bin/tesseract")
                        || slashes(p).endsWith("/usr/bin/tesseract"));

        assertThat(found.map(ImageOcrExtractorTest::slashes)).contains("/home/me/bin/tesseract");
    }

    /** A path as one string with forward slashes, whatever this JVM's separator is. */
    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- fixtures

    /** A PDF of blank pages: no text layer, so it reads as a scan. */
    private static byte[] blankPdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length > 8 && new String(bytes, 1, 3, StandardCharsets.US_ASCII).equals("PNG");
    }
}
