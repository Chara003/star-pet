import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
public class Stat {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    int x0=Integer.parseInt(a[1]), y0=Integer.parseInt(a[2]);
    int w=Integer.parseInt(a[3]), h=Integer.parseInt(a[4]);
    TreeMap<Integer,Integer> hist = new TreeMap<>();
    int dark=0, tot=0, minL=999;
    int minR=0,minG=0,minB=0;
    Iterator<Map.Entry<Integer,Integer>> it;
    for (int y=y0;y<y0+h;y++) for (int x=x0;x<x0+w;x++) {
      if (x<0||y<0||x>=im.getWidth()||y>=im.getHeight()) continue;
      int c=im.getRGB(x,y); int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
      // 跳过近白背景
      if (r>238 && g>238 && b>238) continue;
      tot++;
      int l=(r*30+g*59+b*11)/100;
      if (l<minL){minL=l;minR=r;minG=g;minB=b;}
      if (l<100) dark++;
      int bucket=(l/16)*16;
      hist.merge(bucket,1,Integer::sum);
    }
    System.out.println("区域 "+w+"x"+h+" 非背景像素="+tot);
    System.out.println("最暗像素 = #" + String.format("%02X%02X%02X",minR,minG,minB)
      + "  亮度=" + minL);
    System.out.println("亮度<100 的占比 = " + (tot>0 ? (dark*100.0/tot) : 0) + "%");
    System.out.print("亮度直方图: ");
    for (Map.Entry<Integer,Integer> e : hist.entrySet())
      System.out.print(e.getKey()+"-"+ (e.getKey()+15) +":"+e.getValue()+"  ");
    System.out.println();
  }
}
