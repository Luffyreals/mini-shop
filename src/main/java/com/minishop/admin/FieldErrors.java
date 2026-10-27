package com.minishop.admin;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;

import java.lang.reflect.RecordComponent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gom lỗi của {@code Validator} thành một câu cho mỗi ô. Dùng chung cho ba form quản trị (QĐ13).
 * Không import JavaFX: presenter đặt câu vào property của từng ô.
 */
final class FieldErrors {

    private FieldErrors() {
    }

    /**
     * Mỗi ô một lỗi: {@code @NotBlank} trước các ràng buộc khác; cùng hạng thì câu đứng trước theo chữ cái
     * (kết quả ổn định). Khóa là tên ô, theo thứ tự khai báo của thành phần record (ô không phải thành phần record,
     * ví dụ lỗi cấp lớp có đường dẫn rỗng, đứng cuối theo thứ tự tên). Ô không lỗi không có trong kết quả, nên
     * phần tử đầu tiên của map là ô để focus.
     */
    static <T> Map<String, String> firstPerField(Set<ConstraintViolation<T>> violations) {
        Comparator<ConstraintViolation<T>> order = Comparator
                .<ConstraintViolation<T>>comparingInt(FieldErrors::rank)
                .thenComparing(ConstraintViolation::getMessage);
        Map<String, ConstraintViolation<T>> first = new HashMap<>();
        Class<?> rootClass = null;
        for (ConstraintViolation<T> violation : violations) {
            rootClass = violation.getRootBeanClass();
            first.merge(violation.getPropertyPath().toString(), violation,
                    (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (rootClass != null && rootClass.isRecord()) {
            for (RecordComponent component : rootClass.getRecordComponents()) {
                ConstraintViolation<T> violation = first.remove(component.getName());
                if (violation != null) {
                    result.put(component.getName(), violation.getMessage());
                }
            }
        }
        first.keySet().stream().sorted().forEach(key -> result.put(key, first.get(key).getMessage()));
        return result;
    }

    private static int rank(ConstraintViolation<?> violation) {
        return violation.getConstraintDescriptor().getAnnotation().annotationType() == NotBlank.class ? 0 : 1;
    }
}
