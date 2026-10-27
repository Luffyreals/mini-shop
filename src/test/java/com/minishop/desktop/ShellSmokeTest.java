package com.minishop.desktop;

import com.minishop.catalog.ProductRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.support.FxTestSupport;
import com.minishop.support.LogCapture;
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
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
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
import org.slf4j.LoggerFactory;
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
    private static LogCapture startupLog;
    private static Stage stage;

    @BeforeAll
    static void startApp() throws Exception {
        Stage primary = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        // Boot re-initialises Logback while the context starts, which drops appenders added earlier: attach the capture
        // as soon as the context is built, before start() shows the window and logs "Main window shown"
        app = (TestShellApp) FxToolkit.setupApplication(() -> new TestShellApp() {
            @Override
            protected ConfigurableApplicationContext createContext(String[] args) {
                ConfigurableApplicationContext built = super.createContext(args);
                startupLog = LogCapture.of(LoggerFactory.getLogger(MiniShopApp.class));
                return built;
            }
        });
        stage = primary;
        FxTestSupport.await("the main window", () -> primary.isShowing());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (startupLog != null) {
            startupLog.close();
        }
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
    @Order(3)
    void start_enablesTheCartButtonBecauseTheCartDrawerAttachedItsAction() {
        assertThat(isDisabled("cartButton")).as("#cartButton is enabled").isFalse();
    }

    @Test
    @Order(3)
    void start_hasTheDrawerLayerBetweenTheShellAndTheToastLayer_soToastsShowAboveTheDrawer() {
        Node shell = lookup("shell");
        Node drawerLayer = lookup("drawerLayer");
        Node toastLayer = lookup("toastLayer");
        List<Node> siblings = FxTestSupport.callOnFx(() -> List.copyOf(((Pane) shell.getParent()).getChildren()));

        assertThat(drawerLayer.getParent()).as("same parent").isSameAs(shell.getParent());
        assertThat(toastLayer.getParent()).as("same parent").isSameAs(shell.getParent());
        assertThat(siblings.indexOf(drawerLayer)).as("the drawer layer is above the shell")
                .isGreaterThan(siblings.indexOf(shell));
        assertThat(siblings.indexOf(toastLayer)).as("the toast layer is above the drawer layer")
                .isGreaterThan(siblings.indexOf(drawerLayer));
        assertThat(FxTestSupport.callOnFx(() -> ((Pane) drawerLayer).getChildren().isEmpty()))
                .as("no drawer is open at start").isTrue();
        assertThat(FxTestSupport.callOnFx(() -> ((Pane) drawerLayer).isPickOnBounds()))
                .as("the empty layer does not catch the mouse").isFalse();
    }

    @Test
    @Order(4)
    void start_enablesTheStoreButtonsAndTheSearchBoxBecauseTheStorefrontAttachedThem() {
        for (String id : List.of("navStore", "navNew", "navCategories", "searchField")) {
            assertThat(isDisabled(id)).as("#" + id + " is disabled").isFalse();
        }
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

    /** The centre of {@code #id} converted into the local coordinates of {@code layer}. */
    private static javafx.geometry.Point2D centreOfInto(String id, Node layer) {
        return FxTestSupport.callOnFx(() -> {
            Node target = stage.getScene().lookup("#" + id);
            javafx.geometry.Bounds bounds = target.getLayoutBounds();
            javafx.geometry.Point2D inScene = target.localToScene(bounds.getCenterX(), bounds.getCenterY());
            return layer.sceneToLocal(inScene);
        });
    }

    @Test
    @Order(9)
    void drawerClosed_theEmptyDrawerAndToastLayersDoNotCoverTheWindow_soClicksReachTheShell() {
        // A transparent background fill still counts for hit-testing, so the layers must have no fill at all
        for (String id : List.of("cartButton", "navStore", "searchField", "accountButton")) {
            for (String layerId : List.of("drawerLayer", "toastLayer")) {
                Node layer = lookup(layerId);
                javafx.geometry.Point2D local = centreOfInto(id, layer);
                assertThat(FxTestSupport.callOnFx(() -> layer.contains(local)))
                        .as("#" + layerId + " must not contain the centre of #" + id).isFalse();
            }
        }
        assertThat(FxTestSupport.callOnFx(() -> ((Pane) lookup("drawerLayer")).getBackground()))
                .as("the drawer layer has no background").isNull();
    }

    @Test
    @Order(9)
    void drawerOpen_theScrimCatchesTheClicksOverTheShell_andTheLayerIsEmptyAgainAfterClosing() {
        DrawerHost host = app.context().getBean(DrawerHost.class);
        Pane layer = (Pane) lookup("drawerLayer");
        try {
            FxTestSupport.runOnFx(() -> host.open(new StackPane(), null));
            FxTestSupport.flushFx();

            Node scrim = lookup("drawerScrim");
            // The scrim is laid out on the next pulse, so wait for it instead of asserting at once
            FxTestSupport.await("the scrim to cover #cartButton while the drawer is open",
                    () -> FxTestSupport.callOnFx(() -> scrim.contains(centreOfInto("cartButton", scrim))));
        } finally {
            FxTestSupport.runOnFx(host::close);
            FxTestSupport.await("the drawer layer to be empty again",
                    () -> FxTestSupport.callOnFx(() -> layer.getChildren().isEmpty()));
        }
        javafx.geometry.Point2D local = centreOfInto("cartButton", layer);
        assertThat(FxTestSupport.callOnFx(() -> layer.contains(local)))
                .as("after closing, #drawerLayer does not contain the centre of #cartButton").isFalse();
    }

    @Test
    @Order(2)
    void start_logsMainWindowShownOnceAndAddsTheFourWindowIcons() {
        assertThat(startupLog.events(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(MiniShopApp.MAIN_WINDOW_SHOWN::equals).hasSize(1);
        assertThat(startupLog.events(Level.WARN)).as("no window icon warning").extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.startsWith("Window icon"));

        // TestFX hands every test class the same primary stage, so a class that ran earlier in this JVM may have left
        // its own four icons there: look at the last four, which this start added, in the order MiniShopApp adds them
        List<Image> icons = FxTestSupport.callOnFx(() -> List.copyOf(stage.getIcons()));
        assertThat(icons).hasSizeGreaterThanOrEqualTo(4).noneMatch(Image::isError);
        assertThat(icons.subList(icons.size() - 4, icons.size())).extracting(image -> (int) image.getWidth())
                .containsExactly(16, 32, 48, 256);
    }

    /** Applies CSS and layout, then reads what the account button really draws (the text of its inner label). */
    private static String drawnAccountText() {
        return FxTestSupport.callOnFx(() -> {
            Node root = stage.getScene().getRoot();
            root.applyCss();
            ((Region) root).layout();
            Node button = root.lookup("#accountButton");
            Node inner = button.lookup(".text");
            return inner instanceof Text text ? text.getText() : null;
        });
    }

    @Test
    @Order(10)
    void accountLabel_atTheMinimumWindowWidth_showsBothDemoNamesInFullAndKeepsTheSearchBoxWide() {
        ShellPresenter shell = app.context().getBean(ShellPresenter.class);
        double oldWidth = FxTestSupport.callOnFx(stage::getWidth);
        double oldHeight = FxTestSupport.callOnFx(stage::getHeight);
        Region searchBox = (Region) lookup("searchBox");
        try {
            FxTestSupport.runOnFx(() -> stage.setWidth(1024));
            FxTestSupport.await("the window to be 1024 px wide", () -> FxTestSupport.callOnFx(() -> stage.getWidth()) <= 1024.5);

            for (String name : List.of("Quản trị viên", "Khách hàng mẫu")) {
                FxTestSupport.runOnFx(() -> shell.setAccountLabel(name));
                FxTestSupport.flushFx();

                assertThat(drawnAccountText()).as("the text drawn in the account button").isEqualTo(name);
                assertThat(FxTestSupport.<Double>callOnFx(() -> ((Region) lookup("accountButton")).getWidth()))
                        .as("the account button is not wider than its cap").isLessThanOrEqualTo(168.0);
                assertThat(FxTestSupport.<Double>callOnFx(searchBox::getMinWidth)).isEqualTo(268.0);
                assertThat(FxTestSupport.<Double>callOnFx(searchBox::getWidth))
                        .as("the search box is not narrower than its minWidth with " + name)
                        .isGreaterThanOrEqualTo(268.0);
            }
        } finally {
            FxTestSupport.runOnFx(() -> {
                shell.setAccountLabel(null);
                stage.setWidth(oldWidth);
                stage.setHeight(oldHeight);
            });
            FxTestSupport.flushFx();
        }
    }

    @Test
    @Order(10)
    void accountLabel_longName_isCutWithAnEllipsisAndTheButtonStaysWithinItsCap() {
        ShellPresenter shell = app.context().getBean(ShellPresenter.class);
        try {
            FxTestSupport.runOnFx(() -> shell.setAccountLabel("Nguyễn Thị Phương Thảo Trần Văn"));
            FxTestSupport.flushFx();

            assertThat(drawnAccountText()).as("the button's -fx-ellipsis-string is the single character")
                    .matches(".+…");
            assertThat(FxTestSupport.<Double>callOnFx(() -> ((Region) lookup("accountButton")).getWidth()))
                    .isLessThanOrEqualTo(168.0);
        } finally {
            FxTestSupport.runOnFx(() -> shell.setAccountLabel(null));
            FxTestSupport.flushFx();
        }
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
