/**
 * Thành phần dùng chung, không phụ thuộc tính năng nào và không phụ thuộc web hay JavaFX: exception nghiệp vụ
 * ({@code NotFoundException}, {@code AccessDeniedException}), làm sạch và định dạng chuỗi ({@code FormText},
 * {@code VndFormatter}, {@code VnDateTimeFormatter}), {@code PageNav}, {@code ConstraintViolations},
 * {@code SafeExceptionLog} (che thông điệp SQL của H2 trước khi ghi log, ADR-0013) và {@code AdminAccess}, điểm cắm
 * kiểm vai trò ADMIN mà {@code user.AppSession} cài đặt để service quản trị không phải phụ thuộc {@code user}.
 */
package com.minishop.common;
