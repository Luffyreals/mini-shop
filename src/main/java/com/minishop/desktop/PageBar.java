package com.minishop.desktop;

import com.minishop.common.PageNav;
import java.util.function.IntConsumer;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * Thanh phân trang dùng chung (QĐ9 của D3): "Trang trước", các số trang, "Trang sau". Trang hiện tại là nhãn
 * ({@code .page-current}) không bấm được; các số khác là nút ({@code .page-button}) có {@code accessibleText}
 * là số trang. "Trang trước" và "Trang sau" bị khóa ở đầu và cuối. Thanh ẩn ({@code visible} và {@code managed}
 * đều {@code false}) khi chỉ có một trang hoặc chưa có dữ liệu.
 * <p>
 * Id cố định cho test: {@code pagePrev}, {@code pageNext}, {@code pageCurrent}, {@code pageButton-{n}}; id của
 * chính thanh do màn đặt (D3 dùng {@code pageBar}). Gọi {@link #show} trên luồng giao diện; mỗi lần gọi dựng
 * lại các nút.
 */
public final class PageBar extends HBox {

    static final String PREVIOUS_TEXT = "Trang trước";
    static final String NEXT_TEXT = "Trang sau";

    /** @param accessibleLabel chữ cho trình đọc màn hình của cả thanh (D3 dùng "Phân trang") */
    public PageBar(String accessibleLabel) {
        getStyleClass().add("page-bar");
        setAlignment(Pos.CENTER);
        setAccessibleRole(AccessibleRole.PAGINATION);
        setAccessibleText(accessibleLabel);
        show(null, page -> { });
    }

    /**
     * Vẽ {@code nav}; {@code null} hoặc chỉ một trang thì ẩn thanh.
     *
     * @param onPage nhận số trang (từ 1) khi người dùng bấm "Trang trước", "Trang sau" hoặc một số
     */
    public void show(PageNav nav, IntConsumer onPage) {
        getChildren().clear();
        boolean visible = nav != null && nav.totalPages() > 1;
        setVisible(visible);
        setManaged(visible);
        if (!visible) {
            return;
        }

        Button previous = button("pagePrev", PREVIOUS_TEXT, PREVIOUS_TEXT);
        previous.setDisable(!nav.hasPrevious());
        previous.setOnAction(event -> onPage.accept(nav.current() - 1));
        getChildren().add(previous);

        for (int number : nav.pages()) {
            if (number == nav.current()) {
                Label current = new Label(String.valueOf(number));
                current.setId("pageCurrent");
                current.getStyleClass().add("page-current");
                current.setAccessibleText(String.valueOf(number));
                getChildren().add(current);
            } else {
                Button page = button("pageButton-" + number, String.valueOf(number), String.valueOf(number));
                page.setOnAction(event -> onPage.accept(number));
                getChildren().add(page);
            }
        }

        Button next = button("pageNext", NEXT_TEXT, NEXT_TEXT);
        next.setDisable(!nav.hasNext());
        next.setOnAction(event -> onPage.accept(nav.current() + 1));
        getChildren().add(next);
    }

    private static Button button(String id, String text, String accessibleText) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().addAll("page-button", "pill");
        button.setAccessibleText(accessibleText);
        return button;
    }
}
