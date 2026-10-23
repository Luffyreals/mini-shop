# Đặc tả tính năng (Specs)

Spec trả lời câu hỏi **làm cái gì và thế nào là xong**, từ góc nhìn người dùng, trước khi viết dòng code nào. Kế hoạch kỹ thuật (`docs/plans/`) trả lời **làm thế nào**; quyết định kiến trúc (`docs/adr/`) trả lời **vì sao**.

## Quy trình

```
brief (phạm vi) ─▶ spec (cái gì, điều kiện "xong") ─▶ plan (task, agent) ─▶ code + test ─▶ verify (đối chiếu spec)
```

1. **Viết spec** ở đầu mỗi giai đoạn bằng skill `/write-spec` (hoặc viết tay theo [_template.md](_template.md)). Spec chỉ được nằm trong phạm vi của `docs/project-brief.md`.
2. **Người dùng duyệt spec**: trả lời các câu hỏi mở, đổi trạng thái sang `Đã duyệt`.
3. **`tech-lead` lập kế hoạch** từ spec đã duyệt, lưu vào `docs/plans/phase-<N>.md`.
4. **Triển khai và test:** mỗi tiêu chí chấp nhận (AC) phải có ít nhất một test tự động hoặc một mục trong checklist thủ công.
5. **`phase-verifier` kiểm tra** từng AC. Khi tất cả đạt, trạng thái spec chuyển sang `Hoàn thành`.

Đang làm mà spec cần đổi thì sửa spec trước, ghi vào mục *Lịch sử thay đổi*, rồi mới sửa code.

## Quy ước

- Tên file: `NNN-ten-ngan.md`, đánh số tăng dần theo thứ tự làm.
- Trạng thái: `Nháp` → `Đã duyệt` → `Đang làm` → `Hoàn thành`.
- Tiêu chí chấp nhận viết theo dạng **Cho / Khi / Thì** (Given / When / Then). Mỗi AC có mã `AC-<spec>-<n>` để test và checklist trích dẫn được.
- Viết bằng tiếng Việt; tên class, bảng, URL giữ nguyên tiếng Anh.

## Danh sách

| Spec | Giai đoạn | Trạng thái |
|---|---|---|
| [001 Nền móng dự án](001-project-foundation.md) | GĐ1 | Hoàn thành |
| [002 Danh sách và chi tiết sản phẩm](002-product-catalog.md) | GĐ2 | Đang làm |
