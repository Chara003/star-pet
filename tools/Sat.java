import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
public class Sat {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    int x0=Integer.parseInt(a[1]), y0=Integer.parseInt(a[2]);
    int w=Integer.parseInt(a[3]), h=Integer.parseInt(a[4]);
    // 收集高饱和像素（S>0.35 且亮度中等，排除纯背景和纯白）
    List<int[]> hits = new ArrayList<>();
    for (int y=y0;y<y0+h;y++) for (int x=x0;x<x0+w;x++) {
      if (x<0||y<0||x>=im.getWidth()||y>=im.getHeight()) continue;
      int c=im.getRGB(x,y); int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
      int mx=Math.max(r,Math.max(g,b)), mn=Math.min(r,Math.min(g,b));
      if (mx<40) continue;
      double s=(mx-mn)/(double)mx;
      int lum=(r*30+g*59+b*11)/100;
      if (s>0.35 && lum>25 && lum<235) hits.add(new int[]{x,y,r,g,b,(int)(s*100)});
    }
    System.out.println("高饱和像素数 = " + hits.size());
    hits.sort((p,q)->q[5]-p[5]);
    for (int i=0;i<Math.min(12,hits.size());i++){
      int[] p=hits.get(i);
      System.out.printf("(%d,%d) #%02X%02X%02X  S=%.2f%n",p[0],p[1],p[2],p[3],p[4],p[5]/100.0);
    }
  }
}
