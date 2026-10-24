import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
public class Crop {
  public static void main(String[] a) throws Exception {
    BufferedImage in = ImageIO.read(new File(a[0]));
    int x=Integer.parseInt(a[1]),y=Integer.parseInt(a[2]),w=Integer.parseInt(a[3]),h=Integer.parseInt(a[4]);
    int sc=Integer.parseInt(a[5]);
    System.out.println("源图 "+in.getWidth()+"x"+in.getHeight()+"  裁 "+x+","+y+" "+w+"x"+h+"  放大 "+sc+"x");
    BufferedImage c = in.getSubimage(x,y,w,h);
    BufferedImage o = new BufferedImage(w*sc,h*sc,BufferedImage.TYPE_INT_ARGB);
    for(int j=0;j<h*sc;j++) for(int i=0;i<w*sc;i++) o.setRGB(i,j,c.getRGB(i/sc,j/sc));
    ImageIO.write(o,"png",new File(a[6]));
    System.out.println("已写 "+a[6]);
  }
}
