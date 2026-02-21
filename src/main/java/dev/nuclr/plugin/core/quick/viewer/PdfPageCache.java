package dev.nuclr.plugin.core.quick.viewer;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe LRU cache for rendered PDF page images.
 * Key: (documentId, pageIndex, dpi).
 */
public final class PdfPageCache {

    public record Key(String documentId, int pageIndex, float dpi) {}

    private final LinkedHashMap<Key, BufferedImage> cache;

    public PdfPageCache(int maxEntries) {
        int max = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, BufferedImage> eldest) {
                return size() > max;
            }
        };
    }

    public synchronized BufferedImage get(Key key) {
        return cache.get(key);
    }

    public synchronized void put(Key key, BufferedImage image) {
        cache.put(key, image);
    }

    public synchronized void invalidate(String documentId) {
        cache.keySet().removeIf(k -> k.documentId().equals(documentId));
    }

    public synchronized void clear() {
        cache.clear();
    }
}
