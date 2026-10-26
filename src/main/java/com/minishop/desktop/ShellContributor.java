package com.minishop.desktop;

/**
 * Chỗ duy nhất để một tính năng gắn hành động và trạng thái vào thanh trên (QĐ9 của D2).
 * <p>
 * Một tính năng định nghĩa một bean implements interface này. {@link MainWindow} gọi {@link #contribute} cho
 * mọi bean (theo {@code @Order}) trên <b>luồng giao diện</b>, sau khi đã gắn các property của
 * {@link ShellPresenter} vào node. Vì vậy trong {@code contribute} được đặt property của thanh trên ngay,
 * còn trong constructor của bean thì không (constructor chạy trên luồng {@code JavaFX-Launcher}).
 * <p>
 * {@code desktop} không biết tính năng nào: tính năng phụ thuộc vào {@code desktop}, không ngược lại (QĐ4 của D1).
 * D3 và D4 dùng lại cho nút cửa hàng và nút giỏ.
 */
@FunctionalInterface
public interface ShellContributor {

    /**
     * Gắn hành động, nhãn, menu hay số đếm cho thanh trên. Chạy một lần, trên luồng giao diện. Ném lỗi thì
     * đi vào bộ bắt lỗi chung và cửa sổ chính không mở được, nên chỉ đặt property, không làm việc nặng.
     */
    void contribute(ShellPresenter shell);
}
