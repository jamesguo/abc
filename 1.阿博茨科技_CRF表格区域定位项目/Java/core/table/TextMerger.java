package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.TextUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 **文字块合并策略
 * Created by xwang on 17-12-7.
 */
public class TextMerger {
    private static final float AVERAGE_CHAR_TOLERANCE = 0.5f;
    private static final float SAME_ROW_OVERLAP = 0.5f;

    private static final Pattern TEXT_CHUNK_END = Pattern.compile("[.。:：)）]\\s*$");
    private static final Pattern TEXT_SECTION_PREFIX = Pattern.compile("^\\s*[(（]?[一二三四五六七八九十\\d]([.．、:：)）\\s]+)\\s*");
    private static final Pattern TEXT_CJK_SEGMENT = Pattern.compile(".*[、：]\\s*$");
    private static final Pattern TEXT_LIST_SYMBOL = Pattern.compile("\\s*[‧]\\s*");
    private static final Pattern TEXT_NUMBER_CONTENT = Pattern.compile("[\\s€$(（）),.+\\-\\d%]*");
    private static final Pattern CONTAIN_NUMBER_PREFIX = Pattern.compile(".*\\d+.*");
    public static final Pattern NUM_GROUP_PREFIX = Pattern.compile("[$€(（.+\\-]{0,4}[\\s]?\\d[\\d%）).,]*");
    private static final Pattern NUM_PREFIX_ENDING = Pattern.compile(".*[\\d\\s%）)]$");
    private static final Pattern TEXT_NUMBER = Pattern.compile("\\s*[+\\-]?\\d[\\d.,]*\\s*");
    private static final Pattern TEXT_LINE_END = Pattern.compile("。\\s*$");
    private static final Pattern TEXT_PERCENTAGE = Pattern.compile("\\s*[+-]?(\\d{1,3}(\\.\\d+)?%)\\s*");
    private static final Pattern TEXT_DATE_FORMAT = Pattern.compile("(\\d{2,4}[-|\\/|年])?([0]?[1-9]|1[1-2])[-|\\/|月](([0]?[1-9]|[1-2]\\d|3[0-1])[日]?)?");
    private static final Pattern TEXT_DATE_SHORT = Pattern.compile("\\s*((0)?[1-9]|1[1-2])月((0)?[1-9]|[1-2]\\d|3[0-1])日\\s*");
    private static final Pattern TEXT_DATE_YEAR = Pattern.compile("(199\\d|2[01]\\d{2,})[A|E]?");
    private static final Pattern BRACKET_TEXT_PREFIX = Pattern.compile("\\s*[(（].*[）)]\\s*");
    private static final Pattern TEXT_MONEY_UNIT = Pattern.compile("[$€￥]");
    public static final Pattern TEXT_SUPER_SCRIPT = Pattern.compile("\\s*[(（][a-g|1-9][）)]\\s*");

    // 特殊模板
    private static final Pattern TEXT_SERIAL_DOT = Pattern.compile("[^•]*(•){1}[^•]*");
    private static final Pattern TEXT_UNDER_DOTS = Pattern.compile("(\\s*)((\\.\\s)+|(\\.)+)(\\.)(\\s*)");

    // 左边文本块未结束模版
    private static final Pattern TEXE_CONTINUE_LEFT = Pattern.compile("[^()（）]*[(（][^()（）]*");
    // 右边文本块连续模版
    private static final Pattern TEXE_CONTINUE_RIGHT = Pattern.compile("[^()（）]*[）)][^()（）]*");

    //以括号开始的文本
    private static final Pattern TEXT_BEGIN_WITH_BRACKET = Pattern.compile("\\s*[(（].*");
    //以括号结束的文本
    private static final Pattern TEXT_ENDIN_WITH_BRACKET = Pattern.compile(".*[）)]\\s*");

    private static final List<Pattern> TEXT_MERGER_TEMPLATE_WHITELISTS = Arrays.asList(
            Pattern.compile("项目"),
            Pattern.compile("资产"),
            Pattern.compile("釋義"),
            Pattern.compile("[入出]金[:：]?"),
            Pattern.compile("仓储(费)?[:：]?"),
            Pattern.compile("递延(费)?[:：]?"),
            Pattern.compile("违约(金)?[:：]?"),
            Pattern.compile("运保(费)?[:：]?"));

    private static final List<Pattern> TEXT_MERGER_TEMPLATE_BLACKLISTS = Arrays.asList(
            Pattern.compile("Male[\\s]*Female")
    );

    private static String MERGER_TYPE = "MergerType";

    enum ChunkMergerType {
        NORMAL,
        CHUNK_END,
        POTENTIAL_CHUNK_END,
        BLOCK_END,
    }

    // 判断当前文本为数值类文本，只包含数值及相关的数学符号、货币单位
    //eg: $ 2345,341.4 或 2015-2-92016-4-52017-3-4等
    public static boolean isNumberLine(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }

        return TEXT_NUMBER_CONTENT.matcher(text).matches() && CONTAIN_NUMBER_PREFIX.matcher(text).matches();
    }

    // 判断当前文本是否为多块数值，如 234.34  23,234.1  $452.2  .98  0.56  (2345)
    // 连续较长非有效数值块不属于正样例，如2015-2-92016-4-52017-3-4
    public static boolean isNumberGroup(String text) {
        if (!isNumberLine(text)) {
            return false;
        }

        String[] splitTexts = text.split(" ");
        int groupNum = 0;
        for (String subText: splitTexts) {
            if (StringUtils.isBlank(subText) || TEXT_MONEY_UNIT.matcher(subText.trim()).matches()) {
                continue;
            }

            groupNum += 1;
            if (!NUM_GROUP_PREFIX.matcher(subText).matches() || !NUM_PREFIX_ENDING.matcher(subText).matches()) {
                return false;
            }
        }
        return groupNum >= 2;
    }

    public static List<TextChunk> mergeCellTextChunks(List<TextChunk> textChunks) {
        if (textChunks.isEmpty()) {
            return textChunks;
        }
        // 使用默认的文本块合并机制，自动填补丢失的空格
        TextBlock cellBlock = new TextBlock();
        cellBlock.setAdjustSpaces(true);
        cellBlock.addElements(textChunks);
        return cellBlock.getElements();
    }

    private static boolean isSameRow(TextElement textElement1, TextElement textElement2) {
        return textElement1.verticalOverlapRatio(textElement2) > SAME_ROW_OVERLAP;
    }

    private static boolean isSameRow(TextChunk textChunk1, TextChunk textChunk2) {
        return isSameRow(textChunk1.getLastElement(), textChunk2.getFirstElement());
    }

    private static Rectangle getTextValidRect(List<TextChunk> textChunks) {
        List<TextChunk> filterChunk = textChunks.stream()
                .filter(textChunk->!PaperParameter.A4.topMarginContains(textChunk.getBottom()) &&
                        !PaperParameter.A4.bottomMarginContains(textChunk.getTop()))
                .collect(Collectors.toList());
        if (!filterChunk.isEmpty()) {
            return Rectangle.boundingBoxOf(filterChunk);
        } else {
            return new Rectangle(0, 0, 0, 0);
        }
    }

    // TODO:目前对中文有效
    private static boolean isTextRow(TextChunk lastChunk, TextChunk nextChunk, Rectangle textVaildRect, float searchHeight) {
        // overlapRatio
        if (lastChunk.horizontallyOverlapRatio(nextChunk) < 0.8f) {
            return false;
        }

        // lineSpace
        if (nextChunk.getTop() <= lastChunk.getBottom() + 0.7f * searchHeight) {
            return false;
        }

        // lastTextShort
        if (lastChunk.getWidth() / nextChunk.getWidth() >= 0.5f) {
            return false;
        }

        boolean nextChunkTextFeature = TEXT_LINE_END.matcher(nextChunk.getText()).find();
        boolean lastChunkTextFeature = TEXT_LINE_END.matcher(lastChunk.getText()).find();

        // 正文一行不小于350，经验值
        double textVaildWidth = Math.max(350.0, textVaildRect.getWidth());
        boolean nextLongText = nextChunk.getTextLength() > textVaildWidth * 0.8;
        boolean lastLongText = lastChunk.getTextLength() > textVaildWidth * 0.8;
        boolean lastChunkIsText = lastLongText || lastChunkTextFeature;
        boolean nextChunkIsText = nextLongText || nextChunkTextFeature;

        return !lastChunkIsText && nextChunkIsText;
    }

    private static int countSpecificString(String text, String s) {
        // 根据指定的字符构建正则
        Pattern pattern = Pattern.compile(s);
        // 构建字符串和正则的匹配
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        // 循环依次往下匹配
        while (matcher.find()){
            // 如果匹配,则数量+1
            count++;
        }
        return  count;
    }

    private static boolean canMergeChunk(TextChunk textChunk1, TextChunk textChunk2, RectangleSpatialIndex<TextChunk> chunkSpatialIndex,
                                         List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        // 不同行的不能合并, 或待合并文本在左边的不能合并
        if (!isSameRow(textChunk1, textChunk2) || textChunk1.getVisibleMinX() > textChunk2.getVisibleMaxX()) {
            // 例外条件，针对上标时，isSameRow可能不满足，但存在一定的水平重合
            if (textChunk1.verticalOverlapRatio(textChunk2) < 0.4 || !TEXT_SUPER_SCRIPT.matcher(textChunk2.getText()).matches()) {
                return false;
            }
        }

        // 如果前一个chunk具有结束标志，则不能合并
        if (textChunk1.hasTag(MERGER_TYPE) && textChunk1.getTag(MERGER_TYPE) == ChunkMergerType.CHUNK_END) {
            // 根据情况细分，部分情况下CHUNK_END视为无效
            boolean status = true;
            if (TEXT_UNDER_DOTS.matcher(textChunk1.getText()).find() && TEXT_UNDER_DOTS.matcher(textChunk2.getText()).matches()) {
                status = false;
            }

            if (status) {
                return false;
            }
        }

        // 满足模版特例情况，不考虑后面其他条件
        if (textChunk1.getText().trim().length() + textChunk2.getText().trim().length() <= 4) {
            // 目前模版集最多4字符长
            for (Pattern pattern: TEXT_MERGER_TEMPLATE_WHITELISTS) {
                if (pattern.matcher(textChunk1.getText().trim() + textChunk2.getText().trim()).matches()) {
                    return true;
                }
            }
        }

        for (Pattern pattern: TEXT_MERGER_TEMPLATE_BLACKLISTS) {
            if (pattern.matcher(textChunk1.getText().trim() + textChunk2.getText().trim()).matches()) {
                return false;
            }
        }

        // 文字方向确定的文字块只能和同方向的合并, 方向不确定的可以和任意方向合并
        if (textChunk1.getDirection() != textChunk2.getDirection()
                && textChunk1.getDirection() != TextDirection.UNKNOWN
                && textChunk2.getDirection() != TextDirection.UNKNOWN) {
            return false;
        }

        float wordSpacing1 = textChunk1.getMostCommonElementInterval();
        float wordSpacing2 = textChunk2.getMostCommonElementInterval();
        float maxCharWidth1 = (float) textChunk1.getMaxElementVisibleWidth();
        float maxCharWidth2 = (float) textChunk2.getMaxElementVisibleWidth();
        boolean twoSingleChar = wordSpacing1 == 0 && wordSpacing2 == 0;
        float maxWordSpace = Math.max(wordSpacing1, wordSpacing2);
        float maxCharWidth = Math.max(maxCharWidth1, maxCharWidth2);
        float searchWidth;
        boolean isCJK = false, isNumber = false, isEng = false;
        if (TextUtils.containsCJK(textChunk1.getText()) || TextUtils.containsCJK(textChunk2.getText())) {
            isCJK = true;
            searchWidth = maxCharWidth;
        } else if (isNumberLine(textChunk1.getText()) && isNumberLine(textChunk2.getText())) {
            isNumber = true;
            searchWidth = twoSingleChar ? maxCharWidth*2.f : maxWordSpace + maxCharWidth;
        } else {
            isEng = true;
            searchWidth = twoSingleChar ? maxCharWidth*2.f : maxWordSpace + maxCharWidth;
        }

        // TODO:有缺陷，待完善
        // 文本连续的特殊模版
        //if ((TEXE_CONTINUE_LEFT.matcher(textChunk1.getText()).matches() ||
        //       TEXE_CONTINUE_RIGHT.matcher(textChunk2.getText()).matches())) {
        //    return true;
        //}

        if (TEXT_SECTION_PREFIX.matcher(textChunk1.getText()).matches() ||
                TEXT_CJK_SEGMENT.matcher(textChunk1.getText()).matches() ||
                TEXT_LIST_SYMBOL.matcher(textChunk1.getText()).matches()) {
            // 项目符号后面的空格比较多, 增大查找的区域
            searchWidth = searchWidth + maxCharWidth;
        }

        boolean canMerge = true;
        if (!(textChunk1.getVisibleMinX() <= textChunk2.getVisibleMinX() &&
                textChunk2.getVisibleMinX() - textChunk1.getVisibleMaxX() <= searchWidth)) {
            return false;
        } else {
            // 判断是否两文本之间有ruling分隔，如果有则不能合并
            boolean acrossVerticalRuling = false;
            for (Ruling r : verticalRulingLines) {
                if ((r.isVerticallyOverlap(textChunk1) && r.isVerticallyOverlap(textChunk2)) &&
                        ((textChunk1.getCenterX() < r.getPosition() && textChunk2.getCenterX() > r.getPosition()) ||
                                (textChunk1.getCenterX() > r.getPosition() && textChunk2.getCenterX() < r.getPosition()))) {
                    acrossVerticalRuling = true;
                    break;
                }
            }
            if (acrossVerticalRuling) {
                return false;
            }
        }

        // 不同的字体，且不相邻的，则不能合并
        if (textChunk1.getMostCommonTextStyle() != textChunk2.getMostCommonTextStyle()) {
            double distance = Math.max(textChunk1.getMostCommonElementInterval(), textChunk2.getMostCommonElementInterval())*1.1;
            distance = Math.max(distance*2.f, 1);

            // chunk2为单元素，且字体比chunk1字体小，假设合并后，整体分析判断chunk2是否为上标
            boolean isSuperscript = false;
            if (textChunk2.getElements().size() == 1 &&
                    textChunk2.getFirstElement().getFontSize() < textChunk1.getLastElement().getFontSize()) {
                TextChunk tempChunk = new TextChunk(textChunk1);
                tempChunk.merge(textChunk2);
                isSuperscript = tempChunk.isEndWithSuperscript();
            }

            // TODO: 优化下划线字体的判定，特别是(\/)等符号
            if (textChunk1.horizontalVisibleDistance(textChunk2) > distance && !isSuperscript &&
                    !TEXT_BEGIN_WITH_BRACKET.matcher(textChunk2.getText()).matches() &&
                    textChunk1.getMostCommonTextStyle() != TextElement.TEXT_STYLE_UNDERLINE &&
                    textChunk2.getMostCommonTextStyle() != TextElement.TEXT_STYLE_UNDERLINE) {
                canMerge = false;
            }
        }

        // 内容完全相同，则可能是表格头的一致性描述单元格内容
        if (Objects.equals(textChunk1.getText().trim(), textChunk2.getText().trim())) {
            // TODO： 可能会造成误切分，后续根据实际误样本细化本条件
            // 细化条件：1.只对非数值文本; 2.每个chunk包含的element要大于等于2
            if (textChunk1.getElements().size() >= 2 && textChunk2.getElements().size() >= 2 &&
                    !isNumberLine(textChunk1.getText()) && !isNumberLine(textChunk2.getText())) {
                return false;
            }
        }

        // 如果两个文本块下面分别都有独立对应的下划线，且对应的下划线中间空隙能切分两文本块，则不能合并
        float minLeft = Math.min(textChunk1.getLeft(), textChunk2.getLeft());
        float maxTop = Math.max(textChunk1.getTop(), textChunk2.getTop());
        float maxRight = Math.max(textChunk1.getRight(), textChunk2.getRight());
        float maxBottom = Math.max(textChunk1.getBottom(), textChunk2.getBottom());
        float extraHeight = (float) Math.min(textChunk1.getHeight(), textChunk2.getHeight()) * 0.5f;
        List<Ruling> chunkRuling = Ruling.getRulingsFromArea(horizontalRulingLines,
                new Rectangle2D.Float(minLeft, maxTop + extraHeight, maxRight - minLeft, maxBottom - maxTop));
        if (chunkRuling.size() == 2) {
            chunkRuling.sort(Comparator.comparing(Ruling::getLeft));
            if (chunkRuling.get(0).getRight() < chunkRuling.get(1).getLeft()) {
                float centreX = (chunkRuling.get(0).getRight() + chunkRuling.get(1).getLeft()) * 0.5f;
                if (textChunk1.getRight() < centreX && textChunk2.getLeft() > centreX) {
                    return false;
                }
            }
        }

        // 针对特殊模式分析处理
        if (canMerge) {
            // 1.对数值和百分数文本合并处理
            if (isNumber) {
                // 如果后面的文本块是百分数，前面的数值位数较多，中间还存在较大间距，则基本可判断不能合并
                if (TEXT_PERCENTAGE.matcher(textChunk2.getText()).matches() &&
                        textChunk1.getText().trim().length() > 3 &&
                        textChunk2.getVisibleMinX() - textChunk1.getVisibleMaxX() > maxWordSpace*3f) {
                   return false;
                }

                // 如果两个数值都是以$符号开始，则不能合并，较常见于英文表格中
                if (textChunk1.getText().trim().charAt(0) == '$' &&
                        textChunk2.getText().trim().charAt(0) == '$') {
                    return false;
                }

                //
                if (NUM_GROUP_PREFIX.matcher(textChunk1.getText()).matches() && NUM_GROUP_PREFIX.matcher(textChunk2.getText()).matches()) {
                    // 如果前一个数值块存在小数点，后一数值块存在千分符或者也存在小数点，则不合并
                    if (textChunk1.getText().contains(".") && (textChunk2.getText().contains(",") || textChunk2.getText().contains(".")) &&
                            textChunk2.getVisibleMinX() - textChunk1.getVisibleMaxX() > maxWordSpace*3f) {
                        return false;
                    }
                }
            }

            if (isEng) {
                // 前文本块为小写开头，以上标字符结尾，后文本块为大写开头，且两文本块非零字符个数比较少(限定短内容或单词)，则不能合并，多见于英文表头比较紧密的情况
                TextElement firstTE1 = textChunk1.getFirstNonBlankElement();
                TextElement firstTE2 = textChunk2.getFirstNonBlankElement();
                if (firstTE1 != null && firstTE2 != null &&
                        Character.isLowerCase(firstTE1.getText().charAt(0)) &&
                        Character.isUpperCase(firstTE2.getText().charAt(0)) &&
                        textChunk1.getText().replace(" ", "").length() < 10 &&
                        textChunk2.getText().replace(" ", "").length() < 10 &&
                        textChunk1.isEndWithSuperscript()) {
                    return false;
                }

                // 特例：针对矢量信息不存在错误的情况下，无法用通用规则或算法正确拆分合并
                // 前文本是括号结束(增加限制条件：前文本非数值文本)，后文本是%号开始，不符合百分数规则，则不能合并
                if (TEXT_ENDIN_WITH_BRACKET.matcher(textChunk1.getText()).matches() &&
                        !TEXT_NUMBER_CONTENT.matcher(textChunk1.getText()).matches() &&
                        textChunk2.getText().trim().startsWith("%")) {
                    return false;
                }
            }

            // 对两个都是年度，则不合并
            if (TEXT_DATE_YEAR.matcher(textChunk1.getText().trim()).matches() &&
                    TEXT_DATE_YEAR.matcher(textChunk2.getText().trim()).matches()) {
                return false;
            }
        }

        return canMerge;
    }

    private static boolean canMergeBlock(TextBlock prevBlock, TextChunk nextText, RectangleSpatialIndex<TextChunk> chunkSpatialIndex,
                                         List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines,
                                         Rectangle textVaildRect) {
        TextChunk lastText = prevBlock.getLastTextChunk();
        if (lastText.hasTag(MERGER_TYPE) && lastText.getTag(MERGER_TYPE) == ChunkMergerType.BLOCK_END) {
            return false;
        }

        if (!isSameRow(lastText, nextText)) {
            // 如果不在同一行，则分析是否满足换行的合并单元格
            float meanHeight = (float) (lastText.getVisibleHeight() + nextText.getVisibleHeight()) / 2.0f;
            float searchHeight = meanHeight * 1.5f;
            if (lastText.horizontallyOverlapRatio(nextText) < 0.5 ||
                    !(lastText.getVisibleMinY() <= nextText.getVisibleMinY() && nextText.getVisibleMinY() <= lastText.getVisibleMaxY() + searchHeight)) {
                return false;
            }

            // 换行的字体颜色不同，则不能合并
            Color lastTextColor = lastText.getMostCommonFontColor();
            Color nextTextColor = nextText.getMostCommonFontColor();
            if ((lastTextColor != null && !lastTextColor.equals(nextTextColor)) ||
                    (nextTextColor != null && !nextTextColor.equals(lastTextColor))) {
                return false;
            }

            // 字体不同，且不存在由括号组成的连续文本，则不能合并
            if (lastText.getMostCommonTextStyle()!= nextText.getMostCommonTextStyle() &&
                    !TEXE_CONTINUE_LEFT.matcher(lastText.getText()).matches() &&
                    !TEXE_CONTINUE_RIGHT.matcher(nextText.getText()).matches()) {
                return false;
            }

            // 长度相同，内容相同的两行文本，不合并
            if (prevBlock.getElements().size() == 1 && Objects.equals(lastText.getText().trim(), nextText.getText().trim())) {
                return false;
            }

            // 如果都是粗体，可能是表头或标题，如果两个文本块存在左对齐，则不合并
            if (lastText.getMostCommonTextStyle() == TextElement.TEXT_STYLE_BOLD &&
                    nextText.getMostCommonTextStyle() == TextElement.TEXT_STYLE_BOLD &&
                    FloatUtils.feq(lastText.getVisibleMinX(), nextText.getVisibleMinX(), 0.5f) &&
                    prevBlock.getElements().size() == 1) {
                // 如果都是英文，且都是大写开头
                String lastStr = lastText.getText().trim();
                String nextStr = nextText.getText().trim();
                if (!TextUtils.containsCJK(lastStr) && !TextUtils.containsCJK(nextStr) &&
                        Character.isUpperCase(lastStr.charAt(0)) && Character.isUpperCase(nextStr.charAt(0)))
                return false;
            }

            // 如果字样式相同，字号差距大，不能合并
            if (StringUtils.equals(lastText.getMostCommonFontName(), nextText.getMostCommonFontName()) &&
                    !FloatUtils.feq(lastText.getMostCommonFontSize(), nextText.getMostCommonFontSize(), 0.5)) {
                return false;
            }

            // 正则要求
            if (TEXT_CHUNK_END.matcher(lastText.getText()).find() ||
                    (TEXT_SECTION_PREFIX.matcher(nextText.getText()).find() && !isNumberLine(nextText.getText()))) {
                // 补充条件 - 对英文单词缩写以.结尾的文本例外
                if (TextUtils.containsCJK(lastText.getText()) || lastText.getElements().size() > 6) {
                    return false;
                }
            }

            // 判断是否两文本之间有ruling分隔，如果有则不能合并
            for (Ruling r : horizontalRulingLines) {
                if ((r.isHorizontallyOverlap(lastText) && r.isHorizontallyOverlap(nextText)) &&
                        ((lastText.getCenterY() < r.getPosition() && nextText.getCenterY() > r.getPosition()) ||
                                (lastText.getCenterY() > r.getPosition() && nextText.getCenterY() < r.getPosition()))) {
                    return false;
                }
            }

            // 字体颜色的不同,来过滤掉一部分英文标题
            if (lastText.getLastElement().getColor().getRGB() != nextText.getFirstElement().getColor().getRGB()
                    && prevBlock.getElements().size() == 1 && lastText.getFontSize() != nextText.getFontSize()) {
                return false;
            }

            // 判断正文
            if (isTextRow(lastText, nextText, textVaildRect, searchHeight)) {
                return false;
            }

            // 针对特殊模式分析处理
            // 1.对数值和百分数文本合并处理
            if (NUM_GROUP_PREFIX.matcher(lastText.getText()).matches() && NUM_GROUP_PREFIX.matcher(nextText.getText()).matches()) {
                if (!canMergeNumberGroup(lastText, nextText)) {
                    return false;
                }
            }

            // 2.对两行疑是日期的数字文本，不能合并
            if (TEXT_DATE_FORMAT.matcher(lastText.getText().trim()).matches() && TEXT_DATE_FORMAT.matcher(nextText.getText().trim()).matches()) {
                return false;
            }

            // 3.对缩进带有-前缀的段落文本，不合并
            // 如： xxxxxxxxxxxxx
            //       -xxxxxx
            //       -xxxxxx
            boolean lastSpecial = lastText.getText().trim().startsWith("–");
            boolean nextSpecial = nextText.getText().trim().startsWith("–");
            if (nextSpecial || (lastSpecial && lastText.getVisibleMinX() > nextText.getVisibleMinX())) {
                return false;
            }

            // 3.对两行都以%结尾的文本，不能合并
            if (lastText.getText().trim().endsWith("%") && nextText.getText().trim().endsWith("%")) {
                return false;
            }

            // 结合同文本行的chunk来分析
            Rectangle prevExtraRect = new Rectangle(prevBlock.getRight() + 2, prevBlock.getTop(), prevBlock.getWidth()*5, prevBlock.getHeight());
            Rectangle nextExtraRect = new Rectangle(nextText.getRight() + 2, nextText.getTop(), nextText.getWidth()*5, nextText.getHeight());
            float maxExtraRight = Math.min(Math.max(prevExtraRect.getRight(), nextExtraRect.getRight()), textVaildRect.getRight());
            prevExtraRect.setRight(maxExtraRight);
            nextExtraRect.setRight(maxExtraRight);
            List<TextChunk> prevExtraChunks = Page.getTextChunksFromIndex(chunkSpatialIndex, prevExtraRect, false);
            List<TextChunk> nextExtraChunks = Page.getTextChunksFromIndex(chunkSpatialIndex, nextExtraRect, false);
            if (!prevExtraChunks.isEmpty() && !nextExtraChunks.isEmpty() &&
                    (prevExtraChunks.size() != nextExtraChunks.size() || CollectionUtils.intersection(prevExtraChunks, nextExtraChunks).size() != nextExtraChunks.size())) {
                // 当前文本非数值类，且两行附近的扩展区域文本块都为多个数值文本，则很可能是独立表头，不合并
                if (!TEXT_NUMBER_CONTENT.matcher(lastText.getText()).matches() &&
                        !TEXT_NUMBER_CONTENT.matcher(nextText.getText()).matches() &&
                        prevExtraChunks.stream().allMatch(t->NUM_GROUP_PREFIX.matcher(t.getText()).matches()) &&
                        nextExtraChunks.stream().allMatch(t->NUM_GROUP_PREFIX.matcher(t.getText()).matches())) {
                    return false;
                }
            }

            return true;
        } else {
            // 如果在同一行，则分析是否满足搜索合并区域
            boolean canMerge = true;
            canMerge = canMergeChunk(lastText, nextText, chunkSpatialIndex, horizontalRulingLines, verticalRulingLines);
            return canMerge;
        }
    }

    // 判断上下两个换行的数值Chunk是否能合并
    // 如:  numberA
    //      numberB
    public static boolean canMergeNumberGroup(TextChunk lastText, TextChunk nextText) {
        // 其中一个是百分数，则不可能是合并数值
        if (TEXT_PERCENTAGE.matcher(lastText.getText()).matches() || TEXT_PERCENTAGE.matcher(nextText.getText()).matches()) {
            return false;
        }

        // 至少有一个存在括号包含，则不可能是合并数值
        if (BRACKET_TEXT_PREFIX.matcher(lastText.getText()).matches() || BRACKET_TEXT_PREFIX.matcher(nextText.getText()).matches()) {
            return false;
        }

        // 正常数值最多只有一个小数点
        String mergeText = lastText.getText().trim() + nextText.getText().trim();
        if (countSpecificString(mergeText, "\\.") > 1) {
            return false;
        }

        // 两个都是纯数字
        if (TEXT_NUMBER.matcher(lastText.getText()).matches() && TEXT_NUMBER.matcher(nextText.getText()).matches()) {
            String[] lastSplit = lastText.getText().trim().split(",");
            String[] nextSplit = nextText.getText().trim().split(",");
            // 没有千分位符，则不可能是长数值换行
            if (lastSplit.length == 1 && nextSplit.length == 1) {
                return false;
            }

            // 存在千分位符，则两个数值的合并处，是否满足千分位的要求，即三位数
            if ((lastSplit[lastSplit.length - 1] + nextSplit[0]).length() != 3) {
                return false;
            }

            // 存在千分位符，但前数值比换行后数值短，也不合理
            if (lastText.getText().trim().length() < nextText.getText().trim().length()) {
                return false;
            }
        }
        return true;
    }

    private static List<TextChunk> preProcessChunkMerge(List<TextChunk> textObjects,
                                                        List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        TextChunk prevText = null;
        List<TextChunk> confuseChunk = new ArrayList<>();
        List<TextChunk> preProcessChunks = new ArrayList<>();

        for (TextChunk textObject : textObjects) {
            // 去除被标记为deleted的element
            TextChunk text = textObject.getActualChunk();
            if (text.isEmpty()) {
                continue;
            }

            // 对旋转但不清楚旋转方向和角度的chunk，单独保存处理
            if (text.getDirection() == TextDirection.ROTATED) {
                if (StringUtils.isNotBlank(text.getText())) {
                    confuseChunk.add(text);
                }
                continue;
            }

            if (StringUtils.isBlank(text.getText())) {
                if (prevText != null) {
                    if (isSameRow(text, prevText)) {
                        if (text.getLeft() - prevText.getRight() > text.getWidth() * 2) {
                            prevText.addTag(MERGER_TYPE, ChunkMergerType.BLOCK_END);
                        }
                    } else {
                        if (prevText.horizontallyOverlapRatio(text) > 0.8 &&
                                text.getElements().size() > prevText.getElements().size()) {
                            prevText.addTag(MERGER_TYPE, ChunkMergerType.BLOCK_END);
                        }
                    }
                }
                continue;
            }

            //  ======= 对文本做空格间隔拆分 =======
            List<TextChunk> squeezeChunks;
            // 尝试分析文本块是否为多个重复内容，如果是则直接切分
            List<TextChunk> sameChunks = squeeze(text, ' ', 1);
            boolean isSameChunk = true;
            if (sameChunks.size() < 2) {
                isSameChunk = false;
            } else {
                for (int i = 0; i < sameChunks.size()-1; i++) {
                    TextChunk currChunk = sameChunks.get(i);
                    TextChunk nextChunk = sameChunks.get(i+1);
                    currChunk.trim();
                    nextChunk.trim();
                    if (!Objects.equals(currChunk.getText(), nextChunk.getText()) ||
                            currChunk.getElements().size() < 2) {
                        isSameChunk = false;
                        break;
                    }
                }
            }
            if (isSameChunk) {
                for (TextChunk subChunk: sameChunks) {
                    subChunk.addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                }
                squeezeChunks = sameChunks;
            } else {
                //对每个待合并分析的TJ基于空字符间隔压缩拆分
                squeezeChunks = squeeze(text, ' ', 3, verticalRulingLines, true);
            }

            // ======= 对黑名单进行拆分 =======
            // 这是非常恶心但又无可奈何的操作，可是又能怎么办
            List<TextChunk> sickSplitChunks = new ArrayList<>();
            for (TextChunk squeezeChunk : squeezeChunks) {
                if (StringUtils.isBlank(squeezeChunk.getText())) {
                    continue;
                }

                boolean hasHitBlackList = false;
                for (Pattern pattern: TEXT_MERGER_TEMPLATE_BLACKLISTS) {
                    if (pattern.matcher(squeezeChunk.getText().trim()).matches()) {
                        hasHitBlackList = true;
                        break;
                    }
                }
                boolean hasSplit = false;
                if (hasHitBlackList) {
                    String textString = squeezeChunk.getText();
                    int spaceIdx = textString.indexOf(" ");
                    if (spaceIdx > 0 && spaceIdx < textString.length() - 1 && spaceIdx < squeezeChunk.getElements().size()) {
                        TextChunk[] t = splitAt(squeezeChunk, spaceIdx);
                        t[0].addTag(MERGER_TYPE, ChunkMergerType.POTENTIAL_CHUNK_END);
                        sickSplitChunks.add(t[0]);
                        t[1].trim();
                        sickSplitChunks.add(t[1]);
                        hasSplit = true;
                    }
                }
                if (!hasSplit) {
                    sickSplitChunks.add(squeezeChunk);
                }
            }

            // ======= 对文本做基于段落序号点（"•"）拆分 =======
            List<TextChunk> dotSplitChunks = new ArrayList<>();
            for (TextChunk sickSplitChunk : sickSplitChunks) {
                if (StringUtils.isBlank(sickSplitChunk.getText())) {
                    continue;
                }

                boolean isDotSplit = false;
                if (!TextUtils.containsCJK(sickSplitChunk.getText()) && TEXT_SERIAL_DOT.matcher(sickSplitChunk.getText()).matches()) {
                    // 文本中含有且仅含有一个段落序号点（"•"），则先进行拆分，后续根据文本合并规则做合并
                    // 针对类似样本:GIC - 2016 0630 TPG Asia VI, L.P - FS InvstSch PCAP (96571870).pdf,page 22等
                    // 仅对英文处理分割
                    String tempText = sickSplitChunk.getText();
                    int dotIdx = tempText.indexOf("•");
                    if (dotIdx > 0 && dotIdx < sickSplitChunk.getElements().size()) {
                        TextChunk[] tempChunks = splitAt(sickSplitChunk, dotIdx);
                        dotSplitChunks.addAll(Arrays.asList(tempChunks));
                        isDotSplit = true;
                    }
                }
                if (!isDotSplit){
                    dotSplitChunks.add(sickSplitChunk);
                }
            }

            // ======= 对类似"******* . . . . . .  ****"文本，做切分处理，并标记CHUNK_END =======
            List<TextChunk> dotLineChunks = new ArrayList<>();
            for (TextChunk dotSplitChunk: dotSplitChunks) {
                if (StringUtils.isBlank(dotSplitChunk.getText())) {
                    continue;
                }
                dotLineChunks.addAll(squeezeDotLineText(dotSplitChunk));
            }


            // ======= 对文本做基于数值拆分 =======
            List<TextChunk> numSplitChunks = new ArrayList<>();
            for (TextChunk dotLineChunk: dotLineChunks) {
                if (StringUtils.isBlank(dotLineChunk.getText())) {
                    continue;
                }

                //清除每个chunk的前后空白符
                dotLineChunk.trim();
                numSplitChunks.addAll(squeezeNumberText(dotLineChunk));
            }

            // ======= 对文本做基于ruling拆分 =======
            List<TextChunk> rulingSplitChunks = new ArrayList<>();
            for (TextChunk numSplitChunk: numSplitChunks) {
                if (StringUtils.isBlank(numSplitChunk.getText())) {
                    continue;
                }

                rulingSplitChunks.addAll(squeezeUnderlineText(numSplitChunk, horizontalRulingLines));
            }

            // 汇总经过切分后的chunks
            if (!rulingSplitChunks.isEmpty()) {
                preProcessChunks.addAll(rulingSplitChunks);
                prevText = rulingSplitChunks.get(rulingSplitChunks.size() - 1);
            }
        }

        // 对方向为ROTATED的不确定文本块，如果数量较少，则认为是水印，否则加入到输出文本块结果中
        if (confuseChunk.size() > 10) {
            preProcessChunks.addAll(confuseChunk);
        }
        return preProcessChunks;
    }

    /**
     * 根据原始PDF的TJ
     * 合并在一块的相邻的文字
     */
    public static List<TextChunk> groupNeighbour(List<TextChunk> textObjects) {
        return groupNeighbour(textObjects, true);
    }

    static List<TextChunk> groupNeighbour(List<TextChunk> textObjects, boolean usePreProcess) {
        return groupNeighbour(textObjects, new ArrayList<>(), new ArrayList<>(), usePreProcess);
    }

    static List<TextChunk> groupNeighbour(List<TextChunk> textObjects,
                                          List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines, boolean usePreProcess) {
        List<TextChunk> merged = new ArrayList<>();
        TextChunk prevText = null;
        List<TextChunk> confuseChunk = new ArrayList<>();

        List<TextChunk> srcTextChunks;
        if (usePreProcess) {
            srcTextChunks = preProcessChunkMerge(textObjects, horizontalRulingLines, verticalRulingLines);
        } else {
            srcTextChunks = textObjects;
        }

        RectangleSpatialIndex<TextChunk> chunkSpatialIndex = Page.buildSpatialIndex(srcTextChunks);
        //对每个TC做合并分析
        for (TextChunk currChunk : srcTextChunks) {
            if (StringUtils.isBlank(currChunk.getText())) {
                continue;
            }

            // 对旋转但不清楚旋转方向和角度的chunk，单独保存处理
            if (currChunk.getDirection() == TextDirection.ROTATED) {
                if (StringUtils.isNotBlank(currChunk.getText())) {
                    confuseChunk.add(currChunk);
                }
                continue;
            }

            if (prevText != null && canMergeChunk(prevText, currChunk, chunkSpatialIndex, horizontalRulingLines, verticalRulingLines)) {
                prevText.merge(currChunk);
                if (currChunk.hasTag(MERGER_TYPE) && currChunk.getTag(MERGER_TYPE) == ChunkMergerType.CHUNK_END) {
                    prevText.addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                }
            } else {
                prevText = currChunk;
                merged.add(currChunk);
            }

        }

        for (TextChunk textChunk : merged) {
            if (textChunk.getDirection() == TextDirection.UNKNOWN) {
                textChunk.setDirection(TextDirection.LTR);
            }
        }
        // 对方向为ROTATED的不确定文本块，如果数量较少，则认为是水印，否则加入到输出文本块结果中
        if (confuseChunk.size() > 10) {
            merged.addAll(confuseChunk);
        }
        return merged;
    }

    /**
     * 将连续相邻的文本字符合并成一个文本块
     */
    public static List<TextChunk> groupByChunks(List<TextElement> textElements) {
        return groupByChunks(textElements, new ArrayList<>(), new ArrayList<>());
    }

    public static List<TextChunk> groupByChunks(List<TextElement> textElements, List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        List<TextChunk> t = textElements.stream().map(TextChunk::new).collect(Collectors.toList());
        return groupNeighbour(t, horizontalRulingLines, verticalRulingLines, false);
    }

    /**
     * 根据原始PDF的TJ，或者初步合并后的单独文本块，合并成满足连续文本的文本块
     * 如：实际连续的多行独立单元格文本块
     */
    public static List<TextBlock> groupByBlock(List<TextChunk> textChunks) {
        return groupByBlock(textChunks, new ArrayList<>(), new ArrayList<>());
    }

    public static List<TextBlock> groupByBlock(List<TextChunk> textChunks, List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        List<TextBlock> merged = new ArrayList<>();
        TextBlock prevBlock = null;
        Rectangle textValidRect = getTextValidRect(textChunks);
        RectangleSpatialIndex<TextChunk> chunkSpatialIndex = Page.buildSpatialIndex(textChunks);
        for (int i=0; i<textChunks.size(); i++) {
            TextChunk currText = textChunks.get(i);
            if (currText.isDeleted()) {
                continue;
            }

            if (prevBlock != null && i < textChunks.size() - 1) {
                TextChunk lastText = prevBlock.getLastTextChunk();
                TextChunk nextText = textChunks.get(i+1);

                //上一个文本块与下一个文本块有垂直重合，且当前Chunk与下一个Chunk在一行并且不能合并，则当前Chunk也不能被上一个Chunk合并
                // 针对如下情况：
                //  [----- lastChunk -----]
                // [currChunk]   [nextChunk]
                if (lastText.horizontalOverlap(nextText) > nextText.getWidthOfSpace() && isSameRow(currText, nextText) &&
                        !canMergeChunk(currText, nextText, chunkSpatialIndex, horizontalRulingLines,verticalRulingLines)) {
                    lastText.addTag(MERGER_TYPE, ChunkMergerType.BLOCK_END);
                }

                // 次级表头下，存在对齐的子级表头文本; 或者连续几行都对齐的文本
                // 针对如下情况:                            或如下情况：
                // [ ---- lastChunk ---- ]                [ --- lastChunk --- ]
                //      [--- currChunk ---]               [ --- currChunk --- ]
                //      [--- nextChunk ---]               [ --- nextChunk --- ]
                if (!isSameRow(lastText, currText) && !isSameRow(currText, nextText) &&
                        lastText.getCenterY() < currText.getCenterY() && currText.getCenterY() < nextText.getCenterY() &&
                        lastText.horizontalOverlap(currText) > 0) {
                    if (FloatUtils.feq(currText.getVisibleMinX(), nextText.getVisibleMinX(), 0.5f)) {
                        if ((FloatUtils.feq(lastText.getVisibleMinX(), currText.getVisibleMinX(), 0.5f) &&
                                FloatUtils.feq(lastText.getVisibleMinX(), nextText.getVisibleMinX(), 0.5f)) ||
                                currText.getVisibleMinX() - lastText.getVisibleMinX() > 3*lastText.getAvgCharWidth()) {
                            lastText.addTag(MERGER_TYPE, ChunkMergerType.BLOCK_END);
                            currText.addTag(MERGER_TYPE, ChunkMergerType.BLOCK_END);
                        }
                    }
                }
            }

            if (prevBlock != null && canMergeBlock(prevBlock, currText, chunkSpatialIndex, horizontalRulingLines, verticalRulingLines, textValidRect)) {
                prevBlock.addElement(currText);
            } else if (StringUtils.isNotBlank(currText.getText())) {
                prevBlock = new TextBlock(currText);
                merged.add(prevBlock);
            }
        }
        return merged;
    }

    /** Splits a TextChunk in two, at the position of the i-th TextElement
     */
    private static TextChunk[] splitAt(TextChunk textChunk, int i) {
        if (i < 1 || i >= textChunk.getElements().size()) {
            throw new IllegalArgumentException();
        }

        TextChunk[] chunks = new TextChunk[] {
                new TextChunk(textChunk.getElements().subList(0, i), false),
                new TextChunk(textChunk.getElements().subList(i, textChunk.getElements().size()), false)
        };
        chunks[0].copyAttribute(textChunk);
        chunks[1].copyAttribute(textChunk);

        return chunks;
    }

    public static float computeSpace(TextElement lastElement, TextElement currElement) {
        float widthOfSpace = (lastElement.getWidthOfSpace() + currElement.getWidthOfSpace()) / 2;
        double distanceX = Math.abs(lastElement.getCenterX() - currElement.getCenterX())
                - ((lastElement.getWidth() - currElement.getWidth()) / 2);
        double distanceY = Math.abs(lastElement.getCenterY() - currElement.getCenterY())
                - ((lastElement.getHeight() + currElement.getHeight()) / 2);
        double distance = Math.max(distanceX, distanceY);
        if (distance < 0) {
            return 0;
        }
        return (float) distance / widthOfSpace;
    }

    /**
     * Removes runs of identical TextElements in this TextChunk
     * For example, if the TextChunk contains this string of characters: "1234xxxxx56xx"
     * and c == 'x' and minRunLength == 4, this method will return a list of TextChunk
     * such that: ["1234", "56xx"]
     */
    public static List<TextChunk> squeeze(TextChunk textChunk, Character c, int minRunLength) {
        return squeeze(textChunk, c, minRunLength, new ArrayList<>(), false);
    }

    private static List<TextChunk> squeeze(TextChunk textChunk, Character c, int minRunLength, List<Ruling> verticalRulingLines, boolean useExtend) {
        Character currentChar, lastChar = null;
        TextElement currentElement, lastElement = null;
        int subSequenceLength = 0, subSequenceStart = 0, countLength = 0;
        TextChunk[] t;
        List<TextChunk> rv = new ArrayList<>();
        boolean hasContainsCJK = TextUtils.containsCJK(textChunk.getText());
        float commonIntervalThresh = textChunk.getMostCommonElementInterval() * 3;
        float extremeIntervalThresh = commonIntervalThresh * 3;

        for (int i = 0; i < textChunk.getElements().size(); i++) {
            currentElement = textChunk.getElements().get(i);
            currentChar = currentElement.getText().charAt(0);
            if (currentElement.isWhitespace() && currentElement.isMocked()) {
                if (countLength > 0) {
                    subSequenceLength++;
                    countLength += currentElement.getUnicode().length();
                }
                continue;
            }

            // 使用ruling辅助拆分
            boolean splitVerticalRuling = false;
            boolean acrossVerticalRuling = false;
            // 根据字符间距辅助拆分 -- 目前仅对中文处理
            boolean hasLongDistance = false;
            if (useExtend) {
                // 使用ruling辅助拆分
                for (Ruling r : verticalRulingLines) {
                    if (r.getHeight() < currentElement.getHeight()*2 ||
                            (lastElement != null && r.getHeight() < lastElement.getHeight()*2)) {
                        continue;
                    }

                    if (lastElement != null && (r.verticalOverlap(currentElement) > currentElement.getHeight()*0.5 &&
                            r.verticalOverlap(lastElement) > lastElement.getHeight()*0.5) &&
                            ((currentElement.getCenterX() < r.getPosition() && lastElement.getCenterX() > r.getPosition()) ||
                                    (currentElement.getCenterX() > r.getPosition() && lastElement.getCenterX() < r.getPosition()))) {
                        splitVerticalRuling = true;
                        break;
                    } else if (r.intersects(currentElement) && StringUtils.isBlank(currentElement.getText())) {
                        acrossVerticalRuling = true;
                        break;
                    }
                }

                // 根据字符间距辅助拆分 -- 目前仅对中文处理
                if (hasContainsCJK && lastElement != null && isSameRow(lastElement, currentElement)) {
                    float distanceOfSpace = computeSpace(lastElement, currentElement);
                    if ((distanceOfSpace > 1.1f && currentElement.getLeft() - lastElement.getRight() > commonIntervalThresh) ||
                            currentElement.getLeft() - lastElement.getRight() > extremeIntervalThresh) {
                        hasLongDistance = true;
                    }
                }
            }

            if (lastChar != null && currentChar.equals(c) && lastChar.equals(currentChar) &&
                    !splitVerticalRuling && !acrossVerticalRuling) {
                subSequenceLength++;
                countLength += currentElement.getUnicode().length();
            }
            else {
                if ((((lastChar != null && !lastChar.equals(currentChar)) || i + 1 == textChunk.getElements().size()) &&
                        countLength >= minRunLength) || splitVerticalRuling || acrossVerticalRuling || hasLongDistance) {

                    if (splitVerticalRuling || hasLongDistance) {
                        t = splitAt(textChunk, i);
                        if (splitVerticalRuling) {
                            t[0].addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                        } else {
                            t[0].addTag(MERGER_TYPE, ChunkMergerType.POTENTIAL_CHUNK_END);
                        }
                        rv.add(t[0]);
                    } else if (acrossVerticalRuling) {
                        if (i == 0) {
                            t = splitAt(textChunk, i+1);
                        } else {
                            t = splitAt(textChunk, i);
                            t[0].addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                            rv.add(t[0]);
                        }
                    } else {
                        if (subSequenceStart == 0 && subSequenceLength <= textChunk.getElements().size() - 1) {
                            t = splitAt(textChunk, subSequenceLength);
                        }
                        else {
                            t = splitAt(textChunk, subSequenceStart);
                            t[0].addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                            rv.add(t[0]);
                        }
                    }
                    if (StringUtils.isNotBlank(t[1].getText())) {
                        rv.addAll(squeeze(t[1], c, minRunLength, verticalRulingLines, useExtend)); // Lo and behold, recursion.
                    }
                    break;

                }
                subSequenceLength = 1;
                subSequenceStart = i;
                if (currentChar.equals(c)) {
                    countLength = currentElement.getUnicode().length();
                } else {
                    countLength = 0;
                }
            }

            lastChar = currentChar;
            lastElement = currentElement;
        }

        if (rv.isEmpty()) { // no splits occurred, hence this.squeeze() == [this]
            if (subSequenceStart >= 1 && subSequenceStart < textChunk.getElements().size() &&
                    countLength >= minRunLength && subSequenceLength < textChunk.getElements().size()) {
                TextChunk[] chunks = splitAt(textChunk, subSequenceStart);
                rv.add(chunks[0]);
            }
            else {
                rv.add(new TextChunk(textChunk));
            }
        }

        return rv;
    }

    private static List<TextChunk> squeezeUnderlineText(TextChunk textChunk, List<Ruling> horizontalRulingLines) {
        List<TextChunk> rv = new ArrayList<>();

        // 查找文本块下是否有ruling下划线
        float extraHeight = (float) textChunk.getHeight() * 0.5f;
        List<Ruling> chunkRuling = Ruling.getRulingsFromArea(horizontalRulingLines,
                new Rectangle2D.Float(textChunk.getLeft(), textChunk.getTop() + extraHeight, (float) textChunk.getWidth(),
                        (float) textChunk.getHeight()));
        boolean hasSplit = false;
        if (chunkRuling.size() > 1) {
            // 存在多下划线，则根据不同的线切分当前chunk
            chunkRuling.sort(Comparator.comparing(Ruling::getLeft));
            Ruling firstRuling = chunkRuling.get(0);
            Ruling secondRuling = chunkRuling.get(1);
            int secondRulingIdx = 1;
            while (secondRuling.getLeft() - firstRuling.getRight() < 1 && secondRulingIdx < chunkRuling.size() - 1) {
                secondRulingIdx += 1;
                secondRuling = chunkRuling.get(secondRulingIdx);
            }
            float centreX = (firstRuling.getRight() + secondRuling.getLeft()) * 0.5f;
            List<TextElement> elements = textChunk.getElements();
            int splitIdx = 0;
            TextElement currElement = elements.get(0);
            for (int i=1; i<elements.size(); i++) {
                TextElement nextElement = elements.get(i);
                if (currElement.getCenterX() < centreX && nextElement.getCenterX() > centreX) {
                    splitIdx = i;
                    break;
                }
                currElement = nextElement;
            }
            if (splitIdx > 0 && splitIdx < elements.size() - 1) {
                TextChunk[] splitChunks = splitAt(textChunk, splitIdx);
                splitChunks[0].trim();
                splitChunks[1].trim();
                splitChunks[0].addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                rv.add(splitChunks[0]);
                rv.addAll(squeezeUnderlineText(splitChunks[1], horizontalRulingLines));
                hasSplit = true;
            }
        }
        if (!hasSplit){
            // 没有下划线切分，则返回原chunk
            rv.add(textChunk);
        }

        return rv;
    }

    private static List<TextChunk> squeezeDotLineText(TextChunk textChunk) {
        List<TextChunk> rv = new ArrayList<>();

        Matcher matcher = TEXT_UNDER_DOTS.matcher(textChunk.getText());
        boolean hasSqueeze = false;
        if (matcher.find()) {
            String[] splitString = TEXT_UNDER_DOTS.split(textChunk.getText());
            int startIdx = matcher.start();
            int endIdx = matcher.end();
            boolean needSplit = false;

            if (splitString.length == 0) {
                // 完全匹配，则不切分，只标记CHUNK_END
                textChunk.addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                rv.add(textChunk);
                hasSqueeze = true;
            } else if (splitString.length == 1) {
                // ......... xxxxx 或者
                // 只处理 ......... xxxxx这种情况
                if (startIdx == 0 && endIdx < textChunk.getElements().size()) {
                    needSplit = true;
                }
                // xxxxx.......... 情况下，不切分，只标记CHUNK_END
                if (startIdx > 0 && endIdx == textChunk.getElements().size()) {
                    textChunk.addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                    rv.add(textChunk);
                    hasSqueeze = true;
                }
            } else if (splitString.length == 2) {
                // xxxxxx ........... xxxxxx
                if (endIdx > 0 && endIdx < textChunk.getElements().size()) {
                    needSplit = true;
                }
            }
            if (needSplit) {
                TextChunk[] tempChunks = splitAt(textChunk, endIdx);
                tempChunks[0].addTag(MERGER_TYPE, ChunkMergerType.CHUNK_END);
                rv.addAll(Arrays.asList(tempChunks));
                hasSqueeze = true;
            }
        }

        if (!hasSqueeze) {
            rv.add(textChunk);
        }
        return rv;
    }

    private static List<TextChunk> squeezeNumberText(TextChunk textChunk) {
        List<TextChunk> rv = new ArrayList<>();

        String text = textChunk.getText();
        int spaceIdx = text.indexOf(" ");
        if (spaceIdx > 0 && spaceIdx < text.length() - 1 && spaceIdx < textChunk.getElements().size() &&
                isNumberGroup(textChunk.getText()) ) {
            TextChunk[] t = splitAt(textChunk, spaceIdx);
            t[0].addTag(MERGER_TYPE, ChunkMergerType.POTENTIAL_CHUNK_END);
            rv.add(t[0]);
            t[1].trim();
            rv.addAll(squeezeNumberText(t[1]));
        } else {
            rv.add(textChunk);
        }
        return rv;
    }

    public static boolean isSameChar(TextChunk textChunk, Character c) {
        return isSameChar(textChunk, new Character[] { c });
    }

    public static boolean isSameChar(TextChunk textChunk, Character[] c) {
        String s = textChunk.getText();
        List<Character> chars = Arrays.asList(c);
        for (int i = 0; i < s.length(); i++) {
            if (!chars.contains(s.charAt(i))) { return false; }
        }
        return true;
    }

    public static boolean allSameChar(List<TextChunk> textChunks) {
        /* the previous, far more elegant version of this method failed when there was an empty TextChunk in textChunks.
         * so I rewrote it in an ugly way. but it works!
         * it would be good for this to get rewritten eventually
         * the purpose is basically just to return true iff there are 2+ TextChunks and they're identical.
         * -Jeremy 5/13/2016
         */

        if(textChunks.size() == 1) {
            return false;
        }

        boolean hasHadAtLeastOneNonEmptyTextChunk = false;
        char first = '\u0000';
        for (TextChunk tc: textChunks) {
            if (tc.getText().length() == 0) {
                continue;
            }
            if (first == '\u0000'){
                first = tc.getText().charAt(0);
            }else{
                hasHadAtLeastOneNonEmptyTextChunk = true;
                if (!isSameChar(tc, first)) {
                    return false;
                }
            }
        }
        return hasHadAtLeastOneNonEmptyTextChunk;
    }

    private static TextBlock removeRepeatedCharacters(TextBlock line, Character c, int minRunLength) {

        TextBlock rv = new TextBlock();

        List<TextChunk> elements = line.getElements();
        elements.sort(Comparator.comparing(TextChunk::getRight));
        for(TextChunk t: elements) {
            TextChunk temp = new TextChunk();
            for (TextChunk r: squeeze(t, c, minRunLength)) {
                temp.merge(r, false);
            }
            rv.addElement(temp);
        }

        return rv;
    }

    /**
     * 合并在一行的文字块
     */
    public static List<TextLine> groupByLines(List<TextChunk> textChunks) {
        List<TextBlock> tb = textChunks.stream().map(TextBlock::new).collect(Collectors.toList());
        return groupByLines(tb, false);
    }

    public static List<TextLine> groupByLines(List<TextBlock> textBlocks, boolean mutable) {
        if (textBlocks.size() == 0) {
            return new ArrayList<>();
        }

        List<TextLine> lines;
        TextLine line;
        boolean isHorizontal;
        double textScope;

        List<TextLine> verticalLines = new ArrayList<>();
        List<TextLine> horizontalLines = new ArrayList<>();

        for (TextBlock tb : textBlocks) {
            if (StringUtils.isBlank(tb.getText())) {
                continue;
            }

            if (tb.getDirection() == TextDirection.VERTICAL_UP || tb.getDirection() == TextDirection.VERTICAL_DOWN) {
                lines = verticalLines;
                textScope = tb.getMaxCharWidth();
                isHorizontal = false;
            } else {
                lines = horizontalLines;
                textScope = tb.getMaxCharHeight();
                isHorizontal = true;
            }

            float maxOverlap = 0;
            int maxIdx = -1;
            for (int j = 0; j < lines.size(); j++) {
                line = lines.get(j);
                float overlap = isHorizontal ?
                        Rectangle.verticalOverlap(line.getBounds2D(), tb.getBounds2D()) :
                        Rectangle.horizontalOverlap(line.getBounds2D(), tb.getBounds2D());
                if (overlap > maxOverlap) {
                    maxOverlap = overlap;
                    maxIdx = j;
                }
            }

            TextBlock addBlock = mutable ? new TextBlock(tb) : tb;
            if (maxIdx == -1 || maxOverlap < textScope*SAME_ROW_OVERLAP) {
                line = new TextLine(addBlock);
                lines.add(line);
            } else {
                line = lines.get(maxIdx);
                line.addElement(addBlock);
            }
        }

        horizontalLines.addAll(verticalLines);
        horizontalLines.sort(Comparator.comparing(Rectangle::getTop));
        return horizontalLines;
    }

    @Deprecated
    public static List<TextBlock> collectByRows(List<TextBlock> textBlocks) {
        List<TextChunk> tc = textBlocks.stream().map(TextChunk::new).collect(Collectors.toList());
        return collectByLines(tc);
    }

    @Deprecated
    public static List<TextBlock> collectByLines(List<TextChunk> textChunks) {
        if (textChunks.size() == 0) {
            return new ArrayList<>();
        }

        List<TextBlock> lines;
        List<Rectangle> lineBounds;
        TextBlock line;
        Rectangle bound;
        boolean isHorizontal;
        double textScope;

        List<TextChunk> horizontalTextChunks = new ArrayList<>();
        List<TextBlock> verticalLines = new ArrayList<>();
        List<Rectangle> verticalLineBounds = new ArrayList<>();
        List<TextBlock> horizontalLines = new ArrayList<>();
        List<Rectangle> horizontalLineBounds = new ArrayList<>();

        for (TextChunk te : textChunks) {
            if (StringUtils.isBlank(te.getText())) {
                continue;
            }

            float maxOverlap = 0;
            int maxIdx = -1;
            if (te.getDirection() == TextDirection.VERTICAL_UP || te.getDirection() == TextDirection.VERTICAL_DOWN) {
                lines = verticalLines;
                lineBounds = verticalLineBounds;
                textScope = te.getMaxTextWidth();
                isHorizontal = false;
            } else {
                lines = horizontalLines;
                lineBounds = horizontalLineBounds;
                textScope = te.getTextHeight();
                isHorizontal = true;
                horizontalTextChunks.add(te);
            }


            for (int j = 0; j < lineBounds.size(); j++) {
                bound = lineBounds.get(j);
                float overlap = isHorizontal ? bound.verticalOverlap(te) : bound.horizontalOverlap(te);
                if (overlap > maxOverlap) {
                    maxOverlap = overlap;
                    maxIdx = j;
                }
            }
            if (maxIdx == -1 || maxOverlap < textScope*SAME_ROW_OVERLAP) {
                line = new TextBlock(te);
                lines.add(line);
                lineBounds.add(new Rectangle(line.getBounds2D()));
            } else {
                line = lines.get(maxIdx);
                line.addElement(te);
                lineBounds.get(maxIdx).setRect(line.getBounds2D());
            }
        }

        /*
        // 对投影区间的重合区间合并 -- 暂时只对水平行做聚类合并
        List<TextBlock> mergeLines = new ArrayList<>();
        List<Rectangle> mergeLineBounds = new ArrayList<>();
        horizontalLines.sort(Comparator.comparing(textBlock -> textBlock.getBounds2D().getMinY()));
        horizontalLineBounds.sort(Comparator.comparing(Rectangle::getTop));
        for (int i=0; i<horizontalLineBounds.size(); i++) {
            TextBlock textLine = horizontalLines.get(i);
            Rectangle rectLine = horizontalLineBounds.get(i);

            boolean isMerge = false;
            for (int j=0; j<mergeLineBounds.size(); j++) {
                TextBlock baseTextLine = mergeLines.get(j);
                Rectangle baseRectLine = mergeLineBounds.get(j);

                if (rectLine.verticalOverlap(baseRectLine) > SAME_ROW_OVERLAP) {
                    baseRectLine.merge(rectLine);
                    baseTextLine.addElements(textLine.getElements());
                    isMerge = true;
                }
            }
            if (!isMerge) {
                mergeLineBounds.add(rectLine);
                mergeLines.add(textLine);
            }
        }
        horizontalLines = mergeLines;
        horizontalLineBounds = mergeLineBounds;
        */

        float bbwidth = Rectangle.boundingBoxOf(horizontalTextChunks).width;
        List<TextBlock> rv = new ArrayList<>(horizontalLines.size());
        for (int i = 0; i < horizontalLines.size();) {
            line = horizontalLines.get(i);
            bound = horizontalLineBounds.get(i);
            if (horizontalLines.size() > 1 && bound.width / bbwidth > 0.9 && allSameChar(line.getElements())) {
                horizontalLines.remove(i);
                horizontalLineBounds.remove(i);
            } else {
                rv.add(removeRepeatedCharacters(line, ' ', 3));
                i++;
            }
        }
        rv.addAll(verticalLines);

        rv.sort(Comparator.comparing(textBlock -> textBlock.getBounds2D().getMinY()));
        return rv;
    }

}
