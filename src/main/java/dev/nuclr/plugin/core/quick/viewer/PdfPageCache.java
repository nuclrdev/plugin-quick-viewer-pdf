package dev.nuclr.plugin.core.quick.viewer;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe LRU cache for rendered PDF page images.
 *
 * Eviction is driven by a byte budget (default 64 MB) so that large-format
 * pages don't fill the heap just by navigating a few pages, plus a secondary
 * entry-count cap inherited from settings.
 *
 * Key: (documentId, pageIndex, dpi).
 */
public final class PdfPageCache {

    public record Key(String documentId, int pageIndex, float dpi) {}

    private static final long MAX_BYTES = 64L * 1024 * 1024; // 64 MB total budget

    private final int maxEntries;
    private long cachedBytes;
    private final LinkedHashMap<Key, BufferedImage> cache;

    public PdfPageCache(int maxEntries) {
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true /* access-order */);
    }

    public synchronized BufferedImage get(Key key) {
        return cache.get(key);
    }

    public synchronized void put(Key key, BufferedImage img) {
        if (img == null) return;
        // Replace existing entry without double-counting its bytes.
        BufferedImage prev = cache.remove(key);
        if (prev != null) cachedBytes -= imageBytes(prev);

        long newBytes = imageBytes(img);
        // Evict LRU entries until the new image fits within both limits.
        var it = cache.entrySet().iterator();
        while (it.hasNext() && (cache.size() >= maxEntries || cachedBytes + newBytes > MAX_BYTES)) {
            Map.Entry<Key, BufferedImage> lru = it.next();
            cachedBytes -= imageBytes(lru.getValue());
            it.remove();
        }
        cache.put(key, img);
        cachedBytes += newBytes;
    }

    public synchronized void invalidate(String documentId) {
        cache.entrySet().removeIf(e -> {
            if (e.getKey().documentId().equals(documentId)) {
                cachedBytes -= imageBytes(e.getValue());
                return true;
            }
            return false;
        });
    }

    public synchronized void clear() {
        cache.clear();
        cachedBytes = 0;
    }

    private static long imageBytes(BufferedImage img) {
        int channels = switch (img.getType()) {
            case BufferedImage.TYPE_BYTE_GRAY  -> 1;
            case BufferedImage.TYPE_3BYTE_BGR  -> 3;
            default                            -> 4; // INT_RGB, INT_ARGB, etc.
        };
        return (long) img.getWidth() * img.getHeight() * channels;
    }
}
