package com.minishop.desktop;

import com.minishop.MiniShopApplication;
import com.minishop.common.SafeExceptionLog;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Ứng dụng JavaFX. Spring được dựng trong {@link #init()} (luồng {@code JavaFX-Launcher}), nên Flyway
 * và seed chạy xong trước khi cửa sổ hiện mà không làm cửa sổ treo; giao diện dựng trong {@link #start}
 * (luồng giao diện); {@link #stop()} đóng context để executor nền dừng và H2 nhả khóa file.
 * <p>
 * Không phải bean Spring: JavaFX tự tạo đối tượng {@code Application}.
 */
public class MiniShopApp extends Application {

    static final String TITLE = "Mini Shop";
    static final double MIN_WIDTH = 1024;
    static final double MIN_HEIGHT = 700;
    static final double INITIAL_WIDTH = 1280;
    static final double INITIAL_HEIGHT = 800;

    private static final int[] ICON_SIZES = {16, 32, 48, 256};

    /** Dòng INFO ghi một lần khi cửa sổ chính đã hiện (job CI {@code package-windows} chờ dòng này). */
    static final String MAIN_WINDOW_SHOWN = "Main window shown";

    private static final Logger LOG = LoggerFactory.getLogger(MiniShopApp.class);

    private ConfigurableApplicationContext context;
    /** Lỗi dựng context ở {@link #init()}, được {@link #start} đọc để hiện hộp thoại rồi thoát. */
    private Throwable startupError;

    @Override
    public void init() {
        // Đặt trước mọi việc khác: lỗi ở bất kỳ luồng nào từ đây trở đi đều có log và hộp thoại (AC-009-12).
        Thread.setDefaultUncaughtExceptionHandler(
                new UncaughtErrorHandler(new ErrorReporter(ErrorDialog::showLater)));
        try {
            context = createContext(getParameters().getRaw().toArray(String[]::new));
        } catch (Exception | LinkageError e) {
            startupError = e;
        }
    }

    /**
     * Dựng context Spring. {@code web(NONE)} ghi tường minh để app vẫn đúng khi còn thư viện web trên
     * classpath; {@code headless(false)} vì Spring mặc định đặt {@code java.awt.headless=true}, mà
     * các màn sau có thể cần AWT (ví dụ mở liên kết). Test ghi đè để loại lớp test khỏi việc quét bean.
     * <p>
     * Ba logger báo lỗi khởi động (Boot và Spring khi hủy refresh) bị tắt: chúng in nguyên thông điệp lỗi,
     * mà H2 có thể nhét cả dòng
     * dữ liệu vào đó (ADR-0013). Lỗi được ghi lại đúng một lần, đã lọc, ở {@link #start}.
     */
    protected ConfigurableApplicationContext createContext(String[] args) {
        return new SpringApplicationBuilder(MiniShopApplication.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                // HostServices thuộc về Application nên phải được đưa vào context từ đây: FxExternalLinks lấy nó
                // để mở liên kết trong trình duyệt (context của test không có bean này).
                .initializers(applicationContext -> applicationContext.getBeanFactory()
                        .registerSingleton("hostServices", getHostServices()))
                .properties(
                        "logging.level.org.springframework.boot.SpringApplication=OFF",
                        "logging.level.org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter=OFF",
                        "logging.level.org.springframework.context.annotation.AnnotationConfigApplicationContext=OFF")
                .run(args);
    }

    @Override
    public void start(Stage stage) {
        if (startupError != null) {
            failToStart(startupError);
            return;
        }
        try {
            showMainWindow(stage);
        } catch (IOException | RuntimeException | LinkageError e) {
            failToStart(e);
        }
    }

    /**
     * Ghi log, hiện hộp thoại (AC-009-2 hoặc AC-009-12) rồi thoát khi hộp thoại đóng. Nếu chính hộp thoại
     * không hiện được thì không còn cửa sổ nào để {@code implicitExit} kích hoạt, và JVM cùng context Spring
     * (khóa file H2) sẽ sống ngầm: vì vậy ghi log một lần rồi gọi {@link Platform#exit()}; {@link #stop()}
     * vẫn đóng context.
     */
    private void failToStart(Throwable failure) {
        StartupFailure kind = StartupFailure.classify(failure);
        kind.log(LOG, failure);
        try {
            ErrorDialog.show(kind.message(), Platform::exit);
        } catch (RuntimeException | LinkageError dialogFailure) {
            LOG.error("Could not show the startup error dialog",
                    SafeExceptionLog.withoutDatabaseMessages(dialogFailure));
            Platform.exit();
        }
    }

    private void showMainWindow(Stage stage) throws IOException {
        Theme.loadFonts();

        URL fxml = MiniShopApp.class.getResource("/ui/main-window.fxml");
        if (fxml == null) {
            throw new IllegalStateException("Thiếu /ui/main-window.fxml");
        }
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(context::getBean);
        Parent root = loader.load();

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(INITIAL_WIDTH, screen.getWidth());
        double height = Math.min(INITIAL_HEIGHT, screen.getHeight());

        Scene scene = new Scene(root, width, height);
        Theme.apply(scene);

        stage.setTitle(TITLE);
        // Cỡ tối thiểu cũng không vượt vùng nhìn thấy, nếu không cạnh dưới sẽ chìm dưới thanh tác vụ (màn 1366x720).
        stage.setMinWidth(Math.min(MIN_WIDTH, screen.getWidth()));
        stage.setMinHeight(Math.min(MIN_HEIGHT, screen.getHeight()));
        stage.setScene(scene);
        addWindowIcons(stage);
        stage.show();
        // Dấu mốc "app đã mở thật": Boot không ghi "Started …" (logger đã tắt, xem createContext), nên job CI
        // package-windows chờ đúng dòng này trong file log của bản đóng gói. Đổi chữ thì sửa cả ci.yml.
        LOG.info(MAIN_WINDOW_SHOWN);
        // Một lần sau khi context đã dựng và cửa sổ đã hiện: nạp sẵn ImageIO ngoài luồng giao diện
        context.getBeanProvider(ImageLoader.class).ifAvailable(ImageLoader::warmUp);
    }

    /**
     * Biểu tượng của cửa sổ chính (thanh tiêu đề, thanh tác vụ, Alt+Tab): bốn PNG vì JavaFX không đọc SVG. Thiếu
     * hoặc hỏng file nào thì bỏ file đó, ghi một dòng WARN và vẫn mở cửa sổ (giống {@link Theme}).
     */
    private static void addWindowIcons(Stage stage) {
        for (int size : ICON_SIZES) {
            String path = "/ui/icons/app-icon-" + size + ".png";
            try (InputStream in = MiniShopApp.class.getResourceAsStream(path)) {
                if (in == null) {
                    LOG.warn("Window icon missing: {}", path);
                    continue;
                }
                Image image = new Image(in);
                if (image.isError()) {
                    LOG.warn("Window icon unreadable: {}", path);
                    continue;
                }
                stage.getIcons().add(image);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Window icon failed: {}: {}", path, e.toString());
            }
        }
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }
}
