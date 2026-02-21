package dev.nuclr.plugin.core.quick.viewer;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Simple settings store for the PDF Quick Viewer plugin.
 * Persisted to the platform user config directory as a .properties file.
 * No external dependencies — uses java.util.Properties.
 */
@Slf4j
public final class PdfSettings {

    public static final String KEY_DPI              = "pdf.quickView.dpi";
    public static final String KEY_CACHE_PAGES      = "pdf.quickView.cachePages";
    public static final String KEY_SHOW_INFO_OVERLAY = "pdf.quickView.showInfoOverlay";
    public static final String KEY_BACKEND          = "pdf.quickView.backend";

    public enum Backend {
        PDFBOX, AUTO, CLI_MuTool, CLI_POPPLER, CLI_GS
    }

    private static final int     DEFAULT_DPI           = 144;
    private static final int     MAX_DPI               = 200;
    private static final int     MIN_DPI               = 36;
    private static final int     DEFAULT_CACHE_PAGES   = 10;
    private static final boolean DEFAULT_SHOW_OVERLAY  = true;
    private static final Backend DEFAULT_BACKEND       = Backend.PDFBOX;

    private static final PdfSettings INSTANCE = new PdfSettings();

    private final Properties props = new Properties();

    private PdfSettings() {
        load();
    }

    public static PdfSettings getInstance() {
        return INSTANCE;
    }

    // --- Getters ---

    public int getDpi() {
        try {
            int raw = Integer.parseInt(props.getProperty(KEY_DPI, String.valueOf(DEFAULT_DPI)));
            return Math.min(Math.max(raw, MIN_DPI), MAX_DPI);
        } catch (NumberFormatException e) {
            return DEFAULT_DPI;
        }
    }

    public int getCachePages() {
        try {
            return Integer.parseInt(props.getProperty(KEY_CACHE_PAGES, String.valueOf(DEFAULT_CACHE_PAGES)));
        } catch (NumberFormatException e) {
            return DEFAULT_CACHE_PAGES;
        }
    }

    public boolean isShowInfoOverlay() {
        return Boolean.parseBoolean(props.getProperty(KEY_SHOW_INFO_OVERLAY, String.valueOf(DEFAULT_SHOW_OVERLAY)));
    }

    public Backend getBackend() {
        try {
            return Backend.valueOf(props.getProperty(KEY_BACKEND, DEFAULT_BACKEND.name()));
        } catch (IllegalArgumentException e) {
            return DEFAULT_BACKEND;
        }
    }

    // --- Setters (also persist) ---

    public synchronized void setDpi(int dpi) {
        props.setProperty(KEY_DPI, String.valueOf(Math.min(Math.max(dpi, MIN_DPI), MAX_DPI)));
        save();
    }

    public synchronized void setShowInfoOverlay(boolean show) {
        props.setProperty(KEY_SHOW_INFO_OVERLAY, String.valueOf(show));
        save();
    }

    public synchronized void setBackend(Backend backend) {
        props.setProperty(KEY_BACKEND, backend.name());
        save();
    }

    // --- Persistence ---

    private void load() {
        Path file = settingsFile();
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Could not load PDF plugin settings, using defaults: {}", e.getMessage());
        }
    }

    private void save() {
        Path file = settingsFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "Nuclr PDF Quick Viewer settings");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not save PDF plugin settings: {}", e.getMessage());
        }
    }

    private static Path settingsFile() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = (appData != null)
                    ? Path.of(appData, "nuclr")
                    : Path.of(System.getProperty("user.home"), "nuclr");
        } else if (os.contains("mac")) {
            dir = Path.of(System.getProperty("user.home"), "Library", "Application Support", "nuclr");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            dir = (xdg != null)
                    ? Path.of(xdg, "nuclr")
                    : Path.of(System.getProperty("user.home"), ".config", "nuclr");
        }
        return dir.resolve("pdf-quick-viewer.properties");
    }
}
