package dev.nuclr.plugin.core.quick.viewer.backend;

import dev.nuclr.plugin.core.quick.viewer.PdfDocumentInfo;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional CLI-based PDF rendering backend.
 * Supports MuPDF (mutool), Poppler (pdftocairo/pdftoppm), and Ghostscript (gs).
 * Falls back gracefully when the chosen tool is unavailable.
 */
@Slf4j
public class CliBackend implements PdfRenderBackend {

    public enum Tool {
        MUTOOL("mutool"),
        POPPLER_CAIRO("pdftocairo"),
        POPPLER_PPM("pdftoppm"),
        GHOSTSCRIPT("gs");

        final String exe;
        Tool(String exe) { this.exe = exe; }
    }

    private final Tool tool;
    private Path tempPdfFile;

    private CliBackend(Tool tool) {
        this.tool = tool;
    }

    /**
     * Detect the first available CLI tool in preference order.
     * Returns null if no tool is found.
     */
    public static CliBackend detect() {
        for (Tool t : Tool.values()) {
            if (probe(t.exe)) {
                log.info("PDF CLI backend detected: {} ({})", t.name(), t.exe);
                return new CliBackend(t);
            }
        }
        return null;
    }

    /**
     * Create a backend for a specific tool enum name (case-insensitive).
     * Returns null if the tool is not available.
     */
    public static CliBackend forTool(String toolEnumName) {
        for (Tool t : Tool.values()) {
            if (t.name().equalsIgnoreCase(toolEnumName) && probe(t.exe)) {
                return new CliBackend(t);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ probe

    private static boolean probe(String exe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------- PdfRenderBackend impl

    @Override
    public String name() {
        return "CLI/" + tool.name();
    }

    @Override
    public boolean isAvailable() {
        return probe(tool.exe);
    }

    @Override
    public PdfDocumentInfo openDocument(byte[] pdfBytes) throws Exception {
        closeDocument();
        tempPdfFile = Files.createTempFile("nuclr-pdf-", ".pdf");
        Files.write(tempPdfFile, pdfBytes);
        int pageCount = detectPageCount(tempPdfFile);
        log.info("Opened PDF via {}: {} pages", tool.exe, pageCount);
        return new PdfDocumentInfo(null, null, pageCount, null, false);
    }

    @Override
    public BufferedImage renderPage(int pageIndex, float dpi) throws Exception {
        if (tempPdfFile == null) throw new IllegalStateException("No document open");
        Path outBase = Files.createTempFile("nuclr-page-", "");
        try {
            Path outPng = renderToFile(tempPdfFile, pageIndex, dpi, outBase);
            BufferedImage img = ImageIO.read(outPng.toFile());
            Files.deleteIfExists(outPng);
            if (img == null) throw new IOException(tool.exe + " produced an unreadable image");
            return img;
        } finally {
            Files.deleteIfExists(outBase);
        }
    }

    @Override
    public void closeDocument() {
        if (tempPdfFile != null) {
            try { Files.deleteIfExists(tempPdfFile); } catch (IOException ignored) {}
            tempPdfFile = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private int detectPageCount(Path pdf) {
        List<String> cmd = new ArrayList<>();
        switch (tool) {
            case MUTOOL         -> { cmd.add(tool.exe); cmd.add("info"); cmd.add(pdf.toString()); }
            case POPPLER_CAIRO,
                 POPPLER_PPM   -> { cmd.add("pdfinfo"); cmd.add(pdf.toString()); }
            default            -> { return 1; }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for (String line : output.split("[\r\n]+")) {
                String t = line.trim();
                if (t.startsWith("Pages:")) {
                    String[] parts = t.split(":\\s*", 2);
                    if (parts.length == 2) {
                        String num = parts[1].trim().split("\\s+")[0];
                        return Integer.parseInt(num);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not detect page count via {}: {}", tool.exe, e.getMessage());
        }
        return 1;
    }

    /**
     * Render a single page to a PNG file and return its path.
     * The caller is responsible for deleting the returned file.
     */
    private Path renderToFile(Path pdf, int pageIndex, float dpi, Path outBase) throws Exception {
        int oneBased = pageIndex + 1;
        int dpiInt   = Math.round(dpi);
        List<String> cmd = new ArrayList<>();
        Path outPng;

        switch (tool) {
            case MUTOOL -> {
                outPng = Path.of(outBase + ".png");
                cmd.addAll(List.of(tool.exe, "draw",
                        "-r", String.valueOf(dpiInt),
                        "-o", outPng.toString(),
                        pdf.toString(),
                        String.valueOf(oneBased)));
            }
            case POPPLER_CAIRO -> {
                // pdftocairo appends -000001.png to the prefix
                outPng = Path.of(outBase + String.format("-%06d.png", 1));
                cmd.addAll(List.of(tool.exe, "-png",
                        "-r", String.valueOf(dpiInt),
                        "-f", String.valueOf(oneBased),
                        "-l", String.valueOf(oneBased),
                        pdf.toString(), outBase.toString()));
            }
            case POPPLER_PPM -> {
                outPng = Path.of(outBase + String.format("-%06d.png", 1));
                cmd.addAll(List.of(tool.exe, "-png",
                        "-r", String.valueOf(dpiInt),
                        "-f", String.valueOf(oneBased),
                        "-l", String.valueOf(oneBased),
                        pdf.toString(), outBase.toString()));
            }
            case GHOSTSCRIPT -> {
                outPng = Path.of(outBase + ".png");
                cmd.addAll(List.of(tool.exe,
                        "-dNOPAUSE", "-dBATCH", "-dSAFER",
                        "-sDEVICE=png16m",
                        "-r" + dpiInt,
                        "-dFirstPage=" + oneBased,
                        "-dLastPage=" + oneBased,
                        "-sOutputFile=" + outPng,
                        pdf.toString()));
            }
            default -> throw new IllegalStateException("Unknown tool: " + tool);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String stderr = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException(tool.exe + " exited with " + exitCode + ": " + stderr.trim());
        }
        return outPng;
    }
}
