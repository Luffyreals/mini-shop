package com.minishop.catalog;

import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellContributor;
import com.minishop.desktop.ShellMenuEntry;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import com.minishop.desktop.UnexpectedErrors;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phần thanh trên thuộc về cửa hàng (QĐ6 của D3): "Cửa hàng" (về trang chủ), "Hàng mới", menu "Danh mục" và ô tìm kiếm; cũng đặt trang chủ
 * (QĐ5) và mở nó lúc khởi động.
 *
 * <p><b>Menu danh mục:</b> có ngay mục "Tất cả danh mục" khi {@link #contribute}, nên nút "Danh mục" bật từ đầu;
 * danh mục đầy đủ được nạp ngoài luồng giao diện, và mỗi lần màn danh sách nạp xong thì làm mới menu bằng
 * {@link #updateCategories}.
 *
 * <p><b>Tìm kiếm:</b> Enter trong ô tìm kiếm. Đang ở danh sách thì giữ danh mục và cách sắp xếp, về trang 1; ở
 * màn khác thì tìm trong mọi danh mục. Ô rỗng nghĩa là không lọc.
 *
 * <p>Mọi phương thức chạy trên luồng giao diện. {@link #contribute} chỉ đặt property và gửi việc nền, nên không
 * làm hỏng cửa sổ chính.
 */
@Component
class StorefrontPresenter implements ShellContributor {

    static final String ALL_CATEGORIES_LABEL = "Tất cả danh mục";
    static final String ALL_CATEGORIES_ID = "categoryMenuAll";

    private final ProductService productService;
    private final Navigator navigator;
    private final BackgroundTasks tasks;
    private final UnexpectedErrors errors;

    /** {@code null} cho tới khi cửa sổ chính gọi {@link #contribute}. */
    private ShellPresenter shell;

    StorefrontPresenter(ProductService productService, Navigator navigator, BackgroundTasks tasks,
                        UnexpectedErrors errors) {
        this.productService = productService;
        this.navigator = navigator;
        this.tasks = tasks;
        this.errors = errors;
    }

    @Override
    public void contribute(ShellPresenter shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
        shell.setAction(ShellAction.STORE, navigator::goHome);
        shell.setAction(ShellAction.NEW_ARRIVALS, () -> navigator.navigateTo(CatalogRoute.all()));
        shell.setSearchHandler(this::search);
        updateCategories(List.of());
        tasks.submit(productService::categories, this::updateCategories, errors::report);
        // MainWindow đã gắn Navigator vào vùng nội dung trước khi gọi contribute; factory chỉ dựng node và gửi việc nền
        navigator.setHomeRoute(new HomeRoute());
        navigator.goHome();
    }

    /**
     * Thay menu "Danh mục" bằng "Tất cả danh mục" và các danh mục (theo thứ tự đã cho). Chưa có thanh trên thì
     * chưa có gì để cập nhật.
     */
    void updateCategories(List<CategoryOption> categories) {
        if (shell == null) {
            return;
        }
        List<ShellMenuEntry> entries = new ArrayList<>();
        entries.add(new ShellMenuEntry(ALL_CATEGORIES_ID, ALL_CATEGORIES_LABEL,
                () -> navigator.navigateTo(CatalogRoute.all())));
        for (CategoryOption category : categories) {
            long id = category.id();
            String name = category.name();
            if (name != null && !name.isBlank()) {
                entries.add(new ShellMenuEntry("categoryMenu-" + id, name,
                        () -> navigator.navigateTo(CatalogRoute.inCategory(id))));
            }
        }
        shell.setCategoriesMenu(entries);
    }

    private void search(String text) {
        if (navigator.currentRoute() instanceof CatalogRoute current) {
            navigator.navigateTo(current.withQuery(text));
        } else {
            navigator.navigateTo(CatalogRoute.search(text));
        }
    }
}
