package com.minishop.desktop;

import java.util.List;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Các ô xếp thành hàng {@code columns} ô chia đều chiều rộng, mỗi hàng cao bằng ô cao nhất của hàng ở chiều rộng
 * của ô, tính lại mỗi lần được hỏi. Dùng thay {@code GridPane} cho thẻ có chiều cao theo chiều rộng (ảnh 4:5, chữ
 * xuống dòng): {@code GridPane} giữ lại chiều cao hàng đã tính ở chiều rộng cũ, nên khi cửa sổ rộng ra mà số cột
 * không đổi, hàng thấp hơn thẻ và các thẻ tràn lên che tiêu đề và chân trang.
 * <p>
 * Hàng cuối ít ô hơn: mặc định mỗi ô rộng ra cho đủ hàng ({@code fillLastRow} = true); đặt false thì ô giữ đúng
 * chiều rộng của các hàng trên (lưới danh sách sản phẩm). Đổi số cột hay thứ tự chỉ đặt lại vị trí, không dựng lại
 * ô nên focus được giữ. Chỉ các con {@code managed} được xếp.
 */
public final class EvenRows extends Pane {

    /** Khoảng cách giữa các ô, cùng cỡ với khoảng cách giữa các khối của {@code .page-root} (20 px). */
    public static final double GAP = 20;

    private int columns = 2;
    private boolean fillLastRow = true;

    public void setColumns(int columns) {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be >= 1: " + columns);
        }
        if (columns != this.columns) {
            this.columns = columns;
            requestLayout();
        }
    }

    public void setFillLastRow(boolean fillLastRow) {
        if (fillLastRow != this.fillLastRow) {
            this.fillLastRow = fillLastRow;
            requestLayout();
        }
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    private List<Node> cells() {
        return getChildren().stream().filter(Node::isManaged).toList();
    }

    private double cellWidth(double innerWidth, int inRow) {
        int slots = fillLastRow ? inRow : columns;
        return Math.max(0, (innerWidth - GAP * (slots - 1)) / slots);
    }

    private static double rowHeight(List<Node> row, double cellWidth) {
        double height = 0;
        for (Node cell : row) {
            height = Math.max(height, cell.prefHeight(cellWidth));
        }
        return height;
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        List<Node> cells = cells();
        double innerWidth = (width < 0 ? 0 : width) - snappedLeftInset() - snappedRightInset();
        double total = 0;
        int rows = 0;
        for (int start = 0; start < cells.size(); start += columns) {
            List<Node> row = cells.subList(start, Math.min(start + columns, cells.size()));
            total += rowHeight(row, cellWidth(innerWidth, row.size())) + (rows == 0 ? 0 : GAP);
            rows++;
        }
        return snappedTopInset() + total + snappedBottomInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        List<Node> cells = cells();
        double innerWidth = getWidth() - snappedLeftInset() - snappedRightInset();
        double y = snappedTopInset();
        for (int start = 0; start < cells.size(); start += columns) {
            List<Node> row = cells.subList(start, Math.min(start + columns, cells.size()));
            double width = cellWidth(innerWidth, row.size());
            double height = rowHeight(row, width);
            double x = snappedLeftInset();
            for (Node cell : row) {
                cell.resizeRelocate(x, y, width, height);
                x += width + GAP;
            }
            y += height + GAP;
        }
    }
}
