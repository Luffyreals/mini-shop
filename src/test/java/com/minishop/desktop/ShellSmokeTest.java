package com.minishop.desktop;

import com.minishop.catalog.ProductRepository;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testfx.api.FxToolkit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opens the real main window (AC-009-1, the D1 part; QĐ11): {@link MiniShopApp} started on the real JavaFX toolkit
 * with an in-memory H2 database (test {@code config/application.yml}) and the test classes kept out of component
 * scanning. It only looks up nodes and state; no robot, so it never moves the mouse or presses a key.
 *
 * <p>One application is started for the whole class (Spring takes seconds); the last test closes it and checks that
 * the Spring context is closed, which is what releases the H2 lock and stops the background executor.
 */
@Tag("ui")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShellSmokeTest {

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage stage;

    @BeforeAll
    static void startApp() throws Exception {
        Stage primary = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        stage = primary;
        FxTestSupport.await("the main window", () -> primary.isShowing());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (app != null && app.context() != null && app.context().isActive()) {
            FxToolkit.cleanupApplication(app);
        }
        FxTestSupport.hideAllWindows();
    }

    private static Node lookup(String id) {
        Node node = FxTestSupport.callOnFx(() -> stage.getScene().lookup("#" + id));
        assertThat(node).as("node #" + id).isNotNull();
        return node;
    }

    private static boolean isDisabled(String id) {
        return FxTestSupport.callOnFx(() -> lookup(id).isDisabled());
    }

    @Test
    @Order(1)
    void start_opensTheMainWindowWithTheShellTitleAndMinimumSize() {
        assertThat(stage.isShowing()).isTrue();
        assertThat(stage.getTitle()).isEqualTo("Mini Shop");
        assertThat(stage.getMinWidth()).isEqualTo(1024);
        assertThat(stage.getMinHeight()).isEqualTo(700);
        assertThat(FxTestSupport.showingWindows()).as("no error dialog next to the main window")
                .extracting(Window::getScene)
                .noneMatch(scene -> scene.lookup("#errorDialog") != null);
    }

    @Test
    @Order(2)
    void start_buildsEveryNodeOfTheTopBar() {
        for (String id : List.of("topBar", "logo", "navStore", "navNew", "navCategories", "searchBox", "searchField",
                "cartButton", "cartBadge", "accountButton", "content", "toastLayer")) {
            assertThat(lookup(id)).as("#" + id).isNotNull();
        }
        assertThat(lookup("topBar")).isInstanceOf(Pane.class);
        assertThat(lookup("content")).isInstanceOf(StackPane.class);
        assertThat(((Button) lookup("navStore")).getText()).isEqualTo("Cửa hàng");
        assertThat(((Button) lookup("navNew")).getText()).isEqualTo("Hàng mới");
        assertThat(((Button) lookup("navCategories")).getText()).isEqualTo("Danh mục");
    }

    @Test
    @Order(3)
    void start_showsAnEmptyCartBadgeAndTheGuestAccountButton() {
        assertThat(((Label) lookup("cartBadge")).getText()).isEqualTo("0");
        assertThat(lookup("cartButton").getAccessibleText()).isEqualTo("Giỏ hàng, 0 sản phẩm");
        assertThat(((Button) lookup("accountButton")).getText()).isEqualTo("Đăng nhập");
        assertThat(((TextField) lookup("searchField")).getPromptText()).isEqualTo("Tìm sản phẩm theo tên…");
    }

    @Test
    @Order(4)
    void start_leavesTheCartButtonDisabledUntilAFeatureAttachesIt() {
        // The account button is not checked here: the login (D2) attaches its action, see the test below.
        assertThat(isDisabled("cartButton")).as("#cartButton is disabled (D4 attaches it)").isTrue();
    }

    @Test
    @Order(4)
    void start_enablesTheStoreButtonsAndTheSearchBoxBecauseTheStorefrontAttachedThem() {
        for (String id : List.of("navStore", "navNew", "navCategories", "searchField")) {
            assertThat(isDisabled(id)).as("#" + id + " is disabled").isFalse();
        }
        assertThat(isDisabled("cartButton")).as("#cartButton stays disabled until D4").isTrue();
    }

    @Test
    @Order(4)
    void searchShortcutLabel_showsCtrlK() {
        assertThat(((Label) lookup("searchShortcut")).getText()).isEqualTo("Ctrl K");
    }

    @Test
    @Order(4)
    void ctrlKAccelerator_isRegisteredOnTheSceneAndFocusesTheSearchField() {
        KeyCodeCombination ctrlK = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
        Scene scene = FxTestSupport.callOnFx(stage::getScene);
        Node searchField = lookup("searchField");
        Runnable action = FxTestSupport.callOnFx(() -> scene.getAccelerators().get(ctrlK));
        assertThat(action).as("the Ctrl+K accelerator").isNotNull();

        try {
            FxTestSupport.runOnFx(() -> {
                scene.getRoot().requestFocus();
                action.run();
            });

            assertThat(FxTestSupport.callOnFx(scene::getFocusOwner)).isSameAs(searchField);
        } finally {
            FxTestSupport.runOnFx(() -> scene.getRoot().requestFocus());
        }
    }

    @Test
    @Order(4)
    void searchText_setByAScreen_putsTheCaretAtTheEndNotAtTheStart() {
        ShellPresenter shell = app.context().getBean(ShellPresenter.class);
        TextField searchField = (TextField) lookup("searchField");
        try {
            FxTestSupport.runOnFx(() -> {
                searchField.setText("  áo THUN ");
                searchField.positionCaret(searchField.getLength());
                shell.setSearchText("áo THUN");
            });
            FxTestSupport.flushFx();

            assertThat(FxTestSupport.<String>callOnFx(searchField::getText)).isEqualTo("áo THUN");
            assertThat(FxTestSupport.<Integer>callOnFx(searchField::getCaretPosition)).isEqualTo("áo THUN".length());
        } finally {
            FxTestSupport.runOnFx(() -> shell.setSearchText(""));
            FxTestSupport.flushFx();
        }
    }

    @Test
    @Order(4)
    void searchText_typedByTheUser_keepsTheCaretWhereTheUserPutIt() {
        TextField searchField = (TextField) lookup("searchField");
        try {
            FxTestSupport.runOnFx(() -> {
                searchField.setText("áo thun");
                searchField.positionCaret(2);
                searchField.insertText(2, "x");
            });
            FxTestSupport.flushFx();

            assertThat(FxTestSupport.<String>callOnFx(searchField::getText)).isEqualTo("áox thun");
            assertThat(FxTestSupport.<Integer>callOnFx(searchField::getCaretPosition)).isEqualTo(3);
        } finally {
            FxTestSupport.runOnFx(() -> searchField.setText(""));
            FxTestSupport.flushFx();
        }
    }

    @Test
    @Order(5)
    void start_attachesThemeCssAndLoadsTheFont() {
        Scene scene = FxTestSupport.callOnFx(stage::getScene);

        assertThat(scene.getStylesheets()).anyMatch(css -> css.endsWith("/ui/theme.css"));
        assertThat(FxTestSupport.callOnFx(Font::getFamilies)).contains("Plus Jakarta Sans");
    }

    @Test
    @Order(6)
    void start_installsTheAppUncaughtHandlerAndSeedsTheCatalog() {
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isInstanceOf(UncaughtErrorHandler.class);

        ConfigurableApplicationContext context = app.context();
        assertThat(context.isActive()).isTrue();
        assertThat(context.getBean(ProductRepository.class).count()).as("the seed ran").isPositive();
        assertThat(context.getBean(Navigator.class)).isNotNull();
        assertThat(context.getBean(FxBackgroundTasks.class)).isNotNull();
    }

    @Test
    @Order(7)
    void navigatorIsAttachedToTheContentArea() {
        // The bean is the one MainWindow attached: navigating to a route with no factory fails by the factory lookup
        // (not by "not attached"), which proves the lookup path is wired in the real context.
        Navigator navigator = app.context().getBean(Navigator.class);

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> navigator.navigateTo(new Route() { }))
                .withMessageContaining("Chưa có ScreenFactory");
    }

    @Test
    @Order(8)
    void start_enablesTheGuestAccountButtonBecauseTheLoginAttachedItsAction() {
        Button account = (Button) lookup("accountButton");

        assertThat(isDisabled("accountButton")).as("#accountButton is enabled for a guest").isFalse();
        assertThat(account.getText()).isEqualTo("Đăng nhập");
        assertThat(account.getAccessibleText()).isEqualTo("Đăng nhập");
        assertThat(((Label) lookup("cartBadge")).getText()).as("a guest's cart is empty").isEqualTo("0");
    }

    @Test
    @Order(100)
    void stop_closesTheSpringContextAndTheBackgroundExecutor() throws Exception {
        ConfigurableApplicationContext context = app.context();
        FxBackgroundTasks tasks = context.getBean(FxBackgroundTasks.class);

        FxToolkit.cleanupApplication((Application) app);
        FxTestSupport.runOnFx(() -> stage.hide());

        assertThat(stage.isShowing()).isFalse();
        assertThat(context.isActive()).as("Application.stop() closed the Spring context").isFalse();
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .as("the executor is shut down with the context")
                .isThrownBy(() -> tasks.submit(() -> "x", null, null));
    }
}
