import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Verify that a headless JDK can discover and render an installed font. */
class FontProbe {
    public static void main(String[] args) {
        BufferedImage image = new BufferedImage(160, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
            if (graphics.getFontMetrics().stringWidth("MJN 123") <= 0) {
                throw new IllegalStateException("No usable font metrics");
            }
            graphics.drawString("MJN 123", 8, 35);
            System.out.println("FONT_PROBE_OK");
        } finally {
            graphics.dispose();
        }
    }
}
