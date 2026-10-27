package com.minishop.desktop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dữ liệu ghi công ảnh, phông chữ và biểu tượng, đọc từ {@code /images/credits.tsv} (UTF-8, cột phân cách bằng
 * tab). Các dòng bắt đầu bằng {@code #} là đoạn giới thiệu; dòng tiếp theo là tiêu đề cột; mỗi dòng còn lại có
 * đúng chín cột (xem {@link Credit}). Cột {@code kind} là {@code image}, {@code font} hoặc {@code icon}.
 * <p>
 * Lớp thuần Java (không JavaFX) để test bằng JUnit thường. Một dòng sai cột hoặc sai {@code kind} ném
 * {@link IllegalStateException}, thông điệp chỉ nêu số dòng, không nêu nội dung.
 */
final class Credits {

    static final String RESOURCE = "/images/credits.tsv";

    private static final int COLUMNS = 9;
    private static final String HEADER_START = "kind\t";

    /**
     * Một dòng ghi công. {@code licenseUrl} và {@code sourceUrl} có thể rỗng (không có liên kết), các cột khác
     * cũng có thể rỗng nếu file để trống.
     *
     * @param kind       {@code image}, {@code font} hoặc {@code icon}
     * @param file       tên file ảnh (hoặc tiền tố của họ phông), dùng làm phần cuối của id nút
     * @param subject    tên sản phẩm, họ phông hoặc biểu tượng, dùng làm tiêu đề khối
     * @param title      tên tác phẩm gốc
     * @param author     tác giả
     * @param license    tên giấy phép
     * @param licenseUrl liên kết tới văn bản giấy phép, hoặc rỗng
     * @param source     nơi lấy tác phẩm
     * @param sourceUrl  liên kết tới trang gốc, hoặc rỗng
     */
    record Credit(String kind, String file, String subject, String title, String author,
                         String license, String licenseUrl, String source, String sourceUrl) {
    }

    private final String intro;
    private final List<Credit> images;
    private final List<Credit> fonts;
    private final List<Credit> icons;

    private Credits(String intro, List<Credit> images, List<Credit> fonts, List<Credit> icons) {
        this.intro = intro;
        this.images = List.copyOf(images);
        this.fonts = List.copyOf(fonts);
        this.icons = List.copyOf(icons);
    }

    /** Đọc {@code /images/credits.tsv} từ classpath. Thiếu file hoặc sai dạng thì ném {@link IllegalStateException}. */
    static Credits load() {
        try (InputStream in = Credits.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Thiếu " + RESOURCE);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Như {@link #load()} nhưng từ chuỗi, cho test. */
    static Credits parse(String tsv) {
        try {
            return parse(new StringReader(tsv));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Credits parse(Reader reader) throws IOException {
        List<String> introLines = new ArrayList<>();
        List<Credit> images = new ArrayList<>();
        List<Credit> fonts = new ArrayList<>();
        List<Credit> icons = new ArrayList<>();
        boolean headerSeen = false;

        BufferedReader lines = new BufferedReader(reader);
        int number = 0;
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            number++;
            if (number == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                String text = line.substring(1).strip();
                if (!text.isEmpty()) {
                    introLines.add(text);
                }
                continue;
            }
            if (!headerSeen) {
                if (!line.startsWith(HEADER_START)) {
                    throw new IllegalStateException("credits.tsv: thiếu dòng tiêu đề cột (dòng " + number + ")");
                }
                headerSeen = true;
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (cells.length != COLUMNS) {
                throw new IllegalStateException("credits.tsv: dòng " + number + " có " + cells.length
                        + " cột, cần " + COLUMNS);
            }
            Credit credit = new Credit(cells[0].strip(), cells[1].strip(), cells[2].strip(), cells[3].strip(),
                    cells[4].strip(), cells[5].strip(), cells[6].strip(), cells[7].strip(), cells[8].strip());
            switch (credit.kind()) {
                case "image" -> images.add(credit);
                case "font" -> fonts.add(credit);
                case "icon" -> icons.add(credit);
                default -> throw new IllegalStateException("credits.tsv: dòng " + number + " có kind không hợp lệ");
            }
        }
        return new Credits(String.join(" ", introLines), images, fonts, icons);
    }

    /** Đoạn giới thiệu: các dòng {@code #} nối bằng một dấu cách; rỗng nếu file không có. */
    public String intro() {
        return intro;
    }

    /** Ảnh sản phẩm, theo thứ tự trong file. */
    public List<Credit> images() {
        return images;
    }

    /** Họ phông chữ, theo thứ tự trong file. */
    public List<Credit> fonts() {
        return fonts;
    }

    /** Biểu tượng ứng dụng, theo thứ tự trong file. */
    public List<Credit> icons() {
        return icons;
    }
}
