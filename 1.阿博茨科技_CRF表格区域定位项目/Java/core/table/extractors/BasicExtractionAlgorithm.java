package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextElement;
import com.abcft.pdfextract.core.table.*;

import java.util.*;

@Deprecated
public class BasicExtractionAlgorithm implements ExtractionAlgorithm {

    public static final String ALGORITHM_NAME = "stream";

    private List<Ruling> verticalRulings = null;

    public BasicExtractionAlgorithm() {
    }

    public BasicExtractionAlgorithm(List<Ruling> verticalRulings) {
        this.verticalRulings = verticalRulings;
    }

    public List<Table> extract(TabularPage page, List<Float> verticalRulingPositions) {
        List<Ruling> verticalRulings = new ArrayList<Ruling>(verticalRulingPositions.size());
        for (Float p: verticalRulingPositions) {
            verticalRulings.add(new Ruling(page.getTop(), p, 0.0f, (float) page.getHeight()));
        }
        this.verticalRulings = verticalRulings;
        return this.extract(page);
    }

    @Override
    public List<Table> extract(Page page) {

        List<TextElement> textElements = page.getText();

        if (textElements.size() == 0) {
            return Collections.emptyList();
        }

        ArrayList<Table> tables = new ArrayList<Table>();

        List<TabularTextChunk> textChunks = this.verticalRulings == null ?
                TabularUtils.mergeWords(page.getText()) : TabularUtils.mergeWords(page.getText(), this.verticalRulings);
        List<TabularLine> lines = TabularTextChunk.groupByLines(textChunks);
        List<Float> columns;

        Table table = new Table(page);

        if (this.verticalRulings != null) {
            Collections.sort(this.verticalRulings, Comparator.comparingDouble(Ruling::getLeft));
            columns = new ArrayList<Float>(this.verticalRulings.size());
            for (Ruling vr: this.verticalRulings) {
                columns.add(vr.getLeft());
            }
        }
        else {
            columns = columnPositions(lines);
        }


        for (int i = 0; i < lines.size(); i++) {
            TabularLine line = lines.get(i);
            List<TabularTextChunk> elements = line.getTextElements();

            Collections.sort(elements, (o1, o2) -> new Float(o1.getLeft()).compareTo(o2.getLeft()));

            for (TabularTextChunk tc: elements) {
                if (tc.isSameChar(TabularLine.WHITE_SPACE_CHARS)) {
                    continue;
                }

                int j = 0;
                boolean found = false;
                for(; j < columns.size(); j++) {
                    if (tc.getLeft() <= columns.get(j)) {
                        found = true;
                        break;
                    }
                }
                table.add(Cell.fromTextChunk(TabularUtils.toTextChunk(tc)), i, found ? j : columns.size());
            }
        }

        /*
        Table newTable = new Table(page, this);

        List<List<RectangularTextContainer>> rows = table.getRows();
        float columnSize = columns.size();
        int rowIndex = 0;
        boolean validHeaderFound = false;
        for (List<RectangularTextContainer> row : rows) {
            int validCells = 0;
            for (RectangularTextContainer cell : row) {
                if (cell.getText().trim().length() > 0) {
                    ++validCells;
                }
            }
            if (!validHeaderFound && ((float)validCells / columnSize < 0.5f)) {
                // Drop invalid rows
                continue;
            }
            validHeaderFound = true;
            int columnIndex = 0;
            for (RectangularTextContainer cell : row) {
                newTable.add(cell, rowIndex, columnIndex++);
            }
            ++rowIndex;
        }
        */

        tables.add(table);

        return tables;
    }

    @Override
    public String toString() {
        return ALGORITHM_NAME;
    }

    @Override
    public String getVersion() {
        return "0.7";
    }


    /**
     * @param lines must be an array of lines sorted by their +top+ attribute
     * @return a list of column boundaries (x axis)
     */
    public static List<Float> columnPositions(List<TabularLine> lines) {

        List<Rectangle> regions = new ArrayList<Rectangle>();
        for (TabularTextChunk tc: lines.get(0).getTextElements()) {
            if (tc.isSameChar(TabularLine.WHITE_SPACE_CHARS)) {
                continue;
            }
            Rectangle r = new Rectangle();
            r.setRect(tc);
            regions.add(r);
        }

        for (TabularLine l: lines.subList(1, lines.size())) {
            List<TabularTextChunk> lineTextElements = new ArrayList<TabularTextChunk>();
            for (TabularTextChunk tc: l.getTextElements()) {
                if (!tc.isSameChar(TabularLine.WHITE_SPACE_CHARS)) {
                    lineTextElements.add(tc);
                }
            }

            for (Rectangle cr: regions) {

                List<TabularTextChunk> overlaps = new ArrayList<TabularTextChunk>();
                for (TabularTextChunk te: lineTextElements) {
                    if (cr.isHorizontallyOverlap(te)) {
                        overlaps.add(te);
                    }
                }

                for (TabularTextChunk te: overlaps) {
                    cr.merge(te);
                }

                lineTextElements.removeAll(overlaps);
            }

            for (TabularTextChunk te: lineTextElements) {
                Rectangle r = new Rectangle();
                r.setRect(te);
                regions.add(r);
            }
        }

        List<Float> rv = new ArrayList<Float>();
        for (Rectangle r: regions) {
            rv.add(r.getRight());
        }

        Collections.sort(rv);

        return rv;

    }

}
