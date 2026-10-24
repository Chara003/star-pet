import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 图标生成：读取源 PNG，去白底、裁切、生成各密度图标。
 */
public class IconGen {

    static final int WHITE = 245;

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]);
        File outRoot = new File(args[1]);

        BufferedImage img = ImageIO.read(src);
        System.out.println("源图: " + img.getWidth() + "x" + img.getHeight());

        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        // 1) 从四边洪水填充，把外部白底变透明
        boolean[] outside = new boolean[w * h];
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            stack.push(x);
            stack.push((h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            stack.push(y * w);
            stack.push(y * w + w - 1);
        }
        while (!stack.isEmpty()) {
            int idx = stack.pop();
            if (idx < 0 || idx >= w * h) continue;
            if (outside[idx]) continue;
            int c = px[idx];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (r < WHITE || g < WHITE || b < WHITE) continue;
            outside[idx] = true;
            int x = idx % w, y = idx / w;
            if (x > 0) stack.push(idx - 1);
            if (x < w - 1) stack.push(idx + 1);
            if (y > 0) stack.push(idx - w);
            if (y < h - 1) stack.push(idx + w);
        }

        // 2) 求不透明区域包围盒
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!outside[y * w + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) { minX = 0; minY = 0; maxX = w - 1; maxY = h - 1; }

        // 裁成正方形
        int side = Math.max(maxX - minX + 1, maxY - minY + 1);
        int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        int x0 = cx - side / 2, y0 = cy - side / 2;
        System.out.println("裁切: side=" + side + " at (" + x0 + "," + y0 + ")");

        // 3) 构建带透明通道的正方形图
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int sx = x0 + x, sy = y0 + y;
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                if (outside[sy * w + sx]) continue;
                square.setRGB(x, y, 0xFF000000 | (px[sy * w + sx] & 0xFFFFFF));
            }
        }

        // 4) 采样主色作为自适应图标背景
        Map<Integer, Integer> hist = new HashMap<>();
        for (int y = 0; y < side; y += 4) {
            for (int x = 0; x < side; x += 4) {
                int c = square.getRGB(x, y);
                if ((c >>> 24) < 200) continue;
                int q = ((c >> 19) & 0x1F) << 11 | ((c >> 11) & 0x1F) << 6 | ((c >> 3) & 0x1F);
                hist.merge(q, 1, Integer::sum);
            }
        }
        int bestQ = 0, bestN = -1;
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); bestQ = e.getKey(); }
        }
        int rr = ((bestQ >> 11) & 0x1F) << 3, gg = ((bestQ >> 6) & 0x1F) << 3, bb = (bestQ & 0x1F) << 3;
        rr |= rr >> 5; gg |= gg >> 5; bb |= bb >> 5;
        String bgHex = String.format("#FF%02X%02X%02X", rr, gg, bb);
        System.out.println("背景主色: " + bgHex);

        // 5) 生成传统图标 48/72/96/144/192
        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] sizes = {48, 72, 96, 144, 192};
        for (int i = 0; i < dirs.length; i++) {
            File d = new File(outRoot, "mipmap-" + dirs[i]);
            d.mkdirs();
            write(scale(square, sizes[i], sizes[i], 0), new File(d, "ic_launcher.png"));
            write(scale(square, sizes[i], sizes[i], 0), new File(d, "ic_launcher_round.png"));
        }

        // 6) 自适应图标前景 108dp（内容占满画布，由系统遮罩裁切）
        int[] fgSizes = {108, 162, 216, 324, 432};
        for (int i = 0; i < dirs.length; i++) {
            File d = new File(outRoot, "mipmap-" + dirs[i]);
            d.mkdirs();
            write(scale(square, fgSizes[i], fgSizes[i], 0),
                    new File(d, "ic_launcher_foreground.png"));
        }

        // 7) 自适应图标 XML
        File anydpi = new File(outRoot, "mipmap-anydpi-v26");
        anydpi.mkdirs();
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <background android:drawable=\"@color/ic_launcher_background\"/>\n"
                + "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\"/>\n"
                + "</adaptive-icon>\n";
        writeText(xml, new File(anydpi, "ic_launcher.xml"));
        writeText(xml, new File(anydpi, "ic_launcher_round.xml"));

        // 8) 背景色资源
        File values = new File(outRoot, "values");
        values.mkdirs();
        writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <color name=\"ic_launcher_background\">" + bgHex + "</color>\n"
                + "</resources>\n", new File(values, "ic_launcher_background.xml"));

        System.out.println("完成");
    }

    /** 缩放到指定尺寸；padding 为四周留白像素。 */
    static BufferedImage scale(BufferedImage src, int w, int h, int padding) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, padding, padding, w - padding * 2, h - padding * 2, null);
        g.dispose();
        return out;
    }

    static void write(BufferedImage img, File f) throws Exception {
        ImageIO.write(img, "png", f);
        System.out.println("  " + f.getPath() + " (" + img.getWidth() + "x" + img.getHeight()
                + ", " + f.length() + "B)");
    }

    static void writeText(String text, File f) throws Exception {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
            fos.write(text.getBytes("UTF-8"));
        }
        System.out.println("  " + f.getPath());
    }
}
