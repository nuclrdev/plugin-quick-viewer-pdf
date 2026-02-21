package dev.nuclr.plugin.core.quick.viewer.backend;

import dev.nuclr.plugin.core.quick.viewer.PdfDocumentInfo;

import java.awt.image.BufferedImage;

/**
 * Strategy interface for PDF rendering backends.
 * Implementations must be thread-safe: all methods may be called from
 * multiple virtual threads, but the caller guarantees mutual exclusion
 * via an external lock.
 */
public interface PdfRenderBackend {

    /** Human-readable name for logging. */
    String name();

    /** Return true if this backend can be used on the current system. */
    boolean isAvailable();

    /**
     * Open a PDF from the given byte array. Blocks until the document is ready.
     *
     * @param pdfBytes raw PDF bytes
     * @return document metadata
     * @throws EncryptedPdfException if the PDF requires a password
     * @throws Exception             for I/O or format errors
     */
    PdfDocumentInfo openDocument(byte[] pdfBytes) throws Exception;

    /**
     * Render one page to a BufferedImage (RGB colour space).
     *
     * @param pageIndex 0-based page index
     * @param dpi       rendering resolution; caller caps at 200
     */
    BufferedImage renderPage(int pageIndex, float dpi) throws Exception;

    /** Release all resources held for the current document. */
    void closeDocument();

    /** Thrown when the PDF requires a password. */
    class EncryptedPdfException extends Exception {
        public EncryptedPdfException() {
            super("Encrypted PDF \u2013 cannot preview");
        }
    }
}
