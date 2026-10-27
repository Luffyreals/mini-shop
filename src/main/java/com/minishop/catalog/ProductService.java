package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
class ProductService {

    static final int PAGE_SIZE = 12;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImages productImages;

    ProductService(ProductRepository productRepository, CategoryRepository categoryRepository,
                   ProductImages productImages) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productImages = productImages;
    }

    ProductListView search(ProductSearchParams params) {
        ProductFilter filter = ProductFilter.from(params);

        List<Specification<Product>> conditions = new ArrayList<>();
        conditions.add(ProductSpecifications.isActive());
        if (filter.q() != null) {
            conditions.add(ProductSpecifications.nameContains(filter.q()));
        }
        if (filter.categoryId() != null) {
            conditions.add(ProductSpecifications.inCategory(filter.categoryId()));
        }

        // Chỗ duy nhất đổi từ trang đánh số từ 1 (URL) sang trang đánh số từ 0 (Spring Data).
        Page<Product> page = productRepository.findAll(Specification.allOf(conditions),
                PageRequest.of(filter.page() - 1, PAGE_SIZE, filter.sort().toSort()));

        List<ProductCardView> cards = page.getContent().stream().map(this::toCard).toList();
        return new ProductListView(cards, page.getTotalElements(), page.getTotalPages(), filter, categoryOptions());
    }

    ProductDetailView getDetail(Long id) {
        Product p = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        return toDetail(p);
    }

    /**
     * Trang chủ (QĐ5): đúng ba câu SQL, không N+1. Sản phẩm mới nhất làm thẻ lớn, hai sản phẩm kế tiếp làm
     * "hàng mới", và tối đa 3 sản phẩm đang bán có 1 ≤ tồn ≤ ngưỡng sắp hết.
     */
    HomeView home() {
        List<Product> newest = productRepository.findTop3ByActiveTrueOrderByCreatedAtDescIdDesc();
        List<Product> lowStock = productRepository.findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(
                1, ProductAdminService.LOW_STOCK_THRESHOLD);

        ProductDetailView featured = newest.isEmpty() ? null : toDetail(newest.get(0));
        List<ProductCardView> moreNew = newest.stream().skip(1).map(this::toCard).toList();
        List<LowStockCardView> lowStockCards = lowStock.stream()
                .map(p -> new LowStockCardView(p.getId(), p.getName(), p.getStock(),
                        productImages.toUrl(p.getImagePath())))
                .toList();
        return new HomeView(featured, moreNew, lowStockCards, categoryOptions());
    }

    /** Danh mục cho menu, sắp theo tên tiếng Việt (cùng danh sách với bộ lọc). */
    List<CategoryOption> categories() {
        return categoryOptions();
    }

    private List<CategoryOption> categoryOptions() {
        return CategoryOptions.sortedByName(categoryRepository.findAll());
    }

    private ProductDetailView toDetail(Product p) {
        return new ProductDetailView(p.getId(), p.getName(), p.getCategory().getName(), p.getPrice(),
                p.getDescription(), p.getStock() > 0, productImages.toUrl(p.getImagePath()));
    }

    private ProductCardView toCard(Product p) {
        return new ProductCardView(p.getId(), p.getName(), p.getCategory().getName(), p.getPrice(),
                p.getStock() > 0, productImages.toUrl(p.getImagePath()));
    }
}
