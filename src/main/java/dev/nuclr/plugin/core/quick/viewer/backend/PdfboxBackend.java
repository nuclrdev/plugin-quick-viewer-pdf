package dev.nuclr.plugin.core.quick.viewer.backend;

import dev.nuclr.plugin.core.quick.viewer.PdfDocumentInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;

/**
 * PDF rendering backend using Apache PDFBox 3.x.
 * Always available — no external tools required.
 */
@Slf4j
public class PdfboxBackend implements PdfRenderBackend {

    private static volatile boolean imageIoProvidersRegistered;

    private PDDocument document;
    private PDFRenderer renderer;

    @Override
    public String name() {
        return "PDFBox";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public PdfDocumentInfo openDocument(byte[] pdfBytes) throws Exception {
        closeDocument();
        ensureImageIoProvidersRegistered();
        try {
            document = Loader.loadPDF(pdfBytes);
        } catch (InvalidPasswordException e) {
            throw new EncryptedPdfException();
        }

        renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);

        PDDocumentInformation docInfo = document.getDocumentInformation();
        String title  = sanitize(docInfo != null ? docInfo.getTitle()  : null);
        String author = sanitize(docInfo != null ? docInfo.getAuthor() : null);
        int    pages  = document.getNumberOfPages();
        String ver    = String.format("PDF %.1f", document.getVersion());

        log.info("Opened PDF via PDFBox: {} pages, {}", pages, ver);
        return new PdfDocumentInfo(title, author, pages, ver, false);
    }

    @Override
    public BufferedImage renderPage(int pageIndex, float dpi) throws Exception {
        if (renderer == null) throw new IllegalStateException("No document open");
        log.debug("PDFBox: rendering page {} at {} DPI", pageIndex, dpi);
        return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
    }

    @Override
    public void closeDocument() {
        renderer = null;
        if (document != null) {
            try {
                document.close();
            } catch (Exception e) {
                log.warn("Error closing PDDocument", e);
            }
            document = null;
        }
    }

    private static String sanitize(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }

    private static void ensureImageIoProvidersRegistered() {
        if (imageIoProvidersRegistered) {
            return;
        }
        synchronized (PdfboxBackend.class) {
            if (imageIoProvidersRegistered) {
                return;
            }

            ClassLoader pluginClassLoader = PdfboxBackend.class.getClassLoader();
            Thread thread = Thread.currentThread();
            ClassLoader previousContextLoader = thread.getContextClassLoader();

            try {
                thread.setContextClassLoader(pluginClassLoader);
                ImageIO.scanForPlugins();
                registerDiscoveredProviders(pluginClassLoader);
                imageIoProvidersRegistered = true;
            } finally {
                thread.setContextClassLoader(previousContextLoader);
            }
        }
    }

    private static void registerDiscoveredProviders(ClassLoader pluginClassLoader) {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        Iterator<ImageReaderSpi> providers =
                java.util.ServiceLoader.load(ImageReaderSpi.class, pluginClassLoader).iterator();

        while (providers.hasNext()) {
            try {
                registry.registerServiceProvider(providers.next(), ImageReaderSpi.class);
            } catch (Throwable t) {
                log.debug("Skipping ImageIO provider registration: {}", t.getMessage());
            }
        }
    }
}
