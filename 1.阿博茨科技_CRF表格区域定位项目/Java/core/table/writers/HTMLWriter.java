package com.abcft.pdfextract.core.table.writers;

import com.abcft.pdfextract.core.table.Cell;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * Created by dhu on 2017/11/27.
 */
public class HTMLWriter implements Writer {

    private static final Logger logger = LogManager.getLogger();

    @Override
    public void writeFileStart(Appendable out, String path) throws IOException {
        out.append("<html><head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "<title>Table解析结果</title>\n" +
                "<link rel=\"stylesheet\" href=\"https://cdn.bootcss.com/bootstrap/3.3.7/css/bootstrap.min.css\">\n" +
                "<style>\n" +
                ".table-caps {font-style: italic;}\n" +
                ".table-unit {font-style: italic;}\n" +
                ".table-shoes {font-style: italic;}\n" +
                ".table-row-cross-page {font-style: italic}\n" +
                ".table-title {}\n" +
                ".pdf-table {width: auto; margin-bottom: 20px; border: 1px solid #ddd;border-collapse: collapse;}\n" +
                ".pdf-table>tbody>tr:nth-of-type(odd) {background-color: #f9f9f9;}\n" +
                ".pdf-table>tbody>tr>td, .pdf-table>tbody>tr>th, .pdf-table>tfoot>tr>td, .pdf-table>tfoot>tr>th, " +
                ".pdf-table>thead>tr>td, .pdf-table>thead>tr>th {border: 1px solid #ddd; padding: 8px; line-height: 1.42857143;vertical-align: middle;}\n" +
                "</style>\n" +
                "</head><body style=\"margin: 20px;\">");
    }

    @Override
    public void writeFileEnd(Appendable out, String path) throws IOException {
        out.append("</body></html>");
    }

    @Override
    public void writePageStart(Appendable out, int page) throws IOException {
        out.append("<h2>Page ").append(String.valueOf(page)).append("</h2>\n");
    }


    @Override
    public void writePageEnd(Appendable out, int page) {

    }

    @Override
    public void writeTableTitle(Appendable out, String title) throws IOException {
        out.append("<h3 class='table-title'>Table: ").append(title).append("</h3>\n");
    }

    @Override
    public void writeTableCaps(Appendable out, String caps) throws IOException {
        out.append("<h4 class='table-caps'>Caps: ").append(caps).append("</h4>\n");
    }

    @Override
    public void writeTableUnitText(Appendable out, String unitText) throws IOException {
        out.append("<h4 class='table-unit'>Unit: ").append(unitText).append("</h4>\n");
    }

    @Override
    public void writeTableShoes(Appendable out, String shoes) throws IOException {
        out.append("<h4 class='table-shoes'>Shoes: ").append(shoes).append("</h4>\n");
    }

    @Override
    public void writeTableStart(Appendable out, Table table) {

    }

    @Override
    public void writeTableEnd(Appendable out, Table table) throws IOException {
        out.append("<br/>\n\n");
    }

    @Override
    public void writeTable(Appendable out, Table table) {
        try {
            Table prevTable = table.getPrevTable();

            if (prevTable != null) {
                // 表格已经被逻辑合并，不再输出跨页表格
                return;
            }

            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();

            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            Document doc = docBuilder.newDocument();
            Element rootElement = doc.createElement("table");

            rootElement.setAttribute("data-table-type", String.valueOf(table.getTableType()));
            rootElement.setAttribute("id", table.tableId);
            rootElement.setAttribute("data-confidence", String.valueOf(FloatUtils.round(table.getConfidence(), 2)));
            /*
            if (nextTable != null) {
                rootElement.setAttribute("data-next-table", nextTable.tableId);
            }
            if (prevTable != null) {
                rootElement.setAttribute("data-prev-table", prevTable.tableId);
            }
            */
            rootElement.setAttribute("data-page", String.valueOf(table.getPageNumber()));
            rootElement.setAttribute("data-rect",
                    String.format("%d,%d,%d,%d", (int)table.getMinX(),(int)table.getMinY(), (int)table.getWidth(), (int)table.getHeight()));
            if (StringUtils.isNoneBlank(table.getTag())) {
                rootElement.setAttribute("data-tag", table.getTag());
            }
            rootElement.setAttribute("class", "pdf-table");
            /*
            if (prevTable != null) {
                rootElement.setAttribute("class", "pdf-table hidden");
            } else {
                rootElement.setAttribute("class", "pdf-table");
            }
            */
            rootElement.setAttribute("class", "pdf-table");
            doc.appendChild(rootElement);
            writeTableBody(table, doc, rootElement);

            /*
            if (prevTable != null) {
                while (nextTable != null) {
                    writeTableBody(nextTable, doc, rootElement);
                    nextTable = nextTable.getNextTable();
                }
            }
            */

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            DOMSource source = new DOMSource(doc);
            StringWriter writer = new StringWriter();
            StreamResult target = new StreamResult(writer);
            transformer.transform(source, target);
            out.append(writer.toString());
        } catch (ParserConfigurationException e) {
            logger.warn("create xml failed.", e);
        } catch (TransformerException e) {
            logger.warn("transform xml failed", e);
        } catch (Exception e) {
            logger.warn("write table failed", e);
        }
    }

    private void writeTableBody(Table table, Document doc, Element tableElement) {
        int rowCount = table.getRowCount();
        int colCount = table.getColumnCount();
        for (int i = 0; i < rowCount; i++) {
            Element tr = doc.createElement("tr");
            if (table.getCell(i,0).getCrossPageRow() != null && !tr.hasAttribute("data-cross-page-row")) {
                if (table.getCell(i,0).getCrossPageRow() == Cell.CrossPageRowType.FirstRow) {
                    tr.setAttribute("data-cross-page-row", "firstRow");
                } else if (table.getCell(i,0).getCrossPageRow() == Cell.CrossPageRowType.LastRow) {
                    tr.setAttribute("data-cross-page-row", "lastRow");
                }
                tr.setAttribute("class", "table-row-cross-page");
            }

            for (int j = 0; j < colCount; j++) {
                Cell cell = table.getCell(i, j);
                if (cell == null || !cell.isPivot(i, j) || cell.isDeleted()) {
                    continue;
                }
                String text = cell.getTextAt(i, j);
                if (text == null) {
                    text = "";
                }
                Element td = doc.createElement("td");
                if (cell.getRowSpan() > 1) {
                    td.setAttribute("rowspan", String.valueOf(cell.getRowSpan()));
                }
                if (cell.getColSpan() > 1) {
                    td.setAttribute("colspan", String.valueOf(cell.getColSpan()));
                }
                if (cell.isCrossPageCell()) {
                    td.setAttribute("data-cross-page-cell", String.valueOf(cell.isCrossPageCell()));
                }
                String[] lines = text.split("\n");
                for (int k = 0; k < lines.length; k++) {
                    String line = lines[k];
                    if (StringUtils.isBlank(line)) {
                        continue;
                    }
                    if (k > 0) {
                        td.appendChild(doc.createElement("br"));
                    }
                    td.appendChild(doc.createTextNode(line));
                }
                tr.appendChild(td);
            }
            tableElement.appendChild(tr);
        }
    }

}
