package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.content.Page;
import com.abcft.pdfextract.core.content.ParagraphMerger;
import com.abcft.pdfextract.core.content.TextChunkMerger;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.spi.ChartType;
import org.apache.pdfbox.pdmodel.PDPage;

import java.util.Comparator;
import java.util.List;

/**
 * Created by myyang on 17-6-22.
 */
public class PPTChartDetector {
    public static Chart detect(ContentGroup root, PDPage page) {
        // 判断传入参数有效性
        if (root == null || page == null) {
            return null;
        }

        // 解析内部文字信息 并适当合并信息
        List<TextChunk> textChunks = root.getAllTextChunks();
        List<TextChunk> merged = new TextChunkMerger().merge(textChunks);
        Page.TextGroup group = new Page.TextGroup(root.getArea());
        group.addTexts(merged);
        List<TextBlock> paragraphs = ParagraphMerger.merge(group);

        paragraphs.sort(Comparator.comparingDouble(TextBlock::getTop));
        // 找出适当标题信息 和 保存 page 内部所有的文字信息
        Chart chart = new Chart();
        // 应搜索最新的需求，　郭萌提出来将PPT页面直接保存为BITMAP_CHART类型
        chart.type = ChartType.BITMAP_CHART;
        //chart.type = ChartType.BITMAP_PPT_CHART;
        chart.isPPT = true;
        chart.contentGroup = root;
        if (!paragraphs.isEmpty()) {
            // 找字号最大的
            float maxFontSize = 0;
            TextBlock largestTextBlock = null;
            for (TextBlock textBlock : paragraphs) {
                if (textBlock.getFirstTextChunk().getFontSize() > maxFontSize) {
                    maxFontSize = textBlock.getFirstTextChunk().getFontSize();
                    largestTextBlock = textBlock;
                }
                chart.texts.add(textBlock.getText().trim());
                chart.textsChunks.add(textBlock.toTextChunk());
            }
            if (largestTextBlock != null && largestTextBlock.getBounds2D().getCenterY() < root.getArea().getCenterY()) {
                chart.titleTextChunk = largestTextBlock.toTextChunk();
            } else {
                chart.titleTextChunk = paragraphs.get(0).toTextChunk();
            }
            chart.title = chart.titleTextChunk.getText().trim();
        }

        // 设置区域
        chart.setLowerLeftArea(ExtractorUtil.getPageCropBox(page));
        chart.setArea(root.getPageTransform().createTransformedShape(chart.getLowerLeftArea()).getBounds2D());
        chart.pageHeight = ExtractorUtil.determinePageSize(page).y;
        return chart;
    }
}
