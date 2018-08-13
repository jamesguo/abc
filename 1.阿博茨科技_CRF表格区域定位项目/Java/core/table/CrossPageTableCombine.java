package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * 跨页表格合并策略。
 *
 * Created by qywu on 2018/3/2.
 */

public class CrossPageTableCombine {

    private static final Logger logger = LogManager.getLogger(TableExtractor.class);
    // 前表下方和当前表上方存在文字；
    private boolean existText = false;

    private static final Pattern PAGE_FOOTER_RE = Pattern.compile("(^[0-9]{1,4}$)|(^第[0-9]{1,4}页)|([0-9]{1,4}/[0-9]{1,4}$)|([i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ]{1,2})|(^(-|–|—)\\s*[0-9]{1,4}\\s*(-|–|—)$)|(^1-1-[0-9]{1,4}$)|(^\\d{1,4}((-|–|—)\\s*[0-9]{1,4}){1,}$)");//注意(-|–|—)编码有区别
    private static final Pattern COMMENTS_TEXT = Pattern.compile("注[:：]");
    private static final Pattern CONTINUED_TITLE = Pattern.compile("^\\S+[(（]?(?!手持延后接继永)续[表)）]*$");
    private static final Pattern TABLE_TITLE = Pattern.compile("^\\S+表$");
    private static final Pattern NUMBER_RE = Pattern.compile("(^[-+]?\\d+(,\\d{3})*(\\.\\d+)?%?$)|(^\\s+$)");
    private static final Pattern TABLE_UNIT_STATEMENT_RE = Pattern.compile("单位[：:]");
    private static final String CHINESE_PUNCTUATIONS = "[^,，。?？;；、:：“”\"(（)）\\[\\]【】{}!！<>《》]";
    private static final Pattern INDEXES = Pattern.compile("^(\\d|零|一|二|三|四|五|六|七|八|九|十|i|ii|iii|iv|v|vi|vii|viii|ix|x|I|II|III|IV|V|VI|VII|VIII|IX|X|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ|Ⅹ|(\\d{1,3}(\\.\\d{1,3})+))$");
    private static final Pattern NUMERIC_SECTION_PREFIX_RE = Pattern.compile("^((\\s*[(（]?[\\dA-Za-z〇一二三四五六七八九十]{1,3}[)）]?\\s*[．.、\\s]?(?!\\d))|(\\d{1,3}(\\.\\d{1,3})+)|([(（]\\d{1,3}[)）])(?!\\d))");
    private static final Pattern SPECIAL_NO_CELL_MERGE = Pattern.compile("^((序号)|(议案))$");

    /**
     * 跨页表格合并表格解析时的入口
     *
     * @param nowPage 当前页
     * @param nowTable  当前表
     * @param prevPage  上一页
     * @param prevTable 上一表
     *
     */
    public boolean crossPageTableCombine(Table nowTable, Table prevTable, Page nowPage, Page prevPage){

        // TODO:当前算法暂时跳过无线表格的合并
        if (nowTable.getTableType() == TableType.NoLineTable) {
            return false;
        }

        if (canCombine(prevTable, nowTable, prevPage, nowPage)) {
            setCombine(prevTable, nowTable);
            logger.debug("Cross-page table in these pages: {} merged.", nowTable.getPageNumber());
            return true;
        }
        return false;
    }

//    /**
//     * TODO: 对外入口
//     *
//     * @param PDFTables pdf内解析出来的所有tables。
//     * @param pageContexts pdf内的页面结构，页面结构没有确定，根据自己的需要修改。
//     *
//     */
//    public void crossPageTableCombine(List<Table> PDFTables, List<Object> pageContexts) {
//        sortPageTableByY(PDFTables);
//
//        if (PDFTables.size() <= 1) {
//            return;
//        }
//
//        for(int i = 1; i < PDFTables.size(); i++) {
//            Table prevTable = PDFTables.get(i - 1);
//            Table nowTable = PDFTables.get(i);
//            Object prevPage = pageContexts.get(prevTable.getPageNumber());
//            Object nowPage = pageContexts.get(nowTable.getPageNumber());
//        }
//    }

    private void setCombine(Table prevTable, Table nextTable) {
        logger.debug("Page: {} - {}, cross-page table merged, type: {}.",
                prevTable.getPageNumber(), nextTable.getPageNumber(), nextTable.getTableType());
        prevTable.setNeedCombine();
        nextTable.setNeedCombine();
        prevTable.setNextTable(nextTable);
        nextTable.setPrevTable(prevTable);
    }



    private boolean canCombine(Table prevTable, Table nowTable, Page prevPage, Page nowPage) {
        // 英文页面暂不处理
        if (!isChinesePage(nowPage)) {
            return false;
        }

        // 前后页面方向不同不合并
        if (prevPage.getHeight() != nowPage.getHeight()) {
            return false;
        }

        // 同一页内的表格不合并
        if (isSamePageTable(prevTable, nowTable)) {
            return false;
        }

        // 前一页没有表格不合并
        if (nowTable.getPageNumber() - prevTable.getPageNumber() != 1) {
            return false;
        }

        // 上一表格和上一页需要对应
        if (prevTable.getPageNumber() != prevPage.getPageNumber()) {
            return false;
        }

        // 页面非第一张table不和前面的table合并
        if (nowTable.getIndex() != 0) {
            return false;
        }

        // 不同类型的表格不合并
        if (nowTable.getTableType() != prevTable.getTableType()) {
            return false;
        }

        // 续表不合并
        if (CONTINUED_TITLE.matcher(nowTable.getTitle()).find()) {
            return false;
        }

        // 续表表头可能藏在表格里
        if (new HashSet<>(nowTable.getRow(0)).size() == 1 && (TABLE_TITLE.matcher(getRowText(nowTable.getRow(0))).find()
                || CONTINUED_TITLE.matcher(getRowText(nowTable.getRow(0))).find())) {
            return false;
        }

        Rectangle prevPageVaildRegion = prevPage.getTextBounds();
        Rectangle nowPageVaildRegion = nowPage.getTextBounds();

        // 表格的上下边界应该在page的合理位置，存在页眉页脚影响，适当放宽范围
        if (((nowPageVaildRegion.getHeight() * 0.4f +  nowPageVaildRegion.getTop()) < nowTable.getTop() &&
                nowPageVaildRegion.getHeight() > 100) ||
                prevPageVaildRegion.getBottom() * 0.8f > prevTable.getBottom() ||
                prevPage.getBottom() * 0.5f > prevTable.getBottom()) {
            return false;
        }

        judgeText(prevTable, nowTable, prevPage, nowPage, prevPageVaildRegion, nowPageVaildRegion);

        // 表格上下存在正文
        if (this.existText) {
            return false;
        }

        if (analyseTableCombine(prevTable, nowTable) && !this.existText) {
            return true;
        }

        return false;
    }

    private boolean isChinesePage (Page page) {
        for (TextElement text : page.getText()) {
            if (TableTextUtils.hasChinese(text.getText())) {
                return true;
            }
        }

        return false;
    }

    private boolean isSamePageTable(Table prevTable, Table nextTable) {
        return prevTable.getPageNumber() == nextTable.getPageNumber();
    }

    private void judgeText(Table prevTable, Table nowTable, Page prevPage, Page nowPage,
                           Rectangle prevPageVaildRegion, Rectangle nowPageVaildRegion) {
        Rectangle prevPageTextSearchRect = new Rectangle(prevPageVaildRegion.getLeft(), prevTable.getBottom() + 1,
                prevPageVaildRegion.getWidth(), prevPageVaildRegion.getBottom() - prevTable.getBottom());
        Rectangle nowPageTextSearchRect = new Rectangle(nowPageVaildRegion.getLeft(), nowPageVaildRegion.getTop(),
                nowPageVaildRegion.getWidth(), nowTable.getTop() - nowPageVaildRegion.getTop() - 1);

        List<TextBlock> nowTextLine = TextMerger.collectByLines(nowPage.getTextChunks(nowPageTextSearchRect));
        //List<TextBlock> nowTextLine = TableTitle.findTextBlocks(nowPage, nowPageTextSearchRect);

        //剔除了页眉后部分表格可能top在实际页面top之上
        if (nowTable.getTop() < nowPageVaildRegion.getTop() && !nowTextLine.isEmpty()) {
            nowTextLine.clear();
        }

        // 过滤当前页页眉，排除单位行
        if (!nowTextLine.isEmpty()) {
            if (TABLE_UNIT_STATEMENT_RE.matcher(nowTextLine.get(0).getText()).find() &&
                    !NUMERIC_SECTION_PREFIX_RE.matcher(nowTextLine.get(0).getText()).find()) {
                nowTextLine.remove(0);
            }

            if (!nowTextLine.isEmpty()) {
                filterRectText(nowPageTextSearchRect, prevPage, nowTextLine);
                if (this.existText) {
                    return;
                }
            }
        }

        // 首先排除有标记的页眉
        if (!nowTextLine.isEmpty() && !nowTextLine.get(nowTextLine.size() - 1).isPageHeader()) {
            this.existText = true;
        } else {
            List<TextBlock> prevTextLine = TextMerger.collectByLines(prevPage.getTextChunks(prevPageTextSearchRect));
            // List<TextBlock> prevTextLine = TableTitle.findTextBlocks(prevPage, prevPageTextSearchRect);

            if (!prevTextLine.isEmpty()) {
                // 过滤上一页页脚
                TextBlock tbTemp = prevTextLine.get(prevTextLine.size() - 1);

                if (PAGE_FOOTER_RE.matcher(tbTemp.getText().trim()).find() || COMMENTS_TEXT.matcher(tbTemp.getText().trim()).find()) {
                    prevTextLine.remove(tbTemp);
                }

                // 上一页页脚判断下一页的页脚是否相同
                filterRectText(prevPageTextSearchRect, nowPage, prevTextLine);
            }
        }
    }

    // 过滤掉范围内同上页相同去也内相同的文字，并且判断是否有正文
    private void filterRectText(Rectangle rect, Page prevPage, List<TextBlock> textLine) {
        List<TextBlock> prevPageLines = TextMerger.collectByLines(prevPage.getTextChunks(rect));
        // 当前页第一行不是页眉页脚
        if (null == prevPageLines || prevPageLines.isEmpty()) {
            this.existText = true;
            return;
        }

        String prevPageText = prevPageLines.stream().map(TextBlock :: getText).reduce("", (x, y) -> x + y);
        String nowPageText = textLine.stream().map(TextBlock :: getText).reduce("", (x, y) -> x + y);

        // 页号的变化可能导致页眉页脚无法被剔除
        if (editDistance(nowPageText, prevPageText) >= 0.97) {
            textLine.clear();
        } else if (textLine.stream().allMatch(TextBlock::isPagination)) {
            textLine.clear();
        } else {
            this.existText = true;
        }
    }

    // 编辑距离算法
    private float editDistance(String A, String B) {
        int editdistance;

        if (A.equals(B)) {
            editdistance = 0;
        } else {
            //dp[i][j]表示源串A位置i到目标串B位置j处最低需要操作的次数
            int[][] dp = new int[A.length() + 1][B.length() + 1];
            for (int i = 0; i <= A.length(); i++) {
                dp[i][0] = i;
            }

            for (int j = 0; j <= B.length(); j++) {
                dp[0][j] = j;
            }

            for (int i = 1; i <= A.length(); i++) {
                for (int j = 1; j <= B.length(); j++) {
                    if (A.charAt(i - 1) == B.charAt(j - 1))
                        dp[i][j] = dp[i - 1][j - 1];
                    else {
                        dp[i][j] = Math.min(dp[i - 1][j] + 1, Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + 1));
                    }
                }
            }
            editdistance = dp[A.length()][B.length()];
        }

        return 1.0f - (editdistance / (1.0f + A.length() + B.length()));
    }

    private boolean analyseTableCombine(Table prevTable, Table nowTable) {
        float combineScore = 1.0f;
        int prevColumnCount = prevTable.getColumnCount();
        int nowColumnCount = nowTable.getColumnCount();

        // 单行表格
        if (singleLine(prevTable, nowTable)) {
            combineScore *= 0.3f;
        }

        // 列数相同
        if (sameColumnCount(prevColumnCount, nowColumnCount)) {
            combineScore *= 0.6f;
        }

        // 表格宽度相同
        if (tableWidth(prevTable, nowTable)) {
            combineScore *= 0.6f;
        }

        // 判断当前表首行和上页表尾行
        if (lineStructure(prevTable, nowTable)) {
            combineScore *= 0.6f;
        }

        // 有相同的表头
        if (sameTableHead(prevTable, nowTable, prevColumnCount, nowColumnCount)) {
            combineScore *= 0.8f;
        }

        // 当前表是否存在表头
        if (existTableHead(nowTable, nowColumnCount)) {
            combineScore *= 0.5f;
        }

        // 不存在正文
        if (!this.existText) {
            combineScore *= 0.5;
        }

        return 1 - combineScore >= 0.7f;
    }

    private boolean singleLine(Table prevTable, Table nowTable) {
        return prevTable.getRowCount() == 1 || nowTable.getRowCount() == 1;
    }

    private boolean sameColumnCount(int prevCount, int nowCount) {
        return prevCount == nowCount;
    }

    private boolean tableWidth(Table prevTable, Table nowTable) {
        // 宽度保证在不能差到两个表格平均值的二十分之一
        return FloatUtils.feq(prevTable.getWidth(), nowTable.getWidth(), (nowTable.getWidth() + prevTable.getWidth()) * 0.5 * 0.05);
    }

    private static String getRowText(List<Cell> row) {
        String preCellText = "";
        StringBuilder sb = new StringBuilder();
        for (Cell cell : row) {
            if (!preCellText.equals(cell.getText())) {
                sb.append(cell.getText());
                preCellText = cell.getText();
            }
        }
        return sb.toString();
    }

    // 判断有无表头
    private boolean sameTableHead(Table prevTable, Table nowTable, int prevCount, int nowCount) {
        String prevHeader = "";
        String nowHeader = "";
        for (int i = 0; i < Math.min(nowTable.getRowCount(), prevTable.getRowCount()); i++) {
            if (prevTable.getRow(i).size() == prevCount || nowTable.getRow(i).size() == nowCount) {
                if (i == 0) {
                    prevHeader = getRowText(prevTable.getRow(0));
                    nowHeader = getRowText(nowTable.getRow(0));
                }
                break;
            } else {
                prevHeader += getRowText(prevTable.getRow(i));
                nowHeader += getRowText(nowTable.getRow(i));
            }
        }

        return editDistance(prevHeader, nowHeader) > 0.95;
    }

    // 判断两行内的存在数字的列是否一样
    private boolean lineCellStructure(List<Cell> prevline, List<Cell> nowline) {
        for (int i = 0; i < prevline.size(); i++) {
            Cell prevCell = prevline.get(i);
            Cell nowCell = nowline.get(i);
            boolean prevIsDigit = NUMBER_RE.matcher(prevCell.getText(true).trim()).find()
                    || prevCell.getText(true).trim().equals("");
            boolean nowIsDigit = NUMBER_RE.matcher(nowCell.getText(true).trim()).find()
                    || prevCell.getText(true).trim().equals("");
            if ((prevIsDigit && !nowIsDigit) || (!prevIsDigit && nowIsDigit)) {
                return false;
            }
        }
        return true;
    }

    // 判断当前表第一行和上一表最后一行
    private boolean lineStructure(Table prevTable, Table nowTable) {
        List<Cell> prevTableLastLine = prevTable.getRow(prevTable.getRowCount() - 1);
        List<Cell> nowTableFirstLine = nowTable.getRow(0);
        if (prevTableLastLine.size() == nowTableFirstLine.size()) {
            return lineCellStructure(prevTableLastLine, nowTableFirstLine);
        } else {
            return !FloatUtils.feq(nowTableFirstLine.get(0).getLeft(), nowTable.getLeft(), 10.0f) ;
        }
    }

    // 是否存在表头
    private boolean existTableHead(Table nowTable, int nowCount) {
        List<Cell> firstLine = nowTable.getRow(0);
        List<Cell> secondLine = nowTable.getRow(1);
        return nowTable.getRow(0).size() == nowCount && firstLine.size() == secondLine.size() && lineCellStructure(firstLine, secondLine);
    }

    // 将多个表格对齐
    public static Table alignTable(Table table) {
        Table nextTable = table.getNextTable();
        while (nextTable != null) {
            if (table.getTableType() == TableType.LineTable) {
                rulingTableCombine(table, nextTable);
                table.setEndPageNumber(table.getEndPageNumber() + 1);
            } else {
                noRulingTableCombine(table, nextTable);
            }
            nextTable.markDeleted();
            nextTable = nextTable.getNextTable();
        }
        return table;
    }

    // 无线表格需要开发
    private static void noRulingTableCombine(Table nowTable, Table nextTable) {
        return;
    }

    // 有线表格单元格对齐策略
    private static void rulingTableCombine(Table nowTable, Table nextTable) {
        // addTableStartRow为从otherTable从哪一行开始add进thisTable中
        int addTableStartRow = 0;
        addTableStartRow = removeRepeatHead(nowTable, nextTable, addTableStartRow);
        List<Cell> firstRow = nextTable.getRow(addTableStartRow);
        List<Cell> lastRow = nowTable.getRow(nowTable.getRowCount() - 1);
        // 行对齐的情况
        if (nowTable.getColumnCount() == nextTable.getColumnCount()) {

            if (splitedCellMerge(lastRow, firstRow)) {
                logger.debug("Splited Cell merge, Page: {}", nextTable.getPageNumber());
                cellMerge(lastRow, firstRow);
                addTableStartRow++;
            }

            nowTable.addTable(nextTable, addTableStartRow);
            nowTable.updateCells();
        } else {
            Set<Cell> firstRowCell = new HashSet<>(firstRow);
            Set<Cell> lastRowCell = new HashSet<>(lastRow);
            if (firstRowCell.size() == lastRowCell.size()) {
                List<Cell> cleanFirstRow = new ArrayList<>(firstRowCell);
                List<Cell> cleanLastRow = new ArrayList<>(lastRowCell);
                cleanFirstRow.sort(Comparator.comparing(cell -> cell.getCol()));
                cleanLastRow.sort(Comparator.comparing(cell -> cell.getCol()));

                if (splitedCellMerge(cleanLastRow, cleanFirstRow)) {
                    logger.debug("Splited Cell merge, Page: {}", nextTable.getPageNumber());
                    cellMerge(lastRow, firstRow);
                    addTableStartRow++;
                }
            }

            nowTable.addTable(nextTable, addTableStartRow);
            Set<Float> vRuling = new HashSet<>();

            List<Cell> cells = nowTable.getCells();

            for (Cell cell : cells) {
                vRuling.add(cell.getLeft());
            }
            vRuling.add(nowTable.getRight());

            List<Float> columnBounds = new ArrayList<>(vRuling);
            Collections.sort(columnBounds);

            List<Rectangle> columns = new ArrayList<>();
            for (int i = 0; i < columnBounds.size() - 1; i++) {
                if (!FloatUtils.feq(columnBounds.get(i+1), columnBounds.get(i), 5)) {
                    columns.add(new Rectangle(columnBounds.get(i), nowTable.getTop(), columnBounds.get(i+1) - columnBounds.get(i), nowTable.getHeight()));
                }
            }

            Table.CellMatrix matrix = new Table.CellMatrix(nowTable.getRowCount(), columns.size());
            List<Cell> processedCell = new ArrayList<>();

            for (Cell pivotCell : cells) {
                if (pivotCell.isDirty()) {
                    if (processedCell.contains(pivotCell)) {
                        continue;
                    }

                    matrix.nextCol();
                    continue;

                }

                if (pivotCell.getRow() != matrix.getRow()) {
                    matrix.nextRow();
                }

                int colSpan = 0;
                for (Rectangle column : columns) {
                    if (pivotCell.horizontalOverlap(column) > 2.f) {
                        colSpan++;
                    } else {
                        if (column.getRight() <= pivotCell.getLeft() + 2.f) {
                            // 增加游标
                            continue;
                        }
                        break;
                    }
                }
                pivotCell.setCol(matrix.getCol());
                pivotCell.setColSpan(colSpan);
                processedCell.add(pivotCell);
                matrix.putCell(pivotCell.getRowSpan(), pivotCell.getColSpan());
            }
            nowTable.updateCells();
        }
    }

    // 移除重复的表头
    private static int removeRepeatHead(Table nowTable, Table nextTable, int rowIdx) {
        for(int i = rowIdx; i < Math.min(nowTable.getRowCount(), nextTable.getRowCount()); i++) {
            String nowHead = getRowText(nowTable.getRow(i));
            String nextHead = getRowText(nextTable.getRow(i));
            if (nowHead.equals(nextHead) && !nowHead.equals("")) {
                // 可能遇到跨行表头的情况
                rowIdx++;
                continue;
            }
            break;
        }

        return rowIdx;
    }

    // 切割单元格合并初步策略
    // 判断单元格位置
    private static boolean splitedCellMerge(List<Cell> lastRow, List<Cell> firstRow) {
        // 单元格合并必须是前后行列数相等，排除跨列单元格的影响
        if (lastRow.size() != firstRow.size()) {
            return false;
        }

        int textCellCount = firstRow.size() - Collections.frequency(firstRow.stream().map(Cell::getText).collect(Collectors.toList()), "");

        for (int i = 0; i < lastRow.size(); i++) {
            Cell fistRowCell = firstRow.get(i);
            Cell lastRowCell = lastRow.get(i);
            String firstRowText = fistRowCell.getText();
            String lastRowText = lastRowCell.getText();
            if (i == 0 && NUMERIC_SECTION_PREFIX_RE.matcher(firstRowText).find() &&
                    NUMERIC_SECTION_PREFIX_RE.matcher(lastRowText).find()) {
                return false;
            }

            if (i == 0 && (INDEXES.matcher(firstRowText).find() || SPECIAL_NO_CELL_MERGE.matcher(lastRowText).find())) {
                return false;
            }

            // 两行都是数字不合并
            if (NUMBER_RE.matcher(lastRowText).find() && NUMBER_RE.matcher(firstRowText).find()) {
                return false;
            }

            // 必须是第一行有文字，上一行对应的区域也必须有文字
            if (!firstRowText.equals("") && lastRowText.equals("")) {
                return false;
            }

            if (firstRowText.equals(lastRowText) && !firstRowText.equals("")) {
                return false;
            }

        }

        // TODO:单元格合并这种特征还有问题
        if (textCellCount < firstRow.size() / 2.0f) {
            // 序号类文本可能只占一行
            if (!lastRow.get(0).getText().equals("") && NUMERIC_SECTION_PREFIX_RE.matcher(firstRow.get(0).getText()).find()) {
                return false;
            }
            // 下一行只有第一列有文字，这些文字在有标点的情况下可能是被切分的单元格
            if (!firstRow.get(0).getText().equals("")) {
                if (firstRow.get(0).getText().length() == 1) {
                    return true;
                }

                return punctuationFeature(lastRow, firstRow, textCellCount);
            }

            return true;
        } else {
            if (lastRow.size() == 2 &&
                    firstRow.size() == lastRow.size() &&
                    firstRow.get(0).getText().equals("") &&
                    NUMERIC_SECTION_PREFIX_RE.matcher(firstRow.get(1).getText()).find() &&
                    NUMERIC_SECTION_PREFIX_RE.matcher(lastRow.get(1).getText()).find()) {
                return true;
            }

            // 标点符号的特征
            return punctuationFeature(lastRow, firstRow, textCellCount);
        }
    }

    private static boolean punctuationFeature(List<Cell> lastRow, List<Cell> firstRow, int textCellCount) {
        String halfPunctuations1 = "<《“【[(（";
        String halfPunctuations2 = ">》”】])）";
        String endPunctuations = "。？?！!";
        String linkPunctuations = "，,、/";
        String explainPunctuations = ":：";

        for (int i = 0; i < lastRow.size(); i++) {
            Cell fistRowCell = firstRow.get(i);
            Cell lastRowCell = lastRow.get(i);
            String firstRowText = fistRowCell.getText();
            String lastRowText = lastRowCell.getText();
            // 过滤出所有标点
            String firstRowPunctuations = firstRowText.replaceAll(CHINESE_PUNCTUATIONS, "");
            String lastRowPunctuations = lastRowText.replaceAll(CHINESE_PUNCTUATIONS, "");
            // 获取最后一行最后一个标点符号和第一行第一个标点符号
            String firstRowPunctuation = firstRowPunctuations.equals("") ? "" : String.valueOf(firstRowPunctuations.charAt(0));
            String lastRowPunctuation = lastRowPunctuations.equals("") ? "" : String.valueOf(lastRowPunctuations.charAt(lastRowPunctuations.length() - 1));
            // 获取最后一行最后一个标点后的文字和第一行第一个标点前的文字
            String firstRowFirstSeg;
            String lastRowLastSeg;

            if (firstRowText.equals("")) {
                firstRowFirstSeg = "";
            } else {
                if (firstRowPunctuation.equals("")) {
                    firstRowFirstSeg = firstRowText;
                } else {
                    firstRowFirstSeg = firstRowText.substring(0, firstRowText.indexOf(firstRowPunctuation));
                }
            }

            if (lastRowText.equals("")) {
                lastRowLastSeg = "";
            } else {
                if (lastRowPunctuation.equals("")) {
                    lastRowLastSeg = lastRowText;
                } else {
                    lastRowLastSeg = lastRowText.substring(lastRowText.lastIndexOf(lastRowPunctuation) + 1);
                }
            }

            boolean lastNoPunctuation = lastRowPunctuation.equals("");
            boolean firstNoPunctuation = firstRowPunctuation.equals("");
            boolean lastNoSeg = lastRowLastSeg.equals("");
            boolean firstNoSeg = firstRowFirstSeg.equals("");

            if (!lastNoPunctuation) {
                // 前面没标点暂时不判断
                // 前面有标点没文本，以标点结束
                if (lastNoSeg) {
                    if (halfPunctuations1.contains(lastRowPunctuation) || linkPunctuations.contains(lastRowPunctuation)) {
                        return true;
                    }
                } else {
                    // 前面有标点有文本
                    if (lastRowLastSeg.length() == 1 && !INDEXES.matcher(lastRowLastSeg).find()
                            && !explainPunctuations.contains(lastRowPunctuation)) {
                        return true;
                    }
                }
            }

            if (!firstNoPunctuation) {
                // 后面没标点暂时不判断
                // 后面有标点没文本，以标点开头
                if (firstNoSeg) {
                    if (halfPunctuations1.contains(lastRowPunctuation) || endPunctuations.contains(lastRowPunctuation)) {
                        return true;
                    }
                } else {
                    // 后面有标点有文本
                    if ((firstRowFirstSeg.length() == 1
                            && !INDEXES.matcher(firstRowFirstSeg).find()
                            && !explainPunctuations.contains(firstRowPunctuation))
                            || halfPunctuations2.contains(firstRowPunctuation)) {
                        return true;
                    }
                }
            }

            if (i == 0 && firstRow.get(0).getRowSpan() != 1 && !firstRow.get(0).getText().equals("")) {
                return false;
            }

        }

        if (TensorflowManager.INSTANCE.isModelAvailable(TensorflowManager.CELL_MERGE)) {
            return semanticFeature(lastRow, firstRow, textCellCount);
        }

        return false;
    }

    private static void cellMerge(List<Cell> lastRow, List<Cell> firstRow) {
        if (lastRow.size() != firstRow.size()) {
            filterRepeatCell(lastRow);
            filterRepeatCell(firstRow);
        }

        for (int i = 0; i < lastRow.size(); i++) {
            Cell firstRowCell = firstRow.get(i);
            Cell lastRowCell = lastRow.get(i);
            lastRowCell.setCrossPageCell();
            String firstRowText = firstRowCell.getText();
            String lastRowText = lastRowCell.getText();
            lastRow.get(i).setText(lastRowText + firstRowText);
        }
    }

    private static boolean semanticFeature(List<Cell> lastRow, List<Cell> firstRow, int textCellCount) {
        ArrayList<Pair<String, String>> cells = new ArrayList<>();
        for (int i = 0; i < lastRow.size(); i++) {
            Cell fistRowCell = firstRow.get(i);
            Cell lastRowCell = lastRow.get(i);
            String firstRowText = fistRowCell.getText();
            String lastRowText = lastRowCell.getText();

            Pair<String, String> cell = Pair.of(lastRowText, firstRowText);

            if (firstRowText.equals(lastRowText) && firstRowText.equals("")) {
                continue;
            }

            cells.add(cell);
        }

        List<Pair<Float, Long>> result = CellMergeNLP.cellMergePredict(cells);
        int semanticCount = 0;

        for (Pair<Float, Long> r : result) {
            if (r.getLeft() > 0.75f && r.getRight() > 0) {
                semanticCount++;
            }
        }

        if (semanticCount > textCellCount * 0.6f) {
            return true;
        }

        return false;
    }

    private static void filterRepeatCell(List<Cell> row) {
        Set<Cell> rowCell = new HashSet<>(row);
        row.clear();
        row.addAll(rowCell);
        row.sort(Comparator.comparing(cell -> cell.getCol()));
    }
}
