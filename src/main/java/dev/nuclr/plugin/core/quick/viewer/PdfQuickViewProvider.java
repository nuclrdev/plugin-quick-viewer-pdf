package dev.nuclr.plugin.core.quick.viewer;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.PluginTheme;
import dev.nuclr.plugin.QuickViewProviderPlugin;
import dev.nuclr.plugin.event.PluginEvent;
import dev.nuclr.plugin.event.PluginThemeUpdatedEvent;
import dev.nuclr.plugin.event.bus.PluginEventListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PdfQuickViewProvider implements QuickViewProviderPlugin, PluginEventListener {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    private ApplicationPluginContext context;
    private PdfQuickViewPanel panel;
    private volatile AtomicBoolean currentCancelled;
    private PluginTheme theme;

    @Override
    public PluginManifest getPluginInfo() {
        ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
        try (InputStream is = getClass().getResourceAsStream("/plugin.json")) {
            if (is != null) {
                return objectMapper.readValue(is, PluginManifest.class);
            }
        } catch (Exception e) {
            log.error("Error reading /plugin.json for PdfQuickViewProvider", e);
        }
        return null;
    }

    @Override
    public JComponent getPanel() {
        if (panel == null) {
            panel = new PdfQuickViewPanel();
            panel.applyTheme(theme);
            log.info("PdfQuickViewPanel created");
        }
        return panel;
    }

    @Override
    public List<MenuResource> getMenuItems(PluginPathResource source) {
        return List.of();
    }

    @Override
    public void load(ApplicationPluginContext context) {
        this.context = context;
        context.getEventBus().subscribe(this);
        applyTheme(resolveTheme(context));
    }

    @Override
    public void unload() {
        closeItem();
        if (context != null) {
            context.getEventBus().unsubscribe(this);
        }
        panel = null;
        context = null;
    }

    @Override
    public boolean supports(PluginPathResource resource) {
        if (resource == null || resource.getExtension() == null) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(resource.getExtension().toLowerCase(Locale.ROOT));
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
        if (currentCancelled != null) {
            currentCancelled.set(true);
        }
        currentCancelled = cancelled;
        getPanel();
        log.info("Opening PDF quick view: {}", resource.getName());
        return panel.load(resource, cancelled);
    }

    @Override
    public void closeItem() {
        if (currentCancelled != null) {
            currentCancelled.set(true);
            currentCancelled = null;
        }
        if (panel != null) {
            panel.clear();
        }
    }

    public void applyTheme(PluginTheme theme) {
        this.theme = theme;
        if (panel != null) {
            panel.applyTheme(theme);
        }
    }

    @Override
    public boolean isMessageSupported(PluginEvent msg) {
        return msg instanceof PluginThemeUpdatedEvent;
    }

    @Override
    public void handleMessage(PluginEvent e) {
        if (e instanceof PluginThemeUpdatedEvent) {
            applyTheme(resolveTheme(context));
        }
    }

    @Override
    public void onFocusGained() {
        // Quick view providers do not need focus-specific behavior.
    }

    @Override
    public void onFocusLost() {
        // Quick view providers do not need focus-specific behavior.
    }

    private static PluginTheme resolveTheme(ApplicationPluginContext context) {
        if (context == null) {
            return null;
        }
        Object theme = context.getGlobalData().get("pluginTheme");
        if (theme instanceof PluginTheme pluginTheme) {
            return pluginTheme;
        }
        return null;
    }
}
