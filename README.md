# PDF Quick Viewer

A [Nuclr Commander](https://nuclr.dev) plugin that renders PDF files directly in the quick-view panel. Press **Ctrl+Q** on any `.pdf` file to preview it without leaving the file manager.

---

## Features

- **Page-accurate rendering** — every page is rasterised to a crisp RGB image via Apache PDFBox
- **Page navigation** — move between pages with on-screen buttons or keyboard shortcuts
- **Info overlay** — semi-transparent HUD showing title, author, page count, PDF version, and render DPI
- **LRU page cache** — recently viewed pages are kept in memory so navigation feels instant
- **Cancellation-aware** — switching files mid-render immediately aborts the in-flight job; no stale frames ever reach the UI
- **Encrypted PDF handling** — password-protected files display a clear message instead of crashing
- **Optional CLI backends** — can delegate rendering to MuPDF, Poppler, or Ghostscript when installed; falls back to PDFBox automatically on any failure
- **Persistent settings** — DPI, overlay toggle, and backend choice survive restarts

---

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `←` / `↑` / `Page Up` | Previous page |
| `→` / `↓` / `Page Down` | Next page |
| `Home` | First page |
| `End` | Last page |

> The quick-view panel must have focus for keyboard shortcuts to work. Click the panel or press **Ctrl+Q** to focus it.

---

## Settings

Settings are stored as a plain `.properties` file in the platform config directory:

| Platform | Path |
|----------|------|
| Windows | `%APPDATA%\nuclr\pdf-quick-viewer.properties` |
| macOS | `~/Library/Application Support/nuclr/pdf-quick-viewer.properties` |
| Linux | `$XDG_CONFIG_HOME/nuclr/pdf-quick-viewer.properties` (or `~/.config/nuclr/`) |

### Available keys

| Key | Default | Description |
|-----|---------|-------------|
| `pdf.quickView.dpi` | `144` | Render resolution. Range: 36–200. Higher values are sharper but slower and use more memory. |
| `pdf.quickView.cachePages` | `10` | Maximum number of rendered pages kept in the LRU cache. |
| `pdf.quickView.showInfoOverlay` | `true` | Show the semi-transparent info panel over the page image. |
| `pdf.quickView.backend` | `PDFBOX` | Rendering backend (see table below). |

### Backends

| Value | Description |
|-------|-------------|
| `PDFBOX` | Apache PDFBox — pure Java, always available, no system tools needed. **Recommended.** |
| `AUTO` | Try CLI tools in preference order (MuPDF → Poppler → Ghostscript); fall back to PDFBox. |
| `CLI_MuTool` | MuPDF `mutool draw`. Requires `mutool` on `PATH`. |
| `CLI_POPPLER` | Poppler `pdftocairo` or `pdftoppm`. Requires Poppler on `PATH`. |
| `CLI_GS` | Ghostscript `gs`. Requires Ghostscript on `PATH`. |

Any CLI backend failure is caught automatically and PDFBox is used as a fallback — the viewer will never go blank due to a missing tool.

**Example** — bump DPI and switch to auto-detect:

```properties
pdf.quickView.dpi=180
pdf.quickView.backend=AUTO
pdf.quickView.showInfoOverlay=true
```

---

## Building

Requires **Java 21+** and **Maven 3.9+**. The plugin SDK must be installed first:

```bash
# 1. Install the SDK (one-time)
cd ../../..   # → nuclr/sources/plugins-sdk
mvn clean install

# 2. Build the plugin ZIP (no signing)
cd plugins/core/quick-viewer-pdf
mvn clean package -Dmaven.verify.skip=true
# Output: target/quick-view-pdf-1.0.0.zip
```

### Building with signing

Signing requires the Nuclr keystore at `C:/nuclr/key/nuclr-signing.p12`:

```bash
mvn clean verify -Djarsigner.storepass=<password>
# Output: target/quick-view-pdf-1.0.0.zip
#         target/quick-view-pdf-1.0.0.zip.sig
```

### Deploy to Commander

```batch
deploy.bat
```

This runs `mvn clean verify` and copies the signed ZIP and `.sig` to `commander/plugins/`.

---

## Architecture

```
PdfQuickViewProvider          implements QuickViewProvider
└── PdfQuickViewPanel         Swing JPanel — all state is EDT-only
    └── PdfRenderService      virtual-thread orchestrator
        ├── PdfPageCache      thread-safe LRU (LinkedHashMap, access-order)
        ├── PdfSettings       singleton — java.util.Properties persistence
        └── backend/
            ├── PdfRenderBackend   strategy interface
            ├── PdfboxBackend      Apache PDFBox 3.x (default)
            └── CliBackend         MuPDF / Poppler / Ghostscript
```

### Threading model

- **EDT** — UI state reads/writes, Swing repaints, button callbacks
- **Virtual threads** (`Thread.ofVirtual()`) — byte reading, document opening, page rendering
- **Cancellation** — a monotonic `AtomicLong` epoch is incremented on every new request; any virtual thread that finishes late sees the stale epoch and silently discards its result
- **Backend lock** — a `ReentrantLock` serialises `openDocument` and `renderPage` calls on the active backend, preventing concurrent access to a non-thread-safe `PDFRenderer`

### Bundled dependencies (in `lib/`)

| Artifact | Version | License |
|----------|---------|---------|
| `pdfbox` | 3.0.3 | Apache 2.0 |
| `pdfbox-io` | 3.0.3 | Apache 2.0 |
| `fontbox` | 3.0.3 | Apache 2.0 |
| `commons-logging` | 1.3.3 | Apache 2.0 |

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
