package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.content.*;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.util.ClosureWrapper;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.util.FastMath;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Represents a table title.
 */
public class TableTitle {

    private static final int TABLE_TITLE_SEARCH_LINES = 6;
    private static final int TABLE_SHOES_SEARCH_LINES = 2;

    private static final int TABLE_UNIT_SEARCH_OUTER_LINES = 2;
    private static final int TABLE_UNIT_SEARCH_INNER_LINES = 2;
    private static final boolean isOuterSearch = true;
    private static final boolean isInnerSearch = false;

    private static final float MIN_CHAR_EPSILON = 4f;


    // FIX 江苏银行-2017年三季报告 Unicode 错误 合幵（jian）-> 合并
    private static final List<Pair<String, String>> PATCHES_PREFIX =
            Lists.newArrayList(Pair.of("合幵", "合并"));

    private static final String[] SPECIAL_TABLE_DESCRIPTION_PREFIXES = new String[] {
            "The following table",
            "公司是否需追溯", "公司是否因", "公司是否需", "财务报表附注"
    };

    private static final TableTitle EMTPY = new TableTitle("", "", "", null);

    private static final CandidateTitle NO_FOUND = new CandidateTitle();

    private static final class CandidateTitle {
        final TextBlock textBlock;
        final String text;

        final int columnCount;
        final int lineCount;
        final boolean bold;
        final boolean tableText;    // 表示当前标题是否为“～表”、“表X ～”之类的文本
        final boolean asFollows;    // 表示当前标题是否为“～如下：”之类的描述
        final int catalogLevel;     // 表示标题的目录级别
        final boolean heading;      // 表示表格是否疑似为标题大纲
        final boolean numericSection;      // 表示当前标题是否为“（N）～”之类的文本
        final int index;            // 表搜索到标题的段落索引
        final boolean tableTextPart;
        final boolean pagination;


        CandidateTitle() {
            this.textBlock = null;
            this.index = -1;
            this.text = "";
            this.columnCount = 0;
            this.lineCount = 0;
            this.bold = false;
            this.asFollows = false;
            this.catalogLevel = 10;
            this.heading = false;
            this.numericSection = false;
            this.tableText = false;
            this.tableTextPart = false;
            this.pagination = false;
        }

        CandidateTitle(Cell cell) {
            this(new TextBlock(cell.getElements()), -1);
        }

        CandidateTitle(TextBlock textBlock, int index) {
            this.textBlock = textBlock;
            this.index = index;
            this.text = textBlock.getText().trim();
            this.pagination = textBlock.isPageHeader() || textBlock.isPageFooter();

            this.columnCount = textBlock.getColumnCount();
            this.lineCount = textBlock.getLineCount();
            this.catalogLevel = textBlock.getCatalogLevel();
            this.heading = catalogLevel < 9;
            this.numericSection = hasNumericSectionPrefix(text);
            this.bold = isTextBlockBold(textBlock);
            this.tableText = hasTablePrefix(text) || hasTableSuffix(text);
            this.tableTextPart = hasTablePrefix(text) || hasTableSuffixPart(text);
            this.asFollows = hasAsFollowStatement(text);
        }

        boolean likelyTitle() {
            return tableText || heading || numericSection;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static boolean adjustSearchArea(Rectangle desired, Rectangle masked) {
        boolean overhead = false;
        if (desired.getTop() <= 0) {
            desired.setTop(0);
            overhead = true;
        }
        if (null == masked || !desired.intersects(masked)) {
            return overhead;
        }
        if (desired.getBottom() < masked.getTop()) {
            desired.setTop(masked.getBottom());
            desired.setBottom(masked.getBottom());
            return false;
        }
        Rectangle2D intersection = masked.createIntersection(desired);
        if (FloatUtils.feq(intersection.getMaxY(), masked.getBottom(), MIN_CHAR_EPSILON)) {
            desired.setTop(masked.getBottom());
            return true;
        }
        return false;
    }

    private static void excludeTableAreas(Rectangle searchArea, List<Rectangle> tableAreas) {
        for (Rectangle tableArea : tableAreas) {
            if (!tableArea.intersects(searchArea)) {
                continue;
            }
            searchArea.setTop(FastMath.max(searchArea.y, tableArea.getBottom()));
        }
    }

    private static List<TextChunk> cleanTextChunks(Page page, List<TextChunk> chunks, Rectangle area) {
        List<TextChunk> cleanChunks = new ArrayList<>(chunks.size());
        float areaLeft = area.getLeft();
        float areaTop = area.getTop();
        float areaBottom = area.getBottom();
        for (TextChunk chunk : chunks) {
            // 下边缘比搜索区域还低的文本块
            if (!FloatUtils.flte(chunk.getBottom(), areaBottom, MIN_CHAR_EPSILON))
                continue;
            // 上边缘比搜索区域还高的文本块
            if (!FloatUtils.fgte(chunk.getTop(), areaTop, MIN_CHAR_EPSILON))
                continue;
            // 过滤表格区域外偏离过远的文本块
            if (FloatUtils.flte(chunk.getLeft(), areaLeft - chunk.getAvgCharWidth() * 6, MIN_CHAR_EPSILON))
                continue;
            cleanChunks.add(chunk);
        }
        return cleanChunks;
    }

    public static List<TextBlock> findTextBlocks(Page page, Rectangle searchArea) {
        ContentExtractParameters contentExtractParameters = page.params.context.getTag(PdfExtractContext.TEXT_PARAMS, ContentExtractParameters.class);
        if (null == contentExtractParameters) {
            contentExtractParameters = new ContentExtractParameters.Builder()
                    .build();
            contentExtractParameters.context = page.params.context;
        }
        TextExtractEngine textExtractEngine = new TextExtractEngine((PDDocument) page.params.context.getNativeDocument(),
                contentExtractParameters, null);
        try {
            com.abcft.pdfextract.core.content.Page contentPage = textExtractEngine.processPage(page.pageNumber, page.getPDPage());
            if (contentPage == null) {
                return new ArrayList<>();
            }
            List<Paragraph> paragraphs = contentPage.getParagraphs(false, true);
            return paragraphs.stream()
                    .filter(paragraph -> paragraph.selfOverlapRatio(searchArea) > 0.15f)
                    .map(paragraph -> paragraph.getMergedTextBlock().getActualBlock())
                    .filter(textBlock -> textBlock.hasNonBlankText() && textBlock.getAvgCharWidth() >= MIN_CHAR_EPSILON)
                    .collect(Collectors.toList());
        } catch (TimeoutException e) {
            return new ArrayList<>();
        }
//        List<TextChunk> textChunks = page.getMutableTextChunks(searchArea);
//        textChunks = cleanTextChunks(page, textChunks, searchArea);
//        return TableTitleLineMerger.merge(textChunks, searchArea);
    }


    private static boolean isTextBlockBold(TextBlock textBlock) {
        int boldTextCount = 0;
        List<TextChunk> textChunks = textBlock.getElements();
        int totalTextCount = textBlock.getText().length();
        for (TextChunk textChunk : textChunks) {
            for (TextElement textElement : textChunk.getElements()) {
                if (textElement.isBold()) {
                    boldTextCount += textElement.getText().length();
                }
            }
        }
        return  (float)boldTextCount / totalTextCount >= 0.8f;
    }

    private static boolean hasNumericSectionPrefix(String title) {
        return TableTextUtils.containsAny(title, TableTextUtils.NUMERIC_SECTION_PREFIXS);
    }

    private static boolean isNumericSection(String title) {
        return TableTextUtils.matchesAny(title, TableTextUtils.NUMERIC_SECTION_PREFIXS);
    }

    private static boolean hasTablePrefix(String title) {
        return TableTextUtils.contains(title, TableTextUtils.TABLE_TITLE_PREFIX_RE1)
                || TableTextUtils.contains(title, TableTextUtils.TABLE_TITLE_PREFIX_RE2);
    }

    static boolean hasTableSuffix(String title) {
        return TableTextUtils.containsAny(title, TableTextUtils.TABLE_TITLE_SUFFIX_RE);
    }

    static boolean hasTableSuffixPart(String title) {
        return TableTextUtils.containsAny(title, TableTextUtils.TABLE_TITLE_SUFFIX_PART_RE)
                || StringUtils.endsWithAny(title, ":", "：");
    }

    private static boolean hasAsFollowStatement(String title) {
        return TableTextUtils.contains(title, TableTextUtils.TABLE_TITLE_AS_FOLLOW_RE);
    }

    private static boolean hasUnitStatement(String unitTextLine) {
        return TableTextUtils.contains(unitTextLine, TableTextUtils.TABLE_UNIT_STATEMENT_RE);
    }

    private static boolean anyContains(List<? extends TextContainer> texts, Pattern pattern) {
        for (TextContainer textChunk : texts) {
            String text = textChunk.getText().trim();
            if (TableTextUtils.contains(text, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPaginationHeaderText(TextBlock title) {
        return anyContains(title.getElements(), TableTextUtils.REPORT_HEADER_RE)
                || anyContains(title.getElements(), TableTextUtils.REPORT_COMPANY_RE);
    }

    private static boolean isPaginationHeaderText(String text) {
        return TableTextUtils.contains(text, TableTextUtils.REPORT_HEADER_RE)
                || TableTextUtils.contains(text, TableTextUtils.REPORT_COMPANY_RE);
    }

    private static boolean hasPaginationFooterText(TextBlock title) {
        return TableTextUtils.contains(title.getText(), TableTextUtils.PAGER_RE)
                || TableTextUtils.contains(title.getText(), TableTextUtils.PAGER_RE3);
    }


    /**
     * 验证一个字符串是否可能是表格标题。
     *
     * @param candidateTitle 待验证的标题。
     * @return {@code true} 代表其可能是标题，{@code false} 表示它极有可能不是标题。
     */
    private static boolean verifyTitle(CandidateTitle candidateTitle) {
        String title = candidateTitle.text;
        boolean bold = candidateTitle.bold;
        boolean isOK = !title.isEmpty() && TableTextUtils.isValidateCharSet(title);
        if (!isOK) return false;

        title = StringUtils.trim(title);

        // 下述条件存在的问题
        // 1、无法处理多行标题的情况，包括没有明显停止符号的标题，如：
        // >”截至公告日,公司在一季度内累计购买各类银行理财产品5笔,其中理财到期赎回3笔,尚未到期的理财2
        // 笔,截止2017年3月31日已取得理财收益7.54万元。“

        isOK = title.length() > 1       // 目前的情形来看，金融领域的表格标题不太可能只有一个字符
                && title.length() < 120 // 太长的，也不能算标题
                && !StringUtils.startsWithAny(title, SPECIAL_TABLE_DESCRIPTION_PREFIXES) // 特殊处理
                && !StringUtils.endsWithAny(title, "；", ",", ";", ",", "、")
                && (bold || StringUtils.countMatches(title, '、') < 4)
                && (candidateTitle.likelyTitle() || (
                        !StringUtils.containsAny(title, '@', ';', ',', ';', ',')
                        && !StringUtils.endsWithAny(title, "。", ".")
                        && !TableTextUtils.contains(title, TableTextUtils.TABLE_CAPS_RE)
                        && !TableTextUtils.matches(title, TableTextUtils.TABLE_UNIT_RE)
                        && !TableTextUtils.matches(title, TableTextUtils.TABLE_UNIT_RE2)
                        && !TableTextUtils.contains(title, TableTextUtils.APPLICABLE_RE)
                        && !TableTextUtils.contains(title, TableTextUtils.APPLICABLE_AFFIRMATIVE_RE)
                        && !TableTextUtils.contains(title, TableTextUtils.APPLICABLE_NEGATIVE_RE)
                        && !TableTextUtils.contains(title, TableTextUtils.TABLE_DATE_RE)
                        && !TableTextUtils.contains(title, TableTextUtils.TABLE_UNTIL_DATE_RE)
                        && !TableTextUtils.contains(title, TableTextUtils.TABLE_FY_RE)
                        && !TableTextUtils.contains(title, TableTextUtils.TABLE_FQ_RE)
                        && !TableTextUtils.matches(title, TableTextUtils.TABLE_REMARK_RE))
                );
        //&& TextUtils.withoutRegex(title, "[\u4e00-\u9fa5]+[a-zA-Z]+[\u4e00-\u9fa5]+");//中银国际-170412.pdf Juxx股票xx指数
        return isOK;
    }

    private static boolean isApplicableStatement(String title) {
        return TableTextUtils.contains(title, TableTextUtils.APPLICABLE_RE)
                || TableTextUtils.contains(title, TableTextUtils.APPLICABLE_AFFIRMATIVE_RE)
                || TableTextUtils.matches(title, TableTextUtils.APPLICABLE_AFFIRMATIVE_RE2);
    }

    /**
     * 从页面上特定区域中，收集潜在的标题文本。
     *
     * @param page 要搜索的页面。
     * @param searchArea 要搜索的区域。
     * @param exclusionArea 要排除的区域。一般是上一张表格的区域。
     * @param desiredLines 希望搜索的行数。
     * @param searchExtraLines 是否执行额外搜索。尽可能多地增加候选标题文本。
     * @return 可能作为标题的文本块。
     */
    private static List<TextBlock> collectCandidateTitles(Page page, Rectangle searchArea,
                                                          Rectangle exclusionArea, int desiredLines,
                                                          boolean searchExtraLines) {
        if (searchExtraLines) {
            searchExtraLines = !adjustSearchArea(searchArea, exclusionArea);
        }

        List<TextBlock> titles = findTextBlocks(page, searchArea);

        if (titles.isEmpty()) {
            return Collections.emptyList();
        }

        int extraSearch = desiredLines - titles.size();
        Rectangle extraSearchArea = new Rectangle(searchArea);

        // Search for extract lines if possible
        while (searchExtraLines && !titles.isEmpty() && titles.size() < desiredLines && extraSearch > 0) {
            TextBlock topLine = titles.get(0);
            float lineHeight = topLine.getAvgCharHeight() * 1.5f;
            float extractHeight = lineHeight * (desiredLines - titles.size()) + MIN_CHAR_EPSILON;
            extraSearchArea.resize((float) extraSearchArea.getWidth(), extractHeight);
            extraSearchArea.moveTo(extraSearchArea.getLeft(), (float) (topLine.getTop() - MIN_CHAR_EPSILON));
            extraSearchArea.moveBy(0, -extractHeight);
            searchExtraLines = !adjustSearchArea(extraSearchArea, exclusionArea);
            List<TextBlock> newLines = findTextBlocks(page, extraSearchArea);
            if (!newLines.isEmpty()) {
                titles.addAll(0, newLines);
                --extraSearch;
            } else {
                searchExtraLines = false;
            }
        }

        return titles;
    }

    private static final Comparator<TextBlock> TITLE_SEARCH_SORTER = Comparator.comparingDouble(TextBlock::getTop).reversed();


    private static CandidateTitle searchTitleInTable(Table table) {
        Table.CellPosition position = findFirstNonEmptyCell(table);
        if (position.row > 1 || position.col > table.getColumnCount() / 2) {
            // 第一个非空行太远，基本不可能是标题了
            // 第一个非空单元格位于表格右侧，基本也不可能是标题了
            return null;
        }
        Cell titleCell = null;
        if (table.isStructureTable()) {
            // 结构化表格可能是有线或者无线，需要检查后续所有列均为空
            titleCell = table.getCell(position.row, position.col);
            if (anyCellNonEmptyFromColumn(table, position.row, position.col)) {
                return null;
            }
        } else if (table.getTableType() == TableType.LineTable) {
            // 有线表格，要求有非常明确的占满整行的单元格才视为后续标题单元格
            titleCell = table.getCell(position.row, position.col);
            if (titleCell.getColSpan() != table.getColumnCount()) {
                return null;
            }
        } else if (table.getTableType() == TableType.NoLineTable) {
            // 无线表格，可能存在定位偏差导致形如（15）这样的数字编号单独化为一列，因此追加检测
            int columnStart = position.col;
            titleCell = table.getCell(position.row, columnStart);
            if (isNumericSection(titleCell.getText().trim())) {
                ++columnStart;
                titleCell = table.getCell(position.row, columnStart);
            }

            // 无线框表格，单元格无法区分，所以检测一行下来是否有其他内容
            if (anyCellNonEmptyFromColumn(table, position.row, columnStart)) {
                return null;
            }
        }

        if (null == titleCell || titleCell.getElements().isEmpty()) {
            return null;
        }
        CandidateTitle candidateTitle = new CandidateTitle(titleCell);
        if (!candidateTitle.likelyTitle() && !candidateTitle.tableTextPart) {
            // 目前暂时只搜索非常明显的标题，不明显的全部无视。
            return null;
        }
        ClosureWrapper<CandidateTitle> wrapper = new ClosureWrapper<>(null);

        if (determineTableTitle(wrapper, candidateTitle)) {
            return wrapper.get();
        } else {
            return null;
        }
    }

    private static boolean anyCellNonEmptyFromColumn(Table table, int row, int colStart) {
        for (int col = colStart + 1; col < table.getColumnCount(); ++col) {
            Cell other = table.getCell(row, col);
            if (!other.isPivot(row, col)) {
                continue;
            }
            if (!StringUtils.isBlank(other.getText())) {
                // 这一行还有其他有文字的单元格，不太可能是标题
                return true;
            }
        }
        return false;
    }

    private static Table.CellPosition findFirstNonEmptyCell(Table table) {
        for (int r = 0; r < table.getRowCount(); ++r) {
            for (int c = 0; c < table.getColumnCount(); ++c) {
                Cell cell = table.getCell(r, c);
                if (!cell.isPivot(r, c)) {
                    continue;
                }
                String cellText = cell.getText();
                if (!StringUtils.isBlank(cellText) && !isPaginationHeaderText(cellText)) {
                    return new Table.CellPosition(r, c);
                }
            }
        }
        // 找不到，默认左上单元格
        return new Table.CellPosition(0, 0);
    }


    /**
     * 搜索给定页面区域内最可能为标题的文本。
     *
     * @param page 要搜索的页面。
     * @param searchArea 要搜索的区域。
     * @param exclusionArea 要排除的区域。一般是上一张表格的区域。
     * @param desiredLines 希望搜索的行数。
     * @param searchExtraLines 是否执行额外搜索。
     * @return 最可能时标题的一行或者一段文本。
     */
    private static TableTitle searchTableTitleImpl(Page page, Rectangle searchArea,
                                                   Rectangle exclusionArea, int desiredLines,
                                                   boolean searchExtraLines) {
        List<TextBlock> titles = collectCandidateTitles(page, searchArea, exclusionArea, desiredLines, searchExtraLines);
        if (titles.isEmpty()) {
            return EMTPY;
        }

        titles.sort(TITLE_SEARCH_SORTER);

        LinkedList<CandidateTitle> caps = new LinkedList<>();
        int currentIndex = -1;

        CandidateTitle mainTitle = NO_FOUND;
        boolean mainTitleFound = false;

        for (TextBlock title : titles) {
            ++currentIndex;

            CandidateTitle candidateTitle = new CandidateTitle(title, currentIndex);

            if (!candidateTitle.tableText && !candidateTitle.tableTextPart && !candidateTitle.numericSection
                    && (hasPaginationHeaderText(title) || hasPaginationFooterText(title))) {
                continue;
            }
            if (!StringUtils.isEmpty(candidateTitle.text)) {
                caps.addFirst(candidateTitle);
            }

            /*
            现在，标题已经全部按段落来查找了。所以，是不是右对齐已经无所谓了。
            // 标题一般不会是偏右的文字
            // 注：原先判断是标题中心如果偏离表格中心右方 5% 即判定为右对齐，原因不明；
            // 这里修改为右边和边缘基本对齐并且左边不对齐。
            Rectangle titleVisibleArea = new Rectangle(title.getBounds2D());
            float threshold = FastMath.min(25.f, searchArea.width / 10.f);
            boolean alignRight = searchArea.getRight() - titleVisibleArea.getRight() < threshold
                    && titleVisibleArea.getLeft() - searchArea.getLeft() > (threshold + title.getAvgCharWidth() * 3);
            if (alignRight) {
                continue;
            }
            */

            if (mainTitleFound) {
                ClosureWrapper<CandidateTitle> wrapper = new ClosureWrapper<>(mainTitle);
                // 某项假标题上方可能有更好的标题，因此追加搜索
                findBetterTitle(wrapper, candidateTitle);
                mainTitle = wrapper.get();
                if (mainTitle == NO_FOUND) {
                    mainTitleFound = false;
                }
                continue;
            }
            ClosureWrapper<CandidateTitle> wrapper = new ClosureWrapper<>(mainTitle);

            mainTitleFound = determineTableTitle(wrapper, candidateTitle);
            if (mainTitleFound) {
                mainTitle = wrapper.get();
            }
        }

        return new TableTitle(mainTitle, caps);
    }

    private static boolean determineTableTitle(ClosureWrapper<CandidateTitle> mainTitleWrap, CandidateTitle candidateTitle) {
        int columnCount = candidateTitle.columnCount;
        int lineCount = candidateTitle.lineCount;
        TextClasses classes = candidateTitle.textBlock.getClasses();

        if (candidateTitle.tableText || candidateTitle.asFollows
                || (columnCount <= 2 && verifyTitle(candidateTitle))) {
            mainTitleWrap.set(candidateTitle);
            return true;
        } else if ((candidateTitle.numericSection || candidateTitle.tableTextPart)
                && (candidateTitle.text.length() < 50 || columnCount >= 2 || lineCount >= 2)) {
            // 允许搜索条件条件为：有数字序号、多栏或者字数较少

            // 蓝海华腾-2017年三季度报告（标题两栏）
            // 西安旅游-2017年三季度报告（标题三栏）
            // 茂业通信-2017年三季度报告（标题三栏、格式混合）
            // 重庆钢材-2017年年度报告
            // 可能会有这种类型的标题：
            // <b>1、<u>合并及母公司资产负债表</u></b>（2017 年 9 月 30 日）
            // 2、母公司资产负债表                        单位：元
            // 7、合并年初到报告期末现金流量表       2017 年 1-9 月      单位：元
            // 现金流量表项目          单位:千元 币种:人民币
            // (一)公司股份变动情况表(截至 2016 年 12 月 31 日)
            // 图表10 2012年中国市场排名前五的智能手机品牌（单位：百万台）
            // 合并股东权益变动表 2013 年 1-6 月（万科A、2013年年报 段落合并失败）
            //
            for (TextChunk line : candidateTitle.textBlock.getElements()) {
                CandidateTitle titleLine = new CandidateTitle(new TextBlock(line, classes), candidateTitle.index);
                if (!titleLine.numericSection && !titleLine.tableText && !titleLine.tableTextPart)
                    continue;
                List<TextChunk> columns = TextMerger.squeeze(line, ' ', 3);
                for (TextChunk chunk : columns) {
                    CandidateTitle tempTitle = new CandidateTitle(new TextBlock(chunk, classes), candidateTitle.index);
                    if (tempTitle.tableText || tempTitle.asFollows || verifyTitle(tempTitle)) {
                        mainTitleWrap.set(tempTitle);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void findBetterTitle(ClosureWrapper<CandidateTitle> mainTitleWrap, CandidateTitle candidateTitle) {
        CandidateTitle mainTitle = mainTitleWrap.get();
        boolean mainTitleInLastLine = (candidateTitle.index - mainTitle.index) == 1;
        boolean checkMoreTitle = mainTitleInLastLine || candidateTitle.heading;

        // 校正描述性标题，如果上一行有数字编号，把数字编号作为标题
        if (mainTitle.asFollows && checkMoreTitle
                && candidateTitle.numericSection && !mainTitle.numericSection) {
            mainTitle = candidateTitle;
        } else if (!mainTitle.bold && candidateTitle.bold
                && !mainTitle.tableText && checkMoreTitle
                && candidateTitle.numericSection && !mainTitle.numericSection) {
            // 旧标题非粗体、非“～表”的形式，而且当前标题粗体或者带数字编号前缀
            mainTitle = candidateTitle;
        } else if (isApplicableStatement(candidateTitle.text) && mainTitleInLastLine
                && !mainTitle.bold && !mainTitle.numericSection && !mainTitle.tableText) {
            // 旧标题非粗体、非数字编号，而当前行属于“是”这样的文字，那么很可能旧标题不是标题
            mainTitle = NO_FOUND;
        }
        mainTitleWrap.set(mainTitle);
    }

    public static void searchTableTitles(Page page, List<Table> tables) {
        if (tables.isEmpty()) {
            return;
        }
        Table prevTable = null;
        for (Table table : tables) {
            int basicCellHeight = (int) (table.getHeight() / table.getRowCount());
            CandidateTitle inlineTitle = searchTitleInTable(table);
            TableTitle title = searchTableTitle(table, basicCellHeight, prevTable, page);
            if (null == inlineTitle || (!inlineTitle.tableText && title.advantaged)) {
                table.setTableTitle(title);
            } else {
                // 如果存在表格内标题，并且外部标题没有明显优势，使用表格内标题
                table.setInlineTitle(inlineTitle.text);
                table.setCaps(title.getCaps());
                table.setShoes(title.getShoes());
                table.setUnit(title.getUnitTextLine(), title.getUnitSet());
            }
            prevTable = table;
        }
    }

    /**
     * 搜索位于特定区域的表格标题。
     * <p>
     * 如果搜索已经达到页面顶部，会考虑搜索上一页的文本。
     *
     * @param tableArea 表格的区域。
     * @param rowHeight 平均或者最可能的行高。
     * @param exclusionArea 需要排除的区域，一般是上一个表格的区域。
     * @param page 表格所在的页。
     * @return 一个可能的表格标题。
     */
    public static TableTitle searchTableTitle(Rectangle tableArea, int rowHeight, Rectangle exclusionArea, Page page) {

        int searchAreaHeight = Math.max(70, rowHeight * TABLE_TITLE_SEARCH_LINES);
        float minCharWidth = FastMath.max(Page.DEFAULT_MIN_CHAR_WIDTH, page.getMinCharWidth());

        Rectangle titleArea =  new Rectangle(tableArea.getLeft() - minCharWidth,
                // 因为结构化表格可能只包含文字的外边框信息，这里需要上移一些避免和文字相交
                tableArea.getTop() - MIN_CHAR_EPSILON - searchAreaHeight,
                (float)tableArea.getWidth() + minCharWidth * 2, searchAreaHeight);
        TableTitle title = searchTableTitleImpl(page, titleArea, exclusionArea, TABLE_TITLE_SEARCH_LINES, true);
        searchTableUnit(tableArea, exclusionArea, page, title);

        PaperParameter paper = page.getPaper();
        Page prevPage = page.getPrevPage();

        // Let's search in previous page if the search area if out of box
        if (prevPage != null && (titleArea.getTop() < 0 || paper.topMarginContains(titleArea.getTop()))) {
            int extraLines;
            if (StringUtils.isBlank(title.getCaps())) {
                extraLines = TABLE_TITLE_SEARCH_LINES;
            } else {
                extraLines = (int) FastMath.ceil((paper.topMarginInPt - titleArea.getTop()) / rowHeight);
            }
            Rectangle prevTextArea = prevPage.getTextArea();
            float startingY;
            if (prevTextArea != null) {
                startingY = prevTextArea.getBottom();
            } else {
                startingY = (float) (prevPage.getHeight() -  paper.bottomMarginInPt);
            }

            int prevSearchAreaHeight = Math.max(50, rowHeight * Math.max(extraLines, 2));
            int prevY = (int) (startingY - MIN_CHAR_EPSILON - prevSearchAreaHeight);
            minCharWidth = FastMath.max(Page.DEFAULT_MIN_CHAR_WIDTH, prevPage.getMinCharWidth());
            Rectangle prevTitleArea =  new Rectangle(titleArea.getLeft() - minCharWidth,
                    prevY,
                    (float) titleArea.getWidth() + minCharWidth * 2,
                    prevSearchAreaHeight);
            excludeTableAreas(prevTitleArea, prevPage.getTableAreas());
            if (prevTitleArea.getHeight() > MIN_CHAR_EPSILON) {
                // It is no good to search too much in previous page, so
                // searchExtraLines should be disabled
                TableTitle previousTitle = searchTableTitleImpl(prevPage, prevTitleArea,
                        null, Math.max(extraLines, 2), false);
                title = title.prepend(previousTitle);
            }
        }

        return title;
    }

    /**
     * 暂时不开启内部单位行搜索
     * @return 可能的文本行
     */
    private static List<TextBlock> collectCandidateUnits(Page page, Rectangle tableArea, Rectangle exclusionArea
            , boolean isOuterSearch, int outerSearchLines, boolean isInnerSearch, int innerSearchLines, boolean searchExtraLines) {
        if (!isOuterSearch) {
            return Collections.emptyList();
        }
        float searchAreaHeight = 1.5f * TABLE_UNIT_SEARCH_OUTER_LINES * page.getAvgCharHeight();
        float minCharWidth = FastMath.max(Page.DEFAULT_MIN_CHAR_WIDTH, page.getMinCharWidth());
        Rectangle searchArea =  new Rectangle(tableArea.getLeft() - minCharWidth, tableArea.getTop() - searchAreaHeight,
                (float)tableArea.getWidth() + minCharWidth * 2, searchAreaHeight);
        if (searchExtraLines) {
            searchExtraLines = !adjustSearchArea(searchArea, exclusionArea);
        }
        List<TextBlock> unitTextBlocks = findTextBlocks(page, searchArea);
        if (unitTextBlocks.isEmpty()) {
            return Collections.emptyList();
        }
        int extraSearch = outerSearchLines - unitTextBlocks.size();
        Rectangle extraSearchArea = new Rectangle(searchArea);

        // Search for extract lines if possible
        while (searchExtraLines && !unitTextBlocks.isEmpty() && unitTextBlocks.size() < outerSearchLines && extraSearch > 0) {
            TextBlock topLine = unitTextBlocks.get(0);
            float lineHeight = 1.5f * topLine.getAvgCharHeight();
            float extractHeight = lineHeight * (outerSearchLines - unitTextBlocks.size()) + 2;
            extraSearchArea.expand(0, extractHeight, 0, 0);
            searchExtraLines = !adjustSearchArea(extraSearchArea, exclusionArea);
            unitTextBlocks = findTextBlocks(page, extraSearchArea);
            --extraSearch;
        }

        return unitTextBlocks;
    }

    private static void searchTableUnit(Rectangle tableArea, Rectangle exclusionArea, Page page, TableTitle title) {
        List<TextBlock> unitTextBlocks = collectCandidateUnits(page, tableArea, exclusionArea, isOuterSearch, TABLE_UNIT_SEARCH_OUTER_LINES
                , isInnerSearch, TABLE_UNIT_SEARCH_INNER_LINES, true);
        if (unitTextBlocks.isEmpty()) {
            return;
        }

        unitTextBlocks.sort(Comparator.comparingDouble(TextBlock::getTop).reversed());
        for (TextBlock unit : unitTextBlocks) {
            String candidateUnit = unit.getText().trim();
            if (StringUtils.isEmpty(candidateUnit) || hasPaginationHeaderText(unit) || hasPaginationFooterText(unit)) {
                continue;
            }
            if (hasUnitStatement(candidateUnit)) {
                HashSet<String> unitSet = new HashSet<>();
                for (TextChunk tc : unit.getElements()) {
                    String tcStr = tc.getText().trim();
                    if (!hasUnitStatement(tcStr) || tcStr.isEmpty()) {
                        continue;
                    }

                    Matcher m = TableTextUtils.UNIT_SPLIT_RE_1.matcher(tcStr);
                    if (m.find()) {
                        unitSet.add(m.group());
                        continue;
                    }

                    if (TableTextUtils.UNIT_SPLIT_RE_2.matcher(tcStr).find()) {
                        unitSet.add(tcStr);
                    }
                }
                title.setUnit(unit, unitSet);
                return;
            }
        }
    }

    private TableTitle(CandidateTitle mainTitle, List<CandidateTitle> candidateTitles) {
        this.title = mainTitle.text;
        if (mainTitle != NO_FOUND) {
            mainTitle.textBlock.getClasses().addClass(TextClasses.CLASS_TABLE_TITLE);
        }
        List<String> capLines = new ArrayList<>(candidateTitles.size());
        for (CandidateTitle candidateTitle : candidateTitles) {
            if (candidateTitle.index < mainTitle.index) {
                candidateTitle.textBlock.getClasses().addClass(TextClasses.CLASS_TABLE_DESC);
            } else if (candidateTitle.index > mainTitle.index) {
                candidateTitle.textBlock.getClasses().addClass(TextClasses.CLASS_TABLE_CAPS);
            }
            capLines.add(candidateTitle.text);
        }
        this.caps = StringUtils.join(capLines, ExtractorUtil.LINE_SEPARATOR);
        this.advantaged = mainTitle.likelyTitle() || mainTitle.bold;
        this.bold = mainTitle.bold;
    }

    private TableTitle(String title, String caps, String unitTextLine, HashSet<String> unitSet) {
        for (Pair<String, String> patch : PATCHES_PREFIX) {
            if (title.contains(patch.getLeft())) {
                title = title.replace(patch.getLeft(), patch.getRight());
            }
            if (caps.contains(patch.getLeft())) {
                caps = caps.replace(patch.getLeft(), patch.getRight());
            }
        }
        this.title = title;
        this.caps = caps;
        this.unitTextLine = unitTextLine;
        this.unitSet = unitSet;
    }

    private TableTitle(TableTitle other) {
        this.title = other.title;
        this.bold = other.bold;
        this.caps = other.caps;
        this.shoes = other.shoes;
        if (other.unitSet != null) {
            this.unitSet = new HashSet<>(other.unitSet);
        }
        this.unitTextLine = other.unitTextLine;
        this.advantaged = other.advantaged;
    }

    private String title;
    private boolean bold;
    private boolean advantaged;
    private String caps;
    private String shoes;
    private String unitTextLine = "";
    private HashSet<String> unitSet;

    public String getTitle() {
        return title;
    }

    public boolean isBold() {
        return bold;
    }

    public String getCaps() {
        return caps;
    }

    public String getShoes() {
        return shoes;
    }

    private void setUnit(TextBlock unitTextLine, HashSet<String> unitSet) {
        this.unitTextLine = unitTextLine.getText();
        unitTextLine.getClasses().addClass(TextClasses.CLASS_TABLE_UNIT);
        this.unitSet = unitSet;
    }


    public String getUnitTextLine() {
        return this.unitTextLine;
    }

    public HashSet<String> getUnitSet() {
        return this.unitSet;
    }

    private TableTitle prepend(TableTitle prev) {
        TableTitle newTitle = new TableTitle(this);
        newTitle.bold = this.bold;
        if (StringUtils.isEmpty(title) || (!advantaged && prev.advantaged)) {
            newTitle.title = prev.title;
        }
        if (!StringUtils.isEmpty(prev.caps)) {
            newTitle.caps = prev.caps + "\n" + this.caps;
        }
        return newTitle;
    }

    @Override
    public String toString() {
        return "TableTitle{" +
                "title='" + title + '\'' +
                ", caps='" + caps + '\'' +
                '}';
    }
}
