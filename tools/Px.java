import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
public class Px {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    System.out.println("size " + im.getWidth() + "x" + im.getHeight());
    for (int i = 1; i + 1 < a.length; i += 2) {
      int x = Integer.parseInt(a[i]), y = Integer.parseInt(a[i+1]);
      if (x<0||y<0||x>=im.getWidth()||y>=im.getHeight()) { System.out.println(x+","+y+" OOB"); continue; }
      int c = im.getRGB(x,y);
      System.out.printf("%d,%d = #%06X  r=%d g=%d b=%d%n", x, y, c & 0xFFFFFF,
        (c>>16)&255, (c>>8)&255, c&255);
    }
  }
}
