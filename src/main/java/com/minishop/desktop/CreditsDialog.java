package com.minishop.desktop;

import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Hộp thoại "Nguồn ảnh và giấy phép": ghi công ảnh sản phẩm, phông chữ và biểu tượng, đọc từ {@link Credits}
 * (QĐ11 của D6). Dựng bằng {@link ModalDialogs#create}.
 * <p>
 * Bố cục: tiêu đề, đoạn giới thiệu, một vùng cuộn (một điểm dừng Tab, cuộn bằng phím mũi tên và PageDown) chứa
 * các mục "Ảnh sản phẩm", "Phông chữ", "Biểu tượng ứng dụng", rồi nút "Đóng" nằm ngoài vùng cuộn nên luôn thấy.
 * Thẻ không cao quá cửa sổ chủ. Focus đầu ở "Đóng"; Esc và "Đóng" đóng hộp thoại, focus quay về nút đã mở nó.
 * Giấy phép và nguồn là liên kết ({@link ExternalLinks}) khi có địa chỉ {@code https://}, không thì là chữ thường.
 * <p>
 * Gọi {@link #show()} trên luồng giao diện; đang mở thì chỉ đưa cửa sổ cũ lên trước, không mở cửa sổ thứ hai.
 * Không tạo {@code Stage} trong constructor (bean được dựng ngoài luồng giao diện). File được đọc một lần, ở lần
 * mở đầu (tài nguyên nhỏ trong jar, không có service hay database).
 * <p>
 * Id cho test: {@code #creditsDialog} (thẻ), {@code #creditsTitle}, {@code #creditsIntro}, {@code #creditsScroll},
 * {@code credit-image-{file}} (mỗi ảnh), {@code #creditsFont}, {@code #creditsIcon}, {@code credit-license-{file}} và
 * {@code credit-source-{file}} (liên kết hoặc chữ; {@code file} của họ phông và của biểu tượng cũng dùng như vậy),
 * {@code #creditsClose}. Khi có nhiều dòng {@code font} hoặc {@code icon}, dòng đầu giữ id trên, các dòng sau là
 * {@code creditsFont-2}, {@code creditsFont-3}...
 */
@Component
public class CreditsDialog {

    static final String TITLE = "Nguồn ảnh và giấy phép";
    static final String SECTION_IMAGES = "Ảnh sản phẩm";
    static final String SECTION_FONTS = "Phông chữ";
    static final String SECTION_ICONS = "Biểu tượng ứng dụng";
    static final String LABEL_IMAGE_NAME = "Tên ảnh";
    static final String LABEL_AUTHOR = "Tác giả";
    static final String LABEL_LICENSE = "Giấy phép";
    static final String LABEL_SOURCE = "Nguồn";
    static final String CLOSE = "Đóng";
    /** Chữ cho trình đọc màn hình của một liên kết: {@code "{tên}, mở trong trình duyệt"}. */
    static final String LINK_SUFFIX = ", mở trong trình duyệt";

    private static final double CARD_WIDTH = 640;
    /** Lề bóng của {@code .dialog-root} (32) và đệm thẻ {@code .dialog-card} (28), theo {@code theme.css}. */
    private static final double SHADOW_MARGIN = 32;
    private static final double CARD_PADDING = 28;
    private static final double ROUNDING_SLACK = 2;
    private static final double MIN_CARD_HEIGHT = 360;
    private static final double MIN_SCROLL_HEIGHT = 120;
    /** Bề rộng chừa cho thanh cuộn dọc khi đo chiều cao nội dung. */
    private static final double SCROLLBAR_ALLOWANCE = 16;
    private static final double LABEL_COLUMN = 96;

    private final ExternalLinks links;
    private Credits credits;
    private Stage stage;

    public CreditsDialog(ExternalLinks links) {
        this.links = Objects.requireNonNull(links, "links");
    }

    /** Hiện hộp thoại (không chặn). Đang hiện thì chỉ đưa lên trước. Gọi trên luồng giao diện. */
    public void show() {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        if (credits == null) {
            credits = Credits.load();
        }
        // Cùng cách chọn với ModalDialogs.create, nên cửa sổ nhận lại focus chính là cửa sổ chủ của hộp thoại
        Window opener = ModalDialogs.pickOwner();
        Node openerFocus = opener != null && opener.getScene() != null ? opener.getScene().getFocusOwner() : null;

        Build build = build(credits);
        Stage dialog = build.stage();
        stage = dialog;
        dialog.setOnHidden(event -> {
            if (stage == dialog) {
                stage = null;
            }
            restoreFocus(opener, openerFocus);
        });

        dialog.show();
        // sizeToScene đổi cỡ cửa sổ, và ModalDialogs tự căn giữa lại (có giới hạn trong màn hình) khi cỡ đổi
        dialog.sizeToScene();
        dialog.toFront();
        build.close().requestFocus();
    }

    private record Build(Stage stage, Button close) {
    }

    private Build build(Credits data) {
        DoubleProperty heightLimit = new SimpleDoubleProperty(Double.MAX_VALUE);

        Label title = new Label(TITLE);
        title.setId("creditsTitle");
        title.getStyleClass().add("text-h2");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(20);
        content.getStyleClass().add("credits-content");
        addSection(content, SECTION_IMAGES, data.images(), "credit-image-", "", true);
        addSection(content, SECTION_FONTS, data.fonts(), null, "creditsFont", false);
        addSection(content, SECTION_ICONS, data.icons(), null, "creditsIcon", false);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setId("creditsScroll");
        scroll.getStyleClass().add("screen-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPannable(false);
        // Một điểm dừng Tab: có focus thì mũi tên và PageDown cuộn được.
        scroll.setFocusTraversable(true);
        scroll.setMinHeight(MIN_SCROLL_HEIGHT);
        scroll.setAccessibleText(TITLE);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button close = new Button(CLOSE);
        close.setId("creditsClose");
        close.getStyleClass().addAll("pill", "button-primary");
        close.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            // Enter bấm nút đang có focus (JavaFX mặc định chỉ bấm bằng Space khi không có nút mặc định).
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                close.fire();
            }
        });
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox.setMargin(buttons, new Insets(4, 0, 0, 0));

        VBox card = new VBox(16) {
            @Override
            protected double computePrefHeight(double width) {
                return Math.min(super.computePrefHeight(width), heightLimit.get());
            }
        };
        card.setId("creditsDialog");
        card.getStyleClass().addAll("card", "dialog-card", "credits-dialog");
        card.setAlignment(Pos.TOP_LEFT);
        card.setMinWidth(CARD_WIDTH);
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setAccessibleText(TITLE);
        card.getChildren().add(title);
        if (!data.intro().isEmpty()) {
            Label intro = new Label(data.intro());
            intro.setId("creditsIntro");
            intro.getStyleClass().add("text-body");
            intro.setWrapText(true);
            intro.setMinHeight(Region.USE_PREF_SIZE);
            card.getChildren().add(intro);
        }
        card.getChildren().addAll(scroll, buttons);

        Stage dialog = ModalDialogs.create(card);
        limitToOwner(dialog, heightLimit, card);
        dialog.setOnCloseRequest(event -> {
            event.consume();
            dialog.close();
        });
        close.setOnAction(event -> dialog.close());
        dialog.getScene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                dialog.close();
            }
        });

        // Đo chiều cao nội dung ở đúng bề rộng của vùng cuộn, sau khi CSS đã áp (cỡ chữ quyết định số dòng).
        dialog.getScene().getRoot().applyCss();
        double contentWidth = CARD_WIDTH - 2 * CARD_PADDING - SCROLLBAR_ALLOWANCE;
        scroll.setPrefViewportHeight(content.prefHeight(contentWidth));
        return new Build(dialog, close);
    }

    // ------------------------------------------------------------------ nội dung

    private void addSection(VBox parent, String heading, List<Credits.Credit> entries, String idPrefix,
                            String fixedId, boolean showImageName) {
        if (entries.isEmpty()) {
            return;
        }
        VBox section = new VBox(12);
        Label sectionTitle = new Label(heading);
        sectionTitle.getStyleClass().addAll("text-h2", "credits-section-title");
        sectionTitle.setMinHeight(Region.USE_PREF_SIZE);
        section.getChildren().add(sectionTitle);
        for (int i = 0; i < entries.size(); i++) {
            Credits.Credit credit = entries.get(i);
            String id = idPrefix != null ? idPrefix + credit.file()
                    : i == 0 ? fixedId : fixedId + "-" + (i + 1);
            section.getChildren().add(entry(credit, id, showImageName));
        }
        parent.getChildren().add(section);
    }

    private VBox entry(Credits.Credit credit, String id, boolean showImageName) {
        VBox box = new VBox(4);
        box.setId(id);
        box.getStyleClass().add("credits-entry");

        Label name = new Label(credit.subject());
        name.getStyleClass().addAll("text-body", "credits-entry-title");
        name.setWrapText(true);
        name.setMinHeight(Region.USE_PREF_SIZE);
        box.getChildren().add(name);

        if (showImageName) {
            box.getChildren().add(field(LABEL_IMAGE_NAME, text(credit.title())));
        } else if (!credit.title().isEmpty()) {
            // Họ phông và biểu tượng: tên tác phẩm là một dòng chữ, không có nhãn (nhãn "Tên ảnh" chỉ dành cho ảnh).
            Label plain = text(credit.title());
            plain.getStyleClass().add("credits-field");
            box.getChildren().add(plain);
        }
        box.getChildren().add(field(LABEL_AUTHOR, text(credit.author())));
        box.getChildren().add(field(LABEL_LICENSE, linkOrText(credit.license(), credit.licenseUrl(),
                "credit-license-" + credit.file())));
        box.getChildren().add(field(LABEL_SOURCE, linkOrText(credit.source(), credit.sourceUrl(),
                "credit-source-" + credit.file())));
        return box;
    }

    /** Một dòng "Nhãn  giá trị": nhãn cột cố định, giá trị tự xuống dòng. */
    private static HBox field(String labelText, Node value) {
        Label label = new Label(labelText);
        label.getStyleClass().addAll("text-caption", "text-muted");
        label.setMinWidth(LABEL_COLUMN);
        label.setPrefWidth(LABEL_COLUMN);
        label.setMaxWidth(LABEL_COLUMN);
        label.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox row = new HBox(12, label, value);
        row.getStyleClass().add("credits-field");
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Label text(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("text-body");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMinWidth(0);
        return label;
    }

    /** Liên kết mở trình duyệt khi {@code url} là {@code https://} hợp lệ, không thì chữ thường (chọn và chép được). */
    private Node linkOrText(String shown, String url, String id) {
        if (shown.isEmpty()) {
            Label empty = text("");
            empty.setId(id);
            return empty;
        }
        if (!FxExternalLinks.isHttps(url)) {
            Label plain = text(shown);
            plain.setId(id);
            return plain;
        }
        Hyperlink link = new Hyperlink(shown);
        link.setId(id);
        link.getStyleClass().addAll("text-body", "credits-link");
        link.setWrapText(true);
        link.setMinHeight(Region.USE_PREF_SIZE);
        link.setMinWidth(0);
        link.setAccessibleText(shown + LINK_SUFFIX);
        link.setOnAction(event -> {
            link.setVisited(false);
            links.open(url);
        });
        return link;
    }

    // ------------------------------------------------------------------ cửa sổ

    /**
     * Thẻ không cao quá cửa sổ chủ (chiều cao cửa sổ trừ lề bóng hai phía): chiều cao ưa thích của thẻ bị chặn ở
     * mức đó và vùng cuộn co lại cho vừa; tiêu đề, giới thiệu và "Đóng" luôn thấy. Không có chủ thì lấy chiều
     * cao vùng làm việc của màn hình chính.
     */
    private static void limitToOwner(Stage dialog, DoubleProperty limit, Region card) {
        Window owner = dialog.getOwner();
        if (owner != null) {
            limit.bind(Bindings.createDoubleBinding(() -> limitFor(owner.getHeight()), owner.heightProperty()));
        } else {
            limit.set(limitFor(Screen.getPrimary().getVisualBounds().getHeight()));
        }
        limit.addListener((observable, was, now) -> card.requestLayout());
    }

    private static double limitFor(double windowHeight) {
        return Math.max(MIN_CARD_HEIGHT, windowHeight - 2 * SHADOW_MARGIN - ROUNDING_SLACK);
    }

    /** Trả focus về nút đã mở hộp thoại (thường là "Nguồn ảnh" ở chân trang), nếu nó còn trong cửa sổ đang hiện. */
    private static void restoreFocus(Window opener, Node openerFocus) {
        if (opener == null || !opener.isShowing() || openerFocus == null || openerFocus.getScene() == null
                || openerFocus.getScene().getWindow() != opener) {
            return;
        }
        openerFocus.requestFocus();
    }
}
