package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * {@link Navigator} on the real JavaFX toolkit (QĐ4, QĐ11): the factory is chosen by the exact route class, a
 * route without a factory fails at the call site, and the {@code #content} area is replaced.
 */
@Tag("ui")
class NavigatorTest {

    record HomeRoute() implements Route {
    }

    record DetailRoute(long id) implements Route {
    }

    /** Not final on purpose: a subclass must not be served by the factory of its parent. */
    static class BaseRoute implements Route {
    }

    static final class ChildRoute extends BaseRoute {
    }

    static final class UnknownRoute implements Route {
    }

    /** A factory that records every route it was asked to build; the node's id is the given text. */
    static final class RecordingFactory<R extends Route> implements ScreenFactory<R> {

        private final Class<R> type;
        private final String text;
        final List<R> created = new ArrayList<>();
        final AtomicBoolean createdOnFxThread = new AtomicBoolean();

        RecordingFactory(Class<R> type, String text) {
            this.type = type;
            this.text = text;
        }

        @Override
        public Class<R> routeType() {
            return type;
        }

        @Override
        public Node create(R route) {
            created.add(route);
            createdOnFxThread.set(Platform.isFxApplicationThread());
            Label label = new Label(text);
            label.setId(text);
            return label;
        }
    }

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    @AfterAll
    static void closeWindows() {
        FxTestSupport.hideAllWindows();
    }

    private static StackPane contentArea() {
        StackPane content = FxTestSupport.callOnFx(StackPane::new);
        content.setId("content");
        return content;
    }

    private static List<String> childIds(StackPane content) {
        return FxTestSupport.callOnFx(() -> content.getChildren().stream().map(Node::getId).toList());
    }

    private static Navigator attached(StackPane content, ScreenFactory<?>... factories) {
        Navigator navigator = new Navigator(List.of(factories));
        FxTestSupport.runOnFx(() -> navigator.attach(content));
        return navigator;
    }

    @Test
    void currentRoute_beforeAnyNavigation_isNull() {
        Navigator navigator = attached(contentArea(), new RecordingFactory<>(HomeRoute.class, "home"));

        assertThat(navigator.currentRoute()).isNull();
        assertThat(navigator.currentRouteProperty().get()).isNull();
    }

    @Test
    void navigateTo_onTheFxThread_replacesContentImmediately() {
        StackPane content = contentArea();
        RecordingFactory<HomeRoute> home = new RecordingFactory<>(HomeRoute.class, "home");
        Navigator navigator = attached(content, home);
        HomeRoute route = new HomeRoute();

        List<String> idsRightAfter = FxTestSupport.callOnFx(() -> {
            navigator.navigateTo(route);
            return content.getChildren().stream().map(Node::getId).toList();
        });

        assertThat(idsRightAfter).containsExactly("home");
        assertThat(navigator.currentRoute()).isSameAs(route);
        assertThat(home.created).containsExactly(route);
    }

    @Test
    void navigateTo_fromAnotherThread_buildsAndReplacesContentOnTheFxThread() {
        StackPane content = contentArea();
        RecordingFactory<DetailRoute> detail = new RecordingFactory<>(DetailRoute.class, "detail");
        Navigator navigator = attached(content, new RecordingFactory<>(HomeRoute.class, "home"), detail);

        navigator.navigateTo(new DetailRoute(7));
        FxTestSupport.await("the content to be replaced", () -> childIds(content).equals(List.of("detail")));

        assertThat(detail.createdOnFxThread).isTrue();
        assertThat(detail.created).containsExactly(new DetailRoute(7));
        assertThat(navigator.currentRoute()).isEqualTo(new DetailRoute(7));
    }

    @Test
    void navigateTo_picksTheFactoryOfTheExactRouteClass() {
        StackPane content = contentArea();
        RecordingFactory<HomeRoute> home = new RecordingFactory<>(HomeRoute.class, "home");
        RecordingFactory<DetailRoute> detail = new RecordingFactory<>(DetailRoute.class, "detail");
        Navigator navigator = attached(content, home, detail);

        FxTestSupport.runOnFx(() -> navigator.navigateTo(new DetailRoute(3)));

        assertThat(childIds(content)).containsExactly("detail");
        assertThat(detail.created).containsExactly(new DetailRoute(3));
        assertThat(home.created).isEmpty();
    }

    @Test
    void navigateTo_twice_replacesTheFirstScreenAndNotifiesListeners() {
        StackPane content = contentArea();
        Navigator navigator = attached(content,
                new RecordingFactory<>(HomeRoute.class, "home"),
                new RecordingFactory<>(DetailRoute.class, "detail"));
        List<Route> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> navigator.currentRouteProperty().addListener((o, was, now) -> seen.add(now)));

        FxTestSupport.runOnFx(() -> navigator.navigateTo(new HomeRoute()));
        FxTestSupport.runOnFx(() -> navigator.navigateTo(new DetailRoute(1)));

        assertThat(childIds(content)).containsExactly("detail");
        assertThat(seen).containsExactly(new HomeRoute(), new DetailRoute(1));
    }

    @Test
    void navigateTo_withNoFactoryForTheRoute_throwsAtTheCallAndKeepsTheContent() {
        StackPane content = contentArea();
        Navigator navigator = attached(content, new RecordingFactory<>(HomeRoute.class, "home"));
        FxTestSupport.runOnFx(() -> navigator.navigateTo(new HomeRoute()));

        // Called from the test thread, not the FX thread: the failure must still be synchronous (QĐ4).
        assertThatIllegalStateException()
                .isThrownBy(() -> navigator.navigateTo(new UnknownRoute()))
                .withMessageContaining(UnknownRoute.class.getName());

        assertThat(childIds(content)).containsExactly("home");
        assertThat(navigator.currentRoute()).isEqualTo(new HomeRoute());
    }

    @Test
    void navigateTo_withASubclassOfARegisteredRoute_isNotServedByTheParentFactory() {
        Navigator navigator = attached(contentArea(), new RecordingFactory<>(BaseRoute.class, "base"));

        assertThatIllegalStateException().isThrownBy(() -> navigator.navigateTo(new ChildRoute()));
    }

    @Test
    void navigateTo_whenTwoFactoriesServeTheSameRoute_throws() {
        Navigator navigator = attached(contentArea(),
                new RecordingFactory<>(HomeRoute.class, "one"),
                new RecordingFactory<>(HomeRoute.class, "two"));

        assertThatIllegalStateException()
                .isThrownBy(() -> navigator.navigateTo(new HomeRoute()))
                .withMessageContaining(HomeRoute.class.getName());
    }

    @Test
    void navigateTo_beforeAttach_throwsOnTheFxThread() {
        Navigator navigator = new Navigator(List.of(new RecordingFactory<>(HomeRoute.class, "home")));

        assertThatIllegalStateException()
                .isThrownBy(() -> FxTestSupport.runOnFx(() -> navigator.navigateTo(new HomeRoute())))
                .withMessageContaining("chưa được gắn");
        assertThat(navigator.currentRoute()).isNull();
    }

    @Test
    void navigateTo_whenTheFactoryThrows_keepsTheOldScreenAndRoute() {
        StackPane content = contentArea();
        ScreenFactory<DetailRoute> broken = new ScreenFactory<>() {
            @Override
            public Class<DetailRoute> routeType() {
                return DetailRoute.class;
            }

            @Override
            public Node create(DetailRoute route) {
                throw new IllegalArgumentException("cannot build");
            }
        };
        Navigator navigator = attached(content, new RecordingFactory<>(HomeRoute.class, "home"), broken);
        FxTestSupport.runOnFx(() -> navigator.navigateTo(new HomeRoute()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FxTestSupport.runOnFx(() -> navigator.navigateTo(new DetailRoute(1))));

        assertThat(childIds(content)).containsExactly("home");
        assertThat(navigator.currentRoute()).isEqualTo(new HomeRoute());
    }
}
