package dev.nuclr.plugin.core.quick.viewer;

/**
 * Metadata about an opened PDF document.
 */
public record PdfDocumentInfo(
        String title,
        String author,
        int pageCount,
        String pdfVersion,
        boolean encrypted) {

    public static PdfDocumentInfo ofEncrypted() {
        return new PdfDocumentInfo(null, null, 0, null, true);
    }
}
