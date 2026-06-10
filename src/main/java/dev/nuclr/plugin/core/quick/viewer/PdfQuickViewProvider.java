package dev.nuclr.plugin.core.quick.viewer;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PdfQuickViewProvider implements QuickViewNuclrPlugin {

	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

	private NuclrPluginContext context;
	private PdfQuickViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private String uuid = java.util.UUID.randomUUID().toString();

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new PdfQuickViewPanel();
			log.info("PdfQuickViewPanel created");
		}
		return panel;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public void unload() {
		closeResource();
		panel = null;
		context = null;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		String extension = extension(resource);
		if (extension == null) {
			return false;
		}
		return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
	}

	private static String extension(Path path) {
		var name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
		return FilenameUtils.getExtension(name);
	}

	private static String extension(NuclrResource resource) {
		if (resource == null || resource.getName() == null) {
			return null;
		}
		String name = resource.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}
	
	@Override
	public int priority() {
		return 1;
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentCancelled = cancelled;
		panel();
		log.info("Opening PDF quick view: {}", resource.getName());
		return panel.load(resource, cancelled);
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		if (panel != null) {
			panel.clear();
		}
	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	/*
	 * 
	 * { "schemaVersion": 1, "name": "PDF Quick Viewer", "id":
	 * "dev.nuclr.plugin.core.quickviewer.pdf", "version": "1.0.0", "description":
	 * "A quick viewer for PDF files.", "author": "Nuclr Development Team",
	 * "license": "Apache-2.0", "website": "https://nuclr.dev", "pageUrl":
	 * "https://nuclr.dev/plugins/core/pdf-quick-viewer.html", "docUrl":
	 * "https://nuclr.dev/plugins/core/pdf-quick-viewer.html", "type": "Official",
	 * "quickViewProviders": [
	 * "dev.nuclr.plugin.core.quick.viewer.PdfQuickViewProvider" ] }
	 */

	@Override
	public String id() {
		return "dev.nuclr.plugin.core.quickviewer.pdf";
	}

	@Override
	public String name() {
		return "PDF Quick Viewer";
	}

	@Override
	public String version() {
		return loadVersion();
	}

	private static String loadVersion() {
		try (var stream = PdfQuickViewProvider.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) return "unknown";
			var props = new java.util.Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (java.io.IOException e) {
			return "unknown";
		}
	}

	@Override
	public String description() {
		return "A quick viewer for PDF files.";
	}

	@Override
	public String author() {
		return "Nuclr Development Team";
	}

	@Override
	public String license() {
		return "Apache-2.0";
	}

	@Override
	public String website() {
		return "https://nuclr.dev";
	}

	@Override
	public String pageUrl() {
		return "https://nuclr.dev/plugins/core/pdf-quick-viewer.html";
	}

	@Override
	public String docUrl() {
		return "https://nuclr.dev/plugins/core/pdf-quick-viewer.html";
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}

	@Override
	public NuclrResource getCurrentResource() {
		return null;
	}

	@Override
	public String uuid() {
		return uuid;
	}

}
