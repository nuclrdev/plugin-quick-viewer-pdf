package dev.nuclr.plugin.core.quick.viewer;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JComponent;
import java.util.Locale;
import java.util.Set;

/**
 * QuickViewProvider implementation for PDF files.
 *
 * <p>Registered in {@code plugin.json} under {@code quickViewProviders}.
 * The panel and render service are created lazily on first use.
 */
@Slf4j
public class PdfQuickViewProvider implements QuickViewProvider {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    private PdfQuickViewPanel panel;

    // -------------------------------------------------------- QuickViewProvider

    @Override
    public String getPluginClass() {
        return getClass().getName();
    }

    @Override
    public boolean matches(QuickViewItem item) {
        String ext = item.extension();
        return ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    @Override
    public JComponent getPanel() {
        if (panel == null) {
            panel = new PdfQuickViewPanel();
            log.info("PdfQuickViewPanel created");
        }
        return panel;
    }

    @Override
    public boolean open(QuickViewItem item) {
        getPanel(); // ensure panel is initialised
        log.info("Opening PDF quick view: {}", item.name());
        return panel.load(item);
    }

    @Override
    public void close() {
        if (panel != null) {
            panel.clear();
        }
    }

    @Override
    public void unload() {
        close();
        panel = null;
    }

    @Override
    public int priority() {
        return 1;
    }
}
