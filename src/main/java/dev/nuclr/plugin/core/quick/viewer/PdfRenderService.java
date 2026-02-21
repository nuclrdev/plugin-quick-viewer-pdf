package dev.nuclr.plugin.core.quick.viewer;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.core.quick.viewer.backend.CliBackend;
import dev.nuclr.plugin.core.quick.viewer.backend.PdfRenderBackend;
import dev.nuclr.plugin.core.quick.viewer.backend.PdfboxBackend;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Orchestrates PDF loading, page rendering, LRU caching, and request cancellation.
 *
 * <p>All public methods are safe to call from the EDT. Heavy work runs on
 * virtual threads. Swing callbacks are dispatched via SwingUtilities.invokeLater.
 *
 * <p>Cancellation uses a monotonic epoch counter. When a new request supersedes
 * an in-flight one the old result is silently discarded.
 */
@Slf4j
public class PdfRenderService {

    // ----------------------------------------------------------------- types

    public record RenderResult(
            PdfDocumentInfo info,
            BufferedImage image,
            int pageIndex) {}

    // -------------------------------------------------------------- state

    private final PdfSettings settings = PdfSettings.getInstance();
    private final PdfPageCache cache;

    /** Incremented on every new load or page request to cancel stale work. */
    private final AtomicLong requestEpoch = new AtomicLong(0);

    /** Serialises openDocument / renderPage calls on the active backend. */
    private final ReentrantLock backendLock = new ReentrantLock();

    // Guarded by backendLock for writes; volatile for cheap reads elsewhere
    private volatile PdfRenderBackend activeBackend;
    private volatile String            currentDocumentId;
    private volatile PdfDocumentInfo   currentDocumentInfo;
    private volatile int               currentPageCount;

    // ----------------------------------------------------------- constructor

    public PdfRenderService() {
        this.cache = new PdfPageCache(settings.getCachePages());
    }

    // ------------------------------------------------------ public API (EDT)

    /**
     * Load a new PDF document and render its first page.
     * Cancels any in-flight load or render.
     */
    public void loadDocument(QuickViewItem item,
                             Consumer<RenderResult> onSuccess,
                             Consumer<String> onError) {
        long myEpoch = requestEpoch.incrementAndGet();
        Thread.ofVirtual().name("pdf-load-" + myEpoch).start(() ->
                doLoad(item, myEpoch, onSuccess, onError));
    }

    /**
     * Render a specific page of the already-open document.
     * Cancels any previously queued page render.
     */
    public void renderPage(int pageIndex,
                           Consumer<RenderResult> onSuccess,
                           Consumer<String> onError) {
        long myEpoch = requestEpoch.incrementAndGet();
        Thread.ofVirtual().name("pdf-page-" + myEpoch).start(() -> {
            BufferedImage img = renderPageInternal(pageIndex, myEpoch);
            if (requestEpoch.get() != myEpoch) return;
            if (img != null) {
                PdfDocumentInfo info = currentDocumentInfo;
                SwingUtilities.invokeLater(() -> onSuccess.accept(new RenderResult(info, img, pageIndex)));
            } else {
                SwingUtilities.invokeLater(() -> onError.accept("Failed to render page " + (pageIndex + 1)));
            }
        });
    }

    /**
     * Cancel all in-flight work and release backend resources.
     * Safe to call from EDT; backend close happens off-EDT.
     */
    public void close() {
        requestEpoch.incrementAndGet(); // cancel in-flight work

        // Capture then immediately null out so subsequent calls start clean
        PdfRenderBackend toClose;
        backendLock.lock();
        try {
            toClose = activeBackend;
            activeBackend = null;
            currentDocumentId = null;
            currentDocumentInfo = null;
            currentPageCount = 0;
        } finally {
            backendLock.unlock();
        }

        cache.clear();

        if (toClose != null) {
            Thread.ofVirtual().name("pdf-close").start(toClose::closeDocument);
        }
    }

    public int getCurrentPageCount() {
        return currentPageCount;
    }

    // ----------------------------------------------------- internal load flow

    private void doLoad(QuickViewItem item, long myEpoch,
                        Consumer<RenderResult> onSuccess,
                        Consumer<String> onError) {
        try {
            log.info("Loading PDF: {}", item.name());

            // Read bytes off EDT (may involve network/slow FS)
            byte[] pdfBytes;
            try (var in = item.openStream()) {
                pdfBytes = in.readAllBytes();
            }
            if (requestEpoch.get() != myEpoch) return;

            PdfRenderBackend backend = selectBackend();
            PdfDocumentInfo info;

            backendLock.lock();
            try {
                if (requestEpoch.get() != myEpoch) return; // superseded while waiting
                closeCurrentBackendInternal();
                info = backend.openDocument(pdfBytes);
                String docId = computeDocId(item);
                cache.invalidate(currentDocumentId);
                activeBackend      = backend;
                currentDocumentId  = docId;
                currentDocumentInfo = info;
                currentPageCount   = info.pageCount();
            } finally {
                backendLock.unlock();
            }

            if (requestEpoch.get() != myEpoch) return;

            // Render first page
            BufferedImage firstPage = renderPageInternal(0, myEpoch);
            if (requestEpoch.get() != myEpoch) return;

            if (firstPage != null) {
                SwingUtilities.invokeLater(() -> onSuccess.accept(new RenderResult(info, firstPage, 0)));
            } else {
                SwingUtilities.invokeLater(() -> onError.accept("Failed to render first page"));
            }

        } catch (PdfRenderBackend.EncryptedPdfException e) {
            if (requestEpoch.get() == myEpoch) {
                SwingUtilities.invokeLater(() -> onError.accept("Encrypted PDF \u2013 cannot preview"));
            }
        } catch (Exception e) {
            log.error("Failed to load PDF: {}", item.name(), e);
            if (requestEpoch.get() == myEpoch) {
                fallbackLoad(item, myEpoch, onSuccess, onError);
            }
        }
    }

    /** Retry with PDFBox when an optional CLI backend fails. */
    private void fallbackLoad(QuickViewItem item, long myEpoch,
                              Consumer<RenderResult> onSuccess,
                              Consumer<String> onError) {
        if (activeBackend instanceof PdfboxBackend) {
            // Already using PDFBox — nothing to fall back to
            SwingUtilities.invokeLater(() -> onError.accept("Cannot render PDF"));
            return;
        }
        log.warn("Primary backend failed; falling back to PDFBox for {}", item.name());
        try {
            byte[] pdfBytes;
            try (var in = item.openStream()) {
                pdfBytes = in.readAllBytes();
            }
            if (requestEpoch.get() != myEpoch) return;

            PdfboxBackend fallback = new PdfboxBackend();
            PdfDocumentInfo info;

            backendLock.lock();
            try {
                if (requestEpoch.get() != myEpoch) return;
                closeCurrentBackendInternal();
                info = fallback.openDocument(pdfBytes);
                String docId = computeDocId(item);
                cache.invalidate(currentDocumentId);
                activeBackend      = fallback;
                currentDocumentId  = docId;
                currentDocumentInfo = info;
                currentPageCount   = info.pageCount();
            } finally {
                backendLock.unlock();
            }

            if (requestEpoch.get() != myEpoch) return;

            BufferedImage firstPage = renderPageInternal(0, myEpoch);
            if (requestEpoch.get() != myEpoch) return;

            if (firstPage != null) {
                SwingUtilities.invokeLater(() -> onSuccess.accept(new RenderResult(info, firstPage, 0)));
            } else {
                SwingUtilities.invokeLater(() -> onError.accept("Failed to render PDF"));
            }

        } catch (PdfRenderBackend.EncryptedPdfException e) {
            if (requestEpoch.get() == myEpoch) {
                SwingUtilities.invokeLater(() -> onError.accept("Encrypted PDF \u2013 cannot preview"));
            }
        } catch (Exception ex) {
            log.error("PDFBox fallback also failed for {}", item.name(), ex);
            if (requestEpoch.get() == myEpoch) {
                SwingUtilities.invokeLater(() -> onError.accept("Cannot render PDF: " + ex.getMessage()));
            }
        }
    }

    // -------------------------------------------------- internal render

    /**
     * Render a page, checking cache first. Returns null if the request is stale
     * or the backend is unavailable.
     */
    private BufferedImage renderPageInternal(int pageIndex, long myEpoch) {
        String docId = currentDocumentId;
        if (docId == null) return null;

        float dpi = settings.getDpi();
        PdfPageCache.Key key = new PdfPageCache.Key(docId, pageIndex, dpi);

        // Cache lookup (no lock needed — PdfPageCache is internally synchronised)
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            log.debug("Cache hit: page {} of {}", pageIndex, docId);
            return cached;
        }

        if (requestEpoch.get() != myEpoch) return null;

        backendLock.lock();
        try {
            if (requestEpoch.get() != myEpoch) return null;
            if (activeBackend == null) return null;

            BufferedImage img = activeBackend.renderPage(pageIndex, dpi);
            cache.put(key, img);
            return img;
        } catch (Exception e) {
            log.error("Error rendering page {}", pageIndex, e);
            return null;
        } finally {
            backendLock.unlock();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Must be called with backendLock held. */
    private void closeCurrentBackendInternal() {
        if (activeBackend != null) {
            try { activeBackend.closeDocument(); }
            catch (Exception e) { log.warn("Error closing backend", e); }
            activeBackend = null;
        }
    }

    private PdfRenderBackend selectBackend() {
        return switch (settings.getBackend()) {
            case PDFBOX -> new PdfboxBackend();
            case AUTO -> {
                CliBackend cli = CliBackend.detect();
                yield cli != null ? cli : new PdfboxBackend();
            }
            case CLI_MuTool -> {
                CliBackend cli = CliBackend.forTool("MUTOOL");
                yield cli != null ? cli : new PdfboxBackend();
            }
            case CLI_POPPLER -> {
                CliBackend cli = CliBackend.forTool("POPPLER_CAIRO");
                if (cli == null) cli = CliBackend.forTool("POPPLER_PPM");
                yield cli != null ? cli : new PdfboxBackend();
            }
            case CLI_GS -> {
                CliBackend cli = CliBackend.forTool("GHOSTSCRIPT");
                yield cli != null ? cli : new PdfboxBackend();
            }
        };
    }

    private static String computeDocId(QuickViewItem item) {
        return item.name() + ":" + item.sizeBytes();
    }
}
