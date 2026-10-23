package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
class ProductService {

    static final int PAGE_SIZE = 12;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
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
        return new ProductDetailView(p.getId(), p.getName(), p.getCategory().getName(), p.getPrice(),
                p.getDescription(), p.getStock() > 0, toImageUrl(p.getImagePath()));
    }

    /** Sắp bằng Collator tiếng Việt trong Java: collation của DB khác nhau giữa PostgreSQL (Alpine) và H2. */
    private List<CategoryOption> categoryOptions() {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("vi")); // Collator không thread-safe
        return categoryRepository.findAll().stream()
                .sorted(Comparator.comparing(Category::getName, collator))
                .map(c -> new CategoryOption(c.getId(), c.getName()))
                .toList();
    }

    private ProductCardView toCard(Product p) {
        return new ProductCardView(p.getId(), p.getName(), p.getCategory().getName(), p.getPrice(),
                p.getStock() > 0, toImageUrl(p.getImagePath()));
    }

    /** Chỗ duy nhất đổi image_path thành URL; GĐ6 sẽ chuyển việc này sang StorageService. */
    private String toImageUrl(String imagePath) {
        return imagePath == null || imagePath.isBlank() ? null : imagePath;
    }
}
