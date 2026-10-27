package com.minishop.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cửa sổ trang và trạng thái trước/sau của {@link PageNav} (AC-002-8). */
class PageNavTest {

    @Test
    void of_whenSinglePage_hasOnePageAndNoPreviousOrNext() {
        PageNav nav = PageNav.of(1, 1);

        assertThat(nav.pages()).containsExactly(1);
        assertThat(nav.current()).isEqualTo(1);
        assertThat(nav.totalPages()).isEqualTo(1);
        assertThat(nav.hasPrevious()).isFalse();
        assertThat(nav.hasNext()).isFalse();
    }

    @Test
    void of_whenThreePagesOnFirst_hasNoPreviousButNext() {
        PageNav nav = PageNav.of(1, 3);

        assertThat(nav.pages()).containsExactly(1, 2, 3);
        assertThat(nav.current()).isEqualTo(1);
        assertThat(nav.hasPrevious()).isFalse();
        assertThat(nav.hasNext()).isTrue();
    }

    @Test
    void of_whenThreePagesOnSecond_hasBothPreviousAndNext() {
        PageNav nav = PageNav.of(2, 3);

        assertThat(nav.pages()).containsExactly(1, 2, 3);
        assertThat(nav.current()).isEqualTo(2);
        assertThat(nav.hasPrevious()).isTrue();
        assertThat(nav.hasNext()).isTrue();
    }

    @Test
    void of_whenThreePagesOnLast_hasPreviousButNoNext() {
        PageNav nav = PageNav.of(3, 3);

        assertThat(nav.current()).isEqualTo(3);
        assertThat(nav.hasPrevious()).isTrue();
        assertThat(nav.hasNext()).isFalse();
    }

    @Test
    void of_whenTwentyPagesOnTenth_windowIsEightToTwelve() {
        PageNav nav = PageNav.of(10, 20);

        assertThat(nav.pages()).containsExactly(8, 9, 10, 11, 12);
        assertThat(nav.current()).isEqualTo(10);
    }

    @Test
    void of_whenManyPagesOnFirst_windowIsOneToFive() {
        assertThat(PageNav.of(1, 20).pages()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void of_whenManyPagesOnLast_windowIsLastFive() {
        PageNav nav = PageNav.of(20, 20);

        assertThat(nav.pages()).containsExactly(16, 17, 18, 19, 20);
        assertThat(nav.hasNext()).isFalse();
        assertThat(nav.hasPrevious()).isTrue();
    }

    @Test
    void of_whenTenPages_windowFollowsCurrentAndStaysWithinBounds() {
        assertThat(PageNav.of(1, 10).pages()).containsExactly(1, 2, 3, 4, 5);
        assertThat(PageNav.of(2, 10).pages()).containsExactly(1, 2, 3, 4, 5);
        assertThat(PageNav.of(5, 10).pages()).containsExactly(3, 4, 5, 6, 7);
        assertThat(PageNav.of(10, 10).pages()).containsExactly(6, 7, 8, 9, 10);
    }

    @Test
    void of_whenFewerPagesThanWindow_listsOnlyExistingPages() {
        assertThat(PageNav.of(2, 4).pages()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void of_whenCurrentBeyondTotal_clampsToLastPage() {
        PageNav nav = PageNav.of(9, 3);

        assertThat(nav.current()).isEqualTo(3);
        assertThat(nav.hasNext()).isFalse();
        assertThat(nav.hasPrevious()).isTrue();
        assertThat(PageNav.of(99, 3).current()).isEqualTo(3);
    }

    @Test
    void of_whenCurrentBelowOne_clampsToFirstPage() {
        PageNav zero = PageNav.of(0, 3);
        PageNav negative = PageNav.of(-3, 3);

        assertThat(zero.current()).isEqualTo(1);
        assertThat(zero.hasPrevious()).isFalse();
        assertThat(negative.current()).isEqualTo(1);
        assertThat(negative.pages()).containsExactly(1, 2, 3);
    }

    @Test
    void of_whenNoPages_hasNoPageNumbersAndNoPreviousOrNext() {
        PageNav nav = PageNav.of(1, 0);

        assertThat(nav.pages()).isEmpty();
        assertThat(nav.totalPages()).isZero();
        assertThat(nav.hasPrevious()).isFalse();
        assertThat(nav.hasNext()).isFalse();
    }

    @Test
    void of_whenNoPagesAndCurrentOutOfRange_keepsTotalZeroAndNeverEnablesNavigation() {
        PageNav beyond = PageNav.of(5, 0);
        PageNav negative = PageNav.of(-3, 0);

        assertThat(beyond.current()).isEqualTo(5);
        assertThat(beyond.pages()).isEmpty();
        assertThat(beyond.hasPrevious()).isFalse();
        assertThat(beyond.hasNext()).isFalse();
        assertThat(negative.current()).isEqualTo(1);
        assertThat(negative.pages()).isEmpty();
    }

    @Test
    void of_whenTotalPagesNegative_treatsItAsZero() {
        PageNav nav = PageNav.of(1, -4);

        assertThat(nav.totalPages()).isZero();
        assertThat(nav.pages()).isEmpty();
        assertThat(nav.hasNext()).isFalse();
    }

    @Test
    void pages_isNotModifiable() {
        PageNav nav = PageNav.of(1, 3);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> nav.pages().add(4))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
