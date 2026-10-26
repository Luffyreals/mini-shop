package com.minishop.desktop;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.AccessibleRole;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * Ô ảnh bo góc (class {@code image-well}: bo 20, nền {@code surface-muted}) có ô giữ chỗ. Ô giữ chỗ hiện khi
 * chưa có URL, ảnh đang nạp, hoặc nạp lỗi (kể cả WebP). Ảnh được nạp nền qua {@link ImageLoader} và vừa khít
 * trong ô, giữ tỉ lệ.
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
    private final ImageView view = new ImageView();
    private final SVGPath placeholder = new SVGPath();
    private final ChangeListener<Object> stateListener = (observable, oldValue, newValue) -> refresh();
    private final WeakChangeListener<Object> weakStateListener = new WeakChangeListener<>(stateListener);
    private Image current;

    public ImageTile(ImageLoader loader, double width, double height) {
        this.loader = loader;
        this.imageWidth = width;
        this.imageHeight = height;
        getStyleClass().addAll("image-well", "image-tile");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);

        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(width);
        view.setFitHeight(height);

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
        detach(current);
        current = url == null ? null : loader.load(url, imageWidth, imageHeight);
        view.setImage(current);
        if (current != null) {
            current.progressProperty().addListener(weakStateListener);
            current.errorProperty().addListener(weakStateListener);
        }
        setAccessibleText(alt == null || alt.isBlank() ? NO_IMAGE_TEXT : alt);
        refresh();
    }

    private void detach(Image image) {
        if (image != null) {
            image.progressProperty().removeListener(weakStateListener);
            image.errorProperty().removeListener(weakStateListener);
        }
    }

    private void refresh() {
        boolean ready = current != null && !current.isError() && current.getProgress() >= 1.0;
        view.setVisible(ready);
        placeholder.setVisible(!ready);
    }
}
