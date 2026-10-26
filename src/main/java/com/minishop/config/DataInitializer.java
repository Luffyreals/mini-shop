package com.minishop.config;

import com.minishop.catalog.Category;
import com.minishop.catalog.CategoryRepository;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductRepository;
import com.minishop.user.DemoAccounts;
import com.minishop.user.Role;
import com.minishop.user.User;
import com.minishop.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Seed dữ liệu demo. Mỗi nhóm có điều kiện chặn riêng nên chạy lại nhiều lần không tạo trùng:
 * user chỉ tạo khi bảng users trống; danh mục và sản phẩm chỉ tạo khi bảng categories trống.
 */
@Component
class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties seed;

    DataInitializer(CategoryRepository categories, ProductRepository products, UserRepository users,
                    PasswordEncoder passwordEncoder, SeedProperties seed) {
        this.categories = categories;
        this.products = products;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUsers();
        seedCatalog();
    }

    private void seedUsers() {
        if (users.count() > 0) {
            log.info("Seed skipped for users: table is not empty");
            return;
        }
        users.save(new User(DemoAccounts.ADMIN_EMAIL, passwordEncoder.encode(seed.adminPassword()),
                "Quản trị viên", Role.ADMIN));
        users.save(new User(DemoAccounts.USER_EMAIL, passwordEncoder.encode(seed.userPassword()),
                "Khách hàng mẫu", Role.USER));
        log.info("Seeded {} users", 2);
    }

    private void seedCatalog() {
        if (categories.count() > 0) {
            log.info("Seed skipped for categories and products: categories table is not empty");
            return;
        }
        Category clothing = categories.save(new Category("Thời trang", "Quần áo và phụ kiện hằng ngày"));
        Category electronics = categories.save(new Category("Điện tử", "Thiết bị và phụ kiện công nghệ"));
        Category home = categories.save(new Category("Nhà cửa", "Đồ dùng và trang trí trong nhà"));
        Category books = categories.save(new Category("Sách", "Sách và văn phòng phẩm"));
        Category sports = categories.save(new Category("Thể thao", "Dụng cụ và trang phục thể thao"));

        List<Product> items = new ArrayList<>();
        items.add(product(clothing, "Áo thun cotton cổ tròn", "Áo thun cotton mềm, thoáng mát", 149000, 80, "ao-thun-cotton-co-tron"));
        items.add(product(clothing, "Quần jean nam ống đứng", "Quần jean co giãn nhẹ", 399000, 35, "quan-jean-nam-ong-dung"));
        items.add(product(clothing, "Váy liền thân dáng chữ A", "Váy vải voan nhẹ, phù hợp đi làm", 459000, 20, "vay-lien-than-dang-chu-a"));
        items.add(product(clothing, "Áo khoác gió hai lớp", "Chống nước nhẹ, có mũ", 549000, 12, "ao-khoac-gio-hai-lop"));
        items.add(product(electronics, "Tai nghe không dây", "Bluetooth, pin dùng khoảng 20 giờ", 890000, 40, "tai-nghe-khong-day"));
        items.add(product(electronics, "Sạc dự phòng 10000 mAh", "Hai cổng ra, sạc nhanh", 350000, 60, "sac-du-phong-10000mah"));
        items.add(product(electronics, "Chuột không dây văn phòng", "Kết nối USB, yên tĩnh khi bấm", 190000, 100, "chuot-khong-day-van-phong"));
        items.add(product(electronics, "Bàn phím cơ cỡ nhỏ", "Switch xanh, đèn nền một màu", 1250000, 8, "ban-phim-co-co-nho"));
        items.add(product(electronics, "Loa bluetooth mini", "Nhỏ gọn, chống bắn nước", 650000, 0, "loa-bluetooth-mini"));
        items.add(product(home, "Bộ chén đĩa sứ 12 món", "Sứ trắng, dùng được trong lò vi sóng", 480000, 25, "bo-chen-dia-su-12-mon"));
        items.add(product(home, "Đèn bàn LED chống cận", "Ba mức sáng, cổng sạc USB", 320000, 45, "den-ban-led-chong-can"));
        items.add(product(home, "Gối ôm lông cừu giả", "Ruột bông mềm, vỏ tháo giặt được", 210000, 30, "goi-om-long-cuu-gia"));
        items.add(product(home, "Bình giữ nhiệt 500 ml", "Inox hai lớp, giữ nóng 12 giờ", 275000, 70, "binh-giu-nhiet-500ml"));
        items.add(product(books, "Sổ tay bìa cứng A5", "200 trang giấy kẻ ngang", 59000, 150, "so-tay-bia-cung-a5"));
        items.add(product(books, "Bộ bút bi mực gel 10 cây", "Ngòi 0.5 mm, mực đen", 45000, 200, "bo-but-bi-muc-gel-10-cay"));
        items.add(product(books, "Truyện ngắn chọn lọc", "Tuyển tập truyện ngắn Việt Nam", 98000, 18, "truyen-ngan-chon-loc"));
        items.add(product(books, "Giáo trình lập trình Java cơ bản", "Kiến thức nền tảng cho người mới", 175000, 22, "giao-trinh-lap-trinh-java-co-ban"));
        items.add(product(sports, "Thảm tập yoga chống trượt", "Dày 6 mm, kèm túi đựng", 230000, 55, "tham-tap-yoga-chong-truot"));
        items.add(product(sports, "Bóng đá số 5", "Da tổng hợp, dùng cho sân cỏ nhân tạo", 280000, 28, "bong-da-so-5"));
        items.add(product(sports, "Dây nhảy thể lực", "Tay cầm chống trượt, chỉnh được độ dài", 79000, 90, "day-nhay-the-luc"));
        items.add(product(sports, "Tạ tay cao su 5 kg", "Bọc cao su, bán theo đôi", 340000, 15, "ta-tay-cao-su-5kg"));
        Product discontinued = product(home, "Ly thủy tinh kiểu cũ", "Mẫu đã ngừng kinh doanh", 65000, 5);
        discontinued.setActive(false);
        items.add(discontinued);

        products.saveAll(items);
        log.info("Seeded {} categories and {} products", 5, items.size());
    }

    private static Product product(Category category, String name, String description, long price, int stock,
                                   String imageSlug) {
        Product p = product(category, name, description, price, stock);
        p.setImagePath("/images/products/" + imageSlug + ".jpg");
        return p;
    }

    private static Product product(Category category, String name, String description, long price, int stock) {
        return new Product(category, name, description, BigDecimal.valueOf(price), stock);
    }
}
