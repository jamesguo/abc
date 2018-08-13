package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractedItem;
import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;

import java.awt.geom.RectangularShape;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *
 * Created by chzhong on 17-6-30.
 */
public abstract class OutlineNode implements ExtractedItem {

    private final List<OutlineItem> children;
    protected OutlineItem previousSibling;
    protected OutlineItem nextSibling;
    protected OutlineNode parent;
    protected OutlineItem firstChild;
    protected OutlineItem lastChild;
    protected int level;
    protected int number;

    // 所有可能成为标题的内容
    private static final Pattern CONTENT_LEVEL = Pattern.compile("^(?<prefix>((§\\s?[\\d一二三四五六七八九十]{1,3}\\s?[^.])" +
            "|(第\\s?[\\d一二三四五六七八九十]{1,3}(?!条)\\s?(章|节|部分))" +
            "|(\\d{1,3}\\.\\d{1,3}(\\.\\d{1,3})?)" +
            "|([\\d一二三四五六七八九十]{1,3}(．|、|\\s|(\\.[^\\d]\\s?)))" +
            "|([(（][\\d一二三四五六七八九十]{1,3}[)）])" +
            "|((议案)|(附件)[\\d一二三四五六七八九十]{1,3})))(?<text>.*[\u4E00-\u9FA5]+.*)$");

    // 过滤掉容易误识别的内容
    private static final Pattern SPECIAL_NUM_PREFIX = Pattern.compile("^((\\d{1,3}\\.\\d+(%|[万元]+|倍))|(\\(?\\d{4}年(\\d{1,2}月)?\\)?)|(\\d+%)|(\\d{4})|([①②③④⑤⑥⑦⑧⑨⑩])|([图表]+\\s*\\d+(\\.\\d)*)|图?表?目录|((\\S{2,}\\s{4,}){2,}))");
    // 过滤掉低级别的标题
    private static final Pattern LOW_LEVEL = Pattern.compile("^(([(（]?[\\d一二三四五六七八九十百]+[)）])|([a-zA-Z]\\.)|(第\\s?[\\d一二三四五六七八九十百]+\\s?条)).*[\u4E00-\u9FA5]+.*$");
    // 寻找目录
    private static final Pattern CATALOGUE = Pattern.compile("(^(内容)?目\\s*[录錄]$)|(^优选信息$)");
    // 目录分级内容
    public static final Pattern CATALOGUE_SUFFIX = Pattern.compile("[^\\d][.…_·]+\\s*(((-\\s)?\\d{1,4}(\\s-)?)|(\\s+\\d{1,4}))\\s*$");

    // 第一级标题可能的开头
    private static final Pattern RELIABLE_LEVEL1 = Pattern.compile("(^|\\|\\s?)([●■◆§])|(第\\s?[\\d一二三四五六七八九十]{1,3}\\s?(章|节|部分))(?!”)\\s*");
    private static final Pattern PROBABLY_LEVEL1 = Pattern.compile("(^|\\|\\s?)[一二三四五六七八九十]{1,3}[\\s、．]\\s*");
    private static final Pattern SUSPICIOUS_LEVEL1 = Pattern.compile("(^|\\s|\\|)\\d{1,3}[\\s\\.、]\\s*");
    private static final Pattern SUSPICIOUS1_LEVEL1 = Pattern.compile("(^|\\|\\s?)\\d{1,3}\\s?[\u4E00-\u9FA5]\\s*");
    private static final Pattern SUSPICIOUS2_LEVEL1 = Pattern.compile("(^|\\|\\s?)\\d{1,3}\\.(?!\\d)\\s*");
    private static final Pattern SUSPICIOUS3_LEVEL1 = Pattern.compile("(^|\\|\\s?)\\d{1,3}[、．]\\s*");
    private static final Pattern SPECIAL_LEVEL1 = Pattern.compile("(^|\\|\\s?)第\\s?[\\d一二三四五六七八九十]{1,3}\\s?(章|部分)\\s*");

    // 第二级标题可能的开头
    private static final Pattern RELIABLE_LEVEL2 = Pattern.compile("(^|\\|\\s?)\\d{1,3}\\.\\d{1,3}[^(.\\d)%]\\s*");
    private static final Pattern PROBABLY_LEVEL2 = Pattern.compile("(^|\\|\\s?)[(（][一二三四五六七八九十]{1,3}[)）]\\s*");
    private static final Pattern SUSPICIOUS_LEVEL2 = Pattern.compile("(^|\\|\\s?)[(（]\\d{1,3}[)）]\\s*");
    private static final Pattern SPECIAL1_LEVEL2 = Pattern.compile("(^|\\|\\s?)[一二三四五六七八九十]{1,3}[\\s、]\\s*");
    private static final Pattern SPECIAL2_LEVEL2 = Pattern.compile("(^|\\|\\s?)第\\s?[\\d一二三四五六七八九十]{1,3}\\s?节\\s*");
    private static final List<Pattern> LEVEL2S = Lists.newArrayList(RELIABLE_LEVEL2, PROBABLY_LEVEL2, SUSPICIOUS_LEVEL2, SPECIAL1_LEVEL2);

    // 第三级标题可能的开头
    private static final Pattern RELIABLE_LEVEL3 = Pattern.compile("(^|\\|\\s?)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\s*");

    // 二级标题的第一个子节点一定是一开头
    private static final Pattern LEVEL_TITLE_FIRST_CHILD = Pattern.compile("^(([(（]?[1一])|(\\d{1,2}\\.1)|(\\d{1,2}\\.\\d{1,2}\\.1)|(第一))[^\\d]");

    // 特殊标题不含标点
    private static final Pattern PUNCTUATIONS = Pattern.compile("[。；＝＋=+]|(如下：)");
    // 标题可能中间含空格，和目录不一样
    private static final Pattern TITLE_TEXT_SPACE = Pattern.compile("^\\S+\\s+\\S+$");

    OutlineNode() {
        this.children = new ArrayList<>();
    }

    void clear() {
        children.clear();
        previousSibling = null;
        nextSibling = null;
        parent = null;
        firstChild = null;
        lastChild = null;
    }

    public OutlineItem getPreviousSibling() {
        return previousSibling;
    }

    public int getLevel() {
        return level;
    }

    public OutlineNode getNextSibling() {
        return nextSibling;
    }

    public OutlineNode getParent() {
        return parent;
    }

    void setParent(OutlineNode parent) {
        this.parent = parent;
    }

    public int getChildCount() {
        return children.size();
    }

    public List<OutlineItem> getChildren() {
        return children;
    }

    public OutlineItem getFirstChild() {
        return firstChild;
    }

    public OutlineItem getLastChild() {
        return lastChild;
    }

    abstract String toHTML();

    StringBuilder toHTML(StringBuilder html) {
        html.append("<ul>");
        OutlineItem item = firstChild;
        while (item != null) {
            html.append("<li>");
            html.append(item.toHTML());
            html.append("</li>");
            item = item.nextSibling;
        }
        html.append("</ul>");
        return html;
    }

    void addChild(OutlineItem item) {
        item.parent = this;
        item.previousSibling = this.lastChild;
        item.nextSibling = null;
        item.level = level + 1;
        if (this.lastChild != null) {
            this.lastChild.nextSibling = item;
            item.number = this.lastChild.number + 1;
        } else{
            item.number = 1;
        }
        this.children.add(item);
        if (this.firstChild == null) {
            this.firstChild = item;
        }
        this.lastChild = item;
    }

    OutlineItem find(String text) {
        OutlineItem item = firstChild;
        while (item != null) {
            if (item.match(text)) {
                return item;
            }
            OutlineItem matchedItem = item.find(text);
            if (matchedItem != null) {
                return matchedItem;
            }
            item = item.nextSibling;
        }
        return null;
    }

    OutlineItem find(PDPageXYZDestination dest) {
        OutlineItem item = firstChild;
        while (item != null) {
            if (item.matchDestination(dest)) {
                return item;
            }
            OutlineItem matchedItem = item.find(dest);
            if (matchedItem != null) {
                return matchedItem;
            }
            item = item.nextSibling;
        }
        return null;
    }

    private Paragraph findNearestParagraphBelow(List<Paragraph> paragraphs, OutlineItem item) {
        int pageIndex = item.getDestPageIndex();
        float y = item.getDestPoint().y;
        return paragraphs.stream()
                .filter(paragraph -> paragraph.getPageNumber()-1 == pageIndex && paragraph.getMinY() >= y)
                .sorted(Comparator.comparingDouble(RectangularShape::getMinY))
                .findFirst().orElse(null);
    }

    void assignOutline(List<Paragraph> paragraphs) {
        OutlineItem item = firstChild;
        while (item != null) {
            Paragraph paragraph = findNearestParagraphBelow(paragraphs, item);
            if (paragraph != null && !PUNCTUATIONS.matcher(paragraph.getText()).find()) {
                paragraph.assignOutline(item);
            }
            item.assignOutline(paragraphs);
            item = item.nextSibling;
        }
    }

    // 如果PDF中不存在自带的目录，从正文中抽取目录
    void extractOutline(List<Paragraph> paragraphs, List<Page> pages, List<Chart> charts, List<Table> tables) {
        List<OutlineItem> levelItems = new ArrayList<>();
        List<Paragraph> catalogueLevel = new ArrayList<>();
        if (!paragraphs.isEmpty()) {

            // 对前缀进行一个判断
            StringBuilder prefixes = new StringBuilder();
            int startIdx = -1;
            int endIdx = -1;
            int nonCataloguePara = 0;
            for (int i = 0; i < paragraphs.size(); i++) {
                Paragraph para = paragraphs.get(i);

                if (para.isCovered() || para.isPageHeader() || para.isPageFooter()) {
                    continue;
                }

                OutlineItem item = new OutlineItem(para);
                item.setText(para.getText());
                String paragraphText = para.getText().trim();

                // 把目录抽取出来
                if (CATALOGUE.matcher(paragraphText).find() && startIdx == -1) {
                    startIdx = i;
                    levelItems.add(item);
                    continue;
                }

                if (CATALOGUE_SUFFIX.matcher(item.getText()).find() && nonCataloguePara < 10) {
                    endIdx = i;
                    continue;
                } else {
                    // 从开始进入目录进行计数
                    if (startIdx != -1 && !para.isPageHeader() && !para.isPageFooter()) {
                        nonCataloguePara++;
                    }
                }

                // 匹配字体较大居中的标题
                if (isSpecialTitle(paragraphs, para, pages)) {
                    levelItems.add(item);
                    continue;
                }

                Matcher levelMatcher = CONTENT_LEVEL.matcher(paragraphText);
                if (levelMatcher.find() && !SPECIAL_NUM_PREFIX.matcher(paragraphText).find()
                        && !CATALOGUE_SUFFIX.matcher(item.getText()).find()) {
                    levelItems.add(item);
                    prefixes.append("|").append(levelMatcher.group("prefix"));
                }
            }

            if (startIdx != -1 && endIdx != -1) {
                if (startIdx < endIdx && endIdx < paragraphs.size()) {
                    for (Paragraph para : paragraphs.subList(startIdx+1, endIdx+1)) {
                        if (!para.isPageHeader() && !para.isPageFooter() && !SPECIAL_NUM_PREFIX.matcher(para.getText()).find()) {
                            catalogueLevel.add(para);
                        }
                    }
                }
            }

            analysisLevel(paragraphs, levelItems, catalogueLevel, prefixes.toString(), pages, charts, tables);
        }
    }

    private void analysisLevel(List<Paragraph> paragraphs, List<OutlineItem> levelItems, List<Paragraph> catalogueLevel, String prefixes, List<Page> pages, List<Chart> charts, List<Table> tables) {
        String catalogueSuffix = "((\\s?(?!\\d)[.…·]+\\s*(-\\s)?\\d{1,4}(\\s-)?)|(\\s+\\d{1,4}))\\s*$";
        StringBuilder levelPrefixes = new StringBuilder();
        boolean noPrefixes = false;
        boolean relyOnContext = false;

        Pattern level1 = null;
        List<String> level1List = new ArrayList<>();
        Pattern level2 = null;
        List<String> level2List = new ArrayList<>();
        Pattern level3 = null;
        List<String> level3List = new ArrayList<>();

        // 存在目录情况
        if (!catalogueLevel.isEmpty()) {
            // 分析目录内的级数
            for (Paragraph levelPara : catalogueLevel) {
                String paragraphText = levelPara.getText();
                Matcher levelMatcher = CONTENT_LEVEL.matcher(paragraphText);
                if (levelMatcher.find() ) {
                    levelPrefixes.append("|").append(levelMatcher.group("prefix"));
                }
            }
            String preString = levelPrefixes.toString();

            // 目录分级情况
            float level1Left = .0f;
            float level2Left = .0f;
            float level3Left = .0f;
            for (Paragraph para : catalogueLevel) {
                // 段落合并的情况
                float paraLeft = para.getFontSize() * 2 < para.getHeight()
                        ? para.getTextBlock().getFirstTextChunk().getTextLeft()
                        : para.getLeft();
                String paraText = para.getText().trim();

                if (level1Left == .0f && !FloatUtils.feq(paraLeft, level1Left, 3.0f)) {
                    level1Left = para.getLeft();
                }

                if (level2Left == .0f && paraLeft - level1Left > 3.0f && paraLeft - level1Left < 50.0f) {
                    level2Left = paraLeft;
                }

                if (level3Left == .0f && level2Left != .0f && paraLeft - level2Left > 3.0f && paraLeft - level2Left < 50.0f) {
                    level3Left = paraLeft;
                }

                if (FloatUtils.feq(paraLeft, level1Left, 3)) {
                    level1List.add(paraText.replaceAll(catalogueSuffix, "").replaceAll("\\s", ""));
                } else if (FloatUtils.feq(paraLeft, level2Left, 3)) {
                    level2List.add(paraText.replaceAll(catalogueSuffix, "").replaceAll("\\s", "").trim());
                } else if (FloatUtils.feq(paraLeft, level3Left, 3)) {
                    level3List.add(paraText.replaceAll(catalogueSuffix, "").trim());
                }
            }

            if (!level1List.isEmpty() && !catalogueLevel.isEmpty() && preString.equals("")) {
                noPrefixes = true;
            }

            if (!level2List.isEmpty()) {
                relyOnContext = true;
            }

            // 目录不分级情况
            if (level2List.isEmpty() && !level1List.isEmpty() && !noPrefixes) {
                // 目录中可能不存在二级目录
                if (LEVEL2S.stream().noneMatch(item -> item.matcher(preString).find())) {
                    levelPrefixes = new StringBuilder(prefixes);
                }

                // 分析前缀得到目录的分级
                if (RELIABLE_LEVEL2.matcher(levelPrefixes.toString()).find()) {
                    level2 = RELIABLE_LEVEL2;
                    level3 = RELIABLE_LEVEL3;
                } else {
                    if (RELIABLE_LEVEL1.matcher(levelPrefixes.toString()).find()) {
                        if (SPECIAL_LEVEL1.matcher(levelPrefixes.toString()).find() && SPECIAL2_LEVEL2.matcher(levelPrefixes.toString()).find()) {
                            level1 = SPECIAL_LEVEL1;
                            level2 = SPECIAL2_LEVEL2;
                        } else if (SPECIAL1_LEVEL2.matcher(levelPrefixes.toString()).find()) {
                            level1 = RELIABLE_LEVEL1;
                            level2 = SPECIAL1_LEVEL2;
                        }
                    } else if (PROBABLY_LEVEL2.matcher(levelPrefixes.toString()).find()) {
                        level2 = PROBABLY_LEVEL2;
                    } else if (SUSPICIOUS_LEVEL2.matcher(levelPrefixes.toString()).find()) {
                        level2 = SUSPICIOUS_LEVEL1;
                    }  else {
                        level2 = null;
                    }
                }

                if (level1 == null) {
                    if (RELIABLE_LEVEL1.matcher(levelPrefixes.toString()).find()) {
                        level1 = RELIABLE_LEVEL1;
                    } else if (PROBABLY_LEVEL1.matcher(levelPrefixes.toString()).find()) {
                        level1 = PROBABLY_LEVEL1;
                        if (SUSPICIOUS_LEVEL1.matcher(levelPrefixes.toString()).find() && level2 == null) {
                            level2 = SUSPICIOUS_LEVEL1;
                        }
                    } else if (SUSPICIOUS1_LEVEL1.matcher(levelPrefixes.toString()).find()) {
                        level1 = SUSPICIOUS1_LEVEL1;
                    } else if (SUSPICIOUS2_LEVEL1.matcher(levelPrefixes.toString()).find()) {
                        level1 = SUSPICIOUS2_LEVEL1;
                    } else if (SUSPICIOUS3_LEVEL1.matcher(levelPrefixes.toString()).find()) {
                        level1 = SUSPICIOUS3_LEVEL1;
                    } else {
                        level1 = null;
                    }
                }

                // 目录不分级但是存在两级目录的情况
                if (level1 != null && level2 != null) {
                    level1List.clear();
                    for (Paragraph para : catalogueLevel) {
                        String paraText = para.getText().trim();
                        if (level1.matcher(paraText).find()) {
                            level1List.add(paraText.replaceAll(catalogueSuffix, "").replaceAll("\\s", ""));
                        } else if (level2.matcher(paraText).find()) {
                            level2List.add(paraText.replaceAll(catalogueSuffix, "").replaceAll("\\s", ""));
                        }

                        if (level3 != null && level3.matcher(paraText).find()) {
                            level3List.add(paraText.replaceAll(catalogueSuffix, "").trim());
                        }
                    }
                    // 目录只存在一级目录的情况
                } else if (level2 == null) {
                    level1List.addAll(catalogueLevel.stream()
                            .map(paragraph -> paragraph.getText().replaceAll(catalogueSuffix, "").replaceAll("\\s", ""))
                            .collect(Collectors.toList()));
                }
            }
        }

        // 对没有二级目录的在进行分析
        if (level2 == null && !relyOnContext) {
            // 分析前缀得到目录的分级
            if (RELIABLE_LEVEL2.matcher(prefixes).find()) {
                level2 = RELIABLE_LEVEL2;
                level3 = RELIABLE_LEVEL3;
            } else {
                if (RELIABLE_LEVEL1.matcher(prefixes).find()) {
                    if (SPECIAL_LEVEL1.matcher(prefixes).find() && SPECIAL2_LEVEL2.matcher(prefixes).find()) {
                        level1 = SPECIAL_LEVEL1;
                        level2 = SPECIAL2_LEVEL2;
                    } else if (SPECIAL1_LEVEL2.matcher(prefixes).find()) {
                        level1 = RELIABLE_LEVEL1;
                        level2 = SPECIAL1_LEVEL2;
                    }
                } else if (PROBABLY_LEVEL2.matcher(prefixes).find()) {
                    level2 = PROBABLY_LEVEL2;
                } else if (SUSPICIOUS_LEVEL2.matcher(prefixes).find()) {
                    level2 = SUSPICIOUS_LEVEL1;
                }  else {
                    level2 = null;
                }
            }

            if (level1 == null) {
                if (RELIABLE_LEVEL1.matcher(prefixes).find()) {
                    level1 = RELIABLE_LEVEL1;
                } else if (PROBABLY_LEVEL1.matcher(prefixes).find()) {
                    level1 = PROBABLY_LEVEL1;
                    if (SUSPICIOUS_LEVEL1.matcher(prefixes).find() && level2 == null) {
                        level2 = SUSPICIOUS_LEVEL1;
                    }
                } else if (SUSPICIOUS1_LEVEL1.matcher(prefixes).find()) {
                    level1 = SUSPICIOUS1_LEVEL1;
                } else if (SUSPICIOUS2_LEVEL1.matcher(prefixes).find()) {
                    level1 = SUSPICIOUS2_LEVEL1;
                } else if (SUSPICIOUS3_LEVEL1.matcher(prefixes).find()) {
                    level1 = SUSPICIOUS3_LEVEL1;
                } else {
                    level1 = null;
                }
            }
        }

        // 针对标题可靠性的在判断
        // 对与1.1 XX此类标题可能是某级标题内部标题，对该类标题进行一个判断
        // TODO：判断可靠的二级标题是不是只在一个一级标题区间内
        Matcher level2Match =  RELIABLE_LEVEL2.matcher(prefixes);
        if (level2Match.find() && level1 != null) {
            int level2Start = level2Match.start(0);
            if (level2Start > 1) {
                Matcher level1Match =  level1.matcher(prefixes.substring(0, level2Start - 1));
                if (level1Match.find()) {
                    int level1Strat = level1Match.start(level1Match.groupCount() - 1);
                    if (Math.abs(level1Strat - level2Start) > 200) {
                        if (RELIABLE_LEVEL1.matcher(prefixes).find()) {
                            if (SPECIAL_LEVEL1.matcher(prefixes).find() && SPECIAL2_LEVEL2.matcher(prefixes).find()) {
                                level1 = SPECIAL_LEVEL1;
                                level2 = SPECIAL2_LEVEL2;
                            } else if (SPECIAL1_LEVEL2.matcher(prefixes).find()) {
                                level1 = RELIABLE_LEVEL1;
                                level2 = SPECIAL1_LEVEL2;
                            }
                        } else if (PROBABLY_LEVEL2.matcher(prefixes).find()) {
                            level2 = PROBABLY_LEVEL2;
                        } else if (SUSPICIOUS_LEVEL2.matcher(prefixes).find()) {
                            level2 = SUSPICIOUS_LEVEL1;
                        }  else {
                            level2 = null;
                        }
                    }
                }
            }
        }

        // 不存在目录也不存在目录，没有明显的标题
        if (level1 == null && level1List.isEmpty()) {
            return;
        }

        if (level2List.isEmpty() && !noPrefixes) {
            for (OutlineItem item : levelItems) {
                String itemText = item.getText().trim();
                Paragraph para = item.getParagraph();

                if (level3 != null) {
                    if (level3.matcher(itemText).find() && !level3List.contains(itemText)) {
                        level3List.add(itemText);
                        continue;
                    }
                }

                if (level2 != null) {
                    if (level2.matcher(itemText).find() &&
                            !level2List.contains(itemText.replaceAll("\\s", ""))) {
                        level2List.add(itemText.replaceAll("\\s", ""));
                        continue;
                    }
                }

                if (level1 != null) {
                    if ((level1.matcher(itemText).find() || isSpecialTitle(paragraphs, para, pages)) &&
                            !level1List.contains(itemText.replaceAll("\\s", ""))) {
                        level1List.add(itemText.replaceAll("\\s", ""));
                    }
                }
            }
        }

        // 将特殊目录内的item过滤掉
        filterInvalidItem(levelItems, pages, charts, tables);

        if (noPrefixes || relyOnContext) {
            levelItems = paragraphs.stream()
                    .filter(para -> !para.isCovered() || !para.isPageHeader() || !para.isPageFooter())
                    .map(para -> new OutlineItem(para)).collect(Collectors.toList());

            levelItems.forEach(item -> item.setText(item.getParagraph().getText()));
        }

        if (!level1List.isEmpty()) {
            OutlineItem level1Item = null;
            OutlineItem level2Item = null;
            for (OutlineItem levelItem : levelItems) {
                Paragraph para = levelItem.getParagraph();
                String itemText = levelItem.getText().trim();

                // 将一级标题中可能存在空格与目录内的不一样
                /*if (!catalogueLevel.isEmpty()) {
                    level1 = RELIABLE_LEVEL1;
                    Matcher level1Mateher = level1.matcher(itemText);
                    if (level1Mateher.find()) {
                        if (level1Mateher.end() < itemText.length() - 1) {
                            String text = itemText.substring(level1Mateher.end()).trim();
                            if (TITLE_TEXT_SPACE.matcher(text).find()) {
                                String amendText = text.replaceAll("\\s", "");
                                itemText = itemText.replace(text, amendText);
                            }
                        }
                    }
                }*/

                // 位置不在页面的左边不提取
                if (para.getLeft() > getPageByNumber(pages, para.getPageNumber()).getPageWidth() * .5f) {
                    continue;
                }

                // 目录内的标题不提取
                if (CATALOGUE_SUFFIX.matcher(itemText).find()) {
                    continue;
                }

                if ((level1List.contains(itemText.replaceAll("\\s", "")) ||
                        isSpecialTitle(paragraphs, para, pages)) &&
                        !this.getChildren().contains(levelItem) &&
                        !level2List.contains(itemText.replaceAll("\\s", ""))) {
                    this.addChild(levelItem);
                    level1Item = levelItem;
                    // 避免同一目录误判为两级目录导致的死循环
                    continue;
                }

                if (!level2List.isEmpty() && level1Item!= null && !level1Item.getChildren().contains(levelItem)) {
                    if (level2List.contains(itemText.replaceAll("\\s", ""))) {
                        if (level1Item.getFirstChild() == null) {
                            // 子节点第一个一定是从一开始
                            if (!LEVEL_TITLE_FIRST_CHILD.matcher(levelItem.getText()).find() && !noPrefixes && !relyOnContext) {
                                continue;
                            }
                        }

                        level1Item.addChild(levelItem);
                        level2Item = levelItem;
                        continue;
                    }
                }

                if (!level3List.isEmpty() && level2Item!= null && !level2Item.getChildren().contains(levelItem)) {
                    if (level3List.contains(itemText)) {
                        if (level2Item.getFirstChild() == null) {
                            // 子节点第一个一定是从一开始
                            if (!LEVEL_TITLE_FIRST_CHILD.matcher(levelItem.getText()).find() && !noPrefixes) {
                                continue;
                            }
                        }

                        level2Item.addChild(levelItem);
                    }
                }
            }

            levelItems.stream().filter(levelItem -> levelItem.getLevel() != 0)
                    .forEach(levelItem -> levelItem.getParagraph().assignOutline(levelItem));
        }
    }

    // 抽取对于没有序号的标题
    private boolean isSpecialTitle(List<Paragraph> paragraphs, Paragraph para, List<Page> pages) {
        if (para.isPageHeader() || para.isPageFooter()) {
            return false;
        }

        Paragraph nextParagraph = findNearestParagraphBelow(paragraphs, para);
        if (null == nextParagraph || null == para.getText()) {
            return false;
        }
        String paragraphText = para.getText();

        Pattern twoWords = Pattern.compile("^\\S\\s*\\S$");
        boolean largeFont = para.getFontSize() >= 10;
        boolean boldFont =  para.getTextBlock().getElements().get(0).isBold();

        boolean center = true;
        // 多行文字都需要居中且长度占满一行
        for (TextChunk chunk : para.getTextBlock().getElements()) {
            if (!FloatUtils.feq((chunk.getVisibleMaxX() + chunk.getVisibleMinX()) * .5f,
                    getPageByNumber(pages, para.getPageNumber()).getPageWidth() * 0.5f, chunk.getFontSize())
                    || chunk.getVisibleWidth() / (getPageByNumber(pages, para.getPageNumber()).getPageWidth() - PaperParameter.A4.leftMarginInPt - PaperParameter.A4.rightMarginInPt) > 0.8) {
                center = false;
                break;
            }
        }

        boolean nextParaCenter = FloatUtils.feq((nextParagraph.getTextBlock().getVisibleMaxX() + nextParagraph.getTextBlock().getVisibleMinX()) * .5f,
                getPageByNumber(pages, nextParagraph.getPageNumber()).getPageWidth() * 0.5f, 5.0f);
        boolean twoWordsTitle = twoWords.matcher(paragraphText.trim()).find();

        boolean nextPara = (center && !nextParaCenter) || para.getFontSize() > nextParagraph.getFontSize() || PUNCTUATIONS.matcher(nextParagraph.getText()).find();

        return para.getPageNumber() != 1
                && para.getCenterY() < getPageByNumber(pages, para.getPageNumber()).getPageHeight() * 0.25f
                && nextPara
                && !CONTENT_LEVEL.matcher(paragraphText).find()
                && !LOW_LEVEL.matcher(paragraphText).find()
                && !SPECIAL_NUM_PREFIX.matcher(paragraphText).find()
                && !PUNCTUATIONS.matcher(paragraphText).find()
                && para.getTextBlock().getColumnCount() < 3
                && nextParagraph.getTextBlock().getColumnCount() < 2
                && ((largeFont && boldFont && para.getTextBlock().getVisibleBBox().getHeight() < para.getFontSize() * 2)
                || (center && boldFont) || (largeFont && center)
                || (center && twoWordsTitle));
    }

    private Paragraph findNearestParagraphBelow(List<Paragraph> paragraphs, Paragraph para) {
        int pageNumber = para.getPageNumber();
        float y = para.getBottom();
        return paragraphs.stream()
                .filter(paragraph -> paragraph.getPageNumber() == pageNumber && paragraph.getMinY() >= y)
                .sorted(Comparator.comparingDouble(RectangularShape::getMinY))
                .findFirst().orElse(null);
    }

    // 过滤不带页码的目录也产生的多余标题
    private void filterInvalidItem(List<OutlineItem> levelItems, List<Page> pages, List<Chart> charts, List<Table> tables) {
        Set<Integer> deletePageNumbers = new HashSet<>();
        Set<Integer> notDeletePageNumbers = new HashSet<>();
        Map<Integer, Integer> pageNumCount = new HashMap<>();

        charts.stream().forEach(chart -> notDeletePageNumbers.add(chart.getPageNumber()));
        tables.stream().forEach(table -> notDeletePageNumbers.add(table.getPageNumber()));

        for (OutlineItem item : levelItems) {
            int pageNum = item.getParagraph().getPageNumber();
            // 如果存在目录页码，则不进行过滤
            if (CATALOGUE_SUFFIX.matcher(item.getText()).find()) {
                return;
            }

            if (pageNum > pages.size() * 0.3) {
                continue;
            }

            if (item.getParagraph().getHeight() > item.getParagraph().getFontSize() * 2) {
                continue;
            }

            if (notDeletePageNumbers.contains(pageNum)) {
                continue;
            }

            if (pageNumCount.containsKey(pageNum)) {
                pageNumCount.put(pageNum, pageNumCount.get(pageNum) + 1);
            } else {
                pageNumCount.put(pageNum, 2);
            }

            if ((float) pageNumCount.get(pageNum) / pages.get(pageNum - 1).getParagraphCount() > .95f) {
                deletePageNumbers.add(pageNum);
            }
        }

        for (int i = 0; i < levelItems.size();) {
            OutlineItem item = levelItems.get(i);
            if (deletePageNumbers.contains(item.getParagraph().getPageNumber())) {
                levelItems.remove(i);
            } else {
                i++;
            }
        }

    }

    private Page getPageByNumber(List<Page> pages, int pageNumber) {
        for (Page page : pages) {
            if (pageNumber == page.getPageNumber()) {
                return page;
            }
        }

        // 如果找不到就取第一页
        return pages.get(0);
    }

    JsonArray getChildrenDocument() {
        JsonArray array = new JsonArray();
        OutlineItem item = firstChild;
        while (item != null) {
            array.add(item.toDocument());
            item = item.nextSibling;
        }
        return array;
    }

    public static boolean isVaildOutline(Outline pdfOutline) {
        boolean vaildOutline = false;
        List<OutlineItem> children = pdfOutline.getChildren();
        for (OutlineItem child : children) {
            if (!child.getChildren().isEmpty()) {
                for (OutlineItem son : child.getChildren()) {
                    // 子级目录如果有序号，则目录第一项要满足要求
                    if (CONTENT_LEVEL.matcher(son.getText().trim()).find()) {
                        return LEVEL_TITLE_FIRST_CHILD.matcher(child.getFirstChild().getText().trim()).find();
                    }
                }
                // 如果没有带序号的子级目录，则存在二级目录就认为是有效的目录
                vaildOutline = true;
            }
        }
        return vaildOutline;
    }


}
