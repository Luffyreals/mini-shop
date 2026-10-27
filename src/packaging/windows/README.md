# Biểu tượng Windows

Thư mục này chứa biểu tượng của Mini Shop cho bản đóng gói `jpackage`.

| File | Là gì |
|---|---|
| `MiniShop.ico` | Biểu tượng của `MiniShop.exe` (`jpackage --icon`). Sáu cỡ 16, 24, 32, 48, 64, 256 px, mỗi cỡ một PNG nhúng trong ICO. |
| `MakeIcon.java` | Chương trình Java một file, chỉ dùng JDK, vẽ biểu tượng bằng Java2D và ghi các file ở trên. |

`MakeIcon` cũng ghi `src/main/resources/ui/icons/app-icon-{16,32,48,256}.png`. Đây là biểu tượng của cửa sổ JavaFX (JavaFX không đọc SVG). Nguồn hình dạng và màu là `src/main/resources/ui/icons/app-icon.svg`; `MakeIcon.java` vẽ lại đúng các hình đó (hình vuông bo góc, chữ "M", chấm cam), nên đổi một nơi thì đổi nơi kia.

## Tạo lại

Từ thư mục gốc của dự án, chạy từ file (không cần Maven, Inkscape hay ImageMagick):

```
java src/packaging/windows/MakeIcon.java
```

Xem thử mà không ghi đè file của dự án:

```
java src/packaging/windows/MakeIcon.java --preview <thư mục>
```

Lệnh này ghi `preview-light.png` và `preview-dark.png` (mọi cỡ cạnh nhau, và ba cỡ nhỏ phóng 8 lần để soi từng điểm ảnh).

## Hình và màu

- Nền: hình vuông bo góc (bán kính 7 trên lưới 32), màu `#1B1B1F` (`-ms-dark`).
- Chữ "M": màu trắng. Chữ và chấm đặt thấp xuống để cả cụm nằm giữa, giống logo "mini shop•" trên thanh trên.
- Chấm: màu `#FF5A1F` (`-ms-accent`), nằm ở chân chữ bên phải như chấm của logo.
- Độ dày nét chữ M là 4,05 trên lưới 32, tức 2 px ở cỡ 16. Nét mảnh hơn làm hai chân và đỉnh chữ M dính vào nhau ở 16 px, nên chữ M được thu nhỏ (0,9 lần bản cũ) chứ nét không bị làm mảnh. Chấm có khe 1,2 đơn vị với chữ để vẫn tách ra ở 16 px.

## Giấy phép

Tự vẽ cho dự án, giấy phép MIT (giống mã nguồn của dự án).
