## Mô tả

<!-- Thay đổi gì và vì sao. Liên kết issue nếu có: Closes #... -->

## Thuộc giai đoạn

<!-- GĐ1–GĐ8, hoặc "ngoài giai đoạn" -->

## Loại thay đổi

- [ ] feat: tính năng mới
- [ ] fix: sửa lỗi
- [ ] refactor / chore / docs / test / build / ci

## Cách kiểm tra

<!-- Các bước để người review tự chạy và thấy kết quả -->

1.

## Checklist

- [ ] `./mvnw verify` chạy xanh
- [ ] Nghiệp vụ nằm trong service, controller không chứa logic
- [ ] Thay đổi schema có migration Flyway **mới** (không sửa migration cũ)
- [ ] Không thêm tính năng ngoài phạm vi trong `docs/project-brief.md`
- [ ] Chữ hiển thị là tiếng Việt, code đặt tên tiếng Anh
- [ ] Đã cập nhật README / CHANGELOG / ADR nếu cần

## Ảnh chụp màn hình (nếu đổi giao diện)
