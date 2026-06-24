package dev.nuclr.plugin.core.quick.viewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Swing panel that displays a PDF quick view.
 *
 * <p>All state fields (currentInfo, currentImage, currentPageIndex, statusMessage)
 * are read and written exclusively on the EDT.
 *
 * <p>Heavy work (loading, rendering) is delegated to {@link PdfRenderService}
 * which runs on virtual threads and pushes results back via invokeLater.
 */
@Slf4j
public class PdfQuickViewPanel extends JPanel {

    // ---------------------------------------------------------------- state (EDT-only)

    private PdfDocumentInfo currentInfo;
    private BufferedImage   currentImage;
    private int             currentPageIndex;
    private String          statusMessage = "No PDF selected";

    // ---------------------------------------------------------------- services

    private final PdfRenderService renderService;
    private final PdfSettings      settings = PdfSettings.getInstance();

    // ---------------------------------------------------------------- UI components

    private final JButton   prevButton;
    private final JButton   nextButton;
    private final JButton   goToButton;
    private final JLabel    pageLabel;
    private final JCheckBox overlayCheck;
    private final JPanel    toolbar;
    private final PageCanvas pageCanvas;

    private Color canvasBackground = Color.BLACK;
    private Color toolbarBackground = new Color(0x2B2B2B);
    private Color secondaryForeground = new Color(0xAAAAAA);
    private Color overlayBackground = new Color(0, 0, 0, 168);
    private Color overlayForeground = new Color(0xDDDDDD);

    // ============================================================ constructor

    public PdfQuickViewPanel() {
        this.renderService = new PdfRenderService();
        setLayout(new BorderLayout());
        setBackground(canvasBackground);
        setFocusable(true);

        // ---------- toolbar
        prevButton  = new JButton("\u25C0");  // ◀
        nextButton  = new JButton("\u25B6");  // ▶
        pageLabel   = new JLabel("", SwingConstants.CENTER);
        goToButton  = new JButton("Go to");
        overlayCheck = new JCheckBox("Info", settings.isShowInfoOverlay());

        prevButton.setFocusable(false);
        nextButton.setFocusable(false);
        goToButton.setFocusable(false);
        overlayCheck.setFocusable(false);

        pageLabel.setForeground(secondaryForeground);
        pageLabel.setToolTipText("Click to jump to a page");
        overlayCheck.setForeground(secondaryForeground);
        overlayCheck.setOpaque(false);
        overlayCheck.setBorderPainted(false);

        toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        toolbar.setBackground(toolbarBackground);
        toolbar.add(prevButton);
        toolbar.add(pageLabel);
        toolbar.add(nextButton);
        toolbar.add(goToButton);
        toolbar.add(Box.createHorizontalStrut(16));
        toolbar.add(overlayCheck);
        add(toolbar, BorderLayout.SOUTH);

        // ---------- canvas
        pageCanvas = new PageCanvas();
        add(pageCanvas, BorderLayout.CENTER);

        // ---------- listeners
        prevButton.addActionListener(e -> navigatePage(-1));
        nextButton.addActionListener(e -> navigatePage(1));
        goToButton.addActionListener(e -> promptForPage());
        overlayCheck.addActionListener(e -> {
            settings.setShowInfoOverlay(overlayCheck.isSelected());
            pageCanvas.repaint();
        });
        pageLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                promptForPage();
            }
        });

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_LEFT  || code == KeyEvent.VK_UP   || code == KeyEvent.VK_PAGE_UP)   navigatePage(-1);
                if (code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_PAGE_DOWN) navigatePage(+1);
                if (code == KeyEvent.VK_HOME) goToPage(0);
                if (code == KeyEvent.VK_END && currentInfo != null) goToPage(currentInfo.pageCount() - 1);
                if (code == KeyEvent.VK_G && e.isControlDown()) promptForPage();
            }
        });

        updateNavigation();
    }

    public void applyTheme(NuclrThemeScheme theme) {
        // The active FlatLaf theme is reflected in UIManager defaults (the base look-and-feel
        // is rebuilt before plugins are notified). The scheme's own palette only carries a
        // handful of overrides, so UIManager is the authoritative source for these keys; fall
        // back to the scheme palette and finally to the existing hardcoded colors.
        canvasBackground    = themeColor(theme, "Panel.background", canvasBackground);
        toolbarBackground   = themeColor(theme, "TableHeader.background", toolbarBackground);
        secondaryForeground = themeColor(theme, "Label.foreground", secondaryForeground);
        Color overlayBase   = themeColor(theme, "PopupMenu.background", overlayBackground);
        overlayBackground   = new Color(
                overlayBase.getRed(),
                overlayBase.getGreen(),
                overlayBase.getBlue(),
                168);
        overlayForeground   = themeColor(theme, "Panel.foreground", overlayForeground);

        setBackground(canvasBackground);
        toolbar.setBackground(toolbarBackground);
        pageLabel.setForeground(secondaryForeground);
        overlayCheck.setForeground(secondaryForeground);
        pageCanvas.setBackground(canvasBackground);

        Font defaultFont = theme != null ? theme.defaultFont() : UIManager.getFont("defaultFont");
        if (defaultFont != null) {
            pageLabel.setFont(defaultFont);
            overlayCheck.setFont(defaultFont);
            prevButton.setFont(defaultFont);
            nextButton.setFont(defaultFont);
            goToButton.setFont(defaultFont);
        }

        repaint();
    }

    /**
     * Resolve a themed color, preferring the live {@link UIManager} defaults (which reflect the
     * active FlatLaf theme), then the scheme's override palette, then {@code fallback}.
     */
    private static Color themeColor(NuclrThemeScheme theme, String key, Color fallback) {
        Color fromUi = UIManager.getColor(key);
        if (fromUi != null) {
            // Strip any UIResource wrapper so later mutations/comparisons are plain Colors.
            return new Color(fromUi.getRed(), fromUi.getGreen(), fromUi.getBlue(), fromUi.getAlpha());
        }
        return theme != null ? theme.color(key, fallback) : fallback;
    }

    // ============================================================ public API

    /**
     * Load a new PDF item. Called from the framework (EDT).
     * Returns true always; actual success/failure is reported asynchronously.
     */
    public boolean load(NuclrResource item, AtomicBoolean cancelled) {
        if (cancelled.get()) return false;
        requestFocusInWindow();
        setLoading();
        renderService.loadDocument(item, this::onRenderResult, this::onError, cancelled);
        return true;
    }

    /** Reset the panel to blank state, cancelling any in-flight work. */
    public void clear() {
        renderService.close();
        currentInfo      = null;
        currentImage     = null;
        currentPageIndex = 0;
        statusMessage    = "No PDF selected";
        pageCanvas.resetView();
        pageCanvas.repaint();
        updateNavigation();
    }

    // ============================================================ private helpers

    private void setLoading() {
        // Called from EDT
        statusMessage    = "Loading\u2026";
        currentImage     = null;
        currentInfo      = null;
        currentPageIndex = 0;
        pageCanvas.repaint();
        updateNavigation();
    }

    /** Invoked on EDT by the render service. */
    private void onRenderResult(PdfRenderService.RenderResult result) {
        assert SwingUtilities.isEventDispatchThread();
        currentInfo      = result.info();
        currentImage     = result.image();
        currentPageIndex = result.pageIndex();
        statusMessage    = null;
        pageCanvas.resetView();
        pageCanvas.repaint();
        updateNavigation();
    }

    /** Invoked on EDT by the render service. */
    private void onError(String msg) {
        assert SwingUtilities.isEventDispatchThread();
        statusMessage = msg;
        currentImage  = null;
        pageCanvas.repaint();
        updateNavigation();
    }

    private void navigatePage(int delta) {
        if (currentInfo == null) return;
        goToPage(currentPageIndex + delta);
    }

    private void goToPage(int pageIndex) {
        if (currentInfo == null) return;
        int bounded = Math.max(0, Math.min(pageIndex, currentInfo.pageCount() - 1));
        if (bounded == currentPageIndex) return;
        currentPageIndex = bounded;
        statusMessage    = "Loading\u2026";
        pageCanvas.repaint();
        updateNavigation();
        renderService.renderPage(bounded, this::onRenderResult, this::onError);
    }

    private void promptForPage() {
        if (currentInfo == null) {
            return;
        }

        int totalPages = currentInfo.pageCount();
        String input = JOptionPane.showInputDialog(
                this,
                "Enter page number (1-" + totalPages + ")",
                Integer.toString(currentPageIndex + 1));

        if (input == null) {
            return;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            showPageValidationError(totalPages);
            return;
        }

        try {
            int requestedPage = Integer.parseInt(trimmed);
            if (requestedPage < 1 || requestedPage > totalPages) {
                showPageValidationError(totalPages);
                return;
            }
            goToPage(requestedPage - 1);
        } catch (NumberFormatException ex) {
            showPageValidationError(totalPages);
        }
    }

    private void showPageValidationError(int totalPages) {
        JOptionPane.showMessageDialog(
                this,
                "Enter a page number between 1 and " + totalPages + ".",
                "Invalid Page",
                JOptionPane.WARNING_MESSAGE);
    }

    private void updateNavigation() {
        boolean hasDoc = currentInfo != null;
        int total = hasDoc ? currentInfo.pageCount() : 0;
        prevButton.setEnabled(hasDoc && currentPageIndex > 0);
        nextButton.setEnabled(hasDoc && currentPageIndex < total - 1);
        goToButton.setEnabled(hasDoc);
        pageLabel.setText(hasDoc ? "Page " + (currentPageIndex + 1) + " / " + total : "");
    }

    // ============================================================ inner canvas

    private class PageCanvas extends JPanel {

        private static final double MIN_ZOOM  = 1.0;
        private static final double MAX_ZOOM  = 16.0;
        private static final double ZOOM_STEP = 1.15;

        /** Zoom multiplier applied on top of the fit-to-panel scale. 1.0 == fit. */
        private double zoom = 1.0;
        /** Pan offset (in panel pixels) relative to the centered position. */
        private double offsetX = 0.0;
        private double offsetY = 0.0;
        private Point  lastDragPoint;

        PageCanvas() {
            setBackground(canvasBackground);
            setOpaque(true);

            addMouseWheelListener(this::handleMouseWheel);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    // Keep keyboard navigation working after interacting with the page.
                    PdfQuickViewPanel.this.requestFocusInWindow();
                    if (SwingUtilities.isLeftMouseButton(e) && isZoomed()) {
                        lastDragPoint = e.getPoint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    lastDragPoint = null;
                    updateCursor();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (lastDragPoint == null) return;
                    offsetX += e.getX() - lastDragPoint.x;
                    offsetY += e.getY() - lastDragPoint.y;
                    lastDragPoint = e.getPoint();
                    clampOffsets();
                    repaint();
                }
            });
        }

        /** Reset zoom and pan back to the fit view. Called whenever a new page is shown. */
        void resetView() {
            zoom          = 1.0;
            offsetX       = 0.0;
            offsetY       = 0.0;
            lastDragPoint = null;
            updateCursor();
        }

        private boolean isZoomed() {
            return currentImage != null && zoom > MIN_ZOOM;
        }

        private void updateCursor() {
            setCursor(Cursor.getPredefinedCursor(isZoomed() ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        /** Fit-to-panel scale (contain), capped so the page is never upscaled at zoom 1.0. */
        private double baseScale() {
            if (currentImage == null) return 1.0;
            int panelW = getWidth();
            int panelH = getHeight();
            int imgW   = currentImage.getWidth();
            int imgH   = currentImage.getHeight();
            if (panelW <= 0 || panelH <= 0 || imgW <= 0 || imgH <= 0) return 1.0;
            double fit = Math.min((double) panelW / imgW, (double) panelH / imgH);
            return Math.min(1.0, fit);
        }

        private void handleMouseWheel(MouseWheelEvent e) {
            // Only zoom while Ctrl is held; otherwise leave the event alone.
            if (currentImage == null || !e.isControlDown()) return;

            double base    = baseScale();
            double oldZoom = zoom;
            // Wheel up (negative rotation) zooms in, wheel down zooms out.
            double factor  = Math.pow(ZOOM_STEP, -e.getPreciseWheelRotation());
            double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, oldZoom * factor));

            // Snap to actual size (100%) when the on-screen scale lands near it (96%–104%).
            double snappedScale = base * newZoom;
            if (snappedScale >= 0.96 && snappedScale <= 1.04) {
                newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 1.0 / base));
            }
            if (newZoom == oldZoom) return;

            // Keep the page point under the cursor anchored while zooming.
            double oldScale = base * oldZoom;
            double newScale = base * newZoom;
            int imgW = currentImage.getWidth();
            int imgH = currentImage.getHeight();

            double oldImgX = (getWidth()  - imgW * oldScale) / 2.0 + offsetX;
            double oldImgY = (getHeight() - imgH * oldScale) / 2.0 + offsetY;
            double pixelX  = (e.getX() - oldImgX) / oldScale;
            double pixelY  = (e.getY() - oldImgY) / oldScale;

            zoom    = newZoom;
            offsetX = e.getX() - pixelX * newScale - (getWidth()  - imgW * newScale) / 2.0;
            offsetY = e.getY() - pixelY * newScale - (getHeight() - imgH * newScale) / 2.0;

            clampOffsets();
            updateCursor();
            repaint();
        }

        /** Constrain the pan offset so a zoomed page can't be dragged completely off-screen. */
        private void clampOffsets() {
            if (currentImage == null) {
                offsetX = 0.0;
                offsetY = 0.0;
                return;
            }
            double scale = baseScale() * zoom;
            double drawW = currentImage.getWidth()  * scale;
            double drawH = currentImage.getHeight() * scale;
            double maxX  = Math.max(0.0, (drawW - getWidth())  / 2.0);
            double maxY  = Math.max(0.0, (drawH - getHeight()) / 2.0);
            offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
            offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                if (statusMessage != null) {
                    drawCenteredMessage(g2, statusMessage);
                    return;
                }

                if (currentImage != null) {
                    double scale = drawPageImage(g2, currentImage);
                    if (settings.isShowInfoOverlay() && currentInfo != null) {
                        drawInfoOverlay(g2, currentInfo);
                    }
                    drawZoomIndicator(g2, scale);
                }
            } finally {
                g2.dispose();
            }
        }

        // ---- drawing helpers

        /** Draws the page with the current fit-scale, zoom and pan; returns the on-screen scale. */
        private double drawPageImage(Graphics2D g2, BufferedImage img) {
            int panelW = getWidth();
            int panelH = getHeight();
            if (panelW <= 0 || panelH <= 0) return 1.0;

            int imgW = img.getWidth();
            int imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) return 1.0;

            // Fit-inside scale (never upscaling at zoom 1.0), with the user zoom on top.
            double scale = baseScale() * zoom;

            int drawW = (int) Math.round(imgW * scale);
            int drawH = (int) Math.round(imgH * scale);
            int x     = (panelW - drawW) / 2 + (int) Math.round(offsetX);
            int y     = (panelH - drawH) / 2 + (int) Math.round(offsetY);

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    scale < 1.0 ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(img, x, y, drawW, drawH, null);
            return scale;
        }

        /** Draws the current on-screen scale (relative to the rendered page pixels) bottom-right. */
        private void drawZoomIndicator(Graphics2D g2, double scale) {
            String text = Math.round(scale * 100) + "%";

            Font font = getFont().deriveFont(Font.PLAIN, 11f);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics(font);

            int padX = 8;
            int padY = 4;
            int boxW = fm.stringWidth(text) + padX * 2;
            int boxH = fm.getAscent() + fm.getDescent() + padY * 2;
            int margin = 10;
            int bx = getWidth()  - boxW - margin;
            int by = getHeight() - boxH - margin;

            g2.setColor(overlayBackground);
            g2.fillRoundRect(bx, by, boxW, boxH, 8, 8);

            g2.setColor(overlayForeground);
            g2.drawString(text, bx + padX, by + padY + fm.getAscent());
        }

        private void drawCenteredMessage(Graphics2D g2, String msg) {
            g2.setColor(secondaryForeground);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth()  - fm.stringWidth(msg)) / 2;
            int y = (getHeight() + fm.getAscent())        / 2;
            g2.drawString(msg, x, y);
        }

        private void drawInfoOverlay(Graphics2D g2, PdfDocumentInfo info) {
            String[] lines = buildOverlayLines(info);
            if (lines.length == 0) return;

            Font       font = getFont().deriveFont(Font.PLAIN, 11f);
            g2.setFont(font);
            FontMetrics fm  = g2.getFontMetrics(font);

            int lineH  = fm.getHeight();
            int pad    = 7;
            int boxW   = 0;
            for (String line : lines) boxW = Math.max(boxW, fm.stringWidth(line));
            boxW  += pad * 2;
            int boxH = lines.length * lineH + pad;

            int margin = 10;
            int bx = margin;
            int by = margin;

            // Semi-transparent background
            g2.setColor(overlayBackground);
            g2.fillRoundRect(bx, by, boxW, boxH, 8, 8);

            // Text
            g2.setColor(overlayForeground);
            for (int i = 0; i < lines.length; i++) {
                g2.drawString(lines[i], bx + pad, by + pad + fm.getAscent() + i * lineH);
            }
        }

        private String[] buildOverlayLines(PdfDocumentInfo info) {
            List<String> lines = new ArrayList<>();
            if (info.title()  != null) lines.add("Title:   " + clip(info.title(),  28));
            if (info.author() != null) lines.add("Author:  " + clip(info.author(), 28));
            lines.add("Pages:   " + info.pageCount());
            lines.add("Page:    " + (currentPageIndex + 1) + " / " + info.pageCount());
            if (info.pdfVersion() != null) lines.add("Version: " + info.pdfVersion());
            lines.add("DPI:     " + settings.getDpi());
            return lines.toArray(String[]::new);
        }

        private static String clip(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
        }
    }
}
