package dev.nuclr.plugin.core.quick.viewer;

import java.awt.image.BufferedImage;
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
import dev.nuclr.plugin.core.quick.viewer.backend.PdfboxBackend;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PdfQuickViewProvider implements QuickViewNuclrPlugin {

	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

	private NuclrPluginContext context;
	private PdfQuickViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private NuclrThemeScheme themeScheme;
	private String uuid = java.util.UUID.randomUUID().toString();

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new PdfQuickViewPanel();
			panel.applyTheme(themeScheme != null ? themeScheme : context != null ? context.getTheme() : null);
			log.info("PdfQuickViewPanel created");
		}
		return panel;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.themeScheme = context != null ? context.getTheme() : null;
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
	public boolean supportsThumbnails() {
		return true;
	}

	@Override
	public BufferedImage thumbnail(NuclrResource resource, int maxWidth, int maxHeight, AtomicBoolean cancelled) {
		if (resource == null || maxWidth <= 0 || maxHeight <= 0 || !supports(resource)) {
			return null;
		}
		try {
			byte[] pdfBytes;
			try (var in = new CancelableInputStream(resource.openInputStream(), cancelled)) {
				pdfBytes = in.readAllBytes();
			}
			if (cancelled != null && cancelled.get()) {
				return null;
			}
			// Always PDFBox: it is in-process and needs no per-document state, where
			// the optional CLI backends are tied to the document an instance has open.
			return ThumbnailScaler.fit(PdfboxBackend.renderFirstPage(pdfBytes, maxWidth, maxHeight), maxWidth, maxHeight);
		} catch (Exception e) {
			log.debug("No thumbnail for {}: {}", resource.getName(), e.toString());
			return null;
		}
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


	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		this.themeScheme = themeScheme;
		if (panel != null) {
			panel.applyTheme(themeScheme);
		}
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
