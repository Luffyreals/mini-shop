# Phông Plus Jakarta Sans

Bốn file TTF tĩnh (400, 500, 600, 700), dùng cho toàn bộ giao diện JavaFX (ADR-0014).

- **Nguồn:** https://github.com/tokotype/PlusJakartaSans, thư mục `fonts/ttf/`
- **Commit:** `18d1cd2f7ea10481919d2f05c1f7064b7307fc26` (commit mà Google Fonts dùng)
- **Giấy phép:** SIL Open Font License 1.1, bản đầy đủ trong `OFL.txt`. Bản quyền: The Plus Jakarta Sans Project Authors.
- **Vì sao dùng file tĩnh:** JavaFX không hỗ trợ trục của phông variable, nên file `PlusJakartaSans[wght].ttf` của Google Fonts chỉ hiện đúng một độ đậm.
- **Tiếng Việt và `₫`:** có trong cả bốn file (đã kiểm bằng `java.awt.Font.canDisplayUpTo`).

| File | SHA-256 |
|---|---|
| `PlusJakartaSans-Regular.ttf` | `bd6276d4060e3b1ebc45047469e0bb86b08f301ba681cdf1ceb6245ea10478d2` |
| `PlusJakartaSans-Medium.ttf` | `c77bab757d7402ec6d9341d5f7ddaafb2474e17026792697ba4624c7dc89caf7` |
| `PlusJakartaSans-SemiBold.ttf` | `65dbcedb6596a41c30869729eb31cb57d1f5edfe365684314bba8a1994eaa4cb` |
| `PlusJakartaSans-Bold.ttf` | `5f5342ef76862b5b5365d1dff1a667629dfa484e388dd602552f647219c3870f` |
| `OFL.txt` | `995c7199cab65954f545996326755daee7b63cc6b42b06c13da1f9502ab08a99` |
