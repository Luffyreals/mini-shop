package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.storage.ImageUpload;
import com.minishop.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Quản trị sản phẩm (admin): danh sách, thêm, sửa, ẩn/hiện, điều chỉnh tồn kho, sắp hết hàng.
 *
 * <p><b>Thứ tự khóa (ADR-0010):</b> thêm/sửa khóa dòng {@code categories} của danh mục đích trước, rồi mới ghi
 * {@code products}; xóa danh mục cũng khóa dòng đó trước, nên hai việc không thể chen nhau. Không đường nào khóa
 * {@code products} rồi mới tới {@code categories}. Danh mục được khóa trước khi ghi file ảnh, nên lỗi danh
 * mục không bao giờ để lại file.
 *
 * <p><b>Tồn kho:</b> {@link #update} không bao giờ ghi cột {@code stock} ({@code Product.stock} là
 * {@code updatable = false}, BR-006-8), nên lưu một form đã mở từ lâu không ghi đè lượng kho khách vừa mua.
 * {@link #adjustStock} là một câu UPDATE có điều kiện; sau đó luôn đọc lại: khi UPDATE đổi 1 dòng, nó giữ khóa
 * dòng tới khi commit nên giá trị đọc được là của chính mình; khi UPDATE đổi 0 dòng thì không có gì bị khóa, lần
 * đọc chỉ để biết vì sao và tồn kho báo về có thể cũ đôi chút (chấp nhận).
 *
 * <p>Mọi method ghi nhận {@code adminId} chỉ để log một dòng INFO, không bao giờ có thông tin cá nhân.
 */
@Service
@Transactional
public class ProductAdminService {

    static final int PAGE_SIZE = 20;
    static final int MAX_STOCK = 100_000;
    static final int LOW_STOCK_THRESHOLD = 5;
    static final int LOW_STOCK_LIMIT = 10;

    private static final Logger log = LoggerFactory.getLogger(ProductAdminService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final StorageService storage;
    private final ProductImages productImages;

    ProductAdminService(ProductRepository productRepository, CategoryRepository categoryRepository,
                        StorageService storage, ProductImages productImages) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.storage = storage;
        this.productImages = productImages;
    }

    /** Một trang sản phẩm (cả đã ẩn), mới thêm trước. {@code rawQ} và {@code rawPage} là chuỗi thô, sai thì bỏ qua. */
    @Transactional(readOnly = true)
    public AdminProductPage list(String rawQ, String rawPage) {
        ProductFilter filter = ProductFilter.from(new ProductSearchParams(rawQ, null, null, rawPage));
        String q = filter.q();
        int page = filter.page();
        Page<Product> result = findPage(q, page);
        if (result.getContent().isEmpty() && result.getTotalElements() > 0) {
            page = 1;
            result = findPage(q, page);
        }
        List<AdminProductRow> rows = result.getContent().stream().map(this::toRow).toList();
        return new AdminProductPage(rows, q, page, result.getTotalPages(), result.getTotalElements());
    }

    private Page<Product> findPage(String q, int page) {
        List<Specification<Product>> conditions = new ArrayList<>();
        if (q != null) {
            conditions.add(ProductSpecifications.nameContains(q));
        }
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return productRepository.findAll(Specification.allOf(conditions), PageRequest.of(page - 1, PAGE_SIZE, sort));
    }

    private AdminProductRow toRow(Product p) {
        return new AdminProductRow(p.getId(), p.getName(), p.getCategory().getName(), p.getPrice(), p.getStock(),
                p.isActive(), productImages.toUrl(p.getImagePath()));
    }

    /** @throws NotFoundException khi không có sản phẩm */
    @Transactional(readOnly = true)
    public ProductEditView getForEdit(long id) {
        Product p = find(id);
        return new ProductEditView(p.getId(), p.getName(), p.getCategory().getId(), p.getPrice(),
                p.getDescription(), p.getStock(), p.isActive(), productImages.toUrl(p.getImagePath()));
    }

    /** Sản phẩm đang bán có tồn kho không quá 5, ít nhất trước, tối đa 10 dòng (BR-006-14). */
    @Transactional(readOnly = true)
    public List<LowStockRow> lowStock() {
        return productRepository.findTop10ByActiveTrueAndStockLessThanEqualOrderByStockAscIdAsc(LOW_STOCK_THRESHOLD)
                .stream()
                .map(p -> new LowStockRow(p.getId(), p.getName(), p.getStock()))
                .toList();
    }

    /**
     * @param image ảnh đã kiểm tra, hoặc {@code null} nếu không có ảnh
     * @throws CategoryNotFoundException khi danh mục không còn
     */
    public SavedProduct create(long adminId, ProductDraft draft, int initialStock, boolean active,
                               ImageUpload image) {
        if (initialStock < 0 || initialStock > MAX_STOCK) {
            throw new IllegalArgumentException("Initial stock must be in 0.." + MAX_STOCK);
        }
        Category category = lockCategory(draft.categoryId());
        Product product = new Product(category, draft.name(), draft.description(), draft.price(), initialStock);
        product.setActive(active);
        if (image != null) {
            product.setImagePath(storage.store(image));
        }
        Product saved = productRepository.save(product);
        log.info("Admin action: admin={}, product={}, action=create", adminId, saved.getId());
        return new SavedProduct(saved.getId(), saved.getName());
    }

    /**
     * Sửa tên, giá, mô tả, danh mục và (tùy chọn) ảnh. Không đụng tồn kho và trạng thái.
     *
     * @param image ảnh mới đã kiểm tra, hoặc {@code null} để giữ ảnh cũ
     * @return tên mới
     * @throws NotFoundException         khi không có sản phẩm
     * @throws CategoryNotFoundException khi danh mục đích không còn
     */
    public String update(long adminId, long id, ProductDraft draft, ImageUpload image) {
        Product product = find(id);
        Category category = lockCategory(draft.categoryId());
        product.updateDetails(category, draft.name(), draft.description(), draft.price());
        if (image != null) {
            String old = product.getImagePath();
            product.setImagePath(storage.store(image));
            storage.deleteAfterCommit(old); // chỉ xóa sau commit; ảnh seed và null bị StorageService bỏ qua
        }
        log.info("Admin action: admin={}, product={}, action=update, imageReplaced={}", adminId, id, image != null);
        return product.getName();
    }

    /** Ẩn hoặc hiện lại. Gọi lại khi đã ở trạng thái đó vẫn thành công. @return tên sản phẩm */
    public String setActive(long adminId, long id, boolean active) {
        Product product = find(id);
        product.setActive(active);
        log.info("Admin action: admin={}, product={}, action={}", adminId, id, active ? "show" : "hide");
        return product.getName();
    }

    /**
     * Điều chỉnh tồn kho theo lượng ±. Không lọc {@code active}.
     *
     * <p>Đọc lại sau UPDATE chỉ đảm bảo là giá trị của chính mình khi UPDATE đổi 1 dòng; khi 0 dòng, tồn kho báo về
     * có thể cũ đôi chút.
     *
     * @throws NotFoundException        khi không có sản phẩm
     * @throws IllegalArgumentException khi {@code delta} bằng 0 hoặc vượt ±100.000 (controller đã kiểm trước)
     */
    public StockAdjustResult adjustStock(long adminId, long id, int delta) {
        if (delta == 0 || Math.abs((long) delta) > MAX_STOCK) {
            throw new IllegalArgumentException("Stock delta must be non-zero and within +/-" + MAX_STOCK);
        }
        int updated = productRepository.adjustStock(id, delta);
        // Persistence context đã bị xóa sau UPDATE, nên đây là câu SELECT mới. Chỉ khi updated == 1 thì dòng còn bị
        // khóa và giá trị là của mình; khi 0 dòng không khóa gì, tồn kho báo về có thể cũ đôi chút (chấp nhận).
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        StockAdjustResult result;
        if (updated == 1) {
            result = new StockAdjustResult.Adjusted(product.getName(), product.getStock());
        } else if (delta < 0) {
            result = new StockAdjustResult.NotEnough(product.getName(), -delta, product.getStock());
        } else {
            result = new StockAdjustResult.WouldExceed(product.getName());
        }
        log.info("Admin action: admin={}, product={}, action=adjustStock, delta={}, result={}", adminId, id, delta,
                result.getClass().getSimpleName());
        return result;
    }

    private Product find(long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
    }

    private Category lockCategory(long categoryId) {
        return categoryRepository.lockById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryId));
    }
}
