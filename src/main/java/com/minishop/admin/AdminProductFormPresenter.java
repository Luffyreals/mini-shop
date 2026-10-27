package com.minishop.admin;

import com.minishop.catalog.CatalogRoute;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryNotFoundException;
import com.minishop.catalog.CategoryOption;
import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.ProductDetailRoute;
import com.minishop.catalog.ProductDraft;
import com.minishop.catalog.ProductEditView;
import com.minishop.catalog.SavedProduct;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.storage.ImageUpload;
import com.minishop.storage.InvalidImageException;
import jakarta.validation.Validator;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của form "Thêm sản phẩm" / "Sửa sản phẩm" (AC-006-18 đến -22, -25 đến -30, -32 đến -34,
 * -37; QĐ5 đến QĐ8, QĐ10, QĐ13 của D5). Không import {@code javafx.scene}; {@link AdminProductFormScreen} chỉ dựng
 * node và gắn vào các property này. Mỗi lần điều hướng tới {@link AdminProductFormRoute} có một presenter mới
 * ({@code prototype}); màn gọi {@link #load} một lần và {@link #dispose} khi bị thay.
 *
 * <p><b>Ba phần độc lập (QĐ7):</b> (1) form thông tin + ảnh ({@link #save}); (2) khối "Điều chỉnh tồn kho"
 * ({@link #adjustStock}); (3) ẩn/hiện ({@link #hide}, {@link #show}). Mỗi phần chỉ đổi phần của nó: điều chỉnh tồn
 * kho và ẩn/hiện không bao giờ chạm chữ đang gõ ở form. Chỉ {@link #save} khi sửa nạp lại cả form từ service (giá trị
 * đã chuẩn hóa, ảnh hiện tại mới, bỏ lựa chọn ảnh). Một cờ {@link #busyProperty()} cho cả màn: trong lúc một việc ghi
 * chạy, mọi việc ghi khác bị bỏ qua ở đây (ba việc cùng ghi một dòng {@code products}; an toàn dữ liệu vẫn do
 * service lo).
 *
 * <p><b>Luồng:</b> mọi phương thức công khai gọi trên luồng giao diện. Mọi lời gọi service và mọi lần đọc file ảnh
 * ({@code ImageUpload.fromFile}) chạy trong {@link BackgroundTasks}. Hai service thuộc hai tính năng nên màn nạp
 * bằng một việc nền hai lời gọi gói trong một record (QĐ8).
 *
 * <p><b>Ảnh (QĐ6):</b> màn mở hộp chọn file rồi gọi {@link #imageChosen}; file được kiểm ngay ở nền
 * ({@link #checkingImageProperty()}). Hợp lệ thì thành lựa chọn và xem trước; không hợp lệ thì lỗi dưới ô ảnh và
 * <em>giữ lựa chọn cũ</em>. Lúc "Lưu": {@code Validator} trên form (luồng giao diện) có lỗi thì dừng, không đọc
 * file, không gọi service; không lỗi thì một việc nền đọc lại file ({@code fromFile}, vì file có thể đã đổi) rồi
 * đúng một lời gọi service, nên ảnh hỏng không bao giờ ghi file nào. File không đọc được (đã bị xóa, bị khóa, lỗi
 * I/O; D6 QĐ16) cho {@link #MSG_IMAGE_UNREADABLE} dưới ô ảnh ở cả hai lúc, không hộp thoại lỗi, và lúc lưu thì không
 * gọi service; lỗi I/O khác (ví dụ {@code store} ghi file) vẫn là lỗi lạ qua {@link AdminFailures}.
 *
 * <p>{@link #dispose} hủy việc nạp và việc kiểm ảnh nhưng <em>không</em> hủy việc ghi đang chạy (hủy callback không
 * hủy transaction): ghi xong sau {@code dispose} thì thành công vẫn hiện toast, không đổi property, không điều
 * hướng; lỗi nghiệp vụ bị bỏ qua; lỗi lạ vẫn báo (AC-009-12). Presenter không ghi log và không đưa tên file vào
 * thông báo.
 */
@Component
@Scope("prototype")
class AdminProductFormPresenter {

    static final String MSG_ADDED = "Đã thêm sản phẩm %s.";
    static final String MSG_UPDATED = "Đã cập nhật sản phẩm %s.";
    static final String MSG_HIDDEN = "Đã ẩn sản phẩm %s. Khách sẽ không thấy sản phẩm này nữa.";
    static final String MSG_SHOWN = "Đã hiện lại sản phẩm %s.";
    static final String MSG_STOCK_UPDATED = "Đã cập nhật tồn kho sản phẩm %s. Tồn kho hiện tại: %d.";
    static final String MSG_STOCK_NOT_ENOUGH = "Không thể giảm %d vì tồn kho hiện tại chỉ còn %d.";
    static final String MSG_STOCK_EXCEEDS = "Tồn kho sau điều chỉnh không được vượt quá 100.000.";
    static final String MSG_DELTA_INVALID =
            "Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.";
    static final String MSG_CATEGORY_GONE = "Danh mục không tồn tại.";
    static final String MSG_IMAGE_TYPE = "Ảnh chỉ được là JPG, PNG hoặc WebP.";
    static final String MSG_IMAGE_SIZE = "Ảnh tối đa 2 MB.";
    /** File đã chọn không đọc được (biến mất, bị khóa, lỗi I/O); QĐ16 của D6. */
    static final String MSG_IMAGE_UNREADABLE = "Không đọc được file ảnh. Vui lòng chọn ảnh khác.";
    /** "Tồn kho hiện tại: {n}". */
    static final String STOCK_TEXT = "Tồn kho hiện tại: %d";

    /** Ô của form; màn đưa focus tới ô lỗi đầu tiên. */
    enum Field { NAME, CATEGORY, PRICE, INITIAL_STOCK, DESCRIPTION, IMAGE, DELTA }

    private final CategoryAdminService categoryAdmin;
    private final ProductAdminService productAdmin;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final Toasts toasts;
    private final AdminFailures failures;
    private final Validator validator;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final ReadOnlyBooleanWrapper loaded = new ReadOnlyBooleanWrapper(false);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty checkingImage = new SimpleBooleanProperty(false);
    private final ReadOnlyBooleanWrapper noCategories = new ReadOnlyBooleanWrapper(false);

    private final ObservableList<CategoryOption> categories = FXCollections.observableArrayList();
    private final StringProperty name = new SimpleStringProperty("");
    private final ObjectProperty<CategoryOption> category = new SimpleObjectProperty<>();
    private final StringProperty price = new SimpleStringProperty("");
    private final StringProperty initialStock = new SimpleStringProperty("");
    private final StringProperty description = new SimpleStringProperty("");
    private final BooleanProperty activeChecked = new SimpleBooleanProperty(true);

    private final ReadOnlyStringWrapper nameError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper categoryError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper priceError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper initialStockError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper descriptionError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper imageError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper deltaError = new ReadOnlyStringWrapper("");
    private final ObjectProperty<Field> firstErrorField = new SimpleObjectProperty<>();

    private final ReadOnlyObjectWrapper<Path> selectedImage = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyStringWrapper selectedImageName = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper previewUrl = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper currentImageUrl = new ReadOnlyStringWrapper("");

    private final ReadOnlyStringWrapper stockText = new ReadOnlyStringWrapper("");
    private final StringProperty delta = new SimpleStringProperty("");
    private final ReadOnlyBooleanWrapper activeNow = new ReadOnlyBooleanWrapper(true);

    private AdminProductFormRoute route;
    private BackgroundTasks.Handle pendingLoad;
    private BackgroundTasks.Handle pendingImageCheck;
    private boolean disposed;
    /** File chọn trong lúc đang ghi; được kiểm khi {@link #busyProperty()} về {@code false}. */
    private Path chosenWhileBusy;

    AdminProductFormPresenter(CategoryAdminService categoryAdmin, ProductAdminService productAdmin,
                              BackgroundTasks tasks, Navigator navigator, Toasts toasts, AdminFailures failures,
                              Validator validator) {
        this.categoryAdmin = categoryAdmin;
        this.productAdmin = productAdmin;
        this.tasks = tasks;
        this.navigator = navigator;
        this.toasts = toasts;
        this.failures = failures;
        this.validator = validator;
        // Sửa một ô thì lỗi của ô đó biến mất (lỗi cũ không còn đúng với chữ mới)
        name.addListener((observable, oldValue, newValue) -> nameError.set(""));
        category.addListener((observable, oldValue, newValue) -> categoryError.set(""));
        price.addListener((observable, oldValue, newValue) -> priceError.set(""));
        initialStock.addListener((observable, oldValue, newValue) -> initialStockError.set(""));
        description.addListener((observable, oldValue, newValue) -> descriptionError.set(""));
        delta.addListener((observable, oldValue, newValue) -> deltaError.set(""));
        busy.addListener((observable, wasBusy, isBusy) -> {
            if (!isBusy && chosenWhileBusy != null) {
                Path pending = chosenWhileBusy;
                chosenWhileBusy = null;
                imageChosen(pending);
            }
        });
    }

    /** Dữ liệu nạp của cả màn, đọc trong một việc nền (QĐ8). {@code product} {@code null} khi thêm mới. */
    private record FormData(List<CategoryOption> options, ProductEditView product) {
    }

    /** Kết quả lưu form sửa: tên mới và dữ liệu đọc lại ({@code null} khi đọc lại thất bại sau khi đã lưu). */
    private record Updated(String name, ProductEditView view) {
    }

    // ------------------------------------------------------------------ nạp

    /** Nạp danh mục (và sản phẩm khi sửa) ở nền. Gọi một lần; lần gọi sau bị bỏ qua. */
    void load(AdminProductFormRoute route) {
        if (disposed || this.route != null) {
            return;
        }
        this.route = route;
        loading.set(true);
        pendingLoad = tasks.submit(
                () -> new FormData(categoryAdmin.options(),
                        route.isNew() ? null : productAdmin.getForEdit(route.productId())),
                this::loadedData, this::loadFailed);
    }

    private void loadedData(FormData data) {
        categories.setAll(data.options());
        if (route.isNew()) {
            noCategories.set(data.options().isEmpty());
        } else {
            applyView(data.product());
        }
        loading.set(false);
        loaded.set(true);
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        failures.handle(failure, AdminFailures.MSG_PRODUCT_NOT_FOUND, this::back);
        if (!(failure instanceof NotFoundException) && !failures.isAccessDenied(failure)) {
            back(); // không để người dùng kẹt ở form rỗng sau một lỗi lạ (đã báo ở trên)
        }
    }

    /**
     * Đặt mọi ô và nhãn từ dữ liệu của service; bỏ lựa chọn ảnh và mọi lỗi, kể cả lỗi dưới ô "Số lượng thay đổi"
     * (lỗi đó là của lần bấm cũ). Chữ đang gõ trong ô "Số lượng thay đổi" được giữ nguyên (QĐ7).
     */
    private void applyView(ProductEditView view) {
        name.set(view.name());
        price.set(view.price().toPlainString());
        description.set(view.description() == null ? "" : view.description());
        category.set(optionFor(view.categoryId()));
        currentImageUrl.set(view.imageUrl() == null ? "" : view.imageUrl());
        stockText.set(STOCK_TEXT.formatted(view.stock()));
        activeNow.set(view.active());
        resetImageSelection();
        clearFieldErrors();   // đặt lại các ô ở trên đã xóa lỗi từng ô; đây xóa nốt lỗi ảnh và ô cần focus
        deltaError.set("");   // ô "Số lượng thay đổi" không bị đặt lại nên lỗi của nó không tự biến mất
    }

    private CategoryOption optionFor(long categoryId) {
        return categories.stream().filter(option -> option.id() != null && option.id() == categoryId)
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ ảnh

    /**
     * Người dùng chọn một file trong hộp chọn file. Kiểm ở nền; hợp lệ thì thành lựa chọn và xem trước, không hợp
     * lệ thì lỗi dưới ô ảnh và giữ lựa chọn cũ. Bỏ qua khi đang ghi (cờ {@code busy}) hoặc chưa nạp xong.
     */
    void imageChosen(Path file) {
        if (disposed || file == null || !loaded.get()) {
            return;
        }
        if (busy.get()) {
            chosenWhileBusy = file;   // file chọn mới nhất; kiểm ngay khi việc ghi xong
            return;
        }
        chosenWhileBusy = null;
        if (pendingImageCheck != null) {
            pendingImageCheck.cancel();
        }
        checkingImage.set(true);
        pendingImageCheck = tasks.submit(() -> {
            readImage(file);   // chỉ để kiểm; lúc lưu đọc lại
            return file;
        }, this::imageAccepted, this::imageRejected);
    }

    private void imageAccepted(Path file) {
        checkingImage.set(false);
        Path fileName = file.getFileName();
        selectedImage.set(file);
        selectedImageName.set(fileName == null ? "" : fileName.toString());
        previewUrl.set(file.toUri().toString());
        imageError.set("");
    }

    private void imageRejected(Throwable failure) {
        checkingImage.set(false);
        if (failure instanceof InvalidImageException invalid) {
            imageError.set(imageMessage(invalid));
        } else if (failure instanceof UnreadableImageException) {
            // File đã biến mất, bị khóa hoặc không đọc được: câu dưới ô ảnh, giữ lựa chọn cũ, không hộp thoại lỗi
            imageError.set(MSG_IMAGE_UNREADABLE);
        } else {
            failures.handle(failure);
        }
    }

    /** "Bỏ chọn ảnh": bỏ ảnh vừa chọn chưa lưu (không xóa ảnh đã lưu). */
    void clearImage() {
        if (disposed || busy.get()) {
            return;
        }
        if (pendingImageCheck != null) {
            pendingImageCheck.cancel();
        }
        checkingImage.set(false);
        resetImageSelection();
        imageError.set("");
    }

    private void resetImageSelection() {
        selectedImage.set(null);
        selectedImageName.set("");
        previewUrl.set("");
        imageError.set("");
    }

    private static String imageMessage(InvalidImageException failure) {
        return failure.reason() == InvalidImageException.Reason.TOO_LARGE ? MSG_IMAGE_SIZE : MSG_IMAGE_TYPE;
    }

    // ------------------------------------------------------------------ lưu

    /**
     * "Lưu". Form sai (mỗi ô một câu, focus ô lỗi đầu): không đọc file, không gọi service. Đúng: một việc nền
     * (đọc ảnh nếu có, rồi {@code create} hoặc {@code update}). Bỏ qua khi đang ghi, đang kiểm ảnh hoặc chưa nạp.
     */
    void save() {
        if (disposed || !loaded.get() || busy.get() || checkingImage.get() || noCategories.get()) {
            return;
        }
        clearFieldErrors();
        if (route.isNew()) {
            saveNew();
        } else {
            saveEdit();
        }
    }

    private void saveNew() {
        NewProductForm form = new NewProductForm(name.get(), categoryIdText(), price.get(), initialStock.get(),
                description.get(), activeChecked.get(), selectedImage.get());
        if (!showViolations(FieldErrors.firstPerField(validator.validate(form)))) {
            return;
        }
        ProductDraft draft = form.toDraft();
        int stock = form.initialStockValue();
        boolean active = form.isActiveChecked();
        Path image = form.image();
        busy.set(true);
        tasks.submit(() -> productAdmin.create(draft, stock, active, readImage(image)),
                this::added, this::writeFailed);
    }

    private void saveEdit() {
        EditProductForm form = new EditProductForm(name.get(), categoryIdText(), price.get(), description.get(),
                selectedImage.get());
        if (!showViolations(FieldErrors.firstPerField(validator.validate(form)))) {
            return;
        }
        ProductDraft draft = form.toDraft();
        Path image = form.image();
        long id = route.productId();
        busy.set(true);
        tasks.submit(() -> {
            String newName = productAdmin.update(id, draft, readImage(image));
            // update đã commit: một lỗi khi đọc lại không được biến thành "lưu thất bại" (lưu lần nữa sẽ tải ảnh
            // lên lần hai). Không đọc lại được thì trả view null, màn tự nạp lại.
            ProductEditView view;
            try {
                view = productAdmin.getForEdit(id);
            } catch (RuntimeException readBackFailure) {
                view = null;
            }
            return new Updated(newName, view);
        }, this::updated, this::writeFailed);
    }

    /**
     * Đọc file ảnh ở nền (lúc chọn để kiểm, lúc lưu để đọc lại vì file có thể đã đổi). Lỗi I/O của chính file này
     * thành {@link UnreadableImageException}, tách khỏi {@link UncheckedIOException} mà {@code store} có thể ném
     * (ghi vào thư mục tải lên): lỗi sau vẫn là lỗi lạ. Khi dùng làm đối số của lời gọi service, lỗi này ném trước
     * khi service được gọi nên không ghi gì.
     */
    private static ImageUpload readImage(Path image) {
        if (image == null) {
            return null;
        }
        try {
            return ImageUpload.fromFile(image);
        } catch (UncheckedIOException e) {
            throw new UnreadableImageException();
        }
    }

    /** File ảnh đã chọn không đọc được (QĐ16). Không mang đường dẫn, cause hay stack trace. */
    private static final class UnreadableImageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnreadableImageException() {
            super("The chosen image could not be read", null, false, false);
        }
    }

    private String categoryIdText() {
        CategoryOption option = category.get();
        return option == null || option.id() == null ? "" : String.valueOf(option.id());
    }

    private void added(SavedProduct saved) {
        toasts.show(MSG_ADDED.formatted(saved.name()), ToastKind.SUCCESS);
        if (disposed) {
            return;
        }
        busy.set(false);
        navigator.navigateTo(AdminProductFormRoute.edit(saved.id(), route.back()));
    }

    private void updated(Updated result) {
        toasts.show(MSG_UPDATED.formatted(result.name()), ToastKind.SUCCESS);
        if (disposed) {
            return;
        }
        busy.set(false);
        if (result.view() == null) {
            // Đã lưu nhưng chưa đọc lại được: bỏ lựa chọn ảnh (đã tải lên) rồi nạp lại cả màn từ service
            resetImageSelection();
            navigator.navigateTo(route);
        } else {
            applyView(result.view());
        }
    }

    /** Lỗi của việc lưu (cả thêm lẫn sửa). */
    private void writeFailed(Throwable failure) {
        if (disposed) {
            lateFailure(failure);
            return;
        }
        busy.set(false);
        if (failure instanceof InvalidImageException invalid) {
            imageError.set(imageMessage(invalid));
            firstErrorField.set(null);
            firstErrorField.set(Field.IMAGE);
        } else if (failure instanceof UnreadableImageException) {
            // Chưa gọi service (đọc file là đối số của lời gọi): không ghi gì, giữ nguyên form và lựa chọn ảnh
            imageError.set(MSG_IMAGE_UNREADABLE);
            firstErrorField.set(null);
            firstErrorField.set(Field.IMAGE);
        } else if (failure instanceof CategoryNotFoundException) {
            categoryError.set(MSG_CATEGORY_GONE);
            firstErrorField.set(null);
            firstErrorField.set(Field.CATEGORY);
        } else {
            failures.handle(failure, AdminFailures.MSG_PRODUCT_NOT_FOUND, this::back);
        }
    }

    // ------------------------------------------------------------------ tồn kho

    /**
     * "Cập nhật tồn kho" (QĐ7). Chữ sai thì báo dưới ô và không gọi service; kết quả của service ánh xạ bằng
     * {@code switch} đủ nhánh. Chỉ có ở màn sửa.
     */
    void adjustStock() {
        if (disposed || !loaded.get() || busy.get() || route.isNew()) {
            return;
        }
        Optional<Integer> parsed = AdminInput.stockDelta(delta.get());
        if (parsed.isEmpty()) {
            deltaError.set(MSG_DELTA_INVALID);
            firstErrorField.set(null);
            firstErrorField.set(Field.DELTA);
            return;
        }
        deltaError.set("");
        long id = route.productId();
        int amount = parsed.get();
        busy.set(true);
        tasks.submit(() -> productAdmin.adjustStock(id, amount), this::stockAdjusted, this::stockFailed);
    }

    private void stockAdjusted(StockAdjustResult result) {
        switch (result) {
            case StockAdjustResult.Adjusted adjusted -> {
                toasts.show(MSG_STOCK_UPDATED.formatted(adjusted.name(), adjusted.stock()), ToastKind.SUCCESS);
                if (disposed) {
                    return;
                }
                stockText.set(STOCK_TEXT.formatted(adjusted.stock()));
                delta.set("");
            }
            case StockAdjustResult.NotEnough notEnough -> {
                if (disposed) {
                    return;
                }
                stockText.set(STOCK_TEXT.formatted(notEnough.current()));
                deltaError.set(MSG_STOCK_NOT_ENOUGH.formatted(notEnough.requested(), notEnough.current()));
                firstErrorField.set(null);
                firstErrorField.set(Field.DELTA);
            }
            case StockAdjustResult.WouldExceed wouldExceed -> {
                if (disposed) {
                    return;
                }
                deltaError.set(MSG_STOCK_EXCEEDS);
                firstErrorField.set(null);
                firstErrorField.set(Field.DELTA);
            }
        }
        if (!disposed) {
            busy.set(false);
        }
    }

    private void stockFailed(Throwable failure) {
        editWriteFailed(failure);
    }

    // ------------------------------------------------------------------ ẩn / hiện

    /** "Ẩn" (không hỏi xác nhận: hiện lại được ngay, câu hỏi 2 của D5). Chỉ đổi nhãn trạng thái và nút. */
    void hide() {
        setActive(false);
    }

    /** "Hiện lại". */
    void show() {
        setActive(true);
    }

    private void setActive(boolean active) {
        if (disposed || !loaded.get() || busy.get() || route.isNew()) {
            return;
        }
        long id = route.productId();
        busy.set(true);
        tasks.submit(() -> productAdmin.setActive(id, active), productName -> activeChanged(productName, active),
                this::editWriteFailed);
    }

    private void activeChanged(String productName, boolean active) {
        toasts.show((active ? MSG_SHOWN : MSG_HIDDEN).formatted(productName), ToastKind.SUCCESS);
        if (disposed) {
            return;
        }
        activeNow.set(active);
        busy.set(false);
    }

    /** Lỗi của tồn kho và ẩn/hiện ở màn sửa: không còn sản phẩm thì về danh sách. */
    private void editWriteFailed(Throwable failure) {
        if (disposed) {
            lateFailure(failure);
            return;
        }
        busy.set(false);
        failures.handle(failure, AdminFailures.MSG_PRODUCT_NOT_FOUND, this::back);
    }

    /** Lỗi đến sau {@link #dispose}: lỗi nghiệp vụ bỏ qua, lỗi lạ vẫn báo (AC-009-12). */
    private void lateFailure(Throwable failure) {
        boolean business = failure instanceof InvalidImageException
                || failure instanceof UnreadableImageException
                || failure instanceof CategoryNotFoundException
                || failure instanceof NotFoundException
                || failure instanceof AccessDeniedException;
        if (!business) {
            failures.handle(failure);
        }
    }

    // ------------------------------------------------------------------ lỗi form

    private void clearFieldErrors() {
        nameError.set("");
        categoryError.set("");
        priceError.set("");
        initialStockError.set("");
        descriptionError.set("");
        imageError.set("");
        firstErrorField.set(null);
    }

    /**
     * Đặt câu lỗi từng ô; ô đầu tiên của map (theo thứ tự record) được báo để focus.
     *
     * @return {@code true} khi không có lỗi (form hợp lệ)
     */
    private boolean showViolations(Map<String, String> errors) {
        if (errors.isEmpty()) {
            return true;
        }
        Field first = null;
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            Field field = switch (entry.getKey()) {
                case "name" -> Field.NAME;
                case "categoryId" -> Field.CATEGORY;
                case "price" -> Field.PRICE;
                case "initialStock" -> Field.INITIAL_STOCK;
                case "description" -> Field.DESCRIPTION;
                default -> null;
            };
            if (field == null) {
                continue;
            }
            errorOf(field).set(entry.getValue());
            if (first == null) {
                first = field;
            }
        }
        if (first == null) {
            // Một ràng buộc không gắn được vào ô nào: lỗi lập trình, không để người dùng nhìn form im lặng
            failures.handle(new IllegalStateException("Product form violation without a field"));
            return false;
        }
        firstErrorField.set(first);
        return false;
    }

    private ReadOnlyStringWrapper errorOf(Field field) {
        return switch (field) {
            case NAME -> nameError;
            case CATEGORY -> categoryError;
            case PRICE -> priceError;
            case INITIAL_STOCK -> initialStockError;
            case DESCRIPTION -> descriptionError;
            case IMAGE -> imageError;
            case DELTA -> deltaError;
        };
    }

    // ------------------------------------------------------------------ lối đi

    /** "Hủy": về danh sách sản phẩm (đúng trang và bộ tìm đã mở form). Chữ chưa lưu bị bỏ (QĐ3). */
    void cancel() {
        back();
    }

    private void back() {
        if (!disposed) {
            navigator.navigateTo(route.back());
        }
    }

    /** "Xem trên cửa hàng" (chỉ khi đang bán): mở chi tiết sản phẩm ở cửa hàng. */
    void viewInStore() {
        if (!disposed && loaded.get() && !route.isNew() && activeNow.get()) {
            navigator.navigateTo(new ProductDetailRoute(route.productId(), CatalogRoute.all()));
        }
    }

    /** "Thêm danh mục" khi chưa có danh mục nào. */
    void addCategory() {
        if (!disposed) {
            navigator.navigateTo(AdminCategoryFormRoute.newCategory());
        }
    }

    /** Hủy việc nạp và việc kiểm ảnh; việc ghi đang chạy không bị hủy (xem mô tả lớp). Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        if (pendingLoad != null) {
            pendingLoad.cancel();
        }
        if (pendingImageCheck != null) {
            pendingImageCheck.cancel();
        }
    }

    // ------------------------------------------------------------------ property cho màn

    /** {@code true} khi đây là form thêm mới (đặt ngay từ {@link #load}). */
    boolean isNew() {
        return route != null && route.isNew();
    }

    /** {@code true} trong lúc nạp. */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lần nạp thành công đầu tiên. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded.getReadOnlyProperty();
    }

    /** {@code true} trong lúc một việc ghi (lưu, tồn kho, ẩn/hiện) chạy. */
    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    /** {@code true} trong lúc kiểm file ảnh vừa chọn. */
    ReadOnlyBooleanProperty checkingImageProperty() {
        return checkingImage;
    }

    /** {@code true} khi thêm mới mà chưa có danh mục nào ("Cần tạo danh mục trước khi thêm sản phẩm."). */
    ReadOnlyBooleanProperty noCategoriesProperty() {
        return noCategories.getReadOnlyProperty();
    }

    /** Các danh mục của ô "Danh mục" (đã sắp theo tên). */
    ObservableList<CategoryOption> categories() {
        return categories;
    }

    StringProperty nameProperty() {
        return name;
    }

    /** Danh mục đã chọn; {@code null} khi chưa chọn (form nhận {@code ""}). */
    ObjectProperty<CategoryOption> categoryProperty() {
        return category;
    }

    StringProperty priceProperty() {
        return price;
    }

    StringProperty initialStockProperty() {
        return initialStock;
    }

    StringProperty descriptionProperty() {
        return description;
    }

    /** Ô "Đang bán" của form thêm mới (bật sẵn). */
    BooleanProperty activeCheckedProperty() {
        return activeChecked;
    }

    ReadOnlyStringProperty nameErrorProperty() {
        return nameError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty categoryErrorProperty() {
        return categoryError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty priceErrorProperty() {
        return priceError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty initialStockErrorProperty() {
        return initialStockError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty descriptionErrorProperty() {
        return descriptionError.getReadOnlyProperty();
    }

    /** Lỗi dưới ô ảnh ("Ảnh chỉ được là JPG, PNG hoặc WebP." / "Ảnh tối đa 2 MB."). */
    ReadOnlyStringProperty imageErrorProperty() {
        return imageError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty deltaErrorProperty() {
        return deltaError.getReadOnlyProperty();
    }

    /** Ô cần focus sau một lần kiểm sai; đặt về {@code null} rồi mới đặt ô, nên cùng một ô lỗi hai lần vẫn báo. */
    ReadOnlyObjectProperty<Field> firstErrorFieldProperty() {
        return firstErrorField;
    }

    /** File ảnh đã chọn và đã kiểm; {@code null} khi chưa chọn. */
    ReadOnlyObjectProperty<Path> selectedImageProperty() {
        return selectedImage.getReadOnlyProperty();
    }

    /** Tên file đã chọn (chỉ hiện trên màn, không bao giờ vào log hay toast). */
    ReadOnlyStringProperty selectedImageNameProperty() {
        return selectedImageName.getReadOnlyProperty();
    }

    /** URL {@code file:} của ảnh đã chọn cho ô xem trước; rỗng khi chưa chọn. */
    ReadOnlyStringProperty previewUrlProperty() {
        return previewUrl.getReadOnlyProperty();
    }

    /** URL ảnh đã lưu của sản phẩm (form sửa); rỗng khi chưa có ảnh. */
    ReadOnlyStringProperty currentImageUrlProperty() {
        return currentImageUrl.getReadOnlyProperty();
    }

    /** "Tồn kho hiện tại: {n}". */
    ReadOnlyStringProperty stockTextProperty() {
        return stockText.getReadOnlyProperty();
    }

    /** Ô "Số lượng thay đổi". */
    StringProperty deltaProperty() {
        return delta;
    }

    /** {@code true} khi sản phẩm đang bán ("Đang bán"), {@code false} khi đã ẩn ("Đã ẩn"); form sửa. */
    ReadOnlyBooleanProperty activeNowProperty() {
        return activeNow.getReadOnlyProperty();
    }
}
