package com.concentus.integration.content;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where the Tesseract program is on this machine, if anywhere.
 *
 * <p>OCR is the one thing the knowledge ingest cannot do in pure Java, and the answer used to be
 * tess4j: a JNA binding that bundled Windows DLLs and an English model inside a nine-megabyte
 * jar, and then still needed the native library installed on every other platform. The program
 * itself is what people actually have — {@code winget}, {@code brew} and {@code apt} all ship it
 * — so the app now looks for that and runs it as a process, and the jar carries nothing.
 *
 * <p>The PATH is searched first. Then the places installers put it without touching the PATH:
 * the UB Mannheim Windows installer defaults to {@code %ProgramFiles%\Tesseract-OCR} (or the
 * per-user {@code %LocalAppData%\Programs\Tesseract-OCR}) and offers to add it to the PATH only
 * as a checkbox most people skip; on macOS a desktop app launched from the Dock inherits a PATH
 * without Homebrew's bin directory at all.
 *
 * <p>Nothing here runs the binary. Finding a file is cheap enough to do per attachment; proving
 * it works is what running it does, and a broken install then fails one attachment with its own
 * error rather than switching OCR off for the session.
 */
public final class TesseractLocator {

    private TesseractLocator() {
    }

    /** The binary, on the real machine. */
    public static Optional<Path> locate() {
        return locate(System.getenv(), System.getProperty("os.name", ""), Files::isExecutable);
    }

    /**
     * The binary, on a described machine — the seam the tests use.
     *
     * @param env      the environment ({@code PATH}, {@code ProgramFiles}, {@code LOCALAPPDATA})
     * @param osName   the JVM's {@code os.name}
     * @param usable   whether a candidate path is an executable file
     */
    static Optional<Path> locate(Map<String, String> env, String osName, Predicate<Path> usable) {
        boolean windows = isWindows(osName);
        String executable = windows ? "tesseract.exe" : "tesseract";
        for (Path dir : candidateDirectories(env, osName)) {
            Path candidate = dir.resolve(executable);
            if (usable.test(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** PATH entries first, in order, then the platform's install locations. */
    static List<Path> candidateDirectories(Map<String, String> env, String osName) {
        List<Path> dirs = new ArrayList<>();
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        // The described OS's separator, not this JVM's: a test describes a Mac from Windows.
        for (String entry : path.split(isWindows(osName) ? ";" : ":")) {
            if (!entry.isBlank()) dirs.add(Path.of(entry.trim()));
        }
        if (isWindows(osName)) {
            String programFiles = env.getOrDefault("ProgramFiles", "C:\\Program Files");
            dirs.add(Path.of(programFiles, "Tesseract-OCR"));
            String localAppData = env.get("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                dirs.add(Path.of(localAppData, "Programs", "Tesseract-OCR"));
            }
        } else if (isMac(osName)) {
            // Apple Silicon Homebrew, Intel Homebrew, MacPorts.
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/opt/local/bin"));
        } else {
            dirs.add(Path.of("/usr/bin"));
            dirs.add(Path.of("/usr/local/bin"));
        }
        return dirs;
    }

    /** The command that installs it here, for the message that says it is missing. */
    public static String installCommand() {
        return installCommand(System.getProperty("os.name", ""));
    }

    static String installCommand(String osName) {
        if (isWindows(osName)) return "winget install UB-Mannheim.TesseractOCR";
        if (isMac(osName)) return "brew install tesseract";
        return "apt install tesseract-ocr";
    }

    /**
     * Where the language packs come from on this platform, for when the program is present but a
     * requested language is not. The Windows installer carries English only unless the extra
     * languages were ticked; Debian and Homebrew package them separately.
     */
    public static String languagePackHint() {
        return languagePackHint(System.getProperty("os.name", ""));
    }

    static String languagePackHint(String osName) {
        if (isWindows(osName)) {
            return "re-run the Tesseract installer and tick the languages under \"Additional "
                    + "language data\", or drop the .traineddata files into its tessdata folder";
        }
        if (isMac(osName)) return "brew install tesseract-lang";
        return "apt install tesseract-ocr-spa tesseract-ocr-cat (one package per language)";
    }

    /**
     * The probe command for the runtime panel: the bare name when it is on the PATH, else the
     * located file quoted for the shell — so a Windows install that never touched the PATH still
     * shows as found rather than telling someone to install what they just installed.
     */
    public static String probeCommand() {
        return locate().map(p -> "\"" + p + "\"").orElse("tesseract");
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }
}
