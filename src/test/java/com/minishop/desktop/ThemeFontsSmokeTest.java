package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Theme#tryLoadFonts()} on the real JavaFX toolkit: with the four bundled fonts it reports success and a
 * second call is a no-op.
 *
 * <p>The failure branch (log one WARN, remember the failure) is not tested on purpose: {@code Theme} keeps static
 * state and has no seam to make a font fail, and calling it without the toolkit would make it fail for real and
 * remember {@code fontsFailed} for every later UI test in the same JVM.
 */
@Tag("ui")
class ThemeFontsSmokeTest {

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    @Test
    void tryLoadFonts_withTheBundledFonts_returnsTrueAndIsRepeatable() {
        assertThat(FxTestSupport.callOnFx(Theme::tryLoadFonts)).isTrue();
        assertThat(FxTestSupport.callOnFx(Theme::tryLoadFonts)).isTrue();
    }
}
