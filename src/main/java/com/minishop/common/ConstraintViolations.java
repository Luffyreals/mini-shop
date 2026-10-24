package com.minishop.common;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;

/** Nhận biết vi phạm ràng buộc DB theo tên, giống nhau trên PostgreSQL và H2. */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * Đi theo chuỗi cause tới {@code ConstraintViolationException} của Hibernate đầu tiên và so tên ràng buộc không
     * phân biệt hoa thường. Dùng {@code contains} chứ không {@code equals}: H2 viết hoa tên và có thể thêm tiền tố
     * schema hoặc phần chú thích phía sau.
     */
    public static boolean isViolationOf(Throwable error, String constraintName) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name != null && name.toLowerCase(Locale.ROOT).contains(constraintName.toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }
}
