package com.minishop.desktop;

import java.util.List;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Bộ dựng bảng dùng chung (D6, QĐ13): {@code GridPane} cột cố định cộng cột co giãn, không dùng
 * {@code TableView}. Hàng tiêu đề và hàng dữ liệu dùng cùng danh sách cột nên các cột thẳng hàng.
 *
 * <p>Gom từ hai bản: D4 {@code order.OrderHistoryScreen} ({@code headerRow}, {@code dataRow}, {@code cell},
 * {@code fixed}, {@code growing}) và D5 {@code admin.AdminScreen} ({@code fixedColumn}, {@code growingColumn},
 * {@code headerRow}, {@code dataRow}, {@code cell}, {@code put}, {@code makeActivatable}, {@code show}). Bản gốc
 * là bản của {@code AdminScreen}: hàng tiêu đề thẳng cột dù cột co giãn nằm ở đâu, nên logic được chuyển nguyên
 * văn; chỉ tên class CSS dùng tên trung tính ({@code table-head}, {@code table-th}, {@code table-row-link}).
 *
 * <p>Cách dùng: dựng danh sách cột bằng {@link #fixedColumn} và {@link #growingColumn}; {@link #headerRow} một lần;
 * mỗi dòng dữ liệu {@link #dataRow} rồi {@link #put} từng ô (thường là {@link #cell}); hàng bấm được thì thêm
 * {@link #activatable}. Gọi trên luồng giao diện.
 */
public final class Tables {

    private Tables() {
    }

    /** Cột chiều rộng cố định. */
    public static ColumnConstraints fixedColumn(double width) {
        ColumnConstraints column = new ColumnConstraints(width, width, width);
        column.setHgrow(Priority.NEVER);
        return column;
    }

    /** Cột co giãn (nhận chỗ còn lại), rộng tối thiểu {@code minWidth}; chữ trong cột xuống dòng. */
    public static ColumnConstraints growingColumn(double minWidth) {
        ColumnConstraints column = new ColumnConstraints(minWidth, minWidth, Double.MAX_VALUE);
        column.setHgrow(Priority.ALWAYS);
        return column;
    }

    /**
     * Hàng tiêu đề của bảng ({@code .data-table .table-head}, nhãn {@code .table-th}); {@code names} có bao nhiêu
     * tên thì bấy nhiêu ô đầu. Đệm ngang 18 px (16 px đệm cộng 2 px viền của {@code .data-table-row}) nằm ở chính
     * lưới, giống hàng dữ liệu, nên các cột thẳng hàng dù cột co giãn nằm ở đâu (đệm không nằm trong từng ô, vì
     * kiểu đệm trong từng ô chỉ hợp khi cột co giãn nằm ở cuối).
     */
    public static GridPane headerRow(List<ColumnConstraints> columns, String... names) {
        GridPane header = baseGrid(columns);
        header.getStyleClass().addAll("data-table", "table-head");
        for (int column = 0; column < names.length; column++) {
            Label label = new Label(names[column]);
            label.getStyleClass().add("table-th");
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            header.add(label, column, 0);
        }
        return header;
    }

    /**
     * Hàng dữ liệu rỗng ({@code .data-table .data-table-row}); thêm ô bằng {@link #put}. {@code clickable} bật
     * kiểu hàng bấm được ({@code .table-row-link}: con trỏ tay, nền khi trỏ chuột, viền focus); khi đó gọi thêm
     * {@link #activatable}.
     */
    public static GridPane dataRow(List<ColumnConstraints> columns, boolean clickable) {
        GridPane grid = baseGrid(columns);
        grid.getStyleClass().addAll("data-table", "data-table-row");
        if (clickable) {
            grid.getStyleClass().add("table-row-link");
        }
        return grid;
    }

    /** Ô chữ của bảng: xuống dòng, không bị cắt "…" khi hiện lần đầu. */
    public static Label cell(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /** Đặt {@code node} vào cột {@code column} của hàng {@code row}, canh giữa theo chiều dọc, sát trái. */
    public static void put(GridPane row, Node node, int column) {
        row.add(node, column, 0);
        GridPane.setValignment(node, VPos.CENTER);
        GridPane.setHalignment(node, HPos.LEFT);
    }

    /**
     * Biến một node (hàng, ô số, dòng danh sách) thành điều khiển bấm được: nhận focus bàn phím, đọc là nút với
     * {@code accessibleText}, chạy {@code action} khi bấm chuột, Enter hoặc Space. Ở nút bên trong node (nếu có)
     * người gọi tự lo việc không kích hoạt hai lần.
     */
    public static void activatable(Node node, String accessibleText, Runnable action) {
        node.setFocusTraversable(true);
        node.setAccessibleRole(AccessibleRole.BUTTON);
        node.setAccessibleText(accessibleText);
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            node.requestFocus();
            action.run();
        });
        node.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                event.consume();
                action.run();
            }
        });
    }

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition}. */
    public static void showWhen(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    private static GridPane baseGrid(List<ColumnConstraints> columns) {
        GridPane grid = new GridPane();
        for (ColumnConstraints shared : columns) {
            // Mỗi GridPane giữ bản sao riêng: một ColumnConstraints không nên dùng chung cho nhiều GridPane
            ColumnConstraints copy = new ColumnConstraints(shared.getMinWidth(), shared.getPrefWidth(),
                    shared.getMaxWidth());
            copy.setHgrow(shared.getHgrow());
            grid.getColumnConstraints().add(copy);
        }
        return grid;
    }
}
