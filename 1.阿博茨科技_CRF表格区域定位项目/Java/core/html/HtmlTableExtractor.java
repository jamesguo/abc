package com.abcft.pdfextract.core.html;

import com.abcft.pdfextract.core.HtmlExtractor;
import com.abcft.pdfextract.core.table.*;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.io.Writer;
import java.util.*;
import java.util.List;

/**
 * Created by dhu on 2017/12/8.
 */
public class HtmlTableExtractor implements HtmlExtractor<TableExtractParameters, Table, TableExtractionResult, TableCallback> {


    private static final String ALGORITHM_NAME = "Html";

    private static Logger logger = LogManager.getLogger(HtmlTableExtractor.class);

    @Override
    public int getVersion() {
        return 3;
    }


    @Override
    public TableExtractionResult process(com.abcft.pdfextract.spi.Document<Document> document, TableExtractParameters parameters, TableCallback callback) {
        return extractTable(document.getDocument(), parameters, null, callback);
    }

    private static boolean inTable(Element element) {
        Element parent = element.parent();
        while (parent != null) {
            if (parent.nodeName().equals("table")) {
                return true;
            }
            parent = parent.parent();
        }
        return false;
    }

    private static Elements findChildren(Element root, String... nodeNames) {
        Elements elements = new Elements();
        List<String> searchList = Arrays.asList(nodeNames);
        for (Element e : root.children()) {
            if (searchList.contains(e.nodeName())) {
                elements.add(e);
            }
        }
        return elements;
    }

    private static Elements findChildren(Elements root, String... nodeNames) {
        Elements elements = new Elements();
        List<String> searchList = Arrays.asList(nodeNames);
        for (Element element : root) {
            for (Element e : element.children()) {
                if (searchList.contains(e.nodeName())) {
                    elements.add(e);
                }
            }
        }
        return elements;
    }

    private static String getElementText(Element element) {
        final StringBuilder accum = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if(node instanceof TextNode) {
                    TextNode textNode = (TextNode)node;
                    String text = textNode.text();
                    if (accum.length() > 0 && StringUtils.isBlank(accum.substring(accum.length()-1, accum.length()))
                            && StringUtils.isBlank(text)) {
                        return;
                    }
                    accum.append(text);
                } else if(node instanceof Element) {
                    Element element = (Element)node;
                    if(accum.length() > 0) {
                        char lastChar = accum.charAt(accum.length() - 1);
                        String nodeName = element.nodeName();
                        if (lastChar != '\n'
                                && ("br".equals(nodeName) || "tr".equals(nodeName) || "p".equals(nodeName))) {
                            accum.append("\n");
                        } else if (element.isBlock() && lastChar != ' ' && lastChar != '\n') {
                            accum.append(' ');
                        }
                    }
                }
            }

            public void tail(Node node, int depth) {
            }
        }, element);
        return accum.toString().trim();
    }

    private TableExtractionResult extractTable(Document document, TableExtractParameters parameters, Writer writer, TableCallback callback) {
        TableExtractionResult result = new TableExtractionResult();
        try {
            if (callback != null) {
                callback.onStart(document);
            }
            for (Element element : document.select("table")) {
                if (inTable(element)) {
                    continue;
                }
                // 如果table内包含图片, 跳过
                if (!element.select("img").isEmpty()) {
                    continue;
                }
                Table table = new Table();
                table.setPageNumber(1);
                table.setIndex(result.getItems().size());
                table.setAlgorithmProperty(ALGORITHM_NAME, String.valueOf(getVersion()));
                int row = 0;
                boolean firstTr = true;
                Elements tbody = findChildren(element, "thead", "tbody");
                for (Element tr : findChildren(tbody, "tr")) {
                    String text = tr.text();
                    // 跳过空行对合并的列会出现问题, 不跳过
//                    if (StringUtils.isBlank(text)) {
//                        continue;
//                    }
                    if (firstTr) {
                        if (text.startsWith("Table ")) {
                            table.setTitle(text);
                        }
                        firstTr = false;
                    }
                    for (Element td : findChildren(tr, "td", "th")) {
                        int colspan = 1;
                        int rowspan = 1;
                        if (td.hasAttr("colspan")) {
                            colspan = Integer.parseInt(td.attr("colspan"));
                        }
                        if (td.hasAttr("rowspan")) {
                            rowspan = Integer.parseInt(td.attr("rowspan"));
                        }
                        int col = findNextColByRow(table, row);
                        Cell cell = new Cell(col, row, colspan, rowspan);
                        cell.setText(getElementText(td));
                        table.add(cell, row, col);
                        if (rowspan > 1 || colspan > 1) {
                            cell.setRowSpan(rowspan);
                            cell.setColSpan(colspan);
                            table.addMergedCell(cell, row, col, rowspan, colspan);
                        }
                    }
                    row++;
                }
                // 解析table测试集数据
                if (element.hasAttr("data-rect") && element.hasAttr("id")) {
                    String[] dataRect = element.attr("data-rect").split(",");
                    String[] idParts = element.attr("id").split("_");
                    String tableTypeText = null;
                    if (element.hasAttr("data-table-type")) {
                        tableTypeText = element.attr("data-table-type");
                    } else if (element.hasAttr("table-type")) {
                        // 兼容不规范的 HTML 标记数据
                        tableTypeText = element.attr("table-type");
                    }
                    TableType tableType = EnumUtils.getEnum(TableType.class, tableTypeText);
                    table.setTableType(tableType);

                    table.setPageNumber(Integer.parseInt(idParts[1])+1);
                    table.setIndex(Integer.parseInt(idParts[2]));
                    table.setRect(Float.parseFloat(dataRect[0]), Float.parseFloat(dataRect[1]),
                            Float.parseFloat(dataRect[2]), Float.parseFloat(dataRect[3]));
                    Element prevElement = element.previousElementSibling();
                    if (Objects.equals(prevElement.tag().toString(), "h4")) {
                        String unit = prevElement.text();
                        if (prevElement.hasClass("table-unit") || unit.startsWith("Unit:")) {
                            unit = unit.substring("Unit:".length()).trim();
                        }
                        table.setUnit(unit, null);

                        prevElement = prevElement.previousElementSibling();
                    }
                    if (Objects.equals(prevElement.tag().toString(), "h4")) {
                        String caps = prevElement.text();
                        if (prevElement.hasClass("table-caps") || caps.startsWith("Caps:")) {
                            caps = caps.substring("Caps:".length()).trim();
                        }
                        table.setCaps(caps);

                        prevElement = prevElement.previousElementSibling();
                    }

                    if (Objects.equals(prevElement.tag().toString(), "h3")) {
                        String title = prevElement.text();
                        if (prevElement.hasClass("table-title") || title.startsWith("Table:")) {
                            title = title.substring("Table:".length()).trim();
                        }
                        table.setTitle(title);
                    }

                    Element nextElement = element.nextElementSibling();
                    if (Objects.equals(nextElement.tag().toString(), "h4")) {
                        String shoes = nextElement.text();
                        if (nextElement.hasClass("table-shoes") || shoes.startsWith("Shoes:")) {
                            shoes = shoes.substring("Shoes:".length()).trim();
                        }
                        table.setShoes(shoes);
                    }
                } else {
                    if (table.getRowCount() <= 1 || table.getCellCount() <= 1) {
                        continue;
                    }
                    if (StringUtils.isBlank(table.getTitle())) {
                        // 从table标签上面找标题
                        assignTableTitle(table, element);
                    }
                    mergeColumn(table);
                    table.setRight(table.getColumnCount());
                    table.setBottom(table.getRowCount());
                }
                result.addTable(table);
                if (callback != null) {
                    callback.onItemExtracted(table);
                }
            }
            if (callback != null) {
                callback.onFinished(result);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onFatalError(e);
            }
        }
        return result;
    }

    private static boolean isCenterAlign(Element element) {
        return element != null && element.hasAttr("style") && element.attr("style").contains("text-align:center");
    }

    private void assignTableTitle(Table table, Element element) {
        // 查找table上面的p标签
        Element parent = element.parent();
        if (parent == null) {
            return;
        }
        if (parent.children().size() == 1) {
            assignTableTitle(table, parent);
        } else {
            LinkedList<Element> titleElements = new LinkedList<>();
            Element prev = element;
            do {
                prev = prev.previousElementSibling();
                if (prev != null && "p".equals(prev.nodeName())) {
                    if (StringUtils.isNoneBlank(prev.text())) {
                        titleElements.add(0, prev);
                        // 如果是居中的话, 可能是多行的标题
                        if (isCenterAlign(prev) && isCenterAlign(prev.previousElementSibling())) {
                            // 继续查找上一个, 如果上一个也是居中的就保留
                        } else {
                            break;
                        }
                    } else {
                        // 继续查找上一个
                    }
                } else {
                    break;
                }
            } while (prev != null);
            StringBuilder builder = new StringBuilder();
            for (Element titleElement : titleElements) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(titleElement.text());
            }
            table.setTitle(builder.toString());
        }
    }

    private int findNextColByRow(Table table, int row) {
        int col;
        for (col = 0; col <= table.getColumnCount(); col++) {
            if (table.getCell(row, col).isEmpty()) {
                return col;
            }
        }
        return col;
    }

    // 美股公告HTML很多$, (, )等符号单独为一列, 合并起来
    private void mergeColumn(Table table) {
        // 向右合并
        for (int col = 0; col < table.getColumnCount()-1; col++) {
            boolean canMergeRight = true;
            for (int row = 0; row < table.getRowCount(); row++) {
                Cell cell = table.getCell(row, col);
                if (cell.isDeleted()) {
                    canMergeRight = false;
                    break;
                }
                String text = cell.getText();
                if (!(StringUtils.isBlank(text) || "$".equals(text) || "(".equals(text)
                        || table.getCell(row, col+1) == cell)) {
                    canMergeRight = false;
                    break;
                }
            }
            if (canMergeRight) {
                for (int row = 0; row < table.getRowCount(); row++) {
                    Cell cell = table.getCell(row, col);
                    Cell rightCell = table.getCell(row, col+1);
                    if (cell != rightCell) {
                        cell.markDeleted();
                        rightCell.setText(cell.getText() + rightCell.getText());
                    } else {
                        cell.setColSpan(cell.getColSpan()-1);
                    }
                }
            }
        }
        // 向左合并
        for (int col = 1; col < table.getColumnCount(); col++) {
            boolean canMergeLeft = true;
            for (int row = 0; row < table.getRowCount(); row++) {
                Cell cell = table.getCell(row, col);
                if (cell.isDeleted()) {
                    canMergeLeft = false;
                    break;
                }
                String text = cell.getText();
                if (!(StringUtils.isBlank(text) || ")".equals(text)
                        || table.getCell(row, col-1) == cell)) {
                    canMergeLeft = false;
                    break;
                }
            }
            if (canMergeLeft) {
                for (int row = 0; row < table.getRowCount(); row++) {
                    Cell cell = table.getCell(row, col);
                    Cell leftCell = table.getCell(row, col-1);
                    if (cell != leftCell) {
                        cell.markDeleted();
                        leftCell.setText(leftCell.getText() + cell.getText());
                    } else {
                        cell.setColSpan(cell.getColSpan()-1);
                    }
                }
            }
        }
    }

}
