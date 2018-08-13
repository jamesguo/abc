package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.ContentGroupPage;
import com.abcft.pdfextract.core.table.ContentGroupTableExtractor;
import com.abcft.pdfextract.core.table.Ruling;
import com.abcft.pdfextract.core.table.TableExtractParameters;
import com.abcft.pdfextract.core.table.extractors.LayoutAnalysisAlgorithm;
import com.abcft.pdfextract.core.util.NumberUtil;
import com.abcft.pdfextract.core.util.TrainDataWriter;
import com.abcft.pdfextract.util.ClosureInt;
import com.abcft.pdfextract.util.FloatUtils;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/11/13.
 */
public class TextExtractEngine {

    private static Logger logger = LogManager.getLogger();
    private final PDDocument document;
    private final ContentExtractParameters parameters;
    private final ContentExtractorCallback callback;
    private ContentGroupPage contentGroupPage = null;

    public TextExtractEngine(PDDocument document, ContentExtractParameters parameters, ContentExtractorCallback callback) {
        this.document = document;
        this.parameters = parameters;
        this.callback = callback;
    }

    private Paragraph toParagraph(int pageNumber, TextBlock textBlock) {
        Rectangle2D bounds = textBlock.getBounds2D();
        TextChunk textChunk = textBlock.getFirstTextChunk();
        Paragraph p = new Paragraph(pageNumber, textBlock.getText(), (float) bounds.getMinX(), (float) bounds.getMinY(),
                (float) bounds.getWidth(), (float) bounds.getHeight());
        p.setLines(1);
        p.setFontName(textChunk.getMostCommonFontName());
        p.setFontSize(textChunk.getFontSize());
        // 再最后生成全文HTML的时候再生成段落HTML
        // p.setHTML(buildHTML(p, textBlock));
        p.setTextBlock(textBlock);
        p.setTextSplitStrategy(parameters.textSplitStrategy);
        return p;
    }

    public Page buildPage(Page page, int pageNumber, PDPage pdPage, ContentGroup contentGroup, boolean enableGroup) {
        List<TextChunk> textChunks = contentGroup.getAllTextChunks();
        List<TextChunk> merged = new TextChunkMerger().merge(textChunks);
        findHeaderAndFooter(contentGroup, page, merged);

        buildGroups(pdPage, contentGroup, page, merged, enableGroup);

        List<Page.TextGroup> allGroups = page.getAllGroups();
        for (int i = 0; i < allGroups.size(); i++) {
            Page.TextGroup textGroup = allGroups.get(i);
            processTextGroup(pageNumber, page, i, textGroup,
                    textGroup == page.getHeader() || textGroup == page.getFooter()
                            || textGroup == page.getLeftWing() || textGroup == page.getRightWing());
        }
        return page;
    }

    private void buildGroups(PDPage pdPage, ContentGroup contentGroup, Page page, List<TextChunk> merged, boolean enableGroup) {
        List<TextChunk> tempMerged = merged;
        merged = merged.stream().filter(textChunk -> !textChunk.isPageHeaderOrFooter() && !textChunk.isPageLeftOrRightWing()).collect(Collectors.toList());
        if (merged.isEmpty()) {
            return;
        }

        if (!enableGroup) {
            Page.TextGroup group = new Page.TextGroup(new Rectangle(merged.get(0)));
            for (TextChunk textChunk : merged) {
                if (textChunk.isPagination()) {
                    continue;
                }
                group.addText(textChunk);
            }
            page.addGroup(group);
        } else {
            List<Rectangle> rectangles;
            if (contentGroup.hasTag(Tags.CONTENT_LAYOUT_RESULT)) {
                LayoutResult layout = contentGroup.getTag(Tags.CONTENT_LAYOUT_RESULT, LayoutResult.class);
                rectangles = layout.getTextLayoutAreas();
            } else {
                TableExtractParameters tableExtractParameters = parameters.context.getTag(PdfExtractContext.TABLE_PARAMS, TableExtractParameters.class);
                if (null == tableExtractParameters) {
                    // 这里可能需要强制开启版本分析
                    tableExtractParameters = new TableExtractParameters.Builder()
                            .setStopWatchEnable(false)
                            .setUseStructureFeature(false) // 文本版面分析不需要提取结构化表格
                            .setLayoutAnalysis(true)
                            .build();
                    tableExtractParameters.context = parameters.context;
                } else {
                    tableExtractParameters = tableExtractParameters.buildUpon()
                            .setStopWatchEnable(false)
                            .build();
                }
                ContentGroupPage contentGroupPage = ContentGroupTableExtractor.createPage(page.getPageNumber(), pdPage,
                        contentGroup, tableExtractParameters);
                List<Rectangle> contentRectangles = new ArrayList<>();
                List<Rectangle> tableLayoutAreas = new ArrayList<>();
                LayoutAnalysisAlgorithm.detect(contentGroupPage, tableLayoutAreas, contentRectangles);
                rectangles = contentRectangles;
                LayoutResult layout = new LayoutResult(tableLayoutAreas, contentRectangles);
                contentGroup.addTag(Tags.CONTENT_LAYOUT_RESULT, layout);

                resetHeadOrFooter(contentGroup, contentGroupPage, page, merged, tempMerged);
                this.contentGroupPage = contentGroupPage;
            }

            for (Rectangle rectangle : rectangles) {
                Page.TextGroup group = new Page.TextGroup(rectangle);
                group.setAutoResize(false);
                page.addGroup(group);
            }

            for (TextChunk textChunk : merged) {
                if (textChunk.isPageHeader() || textChunk.isPageFooter() || textChunk.isPageLeftOrRightWing()) {
                    continue;
                }
                Page.TextGroup textGroup = page.findOrCreateGroup(textChunk);
                textGroup.addText(textChunk);
            }
        }
        page.mergeGroups();
    }

    private void resetHeadOrFooter(ContentGroup contentGroup, ContentGroupPage contentGroupPage, Page page, List<TextChunk> merged, List<TextChunk> tempMerged) {
        // 重新设置页脚
        Ruling tempRuling = contentGroupPage.getHorizontalRulings()
                .stream()
                .max(Comparator.comparingDouble(Ruling::getBottom))
                .orElse(null);
        List<Ruling> verticalBottomRulings = new ArrayList<>();
        if (tempRuling != null) {
            verticalBottomRulings = contentGroupPage.getVerticalRulings()
                    .stream()
                    .filter(ruling -> ruling.nearlyIntersects(tempRuling))
                    .filter(ruling -> ruling.getBottom() > tempRuling.getBottom())
                    .collect(Collectors.toList());
        }

            if (tempRuling != null && verticalBottomRulings.size() < 1
                    && tempRuling.getBottom() > contentGroup.getTag(Tags.PAGINATION_FRAME, Rectangle.class).getBottom() - 15.) {
                List<TextChunk> tempTextChunks = tempMerged.stream()
                        .filter(textChunk -> FloatUtils.fgte(textChunk.getTop(), tempRuling.getBottom(),3.))
                        .collect(Collectors.toList());
                tempTextChunks.forEach(textChunk -> textChunk.setPaginationType(PaginationType.FOOTER));
                findHeaderAndFooter(contentGroup, page, tempMerged);
            }


        //重新设置页眉
        Ruling tempRuling2 = contentGroupPage.getHorizontalRulings()
                .stream()
                .min(Comparator.comparingDouble(Ruling::getTop))
                .orElse(null);
        List<Ruling> verticalTopRulings = new ArrayList<>();
        if (tempRuling2 != null) {
            verticalTopRulings = contentGroupPage.getVerticalRulings()
                    .stream()
                    .filter(ruling -> ruling.nearlyIntersects(tempRuling2))
                    .filter(ruling -> ruling.getTop() < tempRuling2.getTop())
                    .collect(Collectors.toList());
        }

        if (tempRuling2 != null && verticalTopRulings.size() < 1
                && tempRuling2.getBottom() < contentGroup.getTag(Tags.PAGINATION_FRAME, Rectangle.class).getTop() + 15.) {
            List<TextChunk> tempTextChunks = tempMerged.stream()
                    .filter(textChunk -> FloatUtils.flte(textChunk.getBottom(), tempRuling2.getTop(),3.))
                    .collect(Collectors.toList());
            tempTextChunks.forEach(textChunk -> textChunk.setPaginationType(PaginationType.HEADER));
            findHeaderAndFooter(contentGroup, page, tempMerged);
        }
    }

    public static Page.TextGroup createColumn(Rectangle rectangle, List<TextChunk> textChunks) {
        if (textChunks == null || textChunks.isEmpty()) {
            return null;
        }
        Page.TextGroup textGroup = new Page.TextGroup(rectangle);
//        textChunks.sort(Comparator.comparingDouble(TextChunk::getTop));
        textGroup.addTexts(textChunks);
        if(textChunks.get(0).isPageHeader()) {
            textGroup.setPageHeader(true);
        } else if(textChunks.get(0).isPageFooter()) {
            textGroup.setPageFooter(true);
        } else if(textChunks.get(0).isPageLeftWing()) {
            textGroup.setPageLeftWing(true);
        } else if(textChunks.get(0).isPageRightWing()) {
            textGroup.setPageRightWing(true);
        }
        return textGroup;
    }

    private static void writeHeaderAndFooterLine(ContentGroup contentGroup, Page page) {
        Page.TextGroup header = page.getHeader();
        if (header != null) {
            contentGroup.addTag(Tags.PAGINATION_TOP_LINE, header.getBottom() + 1.f);
        }
        Page.TextGroup footer = page.getFooter();
        if (footer != null) {
            contentGroup.addTag(Tags.PAGINATION_BOTTOM_LINE, footer.getTop() - 1.f);
        }
    }

    private static void findHeaderAndFooter(ContentGroup contentGroup, Page page, List<TextChunk> textChunks) {
        double pageWidth = page.getPageWidth();
        double pageHeight = page.getPageHeight();
        Rectangle paginationFrame = contentGroup.getTag(Tags.PAGINATION_FRAME, Rectangle.class);
        Rectangle headerRect = new Rectangle(0f, 0f, (float) pageWidth, paginationFrame.getTop()-0.1f);
        Rectangle footerRect = new Rectangle(0f, paginationFrame.getBottom()+0.1f,
                (float) pageWidth, pageHeight-paginationFrame.getBottom()-0.1f);

        Rectangle leftWingRect = new Rectangle(0f, 0f,0.1, (float)pageHeight);
        Rectangle rightWingRect = new Rectangle(pageWidth - 0.1, 0, 0.1, pageHeight);
        textChunks.stream()
                .filter(TextChunk::isVertical)
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrAlphabet(textChunk.getText()))
                .filter(textChunk -> FloatUtils.flte(textChunk.getRight(), 65.))
                .forEach(textChunk -> textChunk.setPaginationType(PaginationType.LEFT_WING));
        textChunks.stream()
                .filter(TextChunk::isVertical)
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrAlphabet(textChunk.getText()))
                .filter(textChunk -> FloatUtils.fgte(textChunk.getLeft(), pageWidth - 65.))
                .forEach(textChunk -> textChunk.setPaginationType(PaginationType.RIGHT_WING));
        List<TextChunk> headers = textChunks.stream()
                .filter(TextChunk::isPageHeader)
                .filter(TextChunk::isHorizontal)
                .collect(Collectors.toList());
        for (TextChunk header : headers) {
            headerRect.merge(header);
        }
        page.setHeader(createColumn(headerRect, headers));

        List<TextChunk> footers = textChunks.stream()
                .filter(TextChunk::isPageFooter)
                .filter(TextChunk::isHorizontal)
                .collect(Collectors.toList());
        for (TextChunk footer : footers) {
            footerRect.merge(footer);
        }
        page.setFooter(createColumn(footerRect, footers));

        List<TextChunk> leftWings = textChunks.stream()
                .filter(TextChunk::isPageLeftWing)
                .filter(TextChunk::isVertical)
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrAlphabet(textChunk.getText()))
                .collect(Collectors.toList());
        for(TextChunk leftWing : leftWings) {
            leftWingRect.merge(leftWing);
        }
        page.setLeftWing(createColumn(leftWingRect, leftWings));

        List<TextChunk> rightWings = textChunks.stream()
                .filter(TextChunk::isPageRightWing)
                .filter(TextChunk::isVertical)
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrAlphabet(textChunk.getText()))
                .collect(Collectors.toList());
        for(TextChunk rightWing : rightWings) {
            rightWingRect.merge(rightWing);
        }
        page.setRightWing(createColumn(rightWingRect, rightWings));

        writeHeaderAndFooterLine(contentGroup, page);
    }

    public Page processPage(int pageNumber, PDPage pdPage) throws TimeoutException {
        Page page = new Page(pageNumber);
        Point.Float pageSize = ExtractorUtil.determinePageSize(pdPage);
        page.setPageWidth((float) pageSize.getX());
        page.setPageHeight((float) pageSize.getY());
        ContentGroup contentGroup = ((PdfExtractContext)parameters.context).getPageContentGroup(pdPage);
        if (null == contentGroup) {
            logger.warn("Page {}: Failed to load content group.", pageNumber);
            return page;
        }
        if (contentGroup.hasTag(Tags.TEXT_PAGE)) {
            return contentGroup.getTag(Tags.TEXT_PAGE, Page.class);
        }
        if (contentGroup.getTextTree() != null && parameters.useStructureFeatures) {
            if (processTextTree(pageNumber, page, contentGroup, pdPage)) {
                return page;
            }
        }
        if (parameters.skipNoStructureInfoPage) {
            return page;
        }
        buildPage(page, pageNumber, pdPage, contentGroup, this.parameters.enableGroup);
        mergeCrossPageParagraphs(pageNumber, page);
        contentGroup.addTag(Tags.TEXT_PAGE, page);
        if (callback != null) {
            callback.onItemExtracted(page);
        }
        return page;
    }

    private void mergeCrossPageParagraphs(int pageNumber, Page page) {
        if (!parameters.mergeCrossPageParagraphs || pageNumber == 1) {
            return;
        }
        PdfExtractContext.PageContext prevPageContext = ((PdfExtractContext)parameters.context).getPageContext(pageNumber - 2);
        if (prevPageContext == null) {
            return;
        }
        ContentGroup prevContentGroup = prevPageContext.getCachedContentGroup();
        if (prevContentGroup == null) {
            return;
        }
        if (!prevContentGroup.hasTag(Tags.TEXT_PAGE)) {
            return;
        }
        Page prevPage = prevContentGroup.getTag(Tags.TEXT_PAGE, Page.class);
        Paragraph lastParagraph = null;
        Paragraph firstParagraph = null;
        if (TensorflowManager.INSTANCE.isModelAvailable(TensorflowManager.PARAGRAPH)) {
            List<Paragraph> tempParagraphs1 = prevPage.getParagraphs(false, true).stream()
                    .filter(paragraph -> !paragraph.isPagination())
                    .filter(paragraph ->
                            paragraph.getTextBlock().getLastLineTag().equals(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE)
                                    || paragraph.getTextBlock().getLastLineTag().equals(TrainDataWriter.LineTag.PARAGRAPH_START))
                    .filter(paragraph -> {
                        int size = prevPage.getTextGroups().size();
                        if (size < 1) return false;
                        List<Page.TextGroup> textGroupList = prevPage.getTextGroups().stream()
                                .filter(textGroup -> textGroup.getTextChunks().size() > 1)
                                .collect(Collectors.toList());
                        textGroupList.sort(Comparator.comparing(textGroup -> textGroup.getBottom()));
                        return paragraph.getBottom() > prevPage.getTextGroups().get(size - 1).getBottom() - 30;
                    })
                    .filter(paragraph -> !TrainDataWriter.PARAGRAPH_END1_RE.matcher(paragraph.getText()).find())
                    .collect(Collectors.toList());
            int size1 = tempParagraphs1.size();
            if (size1 > 0) {
                tempParagraphs1.sort(Comparator.comparing(Paragraph::getBottom));
                lastParagraph = tempParagraphs1.get(size1 - 1);
            }
            List<Paragraph> tempParagraphs2 = page.getParagraphs(false, true).stream()
                    .filter(paragraph -> !paragraph.isPagination())
                    .filter(paragraph ->
                            paragraph.getTextBlock().getLastLineTag().equals(TrainDataWriter.LineTag.PARAGRAPH_START)
                                    || paragraph.getTextBlock().getLastLineTag().equals(TrainDataWriter.LineTag.PARAGRAPH_MIDDLE)
                                    || paragraph.getTextBlock().getLastLineTag().equals(TrainDataWriter.LineTag.PARAGRAPH_END)
                                    || paragraph.getTextBlock().getLastLineTag().equals(TrainDataWriter.LineTag.SINGLE_LINE_PARAGRAPH))

                    .filter(paragraph -> {
                        int size = page.getTextGroups().size();
                        if (size < 1) return false;
                        List<Page.TextGroup> textGroupList = page.getTextGroups();
                        textGroupList.sort(Comparator.comparing(textGroup -> textGroup.getTop()));
                        Page.TextGroup textGroup = page.getTextGroups().get(0);
                        if (paragraph.selfOverlapRatio(textGroup) > 0.8
                                && TextUtils.getNoneBlankChineseTextCount(paragraph.getText()) < 31
                                && !TrainDataWriter.PARAGRAPH_END_RE.matcher(paragraph.getText()).find()) {
                            return false;
                        }
                        int textChunkSize = textGroup.getTextChunks().size();
                        return paragraph.getTop() < textGroup.getTop() + 10
                                || textChunkSize > 0
                                && textGroup.getTextChunks().get(0).selfOverlapRatio(paragraph.getTextBlock().getFirstTextChunk()) > 0.8;
                    })
                    .filter(paragraph -> !TrainDataWriter.SERIAL_NUMBER_ROW_RE.matcher(paragraph.getText()).find()
                            && !TrainDataWriter.PARAGRAPH_BULLETS_STATRT_RE.matcher(paragraph.getText()).find()
                            && !TrainDataWriter.CHART_BEGINNING_KEYWORD_RE.matcher(paragraph.getText()).find())
                    .filter(paragraph ->
                    {
                        float leftX = paragraph.getTextBlock().getFirstTextChunk().getLeft();
                        float rightX = paragraph.getTextBlock().getLastTextChunk().getRight();
                        return (Math.abs(leftX + rightX - page.getPageWidth()) / 2 / page.getPageWidth() > 0.01
                                || TrainDataWriter.PARAGRAPH_END1_RE.matcher(paragraph.getText()).find());
                    })
                    .collect(Collectors.toList());
            int size2 = tempParagraphs2.size();
            if (size2 > 0) {
                tempParagraphs2.sort(Comparator.comparing(Paragraph::getTop));
                firstParagraph = tempParagraphs2.get(0);
            }
            if (firstParagraph == null || lastParagraph == null ||
                    StringUtils.isBlank(lastParagraph.getText()) ||
                    StringUtils.isBlank(firstParagraph.getText())) {
                return;
            }
            if (firstParagraph != null && lastParagraph != null) {
                lastParagraph.setCrossPageNextParagraph(firstParagraph);
                firstParagraph.setCrossPagePrevParagraph(lastParagraph);
                return;
            }
        } else {
            lastParagraph = prevPage.getLastParagraph();
            firstParagraph = page.getFirstParagraph();
            if (firstParagraph == null || lastParagraph == null ||
                    StringUtils.isBlank(lastParagraph.getText()) ||
                    StringUtils.isBlank(firstParagraph.getText())) {
                return;
            }
            if (ParagraphMerger.canMerge(lastParagraph, page, firstParagraph, page)) {
                lastParagraph.setCrossPageNextParagraph(firstParagraph);
                firstParagraph.setCrossPagePrevParagraph(lastParagraph);
            }
            return;
        }
    }

    private void processTextGroup(int pageNumber, Page contentPage, int textGroupIndex, Page.TextGroup textGroup, boolean mergeLine) {
        if (textGroup == null || textGroup.getTextChunkCount() == 0) {
            return;
        }

        List<TextBlock> paragraphs = ParagraphMerger.merge(contentPage, textGroup, mergeLine, this.contentGroupPage);
        if (paragraphs.isEmpty()) {
            return;
        }
        // 合并好的段落按照阅读顺序排序
        NumberUtil.sortByReadingOrder(paragraphs, p -> new Rectangle(p.getBounds2D()));
        for (TextBlock paragraph : paragraphs) {
            Paragraph p = toParagraph(pageNumber, paragraph);
            p.setTextGroupIndex(textGroupIndex);
            contentPage.addParagraph(p);
        }
    }

    private boolean processTextTree(int pageNumber, Page page, ContentGroup contentGroup, PDPage pdPage) {
        TextTree textTree = contentGroup.getTextTree();
        if (!textTree.canUseStructInfoForTextExtraction()) {
            logger.debug("Can't use structure info for text extraction, skip processTextTree");
            return false;
        }
        // 处理pagination为UNSPECIFIED的TextChunk
        textTree.getNoStructTextChunk().stream()
                .filter(textChunk -> textChunk.getPaginationType() == PaginationType.UNSPECIFIED)
                .forEach(textChunk -> {
                    if (textChunk.getBottom() < page.getPageHeight() / 5) {
                        textChunk.setPaginationType(PaginationType.HEADER);
                    } else if (textChunk.getTop() > page.getPageHeight() * 4 / 5){
                        textChunk.setPaginationType(PaginationType.FOOTER);
                    }
                });

        // textChunk合并
        List<TextChunk> tempTextChunks = textTree.getNoStructTextChunk();
        tempTextChunks = new TextChunkMerger().merge(tempTextChunks);
        textTree.setNoStructTextChunk(tempTextChunks);
        textTree.getNoStructTextChunk().stream()
                .filter(TextChunk::isVertical)
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrAlphabet(textChunk.getText()))
                .filter(textChunk -> FloatUtils.flte(textChunk.getRight(), 65.))
                .forEach(textChunk -> textChunk.setPaginationType(PaginationType.LEFT_WING));

        textTree.getNoStructTextChunk().stream()
                .filter(TextChunk::isVertical)
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrAlphabet(textChunk.getText()))
                .filter(textChunk -> FloatUtils.fgte(textChunk.getLeft(),page.getPageWidth() - 65.))
                .forEach(textChunk -> textChunk.setPaginationType(PaginationType.RIGHT_WING));

        // 如果还有不是页眉页脚或者不是侧边栏并且又没有结构化信息的文本, 则返回
        if (!textTree.getNoStructTextChunk().stream().allMatch(textChunk -> textChunk.isPageHeaderOrFooter()||textChunk.isPageLeftOrRightWing())) {
            return false;
        }

        List<TextNode> spans = textTree.deepFindBy(textNode -> PARAGRAPH_TYPES.contains(textNode.getStructureType()));
        long paragraphTextChunkCount = spans.stream().flatMap(node -> node.getAllTextChunks().stream()).count();
        long allTextChunkCount = textTree.getAllTextChunks().size();
        if (paragraphTextChunkCount != allTextChunkCount) {
            // 通过段落找到的文本数和总文本数不一致, 则说明有些文本不在段落里面, 属于信息缺失, 返回
            logger.warn("Paragraph text count: {} doesn't match total text count: {}, skip", paragraphTextChunkCount, allTextChunkCount);
            return false;
        }

        //对页眉或者页脚很长的情况进行转换(将这样的页眉或页脚转换成正文)
        Rectangle paginationFrame = contentGroup.getTag(Tags.PAGINATION_FRAME, Rectangle.class);
        if (!parameters.useLayoutInfoPage) {
            textTree.getNoStructTextChunk().stream()
                    .filter(TextChunk::isPageHeader)
                    .filter(textChunk -> FloatUtils.fgte(textChunk.getTop(), paginationFrame.getTop(), 15.))
                    .forEach(textChunk -> textChunk.setPaginationType(null));

            textTree.getNoStructTextChunk().stream()
                    .filter(TextChunk::isPageFooter)
                    .filter(textChunk -> FloatUtils.flte(textChunk.getBottom(), paginationFrame.getBottom(), 15.))
                    .forEach(textChunk -> textChunk.setPaginationType(null));
        }

        List<TextChunk> headers = textTree.getNoStructTextChunk().stream()
                .filter(textChunk -> textChunk.isPageHeader() && textChunk.isVisible())
                .filter(TextChunk::isHorizontal)
                .sorted(Comparator.comparing(TextChunk::getGroupIndex))
                .collect(Collectors.toList());
        headers = new TextChunkMerger().merge(headers);
        page.setHeader(createColumn(Rectangle.union(headers), headers));
        processTextGroup(pageNumber, page, 0, page.getHeader(), true);

        List<TextChunk> leftWings = textTree.getNoStructTextChunk().stream()
                .filter(TextChunk::isPageLeftWing)
                .sorted(Comparator.comparing(TextChunk::getGroupIndex))
                .collect(Collectors.toList());
        leftWings = new TextChunkMerger().merge(leftWings);
        page.setLeftWing(createColumn(Rectangle.union(leftWings), leftWings));
        ClosureInt closureInt = new ClosureInt();
        if (! headers.isEmpty()) {
            closureInt.increment();
        }
        processTextGroup(pageNumber, page, closureInt.get(), page.getLeftWing(), true);

        if (!parameters.useLayoutInfoPage) {
            textTree.getNoStructTextChunk().stream()
                    .filter(textChunk -> !textChunk.isPagination())
                    .forEach(textChunk ->
                    {
                        Paragraph paragraph = new Paragraph(pageNumber, textChunk.getText(), (float) textChunk.getX(),
                                (float) textChunk.getY(), (float) textChunk.getWidth(), (float) textChunk.getHeight());
                        if (page.getLeftWing() != null && !page.getLeftWing().isEmpty()) {
                            closureInt.increment();
                        }
                        paragraph.setTextGroupIndex(closureInt.get());
                        paragraph.setTextBlock(new TextBlock(textChunk));
                        page.addParagraph(paragraph);
                    });
        }

        contentGroup.setTextTree(textTree);
        contentGroup.setTextChunksPagination(textTree.getNoStructTextChunk());
        if (parameters.useLayoutInfoPage) {
            processTextPageWihtLayout(page, spans, closureInt, pdPage, contentGroup, this.parameters.enableGroup, pageNumber);
        } else {
            processTextPage(page, spans, closureInt, pageNumber);
        }
        List<TextChunk> rightWings = textTree.getNoStructTextChunk().stream()
                .filter(TextChunk::isPageRightWing)
                .sorted(Comparator.comparing(TextChunk::getGroupIndex))
                .collect(Collectors.toList());
        rightWings = new TextChunkMerger().merge(rightWings);
        page.setRightWing(createColumn(Rectangle.union(rightWings), rightWings));
        if (!rightWings.isEmpty()) {
            closureInt.increment();
        }
        processTextGroup(pageNumber, page, closureInt.get(), page.getRightWing(), true);

        List<TextChunk> footers = textTree.getNoStructTextChunk().stream()
                .filter(textChunk -> textChunk.isPageFooter() && textChunk.isVisible())
                .filter(TextChunk::isHorizontal)
                .collect(Collectors.toList());
        footers = new TextChunkMerger().merge(footers);
        page.setFooter(createColumn(Rectangle.union(footers), footers));
        if (!footers.isEmpty()) {
            closureInt.increment();
        }
        processTextGroup(pageNumber, page, closureInt.get(), page.getFooter(), true);

        if (!parameters.useLayoutInfoPage) {
            List<TextChunk> tempAllTextChunks = page.getParagraphs(false, true).stream()
                    .flatMap(paragraph -> paragraph.getTextBlock().getElements().stream()).collect(Collectors.toList());
            buildGroups(pdPage, contentGroup, page, tempAllTextChunks, this.parameters.enableGroup);
            // 对paragraph按照textGroup进行排序
            List<Paragraph> paragraphs = new ArrayList<>();
            List<Paragraph> tempParagraphs = page.getParagraphs(false, true);
            ClosureInt closureInt1 = new ClosureInt();
            List<Paragraph> tempHeaderParagraphs = tempParagraphs.stream()
                    .filter(Paragraph::isPageHeader)
                    .collect(Collectors.toList());
            tempHeaderParagraphs.forEach(paragraph -> paragraph.setTextGroupIndex(closureInt1.get()));
            if (tempHeaderParagraphs.size() > 0) {
                closureInt1.increment();
            }
            paragraphs.addAll(tempHeaderParagraphs);

            List<Paragraph> tempLeftWingParagraphs = tempParagraphs.stream()
                    .filter(Paragraph::isPageLeftWing)
                    .collect(Collectors.toList());
            tempLeftWingParagraphs.forEach(paragraph -> paragraph.setTextGroupIndex(closureInt1.get()));
            paragraphs.addAll(tempLeftWingParagraphs);
            if (tempLeftWingParagraphs.size() > 0) {
                closureInt1.increment();
            }
            List<Page.TextGroup> tempTextGroups = page.getTextGroups();
            for (Page.TextGroup textGroup : tempTextGroups) {
                List<Paragraph> tempParagaraphs2 = tempParagraphs.stream()
                        .filter(paragraph -> paragraph.selfOverlapRatio(textGroup) > 0.9)
                        .filter(paragraph -> !paragraph.isPagination())
                        .collect(Collectors.toList());
                NumberUtil.sortByReadingOrder(tempParagaraphs2);
                int finalTempGroupIndex = closureInt1.get();
                tempParagaraphs2.forEach(paragraph -> paragraph.setTextGroupIndex(finalTempGroupIndex));
                paragraphs.addAll(tempParagaraphs2);
                closureInt1.increment();
            }
            List<Paragraph> tempRightWingParagraphs = tempParagraphs.stream()
                    .filter(Paragraph::isPageRightWing)
                    .collect(Collectors.toList());
            tempRightWingParagraphs.forEach(paragraph -> paragraph.setTextGroupIndex(closureInt1.get()));
            if (tempRightWingParagraphs.size() > 0) {
                closureInt1.increment();
            }
            paragraphs.addAll(tempRightWingParagraphs);
            List<Paragraph> tempFooterPragraphs = tempParagraphs.stream()
                    .filter(Paragraph::isPageFooter)
                    .collect(Collectors.toList());
            tempFooterPragraphs.forEach(paragraph -> paragraph.setTextGroupIndex(closureInt1.get()));
            paragraphs.addAll(tempFooterPragraphs);
            page.setParagraphs(paragraphs);
        }
        writeHeaderAndFooterLine(contentGroup, page);

        mergeCrossPageParagraphs(pageNumber, page);
        page.setUseStructInfo(true);
        contentGroup.addTag(Tags.TEXT_PAGE, page);
        if (callback != null) {
            callback.onItemExtracted(page);
        }
        return true;
    }

    private void processTextPage(Page page, List<TextNode> spans, ClosureInt closureInt, int pageNumber) {
        List<TextBlock> textBlocks = mergeTextNodes(spans);
        textBlocks.removeIf(textBlock -> textBlock.getElements().stream().allMatch(TextChunk::isHiddenOrBlank));
        if (page.getLeftWing() != null && !page.getLeftWing().isEmpty()) {
            closureInt.increment();
        }
        for (TextBlock textBlock : textBlocks) {
            Paragraph paragraph = toParagraph(pageNumber, textBlock);
            paragraph.setTextGroupIndex(closureInt.get());
            page.addParagraph(paragraph);
        }
    }

    private void processTextPageWihtLayout (Page page, List<TextNode> spans, ClosureInt closureInt, PDPage pdPage, ContentGroup contentGroup, boolean enableGroup, int pageNumber) {
        List<TextBlock> textBlocks = mergeTextNodes(spans);
        textBlocks.removeIf(textBlock -> textBlock.getElements().stream().allMatch(TextChunk::isHiddenOrBlank));
        List<TextChunk> textChunks = textBlocks.stream().flatMap(textBlock -> textBlock.getElements().stream()).collect(Collectors.toList());
        textChunks.addAll(contentGroup.getTextTree().getNoStructTextChunk().stream()
                            .filter(textChunk -> !textChunk.isPagination())
                            .collect(Collectors.toList())
        );
        if (page.getFooter() != null) {
            textChunks.addAll(page.getFooter().getTextChunks());
        }
        if (page.getHeader() != null) {
            textChunks.addAll(page.getHeader().getTextChunks());
        }
        buildGroups(pdPage, contentGroup, page, textChunks, enableGroup);

        List<Page.TextGroup> allGroups = page.getTextGroups();

        if (page.getLeftWing() != null && !page.getLeftWing().isEmpty()) {
            closureInt.increment();
        }

        for (int i = 0; i < allGroups.size(); ++i) {
            Page.TextGroup textGroup = allGroups.get(i);
            List<TextBlock> tempBlocks = textBlocks.stream().filter(textBlock -> new Rectangle(textBlock.getVisibleBBox()).selfOverlapRatio(textGroup) > 0.85).collect(Collectors.toList());
            NumberUtil.sortByReadingOrder(tempBlocks,TextBlock::toRectangle);

            for (TextBlock textBlock : tempBlocks) {
                Paragraph paragraph = toParagraph(pageNumber, textBlock);
                paragraph.setTextGroupIndex(closureInt.get());
                page.addParagraph(paragraph);
            }
            closureInt.increment();
        }
    }

    private List<TextBlock> mergeTextNodes(List<TextNode> textNodes) {
        List<TextBlock> textBlocks = new ArrayList<>();
        for (TextNode textNode : textNodes) {
            if (textNode.getAllStructureType().contains(INLINE_SHAPE)) {
                for (TextNode child : textNode.getChildren()) {
                    textBlocks.add(child.toTextBlock());
                }
                continue;
            }
            if (textNode.getStructureType().equals(FIGURE)) {
                List<TextChunk> lines = ParagraphMerger.mergeToLine(textNode.getAllTextChunks());
                for (TextChunk line : lines) {
                    textBlocks.add(new TextBlock(line));
                }
                continue;
            }
            TextBlock textBlock = textNode.toTextBlock();
            textBlocks.add(textBlock);
        }
        return textBlocks;
    }

    private static final String INLINE_SHAPE = "InlineShape";
    private static final String TR = "TR";
    private static final String TD = "TD";
    private static final String FIGURE = "Figure";
    private static final Set<String> PARAGRAPH_TYPES = Sets.newHashSet("P", TD, FIGURE, "TOCI", "LI", "H1", "H2", "H3", "H4", "H5", "H6");
    private boolean canMerge(TextNode prevNode, TextNode textNode) {
        return prevNode.parentIsType(TD) && textNode.parentIsType(TD)
                && hasSameParent(prevNode.getParent(), textNode.getParent(), TR)
                && prevNode.getBounds2D().verticalOverlapRatio(textNode.getBounds2D()) > 0.5;
    }

    private static boolean hasSameParent(TextNode prevNode, TextNode textNode, String type) {
        if (prevNode.getParent() == null || textNode.getParent() == null) {
            return false;
        }
        return prevNode.getParent() == textNode.getParent() && type.equals(prevNode.getParent().getStructureType());
    }

}
