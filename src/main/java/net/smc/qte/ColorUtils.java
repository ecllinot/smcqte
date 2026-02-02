package net.smc.qte; // 修改为你实际的包名

public class ColorUtils {
    // 模拟 OpenCV 的 RGB 转 HSV (H: 0-180, S: 0-255, V: 0-255)
    public static int[] rgbToHsv(int r, int g, int b) {
        double rNorm = r / 255.0;
        double gNorm = g / 255.0;
        double bNorm = b / 255.0;

        double cMax = Math.max(rNorm, Math.max(gNorm, bNorm));
        double cMin = Math.min(rNorm, Math.min(gNorm, bNorm));
        double delta = cMax - cMin;

        double h = 0;
        if (delta == 0) {
            h = 0;
        } else if (cMax == rNorm) {
            h = 60 * (((gNorm - bNorm) / delta) % 6);
        } else if (cMax == gNorm) {
            h = 60 * (((bNorm - rNorm) / delta) + 2);
        } else if (cMax == bNorm) {
            h = 60 * (((rNorm - gNorm) / delta) + 4);
        }

        if (h < 0) h += 360;

        // OpenCV H is 0-180
        int openCV_H = (int) (h / 2);
        int openCV_S = (cMax == 0) ? 0 : (int) ((delta / cMax) * 255);
        int openCV_V = (int) (cMax * 255);

        return new int[]{openCV_H, openCV_S, openCV_V};
    }

    public static boolean isColorInRange(int[] hsv, int[] lower, int[] upper) {
        return hsv[0] >= lower[0] && hsv[0] <= upper[0] &&
                hsv[1] >= lower[1] && hsv[1] <= upper[1] &&
                hsv[2] >= lower[2] && hsv[2] <= upper[2];
    }
}