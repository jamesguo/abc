package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.ExtractionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of chart extraction.
 *
 * Created by chzhong on 17-3-2.
 */
public class ChartExtractionResult extends ExtractionResult<Chart> {

    private ArrayList<Chart> charts = new ArrayList<>();

    @Override
    public List<Chart> getItems() {
        return Collections.unmodifiableList(charts);
    }

    public List<Chart> getItemsByPage(int pageIndex) {
        return charts.stream().filter(chart -> chart.pageIndex == pageIndex).collect(Collectors.toList());
    }

    @Override
    public boolean hasResult() {
        return !charts.isEmpty();
    }

    public void addCharts(List<Chart> charts) {
        this.charts.addAll(charts);
    }

    public void addChart(Chart chart) {
        this.charts.add(chart);
    }

    /**
     * 过滤掉内容一样的位图Chart对象
     */
    public void filterSameBitmapChart() {
        ChartUtils.filterSameBitmapChart(charts);
    }
}
