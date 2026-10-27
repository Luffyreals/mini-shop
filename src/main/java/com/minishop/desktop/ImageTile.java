package com.minishop.desktop;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Rectangle2D;
import javafx.scene.AccessibleRole;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * Ô ảnh bo góc (class {@code image-well}: bo 20, nền {@code surface-muted}) có ô giữ chỗ. Ô giữ chỗ hiện khi
 * chưa có URL, ảnh đang nạp (WebP: đang giải mã), hoặc nạp lỗi. Ảnh được nạp nền qua {@link ImageLoader} và
 * <b>phủ kín</b> ô ("cover"): phóng vừa đủ để phủ hết ô, căn giữa, phần thừa bị cắt bởi góc bo, nên ảnh rộng không
 * có dải xám hai bên. Phần cắt là {@code viewport} của {@link ImageView}, tính lại khi ô đổi cỡ. Gọi {@link #show} trên luồng giao diện; kết quả đến muộn của một lần {@code show}
 * cũ không ghi đè lần {@code show} mới hơn.
 * <p>
 * Cỡ cố định ({@link #ImageTile(ImageLoader, double, double)}) hoặc co giãn theo chiều rộng được cấp
 * ({@link #flexible}): thẻ sản phẩm dùng ô co giãn để lưới 4/3/2 cột đổi chiều rộng mà ô ảnh vẫn giữ tỉ lệ.
 * <p>
 * Dùng: {@code tile.show(product.imageUrl(), product.name())}. Văn bản thay thế ({@code alt}) cũng là
 * accessible text của ô; để trống thì đọc là "Chưa có ảnh".
 */
public final class ImageTile extends StackPane {

    /** Đường kính cung của góc clip: gấp đôi bán kính 20 của {@code .image-well} trong theme.css. */
    private static final double CLIP_ARC = 40;
    private static final String NO_IMAGE_TEXT = "Chưa có ảnh";

    private final ImageLoader loader;
    private final double imageWidth;
    private final double imageHeight;
    private final boolean flexible;
    private final ImageView view = new ImageView();
    private final SVGPath placeholder = new SVGPath();
    private final ChangeListener<Object> stateListener = (observable, oldValue, newValue) -> refresh();
    private final WeakChangeListener<Object> weakStateListener = new WeakChangeListener<>(stateListener);
    private Image current;
    /** Tăng mỗi lần {@link #show}; kết quả WebP đến muộn chỉ được nhận nếu vẫn là yêu cầu mới nhất. */
    private int requestCounter;

    /** Ô có cỡ cố định {@code width} x {@code height} điểm ảnh, cũng là cỡ yêu cầu khi nạp ảnh. */
    public ImageTile(ImageLoader loader, double width, double height) {
        this(loader, width, height, false);
    }

    /**
     * Ô co giãn: chiều rộng do bố cục cấp (không bao giờ vượt quá chỗ được cấp), chiều cao luôn theo tỉ lệ
     * {@code requestWidth : requestHeight}. Hai số này cũng là cỡ yêu cầu khi nạp ảnh, nên chọn gần cỡ hiển thị
     * lớn nhất thường gặp.
     */
    public static ImageTile flexible(ImageLoader loader, double requestWidth, double requestHeight) {
        return new ImageTile(loader, requestWidth, requestHeight, true);
    }

    private ImageTile(ImageLoader loader, double width, double height, boolean flexible) {
        this.loader = loader;
        this.imageWidth = width;
        this.imageHeight = height;
        this.flexible = flexible;
        getStyleClass().addAll("image-well", "image-tile");

        // Cỡ hiển thị luôn đúng cỡ ô; phần ảnh được chọn bằng viewport (xem updateViewport), nên ảnh không méo
        view.setPreserveRatio(false);
        view.setSmooth(true);
        if (flexible) {
            view.fitWidthProperty().bind(widthProperty());
            view.fitHeightProperty().bind(heightProperty());
        } else {
            setMinSize(width, height);
            setPrefSize(width, height);
            setMaxSize(width, height);
            view.setFitWidth(width);
            view.setFitHeight(height);
        }

        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                detach(current);
            } else {
                // Trong lúc rời Scene có thể ảnh đã nạp xong mà ô không biết: nghe lại rồi vẽ lại theo trạng thái hiện tại
                listenTo(current);
                refresh();
            }
        });
        widthProperty().addListener((observable, oldValue, newValue) -> updateViewport());
        heightProperty().addListener((observable, oldValue, newValue) -> updateViewport());

        placeholder.setContent("M3 5.5a2.5 2.5 0 0 1 2.5-2.5h13A2.5 2.5 0 0 1 21 5.5v13a2.5 2.5 0 0 1-2.5 2.5h-13"
                + "A2.5 2.5 0 0 1 3 18.5z M3 16l5-5 4 4 3-3 6 6 M9 8.5a1 1 0 1 0 0 .01");
        placeholder.getStyleClass().addAll("icon-stroke", "placeholder-icon");
        placeholder.setMouseTransparent(true);

        Rectangle clip = new Rectangle();
        clip.setArcWidth(CLIP_ARC);
        clip.setArcHeight(CLIP_ARC);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        getChildren().addAll(placeholder, view);
        setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        show(null, null);
    }

    /**
     * Hiện ảnh của {@code url} (xem {@link ImageLoader#load}); {@code url} rỗng thì chỉ có ô giữ chỗ.
     *
     * @param alt mô tả ảnh cho trình đọc màn hình, thường là tên sản phẩm
     */
    public void show(String url, String alt) {
        int request = ++requestCounter;
        attach(url == null ? null : loader.loadCover(url, imageWidth, imageHeight));
        if (current == null && url != null) {
            // WebP: ảnh đến sau khi giải mã ngoài luồng giao diện. Ô giữ chỗ hiện trong lúc chờ; nếu giữa chừng
            // ô được yêu cầu hiện URL khác (request đã đổi) thì kết quả muộn bị bỏ.
            loader.whenLoadedCover(url, imageWidth, imageHeight, image -> {
                if (request == requestCounter) {
                    attach(image);
                }
            });
        }
        setAccessibleText(alt == null || alt.isBlank() ? NO_IMAGE_TEXT : alt);
    }

    @Override
    public Orientation getContentBias() {
        return flexible ? Orientation.HORIZONTAL : super.getContentBias();
    }

    @Override
    protected double computePrefWidth(double height) {
        return flexible ? imageWidth : super.computePrefWidth(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        if (!flexible) {
            return super.computePrefHeight(width);
        }
        return width < 0 ? imageHeight : width * imageHeight / imageWidth;
    }

    @Override
    protected double computeMinWidth(double height) {
        return flexible ? 0 : super.computeMinWidth(height);
    }

    @Override
    protected double computeMinHeight(double width) {
        return flexible ? computePrefHeight(width) : super.computeMinHeight(width);
    }

    private void attach(Image image) {
        detach(current);
        current = image;
        view.setImage(current);
        listenTo(current);
        refresh();
    }

    /**
     * Nghe tiến độ và lỗi của ảnh chỉ khi ô đang nằm trong một {@link javafx.scene.Scene}: ảnh trong cache sống lâu
     * hơn ô, và trình nghe yếu của ô đã bị bỏ đi vẫn nằm lại trên ảnh cho tới khi GC dọn. Ô rời Scene thì gỡ, quay lại
     * thì nghe lại (xem constructor).
     */
    private void listenTo(Image image) {
        if (image != null && getScene() != null) {
            detach(image);
            image.progressProperty().addListener(weakStateListener);
            image.errorProperty().addListener(weakStateListener);
        }
    }

    private void detach(Image image) {
        if (image != null) {
            image.progressProperty().removeListener(weakStateListener);
            image.errorProperty().removeListener(weakStateListener);
        }
    }

    private void refresh() {
        // WritableImage (WebP đã giải mã) có đủ điểm ảnh ngay khi được giao
        boolean ready = current != null && !current.isError()
                && (current instanceof WritableImage || current.getProgress() >= 1.0);
        view.setVisible(ready);
        placeholder.setVisible(!ready);
        updateViewport();
    }

    /**
     * Chọn phần ảnh hiện trong ô: hình chữ nhật cùng tỉ lệ với ô, lớn nhất có thể, căn giữa. {@link ImageView} kéo
     * phần đó ra đúng cỡ ô nên ảnh phủ kín mà không méo.
     */
    private void updateViewport() {
        double imageW = current == null ? 0 : current.getWidth();
        double imageH = current == null ? 0 : current.getHeight();
        double tileW = flexible ? getWidth() : imageWidth;
        double tileH = flexible ? getHeight() : imageHeight;
        if (imageW <= 0 || imageH <= 0 || tileW <= 0 || tileH <= 0) {
            view.setViewport(null);
            return;
        }
        double cropW = imageW;
        double cropH = imageH;
        if (imageW * tileH > tileW * imageH) {
            cropW = imageH * tileW / tileH;
        } else {
            cropH = imageW * tileH / tileW;
        }
        view.setViewport(new Rectangle2D((imageW - cropW) / 2, (imageH - cropH) / 2, cropW, cropH));
    }
}
