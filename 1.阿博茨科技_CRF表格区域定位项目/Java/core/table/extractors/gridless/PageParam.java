package com.abcft.pdfextract.core.table.extractors.gridless;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.table.TableExtractParameters;
import org.apache.commons.lang3.StringUtils;

import java.awt.geom.Point2D;

@Deprecated
final class PageParam {

    final TableExtractParameters params;
    final Page page;
    final PaperParameter paper;
    int pgIndex;
    int pgWidth;
    int pgHeight;
    int marginTop;
    int marginBottom;
    int marginLeft;
    int marginRight;
    int cellHeight;
    Rectangle textArea;

    boolean isChinesePage = true;
    final boolean pptGenerated;
    final boolean wordGenerated;
    final boolean verticalPage;
    String pdfPath;
    String language;

    PageParam(Page page, TableExtractParameters params) {
        this.params = params;
        this.page = page;
        this.paper = page.getPaper();
        this.pptGenerated = ExtractorUtil.isPPTGeneratedPDF(params);
        this.wordGenerated = ExtractorUtil.isWordGeneratedPDF(params);
        this.language = page.getLanguage();
        this.isChinesePage = StringUtils.equalsIgnoreCase(this.language, "zh");
        this.pgIndex = 0;
        Point2D.Float pageSize = ExtractorUtil.determinePageSize(page.getPDPage());
        this.pgWidth = (int) pageSize.getX();
        this.pgHeight = (int) pageSize.getY();
        this.verticalPage = pageSize.x < pageSize.y;
        this.marginTop = 10;
        this.marginBottom = 10;
        this.marginLeft = 0;
        this.marginRight = 0;
        this.cellHeight = -1;
        this.textArea = Rectangle.fromLTRB(marginLeft, marginTop, pgWidth - marginRight, pgHeight - marginBottom);
    }

    void setCellHeight(int cellHeight) {
        this.cellHeight = Math.min(8, cellHeight);
    }



}
