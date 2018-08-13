package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.table.Cell;
import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.extractors.SpreadsheetExtractionAlgorithm;
import com.abcft.pdfextract.core.util.NumberUtil;

import java.util.Iterator;
import java.util.List;

/**
 * Created by matt on 2015-12-14.
 *
 * This is the basic spreadsheet table detection algorithm currently implemented in tabula (web).
 *
 * It uses intersecting ruling lines to find tables.
 */
@Deprecated
public class SpreadsheetDetectionAlgorithm implements DetectionAlgorithm {
    @Override
    public List<Rectangle> detect(Page page) {
        List<Cell> cells = SpreadsheetExtractionAlgorithm.findCells(page.getHorizontalRulings(), page.getVerticalRulings());

        List<Rectangle> tables = SpreadsheetExtractionAlgorithm.findSpreadsheetsFromCells(cells);

        // Remove one-cell table: They are probably used for alignment, not table.
        Iterator<Rectangle> iter = tables.iterator();
        while (iter.hasNext()) {
            Rectangle table = iter.next();
            for (Cell cell : cells) {
                if (table.equals(cell)) {
                    iter.remove();
                    break; // for
                }
            }
        }

        // we want tables to be returned from top to bottom on the page
        NumberUtil.sortByReadingOrder(tables);

        return tables;
    }
}
