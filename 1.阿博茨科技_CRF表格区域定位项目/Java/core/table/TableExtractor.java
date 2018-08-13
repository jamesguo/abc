/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractParameters;
import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.PdfExtractor;
import com.abcft.pdfextract.core.chart.ChartCallback;
import com.abcft.pdfextract.core.chart.ChartExtractParameters;
import com.abcft.pdfextract.core.chart.ChartExtractionResult;
import com.abcft.pdfextract.core.chart.ChartExtractor;
import com.abcft.pdfextract.core.table.detectors.PostProcessTableRegionsAlgorithm;
import com.abcft.pdfextract.core.table.extractors.BitmapPageExtractionAlgorithm;
import com.abcft.pdfextract.core.table.writers.*;
import com.abcft.pdfextract.core.util.DebugHelper;
import com.abcft.pdfextract.spi.Document;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;


/**
 * This class will take a pdf document and extract tables.
 * <p>
 * The basic flow of this process is that we get a document and use a series of processXXX() functions that work on
 * smaller and smaller chunks of the page. Eventually, we fully process each page and then print it.
 *
 * @author Albert Zhong
 */
public class TableExtractor implements PdfExtractor<TableExtractParameters, Table, TableExtractionResult, TableCallback> {

    @Override
    public int getVersion() {
        return 48;
    }

    private static final String PAGE_ARG_TABLE_PAGE = "table.page";

    static final Logger logger = LogManager.getLogger(TableExtractor.class);

    @Override
    public TableExtractionResult process(Document<PDDocument> document, TableExtractParameters params, TableCallback callback) {
        TableExtractionResult result;
        try {
            //对于自动化表格测试需开启chart检测
            if (params.useChartResultInTableTest) {
                ChartExtractParameters chartParameters = new ChartExtractParameters.Builder()
                        .setCheckBitmap(false)
                        .setStartPageIndex(params.startPageIndex)
                        .setEndPageIndex(params.endPageIndex)
                        .build();
                chartParameters.context = params.getExtractContext();
                ChartExtractor chartExtractor = new ChartExtractor();
                chartExtractor.process(document, chartParameters, new ChartCallback() {
                    @Override
                    public void onExtractionError(Throwable e) {}
                    @Override
                    public void onLoadError(Throwable e) {}
                    @Override
                    public void onFatalError(Throwable e) {}
                    @Override
                    public void onFinished(ChartExtractionResult result) {}
                });
            }

            result = extractTables(document.getDocument(), params, callback);
        } finally {
            DebugHelper.clearCachedImage();
        }
        return result;
    }

    @Override
    public void processPage(PDDocument document, int pageIndex, PDPage page,
                            TableExtractParameters params, TableExtractionResult result, TableCallback callback) {
        try {
            extractTablesFromPage(pageIndex + 1, page, params, result, callback);
        } finally {
            DebugHelper.clearCachedImage();
        }
    }

    @Override
    public void postProcessing(PDDocument document, TableExtractParameters parameters, TableExtractionResult result, TableCallback callback) {
        Table pendingTable = result.getPendingTable();
        if (pendingTable != null) {
            finishPendingTable(pendingTable, result, callback);
        }
        /*
        // 开启跨页表格合并
        if (parameters.useCrossPageTableMerge) {
            alignCrossPageTables(result);
        }
        */
        parameters.context.setTables(result.getItems());
        parameters.stopWatch.print(TimeUnit.MILLISECONDS);
    }

    /**
     * This will return the tables of a PDF document.<br>
     * NOTE: The document must not be encrypted when coming into this method.
     *
     * @param parameters parameters.
     * @param document   the PDF document.
     * @param callback   the callback handle tables are detected.
     * @return The tables of the PDF page.
     */
    private TableExtractionResult extractTables(PDDocument document, TableExtractParameters parameters, TableCallback callback) {
        TableExtractionResult result = new TableExtractionResult();
        if (callback != null) {
            callback.onStart(document);
        }
        if (parameters.debug) {
            TableDebugUtils.enableDebug();
        }
        if (parameters.useCrossPageTableMerge) {
            logger.info("Cross-page tables merging is enabled.");
        }
        // 默认暂时不开启
        if (parameters.useVectorRuleFilterTable) {
            logger.info("Vector-rule filter table is enbled!");
        }
        ExtractorUtil.forEachEffectivePages(document, parameters, ((pageIndex, page) ->
                extractTablesFromPage(pageIndex + 1, page, parameters, result, callback)));
        postProcessing(document, parameters, result, callback);

        if (callback != null) {
            callback.onFinished(result);
        }
        return result;
    }

    public static String getTablePageArg() {
        return PAGE_ARG_TABLE_PAGE;
    }

    private Page getPreviousPage(int pageNumber, PdfExtractContext context) {
        try {
            if (pageNumber <= 1) {
                return null;
            }
            // pageNumber - 1 = current page index
            // pageNumber - 2 = previous page index
            Page page = context.getPageContext(pageNumber - 2).getArg(PAGE_ARG_TABLE_PAGE);
            if (null == page) {
                logger.warn(String.format("Previous page is null at page=%d.", pageNumber));
            }
            return page;
        } catch (Throwable e) {
            logger.warn(String.format("Failed to load previous page (pageNumber=%d).", pageNumber), e);
        }
        return null;
    }

    private void cachePage(PDPage page, Page tablePage, PdfExtractContext context) {
        PdfExtractContext.PageContext ctx = context.getPageContext(page);
        if (tablePage != null) {
            ctx.putArg(PAGE_ARG_TABLE_PAGE, tablePage);
        } else {
            ctx.removeArg(PAGE_ARG_TABLE_PAGE);
        }
    }

    private void extractTablesFromPage(int pageNumber, PDPage pdPage, TableExtractParameters parameters, TableExtractionResult result, final TableCallback callback) {
        Page tablePage = null;
        PdfExtractContext context = parameters.getExtractContext();
        try {
            parameters.stopWatch.start();

            // 解析表格之前的预处理


            boolean hasAnyFonts = ExtractorUtil.resourcesHasFont(pdPage.getResources());
            if (!hasAnyFonts) {
                // TODO 如果遇到扫描稿，这里需要更合适的处理机制
                logger.warn(String.format("Page %d: No fonts or text.", pageNumber));
                return;
            }

            // 收集基本信息
            tablePage = ContentGroupTableExtractor.createPage(pageNumber, pdPage, parameters);
            if (null == tablePage) {
                logger.warn(String.format("Page %d: failed to create page.", pageNumber));
                return;
            }

            Page prevPage = getPreviousPage(pageNumber, context);
            tablePage.setPrevPage(prevPage);
            cachePage(pdPage, tablePage, context);

            tablePage.pdfPath = parameters.path;

            // 根据算法提取表格
            // 这个方法会最大化确保召回表格。
            List<Table> tables = parameters.algorithm.extractTables(tablePage, parameters.guessAreas);
            parameters.stopWatch.split("Extract Tables");

            // 后续表格加工，让表格更加好用，并且移除可能无意义的表格

            // 1. 分类检出无效的表格
            // 开启区域解析时不进行表格分类
            ExtractParameters.PageAreaMap hintAreas = parameters.hintAreas;
            boolean enableHintAreaParse = !hintAreas.isEmpty();

            // 默认暂时不开启
            if (parameters.useVectorRuleFilterTable) {
                logger.info("filter tables via vector rule...");
                TableDebugUtils.writeTables(tablePage, tables, "before_vector_filter_tables" );
                tables = PostProcessTableRegionsAlgorithm.postDetectTableRegions((ContentGroupPage)tablePage, tables);
                TableDebugUtils.writeTables(tablePage, tables, "after_vector_filter_tables" );
            }

            // 调用grpc进行位图表格分类，将无效表格进行过滤
            if (parameters.useTableClassify && !enableHintAreaParse) {
                logger.info("Classify tables via bitmap-classification...");
                TableClassify.classifyTablesOneByOne(tablePage, tables);
                tables = tables.stream().filter(table -> table.getClassifyScore()>0.8).collect(Collectors.toList());
                /*for (Table table : tables) {
                    // 有线表格以及表格结构较为完整的不走位图分类逻辑
                    if (table.getTableType() == TableType.LineTable || table.isMostPossibleTable()) {
                        table.updateConfidence(Table.HIGH_CONFIDENCE_THRESHOLD, 1.0);
                        continue;
                    }

                    //位图分类结果为非表格的则直接将置信度降为0，并且在算法层面不进行过滤
                    BitmapPageExtractionAlgorithm.classifyTableByBitmap(table);
                }*/
            }
            parameters.stopWatch.split("Classify Tables");

            // 2. 修正页面无旋转，但文字有旋转的表格
            if (tables != null && tablePage.getTextRotate() != 0) {
                int rotate = tablePage.getTextRotate();
                // 根据文字状况旋转表格
                for (int i = 0; i < tables.size(); i++) {
                    Table oldTable = tables.get(i);
                    Table newTable = TableUtils.rotate(oldTable, rotate);
                    tables.set(i, newTable);
                }
            }
            parameters.stopWatch.split("Rotate Tables");

            // 3. 根据内容排除常见无效表格

            // 开启区域解析时不进行表格过滤
            if (!enableHintAreaParse) {
                TableUtils.trimFalsePositiveTables(tables);
            }
            parameters.stopWatch.split("Trim");

            if (tables != null && !tables.isEmpty()) {
                TableDebugUtils.writeTables(tablePage, tables.stream().filter(table -> table.getClassifyScore() > 0.8)
                        .collect(Collectors.toList()), "tables" );
                TableDebugUtils.writeTables(tablePage, tables.stream().filter(table -> table.getClassifyScore() <= 0.8)
                        .collect(Collectors.toList()), "位图分类丢弃的tables" );
            }

            if (tables != null && !tables.isEmpty()) {
                organizeTables(tablePage, tables);
                context.addTables(tables);
                parameters.stopWatch.split("Organize");

                // 4. 合并跨页表格
                if (!parameters.useCrossPageTableMerge) {
                    result.addTables(tables);
                    notifyTableExtracted(tables, callback);
                } else {
                    mergeCrossPageTables(tables, result, tablePage, prevPage, callback);
                    parameters.stopWatch.split("Merge");
                }
            } else {
                // 这一页没有表格，可以结束之前的合并了
                Table pendingTable = result.getPendingTable();
                if (pendingTable != null) {
                    finishPendingTable(pendingTable, result, callback);
                }
            }
        } catch (TimeoutException e) {
            logger.error("Timeout handling page #" + pageNumber, e);
            cachePage(pdPage, null, context);
        } catch (Exception e) {
            result.recordError(e);
            logger.error("Error handling page #" + pageNumber, e);
            if (callback != null) {
                callback.onExtractionError(e);
            }
        } finally {
            if (tablePage != null) {
                TableUtils.releaseCachedImage(tablePage);
                tablePage.clearNonText();
            }
            parameters.stopWatch.stop("Other");
        }
    }

    private void organizeTables(Page tablePage, List<Table> tables) {
        tables.sort(Comparator.comparing(Table::getTop));
        int tableIndex = 0;
        for (Table table : tables) {
            table.setIndex(tableIndex++);
        }
        tablePage.commitTables(tables);
    }

    private void mergeCrossPageTables(List<Table> tables, TableExtractionResult result,
                                      Page currentPage, Page prevPage,
                                      TableCallback callback) {
        CrossPageTableCombine tableCombiner = new CrossPageTableCombine();
        Table pendingTable = result.getPendingTable();
        Table firstTable = tables.get(0);
        boolean hasMoreTables = tables.size() > 1;
        int notifyStart = 1;

        // 处理待合并的表格
        if (pendingTable != null) {
            if (null == prevPage) {
                // 上一页没了，结束合并；此页第一张表可以直接返回
                finishPendingTable(pendingTable, result, callback);
                pendingTable = null;
                notifyStart = 0;
            } else {
                Table prevTable = pendingTable.getTailTable();
                boolean merged = tableCombiner.crossPageTableCombine(firstTable, prevTable, currentPage, prevPage);
                if (!merged) {
                    // 无需合并，结束合并；此页第一张表可以直接返回
                    finishPendingTable(pendingTable, result, callback);
                    pendingTable = null;
                    notifyStart = 0;
                } else if (hasMoreTables) {
                    // 这一页的还有其它表格，可以直接结束合并，不用等下一页了
                    finishPendingTable(pendingTable, result, callback);
                    pendingTable = null;
                }
            }
        } else {
            // 没有待合并的表格，
            // 这一页有更多表格，此页第一张表可以直接返回；否则还是等一等
            notifyStart = hasMoreTables ? 0 : 1;
        }

        if (notifyStart < tables.size() - 1) {
            // 报告最后一张表格以外的表格加
            List<Table> newTables = tables.subList(notifyStart, tables.size() - 1);
            result.addTables(newTables);
            notifyTableExtracted(newTables, callback);
        }

        if (null == pendingTable) {
            // 上一轮合并已经完成
            result.setPendingTable(tables.get(tables.size() - 1));
        }
    }

    private void finishPendingTable(Table pendingTable, TableExtractionResult result, TableCallback callback) {
        // 对齐表格，统一列数
        CrossPageTableCombine.alignTable(pendingTable);
        pendingTable.needCombine = false;
        Table table = pendingTable;
        while (table != null) {
            // 依然输出所有被合并的表格，方便后续正文 HTML 化时检测文字是否和表格区域重叠
            result.addTable(table);
            table = table.getNextTable();
        }
        // 把结果通知回调
        notifyTableExtracted(pendingTable, callback);
    }

    private void notifyTableExtracted(List<Table> tables, TableCallback callback) {
        if (null == callback) {
            return;
        }
        for (Table table : tables) {
            callback.onItemExtracted(table);
        }
    }

    private void notifyTableExtracted(Table table, TableCallback callback) {
        if (callback != null) {
            callback.onItemExtracted(table);
        }
    }

    /**
     * Write extracted tables to output
     *
     * @param tables       extracted tables.
     * @param outputFormat output format of the table.
     * @param out          the output source.
     * @throws IOException if the doc state is invalid or it is encrypted.
     */
    public static void writeTables(List<Table> tables, OutputFormat outputFormat, Appendable out, String path) throws IOException {
        Writer writer = null;
        boolean console = false;
        switch (outputFormat) {
            case CSV:
                writer = new CSVWriter();
                break;
            case CSV_ESCAPE:
                writer = new CSVWriter(CSVWriter.DEFAULT_CSV_FORMAT.withQuoteMode(QuoteMode.NONE));
                console = true;
                break;
            case GSON:
            case JSON:
                writer = new JSONWriter();
                break;
            case TSV:
                writer = new TSVWriter();
                break;
            case HTML:
                writer = new HTMLWriter();
                break;
            case GSON_TEST:
                writer = new TestExpectedWriter();
                break;
        }
        int page = 0;
        writer.writeFileStart(out, path);
        for (Table table : tables) {
            table.tableId = String.format("table_%d_%d", table.getPageNumber() - 1, table.getIndex());
            if (table.getPrevTable() != null) {
                // 被合并的表格不输出
                continue;
            }
            if (page != table.getPageNumber()) {
                writer.writePageEnd(out, page);
                page = table.getPageNumber();
                writer.writePageStart(out, page);
            }
            writer.writeTableStart(out, table);

            // Write title and meta
            String title = table.getTitle();
            String unitText = table.getUnitTextLine();
            if (console) {
                if (!StringUtils.isEmpty(title)) {
                    out.append("《").append(table.getTitle()).append("》");
                } else {
                    out.append("《...》");
                }
                if (!StringUtils.isEmpty(unitText)) {
                    out.append("<").append(table.getUnitTextLine()).append(">");
                } else {
                    out.append("<...>");
                }
                out.append(" - ")
                        .append(table.getAlgorithmName())
                        .append(" - ")
                        .append(String.valueOf(table.getRowCount()))
                        .append(" x ")
                        .append(String.valueOf(table.getColumnCount()))
                        .append(" - ")
                        .append(String.valueOf(table.getCellCount()))
                        .append(ExtractorUtil.LINE_SEPARATOR);
                out.append("↧↧↘")
                        .append(StringUtils.replace(table.getCaps(), ExtractorUtil.LINE_SEPARATOR, "\\n"))
                        .append("↙↧↧")
                        .append(ExtractorUtil.LINE_SEPARATOR);
            } else {
                writer.writeTableTitle(out, title);
                if (table.hasCaps()) {
                    writer.writeTableCaps(out, table.getCaps());
                }
                writer.writeTableUnitText(out, unitText);
            }
            // Write body
            writer.writeTable(out, table);
            if (console) {
                out.append("↥↥↗")
                        .append(StringUtils.replace(table.getShoes(), ExtractorUtil.LINE_SEPARATOR, "\\n"))
                        .append("↖↥↥")
                        .append(ExtractorUtil.LINE_SEPARATOR);
            } else {
                if (table.hasShoes()) {
                    writer.writeTableShoes(out, table.getShoes());
                }
            }
            writer.writeTableEnd(out, table);
        }
        writer.writeFileEnd(out, path);
    }

}
