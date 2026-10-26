package com.minishop.desktop;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thay nội dung vùng {@code #content} của cửa sổ chính theo {@link Route}.
 * <p>
 * Navigator không biết màn cụ thể nào: nó tìm {@link ScreenFactory} theo kiểu chính xác của route. Danh sách
 * factory được lấy lười ở lần điều hướng đầu tiên, nên một factory có thể inject Navigator mà không tạo vòng.
 * Route chưa có factory là lỗi lập trình: {@link IllegalStateException} (đi vào uncaught handler).
 * <p>
 * {@link MainWindow} gọi {@link #attach(StackPane)} khi FXML nạp xong. Chưa có lịch sử "quay lại"; thêm khi
 * màn hình cần.
 */
@Component
public class Navigator {

    private final Supplier<Collection<? extends ScreenFactory<?>>> factorySource;
    private final ReadOnlyObjectWrapper<Route> currentRoute = new ReadOnlyObjectWrapper<>(this, "currentRoute");
    private Map<Class<?>, ScreenFactory<?>> factories;
    private StackPane content;
    private volatile Route homeRoute;

    @Autowired
    public Navigator(ObjectProvider<ScreenFactory<?>> factories) {
        this(() -> factories.orderedStream().toList());
    }

    /** Dùng trong test: danh sách factory cho sẵn. */
    public Navigator(Collection<? extends ScreenFactory<?>> factories) {
        this(() -> factories);
    }

    private Navigator(Supplier<Collection<? extends ScreenFactory<?>>> factorySource) {
        this.factorySource = factorySource;
    }

    /** Gắn vùng nội dung sẽ bị thay node. Gọi trên luồng giao diện. */
    void attach(StackPane content) {
        this.content = Objects.requireNonNull(content);
    }

    /**
     * Hiện màn của {@code route} trong vùng nội dung. Gọi từ luồng nào cũng được: việc dựng và thay node
     * luôn chạy trên luồng giao diện. Việc tìm factory chạy ngay trên luồng gọi, nên route lạ ném
     * {@link IllegalStateException} tại chỗ gọi.
     */
    public void navigateTo(Route route) {
        Objects.requireNonNull(route, "route");
        ScreenFactory<?> factory = factoryFor(route);
        if (Platform.isFxApplicationThread()) {
            show(factory, route);
        } else {
            Platform.runLater(() -> show(factory, route));
        }
    }

    /**
     * Đặt route của "trang chủ" cho {@link #goHome()}; {@code null} để gỡ. Chưa kiểm factory ở đây: route
     * chưa có factory sẽ ném lỗi khi {@code goHome()} điều hướng tới nó. D3 đặt {@code HomeRoute}.
     */
    public void setHomeRoute(Route route) {
        this.homeRoute = route;
    }

    /**
     * Về "trang chủ" (đăng xuất, AC-009-10): điều hướng tới route đã đặt bằng {@link #setHomeRoute}; nếu chưa
     * đặt thì xóa vùng nội dung và đặt {@link #currentRoute()} về {@code null}. Gọi từ luồng nào cũng được,
     * giống {@link #navigateTo}.
     */
    public void goHome() {
        Route home = homeRoute;
        if (home != null) {
            navigateTo(home);
        } else if (Platform.isFxApplicationThread()) {
            clear();
        } else {
            Platform.runLater(this::clear);
        }
    }

    /** Route đang hiện; {@code null} khi chưa điều hướng lần nào. */
    public ReadOnlyObjectProperty<Route> currentRouteProperty() {
        return currentRoute.getReadOnlyProperty();
    }

    public Route currentRoute() {
        return currentRoute.get();
    }

    private synchronized ScreenFactory<?> factoryFor(Route route) {
        if (factories == null) {
            Map<Class<?>, ScreenFactory<?>> byType = new HashMap<>();
            for (ScreenFactory<?> factory : factorySource.get()) {
                ScreenFactory<?> previous = byType.put(factory.routeType(), factory);
                if (previous != null) {
                    throw new IllegalStateException("Hai ScreenFactory cùng phục vụ route "
                            + factory.routeType().getName());
                }
            }
            factories = byType;
        }
        ScreenFactory<?> factory = factories.get(route.getClass());
        if (factory == null) {
            throw new IllegalStateException("Chưa có ScreenFactory cho route " + route.getClass().getName());
        }
        return factory;
    }

    private void show(ScreenFactory<?> factory, Route route) {
        if (content == null) {
            throw new IllegalStateException("Navigator chưa được gắn vào vùng nội dung");
        }
        Node screen = create(factory, route);
        content.getChildren().setAll(screen);
        currentRoute.set(route);
    }

    private void clear() {
        if (content == null) {
            throw new IllegalStateException("Navigator chưa được gắn vào vùng nội dung");
        }
        content.getChildren().clear();
        currentRoute.set(null);
    }

    private static <R extends Route> Node create(ScreenFactory<R> factory, Route route) {
        return factory.create(factory.routeType().cast(route));
    }
}
