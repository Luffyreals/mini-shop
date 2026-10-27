import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Vẽ biểu tượng Mini Shop (khớp src/main/resources/ui/icons/app-icon.svg, lưới 32x32)
 * và ghi MiniShop.ico cùng các PNG của cửa sổ. Chỉ dùng JDK.
 *
 * Chạy từ thư mục gốc dự án:  java src/packaging/windows/MakeIcon.java
 * Xem thử (không ghi file dự án):  java src/packaging/windows/MakeIcon.java --preview <thư mục>
 */
public class MakeIcon {

    // Màu theo token trong ui/theme.css: -ms-dark, trắng, -ms-accent.
    static final Color DARK = new Color(0x1B1B1F);
    static final Color WHITE = Color.WHITE;
    static final Color ACCENT = new Color(0xFF5A1F);

    static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 256};
    static final int[] PNG_SIZES = {16, 32, 48, 256};

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(size / 32.0, size / 32.0);

        // <rect width="32" height="32" rx="7" fill="#1b1b1f"/>
        g.setColor(DARK);
        g.fill(new RoundRectangle2D.Double(0, 0, 32, 32, 14, 14));

        // <path d="M4 24.1V7.9h4.05L13 16.45 17.95 7.9H22v16.2h-4.05V15.1L13 23.65 8.05 15.1v9z"/>
        Path2D m = new Path2D.Double();
        m.moveTo(4, 24.1);
        m.lineTo(4, 7.9);
        m.lineTo(8.05, 7.9);
        m.lineTo(13, 16.45);
        m.lineTo(17.95, 7.9);
        m.lineTo(22, 7.9);
        m.lineTo(22, 24.1);
        m.lineTo(17.95, 24.1);
        m.lineTo(17.95, 15.1);
        m.lineTo(13, 23.65);
        m.lineTo(8.05, 15.1);
        m.lineTo(8.05, 24.1);
        m.closePath();
        g.setColor(WHITE);
        g.fill(m);

        // <circle cx="25.8" cy="21.5" r="2.6" fill="#ff5a1f"/>
        g.setColor(ACCENT);
        g.fill(new Ellipse2D.Double(25.8 - 2.6, 21.5 - 2.6, 5.2, 5.2));

        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** ICO: 6 byte header, 16 byte mỗi mục, rồi dữ liệu PNG của từng cỡ. */
    static byte[] ico(int[] sizes) throws IOException {
        List<byte[]> data = new ArrayList<>();
        for (int s : sizes) {
            data.add(png(render(s)));
        }
        int total = 6 + 16 * sizes.length;
        for (byte[] d : data) {
            total += d.length;
        }
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int dim = sizes[i] >= 256 ? 0 : sizes[i]; // 0 nghĩa là 256
            b.put((byte) dim).put((byte) dim).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32);
            b.putInt(data.get(i).length).putInt(offset);
            offset += data.get(i).length;
        }
        for (byte[] d : data) {
            b.put(d);
        }
        return b.array();
    }

    /** Một ảnh có mọi cỡ cạnh nhau trên nền cho trước; cỡ nhỏ cũng phóng to 4 lần bên dưới. */
    static BufferedImage preview(Color background) {
        int pad = 24;
        int[] sizes = ICO_SIZES;
        int rowWidth = pad;
        for (int s : sizes) {
            rowWidth += s + pad;
        }
        int zoom = 8; // 16, 24, 32 px phóng 8 lần để soi pixel
        int zoomedHeight = 32 * zoom;
        int width = Math.max(rowWidth, pad + (16 + 24 + 32) * zoom + pad * 4);
        int height = pad + 256 + pad + zoomedHeight + pad;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        int x = pad;
        for (int s : sizes) {
            g.drawImage(render(s), x, pad + 256 - s, null);
            x += s + pad;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        x = pad;
        int y = pad + 256 + pad;
        for (int s : new int[] {16, 24, 32}) {
            g.drawImage(render(s), x, y, s * zoom, s * zoom, null);
            x += s * zoom + pad;
        }
        g.dispose();
        return out;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--preview")) {
            Path dir = Path.of(args[1]);
            Files.createDirectories(dir);
            ImageIO.write(preview(new Color(0xF6F6F8)), "png", dir.resolve("preview-light.png").toFile());
            ImageIO.write(preview(new Color(0x2B2B31)), "png", dir.resolve("preview-dark.png").toFile());
            System.out.println("Đã ghi ảnh xem thử vào " + dir.toAbsolutePath());
            return;
        }
        Path ico = Path.of("src/packaging/windows/MiniShop.ico");
        Path icons = Path.of("src/main/resources/ui/icons");
        if (!Files.isDirectory(ico.getParent()) || !Files.isDirectory(icons)) {
            throw new IllegalStateException("Hãy chạy từ thư mục gốc của dự án.");
        }
        Files.write(ico, ico(ICO_SIZES));
        for (int s : PNG_SIZES) {
            Files.write(icons.resolve("app-icon-" + s + ".png"), png(render(s)));
        }
        System.out.println("Đã ghi " + ico + " và app-icon-{16,32,48,256}.png");
    }
}
