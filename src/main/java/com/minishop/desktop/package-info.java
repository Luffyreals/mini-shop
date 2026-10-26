/**
 * Khung ứng dụng desktop JavaFX (ADR-0014): cửa sổ chính, theme, thanh trên, và các phần dùng chung
 * mà mọi màn hình cần (điều hướng, chạy nền, toast, nạp ảnh, báo lỗi).
 * <p>
 * <b>Luật chiều phụ thuộc (QĐ4):</b> {@code desktop} là tầng dưới, giống {@code common}. Các tính năng
 * ({@code catalog}, {@code cart}, {@code order}, {@code user}, {@code admin}) phụ thuộc vào {@code desktop};
 * {@code desktop} không bao giờ import tính năng nào. Thanh trên vì thế chỉ phơi ra trạng thái
 * ({@link com.minishop.desktop.ShellPresenter}) và chỗ gắn hành động; tính năng tự gắn vào khi nó có màn.
 * <p>
 * <b>Chỗ tính năng gắn vào (D2):</b>
 * <ul>
 *   <li>{@link com.minishop.desktop.ShellContributor}: bean của tính năng được {@code MainWindow} gọi một lần,
 *       trên luồng giao diện, để đặt hành động, nhãn, menu và số giỏ cho thanh trên.</li>
 *   <li>Menu nút tài khoản: {@link com.minishop.desktop.ShellPresenter#accountMenuEntries()} là danh sách
 *       {@link com.minishop.desktop.ShellMenuEntry}; menu rỗng thì nút chạy hành động {@code ACCOUNT}.</li>
 *   <li>{@link com.minishop.desktop.Navigator#goHome()} và {@code setHomeRoute}: về "trang chủ" (đăng xuất);
 *       chưa có route trang chủ thì vùng nội dung được xóa trống.</li>
 *   <li>{@link com.minishop.desktop.ModalDialogs}: dựng {@code Stage} modal theo token cho mọi hộp thoại
 *       (lỗi, đăng nhập, xác nhận).</li>
 * </ul>
 * <p>
 * <b>Luật JavaFX:</b> chỉ {@code desktop..} và các lớp {@code *Screen}, {@code *Drawer}, {@code *Dialog}
 * của tính năng được import {@code javafx.scene}. {@code *Presenter} chỉ dùng {@code javafx.beans}.
 * Service và entity không import {@code javafx..}.
 * <p>
 * <b>Luồng:</b> {@link com.minishop.desktop.MiniShopApp#init()} dựng Spring (ngoài luồng giao diện),
 * {@code start()} dựng cửa sổ. Bean trong gói này không tạo {@code Stage}, {@code Popup} hay {@code Tooltip}
 * trong constructor, và controller FXML là {@code prototype}.
 */
package com.minishop.desktop;
