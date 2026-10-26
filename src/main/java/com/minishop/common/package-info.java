/**
 * Thành phần dùng chung, không phụ thuộc tính năng nào và không phụ thuộc web hay JavaFX: exception nghiệp vụ
 * ({@code NotFoundException}), làm sạch và định dạng chuỗi ({@code FormText}, {@code VndFormatter},
 * {@code VnDateTimeFormatter}), {@code PageNav}, {@code ConstraintViolations} và {@code SafeExceptionLog}
 * (che thông điệp SQL của H2 trước khi ghi log, ADR-0013).
 */
package com.minishop.common;
