package com.abcft.pdfextract.core.util;

import com.abcft.pdfextract.core.PageCallback;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.content.*;
import com.abcft.pdfextract.core.content.Page;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.detectors.TableRegionDetectionUtils;
import com.abcft.pdfextract.util.ClosureInt;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDPage;
import org.tensorflow.example.Example;
import org.tensorflow.example.Features;
import org.tensorflow.util.TFRecordReader;
import org.tensorflow.util.TFRecordWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.abcft.pdfextract.core.content.TextExtractEngine.createColumn;

public class TrainDataWriter implements PageCallback {

    private TFRecordWriter tfRecordWriter;
    private ClosureInt tfRecordCount = new ClosureInt();
    //keyword pattern
    public static final Pattern TABLE_BEGINNING_KEYWORD_RE = Pattern.compile("(^表[(（]?[0-9]{1,2}.*)|(^表[零一二三四五六七八九十].*)" +
            "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
            "|(^(the following table).*)|(.*(如下表|如下表所示|见下表)(：|:)$)|(^(下表|以下).*(：|:)$)" +
            "|(.*下表列出.*)|(.*(table)\\s+\\d{1,2}$)|(.*(as follows)[：:.]$)|(^表[0-9].*)|(^(图表|圖表|表格|附表).*)|(^附.*表$)|(^表:.*)" +
            "|(^表(\\d{0,2})?:.*)|(.*表(一|二|三|四)$)|(.*表[(（][零一二三四五六七八九十][)）]$)" +
            "|(^(Figure|figure)(\\d{1,2})?.*)|(^图(\\d{0,2})?.*)|(.*统计表$)|(.*(结果及分析|评估结果)|(^(金额|单位|数量)[:：].*(元|吨|比例：%|比率：%)$))");
    public static final Pattern TABLE_END_KEYWORD_RE = Pattern.compile("(^(资料来源|来源|数据来源|注：|資料來源|注[0-9]：).*)");
    public static final Pattern CHART_BEGINNING_KEYWORD_RE = Pattern.compile("(^(图表|圖表|图|圖)(\\d{0,2})?(：|:|\\s+).*)|(^(chart|figure|Figure)\\s+\\d{0,2})|(^图(\\d{0,2})?.*)");
    static final Pattern MEASURE_UNIT_RE = Pattern.compile(".*((单位|注|人民币|單位|人民幣)([0-9]{1,2})?(：|:)).*");
    public static final Pattern SERIAL_NUMBER_ROW_RE= Pattern.compile("(^\\s*[(（]?[0-9零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）]?[.、]?\\s*[\\u4E00-\\u9FA5]+.+)" +
            "|(^\\s*[(（]?[0-9a-zA-Z零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）].+)|(^\\s*[(（](i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)[)）].+)" +
            "|(^\\s*[(（]?(i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)[)）].+)" +
            "|(^\\s*[(（]?[a-zA-Z]{1,2}[)）]?[.、].+)");
    public static final Pattern PARAGRAPH_BULLETS_STATRT_RE = Pattern.compile("(^\\s*[?⟡❖●■◆???√□§\uF02D\uF0B7]+\\s*(?<text>.*))" +
            "|(^\\s*-\\s+.+)");
    static final Pattern PARAGRAPH_CATLOG_RE = OutlineNode.CATALOGUE_SUFFIX;
    public static final Pattern PARAGRAPH_END1_RE = Pattern.compile(".+[.。]\\s*$");
    public static final Pattern PARAGRAPH_END2_RE = Pattern.compile(".+[:：]\\s*$");
    static final Pattern PARAGRAPH_END3_RE = Pattern.compile(".+[;；]\\s*$");
    public static final Pattern PARAGRAPH_END_RE = Pattern.compile(".+[.。:：;；]\\s*$");
    static final Pattern MULTIPLE_SAPCE = Pattern.compile("\\s{2,}");


    private static final Pattern[] patternList = {
            TABLE_BEGINNING_KEYWORD_RE,
            CHART_BEGINNING_KEYWORD_RE,
            MEASURE_UNIT_RE,
            SERIAL_NUMBER_ROW_RE,
            PARAGRAPH_BULLETS_STATRT_RE,
            PARAGRAPH_CATLOG_RE,
            PARAGRAPH_END1_RE,
            PARAGRAPH_END2_RE,
            PARAGRAPH_END3_RE
    };

    //页面垂直网格划分数目
    private static final int gridNum = 200;

    static LineTag lastPageParagraph = LineTag.OTHER;

    public TrainDataWriter(File output) {
        try {
            tfRecordWriter = new TFRecordWriter(output);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }
    //read tfrecord file
    public Example TrainDataReader(File file) {
        Example example = null;
        try {
            TFRecordReader tfRecordReader = new TFRecordReader(file, false);
            example = tfRecordReader.readExample();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return example;
    }

    @Override
    public void onPageFinished(PdfExtractContext.PageContext pageContext, PDPage pdPage, int pageIndex, Page contentPage, List<Chart> charts, List<Table> tables) {
        ContentGroupPage tablePage = null;
        if (pageContext.getArgMap().containsKey(TableExtractor.getTablePageArg())) {
            tablePage = pageContext.getArg(TableExtractor.getTablePageArg());
        }
        if((contentPage==null) || tablePage==null){
            return;
        }
        //没有指定Json路径同时不使用结构化信息
        if((!contentPage.isUseStructInfo()) && (tablePage.params.jsonDataFilesPath==null)){
            return;
        }
        writePage(tablePage, contentPage, charts.stream().map(Chart::getArea).collect(Collectors.toList()), tables);
    }

    public enum LineTag {
        UNKNOWN,
        OTHER,
        PARAGRAPH_START,
        PARAGRAPH_MIDDLE,
        PARAGRAPH_END,
        SINGLE_LINE_PARAGRAPH,
        PARAGRAPH_HEADER,
        PARAGRAPH_FOOTER,
        TABLE_START,
        TABLE_MIDDLE,
        TABLE_END,
        SINGLE_LINE_TABLE,
        CHART_START,
        CHART_MIDDLE,
        CHART_END,
    }

    public static class LineInfo {

        //字符和字体信息
        TextChunk textChunk;
        String rawText;
        String text;
        int textLength;
        float fontSize;
        boolean bold;
        boolean italic;
        float avgTextGap;//相对字符大小的平均字符宽度

        //坐标信息
        float left;
        float top;
        float width;
        float height;
        float leftDiff; // 与所有文本左边界间距
        float rightDiff; // 与所有文本右边界间距
        float prevLeftDiff; // 与上一行左边界间距
        float prevRightDiff; // 与上一行右边界间距
        float prevTopDiff; // 与上一行上边界间距
        float prevBottomDiff; // 与上一行下边界间距
        float nextLeftDiff; // 与下一行左边界间距
        float nextRightDiff; // 与下一行右边界间距
        float nextTopDiff; // 与下一行上边界间距
        float nextBottomDiff; // 与下一行下边界间距
        float prevMargin;
        float nextMargin;

        //相对版面的坐标信息
        float leftLayout;
        float topLayout;
        public float widthLayout;
        public float heightLayout;
        float leftDiffLayout; // 与所有文本左边界间距
        float rightDiffLayout; // 与所有文本右边界间距
        float prevLeftDiffLayout; // 与上一行左边界间距
        float prevRightDiffLayout; // 与上一行右边界间距
        float prevTopDiffLayout; // 与上一行上边界间距
        float prevBottomDiffLayout; // 与上一行下边界间距
        float nextLeftDiffLayout; // 与下一行左边界间距
        float nextRightDiffLayout; // 与下一行右边界间距
        float nextTopDiffLayout; // 与下一行上边界间距
        float nextBottomDiffLayout; // 与下一行下边界间距

        //Ruling信息
        public boolean hasTopRuling;//文本行上方一定范围内有水平线
        public boolean hasBottomRuling;//文本行下方一定范围内有水平线

        //文本块信息
        public int columnCount;//文本行中字符串数目
        int numberNum;//文本行中数字串的数目
        public float numberRatio;//文本行中数字串所占比率

        //关键词信息
        public List<Long> patternMatchResult;

        //填充色信息
        public boolean hasFillArea;
        //相似的表格行
        public boolean hasPrevSimilarTableLine;
        public boolean hasNextSimilarTableLine;
        float prevSpaceNumRatio;//上下行间互相空格数目比例
        float nextSpaceNumRatio;//上下行间互相空格数目比例
        //是否被有线框区域包围
        public boolean hasRulingRegion;
        //上方是否存在空格行
        public boolean hasTopSpaceLine;

        //下方是否存在空格行
        public boolean hasBottomSpaceLine;

        //是否有表格结束的正则关键字,现有模型已经上线不能再修改旧的字段
        public boolean hasTableEndKeyWord;
        //与几条垂直线相交
        public int numberVerticalLines;
        //有几个TextChunk
        public int numberTextChunks;
        //当前行有多少空格
        float spaceRatio;

        //是否有缩进
        boolean isIndent;
        //段落的最后一个字符是否到达段落右边框
        boolean isEndInAdVance;

        //文本行内部间隔和直线信息（采用网格划分方法）
        //List<Long> girdInfo;

        //tag标签信息
        LineTag tag = LineTag.OTHER;
        public TextChunk getTextChunk(){
            return textChunk;
        }

        private void setText(String text) {
            text = text.trim();
            int num = 0;
            this.rawText = text;
            this.textLength = text.length();
            Matcher matcher = MULTIPLE_SAPCE.matcher(text);
            columnCount = 1;
            while (matcher.find()) {
                columnCount++;
            }
            List<String> chars = new ArrayList<>(text.length());
            for (int i = 0; i < textLength; i++) {
                char c = text.charAt(i);
                if (c == ' ') {
                    chars.add("<SPACE>");
                    num++;
                } else {
                    chars.add(String.valueOf(c));
                }
            }
            if(textLength != 0) {
                this.spaceRatio = (float) num / textLength;
            }
            this.text = StringUtils.join(chars, " ");

            //数字字符串特征
            String [] strings = MULTIPLE_SAPCE.split(text);
            if (strings.length != 0) {
                this.numberNum = TableRegionDetectionUtils.countNumberStr(strings);
                this.numberRatio = (float)this.numberNum / strings.length;
            }

            //关键词匹配
            this.patternMatchResult = new ArrayList<>(patternList.length);
            for (Pattern pattern : patternList) {
                if (pattern.matcher(this.rawText).find()) {
                    this.patternMatchResult.add(1L);
                } else {
                    this.patternMatchResult.add(0L);
                }
            }
        }

        @Override
        public String toString() {
            return rawText + ", " + tag.name() + ", " + prevMargin + ", " + nextMargin + ", " + isIndent + ", " + isEndInAdVance;
        }
    }

    private void writePage(ContentGroupPage tablePage, Page contentPage, List<? extends Rectangle> charts, List<? extends Rectangle> tables) {
        if (contentPage.getHeader() != null) {
            if (ParagraphMerger.mergeToLine(contentPage.getHeader().getTextChunks()).size() > 3) {
                return;
            }
        }
        if (contentPage.getFooter() != null) {
            if (ParagraphMerger.mergeToLine(contentPage.getFooter().getTextChunks()).size() > 3) {
                return;
            }
        }

        List<Paragraph> paragraphs = contentPage.getParagraphs(false, true).stream()
                .filter(paragraph -> !paragraph.isPagination())
                .filter(paragraph -> paragraph.getTextBlock().getElements().stream().noneMatch(TextChunk::isVertical))
                .collect(Collectors.toList());
        List<TextChunk> lines = paragraphs.stream()
                .flatMap(paragraph -> paragraph.getTextBlock().getElements().stream())
                .collect(Collectors.toList());
        List<TextChunk> mergedLines = ParagraphMerger.mergeToLine(lines);
        List<LineInfo> lineInfos = new ArrayList<>();
        List<Page.TextGroup> allGroups=new ArrayList<>();
        if(tablePage.params.useCRFTableLayout) {
            List<Rectangle> layoutAnalysisRectList=tablePage.getTableLayoutAnalysis();//走表格的版面流程
            for (Rectangle rc : layoutAnalysisRectList) {
                Page.TextGroup textGroup = new Page.TextGroup(rc);
                textGroup.addTexts(tablePage.getMutableTextChunks(rc));
                allGroups.add(textGroup);
            }
        }
        else {
            System.out.println("write page: " + contentPage.getPageNumber());
            allGroups= contentPage.getAllGroups();//走段落的版面流程
            //找出结构化信息没有圈对的页眉或页脚信息,用于训练
            List<TextChunk> tempTextChunkLists = allGroups.stream()
                    .flatMap(textGroup -> textGroup.getTextChunks().stream())
                    .collect(Collectors.toList());
            List<TextChunk> tempMergedLines = mergedLines.stream()
                    .filter(textChunk -> textChunk.selfOverlapRatio(textChunk.boundingBoxOf(tempTextChunkLists)) < 0.3)
                    .collect(Collectors.toList());
            List<TextChunk> tempMergedHeaders = tempMergedLines.stream()
                    .filter(textChunk -> textChunk.getTop() < 140)
                    .collect(Collectors.toList());
            List<TextChunk> tempMergedFooters = tempMergedLines.stream()
                    .filter(textChunk -> textChunk.getBottom() > 700)
                    .collect(Collectors.toList());
            List<Page.TextGroup> tempGroupHeaders = allGroups.stream()
                    .filter(Page.TextGroup::isPageHeader)
                    .collect(Collectors.toList());
            if(tempGroupHeaders.isEmpty() && !tempMergedHeaders.isEmpty()) {
                Rectangle tempRectangle = new Rectangle(Page.TextGroup.boundingBoxOf(tempMergedHeaders));
                contentPage.setHeader(createColumn(tempRectangle,tempMergedHeaders));
            } else if (!tempGroupHeaders.isEmpty() && !tempMergedHeaders.isEmpty()){
                System.out.print("have another header group!");
            }
            List<Page.TextGroup> tempGroupFooters = allGroups.stream()
                    .filter(Page.TextGroup::isPageFooter)
                    .collect(Collectors.toList());
            if (tempGroupFooters.isEmpty() && !tempMergedFooters.isEmpty()) {
                Rectangle tempRectangle = new Rectangle(Page.TextGroup.boundingBoxOf(tempMergedFooters));
                tempMergedFooters.stream()
                        .forEach(textChunk -> textChunk.setPaginationType(PaginationType.FOOTER));
                contentPage.setFooter(createColumn(tempRectangle, tempMergedFooters));
            } else if (!tempGroupFooters.isEmpty() && !tempMergedFooters.isEmpty()){
                System.out.print("have another footer group!");
            }

            allGroups = contentPage.getAllGroups();
            allGroups.forEach(textGroup -> NumberUtil.sortByReadingOrder(textGroup.getTextChunks()));
        }

        if (false) {
            //考虑到多个版面的时候,如果重新排序会乱，此处暂不排序，一律走下面的流程
            lineInfos.addAll(buildLineInfos(tablePage, contentPage, mergedLines));
        } else {
            for (Page.TextGroup textGroup : allGroups) {
                if (textGroup.getTextChunkCount() < 1 || StringUtils.isBlank(textGroup.getTextChunks().toString())) {
                    continue;
                }
                lineInfos.addAll(buildLineInfosForLayout(tablePage, contentPage, textGroup));
            }
        }
        if (lineInfos.isEmpty()) {
            return;
        }

        List<LineInfo> lineWithoutTag = new ArrayList<>(lineInfos);
        boolean isFirstParagraph = true;
        for (Paragraph paragraph : paragraphs) {
            List<TextChunk> pLines = paragraph.getTextBlock().getElements();
            pLines.removeIf(TextChunk::isBlank);
            if (pLines.size() == 1) {
                for (Iterator<LineInfo> iterator = lineWithoutTag.iterator(); iterator.hasNext(); ) {
                    LineInfo info = iterator.next();
                    List<Page.TextGroup> tempTextGroups = contentPage.getAllGroups().stream()
                            .filter(textGroup -> pLines.get(0).selfOverlapRatio(textGroup) > 0.8)
                            .collect(Collectors.toList());
                    Page.TextGroup tempTextGroup = null;
                    if (tempTextGroups.size() == 1) {
                        tempTextGroup = tempTextGroups.get(0);
                    } else {
                        break;
                    }
                    Page.TextGroup tempFooter = contentPage.getFooter();
                    Page.TextGroup tempHeader = contentPage.getHeader();
                    List<TextChunk> tempTextChunks = tempTextGroup == null ? null : tempTextGroup.getTextChunks();
                    if (tempTextGroup != null && tempFooter != null
                            && tempTextChunks != null && !tempTextChunks.isEmpty()
                            && tempFooter.overlapRatio(tempTextGroup.getTextChunks().get(0)) > 0.7
                            && info.textChunk.overlapRatio(pLines.get(0)) > 0.6) {
                        info.tag = LineTag.PARAGRAPH_FOOTER;
                    } else if (tempTextGroup != null && tempHeader != null
                            && tempTextChunks != null && !tempTextChunks.isEmpty()
                            && tempHeader.overlapRatio(tempTextGroup.getTextChunks().get(0)) > 0.7
                            && info.textChunk.overlapRatio(pLines.get(0)) > 0.6) {
                        info.tag = LineTag.PARAGRAPH_HEADER;
                    } else if (info.textChunk.selfOverlapRatio(pLines.get(0)) > 0.2
                            && info.textChunk.verticalOverlapRatio(pLines.get(0)) > 0.9) {
                        List<Page.TextGroup> tempTextGroups1 = contentPage.getTextGroups().stream()
                                .filter(textGroup -> info.textChunk.selfOverlapRatio(textGroup) > 0.5)
                                .collect(Collectors.toList());
                        List<Page.TextGroup> tempTextGroups2 = contentPage.getTextGroups().stream()
                                .filter(textGroup -> pLines.get(0).selfOverlapRatio(textGroup) > 0.5)
                                .collect(Collectors.toList());
                        if (!tempTextGroups1.equals(tempTextGroups2)) {
                            continue;
                        }
                        if (info.textChunk.getTop() < 140 && isFirstParagraph) {
                            if (PARAGRAPH_END_RE.matcher(info.textChunk.getText()).find()
                                    && lastPageParagraph.equals(LineTag.PARAGRAPH_MIDDLE)) {
                                info.tag = LineTag.PARAGRAPH_END;
                            } else {
                                info.tag = LineTag.SINGLE_LINE_PARAGRAPH;
                            }
                        } else if (info.textChunk.getBottom() > 700) {

                            if (info.isEndInAdVance || PARAGRAPH_END_RE.matcher(info.text).find()) {
                                info.tag = LineTag.SINGLE_LINE_PARAGRAPH;
                                lastPageParagraph = LineTag.SINGLE_LINE_PARAGRAPH;
                            } else if (tempTextGroup.getTextChunks().size() > 1){
                                info.tag = LineTag.PARAGRAPH_START;
                                lastPageParagraph = LineTag.PARAGRAPH_START;
                            } else {
                                info.tag = LineTag.SINGLE_LINE_PARAGRAPH;
                                lastPageParagraph = LineTag.SINGLE_LINE_PARAGRAPH;
                            }
                        } else {
                            info.tag = LineTag.SINGLE_LINE_PARAGRAPH;
                        }
                        isFirstParagraph = false;
                        iterator.remove();
                        break;
                    }
                }
            } else {
                for (Iterator<LineInfo> iterator = lineWithoutTag.iterator(); iterator.hasNext(); ) {
                    LineInfo info = iterator.next();
                    for (int i = 0; i < pLines.size(); i++) {
                        TextChunk pLine = pLines.get(i);
                        if (info.textChunk.overlapRatio(pLine) > 0.6) {
                            if (i == 0) {
                                if (info.textChunk.getTop() < 140 && info.textLength >= 30 && isFirstParagraph
                                        && FloatUtils.feq(info.textChunk.getLeft(), pLines.get(i + 1).getLeft(), 3.)
                                        && (lastPageParagraph.equals(LineTag.PARAGRAPH_MIDDLE)
                                        || lastPageParagraph.equals(LineTag.PARAGRAPH_START))) {
                                    info.tag = LineTag.PARAGRAPH_MIDDLE;
                                } else {
                                    info.tag = LineTag.PARAGRAPH_START;
                                }
                                isFirstParagraph = false;
                            } else if (i == pLines.size() - 1) {
                                if(info.textLength >= 30 && info.textChunk.getBottom() > 700
                                        && pLines.size() >= 2
                                        && FloatUtils.feq(info.textChunk.getRight(), pLines.get(i - 1).getRight(),6.)) {
                                    info.tag = LineTag.PARAGRAPH_MIDDLE;
                                    lastPageParagraph = info.tag;
                                } else {
                                    info.tag = LineTag.PARAGRAPH_END;
                                    if (info.textChunk.getBottom() > 700) {
                                        lastPageParagraph = info.tag;
                                    }
                                }
                            } else {
                                info.tag = LineTag.PARAGRAPH_MIDDLE;
                            }
                            iterator.remove();
                            break;
                        }
                    }
                }
            }
        }

        if (contentPage.getHeader() != null) {
            List<LineInfo> headerLines = lineInfos.stream()
                    .filter(lineInfo -> lineInfo.textChunk.selfOverlapRatio(contentPage.getHeader()) > 0.8)
                    .collect(Collectors.toList());
            headerLines.forEach(lineInfo -> lineInfo.tag = LineTag.PARAGRAPH_HEADER);
        }

        if (contentPage.getFooter() != null) {
            List<LineInfo> footerLines = lineInfos.stream()
                    .filter(lineInfo -> lineInfo.textChunk.selfOverlapRatio(contentPage.getFooter()) > 0.8)
                    .collect(Collectors.toList());
            footerLines.forEach(lineInfo -> lineInfo.tag = LineTag.PARAGRAPH_FOOTER);
        }

        if (contentPage.getHeader() != null) {
            tables = tables.stream()
                    .filter(table->table.overlapRatio(contentPage.getHeader()) < 0.1)
                    .collect(Collectors.toList());
        }
        if (contentPage.getFooter() != null) {
            tables = tables.stream()
                    .filter(table-> table.overlapRatio(contentPage.getFooter()) < 0.1)
                    .collect(Collectors.toList());
        }
        for (Rectangle table : tables) {
            List<LineInfo> tableLines = lineInfos.stream()
                    .filter(lineInfo -> lineInfo.textChunk.selfOverlapRatio(table) > 0.8)
                    .collect(Collectors.toList());
            if (tableLines.size() == 1) {
                tableLines.get(0).tag = LineTag.SINGLE_LINE_TABLE;
            } else {
                for (int i = 0; i < tableLines.size(); i++) {
                    LineInfo tLine = tableLines.get(i);
                    if (i == 0) {
                        tLine.tag = LineTag.TABLE_START;
                    } else if (i == tableLines.size() - 1) {
                        tLine.tag = LineTag.TABLE_END;
                    } else {
                        tLine.tag = LineTag.TABLE_MIDDLE;
                    }
                }
            }
        }
        for (Rectangle chart : charts) {
            List<LineInfo> chartLines = lineInfos.stream()
                    .filter(lineInfo -> lineInfo.textChunk.selfOverlapRatio(chart) > 0.8)
                    .collect(Collectors.toList());
            if ((chartLines.size() == 1) && (!tablePage.params.useCRFTableLayout)) {//CRF模型的时候不需要这部分信息所以不需要抛异常
                throw new RuntimeException("chart only has one text");
            } else {
                for (int i = 0; i < chartLines.size(); i++) {
                    LineInfo tLine = chartLines.get(i);
                    if (i == 0) {
                        tLine.tag = LineTag.CHART_START;
                    } else if (i == chartLines.size() - 1) {
                        tLine.tag = LineTag.CHART_END;
                    } else {
                        tLine.tag = LineTag.CHART_MIDDLE;
                    }
                }
            }
        }

        Example example = buildExample(lineInfos);
        try {
            tfRecordWriter.write(example);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<LineInfo> buildLineInfos(ContentGroupPage tablePage, Page contentPage, List<TextChunk> mergedLines) {
        float pageWidth, pageHeight;
        if (contentPage != null) {
            pageWidth = contentPage.getPageWidth();
            pageHeight = contentPage.getPageHeight();
        } else if (tablePage != null) {
            pageWidth = (float)tablePage.getWidth();
            pageHeight = (float)tablePage.getHeight();
        } else {
            return new ArrayList<>();
        }
        if (mergedLines.isEmpty()) {
            return new ArrayList<>();
        }

        float left = Float.MAX_VALUE;
        float right = Float.MIN_VALUE;
        for (TextChunk line : mergedLines) {
            left = Math.min(left, line.getTextLeft());
            right = Math.max(right, line.getTextRight());
        }

        List<Ruling> horizontalRulings = (tablePage != null) ? tablePage.getHorizontalRulings() : null;
        List<Ruling> verticalRulings = (tablePage != null) ? tablePage.getVerticalRulings() : null;

        List<FillAreaGroup> fillAreaGroups;
        if (tablePage != null && !tablePage.getPageFillAreaGroup().isEmpty()) {
            fillAreaGroups = tablePage.getPageFillAreaGroup();
        } else {
            fillAreaGroups = null;
        }

        //有效文本宽度
        Rectangle allTextBound = Rectangle.boundingBoxOf(mergedLines);
        float textValidWidth = (float) allTextBound.getWidth();
        //被多条Ruling包围的区域
        List<TableRegion> rulingRegions;
        if (tablePage != null && !tablePage.getRawLineTableRegions().isEmpty()) {
            rulingRegions = tablePage.getRawLineTableRegions();
        } else {
            rulingRegions = null;
        }

        List<LineInfo> lineInfos = new ArrayList<>(mergedLines.size());
        for (int i = 0; i < mergedLines.size(); i++) {
            TextChunk line = mergedLines.get(i);
            TextChunk prev = i > 0 ? mergedLines.get(i - 1) : null;
            TextChunk next = i < mergedLines.size() - 1 ? mergedLines.get(i + 1) : null;
            LineInfo info = new LineInfo();

            //字符和字体颜色等信息
            info.textChunk = line;
            info.setText(line.getText());
            info.fontSize = line.getMostCommonFontSize();
            info.bold = line.isBold();
            info.italic = line.isItalic();
            info.avgTextGap = line.getAvgTextGap();

            //坐标信息
            info.left = line.getTextLeft() / pageWidth;
            info.top = line.getTop() / pageHeight;
            info.width = (float) line.getWidth() / pageWidth;
            info.height = (float) line.getHeight() / pageHeight;
            info.leftDiff = (line.getTextLeft() - left) / pageWidth;
            info.rightDiff = (right - line.getTextRight()) / pageWidth;
            if (prev != null) {
                info.prevLeftDiff = (line.getTextLeft() - prev.getTextLeft()) / pageWidth;
                info.prevRightDiff = (line.getTextRight() - prev.getTextRight()) / pageWidth;
                info.prevTopDiff = (line.getTop() - prev.getTop()) / pageHeight;
                info.prevBottomDiff = (line.getBottom() - prev.getBottom()) / pageHeight;
            }
            if (next != null) {
                info.nextLeftDiff = (next.getTextLeft() - line.getTextLeft()) / pageWidth;
                info.nextRightDiff = (next.getTextRight() - line.getTextRight()) / pageWidth;
                info.nextTopDiff = (next.getTop() - line.getTop()) / pageHeight;
                info.nextBottomDiff = (next.getBottom() - line.getBottom()) / pageHeight;
            }

            //Ruling信息
            if (horizontalRulings != null) {
                float searchHeight = 2.5f * tablePage.getAvgCharHeight();
                float minRulingLength = 10 * tablePage.getAvgCharWidth();
                float searchMinY = tablePage.getPaper().topMarginInPt;
                float searchMaxY = (float)tablePage.getHeight() - tablePage.getPaper().bottomMarginInPt;

                if (prev == null) {
                    info.hasTopRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > searchMinY && ruling.getBottom() < line.getTop()
                            && line.getTop() - ruling.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                } else {
                    info.hasTopRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > prev.getBottom() && ruling.getBottom() < line.getTop()
                            && line.getTop() - ruling.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                }
                if (next == null) {
                    info.hasBottomRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > line.getBottom() && ruling.getBottom() < searchMaxY
                            && ruling.getTop() - line.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                } else {
                    info.hasBottomRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > line.getBottom() && ruling.getBottom() < next.getTop()
                            && ruling.getTop() - line.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                }
            }
            //空格行信息
            if(prev == null){
                info.hasTopSpaceLine=false;
            }
            else{
                if((line.getCenterY()-prev.getCenterY())>=Math.min(line.getAvgCharHeight(),prev.getAvgCharHeight())*3){
                    info.hasTopSpaceLine=true;
                }
            }
            if(next == null){
                info.hasBottomSpaceLine=false;
            }
            else{
                if((next.getCenterY()-line.getCenterY())>=Math.min(line.getAvgCharHeight(),next.getAvgCharHeight())*3){
                    info.hasBottomSpaceLine=true;
                }
            }
            //填充色信息
            if (fillAreaGroups != null) {
                info.hasFillArea = fillAreaGroups.stream().filter(fillAreaGroup -> fillAreaGroup.getGroupRectArea().overlapRatio(line, false) > 0.5
                        && fillAreaGroup.getWidth() > 0.5 * textValidWidth).collect(Collectors.toList()).size() > 0;
            }

            //有线框区域信息
            if (rulingRegions != null) {
                info.hasRulingRegion = rulingRegions.stream().filter(rulingRegion -> rulingRegion.nearlyContains(line))
                        .collect(Collectors.toList()).size() > 0;
            }

            /*//文本行内部间隔和直线信息
            info.girdInfo = new ArrayList<>(2 * gridNum);
            float stepLength = textValidWidth / gridNum;
            List<TextElement> mutableElements = line.getMutableElements();
            TableRegionDetectionUtils.filterBlankElement(mutableElements);
            List<Rectangle> projects = TableRegionDetectionUtils.calVerticalProjection(mutableElements);
            for (int j = 0; j < gridNum; j++) {
                Rectangle stepRect = new Rectangle((float)allTextBound.getMinX() + j * stepLength, line.getTop()
                        , stepLength, line.getHeight());
                //网格内是否存在字符且字符所占宽度比率大于0.3
                if (line.getElements() != null) {
                    if (TableRegionDetectionUtils.getNoRepoProjectWidth(projects, stepRect) > 0.3 * stepLength) {
                        info.girdInfo.add(1L);
                    } else {
                        info.girdInfo.add(0L);
                    }
                } else {
                    info.girdInfo.add(0L);
                }
                //网格内是否存在垂直线，这里不再考虑水平线
                if (verticalRulings != null) {
                    List<Ruling> innerVerticalRulings = Ruling.getRulingsFromArea(verticalRulings, stepRect);
                    if (innerVerticalRulings.size() > 0) {
                        info.girdInfo.add(1L);
                    } else {
                        info.girdInfo.add(0L);
                    }
                } else {
                    info.girdInfo.add(0L);
                }
            }*/

            lineInfos.add(info);
        }
        return lineInfos;
    }

    public static List<LineInfo> buildLineInfosForLayout(ContentGroupPage tablePage, Page contentPage, Page.TextGroup textGroup) {
        float pageWidth, pageHeight;
        if (contentPage != null) {
            pageWidth = contentPage.getPageWidth();
            pageHeight = contentPage.getPageHeight();
        } else if (tablePage != null) {
            pageWidth = (float)tablePage.getWidth();
            pageHeight = (float)tablePage.getHeight();
        } else {
            return new ArrayList<>();
        }
        float groupWidth = textGroup.width;
        float groupHeight = textGroup.height;

        float tempLeft = Float.MAX_VALUE;
        float tempRight = Float.MIN_VALUE;
        float left = 0.f;
        float right = 0.f;
        List<TextChunk> lines = textGroup.getTextChunks();
        NumberUtil.sortByReadingOrder(lines);
        List<TextChunk> mergedLines = ParagraphMerger.mergeToLine(lines);

        //当走生成TFRecord流程来获取表格数据的时候或者当表格CRF模型载入已经训练好的流程的时候
        if((tablePage!=null) && ((tablePage.params.useCRFTableLayout) || (tablePage.params.useCRFModel))){
            pageWidth = (float)tablePage.getWidth();
            pageHeight = (float)tablePage.getHeight();
            List<TextBlock> textLines = TextMerger.collectByRows(TextMerger.groupByBlock(textGroup.getTextChunks(), tablePage.getHorizontalRulings(), tablePage.getVerticalRulings()));
            //去掉页眉页脚里的表格行,防止一部分误检
            mergedLines=textLines.stream().map(TextBlock::toTextChunk).collect(Collectors.toList());
            //此处需要重新排序
            NumberUtil.sortByReadingOrder(mergedLines);
        }

        textGroup.setTexts(mergedLines);
        for (TextChunk line : mergedLines) {
            tempLeft = Float.min(tempLeft, line.getTextLeft());
            tempRight = Float.max(tempRight, line.getTextRight());
        }
        float finalTempRight = tempRight;
        float finalTempLeft = tempLeft;
        List<TextChunk> tempRightMergedLines = mergedLines.stream()
                .filter(textChunk -> {
                    float tempWidth = textChunk.getAvgCharWidth();
                    return FloatUtils.fgte(textChunk.getRight(), finalTempRight - tempWidth, tempWidth * 0.1);
                })
                .collect(Collectors.toList());
        List<TextChunk> tempLeftMergedLines = mergedLines.stream()
                .filter(textChunk ->
                {
                    float tempWidth = textChunk.getAvgCharWidth();
                    return FloatUtils.flte(textChunk.getLeft(), finalTempLeft + tempWidth, tempWidth * 0.1);
                })
                .collect(Collectors.toList());
        for (TextChunk textChunk : tempLeftMergedLines) {
            left += textChunk.getLeft();
        }
        for (TextChunk textChunk : tempRightMergedLines) {
            right += textChunk.getRight();
        }
        if (tempLeftMergedLines.size() > 0) {
            left /= tempLeftMergedLines.size();
        }
        if (tempRightMergedLines.size() > 0) {
            right /= tempRightMergedLines.size();
        }
        List<LineInfo> lineInfos = new ArrayList<>(mergedLines.size());
        List<Ruling> horizontalRulings;
        List<Ruling> verticalRulings;
        if (tablePage != null && tablePage.getHorizontalRulings() != null && !tablePage.getHorizontalRulings().isEmpty()) {
            horizontalRulings = tablePage.getHorizontalRulings();
        } else {
            horizontalRulings = null;
        }
        if (tablePage != null && tablePage.getVerticalRulings() != null && !tablePage.getVerticalRulings().isEmpty()) {
            verticalRulings = tablePage.getVerticalRulings();
        } else {
            verticalRulings = null;
        }
        List<FillAreaGroup> fillAreaGroups;
        if (tablePage != null && !tablePage.getPageFillAreaGroup().isEmpty()) {
            fillAreaGroups = tablePage.getPageFillAreaGroup();
        } else {
            fillAreaGroups = null;
        }
        //有效文本宽度
        double textValidWidth = Rectangle.boundingBoxOf(mergedLines).getWidth();
        //被多条Ruling包围的区域
        List<TableRegion> rulingRegions;
        if (tablePage != null && !tablePage.getRawLineTableRegions().isEmpty() && false) {
            rulingRegions = tablePage.getRawLineTableRegions();
        } else {
            rulingRegions = null;
        }

        for (int i = 0; i < mergedLines.size(); i++) {
            TextChunk line = mergedLines.get(i);
            TextChunk prev = i > 0 ? mergedLines.get(i - 1) : null;
            TextChunk next = i < mergedLines.size() - 1 ? mergedLines.get(i + 1) : null;
            LineInfo info = new LineInfo();

            //字符和字体颜色等信息
            info.textChunk = line;
            info.setText(line.getText());
            info.fontSize = line.getAvgCharHeight();
            info.bold = line.isBold();
            info.italic = line.isItalic();

            //坐标信息
            info.left = line.getTextLeft() / pageWidth;
            info.top = line.getTop() / pageHeight;
            info.width = (float) line.getWidth() / pageWidth;
            info.height = (float) line.getHeight() / pageHeight;
            info.leftDiff = (line.getTextLeft() - left) / pageWidth;
            info.rightDiff = (right - line.getTextRight()) / pageWidth;
            if (prev != null) {
                info.prevLeftDiff = (line.getTextLeft() - prev.getTextLeft()) / pageWidth;
                info.prevRightDiff = (line.getTextRight() - prev.getTextRight()) / pageWidth;
                info.prevTopDiff = (line.getTop() - prev.getTop()) / pageHeight;
                info.prevBottomDiff = (line.getBottom() - prev.getBottom()) / pageHeight;
            }
            if (next != null) {
                info.nextLeftDiff = (next.getTextLeft() - line.getTextLeft()) / pageWidth;
                info.nextRightDiff = (next.getTextRight() - line.getTextRight()) / pageWidth;
                info.nextTopDiff = (next.getTop() - line.getTop()) / pageHeight;
                info.nextBottomDiff = (next.getBottom() - line.getBottom()) / pageHeight;
            }

            //相对版面的信息
            info.leftLayout = line.getTextLeft() / groupWidth;
            info.topLayout = line.getTop() / groupHeight;
            info.widthLayout = (float) line.getWidth() / groupWidth;
            info.heightLayout = (float) line.getHeight() / groupHeight;
            info.leftDiffLayout = (line.getTextLeft() - left) / groupWidth;
            info.rightDiffLayout = (right - line.getTextRight()) / groupWidth;
            if (prev != null) {
                info.prevLeftDiffLayout = (line.getTextLeft() - prev.getTextLeft()) / groupWidth;
                info.prevRightDiffLayout = (line.getTextRight() - prev.getTextRight()) / groupWidth;
                info.prevTopDiffLayout = (line.getTop() - prev.getTop()) / groupHeight;
                info.prevBottomDiffLayout = (line.getBottom() - prev.getBottom()) / groupHeight;
            }
            if (next != null) {
                info.nextLeftDiffLayout = (next.getTextLeft() - line.getTextLeft()) / groupWidth;
                info.nextRightDiffLayout = (next.getTextRight() - line.getTextRight()) / groupWidth;
                info.nextTopDiffLayout = (next.getTop() - line.getTop()) / groupHeight;
                info.nextBottomDiffLayout = (next.getBottom() - line.getBottom()) / groupHeight;
            }

            //Ruling信息
            if (horizontalRulings != null) {
                float searchHeight = (float) 1.5 * tablePage.getAvgCharHeight();
                float minRulingLength = 5 * tablePage.getAvgCharWidth();
                float searchMinY = tablePage.getPaper().topMarginInPt;
                float searchMaxY = (float)tablePage.getHeight() - tablePage.getPaper().bottomMarginInPt;

                if (prev == null) {
                    info.hasTopRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && (ruling.getTop() > searchMinY || searchMinY-ruling.getTop()<1) && ruling.getBottom() < line.getCenterY()
                            && line.getTop() - ruling.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                } else {
                    info.hasTopRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > prev.getCenterY() && ruling.getBottom() < line.getCenterY()
                            && line.getTop() - ruling.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                }
                if (next == null) {
                    info.hasBottomRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > line.getCenterY() && ruling.getBottom() < searchMaxY
                            && ruling.getTop() - line.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                } else {
                    info.hasBottomRuling = horizontalRulings.stream().filter(ruling -> ruling.length() > minRulingLength
                            && ruling.getTop() > line.getCenterY() && ruling.getBottom() < next.getCenterY()
                            && ruling.getTop() - line.getBottom() < searchHeight
                            && ruling.toRectangle().horizontallyOverlapRatio(line.toRectangle()) > 0.5)
                            .collect(Collectors.toList()).size() > 0;
                }
            }
            //空格行信息
            if(prev == null){
                info.hasTopSpaceLine=false;
            }
            else{
                if((line.getTop()-prev.getBottom())>Math.min(line.getAvgCharHeight(),prev.getAvgCharHeight())*2){
                    info.hasTopSpaceLine=true;
                }
            }
            if(next == null){
                info.hasBottomSpaceLine=false;
            }
            else{
                if((next.getTop()-line.getBottom())>Math.min(line.getAvgCharHeight(),next.getAvgCharHeight())*2){
                    info.hasBottomSpaceLine=true;
                }
            }
            //填充色信息
            if (fillAreaGroups != null) {
                info.hasFillArea = fillAreaGroups.stream().filter(fillAreaGroup -> fillAreaGroup.getGroupRectArea().overlapRatio(line, false) > 0.5
                        && fillAreaGroup.getWidth() > 0.5 * textValidWidth).collect(Collectors.toList()).size() > 0;
            }

            //有线框区域信息
            if (rulingRegions != null) {
                info.hasRulingRegion = rulingRegions.stream().filter(rulingRegion -> rulingRegion.nearlyContains(line))
                        .collect(Collectors.toList()).size() > 0;
            }
            //上下行间的空格占比
            info.prevSpaceNumRatio=getSpaceNumRatio(line,prev);
            info.nextSpaceNumRatio=getSpaceNumRatio(line,next);
            info.hasPrevSimilarTableLine=hasSimilarTableLayout(tablePage,mergedLines,i,false);
            info.hasNextSimilarTableLine=hasSimilarTableLayout(tablePage,mergedLines,i,true);
            info.isIndent = isIndent(line, left);
            info.isEndInAdVance = isEndInAdVance(line, right);
            info.prevMargin = getPrevMargin(line, prev, textGroup);
            info.nextMargin = getNextMargin(line, next, textGroup);
            //是否存在表格结束的关键字
            if ((info.rawText!=null) && (info.rawText.length()>0)) {
                info.hasTableEndKeyWord = TABLE_END_KEYWORD_RE.matcher(info.rawText).find();
            }
            if(tablePage != null) {
                //由于行合并导致的,将多列合并为一列
                info.numberTextChunks = tablePage.getTextChunks(info.getTextChunk()).size();
            }
            if(verticalRulings !=null){
                info.numberVerticalLines =  verticalRulings.stream().filter(ruling -> ruling.intersects(info.getTextChunk())).collect(Collectors.toList()).size();
            }

            lineInfos.add(info);
        }
        return lineInfos;
    }
    private static float getSpaceNumRatio(TextChunk curent,TextChunk other){
        if((other == null) || (curent == null)){
            return 0;
        }
        String curLine = curent.getText();
        String otherLine = other.getText();
        int curSpaceNum = 0;
        int otherSpaceNum = 0;
        for (int i = 0; i < curLine.length(); i++) {
            char c = curLine.charAt(i);
            if (c == ' ') {
                curSpaceNum++;
            }
        }
        for (int i = 0; i < otherLine.length(); i++) {
            char c = otherLine.charAt(i);
            if (c == ' ') {
                otherSpaceNum++;
            }
        }
        if(curSpaceNum == 0){
            return 0;
        }
        return (float)Math.min(otherSpaceNum, curSpaceNum) / Math.max(curSpaceNum, otherSpaceNum);
    }

    private static float getPrevMargin(TextChunk textChunk, TextChunk otherChunk, Page.TextGroup textGroup) {
        if (otherChunk == null ) {
            return Math.abs(textChunk.getTop() - textGroup.getTop());
        }
        return Math.abs(textChunk.getTop() - otherChunk.getBottom());
    }

    private static float getNextMargin(TextChunk textChunk, TextChunk otherChunk, Page.TextGroup textGroup) {
        if (otherChunk == null) {
            return Math.abs(textGroup.getBottom() - textChunk.getBottom());
        }
        return Math.abs(otherChunk.getTop() - textChunk.getBottom());
    }

    private static boolean hasSimilarTextChunks(ContentGroupPage tablePage,TextChunk curent,TextChunk other){
        if((curent==null) || (other == null)){
            return false;
        }

        //互相间空格数量占比超过50%
        float SpaceNumRatio = getSpaceNumRatio(curent,other);
        if(SpaceNumRatio < 0.4){
            return false;
        }
        int currentColumn = 1;
        int otherColumn = 1;
        //查找文本块的数量,只所以不用curChunks.size(),是因为存在文本行的合并
        Matcher matcher = MULTIPLE_SAPCE.matcher(curent.getText());
        while (matcher.find()) {
            currentColumn++;
        }
        matcher = MULTIPLE_SAPCE.matcher(other.getText());
        while (matcher.find()) {
            otherColumn++;
        }

        if(otherColumn != currentColumn){
            return false;
        }

        List<TextChunk> curChunks = tablePage.getTextChunks(curent.toRectangle());
        List<TextChunk> otherChunks = tablePage.getTextChunks(other.toRectangle());
        int num=0;
        for(int i=0; i < curChunks.size(); i++){
            for(int j=0; j < otherChunks.size(); j++){
                //至少存在2个单元格水平相交超过80%
                if((curChunks.get(i).toRectangle().horizontallyOverlapRatio(otherChunks.get(j))>=0.8)
                        && (curChunks.get(i).getDirection()==otherChunks.get(j).getDirection())
                        && (curChunks.get(i).getDirection()== TextDirection.LTR)
                        && (curChunks.size()>=2)
                        && (otherChunks.size()>=2))
                    num++;
                if(num >= 2){
                    return true;
                }
            }
        }
        return false;
    }

    //上下行间是否存在相似的表格行布局
    public static boolean hasSimilarTableLayout(ContentGroupPage tablePage,List<TextChunk> lines, int index, boolean flag){
        if((lines.size() == 0) || (tablePage == null)){
            return false;
        }
        //往后搜索
        if(flag){
            TextChunk current = lines.get(index);
            TextChunk nextOne = index < lines.size()-1 ? lines.get(index+1) : null;
            TextChunk nextTwo = index < lines.size()-2 ? lines.get(index+2) : null;
            TextChunk nextThre = index < lines.size()-3 ? lines.get(index+3) : null;
            if(hasSimilarTextChunks(tablePage,current,nextOne) || hasSimilarTextChunks(tablePage,current,nextTwo) || hasSimilarTextChunks(tablePage,current,nextThre)){
                return true;
            }
        }else {
            TextChunk current = lines.get(index);
            TextChunk prevOne = index > 0 ? lines.get(index-1) : null;
            TextChunk prevTwo = index > 1 ? lines.get(index-2) : null;
            TextChunk prevThre = index > 2 ? lines.get(index-3) : null;
            if(hasSimilarTextChunks(tablePage,current,prevOne) || hasSimilarTextChunks(tablePage,current,prevTwo) || hasSimilarTextChunks(tablePage,current,prevThre)){
                return true;
            }
        }
        return false;
    }

    public static boolean isIndent(TextChunk line, float left) {
        if (line.getText().isEmpty()) {
            return false;
        }
        float tempWith = line.getAvgCharWidth();
        return FloatUtils.fgte(line.getLeft(), left + tempWith * 1.5, tempWith * 0.1);
    }

    public static boolean isEndInAdVance(TextChunk line, float right) {
        if (line.getText().isEmpty()) {
            return false;
        }
        float tempWith = line.getAvgCharWidth();
        return FloatUtils.flte(line.getRight(),right - tempWith * 0.5, tempWith * 0.05f);
    }

    public static Example buildExample(List<LineInfo> lineInfos) {
        int lineCount = lineInfos.size();
        return Example.newBuilder()
                .setFeatures(Features.newBuilder()
                        .putFeature("line_count", org.tensorflow.util.Feature.wrap(lineCount))
                        .putFeature("text", org.tensorflow.util.Feature.wrapString(
                                lineInfos.stream().map(lineInfo -> lineInfo.text).collect(Collectors.toList())
                        ))
                        .putFeature("text_length", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> (long)lineInfo.textLength).collect(Collectors.toList())
                        ))
                        .putFeature("avg_text_gap", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.avgTextGap).collect(Collectors.toList())
                        ))
                        .putFeature("tags", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> (long)lineInfo.tag.ordinal()).collect(Collectors.toList())
                        ))
                        .putFeature("bold", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.bold ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("italic", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.italic ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("font_size", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.fontSize).collect(Collectors.toList())
                        ))
                        .putFeature("left", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.left).collect(Collectors.toList())
                        ))
                        .putFeature("top", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.top).collect(Collectors.toList())
                        ))
                        .putFeature("width", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.width).collect(Collectors.toList())
                        ))
                        .putFeature("height", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.height).collect(Collectors.toList())
                        ))
                        .putFeature("left_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.leftDiff).collect(Collectors.toList())
                        ))
                        .putFeature("right_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.rightDiff).collect(Collectors.toList())
                        ))
                        .putFeature("prev_left_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevLeftDiff).collect(Collectors.toList())
                        ))
                        .putFeature("prev_right_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevRightDiff).collect(Collectors.toList())
                        ))
                        .putFeature("prev_top_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevTopDiff).collect(Collectors.toList())
                        ))
                        .putFeature("prev_bottom_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevBottomDiff).collect(Collectors.toList())
                        ))
                        .putFeature("next_left_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextLeftDiff).collect(Collectors.toList())
                        ))
                        .putFeature("next_right_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextRightDiff).collect(Collectors.toList())
                        ))
                        .putFeature("next_top_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextTopDiff).collect(Collectors.toList())
                        ))
                        .putFeature("next_bottom_diff", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextBottomDiff).collect(Collectors.toList())
                        ))
                        .putFeature("column_count", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> (long)lineInfo.columnCount).collect(Collectors.toList())
                        ))
                        .putFeature("has_top_ruling", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasTopRuling ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("has_bottom_ruling", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasBottomRuling ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("number_num", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> (long)lineInfo.numberNum).collect(Collectors.toList())
                        ))
                        .putFeature("number_ratio", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.numberRatio).collect(Collectors.toList())
                        ))
                        .putFeature("pattern_match_result", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().flatMap(lineInfo -> lineInfo.patternMatchResult.stream())
                                        .collect(Collectors.toList())
                        ))
                        .putFeature("has_fill_area", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasFillArea ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("has_ruling_region", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasRulingRegion ? 1L : 0L).collect(Collectors.toList())
                        ))
                        /*.putFeature("grid_info", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().flatMap(lineInfo -> lineInfo.girdInfo.stream())
                                        .collect(Collectors.toList())
                        ))*/
                        .putFeature("left_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.leftLayout).collect(Collectors.toList())
                        ))
                        .putFeature("top_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.topLayout).collect(Collectors.toList())
                        ))
                        .putFeature("width_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.widthLayout).collect(Collectors.toList())
                        ))
                        .putFeature("height_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.heightLayout).collect(Collectors.toList())
                        ))
                        .putFeature("left_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.leftDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("right_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.rightDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("pre_left_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevLeftDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("pre_right_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevRightDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("pre_top_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevTopDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("pre_bottom_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevBottomDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("next_left_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextLeftDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("next_right_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextRightDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("next_top_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextTopDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("next_bottom_diff_layout", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextBottomDiffLayout).collect(Collectors.toList())
                        ))
                        .putFeature("has_bottom_space_line", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasBottomSpaceLine ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("has_top_space_line", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasTopSpaceLine ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("has_prev_similar_table_line", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasPrevSimilarTableLine ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("has_next_similar_table_line", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasNextSimilarTableLine ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("prev_space_num_ratio", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevSpaceNumRatio).collect(Collectors.toList())
                        ))
                        .putFeature("next_space_num_ratio", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextSpaceNumRatio).collect(Collectors.toList())
                        ))
                        .putFeature("has_table_end_key_word", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.hasTableEndKeyWord ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("prev_margin", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.prevMargin).collect(Collectors.toList())
                        ))
                        .putFeature("next_margin", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.nextMargin).collect(Collectors.toList())
                        ))
                        .putFeature("is_indent", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.isIndent ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("is_end_in_advance", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> lineInfo.isEndInAdVance ? 1L : 0L).collect(Collectors.toList())
                        ))
                        .putFeature("number_text_chunks", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> (long)lineInfo.numberTextChunks).collect(Collectors.toList())
                        ))
                        .putFeature("number_vertical_lines", org.tensorflow.util.Feature.wrapLong(
                                lineInfos.stream().map(lineInfo -> (long)lineInfo.numberVerticalLines).collect(Collectors.toList())
                        ))
                        .putFeature("space_ratio", org.tensorflow.util.Feature.wrapFloat(
                                lineInfos.stream().map(lineInfo -> lineInfo.spaceRatio).collect(Collectors.toList())
                        ))
                        .build())
                .build();
    }

    public static Example buildPageExample(Page page, List<TextChunk> lines) {
        List<LineInfo> lineInfos = buildLineInfos(null , page, lines);
        return buildExample(lineInfos);
    }

    public static Example buildPageExample(ContentGroupPage contentGroupPage, Page page, Page.TextGroup textGroup) {
        List<LineInfo> lineInfos = buildLineInfosForLayout(contentGroupPage, page, textGroup);
        return buildExample(lineInfos);
    }

    public void close() {
        tfRecordWriter.close();
    }

    public int getRecordCount() {
        return 0;
    }

}
