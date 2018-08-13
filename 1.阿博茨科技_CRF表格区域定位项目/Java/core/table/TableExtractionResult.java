package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of table extraction.
 *
 * Created by chzhong on 17-3-2.
 */
public class TableExtractionResult extends ExtractionResult<Table> {

    private ArrayList<Table> tables = new ArrayList<>();

    private Table pendingTable = null;

    @Override
    public List<Table> getItems() {
        return Collections.unmodifiableList(tables);
    }

    public List<Table> getItemsByPage(int pageIndex) {
        return tables.stream().filter(table -> table.getPageNumber() == pageIndex+1).collect(Collectors.toList());
    }

    @Override
    public boolean hasResult() {
        return !tables.isEmpty();
    }

    public Table getPendingTable() {
        return pendingTable;
    }

    public void setPendingTable(Table pendingTable) {
        this.pendingTable = pendingTable;
    }

    public void addTable(Table table) {
        if (table == pendingTable) {
            pendingTable = null;
        }
        tables.add(table);
    }

    public void addTables(List<Table> tables) {
        this.tables.addAll(tables);
    }

    public void removeTable(Table table) {
        tables.remove(table);
    }

}
