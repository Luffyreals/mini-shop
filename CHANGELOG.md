# Changelog

Mọi thay đổi đáng chú ý của dự án được ghi ở đây.
Định dạng theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/); mỗi giai đoạn hoàn thành được gắn tag `v0.<giai đoạn>.0`.

## [Unreleased]

### Thêm
- Quy trình đặc tả: `docs/specs/` (template, spec 001) và skill `/write-spec`.
- Khung dự án Spring Boot 4.1.1 / Java 25: Maven Wrapper, profile mặc định (PostgreSQL qua Docker Compose) và profile `h2`, các package theo tính năng, cấu hình bảo mật nền, trang chủ tạm, Bootstrap qua WebJars.
- Test khởi động app với Testcontainers, test MockMvc cho trang chủ và bảo mật.
- GitHub Actions CI (`./mvnw verify`) và Dependabot cho Maven, Actions, Docker Compose.
- Bản mô tả dự án, kiến trúc và lộ trình 8 giai đoạn (`docs/project-brief.md`).
- Architecture Decision Records 0001–0006 (`docs/adr/`).
- `CLAUDE.md` và 8 subagent trong `.claude/agents/`.
- Cấu hình repo: `.gitattributes`, `.editorconfig`, template cho PR và issue, `CONTRIBUTING.md`.
- `.claude/settings.json`: chặn Claude Code đọc file bí mật (`.env`, khóa, keystore).

### Bảo mật
- Bật Dependabot alerts và Dependabot security updates trên GitHub.
- Chỉ cho phép squash merge; tự xóa nhánh sau khi merge.
- Commit dùng email noreply của GitHub thay cho email cá nhân.
