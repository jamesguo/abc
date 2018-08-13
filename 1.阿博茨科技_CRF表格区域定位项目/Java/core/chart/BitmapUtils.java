package com.abcft.pdfextract.core.chart;

import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Created by dhu on 2017/3/30.
 */
public class BitmapUtils {

    public static class ColorWrapper extends Color implements Clusterable, Comparable<ColorWrapper> {

        private double[] points;
        private float percent;
        public ColorWrapper(int color) {
            super(color);
            this.points = new double[] {getRed(), getGreen(), getBlue()};
        }
        public ColorWrapper(int red, int green, int blue) {
            super(red, green, blue);
        }

        public float getPercent() {
            return percent;
        }

        public void setPercent(float percent) {
            this.percent = percent;
        }

        @Override
        public double[] getPoint() {
            return points;
        }

        @Override
        public int compareTo(ColorWrapper o) {
            return Float.compare(percent, o.percent);
        }
    }

    public static BufferedImage resize(BufferedImage src, int maxPixels) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w * h <= maxPixels) {
            return src;
        }
        double scale = Math.sqrt((double) maxPixels / w / h);
        int outW = (int) (w * scale);
        int outH = (int) (h * scale);

        BufferedImage resizedImg = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = resizedImg.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, outW, outH, null);
        g2.dispose();
        return resizedImg;
    }

    public static List<ColorWrapper> generateColorPalette(BufferedImage image) {
        image = resize(image, 12800);
        List<ColorWrapper> colors = new ArrayList<>(image.getWidth()*image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                ColorWrapper color = new ColorWrapper(image.getRGB(x, y));
                colors.add(color);
            }
        }
        KMeansPlusPlusClusterer<ColorWrapper> clusterer = new KMeansPlusPlusClusterer<>(10, 100);
        List<CentroidCluster<ColorWrapper>> clusterResults = clusterer.cluster(colors);
        List<ColorWrapper> result = new ArrayList<>(10);
        for (CentroidCluster<ColorWrapper> clusterResult : clusterResults) {
            int count = clusterResult.getPoints().size();
            double[] center = clusterResult.getCenter().getPoint();
            ColorWrapper color = new ColorWrapper((int) center[0], (int) center[1], (int) center[2]);
            color.setPercent((float) count / colors.size());
            result.add(color);
        }
        Collections.sort(result, Collections.reverseOrder());
        return result;
    }

    public static boolean isChart(BufferedImage image) {
        List<ColorWrapper>  colorPalette = generateColorPalette(image);
        // 分析图片的颜色成分来判断是不是chart
        ColorWrapper mainColor = colorPalette.get(0);
        float top5Percent = 0;
        for (int i = 0; i < 5; i++) {
            top5Percent += colorPalette.get(i).getPercent();
        }
        if (mainColor.getRed() > 250 && mainColor.getGreen() > 250 && mainColor.getBlue() > 250
                && mainColor.getPercent() > 0.48
                && top5Percent > 0.85) {
            return true;
        }
        return false;
    }
}
