package com.minishop.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cửa sổ trang và link trước/sau của {@link PageNav} (AC-002-8). */
class PageNavTest {

    private static String url(int page) {
        return "/p?page=" + page;
    }

    @Test
    void of_whenSinglePage_hasOneCurrentLinkAndNoPreviousOrNext() {
        PageNav nav = PageNav.of(1, 1, PageNavTest::url);

        assertThat(nav.links()).hasSize(1);
        assertThat(nav.links().get(0).current()).isTrue();
        assertThat(nav.previousUrl()).isNull();
        assertThat(nav.nextUrl()).isNull();
    }

    @Test
    void of_whenThreePagesOnFirst_hasNoPreviousButNext() {
        PageNav nav = PageNav.of(1, 3, PageNavTest::url);

        assertThat(nav.links()).extracting(PageNav.PageLink::number).containsExactly(1, 2, 3);
        assertThat(nav.links()).extracting(PageNav.PageLink::current).containsExactly(true, false, false);
        assertThat(nav.previousUrl()).isNull();
        assertThat(nav.nextUrl()).isEqualTo("/p?page=2");
    }

    @Test
    void of_whenThreePagesOnSecond_hasBothPreviousAndNext() {
        PageNav nav = PageNav.of(2, 3, PageNavTest::url);

        assertThat(nav.links()).extracting(PageNav.PageLink::current).containsExactly(false, true, false);
        assertThat(nav.previousUrl()).isEqualTo("/p?page=1");
        assertThat(nav.nextUrl()).isEqualTo("/p?page=3");
    }

    @Test
    void of_whenThreePagesOnLast_hasPreviousButNoNext() {
        PageNav nav = PageNav.of(3, 3, PageNavTest::url);

        assertThat(nav.links()).extracting(PageNav.PageLink::current).containsExactly(false, false, true);
        assertThat(nav.previousUrl()).isEqualTo("/p?page=2");
        assertThat(nav.nextUrl()).isNull();
    }

    @Test
    void of_whenTwentyPagesOnTenth_windowIsEightToTwelve() {
        PageNav nav = PageNav.of(10, 20, PageNavTest::url);

        assertThat(nav.links()).extracting(PageNav.PageLink::number).containsExactly(8, 9, 10, 11, 12);
        assertThat(nav.links()).filteredOn(PageNav.PageLink::current).extracting(PageNav.PageLink::number)
                .containsExactly(10);
        assertThat(nav.links().get(0).url()).isEqualTo("/p?page=8");
    }

    @Test
    void of_whenManyPagesOnFirst_windowIsOneToFive() {
        PageNav nav = PageNav.of(1, 20, PageNavTest::url);

        assertThat(nav.links()).extracting(PageNav.PageLink::number).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void of_whenManyPagesOnLast_windowIsLastFive() {
        PageNav nav = PageNav.of(20, 20, PageNavTest::url);

        assertThat(nav.links()).extracting(PageNav.PageLink::number).containsExactly(16, 17, 18, 19, 20);
        assertThat(nav.nextUrl()).isNull();
    }

    @Test
    void of_whenCurrentBeyondTotal_clampsToLastPage() {
        PageNav nav = PageNav.of(9, 3, PageNavTest::url);

        assertThat(nav.current()).isEqualTo(3);
        assertThat(nav.nextUrl()).isNull();
        assertThat(nav.previousUrl()).isEqualTo("/p?page=2");
    }

    @Test
    void of_whenNoPages_hasNoLinks() {
        PageNav nav = PageNav.of(1, 0, PageNavTest::url);

        assertThat(nav.links()).isEmpty();
        assertThat(nav.previousUrl()).isNull();
        assertThat(nav.nextUrl()).isNull();
    }
}
