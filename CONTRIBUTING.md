# Quy trình phát triển

## Nhánh

- `main` luôn chạy được và `./mvnw verify` luôn xanh. Không commit thẳng vào `main`.
- Mỗi giai đoạn hoặc mỗi thay đổi làm trên một nhánh riêng:
  - `phase-<N>/<mo-ta-ngan>`, ví dụ `phase-1/project-setup`
  - `fix/<mo-ta>`, `docs/<mo-ta>`, `chore/<mo-ta>` cho việc ngoài giai đoạn
- Mở Pull Request vào `main`, điền đủ template, tự review diff, rồi chọn **Squash and merge**.

## Commit message

Theo [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <mô tả>`, viết bằng tiếng Anh, thể mệnh lệnh.

| type | Dùng khi |
|---|---|
| `feat` | Thêm tính năng |
| `fix` | Sửa lỗi |
| `refactor` | Đổi cấu trúc, không đổi hành vi |
| `test` | Thêm hoặc sửa test |
| `docs` | Tài liệu |
| `build` | Maven, dependency |
| `ci` | GitHub Actions |
| `chore` | Việc lặt vặt khác |

Scope là tên tính năng: `catalog`, `cart`, `order`, `user`, `admin`, `storage`, `config`.
Ví dụ: `feat(order): restore stock when an order is cancelled`

## Phát hành

Khi một giai đoạn hoàn thành và đã merge: chuyển mục `[Unreleased]` trong `CHANGELOG.md` thành phiên bản mới, rồi gắn tag `v0.<N>.0` trên `main`.

## Quyết định kiến trúc

Quyết định nào thay đổi kiến trúc, công nghệ hay một ràng buộc nghiệp vụ thì viết ADR mới trong `docs/adr/` (xem [README](docs/adr/README.md)).
