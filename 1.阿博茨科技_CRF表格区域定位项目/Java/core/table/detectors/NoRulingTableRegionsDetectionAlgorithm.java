package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.extractors.CellFillAlgorithm;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.FastMath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class NoRulingTableRegionsDetectionAlgorithm {

    private enum MergeType {UP, DOWN}

    private List<TableRegion> lineTableRegions = new ArrayList<>();
    private List<TableRegion> rulingRectangles = new ArrayList<>();
    private List<TableRegion> chartRegions = new ArrayList<>();

    private static final Logger logger = LogManager.getLogger(NoRulingTableRegionsDetectionAlgorithm.class);

    List<SegmentPageInfo> segmentResultByTj = new ArrayList<>();

    static final Pattern PARAGRAPH_END_RE = Pattern.compile(".*(\\.|。|;|；)$");
    static final Pattern MEASURE_UNIT_RE = Pattern.compile("(.*((单位|注|人民币|單位|人民幣)([0-9]{1,2})?(：|:)).*)|(.*[(（](续|續)[)）][：:]$)");
    static final Pattern SERIAL_NUMBER_ROW_RE_0 = Pattern.compile("(^\\s*[(（]?[0-9零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）]?(\\s+|[.、]).*)" +
            "|(^\\s*[(（]?[0-9a-zA-Z零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）].*)|(^\\s*[(（](i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)[)）].*)" +
            "|(^\\s*[(（]?(i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)[)）].*)" +
            "|(^\\s*[(（]?[a-zA-Z]{1,2}[)）]?[.、].*)");
    static final Pattern SERIAL_NUMBER_ROW_RE_1 = Pattern.compile("(^[0-9]\\.[0-9]\\s*.+)|(^[0-9]\\.[0-9]\\.[0-9]\\s*.+)");
    static final Pattern SERIAL_NUMBER_ROW_RE_2 = Pattern.compile("(^\\d\\d?\\.[\\S\\s]*\\.$)");
    static final Pattern SERIAL_NUMBER_RE = Pattern.compile("(^\\s*[(（]?[0-9a-zA-Z零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）]?(\\s*|[.、]\\s*)$)" +
            "|(^\\s*[(（][0-9a-zA-Z零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）][.、]?\\s*$)|(^\\s*[(（]?(i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)[)）]?(\\s*|[.、]\\s*)$)");
    static final Pattern SPECIAL_SYMBOL_RE = Pattern.compile("^\\s*[●○•◆◇★*☆※].*");
    static final Pattern SPECIAL_TABLE_RE = Pattern.compile("(.*(如下|如下所示|如下表|如下表所示|见下表)(：|:)$)|(.*下表列出.*)|(^[(（]?(续|續)[)）]?$)|(^[(（](续|續)\\s*\\d[)）]$)");
    static final Pattern SPECIAL_ROW_STR_RE = Pattern.compile("^[(（].+[)）]$");
    static final Pattern APPLICABLE_RE = Pattern.compile("([√□]\\s*(适用|是)\\s*([√□])\\s*(不适用|否))|(.*[√□].*)|(^(附录|附錄).*)");
    static final Pattern PAGE_FOOTER_RE_1 = Pattern.compile("(^[0-9]{1,4}$)|(^第[0-9]{1,4}页)|([0-9]{1,4}/[0-9]{1,4}$)|([i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ]{1,2})|(^(-|–|—)\\s*[0-9]{1,4}\\s*(-|–|—)$)|(^1-1-[0-9]{1,4}$)");//注意(-|–|—)编码有区别
    static final Pattern PAGE_FOOTER_RE_2 = Pattern.compile("^第[0-9]{1,4}页$");
    static final Pattern TABLE_HEAD_RE = Pattern.compile("(^(表|表格)[(（]?[0-9]{1,2}[)）]?(：|:).*)|(^(表|表格)[零一二三四五六七八九十]$)");
    static final Pattern TABLE_END_RE = Pattern.compile("(^合计$)|(^小计$)");
    static final Pattern TABLE_NOTE_RE_1 = Pattern.compile("^[(（]?(附注|附註)[)）]?.*");
    static final Pattern TABLE_TITLE_RE_1 = Pattern.compile(".*(财务报表|资产负债表)[：:]?$");
    static final Pattern SPECIAL_STR_HEAD_RE = Pattern.compile(".*[(（]((金额单位(均为|为)人民币百万元)|(.*([,，]金额单位(均为|为)人民币百万元)))[)）]$");
    static final Pattern SPECIAL_STR_END_RE = Pattern.compile("(.*((编制单位|企业负责人|企业责任人|企业代表人|企业法人|法定代表人|数据来源|资料来源|資料來源)(：|:)).*)|(.*(除特别注明外).*)");
    static final Pattern SPECIAL_STR_CONTENT_RE = Pattern.compile("^((目\\s*(录|錄))|content|contents)$");
    static final Pattern CHINESE_STR_RE = Pattern.compile("[\u4e00-\u9fa5]");
    static final Pattern ENGLISH_STR_RE = Pattern.compile("[A-Za-z]");
    static final Pattern SPLITE_TABLE_FLAG_RE = Pattern.compile("(^\\s*[(（]?[0-9a-zA-Z零一二三四五六七八九十①②③④⑤⑥⑦⑧⑨]{1,2}[)）]?(\\s*|[.、])(.*[^,，、.。日0-9][：:]?$))" +
            "|(^\\s*[(（]?(i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX)[)）]?(\\s*|[.、])(.*[^,，、.。日0-9][：:]?$))" +
            "|(^\\s*[(（](Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)[)）]?(\\s*|[.、])(.*[^,，、.。日0-9][：:]?$))");
    static final Pattern DATA_STR_RE = Pattern.compile("((\\d{4}年(度)?)(\\d{1,2}月)?(\\d{1,2}日)?)|((\\d{4}年(度)?)(\\d{1,2}(-|–|—)\\d{1,2}月))|((\\d{1,2}月)(\\d{1,2}日))|([零一二三四五六七八九]{4}年(度)?)");
    static final Pattern CHART_STR_RE_1 = Pattern.compile("(.*(图表|圖表|chart).*)|(^(图|圖)[(（]?\\s?[0-9]{1,2}[)）]?.*)");
    static final Pattern CHART_STR_RE_2 = Pattern.compile("^\\s*(图|圖)(\\s{0,3})(\\d{1,2})(：|:).*");
    static final Pattern APOSTROPHE_RE = Pattern.compile("((\\.\\s){3,})|(\\.{3,})");

    //keyword pattern
    static final Pattern TABLE_BEGINNING_KEYWORD = Pattern.compile("(^(表|表格)[(（]?[0-9]{1,2}.*)|(^(表|表格)[零一二三四五六七八九十].*)" +
            "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
            "|(^(the following table).*)|(.*(如下表|如下表所示|见下表)(：|:)$)|(^(下表|以下).*(：|:)$)|(.*[(（](总表|附表)[)）]$)" +
            "|(.*下表列出.*)|(.*(table)\\s+\\d{1,2}$)|(.*(as follows)[：:.]$)|(.*(明细单)$)|(^(附表)\\s*\\d{1,2}(：|:).*)");
    static final Pattern CHART_BEGINNING_KEYWORD = Pattern.compile("^(图|圖|图表|圖表|chart|figure)[(（]?\\s?[0-9]{1,2}[)）]?.*");
    static final Pattern TABLE_TITLE_ENGLISH_RE = Pattern.compile(".*(Summary)$");

    static final Pattern TABLE_FILTER_STR_RE =Pattern.compile("(.*(数据来源|资料来源|資料來源|除特别注明外|免责申明|免责条款).*)");

    /**
     * 序号行信息收集及基于页面整体信息序号行判断
     */
    private static class SerialNumberInfo {
        enum PunctuationType {DOT_PUNCTUATION, COMMA_PUNCTUATION, NO_PUNCTUATION}

        enum BracketType {CHINESE_BRACKET, ENGLISH_BRACKET, SEMI_CHINESE_BRACKET, SEMI_ENGLISH_BRACKET, NO_BRACKET}

        enum NumberType {CHINESE_NUMBER, ENGLISH_NUMBER, ARABIA_NUMBER, CIRCLED_NUMBER, ROMAN_NUMBER, SECOND_CHAPTER_NUMBER, THIRD_CHAPTER_NUMBER}

        enum TextLineType {TABLE_LINE, TEXT_LINE}

        TextLineType textLineType;
        PunctuationType punctuationType;
        BracketType bracketType;
        NumberType numberType;
        TextElement numberTextElement;
        boolean isGroup = false;


        public SerialNumberInfo() {
            this.textLineType = null;
            this.isGroup = false;
        }

        public SerialNumberInfo(TextLineType textLineType, PunctuationType punctuationType, BracketType bracketType, NumberType numberType, TextElement numberTextElement) {
            this.textLineType = textLineType;
            this.punctuationType = punctuationType;
            this.bracketType = bracketType;
            this.numberType = numberType;
            this.numberTextElement = numberTextElement;
        }

        public void setTextLineType(TextLineType textLineType) {
            this.textLineType = textLineType;
        }

        public TextLineType getTextLineType() {
            return this.textLineType;
        }

        public void setPunctuationType(PunctuationType punctuationType) {
            this.punctuationType = punctuationType;
        }

        public PunctuationType getPunctuationType() {
            return punctuationType;
        }

        public void setBracketType(BracketType bracketType) {
            this.bracketType = bracketType;
        }

        public BracketType getBracketType() {
            return bracketType;
        }

        public void setNumberType(NumberType numberType) {
            this.numberType = numberType;
        }

        public void setNumberTextElement(TextElement numberTextElement) {
            this.numberTextElement = numberTextElement;
        }

        public void setGroup(boolean group) {
            isGroup = group;
        }

        public static int numberMatchFind(Pattern NUMBER_RE, TextBlock tb, String s, SerialNumberInfo serialNumberInfo, NumberType numberType) {
            Matcher m = NUMBER_RE.matcher(s);
            Character[] punctuationArray = {'（','(','）',')'};
            List<Character> punctuationList = Arrays.asList(punctuationArray);
            if (m.find()) {
                serialNumberInfo.setNumberType(numberType);
                boolean numberFindFlag = false;
                List<TextElement> allTextElements = new ArrayList<>();
                for (TextChunk tc : tb.getElements()) {
                    allTextElements.addAll(tc.getElements());
                }
                for (TextElement te : allTextElements) {
                    if (te.getUnicode().isEmpty()) {
                        continue;
                    }
                    char ch = te.getUnicode().charAt(0);
                    if (Character.isWhitespace(ch) || punctuationList.contains(ch)) {
                        continue;
                    } else {
                        serialNumberInfo.setNumberTextElement(te);
                        numberFindFlag = true;
                        break;
                    }
                }
                if (numberFindFlag) {
                    return m.end();
                } else {
                    return -1;
                }
            } else {
                return -1;
            }
        }

        public static int bracketMatchFind(String s, SerialNumberInfo serialNumberInfo) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch)) {
                    continue;
                }
                if (ch == '(') {
                    serialNumberInfo.setBracketType(BracketType.ENGLISH_BRACKET);
                    return i + 1;
                } else if (ch == '（') {
                    serialNumberInfo.setBracketType(BracketType.CHINESE_BRACKET);
                    return i + 1;
                } else {
                    serialNumberInfo.setBracketType(BracketType.NO_BRACKET);
                    return i;
                }
            }
            return -1;
        }

        public static int punctuationTypeMatchFind(String s, SerialNumberInfo serialNumberInfo) {
            Character[] bracketArray = {'（','(','）',')'};
            List<Character> bracketList = Arrays.asList(bracketArray);
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch) || bracketList.contains(ch)) {
                    if (serialNumberInfo != null && serialNumberInfo.getBracketType() == BracketType.NO_BRACKET ) {
                        if (ch == ')') {
                            serialNumberInfo.setBracketType(BracketType.SEMI_ENGLISH_BRACKET);
                        } else if (ch == '）') {
                            serialNumberInfo.setBracketType(BracketType.SEMI_CHINESE_BRACKET);
                        }
                    }
                    continue;
                }
                if (ch == '.') {
                    serialNumberInfo.setPunctuationType(PunctuationType.DOT_PUNCTUATION);
                    return i + 1;
                } else if (ch == '、') {
                    serialNumberInfo.setPunctuationType(PunctuationType.COMMA_PUNCTUATION);
                    return i + 1;
                } else {
                    serialNumberInfo.setPunctuationType(PunctuationType.NO_PUNCTUATION);
                    return i + 1;
                }
            }
            return -1;
        }
    }

    public boolean isTextDense(ContentGroupPage page, TableRegion rc, float dutyRatio, float denseRatio) {
        List<TextChunk> textChunks = TableRegionDetectionUtils.splitChunkByRow(page.getMutableTextChunks(rc));
        if (textChunks.size() < 3) {
            return true;
        }
        float textWidth = (float) Rectangle.boundingBoxOf(textChunks).getWidth();
        int denseNum = 0;
        for (TextChunk row : textChunks) {
            if (TableRegionDetectionUtils.calDutyRatio(Lists.newArrayList(row), textWidth) > dutyRatio) {
                denseNum++;
            }
        }
        return (float)denseNum / (float)textChunks.size() > denseRatio;
    }

    /**
     * 页面按TJ流切分
     */
    public static class SegmentPageInfo extends Rectangle {
        private List<TextChunk> textChunks = new ArrayList<>();
        private List<TextBlock> contentTextBlocks = new ArrayList<>();

        public SegmentPageInfo(TextChunk textChunk) {
            super(textChunk);
            this.textChunks.add(textChunk);
        }

        public SegmentPageInfo(SegmentPageInfo seg) {
            super(seg);
            this.textChunks.addAll(seg.getTextChunks());
            this.contentTextBlocks.addAll(seg.getContentTextBlocks());
        }

        public List<TextChunk> getTextChunks() {
            return this.textChunks;
        }

        public TextChunk getLastTextChunk() {
            return this.textChunks.get(this.textChunks.size() - 1);
        }

        public void setContentTextBlocks(List<TextBlock> contentTextBlocks) {
            this.contentTextBlocks = contentTextBlocks;
        }

        public List<TextBlock> getContentTextBlocks() {
            return this.contentTextBlocks;
        }

        public void addTextChunkToList(TextChunk tc) {
            if (this.textChunks == null) {
                this.textChunks = new ArrayList<>();
            }

            this.textChunks.add(tc);
            this.textChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
            this.setRect(this.createUnion(tc));
        }

        private static List<TextChunk> getExtraTextChunks(List<TextChunk> allTextChunks, List<TextChunk> existingTextChunks) {
            if (allTextChunks.isEmpty() || existingTextChunks.isEmpty()) {
                return new ArrayList<>();
            }
            List<TextChunk> extraTextChunks = new ArrayList<>();
            List<TextChunk> processedChunks = new ArrayList<>(existingTextChunks);
            Rectangle rect = Rectangle.boundingVisibleBoxOf(existingTextChunks);

            boolean extraFindFlag = true;
            while(extraFindFlag) {
                extraFindFlag = false;
                for (TextChunk tc : allTextChunks) {
                    if (processedChunks.contains(tc)) {
                        continue;
                    }
                    if (rect.intersects(tc.getVisibleBBox())) {
                        extraTextChunks.add(tc);
                        processedChunks.add(tc);
                        rect = new Rectangle(rect.createUnion(tc.getVisibleBBox()));
                        extraFindFlag = true;
                    }
                }
            }

            return extraTextChunks;
        }
    }

    private int findFirstSpaceWithinStr(String str) {
        if (str.trim().isEmpty()) {
            return -1;
        }
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < str.length(); i++) {
            if (StringUtils.isNotBlank(Character.toString(str.charAt(i)))) {
                startIdx = i;
                break;
            }
        }
        for (int i = str.length() - 1; i >= 0; i--) {
            if (StringUtils.isNotBlank(Character.toString(str.charAt(i)))) {
                endIdx = i;
                break;
            }
        }
        if (startIdx != -1 && endIdx != -1) {
            for (int i = startIdx; i <= endIdx; i++) {
                if (StringUtils.isBlank(Character.toString(str.charAt(i)))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean hasSpecialStr(String str, Pattern pattern) {
        return !StringUtils.isEmpty(str) && pattern.matcher(str).find();
    }

    private boolean isWrongTextBlock(TextBlock tb) {
        List<TextChunk> textChunkList = tb.getElements();
        if (textChunkList.size() < 2 || textChunkList.size() > 4) {
            return false;
        }
        for (int i = 0; i < textChunkList.size() - 1; i++) {
            TextChunk one = textChunkList.get(i);
            for (int j = i + 1; j < textChunkList.size(); j++) {
                TextChunk other = textChunkList.get(j);
                if (one.horizontallyOverlapRatio(other) > 0.5 && one.verticalOverlapRatio(other) > 0.5
                        && !this.isUnderline(one) && !this.isUnderline(other)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSerialNumberLine(TextBlock tb) {
        String str = tb.getText().trim();
        if (StringUtils.isBlank(str)) {
            return false;
        }
        if (SERIAL_NUMBER_ROW_RE_0.matcher(str).matches() || SERIAL_NUMBER_ROW_RE_1.matcher(str).matches()) {
            String[] specialStr = {"（", "(", ")", "）","、","."};
            for (String s : specialStr) {
                if (str.contains(s)) {
                    return true;
                }
            }
            String s = tb.getText();
            int spaceIdx = findFirstSpaceWithinStr(s);
            List<TextElement> textElements = tb.toTextChunk().getElements();
            if (spaceIdx != -1 && spaceIdx < textElements.size() && StringUtils.isBlank(textElements.get(spaceIdx).getText())
                    && textElements.get(spaceIdx).getTextWidth() < tb.getAvgCharWidth()) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 将特殊方向的文本块分离出来
     * @param textChunks：所有文本块
     * @param normalChunks：正常方向的文本块
     * @param specialChunks：特殊方向的文本块
     */
    private void splitChunk(List<TextChunk> textChunks, List<TextChunk> normalChunks, List<TextChunk> specialChunks) {
        for (TextChunk chunk : textChunks) {
            if (chunk.isUnnormalDirection()) {
                specialChunks.add(chunk);
            } else {
                normalChunks.add(chunk);
            }
        }
    }

    private float calAvgTextLineGap(List<TableRegion> candidateRegions) {
        if (candidateRegions.isEmpty()) {
            return 0;
        }
        List<Double> gapList = new ArrayList<>();
        for (TableRegion rc : candidateRegions) {
            if (rc.getTextBlockList().size() < 2) {
                continue;
            }
            gapList.addAll(calTextLineGap(rc.getTextBlockList()));
        }
        if (gapList.isEmpty()) {
            return 0;
        }
        return gapList.stream().reduce(0.0, (a, b) -> a + b).floatValue() / gapList.size();
    }

    private List<Double> calTextLineGap(List<TextBlock> candidateTextBlocks) {
        if (candidateTextBlocks.size() < 2) {
            return new ArrayList<>();
        }

        candidateTextBlocks.sort(Comparator.comparing(TextBlock::getTop));
        List<Double> gapList = new ArrayList<>();
        for (int i = 0; i < candidateTextBlocks.size() - 1; i++) {
            gapList.add(FastMath.abs(candidateTextBlocks.get(i + 1).getTop() - candidateTextBlocks.get(i).getBottom()));
        }
        return gapList;
    }

    private boolean isSimilarHeaderAndFooter(List<TextChunk> currentTextChunks, List<TextChunk> preTextChunks) {
        if (currentTextChunks.size() != preTextChunks.size()) {
            return false;
        }

        for (TextChunk tc1 : currentTextChunks) {
            if (tc1.isPagination()) {
                continue;
            }
            String str1 = tc1.getText().trim();
            float avgCharHeight1 = tc1.getAvgCharHeight();
            boolean matched = false;
            for (TextChunk tc2 : preTextChunks) {
                if (tc2.isPagination()) {
                    continue;
                }
                String str2 = tc2.getText().trim();
                float avgCharHeight2 = tc2.getAvgCharHeight();
                boolean isSimilarLocation = FloatUtils.feq(tc1.getCenterY(), tc2.getCenterY(), 0.2 * avgCharHeight1)
                        && FloatUtils.feq(avgCharHeight1, avgCharHeight2, 0.2 * avgCharHeight1);
                if (isSimilarLocation && TableRegionDetectionUtils.stringEqualsIgnoreWhitespace(str1, str2)) {
                    matched = true;
                    tc1.setPaginationType(PaginationType.HEADER);
                    tc2.setPaginationType(PaginationType.HEADER);
                    break;
                } else if (isSimilarLocation && PAGE_FOOTER_RE_1.matcher(str1).matches() && PAGE_FOOTER_RE_1.matcher(str2).matches()) {
                    matched = true;
                    tc1.setPaginationType(PaginationType.FOOTER);
                    tc2.setPaginationType(PaginationType.FOOTER);
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        return true;
    }

    private LinkedHashMap<TextBlock, SerialNumberInfo> collectSerialNumberInfo(ContentGroupPage page, List<TextBlock> textBlockList, float avgTextLineGap) {
        List<TextBlock> snTextBlocks = new ArrayList<>();
        for (TextBlock tb : textBlockList) {
            String s = tb.getText().trim();
            if (SERIAL_NUMBER_ROW_RE_0.matcher(s).matches() || SERIAL_NUMBER_ROW_RE_1.matcher(s).matches()) {
                snTextBlocks.add(tb);
            }
        }
        snTextBlocks.sort(Comparator.comparing(TextBlock::getTop));

        //fetch special string
        Pattern NUMBER_RE_1 = Pattern.compile("^\\s*[0-9]\\.[0-9]");
        Pattern NUMBER_RE_2 = Pattern.compile("^\\s*[0-9]\\.[0-9]\\.[0-9]");
        Pattern NUMBER_RE_3 = Pattern.compile("^\\s*[0-9]{1,2}");
        Pattern NUMBER_RE_4 = Pattern.compile("^\\s*[a-zA-Z]{1,2}");
        Pattern NUMBER_RE_5 = Pattern.compile("^\\s*[一二三四五六七八九十]");
        Pattern NUMBER_RE_6 = Pattern.compile("^\\s*[①②③④⑤⑥⑦⑧⑨]");
        Pattern NUMBER_RE_7 = Pattern.compile("^\\s*[i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ]");

        LinkedHashMap<TextBlock, SerialNumberInfo> snMap = new LinkedHashMap<>();
        for (TextBlock tb : snTextBlocks) {
            String s = tb.getText();
            if (s.length() < 1) {
                continue;
            }

            SerialNumberInfo serialNumberInfo = new SerialNumberInfo();
            //bracket
            int bracketMatchEndIdx = SerialNumberInfo.bracketMatchFind(s, serialNumberInfo);
            if (bracketMatchEndIdx == -1) {
                continue;
            }
            //number:1 | 一 | 1.1 | 1.1.1 | ① | a | vii | VII | Ⅶ |
            int numberMatchEndIdx;
            if (((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_7, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.ROMAN_NUMBER)) != -1)
                    || ((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_6, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.CIRCLED_NUMBER)) != -1)
                    || ((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_5, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.CHINESE_NUMBER)) != -1)
                    || ((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_4, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.ENGLISH_NUMBER)) != -1)
                    || ((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_3, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.ARABIA_NUMBER)) != -1)
                    || ((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_2, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.THIRD_CHAPTER_NUMBER)) != -1)
                    || ((numberMatchEndIdx = SerialNumberInfo.numberMatchFind(NUMBER_RE_1, tb, s.substring(bracketMatchEndIdx), serialNumberInfo, SerialNumberInfo.NumberType.SECOND_CHAPTER_NUMBER)) != -1)) {
                //commma
                int punctuationMatchIdx = SerialNumberInfo.punctuationTypeMatchFind(s.substring(numberMatchEndIdx), serialNumberInfo);
                int otherStartIdx = bracketMatchEndIdx + numberMatchEndIdx + punctuationMatchIdx;
                if (punctuationMatchIdx != -1 && otherStartIdx < s.length()) {
                    snMap.put(tb, serialNumberInfo);
                }
            }
        }

        //filter invalid line: if has not bracket, it must be have large gap space
        Iterator<Map.Entry<TextBlock, SerialNumberInfo>> it = snMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TextBlock, SerialNumberInfo> item = it.next();
            TextBlock tb1 = item.getKey();
            SerialNumberInfo one = item.getValue();
            String s = tb1.getText();
            if (one.getBracketType() == SerialNumberInfo.BracketType.NO_BRACKET && one.getPunctuationType() == SerialNumberInfo.PunctuationType.NO_PUNCTUATION) {
                int spaceIdx = findFirstSpaceWithinStr(s);
                List<TextElement> textElements = tb1.toTextChunk().getElements();
                if (spaceIdx != -1 && spaceIdx < textElements.size() && StringUtils.isBlank(textElements.get(spaceIdx).getText())
                        && textElements.get(spaceIdx).getTextWidth() < tb1.getAvgCharWidth()) {
                    it.remove();
                }
            }
        }
        if (snMap.isEmpty()) {
            return new LinkedHashMap<>();
        }

        //divide into groups
        List<List<TextBlock>> groupList = new ArrayList<>();
        for (Map.Entry<TextBlock, SerialNumberInfo> entry: snMap.entrySet()) {
            TextBlock tb1 = entry.getKey();
            SerialNumberInfo one = entry.getValue();
            if (one.isGroup) {
                continue;
            }
            one.setGroup(true);
            List<TextBlock> group = new ArrayList<>();
            group.add(tb1);
            groupList.add(group);

            for (Map.Entry<TextBlock, SerialNumberInfo> entry2: snMap.entrySet()) {
                TextBlock tb2 = entry2.getKey();
                SerialNumberInfo other = entry2.getValue();
                if (tb1 == tb2 || other.isGroup) {
                    continue;
                }
                if (one.numberTextElement.getFontName() == null || other.numberTextElement.getFontName() == null) {
                    continue;
                }
                if (one.numberType == other.numberType && one.bracketType == other.bracketType && one.punctuationType == other.punctuationType
                    && FloatUtils.feq(one.numberTextElement.getFontSize(), other.numberTextElement.getFontSize(), 0.5)
                    && FloatUtils.feq(tb1.getLeft(), tb2.getLeft(), tb1.getAvgCharWidth())) {
                    group.add(tb2);
                    other.setGroup(true);
                }
            }
        }

        Pattern SPECIAL_STR_RE_1 = Pattern.compile("(.*(如下|如下所示|如下表|如下表所示|见下表)(：|:)$)|(.*下表列出.*)|(^[(（]?(续|續)[)）]?$)|(^[(（](续|續)\\s*\\d[)）]$)|(.*[(（](续|續)[)）]$)");
        Pattern SPECIAL_STR_RE_2 = Pattern.compile(".*(合計|合计)$");
        for (List<TextBlock> group : groupList) {
            if (group.size() < 2) {
                continue;
            }
            boolean tableLineMergedFlag = false;
            boolean textLineMergedFlag = false;
            for (TextBlock tb : group) {
                if (tb.getMergeFlag()) {
                    tableLineMergedFlag = true;
                    break;
                }
            }
            if (!tableLineMergedFlag) {
                for (TextBlock tb : group) {
                    //含有特殊字符
                    if (tb.getSize() <= 2 && SPECIAL_STR_RE_1.matcher(tb.getText().trim()).matches()) {
                        textLineMergedFlag = true;
                        break;
                    }
                    List<TextBlock> textChoose = TableRegion.getTopOuterTextBlock(page, tb.toRectangle(), textBlockList, 1);
                    if (textChoose.isEmpty()) {
                        continue;
                    }
                    TextBlock topTextBlock = textChoose.get(0);
                    if (!topTextBlock.getElements().isEmpty() && TableRegionDetectionUtils.isLargeGap(topTextBlock
                            , 2.5f * topTextBlock.getAvgCharWidth(), 1) && TableRegionDetectionUtils.hasNumberStr(topTextBlock.getElements())
                            && TableRegionDetectionUtils.matchAnyText(topTextBlock.getElements(), SPECIAL_STR_RE_2)
                            && (avgTextLineGap > 0.1 && tb.getTop() - topTextBlock.getBottom() > 1.5 * avgTextLineGap)) {
                        textLineMergedFlag = true;
                        break;
                    }
                }

                //含有表头
                if (!textLineMergedFlag) {
                    boolean hasDateLine = false;
                    boolean allIndent = true;
                    for (TextBlock tb : group) {
                        if (tb.getSize() > 2 && TableRegionDetectionUtils.isLargeGap(tb, 2f * tb.getAvgCharWidth(), 1)) {
                            textLineMergedFlag = false;
                            break;
                        }
                        List<TextBlock> textChoose = TableRegion.getBottomOuterTextBlock(page, tb.toRectangle(), textBlockList, 1);
                        if (textChoose.isEmpty()) {
                            textLineMergedFlag = false;
                            break;
                        }
                        TextBlock bottomTextBlock = textChoose.get(0);
                        if (tb.getSize() <= 2 && bottomTextBlock.getLeft() - tb.getLeft() > (Rectangle.union(Arrays.asList(tb.getBounds2D(), bottomTextBlock.getBounds2D())).getWidth() / 3)
                                && !TableRegionDetectionUtils.hasNumberStr(bottomTextBlock.getElements())) {
                            if (!hasDateLine && bottomTextBlock.getSize() >= 2 && TableRegionDetectionUtils.matchAllText(bottomTextBlock.getElements(), DATA_STR_RE)) {
                                hasDateLine = true;
                            }
                        } else {
                            allIndent = false;
                            textLineMergedFlag = false;
                            break;
                        }
                    }
                    if (allIndent && hasDateLine) {
                        textLineMergedFlag = true;
                    }
                }
            }
            if (tableLineMergedFlag) {
                for (TextBlock tb : group) {
                    SerialNumberInfo serialNumberInfo = snMap.get(tb);
                    serialNumberInfo.setTextLineType(SerialNumberInfo.TextLineType.TABLE_LINE);
                    snMap.put(tb, serialNumberInfo);
                }
            } else if (textLineMergedFlag) {
                for (TextBlock tb : group) {
                    SerialNumberInfo serialNumberInfo = snMap.get(tb);
                    serialNumberInfo.setTextLineType(SerialNumberInfo.TextLineType.TEXT_LINE);
                    snMap.put(tb, serialNumberInfo);
                }
            }
        }

        return snMap;
    }

    /**
     * 寻找紧邻某文本行下方的ruling，必须在该文本行及下一行之间
     * @param page
     * @param textBlock
     * @return
     */
    private boolean hasRulingUnderText(ContentGroupPage page, TextBlock textBlock, float maxGap, float minLength) {
        TextChunk underChunk = null;
        float diff = (float) page.getHeight();
        float thresLow = (float) textBlock.getVisibleMaxY();
        for (TextChunk tc : page.getTextChunks()) {
            float visibleMinY = (float) tc.getVisibleMinY();
            if (visibleMinY > thresLow && visibleMinY - thresLow < diff) {
                diff = visibleMinY - thresLow;
                underChunk = tc;
            }
        }

        float thresHigh;
        if (underChunk == null) {
            thresHigh = (float) page.getHeight();
        } else {
            thresHigh = (float) underChunk.getVisibleMinY();
        }

        for (Ruling ruling : page.getHorizontalRulings()) {
            if (ruling.getTop() > thresLow && ruling.getBottom() < thresHigh && ruling.getTop() - thresLow < maxGap
                    && ruling.length() > minLength && textBlock.toRectangle().horizontallyOverlapRatio(ruling.toRectangle()) > 0.5) {
                return true;
            }
        }
        return false;

    }

    /**
     * one需要在other上面
     */
    private static List<Ruling> findRulingBetween(List<Ruling> allRulings, float one, float other) {
        List<Ruling> betweenRulings = new ArrayList<>();
        for (Ruling rul : allRulings) {
            if (rul.getTop() > one && rul.getTop() < other) {
                betweenRulings.add(rul);
            }
        }
        return betweenRulings;
    }

    private List<Ruling> findLongRulingBetween(List<Ruling> allRulings, TextBlock one, TextBlock other, float thres) {
        if (one == null || other == null || one.isEmpty() || other.isEmpty() || allRulings.isEmpty()) {
            return new ArrayList<>();
        }
        List<Ruling> betweenRulings = one.getTop() < other.getTop() ? findRulingBetween(allRulings, (float)one.getCenterY()
                , (float)other.getCenterY()) : findRulingBetween(allRulings, (float)other.getCenterY(), (float)one.getCenterY());
        List<Ruling> findRulings = new ArrayList<>();
        for (Ruling rul : betweenRulings) {
            if (rul.getWidth() > thres) {
                findRulings.add(rul);
            }
        }
        return findRulings;
    }

    public static List<Ruling> findLongRulingBetween(List<Ruling> allRulings, float one, float other, float minLength) {
        if (allRulings.isEmpty()) {
            return new ArrayList<>();
        }
        List<Ruling> betweenRulings = one < other ? findRulingBetween(allRulings, one, other) : findRulingBetween(allRulings, other, one);
        List<Ruling> findRulings = new ArrayList<>();
        for (Ruling rul : betweenRulings) {
            if (rul.getWidth() > minLength) {
                findRulings.add(rul);
            }
        }
        return findRulings;
    }

    private boolean isUnderline(TextChunk textChunk) {
        Pattern UNDERLINE_RE = Pattern.compile("^_{2,}$");
        return UNDERLINE_RE.matcher(textChunk.getText().trim()).matches();
    }

    private double getMinBlankGap(TableRegion candidateRegion) {
        if (candidateRegion.getTextBlockList().size() <= 1) {
            return 0;
        }
        double minBlankGap = Float.MAX_VALUE;
        int num = 0;
        List<TextBlock> textBlockList = candidateRegion.getTextBlockList();
        for (int i = 0; i < textBlockList.size() - 1; i++) {
            double gap = textBlockList.get(i + 1).getTop() - textBlockList.get(i).getBottom();
            if (gap > 0 && gap < minBlankGap) {
                minBlankGap = gap;
                num++;
            }
        }
        return (num > 0) ? minBlankGap : 0;
    }

    private boolean hasTableHeader(ContentGroupPage page, List<Rectangle> otherColumns, TableRegion otherRegion) {
        if (otherColumns.isEmpty() || otherColumns.size() < 2 || otherRegion.getTextBlockList().size() < 2) {
            return false;
        }

        List<TextBlock> textBlockList = otherRegion.getTextBlockList();

        int hasChineseEnglishNum = 0;
        for (int i = 0; i < textBlockList.size() - 1; i++) {
            if (i > 6) {
                return false;
            }
            List<TextChunk> textChunkListOne = new ArrayList<>();
            List<TextChunk> textChunkListOther = new ArrayList<>();
            if (otherColumns.get(0).isHorizontallyOverlap(textBlockList.get(i).getElements().get(0))) {
                if (textBlockList.get(i).getSize() > 1) {
                    textChunkListOne = textBlockList.get(i).getElements().subList(1, textBlockList.get(i).getSize());
                }
            } else {
                textChunkListOne = textBlockList.get(i).getElements();
            }
            if (textChunkListOne.isEmpty()) {
                continue;
            }

            if (otherColumns.get(0).isHorizontallyOverlap(textBlockList.get(i + 1).getElements().get(0))) {
                if (textBlockList.get(i + 1).getSize() > 1) {
                    textChunkListOther = textBlockList.get(i + 1).getElements().subList(1, textBlockList.get(i + 1).getSize());
                }
            } else {
                textChunkListOther = textBlockList.get(i + 1).getElements();
            }
            if (textChunkListOther.isEmpty()) {
                continue;
            }

            StringBuilder sb1 = new StringBuilder();
            for (TextChunk tc : textChunkListOne) {
                sb1.append(tc.getText());
            }
            String one = sb1.toString();
            StringBuilder sb2 = new StringBuilder();
            for (TextChunk tc : textChunkListOther) {
                sb2.append(tc.getText());
            }
            String other = sb2.toString();

            boolean specialOne = TableRegionDetectionUtils.hasCJKEStr(one);
            boolean specialOther = TableRegionDetectionUtils.hasCJKEStr(other);
            hasChineseEnglishNum += (specialOne || specialOther) ? 1 : 0;
            if (specialOne && !specialOther || (hasChineseEnglishNum > 0 && !specialOther)) {
                return true;
            }
        }
        return false;
    }

    public void filterHeaderAndFooter(ContentGroupPage page, List<TextBlock> textLines) {
        List<Ruling> horizontalRulingLines = page.getHorizontalRulings();
        horizontalRulingLines.sort(Comparator.comparing(Ruling::getTop));
        if (textLines.isEmpty()) {
            return;
        }

        float searchHeight = (float) (FastMath.max(page.getPaper().topMarginInPt, (1.0 / 7.0) * page.getHeight()));
        ContentGroupPage prevPage = null;
        if (page.getPrevPage() != null) {
            if (!(page.getPrevPage() instanceof ContentGroupPage)) {
                prevPage = ContentGroupPage.fromPage(page.getPrevPage());
            } else {
                prevPage = (ContentGroupPage) (page.getPrevPage());
            }
        }

        //页眉判断
        if (!page.getPageHeaderDetected()) {
            //结合上一页page信息的页眉判断
            if (prevPage != null && !prevPage.getTextLines().isEmpty() && !textLines.isEmpty() && page.nearlyEquals(prevPage, 1.0f)) {
                TextBlock currentTextBlock = textLines.get(0);
                TextBlock preTextBlock = prevPage.getTextLines().get(0);
                if (currentTextBlock.getBottom() < searchHeight && preTextBlock.getBottom() < searchHeight) {
                    Rectangle currentRect = currentTextBlock.toRectangle().rectReduce(1.0f, 1.0f, page.getWidth(), page.getHeight());
                    Rectangle preRect = preTextBlock.toRectangle().rectReduce(1.0f, 1.0f, page.getWidth(), page.getHeight());
                    List<TextChunk> currentTextChunks = page.getMutableTextChunks(currentRect);
                    List<TextChunk> preTextChunks = prevPage.getMutableTextChunks(preRect);
                    if (isSimilarHeaderAndFooter(currentTextChunks, preTextChunks)) {
                        textLines.get(0).setMergeFlag();
                    }
                }

                //结合页眉线的页眉判断，页眉可居左，居中，居右，最多可两列
                if (!textLines.get(0).getMergeFlag() && !horizontalRulingLines.isEmpty()) {
                    Ruling rul = horizontalRulingLines.get(0);
                    if (textLines.get(0).getSize() <= 2 && rul.getTop() < searchHeight && rul.getWidth() / page.getWidth() > (2.0 / 3.0)
                            && (rul.getTop() - textLines.get(0).getBottom() < 0.5 * page.getAvgCharHeight())
                            && rul.getTop() > textLines.get(0).getBottom() && (textLines.size() == 1
                            || (textLines.size() >= 2 && rul.getTop() < textLines.get(1).getTop()))
                            && !textLines.get(0).getText().contains("目錄")) {
                        textLines.get(0).setMergeFlag();
                    }
                }
            }
        }

        //页脚判断
        if (!page.getPageFooterDetected()) {
            //结合上一页page信息的页脚判断
            if (prevPage != null && !prevPage.getTextLines().isEmpty() && !textLines.isEmpty() && page.nearlyEquals(prevPage, 1.0f)) {
                TextBlock currentTextBlock = textLines.get(textLines.size() - 1);
                TextBlock preTextBlock = prevPage.getTextLines().get(prevPage.getTextLines().size() - 1);
                if (currentTextBlock.getTop() > (page.getHeight() - searchHeight) && preTextBlock.getTop() > (page.getHeight() - searchHeight)) {
                    Rectangle currentRect = currentTextBlock.toRectangle().rectReduce(1.0f, 1.0f, page.getWidth(), page.getHeight());
                    Rectangle preRect = preTextBlock.toRectangle().rectReduce(1.0f, 1.0f, page.getWidth(), page.getHeight());
                    List<TextChunk> currentTextChunks = page.getMutableTextChunks(currentRect);
                    List<TextChunk> preTextChunks = prevPage.getMutableTextChunks(preRect);
                    if (isSimilarHeaderAndFooter(currentTextChunks, preTextChunks)) {
                        textLines.get(textLines.size() - 1).setMergeFlag();
                    }
                }
            }

            //页脚判断：页脚可居左，居中，居右，最多可两列(暂时只考虑一列的情况)
            TextBlock tbTemp = textLines.get(textLines.size() - 1);
            if (!tbTemp.getMergeFlag() && (tbTemp.getTop() > (page.getHeight() - searchHeight))) {
                if (tbTemp.getSize() <= 3 && TableRegionDetectionUtils.matchAnyText(tbTemp.getElements(), PAGE_FOOTER_RE_2)) {
                    tbTemp.setMergeFlag();
                } else if (tbTemp.getSize() == 1 && TableRegionDetectionUtils.matchAllText(tbTemp.getElements(), PAGE_FOOTER_RE_1)) {
                    tbTemp.setMergeFlag();
                }
            }
        }

        textLines.removeIf(TextBlock::getMergeFlag);
    }

    private void filterInvalidTextLines(List<TextBlock> textLines) {
        textLines.removeIf(tb -> CHART_STR_RE_2.matcher(tb.getText().trim()).matches());
    }

    private int calOverlapColumnNum(List<Rectangle> columns, TextBlock checkLine) {
        Rectangle lineBound = checkLine.toRectangle();
        int overlapNum = 0;
        // 如果一列的内容太少不算一列
        for (Rectangle column : columns) {
            if (lineBound.isHorizontallyOverlap(column) && column.getWidth() >= 1.5 * checkLine.getAvgCharWidth()) {
                overlapNum++;
            }
        }
        return overlapNum;
    }

    private int calOverlapColumnNum(List<Rectangle> columns, Rectangle lineBound) {
        int overlapNum = 0;
        // 如果一列的内容太少不算一列
        for (Rectangle column : columns) {
            if (lineBound.isHorizontallyOverlap(column)) {
                overlapNum++;
            }
        }
        return overlapNum;
    }

    private boolean isOverlapSingleColumn(List<Rectangle> columns, TextBlock checkLine) {
        if (columns.size() < 2) {
            return false;
        }

        return this.calOverlapColumnNum(columns, checkLine) == 1;
    }

    private boolean isOverlapMultiColumn(List<Rectangle> columns, TextBlock checkLine) {
        if (columns.size() < 2) {
            return false;
        }

        return this.calOverlapColumnNum(columns, checkLine) > 1;
    }

    private boolean isAlignByTableColumn(List<Rectangle> columns, TextBlock checkLine) {
        if (columns.size() < 2) {
            return false;
        }

        int alignColNum = 0;
        for (TextChunk tc : checkLine.getElements()) {
            if (this.calOverlapColumnNum(columns, tc.toRectangle()) == 1) {
                alignColNum++;
            }
        }

        if (alignColNum == checkLine.getSize()) {
            return true;
        }

        return false;
    }

    //calculate vertical aligning gap
    private HashMap<Point2D, java.lang.Float> calVerticalHardAlignGap(ContentGroupPage page, List<TextBlock> textBlocks) {
        HashMap<Point2D, java.lang.Float> gapMap = new HashMap<>();

        if (textBlocks.size() < 2) {
            return gapMap;
        } else if (textBlocks.size() == 2) {
            boolean hOverlap = textBlocks.get(0).toRectangle().isHorizontallyOverlap(textBlocks.get(1).toRectangle());
            if (!hOverlap) {
                return gapMap;
            }
        }

        //如果有文本块间距过小，可能是前端文本块合并错误，则需要重新合并后进行后面计算，暂时只对两个block的情况进行修正
        List<List<TextChunk>> collectedTextChunks = new ArrayList<>();
        if (textBlocks.size() == 2) {
            for (TextBlock tb : textBlocks) {
                float avgCharWidth = tb.getAvgCharWidth();
                List<TextChunk> teList = tb.getElements();
                List<TextChunk> newTeList = new ArrayList<>();
                TextChunk teTemp = new TextChunk(teList.get(0));
                newTeList.add(teTemp);
                for (int i = 0; i < teList.size() - 1; i++) {
                    if (teList.get(i + 1).getMinX() - teList.get(i).getMaxX() < 1.0 * avgCharWidth) {
                        teTemp.merge(teList.get(i + 1));
                    } else {
                        teTemp = new TextChunk(teList.get(i + 1));
                        newTeList.add(teTemp);
                    }
                }
                collectedTextChunks.add(newTeList);
            }
        } else {
            for (TextBlock tb : textBlocks) {
                collectedTextChunks.add(tb.getElements());
            }
        }

        //find line of max col;
        int maxColumn = 0;
        for (List<TextChunk> te : collectedTextChunks) {
            if (te.size() > maxColumn) {
                maxColumn = te.size();
            }
        }
        if (maxColumn <= 1) {
            return new HashMap<>();
        }

        List<List<TextChunk>> maxColLines= new ArrayList<>();
        for (List<TextChunk> te : collectedTextChunks) {
            if (te.size() == maxColumn) {
                maxColLines.add(te);
            }
        }

        List<Rectangle> baseRectList = new ArrayList<>();
        for (int i = 0; i < maxColumn; i++) {
            float minX = Float.MAX_VALUE;
            float maxX = 0.0f;
            for (List<TextChunk> te : maxColLines) {
                minX = FastMath.min(minX, (float)te.get(i).getMinX());
                maxX = FastMath.max(maxX, (float)te.get(i).getMaxX());
            }
            baseRectList.add(new Rectangle(minX, 0, maxX - minX,  page.getAvgCharHeight()));
        }

        //find aligned textChunk
        List<List<TextChunk>> allAlignedCol = new ArrayList<>();
        for (Rectangle baseRect : baseRectList) {
            List<TextChunk> alignedColTextChunks = new ArrayList<>();
            for (List<TextChunk> te : collectedTextChunks) {
                List<TextChunk> chunkListTemp = te.stream().filter(x -> x.isHorizontallyOverlap(baseRect))
                        .collect(Collectors.toList());
                if (chunkListTemp.size() > 1) {
                    return new HashMap<>();
                } else if (!chunkListTemp.isEmpty()) {
                    alignedColTextChunks.add(chunkListTemp.get(0));
                }
            }
            allAlignedCol.add(alignedColTextChunks);
        }

        //calculate the gap between two adjacent col
        double lastRight = allAlignedCol.get(0).stream().map(Rectangle2D.Float::getMaxX).max(Comparator.comparing(u -> u)).get();
        for (int i = 1; i < allAlignedCol.size(); i++) {
            double nowLeft = allAlignedCol.get(i).stream().map(Rectangle2D.Float::getMinX).min(Comparator.comparing(u -> u)).get();
            double nowRight = allAlignedCol.get(i).stream().map(Rectangle2D.Float::getMaxX).max(Comparator.comparing(u -> u)).get();
            if (nowLeft < lastRight) {
                return new HashMap<>();
            } else {
                gapMap.put(new Point2D.Float((float)lastRight, (float)nowLeft), (float)(nowLeft - lastRight));
            }
            lastRight = nowRight;
        }

        return gapMap;
    }

    private boolean isVerticalHardAlign(ContentGroupPage page, List<TextBlock> textBlocks, float alignGap) {
        HashMap<Point2D, java.lang.Float> gapMap = calVerticalHardAlignGap(page, textBlocks);

        if (gapMap.isEmpty()) {
            return false;
        }

        for (Iterator<HashMap.Entry<Point2D, java.lang.Float>> it = gapMap.entrySet().iterator(); it.hasNext();) {
            HashMap.Entry<Point2D, java.lang.Float> item = it.next();
            for (TextBlock tb : textBlocks) {
                if (item.getKey().getX() <= tb.getLeft() || item.getKey().getY() >= tb.getRight()) {
                    it.remove();
                    break;
                }
            }
        }

        if (gapMap.isEmpty()) {
            return false;
        }

        for (Float gap : gapMap.values()) {
            if (gap < alignGap) {
                return false;
            }
        }

        return true;
    }

    private boolean isExistNumberTextLine(ContentGroupPage page, List<TextBlock> textBlocks, Rectangle area) {
        boolean result = false;
        List<TextBlock> regionTextBlocks = new ArrayList<>();
        for (TextBlock textBlock : textBlocks) {
            if (textBlock.intersects(area)) {
                regionTextBlocks.add(textBlock);
            }
        }
        if (!regionTextBlocks.isEmpty() &&
                SPLITE_TABLE_FLAG_RE.matcher(regionTextBlocks.get(regionTextBlocks.size() - 1).getText()).matches()) {
            result = true;
        }
        return result;
    }

    public static boolean isChartTableTitle(TextBlock textBlock) {
        String str = textBlock.getText().trim().toLowerCase();
        if (StringUtils.isBlank(str)) {
            return false;
        }
        return TABLE_BEGINNING_KEYWORD.matcher(str).matches() || CHART_BEGINNING_KEYWORD.matcher(str).matches();
    }

    //find candidate region
    private List<TableRegion> findMostPossibleRegion(ContentGroupPage page, List<TextBlock> allTextBlocks, Rectangle processRect, float pageValidWidth) {
        if (allTextBlocks.isEmpty()) {
            return new ArrayList<>();
        }

        //find maximum possible textblock which can make up table
        List<TableRegion> candidateRegions = new ArrayList<>();
        List<TextBlock> mostPossibleTextBlocks = new ArrayList<>();
        List<TextBlock> secondMostPossibleTextBlocks = new ArrayList<>();
        for (TextBlock tb : allTextBlocks) {
            String tbStr = tb.getText().trim();
            //过滤掉合并错误的textBlock
            if (tb.getSize() < 2 || isWrongTextBlock(tb) || (SPECIAL_SYMBOL_RE.matcher(tbStr).matches() && tb.getSize() <= 2)
                    && !TableRegionDetectionUtils.isLargeGap(tb, 5 * tb.getAvgCharWidth(), 1)) {
                continue;
            }

            //serial number text line
            List<TextChunk> textChunkList = tb.getElements();
            if (textChunkList.size() >= 2 && TableRegionDetectionUtils.isLargeGap(tb, 2 * tb.getAvgCharWidth(), 1) && !TableRegionDetectionUtils.hasCJKEStr(textChunkList)) {
                mostPossibleTextBlocks.add(tb);
                tb.setMergeFlag();
                continue;
            }
            boolean isSerialLine = false;
            if ((SERIAL_NUMBER_ROW_RE_0.matcher(tbStr).matches() || SERIAL_NUMBER_ROW_RE_1.matcher(tbStr).matches())
                    && SERIAL_NUMBER_RE.matcher(tb.getFirstTextChunk().getText()).matches()) {
                isSerialLine = true;
            }
            if (textChunkList.size() <= 2 && (isSerialLine && !TableRegionDetectionUtils.isLargeGap(tb, 5 * tb.getAvgCharWidth(), 1))) {
                continue;
            }

            //date line
            if (textChunkList.size() >= 2 && TableRegionDetectionUtils.calStrMatchNum(DATA_STR_RE, tbStr) >= 1
                    && TableRegionDetectionUtils.isLargeGap(tb, 1.5f * tb.getAvgCharWidth(), 1)
                    && tb.getLeft() > (processRect.getLeft() + processRect.getWidth() / 3)) {
                mostPossibleTextBlocks.add(tb);
                tb.setMergeFlag();
                continue;
            }

            //table and chart beginning keyword
            if (tb.getSize() <= 2 && (TableRegionDetectionUtils.calStrMatchNum(TABLE_BEGINNING_KEYWORD, tb.getElements()) >= 2
                    || TableRegionDetectionUtils.calStrMatchNum(CHART_BEGINNING_KEYWORD, tb.getElements()) >= 2)) {
                continue;
            }

            //text length is long
            if (textChunkList.size() <= 2 && tb.getLastTextChunk().getTextLength() > 0.8 * pageValidWidth
                    && !TableRegionDetectionUtils.matchAnyText(tb.getElements(), MEASURE_UNIT_RE)) {
                boolean hasUnderRuling = hasRulingUnderText(page, tb, page.getAvgCharHeight(), (float) (0.5 * tb.getWidth()));
                if (hasUnderRuling && TableRegionDetectionUtils.isLargeGap(tb, 1.5f * tb.getAvgCharWidth(), 1)) {
                    mostPossibleTextBlocks.add(tb);
                    tb.setMergeFlag();
                }
                continue;
            }

            //underline process
            List<TextChunk> underlineTextChunks = new ArrayList<>();
            List<TextChunk> validTextChunks = new ArrayList<>();
            for (TextChunk chunk : textChunkList) {
                if (this.isUnderline(chunk)) {
                    underlineTextChunks.add(chunk);
                } else {
                    validTextChunks.add(chunk);
                }
            }
            if (!underlineTextChunks.isEmpty() && !validTextChunks.isEmpty()) {
                List<Rectangle> mixedRects = new ArrayList<>();
                for (TextChunk base : underlineTextChunks) {
                    for (TextChunk other : validTextChunks) {
                        if (other.getBottom() <= base.getBottom() && base.isHorizontallyOverlap(other)) {
                            mixedRects.add(new Rectangle(base.createUnion(other)));
                        }
                    }
                }
                if ((!mixedRects.isEmpty() && TableRegionDetectionUtils.isLargeGap(mixedRects, 1.0f * page.getAvgCharWidth(), 1))) {
                    mostPossibleTextBlocks.add(tb);
                    tb.setMergeFlag();
                }
            }

            if (validTextChunks.size() >= 2) {
                if (!TableRegionDetectionUtils.hasNumberStr(validTextChunks) && (MEASURE_UNIT_RE.matcher(validTextChunks.get(0).getText()).matches() ||
                        MEASURE_UNIT_RE.matcher(validTextChunks.get(validTextChunks.size() - 1).getText()).matches() ||
                        SPECIAL_STR_END_RE.matcher(validTextChunks.get(0).getText()).matches())) {
                    continue;
                }
                if (isSerialLine && !TableRegionDetectionUtils.isLargeGap(tb, 2.0f * page.getAvgCharWidth(), 2)
                        && TableRegionDetectionUtils.hasLongStr(tb, 0.8f * pageValidWidth, 1)) {
                    continue;
                }

                HashMap<Point2D, java.lang.Float> gapMap = TableRegionDetectionUtils.calVerticalProjectionGap(validTextChunks);
                int hugeGapFlag = TableRegionDetectionUtils.countLargeGap(gapMap, 4 * tb.getAvgCharWidth());
                int largeGapCount = TableRegionDetectionUtils.countLargeGap(gapMap, 1.5f * tb.getAvgCharWidth());
                int littleGapCount = TableRegionDetectionUtils.countLargeGap(gapMap, tb.getAvgCharWidth());
                //统计英文字符的数量
                int englishNum = 0;
                for (TextChunk tc : validTextChunks) {
                    if (ENGLISH_STR_RE.matcher(tc.getText().trim()).find()) {
                        englishNum++;
                    }
                }
                if (hugeGapFlag >= 1 || largeGapCount >= 2 || (littleGapCount >= 5 && englishNum < 0.5 * validTextChunks.size())) {
                    if (!tb.getMergeFlag()) {
                        mostPossibleTextBlocks.add(tb);
                        tb.setMergeFlag();
                    }
                } else if (largeGapCount >= 1 && !tb.getMergeFlag()) {
                    secondMostPossibleTextBlocks.add(tb);
                }
            }
        }

        //merge most possible textblock
        for (TextBlock currentTextBlock : mostPossibleTextBlocks) {
            if (candidateRegions.isEmpty()) {
                candidateRegions.add(new TableRegion(currentTextBlock));
                continue;
            }

            TableRegion lastTableRegion = candidateRegions.get(candidateRegions.size() - 1);
            float reGapMinX = (float)FastMath.min(lastTableRegion.getBounds2D().getMinX(), currentTextBlock.getBounds2D().getMinX());
            float reGapMinY = (float)lastTableRegion.getBounds2D().getMaxY();
            float reGapMaxX = (float)FastMath.max(lastTableRegion.getBounds2D().getMaxX(), currentTextBlock.getBounds2D().getMaxX());
            float reGapMaxY = (float) currentTextBlock.getBounds2D().getMinY();
            Rectangle gapRect =  new Rectangle(reGapMinX, reGapMinY, reGapMaxX - reGapMinX, reGapMaxY - reGapMinY)
                    .rectReduce(0, 0.5, page.getWidth(), page.getHeight());
            List<TableRegion> rcTemp = lineTableRegions.stream().filter(rc -> rc.isShapeIntersects(gapRect))
                    .collect(Collectors.toList());
            if (rcTemp.isEmpty() && reGapMaxY - reGapMinY < 2 * page.getAvgCharHeight() &&
                    !isExistNumberTextLine(page, allTextBlocks, gapRect)) {
                List<TextBlock> tb = new ArrayList<>(candidateRegions.get(candidateRegions.size() - 1).getTextBlockList());
                tb.add(currentTextBlock);
                if (isVerticalHardAlign(page, tb, 1.5f * page.getAvgCharWidth())) {
                    lastTableRegion.addTextBlockToList(currentTextBlock);
                } else if (isVerticalHardAlign(page, Arrays.asList(tb.get(tb.size() - 2), currentTextBlock), 1.5f * page.getAvgCharWidth())) {
                    lastTableRegion.addTextBlockToList(currentTextBlock);
                } else {
                    List<TextChunk> te = currentTextBlock.getElements();
                    boolean largeGapFlag = false;
                    for (int j = 0; j < currentTextBlock.getSize() - 1; j++) {
                        if (te.get(j + 1).getMinX() - te.get(j).getMaxX() > 2 * page.getAvgCharWidth()) {
                            largeGapFlag = true;
                            break;
                        }
                    }

                    if (largeGapFlag) {
                        candidateRegions.get(candidateRegions.size() - 1).addTextBlockToList(currentTextBlock);
                    } else {
                        candidateRegions.add(new TableRegion(currentTextBlock));
                    }
                }
            } else {
                candidateRegions.add(new TableRegion(currentTextBlock));
            }
        }

        //merge second most possible textblock
        if (secondMostPossibleTextBlocks.size() >= 2) {
            //存在连续多行，间隔较小，且列对齐的表格
            secondMostPossibleTextBlocks.sort(Comparator.comparing(TextBlock::getTop));
            List<List<TextBlock>> groups = new ArrayList<>();
            for (TextBlock textBlock : secondMostPossibleTextBlocks) {
                if (groups.isEmpty()) {
                    if (TableRegionDetectionUtils.isHorizontalCenterNear(textBlock.getElements())) {
                        groups.add(Lists.newArrayList(textBlock));
                    }
                    continue;
                }

                List<TextBlock> lastGroup = groups.get(groups.size() - 1);
                TextBlock lastTextBlock = lastGroup.get(lastGroup.size() - 1);
                float minX = (float)FastMath.min(textBlock.getLeft(), lastTextBlock.getLeft());
                float maxX = (float)FastMath.max(textBlock.getRight(), lastTextBlock.getRight());
                float minY = (float)lastTextBlock.getBottom();
                float maxY = (float)textBlock.getTop();
                Rectangle gapRect = new Rectangle(minX, minY, maxX - minX, maxY - minY)
                        .rectReduce(0, 1.0, page.getWidth(), page.getHeight());
                if (TableRegionDetectionUtils.isHorizontalCenterNear(textBlock.getElements()) && (gapRect.getHeight() < 0
                        || TableRegionDetectionUtils.getTextBlockWithinShape(page, gapRect, allTextBlocks).isEmpty())) {
                    lastGroup.add(textBlock);
                } else {
                    groups.add(Lists.newArrayList(textBlock));
                }

            }

            for (List<TextBlock> group : groups) {
                if (group.size() < 2) {
                    continue;
                }

                Rectangle bound = Rectangle.textBlockBoundingVisibleBoxOf(group);
                HashMap<Point2D, java.lang.Float> gapMap = TableRegionDetectionUtils.calVerticalProjectionGap(page
                        , bound, false);
                float avgWidth = (float)page.getText(bound).parallelStream()
                        .mapToDouble(TextElement::getTextWidth).average().orElse(Page.DEFAULT_MIN_CHAR_WIDTH);
                if (TableRegionDetectionUtils.countLargeGap(gapMap, 1.5f * avgWidth) >= 1) {
                    candidateRegions.add(new TableRegion(bound, group));
                }
            }
        }

        //TODO
        //find local line which can make up table
        //find special color which can make up table
        //find clipArea which can make up table

        checkCandidateRegions(page, allTextBlocks, candidateRegions);

        return candidateRegions;
    }

    /**
     *
     * @param page
     * @param layoutRectangle：需要版面分析的区域
     * @param considerTextGap：是否考虑文本间隔，如果考虑间隔，则间隔较大的文本块会认为是两个版面
     * @param strictSeg：强制版面分析，不考虑有线表格区域
     * @return
     */
    public List<SegmentPageInfo> segmentPageByTj(ContentGroupPage page, Rectangle layoutRectangle, List<TableRegion> lineTableRegions, Boolean considerTextGap, Boolean strictSeg) {
        List<SegmentPageInfo> segmentResults = new ArrayList<>();

        List<TextChunk> allTextChunks = page.getMutableTextChunks(layoutRectangle);
        //去除空白、重复、有线表格区域中的文本块
        TableRegionDetectionUtils.filterBlankChunk(allTextChunks);
        removeDuplicateChunk(allTextChunks);
        if (!strictSeg) {
            if (!allTextChunks.isEmpty() && !lineTableRegions.isEmpty()) {
                for (TextChunk tc : allTextChunks) {
                    for (TableRegion tableRegion : lineTableRegions) {
                        if (tableRegion.intersects(tc)) {
                            tc.markDeleted();
                            break;
                        }
                    }
                }
                allTextChunks.removeIf(TextChunk::isDeleted);
            }
        }

        if (allTextChunks.isEmpty()) {
            return segmentResults;
        }

        allTextChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
        for (int i = 0; i < allTextChunks.size(); i++) {
            TextChunk currentTextChunk = allTextChunks.get(i);
            if (segmentResults.isEmpty()) {
                segmentResults.add(new SegmentPageInfo(currentTextChunk));
                continue;
            }

            SegmentPageInfo lastSegmentResult = segmentResults.get(segmentResults.size() - 1);
            if (lastSegmentResult.getLastTextChunk().getCenterY() - 0.5 * page.getAvgCharHeight() > currentTextChunk.getCenterY()
                    || (!lastSegmentResult.isHorizontallyOverlap(currentTextChunk) && !lastSegmentResult.isVerticallyOverlap(currentTextChunk))) {
                segmentResults.add(new SegmentPageInfo(currentTextChunk));
                continue;
            }

            if (considerTextGap && lastSegmentResult.isVerticallyOverlap(new Rectangle(currentTextChunk.getVisibleBBox()))
                    && !FloatUtils.feq(lastSegmentResult.getMaxX(), currentTextChunk.getMinX(), 2 * page.getAvgCharWidth())) {
                segmentResults.add(new SegmentPageInfo(currentTextChunk));
                continue;
            }

            List<TextChunk> existingTextChunks = new ArrayList<>(lastSegmentResult.getTextChunks());
            existingTextChunks.add(currentTextChunk);
            List<TextChunk> extraTextChunks = SegmentPageInfo.getExtraTextChunks(allTextChunks, existingTextChunks);
            if (extraTextChunks.isEmpty()) {
                lastSegmentResult.addTextChunkToList(currentTextChunk);
                continue;
            }
            extraTextChunks.sort(Comparator.comparing(TextChunk::getGroupIndex));
            if (allTextChunks.indexOf(extraTextChunks.get(0)) == i + 1 && currentTextChunk.getCenterY() - 0.5 * page.getAvgCharHeight() < extraTextChunks.get(0).getCenterY()) {
                if (extraTextChunks.size() == 1) {
                    lastSegmentResult.addTextChunkToList(currentTextChunk);
                } else {
                    boolean isContinusTj = true;
                    for (int j = 0; j < extraTextChunks.size() - 1; j++) {
                        if (allTextChunks.indexOf(extraTextChunks.get(j + 1)) - allTextChunks.indexOf(extraTextChunks.get(j)) != 1
                                || extraTextChunks.get(j).getCenterY() - extraTextChunks.get(j + 1).getCenterY() > 0.5 * page.getAvgCharHeight()) {
                            isContinusTj = false;
                            break;
                        }
                    }
                    if (isContinusTj) {
                        lastSegmentResult.addTextChunkToList(currentTextChunk);
                    } else {
                        segmentResults.add(new SegmentPageInfo(currentTextChunk));
                    }
                }

            } else {
                segmentResults.add(new SegmentPageInfo(currentTextChunk));
            }
        }
        return segmentResults;
    }

    private List<TableRegion> findMostPossibleContentRegion(ContentGroupPage page) {
        List<TableRegion> contentRectangles = new ArrayList<>();
        this.segmentResultByTj = segmentPageByTj(page, new Rectangle(page), this.lineTableRegions, false, false);
        if (this.segmentResultByTj.isEmpty()) {
            return new ArrayList<>();
        }
        TableDebugUtils.writeImage(page,this.segmentResultByTj,"content_seg");

        //过滤零碎的区域
        float pageValidWidth = TableRegionDetectionUtils.getValidTextWidthInRegion(page, page);
        List<SegmentPageInfo> segmentResultCopy = new ArrayList<>();
        for (SegmentPageInfo seg : this.segmentResultByTj) {
            segmentResultCopy.add(new SegmentPageInfo(seg));
        }
        for (SegmentPageInfo seg : segmentResultCopy) {
            List<TextChunk> innnerTextChunks = page.getTextChunks(seg.rectReduce(1.0f, 1.0f
                    , page.getWidth(), page.getHeight()));
            List<TextChunk> rowTextChunks = TableRegionDetectionUtils.splitChunkByRow(innnerTextChunks);
            boolean unnormalDirection = innnerTextChunks.stream().anyMatch(TextChunk::isUnnormalDirection);
            if (unnormalDirection || seg.getTextChunks().size() < 4 || rowTextChunks.size() < 4 || seg.getWidth() > (2.0 / 3.0) * pageValidWidth) {
                seg.markDeleted();
                continue;
            }
            //按正文逻辑合并文本行
            List<TextBlock> contentTextBlocks = TableTitle.findTextBlocks(page, seg);
            if (seg.nearlyContains(Rectangle.textBlockBoundingVisibleBoxOf(contentTextBlocks))) {
                seg.setContentTextBlocks(TableTitle.findTextBlocks(page, seg));
            } else {
                seg.markDeleted();
            }
        }

        segmentResultCopy.sort(Comparator.comparing(Rectangle::getTop));
        for (SegmentPageInfo seg : segmentResultCopy) {
            if (seg.isDeleted()) {
                continue;
            }
            if (TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils.calVerticalProjectionGap(page
                    , seg, true), page.getAvgCharWidth()) >= 1) {
                continue;
            }

            List<SegmentPageInfo> horizontalSegs = segmentResultCopy.stream().filter(s -> s.isVerticallyOverlap(seg)).collect(Collectors.toList());
            if (horizontalSegs.size() == 2) {
                boolean maybeTableFlag = false;
                for (SegmentPageInfo hSeg : horizontalSegs) {
                    if (!hSeg.equals(seg) && (TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils.calVerticalProjectionGap(page
                            , hSeg, true), page.getAvgCharWidth()) >= 1)) {
                        maybeTableFlag = true;
                        break;
                    }
                }
                if (maybeTableFlag) {
                    continue;
                }
            }
            List<Rectangle> horizontalRects = new ArrayList<>();
            for (SegmentPageInfo hSeg : horizontalSegs) {
                horizontalRects.add(new Rectangle(hSeg));//注意：calVerticalProjection中会对Rectangle重设deleted标签
            }
            if (TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils.calVerticalProjectionGap(horizontalRects), page.getAvgCharWidth()) > 1) {
                continue;
            }

            for (TextBlock tb : seg.getContentTextBlocks()) {
                List<List<TextChunk>> rowTextChunks = TableRegionDetectionUtils.analysisTextBlock(tb);
                if (rowTextChunks.size() < 4) {
                    continue;
                }
                int fullRowNum = 0;
                for (List<TextChunk> rows : rowTextChunks) {
                    if (Rectangle.boundingBoxOf(rows).getWidth() > 0.85 * seg.getWidth()
                            && !TableRegionDetectionUtils.isLargeGap(rows, 1.5f * page.getAvgCharWidth(), 1)
                            && TableRegionDetectionUtils.countNumberStr(rows) < 0.3 * rows.size()) {
                        fullRowNum++;
                    }
                }

                if (fullRowNum >= 0.75 * rowTextChunks.size()) {
                    contentRectangles.add(new TableRegion(tb.getTextBound()));
                }
            }
        }

        TableDebugUtils.writeImage(page,contentRectangles,"content_remove");

        return contentRectangles;
    }

    private List<TextChunk> findTextChunks(ContentGroupPage page) {
        if (page.getTextChunks() == null || page.getTextChunks().isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> removeRectangles = new ArrayList<>();
        removeRectangles.addAll(lineTableRegions);

        //remove content
        boolean isRemoveContent = true;
        if (isRemoveContent) {
            removeRectangles.addAll(findMostPossibleContentRegion(page));
        }

        //remove chart
        boolean isRemoveChart = true;
        if (isRemoveChart) {
            removeRectangles.addAll(chartRegions);
        }

        List<TextChunk> textChunks = new ArrayList<>();
        List<Rectangle> reduceRectangles = new ArrayList<>();
        for (Rectangle rc : removeRectangles) {
            reduceRectangles.add(rc.rectReduce(page.getAvgCharWidth() / 2, 2.0
                    , page.getWidth(), page.getHeight()));
        }
        for (TextChunk chunk : page.getTextChunks()) {
            boolean findFlag = false;
            for (Rectangle rc : reduceRectangles) {
                if (rc.intersects(chunk)) {
                    findFlag = true;
                    break;
                }
            }
            if (!findFlag) {
                textChunks.add(new TextChunk(chunk));
            }
        }

        TableRegionDetectionUtils.filterBlankChunk(textChunks);
        //filterLeftRightPageNumber(page, textChunks);//可能会将居左的序号串去掉，导致多个表格被合并
        if (textChunks.isEmpty()) {
            return new ArrayList<>();
        }

        return textChunks;
    }

    private boolean hasLeftPageNumber(ContentGroupPage page, List<TextChunk> middleTextChunks, double leftMargin) {
        if (middleTextChunks.size() <= 2) {
            return false;
        }

        middleTextChunks.sort(Comparator.comparing(TextChunk::getLeft));
        TextChunk leftChunk = middleTextChunks.get(0);
        TextChunk neighborChunk = middleTextChunks.get(1);
        if (leftChunk.getRight() < leftMargin && TableRegionDetectionUtils.isNumberStr(leftChunk.getText().trim())
                && neighborChunk.getLeft() - leftChunk.getRight() > 2 * page.getAvgCharWidth()) {
            return true;

        }
        return false;
    }

    private boolean hasRightPageNumber(ContentGroupPage page, List<TextChunk> middleTextChunks, double rightMargin) {
        if (middleTextChunks.size() <= 2) {
            return false;
        }

        middleTextChunks.sort(Comparator.comparing(TextChunk::getRight));
        TextChunk rightChunk = middleTextChunks.get(middleTextChunks.size() - 1);
        TextChunk neighborChunk = middleTextChunks.get(middleTextChunks.size() - 2);
        if (rightChunk.getRight() > rightMargin && TableRegionDetectionUtils.isNumberStr(rightChunk.getText().trim())
                && rightChunk.getLeft() - neighborChunk.getRight() > 2 * page.getAvgCharWidth()) {
            return true;

        }
        return false;
    }

    /**
     *去除页码在页面左中或右中的情况
     */
    private void filterLeftRightPageNumber(ContentGroupPage page, List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.isEmpty()) {
            return;
        }

        double topMargin = FastMath.max(page.getPaper().topMarginInPt, page.getPaper().bottomMarginInPt);
        double bottomMargin = page.getHeight() - topMargin;
        double leftMargin = 1.5 * FastMath.max(page.getPaper().leftMarginInPt, page.getPaper().rightMarginInPt);
        double rightMargin = page.getWidth() - leftMargin;

        List<TextChunk> middleTextChunks = textChunks.stream().filter(tc -> (tc.getCenterY() > topMargin && tc.getCenterY() < bottomMargin))
                .collect(Collectors.toList());
        if (middleTextChunks.size() <= 2) {
            return;
        }

        boolean hasPageNumberOfPrev = false;
        if (page.getPrevPage() != null) {
            ContentGroupPage prePage;
            if (!(page.getPrevPage() instanceof ContentGroupPage)) {
                prePage = ContentGroupPage.fromPage(page.getPrevPage());
            } else {
                prePage = (ContentGroupPage) (page.getPrevPage());
            }
            List<TextChunk> prevPageMiddleTextChunks = prePage.getTextChunks().stream().filter(tc -> (tc.getCenterY() > topMargin && tc.getCenterY() < bottomMargin))
                    .collect(Collectors.toList());
            if (prevPageMiddleTextChunks.size() > 2 && (hasLeftPageNumber(prePage, prevPageMiddleTextChunks, leftMargin))
                    || hasRightPageNumber(prePage, prevPageMiddleTextChunks, rightMargin)) {
                hasPageNumberOfPrev = true;
            }
        }

        if (hasPageNumberOfPrev) {
            if (hasLeftPageNumber(page, middleTextChunks, leftMargin)) {
                middleTextChunks.sort(Comparator.comparing(TextChunk::getLeft));
                textChunks.remove(middleTextChunks.get(0));
            } else if (hasRightPageNumber(page, middleTextChunks, rightMargin)) {
                middleTextChunks.sort(Comparator.comparing(TextChunk::getRight));
                textChunks.remove(middleTextChunks.get(middleTextChunks.size() - 1));
            }
        }
    }

    private void removeDuplicateChunk(List<TextChunk> textChunks) {
        HashSet chunkSet = new HashSet(textChunks);
        textChunks.clear();
        textChunks.addAll(chunkSet);
    }

    //判断行是否是正文，标题，表头，页眉页脚
    private boolean isTextBodyLine(ContentGroupPage page, TableRegion regionBase, TextBlock previousBottomTextBlock, TextBlock bottomTextBlock, TextBlock downBlock, TextBlock nextDownBlock, List<Rectangle> columns, List<Ruling> hRulingInProcessRect, float pageValidWidth, LinkedHashMap<TextBlock, SerialNumberInfo> snMap, Rectangle processRect, MergeType mergeType, List<FillAreaGroup> fillAreaGroupsInProcessRect) {
        Rectangle rect = new Rectangle(regionBase.getBounds());
        TextChunk lastChunk = downBlock.getLastTextChunk();
        String lastChunkText = lastChunk.getText().replace(" ", "");

        //global serial number information
        if (snMap.containsKey(downBlock) && snMap.get(downBlock).getTextLineType() == SerialNumberInfo.TextLineType.TABLE_LINE) {
            regionBase.addTextBlockToList(downBlock);
            return false;
        }
        if (snMap.containsKey(downBlock) && snMap.get(downBlock).getTextLineType() == SerialNumberInfo.TextLineType.TEXT_LINE) {
            return true;
        }

        //头尾检查
        if (SPECIAL_STR_HEAD_RE.matcher(downBlock.getText()).matches() || TABLE_HEAD_RE.matcher(downBlock.getText()).matches()
                || (CHART_BEGINNING_KEYWORD.matcher(downBlock.getText().trim().toLowerCase()).matches() && downBlock.getSize() <= 2)) {
            return true;
        }
        if (SPECIAL_STR_END_RE.matcher(downBlock.getFirstTextChunk().getText().trim()).matches()) {
            if (TableRegionDetectionUtils.calStrMatchNum(SPECIAL_STR_END_RE, downBlock.getElements()) >= 2 && downBlock.getSize() <=2) {
                return true;
            }
            if (TableRegionDetectionUtils.isLargeGap(downBlock, 3 * downBlock.getAvgCharWidth(), 1)) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
            return true;
        }
        if (TABLE_BEGINNING_KEYWORD.matcher(downBlock.getText().trim().toLowerCase()).matches()) {
            if (downBlock.getSize() == 1) {
                return true;
            } else if (downBlock.getSize() == 2 && TableRegionDetectionUtils.calStrMatchNum(TABLE_BEGINNING_KEYWORD, downBlock.getElements()) >= 2) {
                return true;
            }
        }
        if (mergeType == MergeType.DOWN) {
            if (TABLE_BEGINNING_KEYWORD.matcher(downBlock.getText().trim().toLowerCase()).matches()
                    && !TableRegionDetectionUtils.isLargeGap(downBlock, 2 * downBlock.getAvgCharWidth(), 1)) {
                return true;
            }
            if (TABLE_END_RE.matcher(bottomTextBlock.getElements().get(0).getText()).matches() && downBlock.getColumnCount() != columns.size()) {
                return true;
            }
            if (downBlock.getSize() == 1 && TABLE_TITLE_RE_1.matcher(downBlock.getText().trim()).matches() && isSerialNumberLine(downBlock)) {
                return true;
            }
        } else if (mergeType == MergeType.UP) {
            if (TABLE_BEGINNING_KEYWORD.matcher(downBlock.getText().trim().toLowerCase()).matches()
                    && !TableRegionDetectionUtils.isLargeGap(downBlock, 2 * downBlock.getAvgCharWidth(), 1)) {
                return true;
            }
            if (TABLE_BEGINNING_KEYWORD.matcher(nextDownBlock.getText().trim().toLowerCase()).matches()
                    && !TableRegionDetectionUtils.isLargeGap(nextDownBlock, 2 * downBlock.getAvgCharWidth(), 2)
                    && !SPECIAL_STR_HEAD_RE.matcher(downBlock.getText().trim().toLowerCase()).matches()
                    && regionBase.nearlyHorizontalContains(downBlock.toRectangle(), downBlock.getAvgCharWidth())
                    && TableRegionDetectionUtils.isLargeGap(downBlock, 2 * downBlock.getAvgCharWidth(), 1)) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
            if (TABLE_END_RE.matcher(downBlock.getElements().get(0).getText()).matches() && bottomTextBlock.getColumnCount() != downBlock.getColumnCount()) {
                return true;
            }
        }

        //FillingArea information
        List<FillAreaGroup> verticalIntersetGroups = fillAreaGroupsInProcessRect.stream().filter(fillAreaGroup
                -> fillAreaGroup.isVertical() && fillAreaGroup.getGroupRectArea().intersects(regionBase)).collect(Collectors.toList());
        if (verticalIntersetGroups.size() == 1) {
            FillAreaGroup fillAreaGroup = verticalIntersetGroups.get(0);
             if (regionBase.verticalOverlapRatio(fillAreaGroup.getGroupRectArea()) > 0.9
                     && downBlock.toRectangle().verticalOverlapRatio(fillAreaGroup.getGroupRectArea()) > 0.9
                     && fillAreaGroup.getColorNum() == 1 && fillAreaGroup.getWidth() < 0.25 * regionBase.getWidth()
                     && regionBase.horizontallyOverlapRatio(downBlock.toRectangle()) > 0.6) {
                 regionBase.addTextBlockToList(downBlock);
                 return false;
             }
        }

        //某行存在很多间距较大的列则一定为表格区域，避免部分文本块合并错误导致的后续错误
        if (downBlock.getSize() >= 3) {
            List<TextChunk> te = downBlock.getElements();
            int largeGapNum = 0;
            for (int i = 0; i < downBlock.getSize() - 1; i++) {
                if (te.get(i + 1).getMinX() - te.get(i).getMaxX() > 1.5 * page.getAvgCharWidth()) {
                    largeGapNum++;
                }
            }
            if (largeGapNum >= 3) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
        }

        //匹配特殊字符串
        if (SPECIAL_TABLE_RE.matcher(lastChunkText).matches() && downBlock.getSize() <= 2
                && (downBlock.getLeft() < 1.0 / 3.0 * processRect.getWidth())) {
            return true;
        }
        //对含有下划线的文本块处理
        if (downBlock.getSize() >= 2 && FloatUtils.feq(rect.getRight(), downBlock.getRight(), 2 * downBlock.getAvgCharWidth())) {
            List<TextChunk> underlineTextChunks = new ArrayList<>();
            List<TextChunk> otherTextChunks = new ArrayList<>();
            for (TextChunk chunk : downBlock.getElements()) {
                if (this.isUnderline(chunk)) {
                    underlineTextChunks.add(chunk);
                } else {
                    otherTextChunks.add(chunk);
                }
            }
            if (!underlineTextChunks.isEmpty() && !otherTextChunks.isEmpty()) {
                boolean hasTextAboveUnderline = false;
                for (TextChunk base : underlineTextChunks) {
                    for (TextChunk other : otherTextChunks) {
                        if (other.getBottom() <= base.getBottom() && base.isHorizontallyOverlap(other)) {
                            hasTextAboveUnderline = true;
                            break;
                        }
                    }
                }
                if (hasTextAboveUnderline && (TableRegionDetectionUtils.isLargeGap(bottomTextBlock, 2 * page.getAvgCharWidth(), 1)
                        || TableRegionDetectionUtils.isLargeGap(nextDownBlock, 2 * page.getAvgCharWidth(), 1))) {
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                }
            }
        }

        //匹配含有序号的行
        if (SERIAL_NUMBER_ROW_RE_1.matcher(downBlock.getText().trim()).matches() && downBlock.getSize() <= 2
                && !TableRegionDetectionUtils.isLargeGap(downBlock, 3 * downBlock.getAvgCharWidth(), 1)
                && (FloatUtils.feq(rect.getTop(), downBlock.getBottom(), 3 * page.getAvgCharHeight())
                || FloatUtils.feq(rect.getBottom(), downBlock.getTop(), 3 * page.getAvgCharHeight()))
                && FloatUtils.feq(rect.getLeft(), downBlock.getLeft(), 5 * downBlock.getAvgCharWidth())) {
            return true;
        }

        if (isSerialNumberLine(downBlock)) {
            if (!FloatUtils.feq(rect.getLeft(), downBlock.getLeft(), 5 * downBlock.getAvgCharWidth())) {
                return true;
            } else {
                if (downBlock.getSize() <= 2) {

                    if (downBlock.getSize() == 2) {
                        if (downBlock.getLastTextChunk().getLeft() - downBlock.getFirstTextChunk().getRight() >
                                downBlock.getAvgCharWidth() &&
                                downBlock.getColumnCount() != bottomTextBlock.getColumnCount()) {
                            return true;
                        }
                    }

                    if (FloatUtils.feq(rect.getLeft(), downBlock.getLeft(), downBlock.getAvgCharWidth()) &&
                            FloatUtils.feq(downBlock.getBottom(), bottomTextBlock.getTop(), downBlock.getAvgCharHeight()) &&
                            (bottomTextBlock.getLeft() - downBlock.getLeft() > downBlock.getAvgCharWidth())) {
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }

                    boolean fontLargeDiff = downBlock.getAvgCharHeight() > bottomTextBlock.getAvgCharHeight() * 1.5
                            || bottomTextBlock.getAvgCharHeight() > downBlock.getAvgCharHeight() * 1.5;
                    boolean overlapColumns = this.isOverlapMultiColumn(columns, downBlock);
                    boolean longLength = bottomTextBlock.getSize() > 1 && (bottomTextBlock.getElements().get(1).getLeft()
                            - bottomTextBlock.getElements().get(0).getRight() > 1.5 * bottomTextBlock.getAvgCharWidth())
                            && (downBlock.getRight() < bottomTextBlock.getElements().get(1).getLeft());

                    boolean hasHeader = false;
                    if ((mergeType == MergeType.UP) && regionBase.getTextBlockList().size() > 1) {
                        if (regionBase.getTextBlockList().get(0).getLeft() - rect.getLeft() > 5 * downBlock.getAvgCharWidth()
                                && regionBase.getTextBlockList().get(1).getLeft() - rect.getLeft() > 5 * downBlock.getAvgCharWidth()
                                && TableRegionDetectionUtils.hasCJKEStr(regionBase.getTextBlockList().get(0).getLastTextChunk().getText())
                                && TableRegionDetectionUtils.hasCJKEStr(regionBase.getTextBlockList().get(1).getLastTextChunk().getText())) {
                            List<TextBlock> topTwoLines = regionBase.getTextBlockList().subList(0, 2);
                            Rectangle topTwoLinesBound = new Rectangle(topTwoLines.get(0).getBounds2D().createUnion(topTwoLines.get(1).getBounds2D()));
                            hasHeader = topTwoLinesBound.getLeft() - rect.getLeft() > 0 && (columns.size() > 1);
                        } else {
                            hasHeader = regionBase.getTextBlockList().get(0).getLeft() - rect.getLeft() > 5 * downBlock.getAvgCharWidth()
                                    && (columns.size() > 1) && TableRegionDetectionUtils.hasCJKEStr(regionBase.getTextBlockList().get(0).getLastTextChunk().getText());
                        }
                    }

                    boolean hasLongRuling = false;
                    for (Ruling rul : hRulingInProcessRect) {
                        if ((mergeType == MergeType.UP && rul.getTop() > downBlock.getCenterY() && rul.getTop() < bottomTextBlock.getCenterY())
                                ||(mergeType == MergeType.DOWN && rul.getTop() < downBlock.getCenterY() && rul.getTop() > bottomTextBlock.getCenterY())) {
                            hasLongRuling = ((rul.getWidth() / rect.getWidth()) > 3.0 / 4.0);
                            break;
                        }
                    }

                    boolean hasMergedCell = false;
                    if (mergeType == MergeType.UP && regionBase.getTextBlockList().size() >= 3) {
                        TextBlock one = regionBase.getTextBlockList().get(0);
                        TextBlock two = regionBase.getTextBlockList().get(1);
                        TextBlock three = regionBase.getTextBlockList().get(2);
                        hasMergedCell = (three.getBottom() - one.getTop() < (one.getHeight() + two.getHeight() + three.getHeight()));
                    }

                    if (hasMergedCell|| fontLargeDiff || overlapColumns || !longLength || MEASURE_UNIT_RE.matcher(lastChunkText).matches() || (mergeType == MergeType.UP && hasHeader)) {
                        if (hasLongRuling) {
                            List<Ruling> gapLines = new ArrayList<>();
                            for (Ruling rul : hRulingInProcessRect) {
                                boolean isVaildLine = ((rul.getWidth() / rect.getWidth()) > 3.0 / 4.0);
                                if (mergeType == MergeType.UP) {
                                    if (rul.getTop() > nextDownBlock.getBottom() && rul.getTop() < downBlock.getTop() && isVaildLine) {
                                        gapLines.add(rul);
                                    }
                                    if (rul.getTop() > downBlock.getBottom() && rul.getTop() < bottomTextBlock.getTop() && isVaildLine) {
                                        gapLines.add(rul);
                                        break;
                                    }
                                } else {
                                    if (rul.getTop() > bottomTextBlock.getBottom() && rul.getTop() < downBlock.getTop() && isVaildLine) {
                                        gapLines.add(rul);
                                    }
                                    if (rul.getTop() > downBlock.getBottom() && rul.getTop() < nextDownBlock.getTop() && isVaildLine) {
                                        gapLines.add(rul);
                                        break;
                                    }
                                }
                            }
                            if ((gapLines.size() == 2) &&
                                    FloatUtils.feq(gapLines.get(0).getWidth(), gapLines.get(1).getWidth(), downBlock.getAvgCharWidth())) {
                                regionBase.addTextBlockToList(downBlock);
                            }
                        }
                        return true;
                    } else {
                        if (hasLongRuling && (bottomTextBlock.getColumnCount() != downBlock.getColumnCount() && bottomTextBlock.getColumnCount() != nextDownBlock.getColumnCount())) {
                            return true;
                        }
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }
                } else if (downBlock.getSize() > 2 && TableRegionDetectionUtils.isLargeGap(downBlock, 1.5f * downBlock.getAvgCharWidth(), 1)){
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                } else {
                    return true;
                }
            }
        }

        //表格属性行判断
        if (downBlock.getSize() == 1 && SPECIAL_ROW_STR_RE.matcher(downBlock.getText().trim()).matches()
                && downBlock.getLeft() - rect.getMinX() > 4 * downBlock.getAvgCharWidth()
                && rect.getMaxX() - downBlock.getRight() > 4 * downBlock.getAvgCharWidth()) {
            boolean witchinHeightScope = (mergeType == MergeType.DOWN && FloatUtils.feq(rect.getBottom(), downBlock.getTop(), 2 * downBlock.getAvgCharHeight()))
                    || (mergeType == MergeType.UP && FloatUtils.feq(rect.getTop(), downBlock.getBottom(), 2 * downBlock.getAvgCharHeight()));
            if (witchinHeightScope && (TableRegionDetectionUtils.isLargeGap(nextDownBlock, 2 * nextDownBlock.getAvgCharWidth(), 1)
                    || TableRegionDetectionUtils.isLargeGap(bottomTextBlock, 2 * nextDownBlock.getAvgCharWidth(), 1))) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
        }
        if (downBlock.getLeft() - rect.getMinX() > 5 * downBlock.getAvgCharWidth()) {
            boolean witchinHeightScope = (mergeType == MergeType.DOWN && FloatUtils.feq(rect.getBottom(), downBlock.getTop(), 2 * downBlock.getAvgCharHeight()))
                    || (mergeType == MergeType.UP && FloatUtils.feq(rect.getTop(), downBlock.getBottom(), 2 * downBlock.getAvgCharHeight()));
            if (witchinHeightScope && downBlock.getSize() >= 2 && TableRegionDetectionUtils.isLargeGap(downBlock, downBlock.getAvgCharWidth(), 1)) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
        }

        //某行的上下两行满足多列对齐，且行间距较小则一定是表格
        if (!isSerialNumberLine(downBlock) && !TABLE_BEGINNING_KEYWORD.matcher(downBlock.getText().trim().toLowerCase()).matches()
                && TableRegionDetectionUtils.isLargeGap(bottomTextBlock, 2 * bottomTextBlock.getAvgCharWidth(), 1)
                && TableRegionDetectionUtils.isLargeGap(nextDownBlock, 2 * nextDownBlock.getAvgCharWidth(), 1)
                && TableRegionDetectionUtils.isVerticalNeighborText(bottomTextBlock, downBlock, 1.5f * page.getAvgCharHeight())
                && TableRegionDetectionUtils.isVerticalNeighborText(downBlock, nextDownBlock, 1.5f * page.getAvgCharHeight())
                && TableRegionDetectionUtils.hasNumberStr(bottomTextBlock.getElements())
                && TableRegionDetectionUtils.hasNumberStr(nextDownBlock.getElements())) {
            if ((downBlock.getSize() == 1) && (downBlock.getTextNoRedoLength() / pageValidWidth > 2.0/3.0)) {
                return true;
            }
            regionBase.addTextBlockToList(downBlock);
            regionBase.addTextBlockToList(nextDownBlock);
            return false;
        }

        //GIC: 1-2.pdf page222
        Pattern SPECIAL_GIC_1 = Pattern.compile(".*[:：].*");
        if (mergeType == MergeType.UP && !isSerialNumberLine(downBlock) && SPECIAL_GIC_1.matcher(downBlock.getText()).matches()
                && !TableRegionDetectionUtils.isLargeGap(downBlock, downBlock.getAvgCharWidth(), 1)
                && downBlock.getLeft() - regionBase.getLeft() > -page.getMinCharWidth()
                && downBlock.getWidth() < 0.3 * regionBase.getWidth()
                && FloatUtils.feq(bottomTextBlock.getTop(), downBlock.getBottom(), 1.5 * downBlock.getAvgCharHeight())) {
            if (!isSerialNumberLine(bottomTextBlock) && SPECIAL_GIC_1.matcher(bottomTextBlock.getText()).matches()
                    && !TableRegionDetectionUtils.isLargeGap(bottomTextBlock, bottomTextBlock.getAvgCharWidth(), 1)
                    && bottomTextBlock.getLeft() - regionBase.getLeft() > -page.getMinCharWidth()
                    && bottomTextBlock.getWidth() < 0.3 * regionBase.getWidth()
                    && (((TableRegionDetectionUtils.isLargeGap(previousBottomTextBlock, 1.5f * previousBottomTextBlock.getAvgCharWidth(), 3)
                    || TableRegionDetectionUtils.isLargeGap(previousBottomTextBlock, 4f * previousBottomTextBlock.getAvgCharWidth(), 1))
                    && FloatUtils.feq(previousBottomTextBlock.getTop(), bottomTextBlock.getBottom(), 1.5 * downBlock.getAvgCharHeight()))
                    || ((TableRegionDetectionUtils.isLargeGap(nextDownBlock, 1.5f * nextDownBlock.getAvgCharWidth(), 3)
                    || TableRegionDetectionUtils.isLargeGap(nextDownBlock, 4f * nextDownBlock.getAvgCharWidth(), 1))
                    && FloatUtils.feq(downBlock.getTop(), nextDownBlock.getBottom(), 1.5 * downBlock.getAvgCharHeight())))) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
            if (!isSerialNumberLine(nextDownBlock) && SPECIAL_GIC_1.matcher(nextDownBlock.getText()).matches()
                    && !TableRegionDetectionUtils.isLargeGap(nextDownBlock, nextDownBlock.getAvgCharWidth(), 1)
                    && nextDownBlock.getLeft() - regionBase.getLeft() > -page.getMinCharWidth()
                    && nextDownBlock.getWidth() < 0.3 * regionBase.getWidth()
                    && (TableRegionDetectionUtils.isLargeGap(bottomTextBlock, 2f * bottomTextBlock.getAvgCharWidth(), 1)
                    && TableRegionDetectionUtils.hasNumberStr(bottomTextBlock.getElements()))) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
        }
        if ((mergeType == MergeType.UP && !isSerialNumberLine(bottomTextBlock) && SPECIAL_GIC_1.matcher(bottomTextBlock.getText()).matches()
                && !TableRegionDetectionUtils.isLargeGap(bottomTextBlock, bottomTextBlock.getAvgCharWidth(), 1)
                && bottomTextBlock.getLeft() - regionBase.getLeft() > -page.getMinCharWidth()
                && bottomTextBlock.getWidth() < 0.3 * regionBase.getWidth()
                && FloatUtils.feq(bottomTextBlock.getTop(), downBlock.getBottom(), 1.5 * downBlock.getAvgCharHeight()))
                && (!isSerialNumberLine(previousBottomTextBlock) && SPECIAL_GIC_1.matcher(previousBottomTextBlock.getText()).matches()
                && !TableRegionDetectionUtils.isLargeGap(previousBottomTextBlock, previousBottomTextBlock.getAvgCharWidth(), 1)
                && previousBottomTextBlock.getLeft() - regionBase.getLeft() > -page.getMinCharWidth()
                && previousBottomTextBlock.getWidth() < 0.3 * regionBase.getWidth()
                && FloatUtils.feq(previousBottomTextBlock.getTop(), bottomTextBlock.getBottom(), 1.5 * downBlock.getAvgCharHeight()))) {
            if (TableRegionDetectionUtils.isLargeGap(downBlock, 1.5f * downBlock.getAvgCharWidth(), 3)
                    || TableRegionDetectionUtils.isLargeGap(downBlock, 4f * downBlock.getAvgCharWidth(), 1)) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
        }

        //ruling info
        boolean specialMatchFlag = TableRegionDetectionUtils.matchAnyText(downBlock.getElements(), TABLE_BEGINNING_KEYWORD)
                || TableRegionDetectionUtils.matchAnyText(downBlock.getElements(), CHART_BEGINNING_KEYWORD)
                || TableRegionDetectionUtils.matchAnyText(downBlock.getElements(), MEASURE_UNIT_RE)
                || TableRegionDetectionUtils.matchAnyText(downBlock.getElements(), SERIAL_NUMBER_ROW_RE_0)
                || TableRegionDetectionUtils.matchAnyText(downBlock.getElements(), SERIAL_NUMBER_ROW_RE_1)
                || TableRegionDetectionUtils.matchAnyText(downBlock.getElements(), SERIAL_NUMBER_ROW_RE_2);
        if (!specialMatchFlag && !findLongRulingBetween(hRulingInProcessRect, regionBase.getTop() + 0.5f * page.getAvgCharHeight()
                , regionBase.getBottom() - 0.5f * page.getAvgCharHeight(), 0.8f * pageValidWidth).isEmpty()
                && ((mergeType == MergeType.UP && FloatUtils.feq(downBlock.getBottom(), regionBase.getTop(), 1.5 * page.getAvgCharHeight()))
                || (mergeType == MergeType.DOWN && FloatUtils.feq(downBlock.getTop(), regionBase.getBottom(), 1.5 * page.getAvgCharHeight())))) {
            List<Ruling> rulings = findLongRulingBetween(hRulingInProcessRect, (float)bottomTextBlock.getCenterY()
                    , (float)downBlock.getCenterY(), 0.8f * pageValidWidth);
            boolean hasUnderRuling = rulings.size() == 1;
            boolean hasIntersectRuling = RulingTableRegionsDetectionAlgorithm.hasIntersectionLines(rulings, page.getVerticalRulings());
            if (hasUnderRuling && !hasIntersectRuling && FloatUtils.feq(downBlock.getBottom(), rulings.get(0).getTop(), page.getAvgCharHeight())) {
                if (downBlock.getSize() == 1 && regionBase.nearlyHorizontalContains(downBlock.toRectangle()
                        , 0.5 * downBlock.getAvgCharWidth()) && isOverlapSingleColumn(columns, downBlock)
                        && downBlock.getLeft() - regionBase.getLeft() > 5 * downBlock.getAvgCharWidth()) {
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                } else if (downBlock.getSize() >= 2 && TableRegionDetectionUtils.isLargeGap(downBlock, 1.2f * page.getAvgCharWidth(), 1)) {
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                }
            }
        }

        if (downBlock.getSize() == 1) {
            //段落结尾行：文本左对齐且有标点符号
            if ((PARAGRAPH_END_RE.matcher(lastChunkText).matches() && FloatUtils.feq(rect.getMinX(), downBlock.getLeft()
                    , 3 * page.getAvgCharWidth()))) {
                return true;
            }

            //表头
            if ((!SERIAL_NUMBER_ROW_RE_0.matcher(downBlock.getText()).matches() && MEASURE_UNIT_RE.matcher(lastChunkText).matches())) {
                return true;
            }
            if (lastChunk.getWidth() > rect.getWidth() * 2.0f / 3.0f) {
                float textBlockLength = (float) downBlock.getWidth();
                boolean hasRulingBottom = findLongRulingBetween(hRulingInProcessRect, downBlock, bottomTextBlock, 2.0f / 3.0f * textBlockLength).size() > 0;
                boolean hasRulingTop = findLongRulingBetween(hRulingInProcessRect, nextDownBlock, downBlock, 2.0f / 3.0f * textBlockLength).size() > 0;
                if (!nextDownBlock.isEmpty() && hasRulingTop && hasRulingBottom && !MEASURE_UNIT_RE.matcher(nextDownBlock.getText().trim()).matches()) {
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                }
                return true;
            }

            // 页码行
            if (mergeType == MergeType.DOWN && (PAGE_FOOTER_RE_1.matcher(downBlock.getText().trim()).matches()) && (downBlock.getBottom() / page.getHeight() > 0.65)) {
                return true;
            }

            //目录
            String downBlockText = downBlock.getText().replace(" ", "").toLowerCase();
            if (mergeType == MergeType.UP && hasSpecialStr(downBlockText.trim().toLowerCase(), SPECIAL_STR_CONTENT_RE) && FloatUtils.feq(rect.getCenterX()
                    , downBlock.getCenterX(), 5 * page.getAvgCharWidth())) {
                return true;
            }

            //含有数字串的行判断
            boolean witchinHeightScope = (mergeType == MergeType.DOWN && FloatUtils.feq(rect.getBottom(), downBlock.getTop(), 2 * downBlock.getAvgCharHeight()))
                    || (mergeType == MergeType.UP && FloatUtils.feq(rect.getTop(), downBlock.getBottom(), 2 * downBlock.getAvgCharHeight()));
            if (witchinHeightScope && rect.nearlyHorizontalContains(downBlock.toRectangle(), downBlock.getAvgCharHeight())
                    && TableRegionDetectionUtils.isNumberStr(downBlock.getText().trim()) && downBlock.getLeft() - rect.getLeft() > rect.getWidth() / 3.0) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }

            //标题
            if (bottomTextBlock.getSize() > 1 && nextDownBlock.getSize() > 1) {
                boolean centerFlag = FloatUtils.feq(rect.getCenterX(), downBlock.getCenterX(), 8 * downBlock.getAvgCharWidth())
                        && FloatUtils.feq(nextDownBlock.getCenterX(), downBlock.getCenterX(), 8 * downBlock.getAvgCharWidth())
                        && FloatUtils.feq(processRect.getCenterX(), downBlock.getCenterX(), 8 * downBlock.getAvgCharWidth());
                boolean nearFlag = mergeType == MergeType.UP && (bottomTextBlock.getTop() - downBlock.getBottom() > 1.5 * downBlock.getAvgCharHeight()
                        || downBlock.getTop() - nextDownBlock.getBottom() > 1.5 * downBlock.getAvgCharHeight());
                if (centerFlag && nearFlag) {
                    return true;
                }
            }
            if (downBlock.getVisibleHeight() > 1.5 * bottomTextBlock.getVisibleHeight()
                    && nextDownBlock != null && downBlock.getVisibleHeight() > 1.5 * nextDownBlock.getVisibleHeight()
                    && FastMath.abs(nextDownBlock.getSize() - bottomTextBlock.getSize()) >= 2
                    && TABLE_TITLE_ENGLISH_RE.matcher(downBlock.getText().trim()).matches()
                    && FloatUtils.feq(regionBase.getLeft(), downBlock.getLeft(), 2 * downBlock.getAvgCharWidth())) {
                return true;
            }

            //如果某文本行的上下文本行满足对齐关系，且行行间距小，则该行为表格区域
            if (nextDownBlock.getElements().size() > 1 && isVerticalHardAlign(page, Arrays.asList(bottomTextBlock, nextDownBlock)
                    , 1.5f * page.getAvgCharWidth())) {
                double heightRatio = downBlock.getVisibleHeight() / bottomTextBlock.getVisibleHeight();
                if ((heightRatio > 1.3 || heightRatio < (1.0 / 1.3)) && FloatUtils.feq(downBlock.getCenterX()
                        , processRect.getCenterX(), 5 * processRect.getCenterX())
                        && TABLE_BEGINNING_KEYWORD.matcher(downBlock.getText().trim()).matches()) {
                    return true;
                }
                //将downBlock和nextDownBlock均加入到regionBase中
                regionBase.addTextBlockToList(downBlock);
                if (FloatUtils.feq(rect.getMinX(), nextDownBlock.getLeft(), 5 * nextDownBlock.getAvgCharWidth()) && nextDownBlock.getSize() <= 2
                        && SERIAL_NUMBER_ROW_RE_0.matcher(nextDownBlock.getText()).matches()) {
                    regionBase.addTextBlockToList(nextDownBlock);
                    return false;
                }
            }

            //特殊行，比如文本行上下有线存在
            if (mergeType == MergeType.UP && FloatUtils.feq(bottomTextBlock.getTop(), downBlock.getBottom(), 2 * downBlock.getAvgCharHeight())) {
                float textBlockLength = (float) downBlock.getWidth();
                boolean hasRulingBetween = findLongRulingBetween(hRulingInProcessRect, downBlock, bottomTextBlock, 2.0f / 3.0f * textBlockLength).size() > 0;
                if (hasRulingBetween && !SPECIAL_ROW_STR_RE.matcher(downBlock.getText().trim()).matches()) {
                    if (FloatUtils.feq(downBlock.getLeft(), regionBase.getLeft(), 0.5 * bottomTextBlock.getAvgCharWidth()) &&
                            Pattern.compile(".*[)）][：:]$").matcher(downBlock.getText().trim()).matches()) {
                        return true;
                    }
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                }

                if (nextDownBlock.getSize() > 0) {
                    boolean hasRulingBottom = findLongRulingBetween(hRulingInProcessRect, downBlock, bottomTextBlock, 2.0f / 3.0f * textBlockLength).size() > 0;
                    boolean hasRulingTop = findLongRulingBetween(hRulingInProcessRect, nextDownBlock, downBlock, 2.0f / 3.0f * textBlockLength).size() > 0;
                    if (hasRulingTop && hasRulingBottom && DATA_STR_RE.matcher(downBlock.getText().trim()).find()
                            && FloatUtils.feq(downBlock.getBottom(), bottomTextBlock.getTop(), 2 * downBlock.getAvgCharHeight())
                            && downBlock.getLeft() - rect.getLeft() > 2 * downBlock.getAvgCharWidth()
                            && downBlock.getWidth() < rect.getWidth()) {
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }
                }
            }

            if ((FloatUtils.feq(downBlock.getBottom(), bottomTextBlock.getTop(), 2.5 * downBlock.getAvgCharHeight())
                    || FloatUtils.feq(downBlock.getTop(), bottomTextBlock.getBottom(), 2.5 * downBlock.getAvgCharHeight()))
                    && !PARAGRAPH_END_RE.matcher(lastChunkText).matches() && !SERIAL_NUMBER_ROW_RE_0.matcher(downBlock.getText().trim()).matches()) {
                boolean fontLargeDiff = downBlock.getAvgCharHeight() > bottomTextBlock.getAvgCharHeight() * 1.5
                        || bottomTextBlock.getAvgCharHeight() > downBlock.getAvgCharHeight() * 1.5;
                boolean hasLongRuling = findLongRulingBetween(hRulingInProcessRect, downBlock, bottomTextBlock, (float)(0.75 * rect.getWidth())).size() > 0;

                if (fontLargeDiff || hasLongRuling) {
                    return true;
                }

                boolean isOverlap =  downBlock.toRectangle().isHorizontallyOverlap(bottomTextBlock.toRectangle());
                if (SPECIAL_ROW_STR_RE.matcher(downBlock.getText().trim()).matches() && isOverlap) {
                    if (downBlock.getLeft() - regionBase.getLeft() > 4 * downBlock.getAvgCharWidth()
                            && TABLE_NOTE_RE_1.matcher(downBlock.getText().trim()).matches()) {
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }
                    return true;
                }

                //table header
                if (FloatUtils.feq(downBlock.getLeft(), rect.getLeft(), 2 * downBlock.getAvgCharWidth())
                        && this.hasTableHeader(page, columns, regionBase) && !FloatUtils.feq(downBlock.getLeft()
                        , bottomTextBlock.getLeft(), 3 * bottomTextBlock.getAvgCharWidth())) {
                    return true;
                }

                if ((downBlock.getLeft() - rect.getLeft() > -1 * downBlock.getAvgCharWidth())
                        && FloatUtils.feq(downBlock.getLeft(), rect.getLeft(), 4 * downBlock.getAvgCharWidth())
                        && this.isOverlapSingleColumn(columns, downBlock) && !MEASURE_UNIT_RE.matcher(lastChunkText).matches()) {
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                } else if (FloatUtils.feq(downBlock.getCenterX(), rect.getCenterX(), 5 * downBlock.getAvgCharWidth())) {
                    if (MEASURE_UNIT_RE.matcher(lastChunkText).matches()) {
                        return true;
                    } else {
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }
                } else {
                    if (!MEASURE_UNIT_RE.matcher(lastChunkText).matches()) {
                        if (downBlock.getSize() == 1 && nextDownBlock.getSize() == 1
                                && Math.abs(downBlock.getCenterY() - bottomTextBlock.getCenterY()) > 2 * downBlock.getVisibleHeight()
                                && FloatUtils.feq(downBlock.getCenterX(), processRect.getCenterX(), 5 * downBlock.getAvgCharWidth())
                                && Math.abs(downBlock.getCenterX() - nextDownBlock.getCenterX()) > 8 * downBlock.getAvgCharWidth()
                                && Math.abs(downBlock.getLeft() - nextDownBlock.getLeft()) > 5 * downBlock.getAvgCharWidth()
                                && Math.abs(downBlock.getRight() - nextDownBlock.getRight()) > 5 * downBlock.getAvgCharWidth()) {
                            return true;
                        }
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    } else {
                        return true;
                    }
                }
            }
        } else {
            List<TextChunk> te = downBlock.getElements();
            //如果相邻文本块高度相差太大，则一定不是正文
            for (int i = 0; i < downBlock.getSize() - 1; i++) {
                if (te.get(i).getWidth() < 0.8 * pageValidWidth && te.get(i + 1).getWidth() < 0.8 * pageValidWidth
                        && !(SPECIAL_SYMBOL_RE.matcher(downBlock.getText()).matches())
                        && (te.get(i + 1).getHeight() / te.get(i).getHeight() > 1.5 || te.get(i + 1).getHeight() / te.get(i).getHeight() < 1.0 / 1.5)) {
                    Rectangle one = te.get(i).toRectangle();
                    Rectangle other = te.get(i + 1).toRectangle();
                    //文本行合并错误情况则直接返回
                    if (one.verticalOverlap(other) > 0.3 * downBlock.getAvgCharHeight()
                            && one.horizontalOverlap(other) > downBlock.getAvgCharWidth()) {
                        return true;
                    } else {
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }
                }
            }

            //文本行的起始字符为特殊字符
            if (SPECIAL_SYMBOL_RE.matcher(downBlock.getText()).matches() && FloatUtils.feq(rect.getMinX(), downBlock.getLeft()
                    , 3 * page.getAvgCharWidth())) {
                return true;
            }

            //根据文本块间距判断,文本块间距非常大，则一定是表格区域
            HashMap<Point2D, java.lang.Float> textBlockGapMap = TableRegionDetectionUtils.calVerticalProjectionGap(te);
            boolean hugeGapFlag = TableRegionDetectionUtils.countLargeGap(textBlockGapMap, 3 * page.getAvgCharWidth()) >= 1;
            boolean littleLargeGapFlag = TableRegionDetectionUtils.countLargeGap(textBlockGapMap, 1.5f * page.getAvgCharWidth()) >= 1;
            if ((hugeGapFlag && !SERIAL_NUMBER_ROW_RE_0.matcher(downBlock.getText()).matches()) || (littleLargeGapFlag
                    && isVerticalHardAlign(page, Arrays.asList(bottomTextBlock, downBlock)
                    , 1.5f * page.getAvgCharWidth()))) {
                //间隙较大且为相隔较近的多列则为表格区域
                regionBase.addTextBlockToList(downBlock);
                return false;
            } else {
                //间隙较小（可能为文本块合并问题，也可能是文本间距太小）则判断文本的长度，以及上下行的对齐关系
                boolean hasLongRuling = false;
                for (Ruling rul : hRulingInProcessRect) {
                    if (mergeType == MergeType.DOWN && rul.getTop() > downBlock.getBottom() && !nextDownBlock.isEmpty()
                            && rul.getTop() < nextDownBlock.getTop()
                            && FloatUtils.feq(rul.getTop(), downBlock.getBottom(), page.getAvgCharHeight())) {
                        hasLongRuling = ((rul.getWidth() / rect.getWidth()) > 3.0 / 4.0);
                    }
                }
                if (hasLongRuling) {
                    regionBase.addTextBlockToList(downBlock);
                    return false;
                }
                if (downBlock.getTextNoRedoLength() > ((2.0f / 3.0f) * pageValidWidth)) {
                    return true;
                }
            }

            //距离较近且字符串相同的文本行较大可能为表格
            if (downBlock.getSize() >= 3 && (bottomTextBlock.getSize() > 2 || nextDownBlock.getSize() > 2)
                    && ((mergeType == MergeType.UP && FloatUtils.feq(downBlock.getBottom(), bottomTextBlock.getTop(), 1.5 * page.getAvgCharHeight()))
                    || (mergeType == MergeType.DOWN && FloatUtils.feq(downBlock.getTop(), bottomTextBlock.getBottom(), 1.5 * page.getAvgCharHeight())))) {
                boolean hasMultiElement = true;
                for (TextChunk tc : downBlock.getElements()) {
                    if (tc.getElements().size() < 3) {
                        hasMultiElement = false;
                    }
                }
                if (hasMultiElement) {
                    List<TextChunk> textChunks = downBlock.getElements();
                    for (int i = 0; i < downBlock.getSize() - 1; i++) {
                        if (StringUtils.equals(textChunks.get(i).getText().trim(), textChunks.get(i + 1).getText().trim())) {
                            regionBase.addTextBlockToList(downBlock);
                            return false;
                        }
                    }
                }
            }

            //多列属性行判断
            if (downBlock.getSize() >= 2 && TableRegionDetectionUtils.isLargeGap(downBlock, downBlock.getAvgCharWidth(), 1)
                    && downBlock.getLeft() - rect.getLeft() > 4 * page.getAvgCharWidth()) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
            //所有行判断
            if (downBlock.getSize() == columns.size() && this.isAlignByTableColumn(columns, downBlock)) {
                regionBase.addTextBlockToList(downBlock);
                return false;
            }
            //邻近行判断
            if (downBlock.getSize() == bottomTextBlock.getSize()) {
                HashMap<Point2D, java.lang.Float> gapMap = calVerticalHardAlignGap(page, Arrays.asList(bottomTextBlock, downBlock));
                if (!gapMap.isEmpty() && gapMap.size() == downBlock.getSize() - 1) {
                    for (Float gap : gapMap.values()) {
                        if (gap > downBlock.getAvgCharWidth()) {
                            regionBase.addTextBlockToList(downBlock);
                            return false;
                        }
                    }
                }
            }

            //多列间隔距离较近的行
            List<TextChunk> rawTextChunks = page.getMutableTextChunks(downBlock.toRectangle());
            List<TextBlock> rawTextBlocks = TextMerger.collectByLines(rawTextChunks);
            if (rawTextChunks.size() > downBlock.getSize() && rawTextBlocks.size() >= 2) {
                for (TextBlock tb : rawTextBlocks) {
                    if (tb.getSize() >= 3 && (isAlignByTableColumn(columns, tb) || TableRegionDetectionUtils.isLargeGap(tb, tb.getAvgCharWidth(), 3))) {
                        regionBase.addTextBlockToList(downBlock);
                        return false;
                    }
                }
            }
        }

        //date line
        if (TableRegionDetectionUtils.calStrMatchNum(DATA_STR_RE, downBlock.getText().trim()) >= 2
                && downBlock.getLeft() - regionBase.getLeft() > 4 * downBlock.getAvgCharWidth()
                && downBlock.getRight() - regionBase.getRight() < downBlock.getAvgCharWidth()
                && downBlock.getTextNoRedoLength() < 1.5 * pageValidWidth
                && (TableRegionDetectionUtils.isLargeGap(downBlock, 1.5f * downBlock.getAvgCharWidth(), 1)
                || TableRegionDetectionUtils.isLargeGap(bottomTextBlock, 2f * downBlock.getAvgCharWidth(), 1))) {
            regionBase.addTextBlockToList(downBlock);
            return false;
        }

        return true;
    }

    private void mergeNeighborCandidateRegion(ContentGroupPage page, List<TextBlock> candidateTextBlocks, List<TableRegion> candidateRegions) {
        if (candidateRegions.size() < 2) {
            return;
        }

        candidateRegions.sort(Comparator.comparing(TableRegion::getTop));
        for (int i = 0; i < candidateRegions.size() - 1;) {
            float reGapMinX = (float)FastMath.min(candidateRegions.get(i).getMinX(), candidateRegions.get(i + 1).getMinX());
            float reGapMinY = (float)candidateRegions.get(i).getMaxY();
            float reGapMaxX = (float)FastMath.max(candidateRegions.get(i).getMaxX(), candidateRegions.get(i + 1).getMaxX());
            float reGapMaxY = (float)candidateRegions.get(i + 1).getMinY();
            boolean mergeFlag = false;
            Rectangle filterArea =  (new Rectangle(reGapMinX, reGapMinY, reGapMaxX - reGapMinX, reGapMaxY - reGapMinY))
                    .rectReduce(0, 0.5, page.getWidth(), page.getHeight());
            if (reGapMaxY - reGapMinY < 2 * page.getAvgCharHeight() && !isExistNumberTextLine(page, candidateTextBlocks, filterArea)) {
                if (lineTableRegions.isEmpty()) {
                    candidateRegions.get(i).merge(candidateRegions.get(i + 1));
                    candidateRegions.remove(i + 1);
                    mergeFlag = true;
                } else {
                    //两个region之间没有未被合并的textblock
                    Rectangle rectGap = new Rectangle(reGapMinX, reGapMinY, reGapMaxX - reGapMinX, reGapMaxY - reGapMinY);
                    boolean hasTextBlockBetween = false;
                    for (TextBlock tb : candidateTextBlocks) {
                        if (tb.getMergeFlag()) {
                            continue;
                        }
                        if (rectGap.isShapeIntersects(tb.toRectangle())) {
                            hasTextBlockBetween = true;
                            break;
                        }
                    }
                    if (hasTextBlockBetween) {
                        i++;
                        continue;
                    }

                    for (TableRegion rcTemp : lineTableRegions) {
                        if (!rcTemp.isShapeIntersects(rectGap)) {
                            candidateRegions.get(i).merge(candidateRegions.get(i + 1));
                            candidateRegions.remove(i + 1);
                            mergeFlag = true;
                            break;
                        }
                    }
                }
            }
            if (!mergeFlag) {
                i++;
            }
        }
    }

    private boolean MergeDownByOneLine(ContentGroupPage page, List<TextBlock> candidateTextBlocks, TableRegion regionBase, List<Rectangle> columns, List<Ruling> hRulingInProcessRect, float pageValidWidth, LinkedHashMap<TextBlock, SerialNumberInfo> snMap, Rectangle processRect, List<FillAreaGroup> fillAreaGroupsInProcessRect) {
        TextBlock bottomTextBlock = regionBase.getTextBlockList().get(regionBase.getTextBlockList().size() - 1);
        TextBlock previousBottomTextBlock = new TextBlock();
        if (regionBase.getTextBlockList().size() > 1) {
            previousBottomTextBlock = regionBase.getTextBlockList().get(regionBase.getTextBlockList().size() - 2);
        }
        TextBlock downBlock = new TextBlock();
        TextBlock nextDownBlock = new TextBlock();
        Rectangle2D bottomTextBlockBound = bottomTextBlock.getBounds2D();

        List<TextBlock> textBlockListTemp = new ArrayList<>();
        for (TextBlock tb : candidateTextBlocks) {
            if (tb.getBounds2D().getMinY() > bottomTextBlockBound.getMinY()) {
                textBlockListTemp.add(tb);
            }
        }
        if (textBlockListTemp.isEmpty()) {
            return false;
        }

        for (int i = 0; i < textBlockListTemp.size(); i++) {
            TextBlock tb = textBlockListTemp.get(i);
            Rectangle2D tbBound = tb.getBounds2D();
            if (!tb.getMergeFlag() && FloatUtils.feq(bottomTextBlockBound.getMaxY(), tbBound.getMinY(),2.5 * page.getAvgCharHeight())) {
                downBlock = tb;
                if ((i + 1 <= textBlockListTemp.size() - 1) && FloatUtils.feq(tbBound.getMaxY(), textBlockListTemp.get(i + 1).getTop()
                        , 2.5 * page.getAvgCharHeight())) {
                    nextDownBlock = textBlockListTemp.get(i + 1);
                }
                break;
            }
        }

        if (downBlock.getElements().isEmpty()) {
            return false;
        } else if (isTextBodyLine(page, regionBase, previousBottomTextBlock, bottomTextBlock, downBlock, nextDownBlock, columns, hRulingInProcessRect, pageValidWidth, snMap, processRect, MergeType.DOWN, fillAreaGroupsInProcessRect)) {
            return false;
        }

        return true;
    }

    private boolean MergeUpByOneLine(ContentGroupPage page, List<TextBlock> candidateTextBlocks, TableRegion regionBase, List<Rectangle> columns, List<Ruling> hRulingInProcessRect, float pageValidWidth, LinkedHashMap<TextBlock, SerialNumberInfo> snMap, Rectangle processRect, List<FillAreaGroup> fillAreaGroupsInProcessRect) {
        TextBlock topTextBlock = regionBase.getTextBlockList().get(0);
        TextBlock previousTopTextBlock = new TextBlock();
        if (regionBase.getTextBlockList().size() > 1) {
            previousTopTextBlock = regionBase.getTextBlockList().get(1);
        }
        TextBlock upBlock = new TextBlock();
        TextBlock nextUpBlock = new TextBlock();
        Rectangle2D topTextBlockBound = topTextBlock.getBounds2D();

        List<TextBlock> textBlockListTemp = new ArrayList<>();
        for (TextBlock tb : candidateTextBlocks) {
            if (tb.getTop() < topTextBlockBound.getMinY()) {
                textBlockListTemp.add(tb);
            }
        }
        if (textBlockListTemp.isEmpty()) {
            return false;
        }
        for (int i = textBlockListTemp.size() - 1; i >= 0; i--) {
            TextBlock tb = textBlockListTemp.get(i);
            Rectangle2D tbBound = tb.getBounds2D();
            if (!tb.getMergeFlag() && FloatUtils.feq(topTextBlockBound.getMinY(), tbBound.getMaxY(),3 * page.getAvgCharHeight())) {
                upBlock = tb;
                if ((i - 1 >= 0) && FloatUtils.feq(tbBound.getMinY(), textBlockListTemp.get(i - 1).getBottom()
                        , 3 * page.getAvgCharHeight())) {
                    nextUpBlock = textBlockListTemp.get(i - 1);
                }
                break;
            }
        }

        if (upBlock.getElements().isEmpty()) {
            return false;
        } else if (isTextBodyLine(page, regionBase, previousTopTextBlock, topTextBlock, upBlock, nextUpBlock, columns, hRulingInProcessRect, pageValidWidth, snMap, processRect, MergeType.UP, fillAreaGroupsInProcessRect)) {
            return false;
        }

        return true;
    }

    private List<TableRegion> mergeMostPossibleRegion(ContentGroupPage page, List<TextBlock> candidateTextBlocks, List<TableRegion> candidateRegions, List<Ruling> hRulingInProcessRect, Rectangle processRect, float pageValidWidth, LinkedHashMap<TextBlock, SerialNumberInfo> snMap, List<FillAreaGroup> fillAreaGroupsInProcessRect) {
        if (candidateRegions.isEmpty()) {
            return new ArrayList<>();
        }

        //对相邻候选区域进行融合
        mergeNeighborCandidateRegion(page, candidateTextBlocks, candidateRegions);

        //从最大区域开始向下查找融合
        candidateRegions.sort(Comparator.comparing(TableRegion::getHeight).reversed());
        for (int i = 0; i < candidateRegions.size(); ) {
            TableRegion regionBase = candidateRegions.get(i);
            //merge down by one line
            boolean mergeFlag = true;
            while (mergeFlag) {
                int mergeIdx = -1;
                for (int j = 0; j < candidateRegions.size(); j++) {
                    if (j != i && (regionBase.isShapeIntersects(candidateRegions.get(j)) || (candidateRegions.get(j).getBottom() > regionBase.getBottom()
                            && FloatUtils.feq(regionBase.getBottom(), candidateRegions.get(j).getTop(), 3 * page.getAvgCharHeight())))
                            && !TableRegionDetectionUtils.hasTextBlockWithinShape(page, new Rectangle(regionBase.getLeft(), regionBase.getBottom()
                            , (float)regionBase.getWidth(), candidateRegions.get(j).getTop() - regionBase.getBottom()), candidateTextBlocks)) {
                        regionBase.merge(candidateRegions.get(j));
                        candidateRegions.remove(j);
                        mergeIdx = j;
                        break;
                    }
                }
                if (mergeIdx != -1 && mergeIdx < i) {
                    i--;
                }
                List<Rectangle> columns = TableRegionDetectionUtils.getColumnPositions(page, regionBase, regionBase.getTextBlockList());
                mergeFlag = MergeDownByOneLine(page, candidateTextBlocks, regionBase, columns, hRulingInProcessRect, pageValidWidth, snMap, processRect, fillAreaGroupsInProcessRect);
            }
            if (!mergeFlag) {
                i++;
            }
        }

        //从最大区域开始向上融合
        candidateRegions.sort(Comparator.comparing(TableRegion::getHeight).reversed());
        for (int i = 0; i < candidateRegions.size(); ) {
            TableRegion regionBase = candidateRegions.get(i);
            //merge up by one line
            boolean mergeFlag = true;
            while (mergeFlag) {
                int mergeIdx = -1;
                for (int j = 0; j < candidateRegions.size(); j++) {
                    if (j != i && (regionBase.isShapeIntersects(candidateRegions.get(j)) || (candidateRegions.get(j).getTop() < regionBase.getTop()
                            && FloatUtils.feq(regionBase.getTop(), candidateRegions.get(j).getBottom(), 3 * page.getAvgCharHeight()))
                            && !TableRegionDetectionUtils.hasTextBlockWithinShape(page, new Rectangle(regionBase.getLeft(), candidateRegions.get(j).getBottom()
                            , (float)regionBase.getWidth(), regionBase.getTop() - candidateRegions.get(j).getBottom()), candidateTextBlocks))) {
                        TextBlock firstTextBlock = regionBase.getTextBlockList().get(0);
                        TextBlock lastTextBlock = candidateRegions.get(j).getTextBlockList().get(candidateRegions.get(j).getTextBlockList().size() - 1);
                        if (!(lastTextBlock.getColumnCount() != firstTextBlock.getColumnCount() &&
                                TABLE_END_RE.matcher(lastTextBlock.getElements().get(0).getText().trim()).matches())) {
                            regionBase.merge(candidateRegions.get(j));
                            candidateRegions.remove(j);
                            mergeIdx = j;
                            break;
                        }
                        regionBase.merge(candidateRegions.get(j));
                        candidateRegions.remove(j);
                        mergeIdx = j;
                        break;
                    }
                }
                if (mergeIdx != -1 && mergeIdx < i) {
                    i--;
                }
                List<Rectangle> columns = TableRegionDetectionUtils.getColumnPositions(page, regionBase, regionBase.getTextBlockList());
                mergeFlag = MergeUpByOneLine(page, candidateTextBlocks, regionBase, columns, hRulingInProcessRect, pageValidWidth, snMap, processRect, fillAreaGroupsInProcessRect);
            }
            if (!mergeFlag) {
                i++;
            }
        }

        //对相邻候选区域进行融合
        mergeNeighborCandidateRegion(page, candidateTextBlocks, candidateRegions);
        return candidateRegions;
    }

    /**
     * 检测及修正特殊表格标题的三线表、二线表（少部分三线表由于列间距太小未被检测）
     * @return
     */
    private void mergeSpecialTableRegion(ContentGroupPage page, List<TextBlock> candidateTextBlocks, List<TableRegion> candidateRegions, List<Ruling> hRulingInProcessRect, Rectangle processRect, float pageValidWidth) {
        List<TextBlock> titleLines = new ArrayList<>();
        Pattern TABLE_BEGINNING_KEYWORD_OF_STRICT = Pattern.compile("(^(表|表格)[(（]?[0-9]{1,2}.*)|(^(表|表格)[零一二三四五六七八九十].*)" +
                "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
                "|(^(the following table).*)");
        for (TextBlock textBlock : candidateTextBlocks) {
            if (TABLE_BEGINNING_KEYWORD_OF_STRICT.matcher(textBlock.getText().trim().toLowerCase()).matches()) {
                titleLines.add(textBlock);
            }
        }

        if (titleLines.isEmpty()) {
            return;
        }

        candidateRegions.sort(Comparator.comparing(TableRegion::getTop));
        for (TextBlock titleLine : titleLines) {
            TableRegion tableRegion = null;
            for (TableRegion tr : candidateRegions) {
                if (tr.getTop() > titleLine.getCenterY() && tr.getTop() - titleLine.getCenterY() < 2 * titleLine.getAvgCharHeight()
                        && tr.horizontallyOverlapRatio(titleLine.toRectangle()) > 0.2) {
                    tableRegion = tr;
                    break;
                }
            }

            if (tableRegion != null) {
                List<Ruling> rulings = Ruling.getRulingsFromArea(page.getHorizontalRulings()
                        , tableRegion.withCenterExpand(0, 0.5 * titleLine.getAvgCharHeight()));
                if (!rulings.isEmpty()) {
                    boolean isNearlyEqualLength = true;
                    if (rulings.size() > 1) {
                        for (int i = 0; i < rulings.size() - 1; i++) {
                            if (!FloatUtils.feq(rulings.get(i).getLeft(), rulings.get(i + 1).getLeft(), 1.0)
                                    || !FloatUtils.feq(rulings.get(i).getRight(), rulings.get(i + 1).getRight(), 1.0)) {
                                isNearlyEqualLength = false;
                            }
                        }
                    }
                    if (isNearlyEqualLength) {
                        boolean mergeFlag = true;
                        while (mergeFlag) {
                            mergeFlag = false;
                            List<Rectangle> columns = TableRegionDetectionUtils.getColumnPositions(page, tableRegion, tableRegion.getTextBlockList());
                            TextBlock downTextBlock = TableRegionDetectionUtils.getBottomTextBlock(tableRegion, candidateTextBlocks, tableRegion.getBottom());
                            if (downTextBlock != null && !downTextBlock.getMergeFlag()
                                    && TableRegionDetectionUtils.splitChunkByRow(downTextBlock.getElements()).size() <= 2
                                    && !TABLE_FILTER_STR_RE.matcher(downTextBlock.getText().trim().toLowerCase()).matches()
                                    && FloatUtils.feq(downTextBlock.getTop(), tableRegion.getBottom(), 2 * downTextBlock.getAvgCharHeight())
                                    && (downTextBlock.getSize() == 1 && isOverlapSingleColumn(columns, downTextBlock))) {
                                tableRegion.addTextBlockToList(downTextBlock);
                                downTextBlock.setMergeFlag();
                                mergeFlag = true;
                            }
                        }
                    }
                }
            } else {
                return;
            }
        }
    }

    private void mergePossibleSplitRegions(ContentGroupPage page, List<TableRegion> candidateRegions, List<TextBlock> textLines, List<TextBlock> unnormalTextBlocks) {
        if (candidateRegions.isEmpty()) {
            return;
        }

        // 相邻两个表格区域之间，没有文本块，且满足一定的距离，融合为一个区域
        TableRegion baseRegion = candidateRegions.get(0);
        for (int i = 1; i < candidateRegions.size(); i++) {
            TableRegion otherRegion = candidateRegions.get(i);
            double baseAvgCharHeight = page.getText(baseRegion).parallelStream().mapToDouble(TextElement::getTextWidth).average()
                    .orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
            double otherAvgCharHeight = page.getText(baseRegion).parallelStream().mapToDouble(TextElement::getTextWidth).average()
                    .orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
            double avgCharHeight = (baseAvgCharHeight > otherAvgCharHeight) ? baseAvgCharHeight : otherAvgCharHeight;
            double baseMinBlankGap = getMinBlankGap(baseRegion);
            double otherMinBlankGap = getMinBlankGap(otherRegion);
            if (baseMinBlankGap > 0 && otherMinBlankGap > 0) {
                avgCharHeight += (baseMinBlankGap + otherMinBlankGap) / 2;
            } else if (baseMinBlankGap > 0 || otherMinBlankGap > 0){
                avgCharHeight += baseMinBlankGap + otherMinBlankGap;
            }

            float left = (baseRegion.getLeft() > otherRegion.getLeft()) ? otherRegion.getLeft() : baseRegion.getLeft();
            float right = (baseRegion.getRight() < otherRegion.getRight()) ? otherRegion.getRight() : baseRegion.getRight();
            Rectangle gapRegion = new Rectangle(left,baseRegion.getBottom(),right - left,
                    otherRegion.getTop() - baseRegion.getBottom());
            List<TextBlock> textBlockListTemp = TableRegionDetectionUtils.getTextBlockWithinShape(page, gapRegion, textLines);
            if (textBlockListTemp.isEmpty()) {
                if (FloatUtils.feq(baseRegion.getBottom(), otherRegion.getTop(), 2.5 * avgCharHeight)) {
                    baseRegion.merge(otherRegion);
                    otherRegion.markDeleted();
                } else {
                    List<Rectangle> baseColumns = TableRegionDetectionUtils.getColumnPositions(page, baseRegion, baseRegion.getTextBlockList());
                    List<Rectangle> otherColumns = TableRegionDetectionUtils.getColumnPositions(page, otherRegion, otherRegion.getTextBlockList());

                    //对齐判定
                    boolean columnsAlign = true;
                    if (baseColumns.size() == otherColumns.size()) {
                        for (int j = 0; j < baseColumns.size(); j++) {
                            if (!baseColumns.get(j).isHorizontallyOverlap(otherColumns.get(j))) {
                                columnsAlign = false;
                                break;
                            }
                        }
                    }

                    //表格分界线
                    boolean hasLongRuling = false;
                    for (Ruling rul : page.getHorizontalRulings()) {
                        if (rul.getTop() > baseRegion.getBottom() && rul.getTop() < otherRegion.getTop()
                                &&rul.getWidth() > (3.0 / 4.0) * FastMath.max(baseRegion.getWidth(), otherRegion.getWidth())
                                && (!FloatUtils.feq(rul.getTop(), baseRegion.getBottom(), baseAvgCharHeight)
                                || !FloatUtils.feq(rul.getTop(), otherRegion.getTop(), otherAvgCharHeight))) {
                            hasLongRuling = true;
                        }
                    }

                    //中文表头判断
                    boolean hasHeader = hasTableHeader(page, otherColumns, otherRegion);

                    if (hasHeader || !columnsAlign || hasLongRuling) {
                        baseRegion = otherRegion;
                        continue;
                    }
                    if (FloatUtils.feq(baseRegion.getBottom(), otherRegion.getTop(), 3.5 * avgCharHeight) && columnsAlign) {
                        baseRegion.merge(otherRegion);
                        otherRegion.markDeleted();
                    }
                }
            } else if (textBlockListTemp.size() == 1) {
                TextBlock betweenTextBlock = textBlockListTemp.get(0);
                List<Rectangle> baseColumns = TableRegionDetectionUtils.getColumnPositions(page, baseRegion, baseRegion.getTextBlockList());
                if (baseRegion.getCenterX() - otherRegion.getCenterX() > 2.5 * page.getAvgCharWidth()
                        && baseRegion.getLeft() - otherRegion.getLeft() > 5 * page.getAvgCharWidth()
                        && !(PARAGRAPH_END_RE.matcher(betweenTextBlock.getText().trim()).matches()
                        && textBlockListTemp.get(0).hasMultiChunk(page))) {
                    baseRegion.addTextBlockToList(textBlockListTemp.get(0));
                    baseRegion.merge(otherRegion);
                    otherRegion.markDeleted();
                } else if (textBlockListTemp.get(0).getSize() >= 4 && FloatUtils.feq(baseRegion.getBottom(), betweenTextBlock.getTop(), 1.5 * avgCharHeight)
                        && FloatUtils.feq(otherRegion.getTop(), betweenTextBlock.getBottom(), 1.5 * avgCharHeight)) {
                    baseRegion.addTextBlockToList(textBlockListTemp.get(0));
                    baseRegion.merge(otherRegion);
                    otherRegion.markDeleted();
                } else if (FloatUtils.feq(baseRegion.getBottom(), betweenTextBlock.getTop(), 0.5 * page.getAvgCharHeight())
                        && FloatUtils.feq(otherRegion.getTop(), betweenTextBlock.getBottom(), 0.5 * page.getAvgCharHeight())
                        && textBlockListTemp.get(0).getLeft() - baseRegion.getLeft() > -page.getAvgCharWidth()
                        && isOverlapSingleColumn(baseColumns, betweenTextBlock)) {
                    baseRegion.addTextBlockToList(textBlockListTemp.get(0));
                    baseRegion.merge(otherRegion);
                    otherRegion.markDeleted();
                }
            } else {
                baseRegion = otherRegion;
            }
        }
        candidateRegions.removeIf(Rectangle::isDeleted);

        // 判断unnormalTextBlocks是否属于表格
        if (!unnormalTextBlocks.isEmpty() && !candidateRegions.isEmpty()) {
            float thres = 1.0f;
            for (TextBlock tb : unnormalTextBlocks) {
                if (tb.getRight() < page.getPaper().leftMarginInPt || tb.getLeft() > (page.getWidth() - page.getPaper().rightMarginInPt)
                        || tb.getBottom() < page.getPaper().topMarginInPt || tb.getTop() > (page.getHeight() - page.getPaper().bottomMarginInPt)) {
                    continue;
                }
                for (TableRegion tr : candidateRegions) {
                    if (tb.getTop() - tr.getTop() > -thres && tr.getBottom() - tb.getBottom() > -thres
                            && (FloatUtils.feq(tb.getRight(), tr.getLeft(), 1.5 * page.getAvgCharWidth())
                            || FloatUtils.feq(tb.getLeft(), tr.getRight(), 1.5 * page.getAvgCharWidth()))) {
                        tr.addTextBlockToList(tb);
                    }
                }
            }
        }
    }

    private void correctTableRegion(ContentGroupPage page, List<TableRegion> noLineTables, LinkedHashMap<TextBlock, SerialNumberInfo> snMap) {
        if (null == noLineTables || noLineTables.isEmpty()) {
            return;
        }

        //移除TextBlockList中相同的TextBlock
        removeSameTextBlock(noLineTables);

        //如果表格第一行为序号行，则移除该行
        Pattern TITLE_KEYWORD_RE = Pattern.compile("^(chart|table|图表)\\s*\\d{0,2}.*");
        for (TableRegion tr : noLineTables) {
            if (tr.getTextBlockList().size() < 2) {
                continue;
            }
            //first text line
            TextBlock firstTextBlock = tr.getTextBlockList().get(0);
            if (!firstTextBlock.getElements().isEmpty() && TITLE_KEYWORD_RE.matcher(firstTextBlock.getElements().get(0).getText().toLowerCase().trim()).matches()) {
                tr.removeTextBlockFromList(firstTextBlock);
                continue;
            }
            if (firstTextBlock.getSize() <=3 && APPLICABLE_RE.matcher(firstTextBlock.getText().trim()).matches()) {
                tr.removeTextBlockFromList(firstTextBlock);
                continue;
            }
            if (firstTextBlock.getSize() >= 2 && TableRegionDetectionUtils.isLargeGap(firstTextBlock, 4 * firstTextBlock.getAvgCharWidth(), 1)) {
                continue;
            }
            boolean hasSnInfo = snMap.containsKey(firstTextBlock) && snMap.get(firstTextBlock).getTextLineType() == SerialNumberInfo.TextLineType.TABLE_LINE;
            if (!hasSnInfo && (SERIAL_NUMBER_ROW_RE_0.matcher(firstTextBlock.getText().trim()).matches()
                    || SERIAL_NUMBER_ROW_RE_1.matcher(firstTextBlock.getText().trim()).matches())
                    && FloatUtils.feq(firstTextBlock.getLeft(), tr.getLeft(), 5 * page.getAvgCharWidth())
                    && ((firstTextBlock.getSize() <= 1 && TableRegionDetectionUtils.hasCJKEStr(firstTextBlock.getText()))
                    || (firstTextBlock.getSize() == 2 && SERIAL_NUMBER_RE.matcher(firstTextBlock.getFirstTextChunk().getText()).matches()))) {
                tr.removeTextBlockFromList(firstTextBlock);
            }
        }

        //如果表格结尾含有特殊字符，则删除该行
        Pattern special_text_re_1 = Pattern.compile(".*(:|：)$");
        Pattern special_text_re_2 = Pattern.compile("^(来源|來源|资料来源|資料來源)[：:].*]");
        for (TableRegion tr : noLineTables) {
            if (tr.getTextBlockList().size() < 2) {
                continue;
            }
            //last text line
            TextBlock lastTextBlock = tr.getTextBlockList().get(tr.getTextBlockList().size() - 1);
            if (lastTextBlock.getSize() <= 2 && !TableRegionDetectionUtils.isLargeGap(lastTextBlock, 2.5f * lastTextBlock.getAvgCharWidth(), 1)
                    && special_text_re_2.matcher(lastTextBlock.getLastTextChunk().getText().trim()).matches()) {
                tr.removeTextBlockFromList(lastTextBlock);
            }
            if (lastTextBlock.getSize() >= 2 && TableRegionDetectionUtils.isLargeGap(lastTextBlock, 4 * lastTextBlock.getAvgCharWidth(), 1)) {
                continue;
            }
            if (lastTextBlock.getSize() == 1 && special_text_re_1.matcher(lastTextBlock.getLastTextChunk().getText().trim()).matches()) {
                tr.removeTextBlockFromList(lastTextBlock);
            }

            boolean hasSnInfo = snMap.containsKey(lastTextBlock) && !(snMap.get(lastTextBlock).getTextLineType() == SerialNumberInfo.TextLineType.TABLE_LINE);
            Rectangle boundExcudeLastTextBlock = Rectangle.union(tr.getTextBlockList().subList(0, tr.getTextBlockList().size() - 1).stream()
                    .map(TextBlock::getBounds2D).collect(Collectors.toList()));
            if (hasSnInfo && (SERIAL_NUMBER_ROW_RE_0.matcher(lastTextBlock.getText().trim()).matches()
                    || SERIAL_NUMBER_ROW_RE_1.matcher(lastTextBlock.getText().trim()).matches())
                    && ((lastTextBlock.getSize() == 2 && boundExcudeLastTextBlock.getLeft() - lastTextBlock.getLeft() > 2 * lastTextBlock.getAvgCharWidth())
                    || (lastTextBlock.getSize() == 1 && boundExcudeLastTextBlock.getLeft() - lastTextBlock.getLeft() > -1 * lastTextBlock.getAvgCharWidth()))) {
                tr.removeTextBlockFromList(lastTextBlock);
            }
        }
    }

    private void filterFalseLineTableRegion(ContentGroupPage page, boolean isPageColumnar) {
        if (lineTableRegions.isEmpty()) {
            return;
        }

        //去除由于直线提取错误形成的单行无效表格
        for (TableRegion tr : lineTableRegions) {
            if (tr.getTextLineSize() == 1 && (TableRegionDetectionUtils.matchAnyText(tr.getTextBlockList(), CHART_BEGINNING_KEYWORD)
                || TableRegionDetectionUtils.matchAnyText(tr.getTextBlockList(), TABLE_BEGINNING_KEYWORD))) {
                tr.markDeleted();
            }
        }
        lineTableRegions.removeIf(TableRegion::isDeleted);

        //与chart区域相交
        if (!chartRegions.isEmpty()) {
            for (TableRegion tr : lineTableRegions) {
                for (TableRegion chart : chartRegions) {
                    if (chart.nearlyContains(tr, page.getAvgCharWidth()) || tr.overlapRatio(chart) > 0.5) {
                        if (tr.getTableType() == TableType.LineTable && tr.getConfidence() >= 0.9) {
                            List<TextChunk> textChunksWithinRC = page.getTextChunks(tr);
                            List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(ruling -> ruling.length() > 5
                                    && tr.isShapeIntersects(ruling.toRectangle())).collect(Collectors.toList());
                            List<Ruling> vRulings = page.getVerticalRulings().stream().filter(ruling -> ruling.length() > 5
                                    && tr.isShapeIntersects(ruling.toRectangle())).collect(Collectors.toList());
                            hRulings.sort(Comparator.comparing(Ruling::getTop));
                            vRulings.sort(Comparator.comparing(Ruling::getLeft));
                            CellFillAlgorithm.correctTableBroadLines(page.getMinCharWidth(), page.getAvgCharHeight(), hRulings, vRulings);
                            List<Cell> cellsByLine = Ruling.findCells(hRulings, vRulings);
                            int noChunkNum = 0;
                            for (Cell cell : cellsByLine) {
                                boolean chunkFind = false;
                                for (TextChunk chunk : textChunksWithinRC) {
                                    if (cell.intersects(chunk)) {
                                        chunkFind = true;
                                        break;
                                    }
                                }
                                if (!chunkFind) {
                                    noChunkNum++;
                                }
                            }

                            boolean hasUnnormalChunk = false;
                            for (TextChunk tc : textChunksWithinRC) {
                                if (tc.getDirection() == TextDirection.VERTICAL_UP || tc.getDirection() == TextDirection.VERTICAL_DOWN
                                        || tc.getDirection() == TextDirection.ROTATED) {
                                    hasUnnormalChunk = true;
                                    break;
                                }
                            }

                            List<CandidateCell> cellCandidateByLine = cellsByLine.stream()
                                    .filter(cell -> (cell.getWidth() > 5.0 && cell.getHeight() > 5.0))
                                    .map(cell -> new CandidateCell(cell.x, cell.y, cell.width, cell.height))
                                    .collect(Collectors.toList());
                            HashMap<String, Integer> rcMap = RulingTableRegionsDetectionAlgorithm.calRowColNum(cellCandidateByLine);

                            if (!rcMap.isEmpty() && textChunksWithinRC.size() >= 9 && cellCandidateByLine.size() >= 9
                                    && noChunkNum < 0.1 * textChunksWithinRC.size() && !hasUnnormalChunk
                                    && (rcMap.get("rowNum") >= 3 && rcMap.get("colNum") >= 3
                                    && hRulings.size() >= 3 && vRulings.size() >= 3)) {
                                continue;
                            }
                        }
                        tr.markDeleted();
                    }
                }
            }
        }
        lineTableRegions.removeIf(TableRegion::isDeleted);

        //与chart区域相交
        for (TableRegion tr : lineTableRegions) {
            List<TableRegion> charts = chartRegions.stream().filter(chart -> chart.overlapRatio(tr) > 0.2)
                    .collect(Collectors.toList());
            if (charts.size() >= 2 && Rectangle.boundingBoxOf(charts).overlapRatio(tr, true) > 0.7) {
                tr.markDeleted();
            }

            if (charts.size() == 1) {
                TableRegion chart = charts.get(0);
                if (chart.getChartType() == ChartType.PIE_CHART && tr.nearlyContains(chart, page.getAvgCharWidth())) {
                    List<TextChunk> textChunks = TableRegionDetectionUtils.getExcludeTextChunks(page
                            , tr.rectReduce(0.5 * page.getAvgCharWidth(), 0.5 * page.getAvgCharHeight()
                                    , page.getWidth(), page.getHeight()), charts);
                    if (textChunks.size() < 6) {
                        tr.markDeleted();
                    }
                }
            }
        }
        lineTableRegions.removeIf(TableRegion::isDeleted);

        //研报第一页整页当作一个表格（部分因为隐藏线提取问题，部分为整个页面本来就是可见的网格结构）
        if (page.getPageNumber() == 1 && lineTableRegions.size() == 1) {
            Rectangle pageBound = page.getTextBounds();
            TableRegion tableRegion = lineTableRegions.get(0);
            List<Ruling> innerHorRulings = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableRegion);
            List<Ruling> innerVerRulings = Ruling.getRulingsFromArea(page.getVerticalRulings(), tableRegion);
            if (tableRegion.getWidth() > 0.9 * pageBound.getWidth() && tableRegion.getHeight() > 0.85 * pageBound.getHeight()
                    && TableRegionDetectionUtils.getLongRulings(innerHorRulings, (float)(0.8 * tableRegion.getWidth())).size() < 5
                    && TableRegionDetectionUtils.getLongRulings(innerVerRulings, (float)(0.8 * tableRegion.getHeight())).size() < 5) {
                List<SegmentPageInfo> segmentPageInfos = segmentPageByTj(page, tableRegion, this.lineTableRegions, false, true);
                Rectangle largestSeg = null;
                for (SegmentPageInfo seg : segmentPageInfos) {
                    if (largestSeg == null) {
                        largestSeg = seg;
                        continue;
                    }
                    if (seg.getHeight() > largestSeg.getHeight()) {
                        largestSeg = seg;
                    }
                }

                if (largestSeg != null) {
                    Rectangle otherSide;
                    if (Math.abs(largestSeg.getLeft() - tableRegion.getLeft()) > Math.abs(largestSeg.getRight() - tableRegion.getRight())) {
                        otherSide = new Rectangle(tableRegion.getLeft(), largestSeg.getTop()
                                , largestSeg.getLeft() - tableRegion.getLeft(), largestSeg.getHeight());
                    } else {
                        otherSide = new Rectangle(largestSeg.getRight() + 1.0, largestSeg.getTop()
                                , tableRegion.getRight() - largestSeg.getRight(), largestSeg.getHeight());
                    }
                    if (otherSide.getWidth() > 8 * page.getAvgCharWidth()) {
                        boolean hasChartInRegion = this.chartRegions.stream().filter(chart
                                -> otherSide.nearlyContains(chart, page.getAvgCharWidth())).collect(Collectors.toList()).size() != 0;
                        if (hasChartInRegion) {
                            tableRegion.markDeleted();
                        } else {
                            List<TextBlock> textBlocks = TableRegionDetectionUtils.getTextBlockWithinShape(page, otherSide, null);
                            textBlocks.sort(Comparator.comparing(TextBlock::getTop));
                            boolean lastLineFind = false;
                            int continuousLineNum = 0;
                            for (TextBlock textBlock : textBlocks) {
                                if (TableRegionDetectionUtils.isLargeGap(textBlock, 2 * textBlock.getAvgCharWidth(), 1)) {
                                    lastLineFind = true;
                                    continuousLineNum++;
                                } else {
                                    lastLineFind = false;
                                    continuousLineNum = 0;
                                }
                                if (continuousLineNum >= 3) {
                                    tableRegion.markDeleted();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        lineTableRegions.removeIf(TableRegion::isDeleted);

        if (lineTableRegions.isEmpty() || isPageColumnar) {
            return;
        }
        //表格水平方向存在多个并列表格且表格区域以外有多列文本存在
        lineTableRegions.sort(Comparator.comparing(TableRegion::getTop));
        List<TableRegion> tableTempList = new ArrayList<>();
        for (TableRegion tr1 : lineTableRegions) {
            for (TableRegion tr2 : lineTableRegions) {
                if (Objects.equals(tr1, tr2)) {
                    continue;
                }
                if (tr1.verticalOverlapRatio(tr2) > 0.6) {
                    if (!tableTempList.contains(tr1)) {
                        tableTempList.add(tr1);
                    }
                    if (!tableTempList.contains(tr2)) {
                        tableTempList.add(tr2);
                    }
                }
            }
            if (!tableTempList.isEmpty()) {
                break;
            }
        }

        if (tableTempList.isEmpty()) {
            return;
        }

        Rectangle bounds = Rectangle.boundingBoxOf(tableTempList);
        List<TextChunk> chunkList = page.getMutableTextChunks(new Rectangle(0, bounds.getTop(), page.getWidth(), bounds.getHeight()));
        for (TextChunk tc : chunkList) {
            for (TableRegion tr : lineTableRegions) {
                if (tr.isShapeIntersects(tc)) {
                    tc.markDeleted();
                    break;
                }
            }
        }
        chunkList.removeIf(TextChunk::isDeleted);

        List<TextBlock> exceptTextBlocks = TextMerger.collectByRows(TextMerger.groupByBlock(chunkList, page.getHorizontalRulings(), page.getVerticalRulings()));
        for (TextBlock tb : exceptTextBlocks) {
            if (tb.getSize() > 2 && TableRegionDetectionUtils.hasNumberStr(tb.getElements())) {
                lineTableRegions.removeAll(tableTempList);
                return;
            }
        }
    }

    private void filterInvalidTables(ContentGroupPage page, List<TableRegion> noLineTables, List<TextBlock> candidateTextBlocks, float pageValidWidth) {
        if (null == noLineTables || noLineTables.isEmpty()) {
            return;
        }

        //去除含有特殊字符的页面的table，如港股目录页
        if (page.getTextLines() != null && !page.getTextLines().isEmpty()) {
            boolean hasDirectory = false;
            for (TextBlock tb : page.getTextLines()) {
                if (SPECIAL_STR_CONTENT_RE.matcher(tb.getText().trim().toLowerCase()).matches()) {
                    hasDirectory = true;
                    break;
                }
            }

            int multiColNum = 0;
            for (TextBlock tb : page.getTextLines()) {
                if (tb.getSize() > 2) {
                    multiColNum++;
                }
            }

            for (TableRegion tr : noLineTables) {
                int apostropheNum = 0;
                for (TextBlock tb : tr.getTextBlockList()) {
                    if (APOSTROPHE_RE.matcher(tb.getText()).find()) {
                        apostropheNum++;
                    }
                }

                if (hasDirectory) {
                    if (apostropheNum >= 0.3 * tr.getTextBlockList().size()) {
                        tr.markDeleted();
                    }
                } else {
                    if (apostropheNum >= 0.5 * tr.getTextBlockList().size() && multiColNum <= 0.2 * tr.getTextBlockList().size()) {
                        tr.markDeleted();
                    }
                }
            }
            noLineTables.removeIf(Rectangle::isDeleted);
        }

        //去除表格：根据关键词、字体大小、背景色等
        noLineTables.sort(Comparator.comparing(Rectangle::getHeight));
        Collections.reverse(noLineTables);
        for (TableRegion regionBase : noLineTables) {
            if (regionBase.isDeleted()) {
                continue;
            }

            //过滤行数较少且含有特殊关键词的表格
            List<TextBlock> textBlocks = regionBase.getTextBlockList();
            if (textBlocks.size() <= 2 && (TableRegionDetectionUtils.matchAnyText(textBlocks, CHART_STR_RE_1) ||
                    TableRegionDetectionUtils.matchAnyText(textBlocks, TABLE_FILTER_STR_RE))) {
                regionBase.markDeleted();
                continue;
            }

            //字体差别太大
            if (textBlocks.size() <= 2 && TableRegionDetectionUtils.calFontDiffRatio(page.getTextChunks(regionBase)) > 1.3) {
                regionBase.markDeleted();
            }

            List<TextBlock> textChoose = TableRegion.getTopOuterTextBlock(page, regionBase, candidateTextBlocks, 1);
            textChoose.sort(Comparator.comparing(TextBlock::getTop));
            if ((!textChoose.isEmpty() && CHART_BEGINNING_KEYWORD.matcher(textChoose.get(textChoose.size() - 1).getText().trim().toLowerCase()).matches())
                    || (textBlocks.size() > 0 && CHART_BEGINNING_KEYWORD.matcher(textBlocks.get(0).getText().trim().toLowerCase()).matches())) {
                int colNum = TableRegionDetectionUtils.calTableColNum(page, regionBase, regionBase.getTextBlockList());
                List<TextChunk> innerChunks = page.getTextChunks(regionBase.rectReduce(2.0, 2.0
                        , page.getWidth(), page.getHeight())).stream().filter(chunk -> StringUtils.isNotBlank(chunk.getText().trim()))
                        .collect(Collectors.toList());
                if (regionBase.getTextLineSize() * colNum > 15 && innerChunks.size() < 0.35 * regionBase.getTextLineSize() * colNum) {
                    regionBase.markDeleted();
                    continue;
                }
            }

            if (textChoose.isEmpty()) {
                continue;
            }
            TextBlock upBlock = textChoose.get(0);
            if (upBlock.getElements().isEmpty()) {
                continue;
            }
            if (FloatUtils.feq(regionBase.getTop(), upBlock.getBottom(),4 * page.getAvgCharHeight())
                    && TableRegionDetectionUtils.matchAnyText(Arrays.asList(upBlock), SPECIAL_STR_CONTENT_RE)) {
                regionBase.markDeleted();
                continue;
            }
        }
        noLineTables.removeIf(Rectangle::isDeleted);

        //去除图表：文本块有角度或含有图x：的字样可能为图表非表格
        for (Rectangle tableArea : noLineTables) {
            if (tableArea.isDeleted()) {
                continue;
            }
            List<TextChunk> textChunks = page.getTextChunks(tableArea);
            List<TextChunk> normalChunks = new ArrayList<>();
            List<TextChunk> specialChunks = new ArrayList<>();
            splitChunk(textChunks, normalChunks, specialChunks);
            if (textChunks.isEmpty() || normalChunks.isEmpty() || specialChunks.isEmpty() || normalChunks.size() < specialChunks.size()) {
                continue;
            }
            Rectangle normalRect = Rectangle.boundingBoxOf(normalChunks);
            Rectangle specialRect = Rectangle.boundingBoxOf(specialChunks);
            if ((FloatUtils.feq(normalRect.getLeft(), specialRect.getRight(),2 * page.getAvgCharWidth())
                    && specialRect.getRight() < page.getPaper().leftMarginInPt)
                    || (FloatUtils.feq(normalRect.getRight(), specialRect.getLeft(),2 * page.getAvgCharWidth())
                    && specialRect.getLeft() > (page.getWidth() - page.getPaper().rightMarginInPt))) {
                tableArea.markDeleted();
            }
        }
        noLineTables.removeIf(Rectangle::isDeleted);

        //去除图表：根据填充色
        noLineTables.sort(Comparator.comparing(Rectangle::getTop));
        for (TableRegion table : noLineTables) {
            if (table.isDeleted()) {
                continue;
            }
            List<TextBlock> textBlocks = table.getTextBlockList();
            List<TextChunk> textChunks = page.getTextChunks(table);
            page.getHorizontalRulings().sort(Comparator.comparing(Ruling::getY1));
            page.getVerticalRulings().sort(Comparator.comparing(Ruling::getX1));

            //表格内填充色区域太小则暂时不过滤
            if (!chartRegions.isEmpty()) {
                List<TableRegion> chartsInTable = new ArrayList<>();
                for (TableRegion tr : chartRegions) {
                    if (tr.isShapeIntersects(table)) {
                        chartsInTable.add(tr);
                    }
                }
                if (!chartsInTable.isEmpty()) {
                    Rectangle chartBound = Rectangle.boundingBoxOf(chartsInTable);
                    if (chartBound.getArea() > 0.6 * table.getArea() && chartBound.getWidth() > 0.6 * table.getWidth()) {
                        table.markDeleted();
                        continue;
                    }
                }
            }

            if (textBlocks.size() == 1) {
                for (TextChunk chunk : textBlocks.get(0).getElements()) {
                    if (!(chunk.getCenterY() >= table.getTop() && chunk.getBottom() <= table.getBottom())) {
                        table.markDeleted();
                        break;
                    }
                }
                if (!table.isDeleted()) {
                    if (APPLICABLE_RE.matcher(textBlocks.get(0).getText()).matches()) {
                        table.markDeleted();
                    }
                    if (!table.isDeleted() && textBlocks.get(0).getElements().size() == 2
                            && SERIAL_NUMBER_ROW_RE_2.matcher(textBlocks.get(0).getText()).matches()) {
                        table.markDeleted();
                    }
                }
            }
        }
        noLineTables.removeIf(Rectangle::isDeleted);

        //去除图表：根据填充块
        for (TableRegion table : noLineTables) {
            if (table.isDeleted()) {
                continue;
            }
            List<FillAreaGroup> rectFillAreaGroups = page.getPageFillAreaGroup().stream().filter(fillAreaGroup
                    -> fillAreaGroup.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA
                    && table.intersects(fillAreaGroup.getGroupRectArea())).collect(Collectors.toList());
            if (rectFillAreaGroups.size() < 8) {
                continue;
            }

            //检查柱子上面是否有文字
            boolean textFind = false;
            for (FillAreaGroup fillAreaGroup : rectFillAreaGroups) {
                if (page.getTextChunks(fillAreaGroup.getGroupRectArea().rectReduce(0.5, 0.5
                        , page.getWidth(), page.getHeight())).size() > 0) {
                    textFind = true;
                    break;
                }
            }

            boolean bigClusterFind = false;
            List<List<FillAreaGroup>> clusterGroups = clusterRectFillAreaGroups(page, rectFillAreaGroups);
            for (List<FillAreaGroup> cluster : clusterGroups) {
                if (cluster.size() >= 5) {
                    bigClusterFind = true;
                    break;
                }
            }

            Rectangle searchRect = new Rectangle(table.getLeft(), table.getTop() - 2 * page.getAvgCharHeight(), table.getWidth()
                    , 5 * page.getAvgCharHeight());
            List<TextBlock> searchTextBlocks = candidateTextBlocks.stream().filter(textBlock -> searchRect.intersects(textBlock.toRectangle()))
                    .collect(Collectors.toList());
            boolean specialTextMatch = false;
            for (TextBlock textBlock : searchTextBlocks) {
                if (TableRegionDetectionUtils.calStrMatchNum(CHART_BEGINNING_KEYWORD, textBlock.getElements()) >= 2) {
                    specialTextMatch = true;
                    break;
                }
            }

            if (!textFind && bigClusterFind && specialTextMatch) {
                table.markDeleted();
            }
        }
        noLineTables.removeIf(Rectangle::isDeleted);

        //去除疑似正文的表格
        for (TableRegion table : noLineTables) {
            List<TextBlock> textBlocks = table.getTextBlockList();
            if (textBlocks.size() == 1 && textBlocks.get(0).getSize() < 5 && TableRegionDetectionUtils.matchAnyText(textBlocks
                    .get(0).getElements(), SPECIAL_STR_END_RE)) {
                table.markDeleted();
                continue;
            }

            List<TextChunk> textChunksInRC = page.getMergeChunks(table);
            List<Rectangle> rectInRC = textChunksInRC.stream().map(TextChunk::toRectangle).collect(Collectors.toList());
            HashMap<Point2D, java.lang.Float> gapMap = TableRegionDetectionUtils.calVerticalProjectionGap(rectInRC);
            int verticalProjectNum = TableRegionDetectionUtils.countLargeGap(gapMap,  1.5f * page.getAvgCharWidth());
            if (verticalProjectNum != 1) {
                continue;
            }
            double gap = 0;
            double midProject = 0;
            for (HashMap.Entry<Point2D, Float> map : gapMap.entrySet()) {
                if (map.getValue() > gap) {
                    gap = map.getValue();
                    midProject = (map.getKey().getY() + map.getKey().getX()) / 2;
                }
            }
            int horizontalProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils
                    .calHorizontalProjectionGap(rectInRC),  page.getAvgCharHeight());
            List<SegmentPageInfo> segInRC = this.segmentResultByTj.stream().filter(s -> s.isShapeIntersects(table)).collect(Collectors.toList());
            segInRC.sort(Comparator.comparing(Rectangle::getLeft));
            if (table.getHeight() > 5 * page.getAvgCharHeight() && verticalProjectNum <= 2 && horizontalProjectNum == 0
                    && !TableRegionDetectionUtils.hasNumberStr(textChunksInRC) && table.getWidth() > 0.9 * pageValidWidth
                    && segInRC.size() == 2 && segInRC.get(0).getRight() < midProject && segInRC.get(1).getLeft() > midProject
                    && segInRC.get(0).getWidth() > 0.35 * pageValidWidth && segInRC.get(1).getWidth() > 0.35 * pageValidWidth
                    && isTextDense(page, new TableRegion(segInRC.get(0)), 0.7f, 0.7f)
                    && isTextDense(page, new TableRegion(segInRC.get(1)), 0.7f, 0.7f)) {
                table.markDeleted();
            }
        }
        noLineTables.removeIf(Rectangle::isDeleted);
    }

    // 判断文本块中是否存在图表的头
    public static boolean isChartHeader(Page page, Rectangle tableArea, List<TextChunk> textChunks) {
        List<Ruling> hencLines = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableArea);
        if (!hencLines.isEmpty() && !textChunks.isEmpty()) {
            for (Ruling hline : hencLines) {
                //判断线的上下有没有字符
                if (!page.getText(new Rectangle(hline.getLeft(), hline.getTop() - page.getAvgCharHeight()
                        , hline.getWidth(), 2 * page.getAvgCharHeight())).isEmpty()) {
                    continue;
                }
                for (TextChunk chunk : textChunks) {
                    if (FloatUtils.feq(chunk.getLeft(), hline.getRight(), chunk.getAvgCharWidth() * 0.5) &&
                            (hline.getTop() > chunk.getTop()) && (hline.getTop() < chunk.getBottom()) &&
                            (hline.getWidth() > 1.5 * chunk.getAvgCharWidth())) {
                        float left = (((float)hline.getX1() - 2 * chunk.getAvgCharWidth()) < page.getLeft()) ?
                                page.getLeft() : ((float)hline.getX1() - 2 * chunk.getAvgCharWidth());
                        Rectangle extendRegion = new Rectangle(left, chunk.getTop(),
                                (float)hline.getX1() - left, chunk.getBottom() - chunk.getTop());
                        if (page.getText(extendRegion).isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // direction: true 水平线  false 垂直线
    private static boolean hasTextNearByLine(List<TextChunk> textChunks, Ruling line, boolean direction, boolean haveSecondLine, double gap) {
        boolean result = false;
        if (line != null && !textChunks.isEmpty()) {
            List<TextChunk> candidateChunks = new ArrayList<>();
            if (direction == true) {
                for (TextChunk text : textChunks) {
                    if ((text.getTop() > line.getTop()) &&
                            FloatUtils.feq(text.getTop(), line.getTop(), 1.5 * gap) &&
                            (text.getRight() >= line.getX1() && text.getLeft() < line.getX2())) {
                        if (candidateChunks.isEmpty()) {
                            candidateChunks.add(text);
                        } else {
                            if (text.getRight() - candidateChunks.get(candidateChunks.size() - 1).getLeft() >
                                    0.5 * text.getAvgCharWidth()) {
                                candidateChunks.add(text);
                            }
                        }
                    }
                }
            } else {
                textChunks.sort(Comparator.comparing(TextChunk::getTop));
                for (TextChunk text : textChunks) {
                    boolean bEdgeDirection = haveSecondLine ? (text.getLeft() > line.getX1()) : (text.getRight() < line.getX1());
                    float baseVal = haveSecondLine ? text.getLeft() : text.getRight();

                    if (bEdgeDirection && FloatUtils.feq(baseVal, line.getX1(), 3 * gap) &&
                            (text.getBottom() > line.getY1() && text.getTop() < line.getY2())) {
                        if (candidateChunks.isEmpty()) {
                            candidateChunks.add(text);
                        } else {
                            if (text.getTop() - candidateChunks.get(candidateChunks.size() - 1).getBottom() >
                                    0.5 * text.getHeight()) {
                                candidateChunks.add(text);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            if (candidateChunks.size() >= 2) {
                result = true;
            }
        }
        return result;
    }

    // 根据线判断是否存在chart
    public static boolean isChartRegion(Page page, Rectangle tableArea, List<TextChunk> textChunks) {
        boolean result = false;
        List<Ruling> hencLines = Ruling.getRulingsFromArea(page.getHorizontalRulings(), tableArea);
        List<Ruling> vencLines = Ruling.getRulingsFromArea(page.getVerticalRulings(), tableArea);
        if (!hencLines.isEmpty() && !vencLines.isEmpty()) {
            double avgWidth = page.getText(tableArea).parallelStream().mapToDouble(TextElement::getTextWidth).average()
                    .orElse(Page.DEFAULT_MIN_CHAR_WIDTH);
            double avgHeight = page.getText(tableArea).parallelStream().mapToDouble(TextElement::getTextHeight).average()
                    .orElse(Page.DEFAULT_MIN_CHAR_HEIGHT);
            List<Ruling> candidateVlines = new ArrayList<>();
            for (Ruling hline : hencLines) {
                if (hline.getWidth() < avgWidth) {
                    continue;
                }
                for (Ruling vline : vencLines) {
                    if ((hline.getX1() - vline.getX1() < -1.0) && (hline.getX2() - vline.getX1() > 1.0) &&
                            (vline.getY1() < hline.getY1()) && (vline.getY2() >= hline.getY1())) {
                        if (candidateVlines.isEmpty()) {
                            if (hasTextNearByLine(textChunks, vline, false, false, avgWidth)) {
                                candidateVlines.add(vline);
                            }
                        } else {
                            if (((vline.getX1() - candidateVlines.get(candidateVlines.size() - 1).getX1()) / hline.getWidth() > 0.8) &&
                                    hasTextNearByLine(textChunks, vline, false, true, avgWidth)) {
                                candidateVlines.add(vline);
                            }
                        }
                    }
                }
                if (!candidateVlines.isEmpty() && candidateVlines.size() <= 2) {
                    if (hasTextNearByLine(textChunks, hline, true, false, avgHeight)) {
                        if (candidateVlines.size() == 2) {
                            if (FloatUtils.feq(hline.getX1(), candidateVlines.get(0).getX1(), 5) &&
                                    FloatUtils.feq(hline.getX2(), candidateVlines.get(1).getX1(), 5)) {
                                result = true;
                                break;
                            }
                        } else {
                            if (FloatUtils.feq(hline.getX1(), candidateVlines.get(0).getX1(), 5)) {
                                result = true;
                                break;
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     *至少有两条相邻的外接框线
     */
    private static boolean hasCoordinateAxis(ContentGroupPage page, TableRegion rc) {
        double delta = FastMath.max(0.5 * page.getAvgCharHeight(), 3.0);
        Rectangle rectTmp = rc.withCenterExpand(2.0f,2.0f);
        List<Ruling> hRulings = page.getHorizontalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());
        List<Ruling> vRulings = page.getVerticalRulings().stream().filter(
                rul -> rectTmp.isShapeIntersects(rul.toRectangle())).collect(Collectors.toList());

        float lineRatio = 0.8f;

        float topLineLength = 0.0f;
        float bottomLineLength = 0.0f;
        float leftLineLength = 0.0f;
        float rightLineLength = 0.0f;

        for (Ruling rul : hRulings) {
            if (FloatUtils.feq(rc.getTop(), rul.getTop(), 5.0)) {
                topLineLength += rul.getWidth();
            }
            if (FloatUtils.feq(rc.getBottom(), rul.getBottom(), 5.0)) {
                bottomLineLength += rul.getWidth();
            }
        }

        for (Ruling rul : vRulings) {
            if (FloatUtils.feq(rc.getLeft(), rul.getLeft(), 5.0)) {
                leftLineLength += rul.getHeight();
            }
            if (FloatUtils.feq(rc.getRight(), rul.getRight(), 5.0)) {
                rightLineLength += rul.getHeight();
            }
        }

        boolean topFind = topLineLength / rc.getWidth() > lineRatio;
        boolean bottomFind = bottomLineLength / rc.getWidth() > lineRatio;
        boolean leftFind = leftLineLength / rc.getHeight() > lineRatio;
        boolean rightFind = rightLineLength / rc.getHeight() > lineRatio;
        return ((topFind && (leftFind || rightFind)) || (bottomFind && (leftFind || rightFind)));
    }

    /**
     * 将柱状FillAreaGroup进行聚类：具体为以最大填充色为种子点进行聚类
     */
    private static List<List<FillAreaGroup>> clusterRectFillAreaGroups(ContentGroupPage page, List<FillAreaGroup> groups) {
        List<List<FillAreaGroup>> clusterGroups = new ArrayList<>();
        if (groups.size() < 2) {
            clusterGroups.add(groups);
        } else {
            float maxLength = -1;
            FillAreaGroup seedArea = null;
            for (FillAreaGroup fillAreaGroup : groups) {
                if (seedArea == null) {
                    maxLength = fillAreaGroup.getLength();
                    seedArea = fillAreaGroup;
                    continue;
                }
                if (fillAreaGroup.getLength() > maxLength) {
                    maxLength = fillAreaGroup.getLength();
                    seedArea = fillAreaGroup;
                }
            }

            float minAreaGap = FastMath.min(2f * FastMath.min(seedArea.getWidth(), seedArea.getHeight()), 5 * page.getAvgCharWidth());
            boolean isVertical = seedArea.getHeight() > seedArea.getWidth();
            List<FillAreaGroup> groupsTemp = new ArrayList<>();
            groupsTemp.add(seedArea);
            seedArea.setMark(true);
            boolean findFlag = true;
            while (findFlag) {
                findFlag = false;
                for (FillAreaGroup one : groups) {
                    Rectangle rectOne = one.getGroupRectArea();
                    if (one.isMarked()) {
                        continue;
                    }
                    boolean isNeighbor = false;
                    for (FillAreaGroup other : groupsTemp) {
                        Rectangle rectOther = other.getGroupRectArea();
                        if (rectOne.isShapeAdjacent(rectOther, 1.0)) {
                            if (rectOne.verticalOverlapRatio(rectOther) > 0.9 && FloatUtils.feq(rectOne.getHeight(), rectOther.getHeight(), 1.0)) {
                                isNeighbor = true;
                                break;
                            }
                            if (rectOne.horizontallyOverlapRatio(rectOther) > 0.9 && FloatUtils.feq(rectOne.getWidth(), rectOther.getWidth(), 1.0)) {
                                isNeighbor = true;
                                break;
                            }
                        }
                        if (isVertical) {
                            if (rectOne.verticalOverlapRatio(rectOther) > 0.9
                                    && (FloatUtils.feq(one.getLeft(), other.getRight(), minAreaGap)
                                    || FloatUtils.feq(one.getRight(), other.getLeft(), minAreaGap))) {
                                isNeighbor = true;
                                break;
                            }
                        } else {
                            if (one.getGroupRectArea().horizontallyOverlapRatio(other.getGroupRectArea()) > 0.9
                                    && (FloatUtils.feq(one.getTop(), other.getBottom(), minAreaGap)
                                    || FloatUtils.feq(one.getBottom(), other.getTop(), minAreaGap))) {
                                isNeighbor = true;
                                break;
                            }
                        }
                    }
                    if (isNeighbor) {
                        one.setMark(true);
                        groupsTemp.add(one);
                        findFlag = true;
                    }
                }
            }

            clusterGroups.add(groupsTemp);
            clusterGroups.addAll(clusterRectFillAreaGroups(page, groups.stream().filter(g -> !g.isMarked()).collect(Collectors.toList())));
        }
        return clusterGroups;
    }

    private void mergeNeighborLegend(ContentGroupPage page, TableRegion currentChartRegion, List<FillAreaGroup> legendGroups, float thresh) {
        for (FillAreaGroup group : legendGroups) {
            //对应区域如果已经被检测为chart则跳过
            boolean detectFind = false;
            for (TableRegion tr : chartRegions) {
                if (tr.isShapeIntersects(group.getGroupRectArea())) {
                    detectFind = true;
                    break;
                }
            }
            if (detectFind) {
                continue;
            }

            if (currentChartRegion.isShapeIntersects(group.getGroupRectArea()) || currentChartRegion.isShapeAdjacent(group.getGroupRectArea(), thresh)) {
                float left = FastMath.min(currentChartRegion.getLeft(), group.getLeft());
                float top = FastMath.min(currentChartRegion.getTop(), group.getTop());
                float width = FastMath.max(currentChartRegion.getRight(), group.getRight()) - left;
                float height = FastMath.max(currentChartRegion.getBottom(), group.getBottom()) - top;
                currentChartRegion.setRect(left, top, width, height);
            }
        }

        /*for (Ruling ruling : page.getRulings()) {
            if (currentChartRegion.isShapeIntersects(ruling.toRectangle())) {
                float left = FastMath.min(currentChartRegion.getLeft(), ruling.getLeft());
                float top = FastMath.min(currentChartRegion.getTop(), ruling.getTop());
                float width = FastMath.max(currentChartRegion.getRight(), ruling.getRight()) - left;
                float height = FastMath.max(currentChartRegion.getBottom(), ruling.getBottom()) - top;
                currentChartRegion.setRect(left, top, width, height);
            }
        }*/
    }

    public void detectExtraChartByFillArea(ContentGroupPage page) {
        List<FillAreaGroup> rectFillAreaGroups = new ArrayList<>();
        List<FillAreaGroup> sectorFillAreaGroups = new ArrayList<>();
        for (FillAreaGroup group : page.getPageFillAreaGroup()) {
            //对应区域如果已经被检测为chart则跳过
            boolean detectFind = false;
            for (TableRegion tr : chartRegions) {
                if (tr.isShapeIntersects(group.getGroupRectArea())) {
                    detectFind = true;
                    break;
                }
            }
            if (detectFind) {
                continue;
            }

            if (group.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA) {
                if (group.getWidth() > 10 * page.getAvgCharWidth() && group.getHeight() > 10 * page.getAvgCharWidth()) {
                    continue;
                }
                List<TextChunk> groupChunks = page.getMutableTextChunks(group.getGroupRectArea().rectReduce(1.0
                        , 1.0 ,page.getWidth(), page.getHeight()));
                int vProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils
                        .calVerticalProjectionGap(groupChunks), page.getAvgCharWidth());
                int hProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils
                        .calHorizontalProjectionGap(groupChunks), page.getAvgCharWidth());
                if (groupChunks.size() > 8 || (hProjectNum >= 3 && vProjectNum >= 3)) {
                    continue;
                }
                rectFillAreaGroups.add(group);
            } else if (group.getGroupAreaType() == FillAreaGroup.AreaType.PIE_AREA) {
                List<TextChunk> groupChunks = page.getMutableTextChunks(group.getGroupRectArea().rectReduce(1.0
                        , 1.0 ,page.getWidth(), page.getHeight()));
                if (groupChunks.size() > 3 * group.getGroupFillAreas().size()) {
                    continue;
                }
                sectorFillAreaGroups.add(group);
            }
        }

        List<FillAreaGroup> legendGroups = page.getPageFillAreaGroup().stream().filter(FillAreaGroup::isLegend).collect(Collectors.toList());

        //带有坐标轴的柱状图
        for (TableRegion tr : rulingRectangles) {
            boolean findFlag = false;
            List<FillAreaGroup> rectGroups = rectFillAreaGroups.stream().filter(group ->
                    group.getGroupRectArea().isShapeIntersects(tr) && !group.isLegend()).collect(Collectors.toList());
            rectGroups.forEach(f -> f.setMark(true));
            List<TextChunk> textChunks = page.getTextChunks(tr);
            if (rectGroups.size() < 3 || rectGroups.size() > 20 || textChunks.size() > 40
                    || !FillAreaGroup.isFillAreaGroupDense(rectGroups)) {
                continue;
            }
            //如果每个柱子长度相同或柱子排列有问题则为疑似表格
            if (FillAreaGroup.isPossibleTableRegion(page, rectGroups)) {
                continue;
            }
            int mostCommonColorNum = FillAreaGroup.getMostCommonColorNum(rectGroups);
            if ((rectGroups.size() >= 3 && mostCommonColorNum >= 3) || (hasCoordinateAxis(page, tr)
                    && rectGroups.size() >= 8 && (float)textChunks.size() / rectGroups.size() < 6)) {
                findFlag = true;
            }
            //文本块稀疏
            if (!findFlag && (rectGroups.size() >= 3 && (float)textChunks.size() / rectGroups.size() < 1)) {
                findFlag = true;
            }
            //每个柱颜色一样或者包含颜色一样
            if (!findFlag && (FillAreaGroup.isAllNearlyEqualColor(rectGroups) && mostCommonColorNum >= 2)) {
                findFlag = true;
            }
            if (findFlag) {
                TableRegion chartRegion = new TableRegion(tr);
                mergeNeighborLegend(page, chartRegion, legendGroups, 2 * FastMath.max(page.getAvgCharWidth(), page.getAvgCharHeight()));
                chartRegion.setConfidence(0.5);
                chartRegion.setChartType(ChartType.BAR_CHART);
                chartRegions.add(chartRegion);
            }
        }

        //不带坐标轴的柱状图
        List<FillAreaGroup> rectGroups = rectFillAreaGroups.stream().filter(group -> !group.isMarked()).collect(Collectors.toList());
        List<List<FillAreaGroup>> clusterGroups = clusterRectFillAreaGroups(page, rectGroups);
        rectGroups.forEach(f -> f.setMark(false));
        for (List<FillAreaGroup> clustered : clusterGroups) {
            boolean findFlag = false;
            if (clustered.isEmpty()) {
                continue;
            }
            TableRegion chartRegion = FillAreaGroup.boundingBoxOf(clustered);
            List<TextChunk> textChunks = page.getTextChunks(chartRegion);
            if (clustered.size() < 4 || clustered.size() > 18 || textChunks.size() > 40
                    || !FillAreaGroup.isFillAreaGroupDense(clustered)) {
                continue;
            }
            //如果每个柱子长度相同或柱子排列有问题则为疑似表格
            if (FillAreaGroup.isPossibleTableRegion(page, clustered)) {
                continue;
            }

            int mostCommonColorNum = FillAreaGroup.getMostCommonColorNum(clustered);
            if (mostCommonColorNum < 2 && textChunks.size() > 1.5 * clustered.size() && textChunks.size() > 20) {
                continue;
            }
            if (mostCommonColorNum >= 2 || clustered.size() >= 8) {
                findFlag = true;
            }
            //文本块稀疏
            if (!findFlag && (textChunks.size() / clustered.size() <= 1 && FillAreaGroup.isAllNearlyEqualColor(clustered))) {
                findFlag = true;
            }
            if (findFlag) {
                mergeNeighborLegend(page, chartRegion, legendGroups, 2 * FastMath.max(page.getAvgCharWidth(), page.getAvgCharHeight()));
                chartRegion.setConfidence(0.5);
                chartRegion.setChartType(ChartType.BAR_CHART);
                chartRegions.add(chartRegion);
            }
        }

        //饼状图
        float sizeRatio = 5;
        for (FillAreaGroup group : sectorFillAreaGroups) {
            TableRegion chartRegion = new TableRegion(group.getGroupRectArea());
            if (group.getGroupFillAreas().size() < 2) {
                continue;
            }
            if (chartRegion.getWidth() > sizeRatio * chartRegion.getHeight() || chartRegion.getHeight() > sizeRatio * chartRegion.getWidth()) {
                continue;
            }
            mergeNeighborLegend(page, chartRegion, legendGroups, 2 * FastMath.max(page.getAvgCharWidth(), page.getAvgCharHeight()));
            chartRegion.setConfidence(0.5);
            chartRegion.setChartType(ChartType.PIE_CHART);
            chartRegions.add(chartRegion);
        }
    }

    public List<TableRegion> findMostPossibleChartRegion(ContentGroupPage page) {
        for (Chart chart : page.getCharts()) {
            if (chart.getType() == ChartType.UNKNOWN_CHART) {
                continue;
            }

            if (chart.getTitle() != null && CHART_BEGINNING_KEYWORD.matcher(chart.getTitle().trim().toLowerCase()).matches()) {
                TableRegion chartRegion = new TableRegion(chart.getArea());
                chartRegion.setConfidence(chart.getConfidence());
                chartRegion.setChartType(chart.getType());
                chartRegions.add(chartRegion);
                continue;
            }

            if (chart.getConfidence() < 0.6 && (chart.getType() == ChartType.BAR_CHART || chart.type == ChartType.COLUMN_CHART)) {
                List<TextChunk> textChunks = page.getTextChunks(chart.getArea());
                if (textChunks.size() > 80) {
                    continue;
                }
                List<FillAreaGroup> rectGroups = page.getPageFillAreaGroup().stream()
                        .filter(group -> group.getGroupAreaType() == FillAreaGroup.AreaType.BAR_AREA && !group.isLegend()
                                && group.getGroupRectArea().isShapeIntersects(chart.getArea()) ).collect(Collectors.toList());
                if (rectGroups.size() < 3 || rectGroups.size() > 30 || !FillAreaGroup.isFillAreaGroupDense(rectGroups)
                        || FillAreaGroup.isPossibleTableRegion(page, rectGroups)) {
                    continue;
                }

                boolean wrongGroupFind = false;
                for (FillAreaGroup group : rectGroups) {
                    if (group.getWidth() > 10 * page.getAvgCharWidth() && group.getHeight() > 10 * page.getAvgCharWidth()) {
                        wrongGroupFind = true;
                        break;
                    }
                    List<TextChunk> groupChunks = page.getMutableTextChunks(group.getGroupRectArea().rectReduce(1.0
                            , 1.0 ,page.getWidth(), page.getHeight()));
                    int vProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils
                            .calVerticalProjectionGap(groupChunks), page.getAvgCharWidth());
                    int hProjectNum = TableRegionDetectionUtils.countLargeGap(TableRegionDetectionUtils
                            .calHorizontalProjectionGap(groupChunks), page.getAvgCharWidth());
                    if (groupChunks.size() > 5 || (hProjectNum >= 3 && vProjectNum >= 3)) {
                        wrongGroupFind = true;
                        break;
                    }
                }
                if (wrongGroupFind) {
                    continue;
                }
                TableRegion chartRegion = new TableRegion(chart.getArea());
                chartRegion.setConfidence(chart.getConfidence());
                chartRegion.setChartType(chart.getType());
                chartRegions.add(chartRegion);
            } else {
                TableRegion chartRegion = new TableRegion(chart.getArea());
                chartRegion.setConfidence(chart.getConfidence());
                chartRegion.setChartType(chart.getType());
                chartRegions.add(chartRegion);
            }
        }

        //使用填充色信息检测其他chart区域
        detectExtraChartByFillArea(page);

        return chartRegions;
    }

    private void removeSameTextBlock(List<TableRegion> candidateRegions) {
        for (TableRegion tr : candidateRegions) {
            List<TextBlock> tbTemp = new ArrayList<>();
            for (int i = 0; i < tr.getTextBlockList().size();) {
                if (!tbTemp.contains(tr.getTextBlockList().get(i))) {
                    tbTemp.add(tr.getTextBlockList().get(i));
                    i++;
                } else {
                    tr.getTextBlockList().remove(i);
                }
            }
        }
    }

    private void checkCandidateRegions(ContentGroupPage page, List<TextBlock> candidateTextBlock, List<TableRegion> candidateRegions) {
        if (candidateRegions.isEmpty()) {
            return;
        }

        candidateRegions.sort(Comparator.comparing(TableRegion::getTop));
        for (TableRegion rect : candidateRegions) {
            if (page.getHorizontalRulings().isEmpty()) {
               continue;
            }

            List<Ruling> hencLines = Ruling.getRulingsFromArea(page.getHorizontalRulings(), rect);
            if (hencLines.isEmpty()) {
                continue;
            }

            hencLines.sort(Comparator.comparing(Ruling::getLeft));
            float minLeft = FastMath.min(hencLines.get(0).getLeft(), rect.getLeft());
            hencLines.sort(Comparator.comparing(Ruling::getRight));
            float maxRight = FastMath.max(hencLines.get(hencLines.size() - 1).getRight(), rect.getRight());

            Rectangle newRect = new Rectangle(minLeft, rect.getTop(), maxRight - minLeft, rect.getHeight());
            if (Rectangle.boundingBoxOf(page.getTextChunks(newRect)).nearlyEquals(Rectangle.boundingBoxOf(page.getTextChunks(rect)), 0.5)) {
                rect.setRect(newRect);
            }
        }

        //移除TextBlockList中相同的TextBlock
        removeSameTextBlock(candidateRegions);

        //如果某行文字与区域相交但没有被包含进去的则重新添加进去
        for (TableRegion tr : candidateRegions) {
            for (TextBlock tb : candidateTextBlock) {
                if (!tb.getMergeFlag() && !isChartTableTitle(tb)
                        && (tr.createIntersection(tb.getBounds2D()).getHeight() > 0.3 * tb.getHeight()
                        || TableRegionDetectionUtils.hasTextBlockWithinShape(page, tr, Arrays.asList(tb)))) {
                    tr.addTextBlockToList(tb);
                    tb.setMergeFlag();
                }
            }
        }

        //移除TextBlockList中相同的TextBlock
        removeSameTextBlock(candidateRegions);
    }

    /**
     * 根据初始版面结果结合chart进行网格划分
     * @param page
     * @param initialRect
     * @param midBound
     * @param xSegs：从左到右排序
     * @param ySegs：从上到下排序
     */
    private void makeCoordinateValue(ContentGroupPage page, Rectangle initialRect, Rectangle midBound, List<Double> xSegs, List<Double> ySegs) {
       double x1 = initialRect.getMinX();
       double x2 = FastMath.max(initialRect.getMinX(), midBound.getMinX());
       double x3 = FastMath.min(initialRect.getMaxX(), midBound.getMaxX());
       double x4 = initialRect.getMaxX();
       double y1 = initialRect.getMinY();
       double y2 = FastMath.max(initialRect.getMinY(), midBound.getMinY());
       double y3 = FastMath.min(initialRect.getMaxY(), midBound.getMaxY());
       double y4 = initialRect.getMaxY();

       xSegs.addAll(Lists.newArrayList(x1, x2, x3, x4));
       ySegs.addAll(Lists.newArrayList(y1, y2, y3, y4));
    }

    private boolean verticalLayoutSegment(ContentGroupPage page, Rectangle initialRect, List<TableRegion> chartsInReigon, List<Rectangle> processRectList) {
        boolean verticalParallel = false;

        //TODO:垂直分组
        /*List<TableRegion> segmentRegions = new ArrayList<>();
        segmentResultByTj.forEach(item -> {
            if (item.intersects(initialRect)) {
                segmentRegions.add(new TableRegion(item));
            }
        });

        List<List<TableRegion>> verticalGroups = new ArrayList<>();
        segmentRegions.sort(Comparator.comparing(TableRegion::getCenterX));
        for (TableRegion tr : segmentRegions) {
            if (verticalGroups.isEmpty()) {
                verticalGroups.add(Lists.newArrayList(tr));
                continue;
            }
            List<TableRegion> lastGroup = verticalGroups.get(verticalGroups.size() - 1);
            if (tr.horizontallyOverlapRatio(Rectangle.boundingBoxOf(lastGroup), true) > 0.8) {
                lastGroup.add(tr);
            } else {
                verticalGroups.add(Lists.newArrayList(tr));
            }
        }
        for (List<TableRegion> group : verticalGroups) {
            if (group.size() < 2) {
                continue;
            }
        }*/
        return verticalParallel;
    }

    private boolean horizontalLayoutSegment(ContentGroupPage page, Rectangle initialRect, List<TableRegion> chartsInReigon, List<Rectangle> processRectList) {
        float pageValidWidth = TableRegionDetectionUtils.getValidTextWidthInRegion(page, initialRect);
        double thresh = 10 * page.getAvgCharWidth();
        float xBias = 0.5f * page.getAvgCharWidth();
        float yBias = 0.5f * page.getAvgCharHeight();
        boolean horizontalParallel = false;
        if (!chartsInReigon.isEmpty()) {
            //水平并列chart分组
            List<List<TableRegion>> horizontalGroups = new ArrayList<>();
            chartsInReigon.sort(Comparator.comparing(TableRegion::getTop));
            for (TableRegion tr : chartsInReigon) {
                if (horizontalGroups.isEmpty()) {
                    horizontalGroups.add(Lists.newArrayList(tr));
                    continue;
                }
                List<TableRegion> lastGroup = horizontalGroups.get(horizontalGroups.size() - 1);
                if (tr.verticalOverlapRatio(Rectangle.boundingBoxOf(lastGroup)) > 0.8) {
                    lastGroup.add(tr);
                } else {
                    horizontalGroups.add(Lists.newArrayList(tr));
                }
            }

            Rectangle validInitialRect = new Rectangle(initialRect);
            for (List<TableRegion> group : horizontalGroups) {
                if (group.size() == 1) {
                    //单个chart居左或居右，如果左/右栏中为并列栏，且chart上下不存在分栏情况
                    TableRegion chartRegion = group.get(0);
                    if (!chartRegion.intersects(validInitialRect)) {
                        continue;
                    }
                    float searchHeight = 5 * page.getAvgCharHeight();
                    List<TextBlock> tempBlocks = TableRegionDetectionUtils.getNeighborTextBlocks(page.getTextLines()
                            , chartRegion.getTop(), searchHeight);
                    boolean multiChartTitleMatch = false;
                    for (TextBlock tb : tempBlocks) {
                        if (TableRegionDetectionUtils.calStrMatchNum(CHART_BEGINNING_KEYWORD, tb.getElements()) >= 2) {
                            multiChartTitleMatch = true;
                            break;
                        }
                    }
                    if (!multiChartTitleMatch) {
                        continue;
                    }
                    boolean leftAlign = (chartRegion.getLeft() < validInitialRect.getLeft())
                            || (chartRegion.getLeft() - validInitialRect.getLeft() < validInitialRect.getRight() - chartRegion.getRight());
                    List<Double> xSegs =new ArrayList<>();
                    List<Double> ySegs =new ArrayList<>();
                    makeCoordinateValue(page, validInitialRect, chartRegion, xSegs, ySegs);
                    if (xSegs.size() != 4 || ySegs.size() != 4) {
                        continue;
                    }
                    Rectangle leftRect = new Rectangle(xSegs.get(0), ySegs.get(1), xSegs.get(1) - xSegs.get(0) - xBias
                            , ySegs.get(2) - ySegs.get(1));
                    Rectangle rightRect = new Rectangle(xSegs.get(2) + xBias, ySegs.get(1), xSegs.get(3) - xSegs.get(2) - xBias
                            , ySegs.get(2) - ySegs.get(1));
                    boolean leftSeparable = (xSegs.get(1) - xSegs.get(0) > xBias) && TableRegionDetectionUtils.hasLargeGapTextBlock(page, leftRect, 2 * page.getAvgCharWidth(), 1);
                    boolean rightSeparable = (xSegs.get(3) - xSegs.get(2) > xBias) && TableRegionDetectionUtils.hasLargeGapTextBlock(page, rightRect, 2 * page.getAvgCharWidth(), 1);

                    boolean topSeparable = true;
                    boolean bottomSeparable = true;
                    if (ySegs.get(1) - ySegs.get(0) < yBias) {
                        topSeparable = false;
                    } else {
                        float topRectMinY = FastMath.max(ySegs.get(0).floatValue(), ySegs.get(1).floatValue() - searchHeight);
                        Rectangle topRect = new Rectangle(validInitialRect.getLeft(), topRectMinY, validInitialRect.getWidth()
                                , ySegs.get(1) - topRectMinY).rectReduce(xBias, yBias, validInitialRect.getWidth(), validInitialRect.getHeight());
                        List<TextBlock> topTextBlocks = page.getTextLines().stream().filter(tb -> topRect.intersects(tb.getBounds2D()))
                                .collect(Collectors.toList());
                        if (leftAlign && !leftSeparable) {
                            Rectangle topSideRect = new Rectangle(xSegs.get(2) + xBias, topRectMinY, xSegs.get(3) - xSegs.get(2) - xBias
                                    , ySegs.get(1) - topRectMinY);
                            if (TableRegionDetectionUtils.hasNoGapLongTextBlock(topTextBlocks, 0.8f * pageValidWidth, 2 * page.getAvgCharWidth(), 1)
                                    && !TableRegionDetectionUtils.hasLargeGapTextBlock(page, topSideRect, 2 * page.getAvgCharWidth(), 1)) {
                                topSeparable = false;
                            }
                        } else if (!leftAlign && !rightSeparable) {
                            Rectangle topSideRect = new Rectangle(xSegs.get(0), topRectMinY, xSegs.get(1) - xSegs.get(0) - xBias
                                    , ySegs.get(1) - topRectMinY);
                            if (TableRegionDetectionUtils.hasNoGapLongTextBlock(topTextBlocks, 0.8f * pageValidWidth, 2 * page.getAvgCharWidth(), 1)
                                    && !TableRegionDetectionUtils.hasLargeGapTextBlock(page, topSideRect, 2 * page.getAvgCharWidth(), 1)) {
                                topSeparable = false;
                            }
                        }
                    }

                    if (ySegs.get(3) - ySegs.get(2) < yBias) {
                        bottomSeparable = false;
                    } else {
                        float bottomRectMaxY = FastMath.min(ySegs.get(3).floatValue(), ySegs.get(2).floatValue() + searchHeight);
                        Rectangle bottomRect = new Rectangle(validInitialRect.getLeft(), ySegs.get(2), validInitialRect.getWidth()
                                , bottomRectMaxY - ySegs.get(2)).rectReduce(xBias, yBias, validInitialRect.getWidth(), validInitialRect.getHeight());
                        List<TextBlock> bottomTextBlocks = page.getTextLines().stream().filter(tb -> bottomRect.intersects(tb.getBounds2D()))
                                .collect(Collectors.toList());
                        if (leftAlign && !leftSeparable) {
                            Rectangle bottomSideRect = new Rectangle(xSegs.get(2) + xBias, ySegs.get(2), xSegs.get(3) - xSegs.get(2) -xBias
                                    , bottomRectMaxY - ySegs.get(2));
                            if (TableRegionDetectionUtils.hasNoGapLongTextBlock(bottomTextBlocks, 0.8f * pageValidWidth, 2 * page.getAvgCharWidth(), 1)
                                    && !TableRegionDetectionUtils.hasLargeGapTextBlock(page, bottomSideRect, 2 * page.getAvgCharWidth(), 1)) {
                                bottomSeparable = false;
                            }
                        } else if (!leftAlign && !rightSeparable) {
                            Rectangle bottomSideRect = new Rectangle(xSegs.get(0), ySegs.get(2), xSegs.get(1) - xSegs.get(0) - xBias
                                    , bottomRectMaxY - ySegs.get(2));
                            if (TableRegionDetectionUtils.hasNoGapLongTextBlock(bottomTextBlocks, 0.8f * pageValidWidth, 2 * page.getAvgCharWidth(), 1)
                                    && !TableRegionDetectionUtils.hasLargeGapTextBlock(page, bottomSideRect, 2 * page.getAvgCharWidth(), 1)) {
                                bottomSeparable = false;
                            }
                        }
                    }
                    if (!topSeparable && !bottomSeparable) {
                        horizontalParallel = true;
                        if (!processRectList.isEmpty() && validInitialRect.nearlyEquals(processRectList.get(processRectList.size() - 1))) {
                            processRectList.remove(processRectList.size() - 1);
                        }
                        //左
                        if (xSegs.get(1) - xSegs.get(0) > xBias) {
                            processRectList.add(leftRect);
                        }
                        //右
                        if (xSegs.get(3) - xSegs.get(2) > xBias) {
                            processRectList.add(rightRect);
                        }
                        //上
                        if (ySegs.get(1) - ySegs.get(0) > yBias) {
                            processRectList.add(new Rectangle(validInitialRect.getLeft(), ySegs.get(0), validInitialRect.getWidth()
                                    , ySegs.get(1) - ySegs.get(0) - yBias));
                        }
                        //下
                        if (ySegs.get(3) - ySegs.get(2) > yBias) {
                            processRectList.add(new Rectangle(validInitialRect.getLeft(), ySegs.get(2) + yBias, validInitialRect.getWidth()
                                    , ySegs.get(3) - ySegs.get(2) - yBias));
                            validInitialRect.setTop(ySegs.get(2).floatValue() + yBias);
                        }
                    }
                } else if (group.size() >= 2) {
                    //多个chart水平并列
                    Rectangle bound = new Rectangle(Rectangle.boundingBoxOf(group).createIntersection(validInitialRect));
                    if (bound.getWidth() < 0.7 * pageValidWidth) {
                        continue;
                    }
                    group.sort(Comparator.comparing(TableRegion::getLeft));
                    boolean middleSeparable = false;
                    for (int i = 0; i < group.size() - 1; i++) {
                        if (group.get(i + 1).getLeft() - group.get(i).getRight() < thresh) {
                            continue;
                        }
                        Rectangle middleRect = new Rectangle(group.get(i).getRight(), bound.getTop()
                                , group.get(i + 1).getLeft() - group.get(i).getRight(), bound.getHeight());
                        if (TableRegionDetectionUtils.hasLargeGapTextBlock(page, middleRect, 2 * page.getAvgCharWidth(), 2)) {
                            middleSeparable = true;
                            break;
                        }
                    }
                    if (middleSeparable) {
                        continue;
                    }

                    List<Double> xSegs =new ArrayList<>();
                    List<Double> ySegs =new ArrayList<>();
                    makeCoordinateValue(page, validInitialRect, bound, xSegs, ySegs);
                    if (xSegs.size() != 4 || ySegs.size() != 4) {
                        continue;
                    }

                    Rectangle leftRect = new Rectangle(xSegs.get(0), ySegs.get(1), xSegs.get(1) - xSegs.get(0) - xBias
                            , ySegs.get(2) - ySegs.get(1));
                    Rectangle rightRect = new Rectangle(xSegs.get(2) + xBias, ySegs.get(1), xSegs.get(3) - xSegs.get(2) - xBias
                            , ySegs.get(2) - ySegs.get(1));
                    boolean leftSeparable = (xSegs.get(1) - xSegs.get(0) > xBias) && TableRegionDetectionUtils.hasLargeGapTextBlock(page, leftRect, 2 * page.getAvgCharWidth(), 1);
                    boolean rightSeparable = (xSegs.get(3) - xSegs.get(2) > xBias) && TableRegionDetectionUtils.hasLargeGapTextBlock(page, rightRect, 2 * page.getAvgCharWidth(), 1);
                    if (!leftSeparable && !rightSeparable) {
                        horizontalParallel = true;
                        if (!processRectList.isEmpty() && validInitialRect.nearlyEquals(processRectList.get(processRectList.size() - 1))) {
                            processRectList.remove(processRectList.size() - 1);
                        }
                        //左
                        if (xSegs.get(1) - xSegs.get(0) > xBias) {
                            processRectList.add(leftRect);
                        }
                        //右
                        if (xSegs.get(3) - xSegs.get(2) > xBias) {
                            processRectList.add(rightRect);
                        }
                        //上
                        if (ySegs.get(1) - ySegs.get(0) > yBias) {
                            processRectList.add(new Rectangle(validInitialRect.getLeft(), ySegs.get(0), validInitialRect.getWidth()
                                    , ySegs.get(1) - ySegs.get(0) - yBias));
                        }
                        //下
                        if (ySegs.get(3) - ySegs.get(2) > yBias) {
                            processRectList.add(new Rectangle(validInitialRect.getLeft(), ySegs.get(2) + yBias, validInitialRect.getWidth()
                                    , ySegs.get(3) - ySegs.get(2) - yBias));
                            validInitialRect.setTop(ySegs.get(2).floatValue() + yBias);
                        }
                    }
                }
            }
        }
        return horizontalParallel;
    }


    /**
     * 对初始版面区域进行再次切分
     */
    private List<Rectangle> layoutSegment(ContentGroupPage page, Rectangle initialRect) {
        List<Rectangle> processRectList = new ArrayList<>();
        List<TableRegion> chartsInReigon = chartRegions.stream().filter(chart -> chart.overlapRatio(initialRect, false) > 0.5)
                .collect(Collectors.toList());

        if (horizontalLayoutSegment(page, initialRect, chartsInReigon, processRectList)
                || verticalLayoutSegment(page, initialRect, chartsInReigon, processRectList)) {
            TableDebugUtils.writeImage(page, processRectList, "processRectList");
            return processRectList;
        }

        processRectList.clear();
        processRectList.add(initialRect);
        return processRectList;
    }

    private void evaluateNolLineTableConfidence(ContentGroupPage page, List<TableRegion> NoLineTableRegions) {
        if (NoLineTableRegions.isEmpty()) {
            return;
        }

        //单元格文字内容太多，疑似正文过检;字体差别太大
        for (TableRegion rc : NoLineTableRegions) {
            List<TextChunk> chunkWithinRC = page.getMutableMergeChunks(rc);
            for (TextChunk tc : chunkWithinRC) {
                if (tc.getWidth() > rc.getWidth()) {
                    rc.downgradeConfidence(0.1);
                }
            }

            List<TextBlock> textBlocks = rc.getTextBlockList();
            for (TextBlock tb : textBlocks) {
                if (TableRegionDetectionUtils.splitChunkByRow(tb.getElements()).size() > 2) {
                    if (textBlocks.size() < 4) {
                        rc.downgradeConfidence(0.2);
                    } else {
                        rc.downgradeConfidence(0.1);
                    }
                }
                if (tb.getValidTextElements().size() > 100) {
                    rc.downgradeConfidence(0.2);
                }
            }

            if (TableRegionDetectionUtils.calFontDiffRatio(chunkWithinRC) > 1.5) {
                rc.downgradeConfidence(0.2);
            }

            if (rc.getTextBlockList().size() == 1) {
                TextBlock tb = rc.getTextBlockList().get(0);
                if (tb.getSize() <= 5) {
                    if (TableRegionDetectionUtils.numberStrRatio(tb.getElements()) < 0.5) {
                        rc.downgradeConfidence(0.8);
                    } else {
                        rc.downgradeConfidence(0.5);
                    }
                } else {
                    if (tb.getHeight() > 2 * tb.getAvgCharHeight()) {
                        rc.downgradeConfidence(0.5);
                    } else {
                        rc.downgradeConfidence(0.2);
                    }
                }
            }

            if (TableRegionDetectionUtils.calTableColNum(page, rc) == 1) {
                if (TableRegionDetectionUtils.numberStrRatio(page.getTextChunks(rc)) < 0.5) {
                    rc.downgradeConfidence(0.7);
                } else {
                    rc.downgradeConfidence(0.5);
                }
            }
        }

        //含有特殊字符
        boolean possibleContentMatch = TableRegionDetectionUtils.matchAnyText(page.getTextLines(), SPECIAL_STR_CONTENT_RE);
        for (TableRegion rc : NoLineTableRegions) {
            List<TextBlock> textChoose = TableRegion.getTopOuterTextBlock(page, rc, page.getTextLines(), 2);
            textChoose.addAll(rc.getTextBlockList().subList(0, Math.min(2, rc.getTextBlockList().size())));
            if (possibleContentMatch || TableRegionDetectionUtils.matchAnyText(textChoose, SPECIAL_STR_CONTENT_RE)
                    || TableRegionDetectionUtils.matchAnyText(textChoose, CHART_STR_RE_1)) {
                rc.downgradeConfidence(0.5f);
            }
        }

        //矢量流乱序
        for (TableRegion rc : NoLineTableRegions) {
            List<TextChunk> chunkWithinRC = page.getMutableMergeChunks(rc);
            chunkWithinRC.sort(Comparator.comparing(TextChunk::getCenterY));
            if (chunkWithinRC.size() < 2) {
                continue;
            }
            boolean bigIdxDiffFindFlag = false;
            for (int i= 0; i < chunkWithinRC.size() - 1; i++) {
                if (FastMath.abs(chunkWithinRC.get(i + 1).getGroupIndex() - chunkWithinRC.get(i).getGroupIndex()) >= 20) {
                    bigIdxDiffFindFlag = true;
                    break;
                }
            }
            if (bigIdxDiffFindFlag) {
                rc.downgradeConfidence(0.2f);
            }
        }

        //表格内容稀疏;行列对齐性差
        for (TableRegion rc : NoLineTableRegions) {
            if (rc.calSparseRatio(page) > 0.5) {
                rc.downgradeConfidence(0.2f);
            }

            //行列对齐性差
            if (rc.getTextBlockList().size() <= 2) {
                if (TableRegionDetectionUtils.calTableColNum(page, rc) <= 2) {
                    rc.downgradeConfidence(0.8);
                } else if (!TableRegionDetectionUtils.hasNumberStr(page.getTextChunks(rc))) {
                    rc.downgradeConfidence(0.5);
                }
                rc.downgradeConfidence(0.2);
            }
        }

        //填充色
        for (TableRegion rc : NoLineTableRegions) {
            List<FillAreaGroup> fillAreaGroups = page.getPageFillAreaGroup().stream().filter(group -> group.getGroupRectArea()
                    .intersects(rc)).collect(Collectors.toList());
            if (fillAreaGroups.size() > 2) {
                rc.downgradeConfidence(0.1);
            }
        }

        //正文段落
        for (TableRegion rc : NoLineTableRegions) {
            List<SegmentPageInfo> segmentPageInfos = segmentPageByTj(page, rc, this.lineTableRegions, true, false);
            for (SegmentPageInfo segmentPageInfo : segmentPageInfos) {
                if (TableRegionDetectionUtils.splitChunkByRow(segmentPageInfo.getTextChunks()).size() > 4) {
                    rc.downgradeConfidence(0.2);
                }
            }
        }

        //如果表格行列数较多，则升级置信度
        for (TableRegion rc : NoLineTableRegions) {
            //多行多列结构
            if (rc.getTextLineSize() > 4 && TableRegionDetectionUtils.calTableColNum(page, rc, rc.getTextBlockList()) > 3
                    && rc.calSparseRatio(page) < 0.3) {
                double confidence = rc.getConfidence() < 0.5 ? (Math.max(rc.getConfidence() + 0.3, 0.6)) : rc.getConfidence();
                rc.upgradeConfidence(confidence - rc.getConfidence());
                continue;
            }
            //有特殊表格标题
            List<TextBlock> textChoose = TableRegion.getTopOuterTextBlock(page, rc, page.getTextLines(), 2);
            textChoose.addAll(rc.getTextBlockList().subList(0, Math.min(2, rc.getTextBlockList().size())));
            if (TableRegionDetectionUtils.matchAnyText(textChoose, TABLE_BEGINNING_KEYWORD)) {
                double confidence = rc.getConfidence() < 0.5 ? (Math.max(rc.getConfidence() + 0.3, 0.6)) : rc.getConfidence();
                rc.upgradeConfidence(confidence - rc.getConfidence());
            }
        }
    }

    private List<TableRegion> detectTableWithinRegion(ContentGroupPage page, List<TextChunk> textChunks, Rectangle processRect, boolean isPageColumnar) {
        List<TextBlock> textLines;
        List<Ruling> hRulingInProcessRect;
        List<FillAreaGroup> fillAreaGroupsInProcessRect;
        if (isPageColumnar) {
            hRulingInProcessRect = Ruling.getRulingsFromArea(page.getHorizontalRulings(), processRect);
            List<TextChunk> textChunksWithinRC = textChunks.stream().filter(tc -> processRect.isShapeIntersects(tc)).collect(Collectors.toList());
            if (textChunksWithinRC.isEmpty()) {
                return new ArrayList<>();
            }
            textLines = TextMerger.collectByRows(TextMerger.groupByBlock(textChunksWithinRC, page.getHorizontalRulings(), page.getVerticalRulings()));
            fillAreaGroupsInProcessRect = page.getPageFillAreaGroup().stream().filter(group -> group.getGroupRectArea()
                    .isShapeIntersects(processRect)).collect(Collectors.toList());
        } else {
            hRulingInProcessRect = page.getHorizontalRulings();
            textLines = TextMerger.collectByRows(TextMerger.groupByBlock(textChunks, page.getHorizontalRulings(), page.getVerticalRulings()));
            fillAreaGroupsInProcessRect = page.getPageFillAreaGroup();
        }

        for (TextBlock line: textLines) {
            line.getElements().sort(Comparator.comparing(TextChunk::getLeft));
        }

        //filter page header and footer
        filterHeaderAndFooter(page, textLines);

        //过滤掉含有特殊字符的行
        //filterInvalidTextLines(textLines);

        //split textLines to normal direction and unnormal direction
        List<TextBlock> normalTextBlocks = new ArrayList<>();
        List<TextBlock> unnormalTextBlocks = new ArrayList<>();
        TableRegionDetectionUtils.splitTextLine(textLines, normalTextBlocks, unnormalTextBlocks);

        //calculate page valid text line width
        float pageValidWidth = TableRegionDetectionUtils.getValidTextWidthInRegion(page, processRect);

        //find most possible table region
        List<TableRegion> candidateRegions = findMostPossibleRegion(page, normalTextBlocks, processRect, pageValidWidth);

        //collect serial number textline info
        float avgTextLineGap = calAvgTextLineGap(candidateRegions);
        LinkedHashMap<TextBlock, SerialNumberInfo> snMap = collectSerialNumberInfo(page, normalTextBlocks, avgTextLineGap);

        //merge possible region
        mergeMostPossibleRegion(page, normalTextBlocks, candidateRegions, hRulingInProcessRect, processRect, pageValidWidth, snMap, fillAreaGroupsInProcessRect);

        //find special feature table region
        mergeSpecialTableRegion(page, normalTextBlocks, candidateRegions, hRulingInProcessRect, processRect, pageValidWidth);

        //check region
        checkCandidateRegions(page, textLines, candidateRegions);

        // merge possible split region
        mergePossibleSplitRegions(page, candidateRegions, textLines, unnormalTextBlocks);

        //correct table region
        correctTableRegion(page, candidateRegions, snMap);

        // filter invalid tables
        filterInvalidTables(page, candidateRegions, textLines, pageValidWidth);

        //无线表格置信度评级
        evaluateNolLineTableConfidence(page, candidateRegions);

        return candidateRegions;
    }

    public List<TableRegion> detect(ContentGroupPage page) {
        List<TableRegion> noRulingTableRegions = new ArrayList<>();

        //line-table detect
        this.lineTableRegions = page.getLineTableRegions();//真正的有线框表格（含实际分类成的有线和无线表格）
        this.rulingRectangles = RulingTableRegionsDetectionAlgorithm.findRulingRectangles(page);//由线组成的外接框区域

        //find possible chart region
        this.chartRegions = page.getChartRegions("NoRulingTableRegionsDetectionAlgorithm");
        TableDebugUtils.writeImage(page, page.getCharts().stream().map(chart -> new Rectangle(chart.getArea())).collect(Collectors.toList()), "raw_chart_rectangles");
        TableDebugUtils.writeImage(page, this.chartRegions, "chart_rectangles");

        //analysis page layout：analysis again
        List<Rectangle> layoutAnalysisRectList = page.getTableLayoutAnalysis();
        boolean isPageColumnar = layoutAnalysisRectList.size() > 1;
        if (isPageColumnar) {
            logger.info(String.format("page %d has columnar", page.getPageNumber()));
            TableDebugUtils.writeImage(page, layoutAnalysisRectList, "pageColumnar");
        }

        //filter wrong line table region
        filterFalseLineTableRegion(page, isPageColumnar);

        //get textChunks exclude rulingTableRegions/chart/paragraph
        List<TextChunk> textChunks = findTextChunks(page);
        if (textChunks.isEmpty()) {
            return new ArrayList<>();
        }

        //NoLine table region detect within processRect
        for (Rectangle initialRect : layoutAnalysisRectList) {
            //segment initialRect by chartRegion
            boolean fineSeg = true;
            List<Rectangle> processRectList;
            if (fineSeg) {
                processRectList = layoutSegment(page, initialRect);
            } else {
                processRectList = Lists.newArrayList(initialRect);
            }

            isPageColumnar = isPageColumnar || processRectList.size() > 1;
            for (Rectangle processRect : processRectList) {
                noRulingTableRegions.addAll(detectTableWithinRegion(page, textChunks, processRect, isPageColumnar));
            }
        }

        return noRulingTableRegions;
    }
}
