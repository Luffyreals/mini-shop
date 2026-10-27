package com.minishop.catalog;

import com.minishop.common.AdminAccess;
import com.minishop.common.ConstraintViolations;
import com.minishop.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Quản trị danh mục (admin). Trùng tên kiểm không phân biệt hoa thường trong Java; {@code uq_categories_name}
 * là lưới an toàn khi hai yêu cầu đua nhau. Xóa khóa dòng {@code categories} trước (ADR-0010).
 *
 * <p>Mọi phương thức public gọi {@code requireAdmin()} đầu tiên (AC-009-9, ADR-0014); không khóa, không ghi khi
 * bị từ chối. Id admin trả về chỉ dùng cho một dòng log INFO.
 */
@Service
@Transactional
public class CategoryAdminService {

    private static final Logger log = LoggerFactory.getLogger(CategoryAdminService.class);
    private static final String NAME_UNIQUE_CONSTRAINT = "uq_categories_name";

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    private final AdminAccess adminAccess;

    CategoryAdminService(CategoryRepository categoryRepository, ProductRepository productRepository,
                         AdminAccess adminAccess) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.adminAccess = adminAccess;
    }

    @Transactional(readOnly = true)
    public List<CategoryRow> list() {
        adminAccess.requireAdmin();
        Collator collator = Collator.getInstance(Locale.forLanguageTag("vi")); // Collator không thread-safe
        return categoryRepository.findAllWithProductCount().stream()
                .sorted(Comparator.comparing(CategoryRow::name, collator))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryEditView get(long id) {
        adminAccess.requireAdmin();
        Category c = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found: " + id));
        return new CategoryEditView(c.getId(), c.getName(), c.getDescription());
    }

    @Transactional(readOnly = true)
    public List<CategoryOption> options() {
        adminAccess.requireAdmin();
        return CategoryOptions.sortedByName(categoryRepository.findAll());
    }

    /** @return tên đã lưu */
    public String create(CategoryDraft draft) {
        long adminId = adminAccess.requireAdmin();
        if (nameTaken(draft.name(), null)) {
            throw new CategoryNameTakenException("Category name taken");
        }
        Category saved;
        try {
            saved = categoryRepository.saveAndFlush(new Category(draft.name(), draft.description()));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        log.info("Admin action: admin={}, category={}, action=create", adminId, saved.getId());
        return saved.getName();
    }

    /** @return tên đã lưu */
    public String update(long id, CategoryDraft draft) {
        long adminId = adminAccess.requireAdmin();
        // Row lock (like delete, ADR-0010): a concurrent delete cannot turn this into an optimistic-lock 500.
        Category c = categoryRepository.lockById(id)
                .orElseThrow(() -> new NotFoundException("Category not found: " + id));
        if (nameTaken(draft.name(), id)) {
            throw new CategoryNameTakenException("Category name taken");
        }
        c.update(draft.name(), draft.description());
        try {
            categoryRepository.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        log.info("Admin action: admin={}, category={}, action=update", adminId, id);
        return c.getName();
    }

    /**
     * Thứ tự: khóa dòng danh mục, đếm sản phẩm (cả đã ẩn), rồi xóa hoặc báo còn dùng. Thêm sản phẩm cũng khóa dòng
     * này trước, nên hai việc không thể chen nhau.
     */
    public CategoryDeleteResult delete(long id) {
        long adminId = adminAccess.requireAdmin();
        Category c = categoryRepository.lockById(id)
                .orElseThrow(() -> new NotFoundException("Category not found: " + id));
        long count = productRepository.countByCategoryId(id);
        if (count > 0) {
            log.debug("Admin action refused: admin={}, category={}, action=delete, products={}", adminId, id, count);
            return new CategoryDeleteResult.InUse(c.getName(), count);
        }
        categoryRepository.delete(c);
        categoryRepository.flush();
        log.info("Admin action: admin={}, category={}, action=delete", adminId, id);
        return new CategoryDeleteResult.Deleted(c.getName());
    }

    private boolean nameTaken(String name, Long excludeId) {
        String wanted = name.toLowerCase(Locale.ROOT);
        return categoryRepository.findAll().stream()
                .filter(c -> excludeId == null || !c.getId().equals(excludeId))
                .anyMatch(c -> c.getName().toLowerCase(Locale.ROOT).equals(wanted));
    }

    /** Chỉ {@code uq_categories_name} thành {@link CategoryNameTakenException}; lỗi khác ném lại nguyên vẹn. */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        if (ConstraintViolations.isViolationOf(e, NAME_UNIQUE_CONSTRAINT)) {
            return new CategoryNameTakenException("Category name taken", e);
        }
        return e;
    }
}
