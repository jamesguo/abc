package com.abcft.pdfextract.core.table.writers;

import com.abcft.pdfextract.core.table.Cell;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public class TestExpectedWriter implements Writer {

    private JsonObject context = new JsonObject();
    private JsonArray currentTables;
    private JsonObject currentTable;

    @Override
    public void writeFileStart(Appendable out, String path) {
        context = new JsonObject();
        if (!StringUtils.isEmpty(path)) {
            context.addProperty("fileName", FilenameUtils.getName(path));
        }
        currentTables = new JsonArray();
    }

    @Override
    public void writePageStart(Appendable out, int page) {

    }

    @Override
    public void writeTableStart(Appendable out, Table table) {
        currentTable = new JsonObject();
        currentTable.addProperty("page", table.getPageNumber());
        currentTable.addProperty("index", table.getIndex());
        currentTable.addProperty("algorithm", table.getAlgorithmName());
        JsonUtil.putRectangleAbbr(currentTable, table);
    }

    @Override
    public void writeTableTitle(Appendable out, String title) {
        assert currentTable != null;
        currentTable.addProperty("title", title);
    }

    @Override
    public void writeTableUnitText(Appendable out, String unitText) {
        assert currentTable != null;
        currentTable.addProperty("unitText", unitText);
    }

    @Override
    public void writeTableCaps(Appendable out, String caps) {
        assert currentTable != null;
        currentTable.addProperty("caps", caps);
    }

    @Override
    public void writeTableShoes(Appendable out, String shoes) {
        assert currentTable != null;
        currentTable.addProperty("shoes", shoes);
    }

    @Override
    public void writeTable(Appendable out, Table table) {
        assert currentTable != null;
        currentTable.addProperty("rowCount", table.getRowCount());
        currentTable.addProperty("columnCount", table.getColumnCount());
        JsonArray cells = new JsonArray();
        for (int row = 0; row < table.getRowCount(); ++row) {
            for (int column = 0; column < table.getColumnCount(); ++column) {
                Cell cell = table.getCell(row, column);
                if (cell.isDeleted()) {
                    continue;
                }
                if (cell.isPivot(row, column)) {
                    JsonObject currentCell = new JsonObject();
                    currentCell.addProperty("text", cell.getText());
                    currentCell.addProperty("row", row);
                    currentCell.addProperty("col", column);
                    currentCell.addProperty("rowSpan", cell.getRowSpan());
                    currentCell.addProperty("colSpan", cell.getColSpan());
                    currentCell.addProperty("merge", String.valueOf(false));
                    cells.add(currentCell);
                } else {
                    JsonObject currentCell = new JsonObject();
                    currentCell.addProperty("text", cell.getTextAt(row, column));
                    currentCell.addProperty("row", row);
                    currentCell.addProperty("column", column);
                    if (cell.isLeftMerged(row, column)) {
                        currentCell.addProperty("merge", "left");
                    } else if (cell.isUpMerged(row, column)) {
                        currentCell.addProperty("merge", "up");
                    } else if (cell.isUpLeftMerged(row, column)) {
                        currentCell.addProperty("merge", "up-left");
                    } else {
                        currentCell.addProperty("merge", "unknown");
                    }
                    cells.add(currentCell);
                }
            }
        }
        currentTable.add("cells", cells);
    }

    @Override
    public void writeTableEnd(Appendable out, Table table) {
        assert currentTable != null;
        assert currentTables != null;
        currentTables.add(currentTable);
        currentTable = null;
    }

    @Override
    public void writePageEnd(Appendable out, int page) {

    }

    @Override
    public void writeFileEnd(Appendable out, String path) throws IOException {
        assert context != null;
        assert currentTables != null;
        context.add("tables", currentTables);
        currentTables = null;
        out.append(JsonUtil.toString(context, true));
        context = null;
    }
}
