package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.table.*;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 综合表格提取算法。
 */
public enum CompoundTableExtractionAlgorithm implements ExtractionAlgorithm {

    INSTANCE;

    public static final String ALGORITHM_NAME = "Compound";
    // 算法发布日期。
    private static final String ALGORITHM_DATE = "20180721";
    // 版本号不能带小数点(.),否则保存时会出错
    public static final String ALGORITHM_VERSION =  ALGORITHM_NAME + "-v" + ALGORITHM_DATE;

    private static final Logger logger = LogManager.getLogger();


    @Override
    public List<? extends Table> extract(Page page) {
        // 根据参数，使用各种这样的方法，尽量召回表格
        // 此处调用逻辑架构上不一样的各种算法，然后将结果进行整合。

        TableExtractParameters params = page.getParams();
        int pageNumber = page.getPageNumber();


        List<Table> tables = new ArrayList<>();

        if (page.getValidTextLength() < 2) {
            logger.warn(String.format("Page %d: too few text to extract with vector algorithm.", pageNumber));
            if (!params.useBitmapPageFallback) {
                return Lists.newArrayList();
            }
            applyBitmapTableExtraction(page, tables);
            //page.commitTables(tables);
            return tables;
        }

        ContentGroupPage contentGroupPage;
        if (page instanceof ContentGroupPage) {
            contentGroupPage = (ContentGroupPage) page;
        } else {
            contentGroupPage = ContentGroupPage.fromPage(page);
        }

        // 1. 根据矢量算法，查找表格
        VectorTableExtractionAlgorithm vectorAlgorithm = new VectorTableExtractionAlgorithm();
        tables.addAll(vectorAlgorithm.extract(contentGroupPage));

        // 2. 根据参数，决定是否有位图算法补充
        if (params.useBitmapPageFallback) {
            applyBitmapTableExtraction(page, tables);
            params.stopWatch.split("Bitmap Table Extract");
        }

        // TODO 不同算法之间的去重

        return tables;
    }

    @Override
    public String getVersion() {
        return ALGORITHM_VERSION;
    }

    private void applyBitmapTableExtraction(Page page, List<Table> tables) {
        ContentGroupPage tablePage;
        boolean shouldCleanup = false;
        if (!(page instanceof ContentGroupPage)) {
            tablePage = ContentGroupPage.fromPage(page);
            shouldCleanup = true;
        } else {
            tablePage = (ContentGroupPage) page;
        }
        try {
            int oldSize = tables.size();
            BitmapPageExtractionAlgorithm bitmapPageAlgorithm = (BitmapPageExtractionAlgorithm) ExtractionMethod.BITMAP_PAGE.getExtractionAlgorithm(page);
            bitmapPageAlgorithm.addExtractTables(tablePage, tables);
            int extraCount = tables.size() - oldSize;
            logger.info(String.format("Page %d: Got %d extra tables by bitmap-page algorithm", page.getPageNumber(), extraCount));
        } catch (Throwable e) {
            logger.warn("Failed to apply bitmap extraction.", e);
        } finally {
            if (shouldCleanup) {
                tablePage.clearNonText();
            }
        }
    }

}
