package com.minishop.desktop;

import com.minishop.desktop.ShellPresenter.ShellAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The state of the top bar (AC-009-1, QĐ4, QĐ15). It uses only {@code javafx.beans}, so no JavaFX toolkit runs here.
 */
class ShellPresenterTest {

    private final ShellPresenter presenter = new ShellPresenter();

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "5,5", "99,99", "100,99+", "250,99+", "-3,0"})
    void badgeText_showsTheCountAndCapsAbove99(int count, String expected) {
        assertThat(ShellPresenter.badgeText(count)).isEqualTo(expected);
    }

    @Test
    void cartBadge_startsAtZero() {
        assertThat(presenter.cartBadgeTextBinding().get()).isEqualTo("0");
        assertThat(presenter.cartAccessibleTextBinding().get()).isEqualTo("Giỏ hàng, 0 sản phẩm");
    }

    @Test
    void setCartCount_updatesTheBadgeAndTheAccessibleText() {
        presenter.setCartCount(5);

        assertThat(presenter.cartBadgeTextBinding().get()).isEqualTo("5");
        assertThat(presenter.cartAccessibleTextBinding().get()).isEqualTo("Giỏ hàng, 5 sản phẩm");
    }

    @Test
    void setCartCount_above99_capsTheBadgeButNotTheAccessibleText() {
        presenter.setCartCount(100);

        assertThat(presenter.cartBadgeTextBinding().get()).isEqualTo("99+");
        assertThat(presenter.cartAccessibleTextBinding().get()).isEqualTo("Giỏ hàng, 100 sản phẩm");
    }

    @Test
    void setCartCount_negative_isZero() {
        presenter.setCartCount(7);
        presenter.setCartCount(-1);

        assertThat(presenter.cartCountProperty().get()).isZero();
        assertThat(presenter.cartBadgeTextBinding().get()).isEqualTo("0");
    }

    @Test
    void cartBadge_followsTheCountPropertyDirectly() {
        presenter.cartCountProperty().set(12);

        assertThat(presenter.cartBadgeTextBinding().get()).isEqualTo("12");
    }

    @Test
    void accountLabel_isLoginForGuestsAndFallsBackWhenBlank() {
        assertThat(presenter.accountLabelProperty().get()).isEqualTo("Đăng nhập");

        presenter.setAccountLabel("Nguyễn Văn An");
        assertThat(presenter.accountLabelProperty().get()).isEqualTo("Nguyễn Văn An");

        presenter.setAccountLabel("  ");
        assertThat(presenter.accountLabelProperty().get()).isEqualTo("Đăng nhập");

        presenter.setAccountLabel("An");
        presenter.setAccountLabel(null);
        assertThat(presenter.accountLabelProperty().get()).isEqualTo("Đăng nhập");
    }

    @ParameterizedTest
    @EnumSource(ShellAction.class)
    void buttons_areDisabledUntilAnActionIsSet(ShellAction action) {
        assertThat(presenter.enabledBinding(action).get()).isFalse();

        presenter.setAction(action, () -> { });

        assertThat(presenter.enabledBinding(action).get()).isTrue();
    }

    @Test
    void setAction_enablesOnlyThatButton() {
        presenter.setAction(ShellAction.CART, () -> { });

        assertThat(presenter.enabledBinding(ShellAction.CART).get()).isTrue();
        assertThat(presenter.enabledBinding(ShellAction.STORE).get()).isFalse();
        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isFalse();
    }

    @Test
    void setAction_withNull_disablesTheButtonAgain() {
        presenter.setAction(ShellAction.CART, () -> { });

        presenter.setAction(ShellAction.CART, null);

        assertThat(presenter.enabledBinding(ShellAction.CART).get()).isFalse();
    }

    @Test
    void trigger_runsTheActionOfThatButtonOnly() {
        AtomicInteger cart = new AtomicInteger();
        AtomicInteger store = new AtomicInteger();
        presenter.setAction(ShellAction.CART, cart::incrementAndGet);
        presenter.setAction(ShellAction.STORE, store::incrementAndGet);

        presenter.trigger(ShellAction.CART);

        assertThat(cart).hasValue(1);
        assertThat(store).hasValue(0);
    }

    @Test
    void trigger_withoutAnAction_doesNothing() {
        presenter.trigger(ShellAction.ACCOUNT);

        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isFalse();
    }

    @Test
    void search_isDisabledUntilAHandlerIsSetAndPassesTheTextToIt() {
        List<String> received = new ArrayList<>();
        assertThat(presenter.searchEnabledBinding().get()).isFalse();
        presenter.submitSearch("áo thun");
        assertThat(received).isEmpty();

        presenter.setSearchHandler(received::add);

        assertThat(presenter.searchEnabledBinding().get()).isTrue();
        presenter.submitSearch("áo thun");
        presenter.submitSearch(null);
        assertThat(received).containsExactly("áo thun", "");
    }

    @Test
    void search_afterTheHandlerIsRemoved_isDisabledAgain() {
        presenter.setSearchHandler(text -> { });

        presenter.setSearchHandler(null);

        assertThat(presenter.searchEnabledBinding().get()).isFalse();
    }

    // ------------------------------------------------------------------ account menu (QĐ9 of D2)

    private static ShellMenuEntry entry(String id, String label) {
        return new ShellMenuEntry(id, label, () -> { });
    }

    @Test
    void accountMenu_startsEmpty() {
        assertThat(presenter.accountMenuEntries()).isEmpty();
    }

    @Test
    void accountButton_withMenuEntriesButNoAction_isEnabled() {
        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isFalse();

        presenter.setAccountMenu(List.of(entry("accountMenuLogout", "Đăng xuất")));

        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isTrue();
    }

    @Test
    void accountButton_whenTheMenuIsRemovedAndThereIsNoAction_isDisabledAgain() {
        presenter.setAccountMenu(List.of(entry("a", "A")));

        presenter.setAccountMenu(List.of());

        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isFalse();
    }

    @Test
    void accountButton_whenTheMenuIsRemovedButThereIsAnAction_staysEnabled() {
        presenter.setAction(ShellAction.ACCOUNT, () -> { });
        presenter.setAccountMenu(List.of(entry("a", "A")));

        presenter.setAccountMenu(null);

        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isTrue();
    }

    @Test
    void setAccountMenu_replacesTheWholeList() {
        presenter.setAccountMenu(List.of(entry("a", "A"), entry("b", "B")));

        presenter.setAccountMenu(List.of(entry("c", "C")));

        assertThat(presenter.accountMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("c");
    }

    @Test
    void setAccountMenu_copiesTheListSoLaterChangesByTheCallerDoNotLeakIn() {
        List<ShellMenuEntry> entries = new ArrayList<>(List.of(entry("a", "A")));
        presenter.setAccountMenu(entries);

        entries.add(entry("b", "B"));

        assertThat(presenter.accountMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("a");
    }

    @Test
    void setAccountMenu_withNullOrEmpty_removesTheMenu() {
        presenter.setAccountMenu(List.of(entry("a", "A")));
        presenter.setAccountMenu(null);
        assertThat(presenter.accountMenuEntries()).isEmpty();

        presenter.setAccountMenu(List.of(entry("a", "A")));
        presenter.setAccountMenu(List.of());
        assertThat(presenter.accountMenuEntries()).isEmpty();
    }

    @Test
    void accountAccessibleText_defaultsToTheLabelAndFollowsIt() {
        assertThat(presenter.accountAccessibleTextProperty().get()).isEqualTo("Đăng nhập");

        presenter.setAccountLabel("Nguyễn Văn An");

        assertThat(presenter.accountAccessibleTextProperty().get()).isEqualTo("Nguyễn Văn An");
    }

    @Test
    void accountAccessibleText_whenSet_winsOverTheLabel() {
        presenter.setAccountLabel("Nguyễn Văn An");

        presenter.setAccountAccessibleText("Xin chào, Nguyễn Văn An");

        assertThat(presenter.accountAccessibleTextProperty().get()).isEqualTo("Xin chào, Nguyễn Văn An");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void accountAccessibleText_whenNullOrBlank_goesBackToTheLabel(String text) {
        presenter.setAccountLabel("An");
        presenter.setAccountAccessibleText("Xin chào, An");

        presenter.setAccountAccessibleText(text);

        assertThat(presenter.accountAccessibleTextProperty().get()).isEqualTo("An");
    }

    @Test
    void shortenLabel_keepsALabelOfExactly24CodePointsAndCutsLongerOnesWithAnEllipsis() {
        String exactly24 = "a".repeat(ShellPresenter.MAX_ACCOUNT_LABEL);
        String twentyFive = "a".repeat(ShellPresenter.MAX_ACCOUNT_LABEL + 1);

        assertThat(ShellPresenter.shortenLabel("An")).isEqualTo("An");
        assertThat(ShellPresenter.shortenLabel(exactly24)).isEqualTo(exactly24);
        assertThat(ShellPresenter.shortenLabel(twentyFive)).isEqualTo("a".repeat(23) + "…").hasSize(24);
    }

    @Test
    void shortenLabel_countsCodePointsSoASurrogatePairIsNeverSplit() {
        String emoji = "😀";
        String twentyFive = emoji.repeat(25);

        String shortened = ShellPresenter.shortenLabel(twentyFive);

        assertThat(shortened).isEqualTo(emoji.repeat(23) + "…");
        assertThat(shortened.codePointCount(0, shortened.length())).isEqualTo(24);
        assertThat(ShellPresenter.shortenLabel(emoji.repeat(24))).isEqualTo(emoji.repeat(24));
    }

    @Test
    void shortenLabel_dropsASpaceRightBeforeTheEllipsis() {
        String label = "a".repeat(22) + " " + "b".repeat(5);

        assertThat(ShellPresenter.shortenLabel(label)).isEqualTo("a".repeat(22) + "…");
    }

    @Test
    void setAccountLabel_withALongName_isCutToTheLimit() {
        presenter.setAccountLabel("Nguyễn Thị Rất Rất Dài Họ Và Tên Của Một Người Dùng");

        assertThat(presenter.accountLabelProperty().get()).isEqualTo("Nguyễn Thị Rất Rất Dài…");
    }

    @Test
    void setAccountLabel_stripsBeforeMeasuring() {
        presenter.setAccountLabel("   " + "a".repeat(24) + "   ");

        assertThat(presenter.accountLabelProperty().get()).isEqualTo("a".repeat(24));
    }

    // ------------------------------------------------------------------ categories menu and search text (QĐ6 of D3)

    @Test
    void categoriesMenu_startsEmptyAndTheButtonIsDisabled() {
        assertThat(presenter.categoriesMenuEntries()).isEmpty();
        assertThat(presenter.enabledBinding(ShellAction.CATEGORIES).get()).isFalse();
    }

    @Test
    void categoriesButton_withMenuEntriesButNoAction_isEnabled() {
        presenter.setCategoriesMenu(List.of(entry("categoryMenuAll", "Tất cả danh mục")));

        assertThat(presenter.enabledBinding(ShellAction.CATEGORIES).get()).isTrue();
    }

    @Test
    void categoriesButton_whenTheMenuIsRemovedAndThereIsNoAction_isDisabledAgain() {
        presenter.setCategoriesMenu(List.of(entry("a", "A")));

        presenter.setCategoriesMenu(List.of());

        assertThat(presenter.enabledBinding(ShellAction.CATEGORIES).get()).isFalse();
    }

    @Test
    void categoriesButton_whenTheMenuIsRemovedButThereIsAnAction_staysEnabled() {
        presenter.setAction(ShellAction.CATEGORIES, () -> { });
        presenter.setCategoriesMenu(List.of(entry("a", "A")));

        presenter.setCategoriesMenu(null);

        assertThat(presenter.enabledBinding(ShellAction.CATEGORIES).get()).isTrue();
    }

    @Test
    void setCategoriesMenu_withNullOrEmpty_clearsTheMenu() {
        presenter.setCategoriesMenu(List.of(entry("a", "A")));
        presenter.setCategoriesMenu(null);
        assertThat(presenter.categoriesMenuEntries()).isEmpty();

        presenter.setCategoriesMenu(List.of(entry("a", "A")));
        presenter.setCategoriesMenu(List.of());
        assertThat(presenter.categoriesMenuEntries()).isEmpty();
    }

    @Test
    void setCategoriesMenu_replacesTheWholeListAndCopiesIt() {
        List<ShellMenuEntry> entries = new ArrayList<>(List.of(entry("a", "A"), entry("b", "B")));
        presenter.setCategoriesMenu(entries);
        entries.add(entry("c", "C"));

        assertThat(presenter.categoriesMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("a", "b");

        presenter.setCategoriesMenu(List.of(entry("d", "D")));

        assertThat(presenter.categoriesMenuEntries()).extracting(ShellMenuEntry::id).containsExactly("d");
    }

    @Test
    void categoriesMenu_doesNotEnableTheAccountButton() {
        presenter.setCategoriesMenu(List.of(entry("a", "A")));

        assertThat(presenter.enabledBinding(ShellAction.ACCOUNT).get()).isFalse();
    }

    @Test
    void searchText_startsEmpty() {
        assertThat(presenter.searchTextProperty().get()).isEmpty();
    }

    @Test
    void setSearchText_setsTheText_andNullBecomesEmpty() {
        presenter.setSearchText("áo thun");
        assertThat(presenter.searchTextProperty().get()).isEqualTo("áo thun");

        presenter.setSearchText(null);

        assertThat(presenter.searchTextProperty().get()).isEmpty();
    }

    @Test
    void setSearchText_keepsScriptTextVerbatim() {
        presenter.setSearchText("<script>alert(1)</script>");

        assertThat(presenter.searchTextProperty().get()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void shellMenuEntry_rejectsNullOrBlankParts() {
        assertThatNullPointerException().isThrownBy(() -> new ShellMenuEntry(null, "A", () -> { }));
        assertThatNullPointerException().isThrownBy(() -> new ShellMenuEntry("a", null, () -> { }));
        assertThatNullPointerException().isThrownBy(() -> new ShellMenuEntry("a", "A", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ShellMenuEntry(" ", "A", () -> { }));
        assertThatIllegalArgumentException().isThrownBy(() -> new ShellMenuEntry("a", " ", () -> { }));
    }
}
