package com.minishop.desktop;

import com.minishop.desktop.ShellPresenter.ShellAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
}
