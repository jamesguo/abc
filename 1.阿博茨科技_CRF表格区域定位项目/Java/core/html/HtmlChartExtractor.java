package com.abcft.pdfextract.core.html;

import com.abcft.pdfextract.core.HtmlExtractor;
import com.abcft.pdfextract.core.chart.*;
import com.abcft.pdfextract.spi.ChartType;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * Created by dhu on 2017/12/8.
 */
public class HtmlChartExtractor implements HtmlExtractor<ChartExtractParameters, Chart, ChartExtractionResult, ChartCallback> {

    @Override
    public int getVersion() {
        return 7;
    }

    private static Logger logger = LogManager.getLogger(ChartExtractor.class);

    @Override
    public ChartExtractionResult process(com.abcft.pdfextract.spi.Document<Document> document, ChartExtractParameters parameters, ChartCallback callback) {
        return extractChart(document.getDocument(), parameters, null, callback);
    }

    private static String absUrl(Element element, String attr) {
        // 原始的Element.absUrl实现有问题, 如果url为空的时候会返回baseUrl, 这里替换成我们的逻辑
        String url = element.attr(attr);
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.isAbsolute()) {
                return url;
            }
            if (url.startsWith("//")) {
                return "http:" + url;
            }
            return StringUtil.resolve(element.baseUri(), url);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Element findParentByNodeName(Element element, String nodeName) {
        Element parent = element.parent();
        while (parent != null && !StringUtils.equalsIgnoreCase(nodeName, parent.nodeName())) {
            parent = parent.parent();
        }
        return parent;
    }

    private ChartExtractionResult extractChart(Document document, ChartExtractParameters parameters, Writer writer, ChartCallback callback) {
        ChartExtractionResult result = new ChartExtractionResult();
        try {
            if (callback != null) {
                callback.onStart(document);
            }
            int index = 0;
            Map<String, Integer> imageUrlCount = new HashMap<>();
            List<Chart> charts = new ArrayList<>();
            CRC32 crc32 = new CRC32();
            for (Element element : document.select("img")) {
                String src = absUrl(element, "src");
                if (StringUtils.isEmpty(src)) {
                    src = absUrl(element, "data-src");
                }

                if (StringUtils.isEmpty(src)) {
                    continue;
                }
                imageUrlCount.compute(src, (k, v) -> v == null ? 1 : v + 1);
                Chart chart = new Chart();
                chart.imageUrl = src;
                chart.type = ChartType.BITMAP_CHART;
                chart.pageIndex = 0;
                chart.setChartIndex(index++);
                crc32.reset();
                crc32.update(src.getBytes("utf-8"));
                chart.setName(Long.toHexString(crc32.getValue()));
                Element titleElement = element.previousElementSibling();
                if (titleElement == null) {
                    titleElement = element.parent().previousElementSibling();
                }
                if (titleElement != null) {
                    chart.title = titleElement.text().trim();
                }
                if (StringUtils.isBlank(chart.title)) {
                    // 处理img在table里面的情况
                    Element tr = findParentByNodeName(element, "tr");
                    if (tr != null) {
                        Element prevTr = tr.previousElementSibling();
                        if (prevTr != null) {
                            List<String> titles = prevTr.select("td").eachText().stream()
                                    .filter(StringUtils::isNotBlank)
                                    .collect(Collectors.toList());
                            int imageIndex = tr.select("img").indexOf(element);
                            if (imageIndex >= 0 && imageIndex < titles.size()) {
                                chart.title = titles.get(imageIndex).trim();
                            } else if (titles.size() == 1) {
                                chart.title = titles.get(0).trim();
                            }
                        }
                    }
                }
                // 过滤标题
                if (StringUtils.endsWithAny(chart.title, ".", "。")
                        || (chart.title != null && chart.title.length() > 50)) {
                    chart.title = null;
                }
                charts.add(chart);
            }
            // 删除多次出现的图片, 可能是logo一类的图
            charts.removeIf(chart -> imageUrlCount.getOrDefault(chart.imageUrl, 0) > 2);
            if (callback != null) {
                for (Chart chart : charts) {
                    callback.onItemExtracted(chart);
                }
            }
            // 删除保存失败的位图
            charts.removeIf(chart -> chart.getImageFile() == null);
            result.addCharts(charts);
            if (callback != null) {
                callback.onFinished(result);
            }
            return result;
        } catch (Exception e) {
            if (callback != null) {
                callback.onFatalError(e);
            }
        }
        return null;
    }
}
