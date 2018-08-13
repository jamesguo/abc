package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.ExtractParameters;
import com.abcft.pdfextract.util.MapUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.util.Map;

/**
 * Parameters for chart extraction.
 *
 * Created by chzhong on 17-3-7.
 */
public class ChartExtractParameters extends ExtractParameters {

    public static final String PARAM_SAVE_PPT = "chart.savePPT";

    public static final class Builder extends ExtractParameters.Builder<ChartExtractParameters> {

        private boolean checkBitmap = true;
        private boolean savePPT = false;
        private boolean detectChart = false;
        private boolean useChartClassify = false;

        public Builder() {
            super();
        }

        public Builder(Map<String, String> params) {
            super(params);
            this.savePreviewImage = MapUtils.getBoolean(params, "chart.savePreviewImage", true);
            this.checkBitmap = MapUtils.getBoolean(params,"chart.checkBitmap", checkBitmap);
            this.savePPT = MapUtils.getBoolean(params, PARAM_SAVE_PPT, savePPT);
            this.detectChart = MapUtils.getBoolean(params,"chart.detectChart", detectChart);
            this.useChartClassify = MapUtils.getBoolean(params, "chart.useChartClassify", useChartClassify);
            setIgnoreAreas(params.get("chart.ignoredAreas"));
            setHintAreas(params.get("chart.hintAreas"));
        }

        public Builder setCheckBitmap(boolean enable) {
            this.checkBitmap = enable;
            return this;
        }

        public Builder setSavePPT(boolean savePPT) {
            this.savePPT = savePPT;
            return this;
        }

        public Builder setDetectChart(boolean enable) {
            this.detectChart = enable;
            return this;
        }

        public Builder setUseChartClassify(boolean useChartClassify) {
            this.useChartClassify = useChartClassify;
            return this;
        }

        @Override
        public ChartExtractParameters build() {
            return new ChartExtractParameters(this);
        }
    }

    @Override
    public Builder buildUpon() {
        return buildUpon(new Builder())
                .setCheckBitmap(checkBitmap)
                .setSavePPT(savePPT)
                .setDetectChart(detectChart)
                .setUseChartClassify(useChartClassify);
    }

    private ChartExtractParameters(Builder builder) {
        super(builder);
        this.checkBitmap = builder.checkBitmap;
        this.savePPT = builder.savePPT;
        this.detectChart = builder.detectChart;
        this.useChartClassify = builder.useChartClassify;
    }

    final boolean checkBitmap;
    final boolean savePPT;
    final boolean detectChart;
    final boolean useChartClassify;

}
