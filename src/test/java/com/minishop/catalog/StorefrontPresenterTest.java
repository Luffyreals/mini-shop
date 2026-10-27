package com.minishop.catalog;

import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellMenuEntry;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.support.ManualBackgroundTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link StorefrontPresenter}: what the store adds to the top bar (AC-009-1, QĐ6 of the D3 plan). No JavaFX toolkit:
 * the shell presenter is the real one, the navigator and service are mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorefrontPresenterTest {

    private static final List<CategoryOption> CATEGORIES = List.of(
            new CategoryOption(2L, "Điện tử"), new CategoryOption(1L, "Sách"));

    @Mock
    private ProductService productService;
    @Mock
    private Navigator navigator;
    @Mock
    private UnexpectedErrors errors;

    private final ShellPresenter shell = new ShellPresenter();
    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(productService.categories()).thenReturn(CATEGORIES);
    }

    private StorefrontPresenter presenter() {
        return new StorefrontPresenter(productService, navigator, direct, errors);
    }

    private ShellMenuEntry menuEntry(String id) {
        return shell.categoriesMenuEntries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ contribute

    @Test
    void contribute_enablesStoreNewArrivalsCategoriesAndSearch() {
        presenter().contribute(shell);

        assertThat(shell.enabledBinding(ShellAction.STORE).get()).isTrue();
        assertThat(shell.enabledBinding(ShellAction.NEW_ARRIVALS).get()).isTrue();
        assertThat(shell.enabledBinding(ShellAction.CATEGORIES).get()).isTrue();
        assertThat(shell.searchEnabledBinding().get()).isTrue();
    }

    @Test
    void contribute_doesNotEnableTheCartOrAccountButtons() {
        presenter().contribute(shell);

        assertThat(shell.enabledBinding(ShellAction.CART).get()).isFalse();
        assertThat(shell.enabledBinding(ShellAction.ACCOUNT).get()).isFalse();
    }

    @Test
    void contribute_setsTheHomeRouteAndOpensIt() {
        presenter().contribute(shell);

        verify(navigator).setHomeRoute(new HomeRoute());
        verify(navigator).goHome();
    }

    @Test
    void storeButton_goesHome() {
        presenter().contribute(shell);

        shell.trigger(ShellAction.STORE);

        // once at start-up, once for the click
        verify(navigator, times(2)).goHome();
    }

    @Test
    void newArrivalsButton_opensTheFullListNewestFirst() {
        presenter().contribute(shell);

        shell.trigger(ShellAction.NEW_ARRIVALS);

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void contribute_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter().contribute(null));
    }

    // ------------------------------------------------------------------ categories menu

    @Test
    void contribute_putsAllCategoriesInTheMenuAtOnce_thenTheFullListAfterLoading() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        StorefrontPresenter presenter = new StorefrontPresenter(productService, navigator, manual, errors);

        presenter.contribute(shell);

        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("categoryMenuAll");
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::label)
                .containsExactly("Tất cả danh mục");
        assertThat(shell.enabledBinding(ShellAction.CATEGORIES).get()).isTrue();

        manual.runAll();

        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-2", "categoryMenu-1");
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::label)
                .containsExactly("Tất cả danh mục", "Điện tử", "Sách");
    }

    @Test
    void categoriesMenu_allCategoriesEntry_opensTheFullList() {
        presenter().contribute(shell);

        menuEntry("categoryMenuAll").action().run();

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void categoriesMenu_aCategoryEntry_opensThatCategoryOnPageOne() {
        presenter().contribute(shell);

        menuEntry("categoryMenu-2").action().run();

        verify(navigator).navigateTo(CatalogRoute.inCategory(2L));
    }

    @Test
    void updateCategories_replacesTheMenuAndKeepsTheOrderGiven() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);

        presenter.updateCategories(List.of(new CategoryOption(5L, "Thể thao")));

        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::label)
                .containsExactly("Tất cả danh mục", "Thể thao");
    }

    @Test
    void updateCategories_skipsCategoriesWithoutAName() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);

        presenter.updateCategories(List.of(new CategoryOption(5L, " "), new CategoryOption(6L, null),
                new CategoryOption(7L, "Sách")));

        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-7");
    }

    @Test
    void updateCategories_withAnEmptyList_leavesOnlyTheAllEntry() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);

        presenter.updateCategories(List.of());

        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("categoryMenuAll");
    }

    @Test
    void updateCategories_beforeContribute_doesNothing() {
        StorefrontPresenter presenter = presenter();

        presenter.updateCategories(CATEGORIES);

        assertThat(shell.categoriesMenuEntries()).isEmpty();
    }

    @Test
    void contribute_whenTheCategoriesCannotBeLoaded_reportsOnceAndDoesNotThrow() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.categories()).thenThrow(failure);
        StorefrontPresenter presenter = presenter();

        assertThatNoException().isThrownBy(() -> presenter.contribute(shell));

        verify(errors).report(failure);
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("categoryMenuAll");
        assertThat(shell.enabledBinding(ShellAction.CATEGORIES).get()).isTrue();
        verify(navigator).goHome();
    }

    // ------------------------------------------------------------------ refresh (CategoriesMenu, QĐ11 of the D5 plan)

    @Test
    void presenter_isTheCategoriesMenuPortTheAdminScreensCall() {
        assertThat(presenter()).isInstanceOf(CategoriesMenu.class);
    }

    @Test
    void refresh_reloadsTheCategoriesAndUpdatesTheMenu() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);
        when(productService.categories()).thenReturn(List.of(new CategoryOption(9L, "Thể thao"),
                new CategoryOption(2L, "Điện tử"), new CategoryOption(1L, "Sách")));

        presenter.refresh();

        verify(productService, times(2)).categories();   // once at start-up, once for the refresh
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-9", "categoryMenu-2", "categoryMenu-1");
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::label)
                .containsExactly("Tất cả danh mục", "Thể thao", "Điện tử", "Sách");
    }

    @Test
    void refresh_afterADeletedCategory_dropsItFromTheMenu() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);
        when(productService.categories()).thenReturn(List.of(new CategoryOption(1L, "Sách")));

        presenter.refresh();

        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-1");
    }

    @Test
    void refresh_theEntriesOfTheNewMenuStillNavigate() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);
        when(productService.categories()).thenReturn(List.of(new CategoryOption(9L, "Thể thao")));
        presenter.refresh();

        menuEntry("categoryMenu-9").action().run();

        verify(navigator).navigateTo(CatalogRoute.inCategory(9L));
    }

    @Test
    void refresh_whileTheMenuIsLoading_cancelsThePreviousLoad_soAnOldResultNeverOverwritesANewOne() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        StorefrontPresenter presenter = new StorefrontPresenter(productService, navigator, manual, errors);
        presenter.contribute(shell);   // task 0: the start-up load
        when(productService.categories()).thenReturn(List.of(new CategoryOption(9L, "Thể thao")));

        presenter.refresh();           // task 1 replaces it
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(manual.handle(1).isCancelled()).isFalse();
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-9");
    }

    @Test
    void refresh_whenAnEarlierRefreshAlreadyFinished_cancellingItIsHarmless() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        StorefrontPresenter presenter = new StorefrontPresenter(productService, navigator, manual, errors);
        presenter.contribute(shell);
        manual.runAll();
        when(productService.categories()).thenReturn(List.of(new CategoryOption(9L, "Thể thao")));

        presenter.refresh();
        presenter.refresh();
        manual.runAll();

        assertThat(manual.submittedCount()).isEqualTo(3);
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-9");
        verifyNoInteractions(errors);
    }

    @Test
    void refresh_whenTheCategoriesCannotBeLoaded_reportsOnce_andKeepsTheCurrentMenu() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.categories()).thenThrow(failure);

        assertThatNoException().isThrownBy(presenter::refresh);

        verify(errors, times(1)).report(failure);
        assertThat(shell.categoriesMenuEntries()).extracting(ShellMenuEntry::id)
                .containsExactly("categoryMenuAll", "categoryMenu-2", "categoryMenu-1");
    }

    @Test
    void refresh_doesNotNavigateOrTouchTheStoreButtons() {
        StorefrontPresenter presenter = presenter();
        presenter.contribute(shell);

        presenter.refresh();

        // contribute went home once; refresh must not do it again
        verify(navigator, times(1)).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    // ------------------------------------------------------------------ search

    @Test
    void search_whileOnTheList_keepsCategoryAndSortAndGoesToPageOne() {
        when(navigator.currentRoute()).thenReturn(new CatalogRoute("x", 3L, "price_asc", 4));
        presenter().contribute(shell);

        shell.submitSearch("áo");

        verify(navigator).navigateTo(new CatalogRoute("áo", 3L, "price_asc", 1));
    }

    @Test
    void search_whileOnTheHome_searchesEveryCategory() {
        when(navigator.currentRoute()).thenReturn(new HomeRoute());
        presenter().contribute(shell);

        shell.submitSearch("áo");

        verify(navigator).navigateTo(CatalogRoute.search("áo"));
    }

    @Test
    void search_whileOnADetailScreen_searchesEveryCategory() {
        when(navigator.currentRoute()).thenReturn(new ProductDetailRoute(7L, new CatalogRoute("x", 3L, "price_asc", 4)));
        presenter().contribute(shell);

        shell.submitSearch("áo");

        verify(navigator).navigateTo(CatalogRoute.search("áo"));
    }

    @Test
    void search_withNoCurrentRoute_searchesEveryCategory() {
        when(navigator.currentRoute()).thenReturn(null);
        presenter().contribute(shell);

        shell.submitSearch("áo");

        verify(navigator).navigateTo(CatalogRoute.search("áo"));
    }

    @Test
    void search_withABlankText_onTheList_removesTheQueryButKeepsTheRest() {
        when(navigator.currentRoute()).thenReturn(new CatalogRoute("x", 3L, "price_asc", 4));
        presenter().contribute(shell);

        shell.submitSearch("   ");

        verify(navigator).navigateTo(new CatalogRoute(null, 3L, "price_asc", 1));
    }

    @Test
    void search_withABlankText_elsewhere_opensTheFullList() {
        when(navigator.currentRoute()).thenReturn(new HomeRoute());
        presenter().contribute(shell);

        shell.submitSearch("");

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void search_withScriptText_passesItVerbatim() {
        when(navigator.currentRoute()).thenReturn(new HomeRoute());
        presenter().contribute(shell);

        shell.submitSearch("<script>alert(1)</script>");

        verify(navigator).navigateTo(CatalogRoute.search("<script>alert(1)</script>"));
    }

    @Test
    void search_neverReadsTheServiceOnItsOwn() {
        presenter().contribute(shell);

        // only the categories load at start-up; searching is a navigation, the list screen queries
        verify(productService).categories();
        verifyNoInteractions(errors);
        verify(productService, never()).search(any());
    }
}
