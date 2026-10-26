package com.minishop.desktop;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache theo thứ tự truy cập gần nhất: quá {@code maxEntries} thì bỏ mục ít dùng nhất. Java thuần,
 * <b>không an toàn đa luồng</b>; nơi dùng tự đồng bộ ({@link ImageLoader}).
 */
final class LruCache<K, V> extends LinkedHashMap<K, V> {

    private final int maxEntries;

    LruCache(int maxEntries) {
        super(16, 0.75f, true);
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries phải >= 1");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxEntries;
    }
}
