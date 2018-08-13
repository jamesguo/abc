package com.abcft.pdfextract.core.chart;

import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.*;
import java.io.IOException;

/**
 * Created by dhu on 12/01/2017.
 */
public interface ChartRenderer {
    void processPage(PDPage page, ChartCallback chartCallback);
    void renderChartToGraphics(Chart chart, Graphics2D graphics, float scale) throws IOException;
}
