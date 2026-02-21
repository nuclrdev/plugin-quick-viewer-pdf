package dev.nuclr.plugin.core.quick.viewer;

import dev.nuclr.plugin.QuickViewItem;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

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
    private final JLabel    pageLabel;
    private final JCheckBox overlayCheck;
    private final PageCanvas pageCanvas;

    // ============================================================ constructor

    public PdfQuickViewPanel() {
        this.renderService = new PdfRenderService();
        setLayout(new BorderLayout());
        setBackground(Color.BLACK);
        setFocusable(true);

        // ---------- toolbar
        prevButton  = new JButton("\u25C0");  // ◀
        nextButton  = new JButton("\u25B6");  // ▶
        pageLabel   = new JLabel("", SwingConstants.CENTER);
        overlayCheck = new JCheckBox("Info", settings.isShowInfoOverlay());

        prevButton.setFocusable(false);
        nextButton.setFocusable(false);
        overlayCheck.setFocusable(false);

        pageLabel.setForeground(new Color(0xAAAAAA));
        overlayCheck.setForeground(new Color(0xAAAAAA));
        overlayCheck.setOpaque(false);
        overlayCheck.setBorderPainted(false);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        toolbar.setBackground(new Color(0x2B2B2B));
        toolbar.add(prevButton);
        toolbar.add(pageLabel);
        toolbar.add(nextButton);
        toolbar.add(Box.createHorizontalStrut(16));
        toolbar.add(overlayCheck);
        add(toolbar, BorderLayout.SOUTH);

        // ---------- canvas
        pageCanvas = new PageCanvas();
        add(pageCanvas, BorderLayout.CENTER);

        // ---------- listeners
        prevButton.addActionListener(e -> navigatePage(-1));
        nextButton.addActionListener(e -> navigatePage(1));
        overlayCheck.addActionListener(e -> {
            settings.setShowInfoOverlay(overlayCheck.isSelected());
            pageCanvas.repaint();
        });

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_LEFT  || code == KeyEvent.VK_UP   || code == KeyEvent.VK_PAGE_UP)   navigatePage(-1);
                if (code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_PAGE_DOWN) navigatePage(+1);
                if (code == KeyEvent.VK_HOME) goToPage(0);
                if (code == KeyEvent.VK_END && currentInfo != null) goToPage(currentInfo.pageCount() - 1);
            }
        });

        updateNavigation();
    }

    // ============================================================ public API

    /**
     * Load a new PDF item. Called from the framework (EDT).
     * Returns true always; actual success/failure is reported asynchronously.
     */
    public boolean load(QuickViewItem item) {
        requestFocusInWindow();
        setLoading();
        renderService.loadDocument(item, this::onRenderResult, this::onError);
        return true;
    }

    /** Reset the panel to blank state, cancelling any in-flight work. */
    public void clear() {
        renderService.close();
        currentInfo      = null;
        currentImage     = null;
        currentPageIndex = 0;
        statusMessage    = "No PDF selected";
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

    private void updateNavigation() {
        boolean hasDoc = currentInfo != null;
        int total = hasDoc ? currentInfo.pageCount() : 0;
        prevButton.setEnabled(hasDoc && currentPageIndex > 0);
        nextButton.setEnabled(hasDoc && currentPageIndex < total - 1);
        pageLabel.setText(hasDoc ? "Page " + (currentPageIndex + 1) + " / " + total : "");
    }

    // ============================================================ inner canvas

    private class PageCanvas extends JPanel {

        PageCanvas() {
            setBackground(Color.BLACK);
            setOpaque(true);
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
                    drawPageImage(g2, currentImage);
                    if (settings.isShowInfoOverlay() && currentInfo != null) {
                        drawInfoOverlay(g2, currentInfo);
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        // ---- drawing helpers

        private void drawPageImage(Graphics2D g2, BufferedImage img) {
            int panelW = getWidth();
            int panelH = getHeight();
            if (panelW <= 0 || panelH <= 0) return;

            int imgW = img.getWidth();
            int imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) return;

            // Fit-inside scaling — never upscale
            double scale = Math.min(1.0,
                    Math.min((double) panelW / imgW, (double) panelH / imgH));

            int drawW = (int) Math.round(imgW * scale);
            int drawH = (int) Math.round(imgH * scale);
            int x     = (panelW - drawW) / 2;
            int y     = (panelH - drawH) / 2;

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    scale < 1.0 ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(img, x, y, drawW, drawH, null);
        }

        private void drawCenteredMessage(Graphics2D g2, String msg) {
            g2.setColor(new Color(0x888888));
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
            g2.setColor(new Color(0, 0, 0, 168));
            g2.fillRoundRect(bx, by, boxW, boxH, 8, 8);

            // Text
            g2.setColor(new Color(0xDDDDDD));
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
