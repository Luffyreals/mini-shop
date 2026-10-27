package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import com.minishop.support.ManualBackgroundTasks;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The WebP path of {@link ImageLoader} (D3 review fixes): one decode per key shared by every waiter, the result
 * cached as a {@link WritableImage}, a failed key that is never retried, and failures of any kind that finish the key
 * without a callback and without reaching the uncaught handler. The decoder is a mock and the background tasks are
 * controlled by the test, so nothing is decoded for real. {@code WritableImage} needs the JavaFX toolkit, so every
 * call runs on the FX thread.
 */
@Tag("ui")
class ImageLoaderWebpTest {

    private static final String URL_A = Path.of("target", "webp-a.webp").toAbsolutePath().toUri().toString();
    private static final String URL_B = Path.of("target", "webp-b.webp").toAbsolutePath().toUri().toString();
    private static final double W = 80;
    private static final double H = 100;

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    private static Optional<WebpDecoder.DecodedImage> tiny() {
        return Optional.of(new WebpDecoder.DecodedImage(2, 3, new int[] {
                0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF, 0xFF000000, 0xFF808080}));
    }

    private static WebpDecoder decoderReturning(Optional<WebpDecoder.DecodedImage> result) {
        WebpDecoder decoder = mock(WebpDecoder.class);
        when(decoder.decode(any(Path.class), anyInt(), anyInt(), anyBoolean())).thenReturn(result);
        return decoder;
    }

    private static int pendingKeys(ImageLoader loader) {
        try {
            Field field = ImageLoader.class.getDeclaredField("pendingWebp");
            field.setAccessible(true);
            synchronized (loader) {
                return ((Map<?, ?>) field.get(loader)).size();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ sharing and caching

    @Test
    void whenLoaded_twiceForTheSameKey_decodesOnceAndBothCallbacksGetTheSameImage() {
        WebpDecoder decoder = decoderReturning(tiny());
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        List<Image> first = new ArrayList<>();
        List<Image> second = new ArrayList<>();

        FxTestSupport.runOnFx(() -> {
            assertThat(loader.load(URL_A, W, H)).as("not decoded yet").isNull();
            loader.whenLoaded(URL_A, W, H, first::add);
            loader.whenLoaded(URL_A, W, H, second::add);
        });

        assertThat(tasks.submittedCount()).isEqualTo(1);
        assertThat(first).isEmpty();

        FxTestSupport.runOnFx(() -> tasks.run(0));

        verify(decoder, times(1)).decode(any(Path.class), eq(80), eq(100), eq(false));
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0)).isInstanceOf(WritableImage.class).isSameAs(second.get(0));
        assertThat(first.get(0).getWidth()).isEqualTo(2);
        assertThat(first.get(0).getHeight()).isEqualTo(3);
        assertThat(pendingKeys(loader)).isZero();
    }

    @Test
    void load_afterSuccess_returnsTheCachedImage_andWhenLoadedCallsBackAtOnce() {
        WebpDecoder decoder = decoderReturning(tiny());
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        List<Image> delivered = new ArrayList<>();
        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));
        FxTestSupport.runOnFx(() -> tasks.run(0));

        Image cached = FxTestSupport.callOnFx(() -> loader.load(URL_A, W, H));
        List<Image> late = new ArrayList<>();
        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, late::add));

        assertThat(cached).isNotNull().isSameAs(delivered.get(0));
        assertThat(late).containsExactly(cached);
        assertThat(tasks.submittedCount()).as("no second decode").isEqualTo(1);
        assertThat(loader.cachedCount()).isEqualTo(1);
    }

    @Test
    void coverAndFitRequestsForTheSameUrl_areSeparateCacheEntriesWithTheirOwnDecode() {
        WebpDecoder decoder = decoderReturning(tiny());
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        List<Image> fit = new ArrayList<>();
        List<Image> cover = new ArrayList<>();

        FxTestSupport.runOnFx(() -> {
            loader.whenLoaded(URL_A, W, H, fit::add);
            loader.whenLoadedCover(URL_A, W, H, cover::add);
        });

        assertThat(tasks.submittedCount()).isEqualTo(2);

        FxTestSupport.runOnFx(tasks::runAll);

        verify(decoder, times(1)).decode(any(Path.class), eq(80), eq(100), eq(false));
        verify(decoder, times(1)).decode(any(Path.class), eq(80), eq(100), eq(true));
        assertThat(fit).hasSize(1);
        assertThat(cover).hasSize(1);
        assertThat(fit.get(0)).isNotSameAs(cover.get(0));
        assertThat(loader.cachedCount()).isEqualTo(2);
        assertThat(FxTestSupport.callOnFx(() -> loader.load(URL_A, W, H))).isSameAs(fit.get(0));
        assertThat(FxTestSupport.callOnFx(() -> loader.loadCover(URL_A, W, H))).isSameAs(cover.get(0));
    }

    @Test
    void coverRequest_whenOnlyTheFitImageIsCached_isStillNotReady() {
        WebpDecoder decoder = decoderReturning(tiny());
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, image -> { }));
        FxTestSupport.runOnFx(() -> tasks.run(0));

        assertThat(FxTestSupport.callOnFx(() -> loader.loadCover(URL_A, W, H))).isNull();
    }

    // ------------------------------------------------------------------ empty result

    @Test
    void whenLoaded_whenTheDecoderReturnsEmpty_callsNobodyAndNeverDecodesTheKeyAgain() {
        WebpDecoder decoder = decoderReturning(Optional.empty());
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        List<Image> delivered = new ArrayList<>();
        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));

        FxTestSupport.runOnFx(() -> tasks.run(0));
        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));

        assertThat(delivered).isEmpty();
        assertThat(tasks.submittedCount()).as("the failed key is not retried").isEqualTo(1);
        verify(decoder, times(1)).decode(any(Path.class), anyInt(), anyInt(), anyBoolean());
        assertThat(pendingKeys(loader)).isZero();
        assertThat(FxTestSupport.callOnFx(() -> loader.load(URL_A, W, H))).isNull();
        assertThat(loader.cachedCount()).isZero();
    }

    @Test
    void whenLoaded_afterAFailureOfOneKey_anotherUrlStillDecodes() {
        WebpDecoder decoder = mock(WebpDecoder.class);
        when(decoder.decode(any(Path.class), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(Optional.empty())
                .thenReturn(tiny());
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        List<Image> delivered = new ArrayList<>();
        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));
        FxTestSupport.runOnFx(() -> tasks.run(0));

        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_B, W, H, delivered::add));
        FxTestSupport.runOnFx(() -> tasks.run(1));

        assertThat(delivered).hasSize(1);
    }

    // ------------------------------------------------------------------ failures of any kind

    @Test
    void whenLoaded_whenTheDecoderThrowsOutOfMemoryError_cleansUpAndCallsNobody() {
        assertDecoderFailureIsContained(new OutOfMemoryError("test"));
    }

    @Test
    void whenLoaded_whenTheDecoderThrowsServiceConfigurationError_cleansUpAndCallsNobody() {
        assertDecoderFailureIsContained(new ServiceConfigurationError("test"));
    }

    @Test
    void whenLoaded_whenTheDecoderThrowsLinkageError_cleansUpAndCallsNobody() {
        assertDecoderFailureIsContained(new NoClassDefFoundError("test"));
    }

    @Test
    void whenLoaded_whenTheDecoderThrowsRuntimeException_cleansUpAndCallsNobody() {
        assertDecoderFailureIsContained(new IllegalStateException("test"));
    }

    private static void assertDecoderFailureIsContained(Throwable failure) {
        WebpDecoder decoder = mock(WebpDecoder.class);
        when(decoder.decode(any(Path.class), anyInt(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            throw failure;
        });
        DirectBackgroundTasks tasks = new DirectBackgroundTasks();
        ImageLoader loader = new ImageLoader(tasks, decoder);
        List<Image> delivered = new ArrayList<>();

        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));

        assertThat(tasks.unhandled()).as("nothing reaches the uncaught handler").isEmpty();
        assertThat(delivered).isEmpty();
        assertThat(pendingKeys(loader)).as("the key is not stuck as pending").isZero();

        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));

        assertThat(tasks.submittedCount()).as("a later call neither hangs nor decodes again").isEqualTo(1);
        assertThat(delivered).isEmpty();
        assertThat(tasks.unhandled()).isEmpty();
    }

    @Test
    void whenLoaded_whenTheSubmittedWorkFailsInAnyOtherWay_finishesTheKeyAsEmpty() {
        // The safety net: a failure that decode() did not catch arrives as onFailure and must not be stuck either
        List<Throwable> reportedAsUncaught = new ArrayList<>();
        BackgroundTasks failing = new BackgroundTasks() {
            @Override
            public <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess,
                                     Consumer<? super Throwable> onFailure) {
                if (onFailure == null) {
                    reportedAsUncaught.add(new IllegalStateException("no onFailure given"));
                } else {
                    onFailure.accept(new IllegalStateException("executor refused"));
                }
                return new Handle() {
                    @Override
                    public void cancel() {
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                };
            }
        };
        ImageLoader loader = new ImageLoader(failing, mock(WebpDecoder.class));
        List<Image> delivered = new ArrayList<>();

        FxTestSupport.runOnFx(() -> loader.whenLoaded(URL_A, W, H, delivered::add));

        assertThat(reportedAsUncaught).isEmpty();
        assertThat(delivered).isEmpty();
        assertThat(pendingKeys(loader)).isZero();
        assertThat(FxTestSupport.callOnFx(() -> loader.load(URL_A, W, H))).isNull();
    }

    @Test
    void whenLoaded_forAJpgOrAnEmptyUrl_doesNothing() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, mock(WebpDecoder.class));
        List<Image> delivered = new ArrayList<>();

        FxTestSupport.runOnFx(() -> {
            loader.whenLoaded(Path.of("target", "x.jpg").toAbsolutePath().toUri().toString(), W, H, delivered::add);
            loader.whenLoaded("", W, H, delivered::add);
            loader.whenLoaded(null, W, H, delivered::add);
        });

        assertThat(tasks.submittedCount()).isZero();
        assertThat(delivered).isEmpty();
    }

    // ------------------------------------------------------------------ warmUp

    @Test
    void warmUp_submitsExactlyOneTask_andRunningItDoesNotThrow() {
        ManualBackgroundTasks tasks = ManualBackgroundTasks.honoringCancel();
        ImageLoader loader = new ImageLoader(tasks, mock(WebpDecoder.class));

        loader.warmUp();

        assertThat(tasks.submittedCount()).isEqualTo(1);
        tasks.runAll();
        assertThat(tasks.submittedCount()).isEqualTo(1);
    }

    @Test
    void warmUp_whenItFails_theFailureIsSwallowed() {
        List<Consumer<? super Throwable>> failureCallbacks = new ArrayList<>();
        List<Throwable> uncaught = new ArrayList<>();
        BackgroundTasks capturing = new BackgroundTasks() {
            @Override
            public <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess,
                                     Consumer<? super Throwable> onFailure) {
                failureCallbacks.add(onFailure);
                return new Handle() {
                    @Override
                    public void cancel() {
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                };
            }
        };
        ImageLoader loader = new ImageLoader(capturing, mock(WebpDecoder.class));

        loader.warmUp();

        assertThat(failureCallbacks).as("one task with an onFailure").hasSize(1).doesNotContainNull();
        try {
            failureCallbacks.get(0).accept(new IllegalStateException("no reader"));
        } catch (RuntimeException e) {
            uncaught.add(e);
        }
        assertThat(uncaught).isEmpty();
    }
}
