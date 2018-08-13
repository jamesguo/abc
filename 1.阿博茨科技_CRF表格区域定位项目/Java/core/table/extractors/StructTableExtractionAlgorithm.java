package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.TextUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public enum  StructTableExtractionAlgorithm implements ExtractionAlgorithm {

    INSTANCE;

    @Override
    public String getVersion() {
        return "Struct-v20180631";
    }

    @Override
    public List<? extends Table> extract(Page page) {
        if (!(page instanceof ContentGroupPage)) {
            return Lists.newArrayList();
        }
        ContentGroupPage contentGroupPage = (ContentGroupPage) page;
        return findStructureTables(contentGroupPage.getContentGroup());
    }

    private static final Logger logger = LogManager.getLogger();

    private static final Set<String> TABLE_TYPES = Sets.newHashSet("Table");
    private static final Set<String> TR_TYPES = Sets.newHashSet("TR", "Tr");
    private static final Set<String> TD_TYPES = Sets.newHashSet("TH", "TD", "Th", "Td");
    private static final Set<String> NON_TABLE_TYPES = Sets.newHashSet("InlineShape", "L", "Figure");
    private static final Set<String> SKIP_TABLE_TAGS = Sets.newHashSet("[Table_Title]");

    static List<Table> findStructureTables(ContentGroup contentGroup) {
        TextTree tree = contentGroup.getTextTree();
        if (tree == null) {
            return new ArrayList<>();
        }
        List<Table> structureTables = new ArrayList<>(findStructureTablesWithTab(tree));
        List<TextNode> tableNodes = contentGroup.getTextTree().deepFindByStructureTypes(TABLE_TYPES);
        for (TextNode tableNode : tableNodes) {
            // 有些Chart会按照Table来排版, 可能误检, 这里根据Chart的类型来过滤
            if (!tableNode.findByStructureTypes(NON_TABLE_TYPES).isEmpty()) {
                logger.debug("Find non table types in table, skip");
                continue;
            }
            buildTable(tableNode,  structureTables);
        }
        // 有些研报里并排的两个chart, 外层会用table来排版, 检测这种情况, 然后去掉, 如: BMTTables/undone/研报/平安证券-170414.pdf, page 5
        // 检测每行的间距, 如果过大的话, 则认为满足条件
        for (Iterator<Table> iterator = structureTables.iterator(); iterator.hasNext(); ) {
            Table structureTable = iterator.next();
            // 列数超过3, 跳过检测
            if (structureTable.getStructureCells().size() > 3) {
                continue;
            }
            List<List<CandidateCell>> structureCells = structureTable.getStructureCells();
            for (int i = 1; i < structureCells.size(); i++) {
                List<CandidateCell> prevRow = structureCells.get(i-1);
                // 行数超过3, 跳过检测
                if (prevRow.size() > 3) {
                    continue;
                }
                List<CandidateCell> row = structureCells.get(i);
                Rectangle prevRowBounds = Rectangle.union(prevRow);
                Rectangle rowBounds = Rectangle.union(row);
                if (rowBounds.getTop() - prevRowBounds.getBottom() > 5 * (prevRowBounds.getHeight()+rowBounds.getHeight())) {
                    logger.info("Remove layout table");
                    iterator.remove();
                    break;
                }
            }

        }
        return structureTables;
    }

    private static void buildTableIfNoMergedCell(Table table) {
        // 根据结构化信息生成表格, 如果每行的列数一样则采用此算法
        List<List<CandidateCell>> rows = table.getStructureCells();
        // 删除空行
        rows.removeIf(row -> row.stream().allMatch(cell -> StringUtils.isBlank(cell.getText())));
        if (rows.isEmpty()) {
            return;
        }

        long confusedNum = rows.stream().flatMap(Collection::stream).filter(c->c.getCellStatus() == CandidateCell.CellStatus.CONFUSED).count();
        if (confusedNum > Math.min(5, table.getCellCount() * 0.3) ||
                !rows.stream().allMatch(row -> row.stream().allMatch(cell->cell.getCellStatus() == CandidateCell.CellStatus.NORMAL))) {
            // 存在结构化单元格异常情况，走有线或无线表格解析
            table.setNormalStatus(false);
            return;
        }

        int columnCount = rows.get(0).size();
        if (!rows.stream().allMatch(row -> row.size() == columnCount)) {
            // 列数不一致, 走有线/无线表格解析, 删除空白的单元格
            rows.forEach(row -> row.removeIf(cell -> StringUtils.isBlank(cell.getText())));
            return;
        }
        //删除空白列
        List<Integer> removeColIdx = new ArrayList<>();
        for (int col = 0; col < columnCount; col++) {
            boolean blankColFlag = true;
            for (int row = 0; row < rows.size(); row++) {
                if (StringUtils.isNotBlank(rows.get(row).get(col).getText())) {
                    blankColFlag = false;
                    break;
                }
            }
            if (blankColFlag) {
                removeColIdx.add(col);
            }
        }
        //列数相同则直接结构化
        for (int row = 0; row < rows.size(); row++) {
            int actualCol = 0;
            for (int col = 0; col < columnCount; col++) {
                if (removeColIdx.contains(col)) {
                    continue;
                }
                table.add(rows.get(row).get(col).toCell(), row, actualCol);
                actualCol++;
            }
        }
    }

    private static CandidateCell buildCell(TextNode textNode) {
        List<TextChunk> textChunks = textNode.getAllTextChunks();
        if (textChunks.isEmpty() || textChunks.size() > 100) {
            return null;
        }

        // 对node中提取的chunk，按空间位置排序
        NumberUtil.sort(textChunks, (o1, o2) -> {
            if (o1.verticalOverlapRatio(o2) > 0.5) {
                return Double.compare(o1.getLeft(), o2.getLeft());
            }
            return Double.compare(o1.getTop(), o2.getTop());
        });

        TextChunk merged = new TextChunk(textChunks.get(0));
        for (int i = 1; i < textChunks.size(); i++) {
            TextChunk next = textChunks.get(i);
            if (next.isHidden()) {
                continue;
            }
            // 如果是中文, 多行之间去掉空格
            if (!merged.inOneLine(next)
                    && (TextUtils.isEndsWithCJK(merged.getText().trim()) || TextUtils.isStartsWithCJK(next.getText().trim()))) {
                merged.trimEnd();
                next.trimStart();
            }
            if (!next.isEmpty()) {
                merged.merge(next);
            }
        }
        CandidateCell cell = CandidateCell.fromTextChunk(merged);
        cell.setStructType(textNode.getStructureType());

        if (textChunks.size() >= 2) {
            List<TextChunk> NotBlankChunks = textNode.getAllTextChunks().stream()
                    .filter(t->StringUtils.isNotBlank(t.getText()))
                    .collect(Collectors.toList());

            if (NotBlankChunks.size() >= 2) {
                // 结构化TD中含有多个TextChunk时，分析是否存在内容间有较大空白能拆成多列
                // 如果存在，则有可能是把多个实际单元格包含在一个TD中，后续对这类表格通过区域解析
                List<TextChunk> squeezeChunks = TextMerger.squeeze(merged, ' ', 4);
                if (squeezeChunks.size() > 1) {
                    if (NotBlankChunks.size() == 2) {
                        cell.setCellStatus(CandidateCell.CellStatus.CONFUSED);
                    } else {
                        cell.setCellStatus(CandidateCell.CellStatus.ABNORMAL);
                    }
                }

                List<TextChunk> actualChunksByMerge = TextMerger.groupNeighbour(NotBlankChunks);
                List<TextBlock> rows = TextMerger.collectByLines(actualChunksByMerge);
                List<Rectangle> cols = BitmapPageExtractionAlgorithm.calColumnFromLines(Collections.singletonList(actualChunksByMerge));
                // 对结构化TD中含有较多TextChunk时，分析其TD内是否存在多行多列的情况
                // 如果存在，则很大概率是错误的结构化TD，后续对这类表格通过区域采用无线解析算法
                if (cell.getCellStatus() == CandidateCell.CellStatus.NORMAL && NotBlankChunks.size() > 3) {
                    // 存在多行多列
                    if (rows.size() > 1 && cols.size() > 1) {
                        cell.setCellStatus(CandidateCell.CellStatus.ABNORMAL);
                    }
                }
                if (rows.size() > 1 && cols.size() == 1 && actualChunksByMerge.size() >= 2) {
                    // 存在多行单列，判断是否有不可能换行合并的情况，如果有，则判定为错误的结构化TD
                    boolean isNormal = true;
                    for (int i=0; i<actualChunksByMerge.size()-1; i++) {
                        TextChunk currChunk = actualChunksByMerge.get(i);
                        TextChunk nextChunk = actualChunksByMerge.get(i+1);

                        //是否存在不能合并的数值块
                        if (TextMerger.NUM_GROUP_PREFIX.matcher(currChunk.getText()).matches() &&
                                TextMerger.NUM_GROUP_PREFIX.matcher(nextChunk.getText()).matches()) {
                            if (!TextMerger.canMergeNumberGroup(currChunk, nextChunk)) {
                                isNormal = false;
                                break;
                            }
                        }
                    }
                    if (!isNormal) {
                        cell.setCellStatus(CandidateCell.CellStatus.ABNORMAL);
                    }
                }
            }
        }
        return cell;
    }

    private static void buildTable(TextNode tableNode, List<Table> structureTables) {
        Table table = new Table();
        List<List<CandidateCell>> textNodeCells = new ArrayList<>();
        List<TextNode> rows = tableNode.findByStructureTypes(TR_TYPES);
        for (int i = 0; i < rows.size(); i++) {
            TextNode rowNode = rows.get(i);
            if (i == 0) {
                if (rowNode.getAllTextChunks().stream().allMatch(TextChunk::isHiddenOrBlank)) {
                    String tag = rowNode.getText().trim();
                    if (SKIP_TABLE_TAGS.contains(tag)) {
                        return;
                    }
                    table.setTag(tag);
                }
            }
            // 目前发现Tr里面有可能嵌套其他的表, 如果是这样的话, 丢弃这个Tr, 创建一个新的表
            List<TextNode> innerTables = rowNode.findByStructureTypes(TABLE_TYPES);
            if (innerTables.isEmpty()) {
                // 如果所有的文字都不可见, 则一般是隐藏的表头, 跳过
                if (rowNode.getAllTextChunks().stream().allMatch(TextChunk::isHiddenOrBlank)) {
                    continue;
                }
                List<CandidateCell> rowCells = new ArrayList<>();
                for (TextNode cellNode : rowNode.findByStructureTypes(TD_TYPES)) {
                    CandidateCell cell = buildCell(cellNode);
                    if (cell == null) {
                        // 创建一个空cell
                        cell = new CandidateCell(0, 0, 0, 0);
                    }
                    rowCells.add(cell);
                }
                textNodeCells.add(rowCells);
            } else {
                for (TextNode innerTable : innerTables) {
                    buildTable(innerTable, structureTables);
                }
            }
        }
        table.setStructureCells(textNodeCells);
        // 根据文字设置table区域
        Rectangle tableArea = Rectangle.union(textNodeCells.stream().flatMap(Collection::stream).collect(Collectors.toList()));
        if (tableArea != null) {
            table.setRect(tableArea);
        }
        buildTableIfNoMergedCell(table);
        structureTables.add(table);
    }

    public static boolean isTableRow(TextNode node) {
        String type = node.getStructureType();
        if (StringUtils.contains(type, "foot")) {
            return false;
        }
        if (StringUtils.containsAny(type, "Table_", "table", "_tab")) {
            return true;
        } else {
            if (StringUtils.equalsAny(type, "L", "LI")) {
                return false;
            }
            return node.getTextChunks().stream()
                    .filter(textChunk -> StringUtils.contains(textChunk.getActualTextOrText(), "\t"))
                    .count() >= 3;
        }
    }

    private static List<Table> findStructureTablesWithTab(TextNode textTree) {
        List<Table> structureTables = new ArrayList<>();
        List<TextNode> textNodes = textTree.findBy(textNode -> {
            if (textNode.getChildren().isEmpty()) {
                return false;
            }
            long rowCount = textNode.getChildren().stream()
                    .filter(StructTableExtractionAlgorithm::isTableRow)
                    .count();
            return rowCount >= 2;
        });
        for (TextNode tableNode : textNodes) {
            Table table = new Table();
            Rectangle lastRowBounds = null;
            List<TextNode> tableRows = new ArrayList<>();
            boolean beginTableRow = false;
            for (TextNode rowNode : tableNode.getChildren()) {
                String text = rowNode.getText().trim();
                if (isTableRow(rowNode)) {
                    beginTableRow = true;
                } else {
                    if (!beginTableRow) {
                        table.setInlineTitle(text);
                    }
                    // 结束table
                    if (tableRows.size() > 0) {
                        structureTables.add(buildTable(table, tableRows));
                        table = new Table();
                        tableRows = new ArrayList<>();
                    }
                    beginTableRow = false;
                    lastRowBounds = null;
                    continue;
                }
                Rectangle rowBounds = rowNode.getBounds2D();
                // 和上一行进行比较, 如果相距太远则认为是另外一个table了
                if (lastRowBounds != null) {
                    if (!rowBounds.isHorizontallyOverlap(lastRowBounds)
                            || rowBounds.getTop() - lastRowBounds.getBottom() > lastRowBounds.getHeight() * 5) {
                        // 结束上一个table, 开始一个新的
                        if (tableRows.size() > 0) {
                            structureTables.add(buildTable(table, tableRows));
                        }
                        table = new Table();
                        tableRows = new ArrayList<>();
                        if (beginTableRow) {
                            tableRows.add(rowNode);
                            lastRowBounds = rowBounds;
                        } else {
                            lastRowBounds = null;
                        }
                        continue;
                    }
                }
                tableRows.add(rowNode);
                lastRowBounds = rowNode.getBounds2D();
            }
            if (tableRows.size() > 0) {
                structureTables.add(buildTable(table, tableRows));
            }
        }
        return structureTables;
    }

    private static Table buildTable(Table table, List<TextNode> tableRows) {
        List<TextChunk> textChunks = tableRows.stream()
                .flatMap(row -> row.getAllTextChunks().stream())
                .sorted(Comparator.comparing(TextChunk::getMcid).thenComparing(TextChunk::getGroupIndex))
                .collect(Collectors.toList());

        List<TextChunk> merged = new ArrayList<>();
        TextChunk prevText = null;
        for (TextChunk textChunk : textChunks) {
            if (prevText != null && canMerge(prevText, textChunk)) {
                prevText.merge(textChunk);
            } else {
                prevText = textChunk;
                merged.add(textChunk);
            }
        }
        // 合并单元格换行的文本, 一般的特征是上一行空格结尾, 然后\n, 有可能有一个或多个\t, 然后就是下一行的文本
        TextChunk lastNonBlankText = null;
        boolean hasLineBreak = false;
        for (TextChunk textChunk : merged) {
            if (StringUtils.isNoneBlank(textChunk.getActualTextOrText())) {
                if (hasLineBreak && canMergeVertical(lastNonBlankText, textChunk)) {
                    lastNonBlankText.merge(textChunk);
                    textChunk.markDeleted();
                    hasLineBreak = false;
                    continue;
                }
                hasLineBreak = false;
                lastNonBlankText = textChunk;
            }
            if (StringUtils.countMatches(textChunk.getActualTextOrText(), '\t') > 1) {
                lastNonBlankText = null;
            }
            if (StringUtils.contains(textChunk.getActualTextOrText(), "\n")) {
                hasLineBreak = true;
            }
        }
        merged.removeIf(o -> o.isDeleted() || StringUtils.isBlank(o.getText()));
        // 分成行
        List<List<CandidateCell>> tableCells = new ArrayList<>();
        List<CandidateCell> lastRow = new ArrayList<>();
        for (TextChunk textChunk : merged) {
            if (lastRow.isEmpty() || lastRow.get(0).verticalOverlapRatio(textChunk) > 0.6) {
                lastRow.add(CandidateCell.fromTextChunk(textChunk));
            } else {
                tableCells.add(lastRow);
                lastRow = Lists.newArrayList(CandidateCell.fromTextChunk(textChunk));
            }
        }
        if (!lastRow.isEmpty()) {
            tableCells.add(lastRow);
        }
        // 表格第一个单元格是标题
        if (tableCells.size() >= 1) {
            if (tableCells.get(0).get(0).getText().startsWith("TABLE ")) {
                table.setInlineTitle(tableCells.get(0).get(0).getText());
            }
        }
        table.setStructureCells(tableCells);
        return table;
    }

    private static boolean canMergeVertical(TextChunk lastNonBlankText, TextChunk textChunk) {
        return lastNonBlankText != null
                && !StringUtils.startsWith(textChunk.getActualTextOrText(), "$") // 单位开头的不合并, 比如: $ billions
                && FloatUtils.feq(lastNonBlankText.getMostCommonFontSize(), textChunk.getMostCommonFontSize(), 1)
                && lastNonBlankText.getMostCommonTextStyle() == textChunk.getMostCommonTextStyle()
                && lastNonBlankText.horizontallyOverlapRatio(textChunk) > 0.5;
    }

    private static boolean canMerge(TextChunk prevText, TextChunk textChunk) {
        if (StringUtils.containsAny(prevText.getActualTextOrText(), "\t", "\n")
                || StringUtils.containsAny(textChunk.getActualTextOrText(), "\t", "\n")) {
            return false;
        }
        return prevText.inOneLine(textChunk);
    }

}
