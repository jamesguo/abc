package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.model.TextBlock;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextElement;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by dhu on 2017/11/23.
 */
public class TextUtils {
    // 匹配文本里项目符号的正则, 如 (一) 1. ●
    private static final Pattern BULLET_START =
            Pattern.compile("^\\s*[?●■◆???√□§]+\\s*(?<text>.*)");
    private static final Pattern BULLET_START2 =
            Pattern.compile("^\\s*[(（]?[\\d一二三四五六七八九十]{1,3}[、 ：）)．]\\s*(?<text>.*)");
    private static final Pattern BULLET_START3 =
            Pattern.compile("^\\s*[(（]?[一二三四五六七八九十]+[.:][^\\d]+(?<text>.*)");
    private static final Pattern BULLET_START4 =
            Pattern.compile("^\\s*\\d{1,3}[.:]\\d{1,3}\\s[\u4E00-\u9FA5]+(?<text>.*)");
    private static final Pattern BULLET_START5 =
            Pattern.compile("^\\s*\\d{1,3}(\\.\\d{1,3})+\\s*(?<text>.*)");
    private static final List<Pattern> BULLET_STARTS = Lists.newArrayList(BULLET_START, BULLET_START2, BULLET_START3, BULLET_START4, BULLET_START5);

    // 匹配文本里的 第X节
    private static final Pattern CATALOG_PART =
            Pattern.compile("^第\\s?[1-9一二三四五六七八九十]{1,3}\\s?[节章]\\s*[\\d\\u4E00-\\u9FA5]+.*");

    // 匹配 选择符号的行，如 " √ 适用 □ 不适用  □ 是 √ 否
    private static final Pattern CHOICE_SYMBOL1 = Pattern.compile("√.[\u4E00-\u9FA5]+.□.[\u4E00-\u9FA5]+.");
    private static final Pattern CHOICE_SYMBOL2 = Pattern.compile("□.[\u4E00-\u9FA5]+.√.[\u4E00-\u9FA5]+.");
    private static final Pattern CHOICE_SYMBOL3 = Pattern.compile("^[是否无]$");
    private static final List<Pattern> CHOICE_SYMBOLS = Lists.newArrayList(CHOICE_SYMBOL1, CHOICE_SYMBOL2, CHOICE_SYMBOL3);

    // 匹配含有标点符号：，。的行
    private static final Pattern COMMA_SYMBOL = Pattern.compile("，|。|,");

    // 匹配含有X年X月X日的行
    private static final Pattern YEAR_SYMBOL1 = Pattern.compile("\\d{4}年\\d{1,2}月\\d{1,2}日");
    private static final Pattern YEAR_SYMBOL2 = Pattern.compile("[\u4E00-\u9FA5]〇[\u4E00-\u9FA5]{2}年[\u4E00-\u9FA5]{1,2}月[\u4E00-\u9FA5]{1,2}日");
    private static final List<Pattern> YEAR_SYMBOLS = Lists.newArrayList(YEAR_SYMBOL1, YEAR_SYMBOL2);

    // 匹配中文字符，英文字母，英文单词，多个数字，英文较长单词
    public static final String SPACE = " ";
    private static final String CATALOG_SYMBOL = ".";
    private static final Pattern CHN_SYMBOL= Pattern.compile("[\u4E00-\u9FA5]");
    private static final Pattern EN_SYMBOL= Pattern.compile("[a-zA-Z]");
    private static final Pattern EN_WORD = Pattern.compile("[a-zA-Z]{2,15}");
    private static final Pattern CHN_NUM_SYMBOL = Pattern.compile("[\u4E00-\u9FA5]|、|《|》|[0-9]");
    private static final Pattern MULTIPLE_NUM = Pattern.compile("^\\s*[(（][0-9]{3,}.*[）)].*");
    private static final Pattern BIG_NUM = Pattern.compile("^[0-9]{1,3},[0-9]{3},[0-9]{3}.*");
    private static final Pattern SERIAL_NUM = Pattern.compile("^[0-9]{3,}[-][0-9]{3,}.*");
    private static final Pattern LONG_ENWORD = Pattern.compile("^[a-zA-Z]{5,}[\u4E00-\u9FA5]{3,}.*");

    // 匹配段落的结束, 一般是句号结束
    private static final Pattern PARAGRAPH_END = Pattern.compile("[.。:：;；]\\s*$");

    // 匹配段落继续的字、符号
    private static final Pattern PARAGRAPH_KEEP = Pattern.compile("[之第、，]\\s*$");

    // 匹配目录行
    private static final Pattern CATALOG = Pattern.compile("([^\\d][.…·]+\\s*(-\\s)?\\d{1,4}(-\\s)?)|(\\s+\\d{1,4})\\s*$");
    private static final Pattern LONG_CATALOG = Pattern.compile("^.+[.]{110,}\\s*\\d+\\s*$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^(Page|第|PAGE)?\\s*\\d+\\s*(页)?$");
    private static final Pattern PAGE_NUMBER2 = Pattern.compile("^[\\d-–/\\s]+$");
    private static final Pattern PARAGRAPH_FOOTER_RE = Pattern.compile("(^(敬请|请|资料来源|行业研究|谨请).+|(^[\\s\\d]+(敬请|请|行业研究|谨请)))");
    private static final Pattern PARAGRAPH_HEADER_RE = Pattern.compile("((\\d+\\s*(年|-|.)\\d+\\s*(月|-|.)(\\d+\\s*(日|-|.))?\\s{2,})" +
            "|(\\s*[^\\s]{2,}(报告|報告|周报|周報|日报|日報|年报|年報|月报|月報|早报|早報|研究|策略|晨会|投资|证券|行业|点评)\\s*)" +
            "|(^\\s*[^\\s]+公司\\s*$))");
    private static final Pattern DIGITAL_BLANK_LABLE = Pattern.compile("((\\d|-|/|[a-zA-Z]|\\.|%)+\\s{3,}(\\d|-|/|[a-zA-Z]|\\.|%)+)+");
    private static final Pattern DIGITAL_BLANK_LABLE2 = Pattern.compile("\\s*((\\d|-|/|[a-zA-Z]|\\.|%)+\\s*(\\d|-|/|[a-zA-Z]|\\.|%)+)+");
    private static final Pattern MULTIPILE_SPACE = Pattern.compile("\\s*[^\\s]+\\s{3,}[^\\s]+\\s*");
    private static final Pattern SPETIAL_TABLE = Pattern.compile("(\\s*[^\\s]+\\s{3,}[指]\\s{3,}[^\\s]+\\s*)" +
            "|(\\s*\\d+\\s{3,}[\u4E00-\u9FA5]+\\s{3,}+[^\\s]+\\s*)");

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\s{3,}");
    private static final Pattern MULTIPLE_SPACES2 = Pattern.compile("([\u4E00-\u9FA5]|[0-9])\\s{1,3}([\u4E00-\u9FA5]|[0-9])");
    private static final Pattern MULTIPLE_SPACES3 = Pattern.compile("([\u4E00-\u9FA5]|[0-9])\\s{3,}([\u4E00-\u9FA5]|[0-9])");

    // 匹配网址URL
    private static final Pattern URL_SYMBOL = Pattern.compile("[a-zA-z]+://[^\\s]*");

    // 匹配列表： 如 “地址：” 等
    private static final Pattern ADDRESS_SYMBOL = Pattern.compile("[\u4E00-\u9FA5]{2,10}：[a-zA-Z0-9\\u4E00-\\u9FA5]+.*");

    // 匹配XX公司
    private static final Pattern COMPANY_SYMBOL1 = Pattern.compile("^[\u4E00-\u9FA5]{2,}.*公司");
    private static final Pattern COMPANY_SYMBOL2 = Pattern.compile("[a-zA-Z]{2,15}.*Co.*Ltd.");
    private static final List<Pattern> COMPANY_SYMBOLS = Lists.newArrayList(COMPANY_SYMBOL1, COMPANY_SYMBOL2);

    // 匹配// XX 公告, XX 报告
    private static final Pattern NOTICE_SYMBOL1 = Pattern.compile("^[\u4E00-\u9FA5]+公告");
    private static final Pattern NOTICE_SYMBOL2 = Pattern.compile("^[0-9]{4}[\u4E00-\u9FA5]{2,}.*报告");
    private static final List<Pattern> NOTICE_SYMBOLS = Lists.newArrayList(NOTICE_SYMBOL1, NOTICE_SYMBOL2);

    // ①、②、③ List 列表
    private static final Pattern LIST_SYMBOL = Pattern.compile("^[\u4E00-\u9FA5]?[①②③④⑤⑥⑦⑧⑨⑩].[\u4E00-\u9FA5]+.*");

    /**
     * 判断是否是项目符号开头
     * @param text
     * @return
     */
    public static boolean isBulletStart(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return BULLET_STARTS.stream().anyMatch(item -> item.matcher(text).find());
    }

    /**
     * 判断是否是XX公告 XX报告 行
     * @param text
     * @return
     */
    public static boolean isNoticeTitle(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return NOTICE_SYMBOLS.stream().anyMatch(item -> item.matcher(text).matches());
    }

    /**
     * 判断是否是目录中的第x节
     * @param text
     * @return
     */
    public static boolean isCatalogPart(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return CATALOG_PART.matcher(text).matches();
    }

    /**
     * 判断是否是为 括号+多个数字
     * @param text
     * @return
     */
    public static boolean isMultipleNum(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return MULTIPLE_NUM.matcher(text).matches();
    }

    /**
     * 判断是否是为 多个数字如：106,674,595.77
     * @param text
     * @return
     */
    public static boolean isBigNum(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return BIG_NUM.matcher(text).matches();
    }

    /**
     * 判断是否是为 序列号数字如：2017-003
     * @param text
     * @return
     */
    public static boolean isSerialNum(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return SERIAL_NUM.matcher(text).matches();
    }

    /**
     * 判断是否是为 较长的英文单词开头如：MEMSIC
     * @param text
     * @return
     */
    public static boolean isLongENWord(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return LONG_ENWORD.matcher(text).matches();
    }

    /**
     * 判断是否为含有选择符号行
     * @param text
     * @return
     */
    public static boolean hasChoiceSymbol(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return CHOICE_SYMBOLS.stream().anyMatch(item -> item.matcher(text).find());
    }

    /**
     * 判断是否为含有列表符号行 ① ② ③ 开始
     * @param text
     * @return
     */
    public static boolean isListStart(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return LIST_SYMBOL.matcher(text).matches();
    }


    /**
     * 判断是否为含有逗号，句号行
     * @param text
     * @return
     */
    public static boolean hasComma(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return COMMA_SYMBOL.matcher(text).find();
    }

    /**
     * 判断是否为年、月、日落款行
     * @param text
     * @return
     */
    public static boolean isYearMonthDay(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return YEAR_SYMBOLS.stream().anyMatch(item -> item.matcher(text).find());
    }

    /**
     * 判断是否为中文字符
     * @param text
     * @return
     */
    public static boolean isCHNSymbol(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return CHN_SYMBOL.matcher(text).matches();
    }

    /**
     * 判断是否为中文单词
     * @param text
     * @return
     */
    public static boolean isENWord(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return EN_WORD.matcher(text).find();
    }

    /**
     * 判断是否含有网址URL
     * @param text
     * @return
     */
    public static boolean hasURL(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return URL_SYMBOL.matcher(text).find();
    }

    /**
     * 判断是否为地址列表行，如"地址："
     * @param text
     * @return
     */
    public static boolean isAddressLine(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return ADDRESS_SYMBOL.matcher(text).matches();
    }

    /**
     * 判断是否为XX公司的标题，如"×××公司"
     * @param text
     * @return
     */
    public static boolean isCompanyTitle(String text){
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return COMPANY_SYMBOLS.stream().anyMatch(item -> item.matcher(text).matches());
    }

    /**
     * 获取项目编号后面出现的第一个非空的文本字符
     * @param textChunk
     * @return
     */
    public static TextElement getBulletTextElement(TextChunk textChunk) {
        String text = textChunk.getText();
        String words = null;
        for (Pattern pattern : BULLET_STARTS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                words = matcher.group("text");
            }
        }
        if (words == null) {
            return textChunk.getFirstElement();
        }
        int index = text.indexOf(words);
        for (TextElement element : textChunk.getElements()) {
            if (index == 0) {
                return element;
            }
            index -= element.getText().length();
        }
        return null;
    }

    /**
     * 判断是否是段落的结束
     * @param text
     * @return
     */
    public static boolean isParagraphEnd(String text) {
        return !StringUtils.isEmpty(text) && PARAGRAPH_END.matcher(text).find();
    }

    /**
     * 判断是否是段落继续
     * @param text
     * @return
     */
    public static boolean isParagraphKeep(String text) {
        return !StringUtils.isEmpty(text) && PARAGRAPH_KEEP.matcher(text).find();
    }

    /**
     * 是否是目录, 例如:
     * @param text
     * @return
     */
    public static boolean isCatalog(String text) {
        return !StringUtils.isEmpty(text) && CATALOG.matcher(text).find();
    }

    /**
     * 是否是超长目录行, 例如:
     * @param text
     * @return
     */
    public static boolean isLongCatalog(String text) {
        return !StringUtils.isEmpty(text) && LONG_CATALOG.matcher(text).matches();
    }

    public static boolean isPageNumber(String text) {
        return PAGE_NUMBER.matcher(text).matches() || PAGE_NUMBER2.matcher(text).matches();
    }

    public static boolean isDigitalAndBlankOrLabel(String text) {
        return DIGITAL_BLANK_LABLE.matcher(text).find();
    }

    public static boolean isDigitalAndBlankOrAlphabet(String text) {
        return DIGITAL_BLANK_LABLE2.matcher(text).find();
    }

    public static boolean isSpetialTable(String text) {
        return SPETIAL_TABLE.matcher(text).matches();
    }

    public static boolean hasMultipleSpace(String text) {
        return MULTIPILE_SPACE.matcher(text).find();
    }

    public static boolean isParagraphFooter(String text) {
        return isPageNumber(text) || PARAGRAPH_FOOTER_RE.matcher(text).find();
    }

    public static boolean isParagraphHeader(String text) {
        return isPageNumber(text) || PARAGRAPH_HEADER_RE.matcher(text).find();
    }

    public static int getNoneBlankTextCount(String text) {
        return text.replaceAll(" ", "").length();
    }

    public static int getNoneBlankChineseTextCount(String text) {
        return text.replaceAll(" ", "").replaceAll("[^\u4E00-\u9FA5]", "").length();
    }

    public static int getColumnCount(String text) {
        int columnCount = 1;
        Matcher matcher = MULTIPLE_SPACES.matcher(text);
        while (matcher.find()) {
            columnCount++;
        }
        return columnCount;
    }

    /**
     * text中的文字间空格数（前后空格不计），连续空格算一个
     * @param text
     * @return
     */
    public static int getSpaceGapCount(String text) {
        int spaceGapCount = 1;
        Matcher matcher = MULTIPLE_SPACES2.matcher(text);
        while (matcher.find()) {
            spaceGapCount++;
        }
        return spaceGapCount;
    }

    /**
     * 计算textChunk里text内容中前面的空格数
     */
    public static int getStartSpaceN(TextChunk textChunk){
        List<TextElement> elements = textChunk.getElements();
        int space_count = 0;
        for(int i=0; i<elements.size(); i++){
            if(!elements.get(i).getText().equals(SPACE)){
                break;
            }
            else {
                space_count++;
            }
        }
        return space_count;
    }

    /**
     * 计算textChunk里text内容的中文字数，包含数字
     */
    public static int getCHNNumber(TextChunk textChunk){
        List<TextElement> elements = textChunk.getElements();
        int CHN_count = 0;
        String element_text;
        for(int i=0; i<elements.size(); i++){
            element_text = elements.get(i).getText();
            if (CHN_NUM_SYMBOL.matcher(element_text).matches()){
                CHN_count++;
            }
        }
        return CHN_count;
    }

    /**
     * 计算textChunk里text内容的目录字符数
     */
    public static int getCatalogNumber(TextChunk textChunk){
        List<TextElement> elements = textChunk.getElements();
        int catalog_count = 0;
        String element_text;
        for(int i=0; i<elements.size(); i++){
            element_text = elements.get(i).getText();
            if (elements.get(i).getText().equals(CATALOG_SYMBOL)){
                catalog_count++;
            }
        }
        return catalog_count;
    }

    /**
     * 取textChunk里text内容的中文字体
     */
    public static String getCHNFont(TextChunk textChunk){
        List<TextElement> elements = textChunk.getElements();
        String element_text;
        for(int i=0; i<elements.size(); i++){
            element_text = elements.get(i).getText();
            if (CHN_SYMBOL.matcher(element_text).matches()){
                return elements.get(i).getFontName();
            }
        }
        return SPACE;
    }

    /**
     * 取textChunk里text内容的英文字体
     */
    public static String getENFont(TextChunk textChunk){
        List<TextElement> elements = textChunk.getElements();
        String element_text;
        for(int i=0; i<elements.size(); i++){
            element_text = elements.get(i).getText();
            if (EN_SYMBOL.matcher(element_text).matches()){
                return elements.get(i).getFontName();
            }
        }
        return SPACE;
    }

    /**
     * 判断textChunk里字体是否为粗体、黑体、Arial Unicode MS
     */
    public static boolean isBoldText(TextChunk textChunk) {
        return textChunk.isBold()
                || StringUtils.equalsAny(textChunk.getMostCommonFontName(), "黑体", "Arial Unicode MS");
    }

    /**
     * 判断text中的文字是否为多个空格分隔开的文字块
     * 首先Match文字间含有3个以上的空格符
     * 计算前后非空格元素的X位置差
     */
    public static boolean hasMultipleSpace(TextChunk textChunk) {
        if (StringUtils.isBlank(textChunk.getText())) {
            return false;
        }

        return MULTIPLE_SPACES3.matcher(textChunk.getText()).find();
    }

    /**
     * 判断textChunk里的内容为表格文字
     */
    public static boolean hasTableText(TextChunk textChunk) {
        List<String> stTypes = textChunk.getStructureTypes();
        if (stTypes == null) {
            return false;
        }
        return stTypes.contains("Table") || stTypes.contains("TD") || stTypes.contains("TR");
    }

}
