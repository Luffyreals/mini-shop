package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import com.minishop.support.ManualBackgroundTasks;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ImageTile} with WebP images (D3 review fixes): a late decode result that belongs to an older {@code show}
 * is dropped, the placeholder shows while waiting, and the tile keeps its image across leaving and re-entering a
 * scene. The decoder is a mock (image A is 2x3, image B is 4x5) and the tasks are controlled by the test; the
 * toolkit is needed for the nodes. No window is opened.
 */
@Tag("ui")
class ImageTileTest {

    private static final String URL_A = Path.of("target", "tile-a.webp").toAbsolutePath().toUri().toString();
    private static final String URL_B = Path.of("target", "tile-b.webp").toAbsolutePath().toUri().toString();

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    /** A decoder whose answer depends on the file name: tile-a gives 2x3, tile-b gives 4x5. */
    private static WebpDecoder decoder() {
        WebpDecoder decoder = mock(WebpDecoder.class);
        when(decoder.decode(any(Path.class), anyInt(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            Path file = invocation.getArgument(0);
            boolean isA = file.getFileName().toString().contains("tile-a");
            int width = isA ? 2 : 4;
            int height = isA ? 3 : 5;
            return Optional.of(new WebpDecoder.DecodedImage(width, height, new int[width * height]));
        });
        return decoder;
    }

    private static ImageView imageView(ImageTile tile) {
        return tile.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast)
                .findFirst().orElseThrow();
    }

    private static SVGPath placeholder(ImageTile tile) {
        return tile.getChildren().stream().filter(SVGPath.class::isInstance).map(SVGPath.class::cast)
                .findFirst().orElseThrow();
    }

    private static boolean showsImage(ImageTile tile) {
        return FxTestSupport.callOnFx(() -> imageView(tile).isVisible() && !placeholder(tile).isVisible());
    }

    private static boolean showsPlaceholder(ImageTile tile) {
        return FxTestSupport.callOnFx(() -> placeholder(tile).isVisible() && !imageView(tile).isVisible());
    }

    // ------------------------------------------------------------------ stale results

    @Test
    void show_aNewUrlBeforeTheOldDecodeFinishes_dropsTheOldResult() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder());
        ImageTile tile = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));

        FxTestSupport.runOnFx(() -> {
            tile.show(URL_A, "A");
            tile.show(URL_B, "B");
        });

        assertThat(tasks.submittedCount()).isEqualTo(2);
        assertThat(showsPlaceholder(tile)).as("placeholder while both decodes wait").isTrue();

        FxTestSupport.runOnFx(() -> tasks.run(0));

        assertThat(FxTestSupport.callOnFx(() -> imageView(tile).getImage())).as("A is stale").isNull();
        assertThat(showsPlaceholder(tile)).as("still the placeholder after A finished").isTrue();

        FxTestSupport.runOnFx(() -> tasks.run(1));

        assertThat(FxTestSupport.callOnFx(() -> imageView(tile).getImage().getWidth())).isEqualTo(4);
        assertThat(showsImage(tile)).isTrue();
        assertThat(tile.getAccessibleText()).isEqualTo("B");
    }

    @Test
    void show_theNewerDecodeFinishingFirst_isNotReplacedByTheOlderOne() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder());
        ImageTile tile = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));
        FxTestSupport.runOnFx(() -> {
            tile.show(URL_A, "A");
            tile.show(URL_B, "B");
        });

        FxTestSupport.runOnFx(() -> tasks.run(1));
        FxTestSupport.runOnFx(() -> tasks.run(0));

        assertThat(FxTestSupport.callOnFx(() -> imageView(tile).getImage().getWidth()))
                .as("B stays after A's late result").isEqualTo(4);
    }

    @Test
    void show_nullUrlBeforeTheDecodeFinishes_leavesThePlaceholder() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder());
        ImageTile tile = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));
        FxTestSupport.runOnFx(() -> {
            tile.show(URL_A, "A");
            tile.show(null, null);
        });

        FxTestSupport.runOnFx(() -> tasks.run(0));

        assertThat(FxTestSupport.callOnFx(() -> imageView(tile).getImage())).isNull();
        assertThat(showsPlaceholder(tile)).isTrue();
        assertThat(tile.getAccessibleText()).isEqualTo("Chưa có ảnh");
    }

    @Test
    void show_aSecondTileWaitingForTheSameUrl_alsoGetsItsImage() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder());
        ImageTile first = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));
        ImageTile second = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));
        FxTestSupport.runOnFx(() -> {
            first.show(URL_A, "A");
            second.show(URL_A, "A");
        });

        FxTestSupport.runOnFx(tasks::runAll);

        assertThat(tasks.submittedCount()).isEqualTo(1);
        assertThat(showsImage(first)).isTrue();
        assertThat(showsImage(second)).isTrue();
    }

    // ------------------------------------------------------------------ scene transitions

    @Test
    void tile_removedFromItsSceneAndAddedBack_stillShowsTheImage() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder());
        ImageTile tile = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));
        StackPane root = FxTestSupport.callOnFx(() -> {
            StackPane pane = new StackPane(tile);
            new Scene(pane, 200, 200);
            return pane;
        });
        FxTestSupport.runOnFx(() -> tile.show(URL_A, "A"));
        FxTestSupport.runOnFx(() -> tasks.run(0));
        assertThat(FxTestSupport.callOnFx(tile::getScene)).isNotNull();
        assertThat(showsImage(tile)).isTrue();

        FxTestSupport.runOnFx(() -> root.getChildren().remove(tile));

        assertThat(FxTestSupport.callOnFx(tile::getScene)).as("left the scene").isNull();
        assertThat(showsImage(tile)).as("the image is kept while detached").isTrue();

        FxTestSupport.runOnFx(() -> root.getChildren().add(tile));

        assertThat(FxTestSupport.callOnFx(tile::getScene)).as("back in the scene").isNotNull();
        assertThat(showsImage(tile)).isTrue();
        assertThat(FxTestSupport.callOnFx(() -> imageView(tile).getImage().getWidth())).isEqualTo(2);
    }

    @Test
    void tile_resultArrivingWhileOutsideAnyScene_isStillShownAfterItEntersOne() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder());
        ImageTile tile = FxTestSupport.callOnFx(() -> new ImageTile(loader, 80, 100));
        FxTestSupport.runOnFx(() -> tile.show(URL_A, "A"));
        FxTestSupport.runOnFx(() -> tasks.run(0));
        assertThat(FxTestSupport.callOnFx(tile::getScene)).isNull();

        FxTestSupport.runOnFx(() -> new Scene(new StackPane(tile), 200, 200));

        assertThat(FxTestSupport.callOnFx(tile::getScene)).isNotNull();
        assertThat(showsImage(tile)).isTrue();
    }
}
