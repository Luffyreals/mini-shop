package com.minishop.desktop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The parts of image loading that need no JavaFX toolkit: the cache key, the LRU cache and the URL resolution of
 * {@link ImageLoader#resolve}. Loading an actual {@code Image} is covered by the toolkit tests.
 */
class ImageCacheTest {

    // ------------------------------------------------------------------ ImageKey

    @Test
    void imageKey_roundsSizesUpToWholePixels() {
        assertThat(ImageKey.of("u", 100.2, 50.0)).isEqualTo(new ImageKey("u", 101, 50));
        assertThat(ImageKey.of("u", 99.0, 0.5)).isEqualTo(new ImageKey("u", 99, 1));
    }

    @Test
    void imageKey_zeroNegativeAndNaNSizesMeanOriginalSize() {
        assertThat(ImageKey.of("u", 0, 0)).isEqualTo(new ImageKey("u", 0, 0));
        assertThat(ImageKey.of("u", -5, -1)).isEqualTo(new ImageKey("u", 0, 0));
        assertThat(ImageKey.of("u", Double.NaN, Double.NaN)).isEqualTo(new ImageKey("u", 0, 0));
    }

    @Test
    void imageKey_sameImageAtTwoSizesIsTwoKeys_andSameSizesAreEqual() {
        assertThat(ImageKey.of("u", 100, 100)).isNotEqualTo(ImageKey.of("u", 200, 200));
        assertThat(ImageKey.of("u", 100, 100)).isNotEqualTo(ImageKey.of("v", 100, 100));
        assertThat(ImageKey.of("u", 99.5, 99.5)).isEqualTo(ImageKey.of("u", 100, 100))
                .hasSameHashCodeAs(ImageKey.of("u", 100, 100));
    }

    // ------------------------------------------------------------------ LruCache

    @Test
    void lruCache_overTheLimit_evictsTheLeastRecentlyUsedEntry() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);

        cache.put("c", 3);

        assertThat(cache).containsOnlyKeys("b", "c");
    }

    @Test
    void lruCache_aReadCountsAsUse() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");

        cache.put("c", 3);

        assertThat(cache).containsOnlyKeys("a", "c");
    }

    @Test
    void lruCache_replacingAKeyDoesNotGrowOrEvict() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);

        cache.put("a", 10);

        assertThat(cache).hasSize(2).containsEntry("a", 10).containsEntry("b", 2);
    }

    @Test
    void lruCache_withLimitBelowOne_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LruCache<String, String>(0));
    }

    @Test
    void imageLoader_cacheLimitIs200() {
        assertThat(ImageLoader.MAX_ENTRIES).isEqualTo(200);
    }

    // ------------------------------------------------------------------ ImageLoader.resolve

    @Test
    void resolve_nullAndBlank_giveNull() {
        assertThat(ImageLoader.resolve(null)).isNull();
        assertThat(ImageLoader.resolve("")).isNull();
        assertThat(ImageLoader.resolve("   ")).isNull();
    }

    @Test
    void resolve_urlsWithAScheme_areKeptAsTheyAreAfterTrimming() {
        assertThat(ImageLoader.resolve("file:/C:/data/uploads/a.jpg")).isEqualTo("file:/C:/data/uploads/a.jpg");
        assertThat(ImageLoader.resolve("  https://example.com/a.png ")).isEqualTo("https://example.com/a.png");
        assertThat(ImageLoader.resolve("jar:file:/app.jar!/images/a.jpg")).isEqualTo("jar:file:/app.jar!/images/a.jpg");
        assertThat(ImageLoader.resolve("data:image/png;base64,AAAA")).isEqualTo("data:image/png;base64,AAAA");
    }

    @Test
    void resolve_aSeedImagePath_becomesTheClasspathUrl() {
        String resolved = ImageLoader.resolve("/images/products/ao-thun-cotton-co-tron.jpg");

        assertThat(resolved).isNotNull().endsWith("/images/products/ao-thun-cotton-co-tron.jpg");
        assertThat(resolved).matches("^(file|jar):.*");
    }

    @Test
    void resolve_aClasspathPathWithoutLeadingSlash_isTheSameAsWithIt() {
        assertThat(ImageLoader.resolve("images/products/ao-thun-cotton-co-tron.jpg"))
                .isEqualTo(ImageLoader.resolve("/images/products/ao-thun-cotton-co-tron.jpg"));
    }

    @Test
    void resolve_aMissingResource_givesNull() {
        assertThat(ImageLoader.resolve("/images/products/does-not-exist.jpg")).isNull();
    }
}
