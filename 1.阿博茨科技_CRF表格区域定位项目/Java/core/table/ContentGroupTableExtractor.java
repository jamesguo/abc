package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PaperParameter;
import com.abcft.pdfextract.core.PdfExtractContext;
import com.abcft.pdfextract.core.chart.Chart;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.*;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.util.FastMath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class ContentGroupTableExtractor {

    private static final Logger logger = LogManager.getLogger();

    static ContentGroupPage createPage(int pageNumber, PDPage page, TableExtractParameters params) throws TimeoutException {
        PdfExtractContext extractContext = params.getExtractContext();
        ContentGroup contentGroup = extractContext.getPageContentGroup(page);
        if (null == contentGroup) {
            logger.warn("Page {}: Failed to load content group.", pageNumber);
            params.stopWatch.stop("Load ContentGroup");
            return null;
        }
        params.stopWatch.split("Load ContentGroup");
        return createPage(pageNumber, page, contentGroup, params);
    }

    public static ContentGroupPage createPage(int pageNumber, PDPage page, ContentGroup contentGroup, TableExtractParameters params) {
        List<Chart> charts = params.getExtractContext().getCharts().stream().filter(chart -> chart.getPageNumber() == pageNumber
                && chart.type != ChartType.BITMAP_CHART).collect(Collectors.toList());
        if (contentGroup.hasTag(Tags.CONTENT_GROUP_PAGE)) {
            ContentGroupPage contentGroupPage = contentGroup.getTag(Tags.CONTENT_GROUP_PAGE, ContentGroupPage.class);
            contentGroupPage.setCharts(charts);
            return contentGroupPage;
        }
        List<PathItem> pathItems = contentGroup.getAllPathItems();
        List<TextChunk> textChunks = contentGroup.getAllTextChunks();
        Map<TextDirection, Pair<Integer, Float>> textDirectionMap =
                StatsUtils.groupDistribution(textChunks, TextChunk::getDirection, textChunk -> !textChunk.isBlank());

        boolean verticalUp = textDirectionMap.containsKey(TextDirection.VERTICAL_UP)
                && textDirectionMap.get(TextDirection.VERTICAL_UP).getRight() > 0.85f;
        boolean verticalDown = textDirectionMap.containsKey(TextDirection.VERTICAL_DOWN)
                && textDirectionMap.get(TextDirection.VERTICAL_DOWN).getRight() > 0.85f;

        Point2D.Float pageSize = ExtractorUtil.determinePageSize(page);

        int textRotate = 0;

        if (verticalUp) {
            textRotate = 90;
        } else if (verticalDown) {
            textRotate = -90;
        }
        params.stopWatch.split("Correct Text Direction");

        float pageWidth = pageSize.x;
        float pageHeight = pageSize.y;
        PaperParameter rawPaper = contentGroup.getTag(Tags.PAPER, PaperParameter.class);

        List<Rectangle2D> clipAreas = new ArrayList<>();

        // 暂且用之前计算好的线框，这里设定页眉、页脚已经 OK 那么旧不会进行表格的搜索方式了
        Rectangle2D paginationFrame = (Rectangle2D) contentGroup.getTag(Tags.PAGINATION_FRAME);
        ClosureBoolean hasHeaderLine = new ClosureBoolean(true);
        ClosureBoolean hasFooterLine = new ClosureBoolean(true);
        ClosureWrapper<PaperParameter> paperWrapper = new ClosureWrapper<>(rawPaper);
        paperWrapper.set(rawPaper.withMargins(
                (float) paginationFrame.getMinY(),
                (float) paginationFrame.getMinY(),
                (float) (pageWidth - paginationFrame.getMaxX()),
                (float) (pageHeight - paginationFrame.getMaxY())
        ));

        List<Ruling> rulings = new ArrayList<>(100);
        collectClipAreasAndRulings(params, pageNumber, clipAreas, rulings, contentGroup, pageWidth, pageHeight, paperWrapper,
                false, hasHeaderLine, hasFooterLine);
        params.stopWatch.split("Collect Rulings");

        TableUtils.findAdobeHeaderLine(rulings, pageWidth, hasHeaderLine, paperWrapper);
        TableUtils.findFooterLine(rulings, textChunks, pageWidth, hasFooterLine, paperWrapper);

        PaperParameter paper = paperWrapper.get();

        ClosureBoolean pageHeaderDetected = new ClosureBoolean(false);
        ClosureBoolean pageFooterDetected = new ClosureBoolean(false);
        List<TextChunk> marginChunks = getCleanTextChunks(textChunks, paper, contentGroup.getTag(Tags.PAGINATION_TOP_LINE, Float.class),
                contentGroup.getTag(Tags.PAGINATION_BOTTOM_LINE, Float.class), pageHeaderDetected, pageFooterDetected);

        TextElementsInfo textElementsInfo = buildTextElements(textChunks);
        params.stopWatch.split("Clean Text");

        RectangleSpatialIndex<TextChunk> textIndex = new RectangleSpatialIndex<>();
        textIndex.addAll(textChunks);

        int pageRotation = page.getRotation();

        ContentGroupPage p = new ContentGroupPage(
                params, pageNumber, page,
                0, 0, pageSize.x, pageSize.y, pageRotation, paper,
                textElementsInfo.minCharWidth, textElementsInfo.minCharHeight,
                textElementsInfo.avgCharWidth, textElementsInfo.avgCharHeight,
                textElementsInfo.characters, textChunks,
                rulings, clipAreas, textElementsInfo.characterIndex, textIndex,
                null, null, null, pathItems);
        p.setRoot(contentGroup);
        p.setTextRotate(textRotate);
        p.setTextArea(textElementsInfo.textArea);
        p.setPageHeaderDetected(pageHeaderDetected.get());
        p.setPageFooterDetected(pageFooterDetected.get());
        p.setCharts(charts);
        p.setPageMarginChunks(marginChunks);

        //收集填充区,包括柱状图和饼状图等
        List<FillAreaGroup> fillAreaGroups = new ArrayList<>();
        FillAreaMerge.collectFillAreasGroup(p, fillAreaGroups);
        p.setPageFillAreaGroup(fillAreaGroups);

        TableDebugUtils.writeImage(p, Arrays.asList(rawPaper.createFrame(), paper.createFrame(), p.getTextArea()),
                "text-frames");

        contentGroup.addTag(Tags.CONTENT_GROUP_PAGE, p);
        return p;
    }

    private static List<TextChunk> getCleanTextChunks(List<TextChunk> textChunks,
                                        PaperParameter paper,
                                        Float headerLine, Float footerLine,
                                        ClosureBoolean pageHeaderDetected, ClosureBoolean pageFooterDetected ) {
        // 建设银行-2017年第三季度报告
        // 部分页眉可能是表格的标题（尤其是银行类），所以仅在页眉线实际存在时删除页眉
        int chunkNum = textChunks.size();
        List<TextChunk> cleanChunks = new ArrayList<>();
        if (headerLine != null) {
            cleanChunks.addAll(textChunks.stream()
                    .filter(textChunk -> paper.topMarginContains(textChunk.getMaxY(), 0)).collect(Collectors.toList()));
            textChunks.removeIf(textChunk -> paper.topMarginContains(textChunk.getMaxY(), 0));

            // 根据页眉线删了，剩余的视为未定义信息
            textChunks.stream().filter(TextChunk::isPageHeader)
                .forEach(textChunk -> textChunk.setPaginationType(PaginationType.UNSPECIFIED));
        }
        if (textChunks.size() != chunkNum) {
            pageHeaderDetected.set(true);
            chunkNum = textChunks.size();
        }

        // 暂时未发现页脚包含有效信息，因此删除
        cleanChunks.addAll(textChunks.stream().filter(TextChunk::isPageFooter).collect(Collectors.toList()));
        textChunks.removeIf(TextChunk::isPageFooter);
        if (footerLine != null) {
            //textChunks.removeIf(textChunk -> paper.bottomMarginContains(textChunk.getMinY(), 0));
            cleanChunks.addAll(textChunks.stream()
                    .filter(textChunk -> paper.bottomMarginContains(textChunk.getMinY(), 0)).collect(Collectors.toList()));
            textChunks.removeAll(cleanChunks);
        } else {
            // 没有页脚线，所以只要疑似相交就要考虑移除
            List<TextChunk> bottomTexts = textChunks.stream().filter(textChunk -> paper.bottomMarginContains(textChunk.getMaxY()))
                    .collect(Collectors.toList());
            List<TextBlock> bottomLines = TextMerger.collectByLines(bottomTexts);
            for (TextBlock line : bottomLines) {
                if (!TableUtils.isPageFooter(line.getText())) {
                    continue;
                }
                cleanChunks.addAll(line.getElements());
                textChunks.removeAll(line.getElements());
            }
        }
        if (textChunks.size() != chunkNum) {
            pageFooterDetected.set(true);
        }
        return cleanChunks;
    }

    private static TextElementsInfo buildTextElements(List<TextChunk> textChunks) {

        List<TextElement> characters = textChunks.parallelStream()
                .flatMap(x -> x.getElements().stream())
                .filter(e -> !e.isDeleted())
                .sorted(Comparator.comparing(TextElement::getGroupIndex))
                .collect(Collectors.toList());

        // TODO 目前 ContentGroupRenderer 是否已经完美地处理重影问题？如果已经做到了，下面的这段代码可以移除
        // TextChunk 的移除可能不彻底，在根据文本的位置进一步处理
        TextElement lastTextElement = null;
        for (TextElement element : characters) {
            if (lastTextElement != null && lastTextElement.isShadowOf(element)) {
                // 前一个字是阴影, 删除
                lastTextElement.markDeleted();
            }
            lastTextElement = element;
        }
        characters.removeIf(TextElement::isDeleted);
        //characters.sort(Comparator.comparing(TextElement::getGroupIndex));

        double minX = Float.MAX_VALUE;
        double minY = Float.MAX_VALUE;
        double maxX = -Float.MAX_VALUE;
        double maxY = -Float.MAX_VALUE;
        float minCharWidth = Float.MAX_VALUE;
        float minCharHeight = Float.MAX_VALUE;
        int totalCharCount = 0;

        float totalCharWidth = .0f;
        float totalCharHeight = .0f;

        for (TextElement ch : characters) {
            if (ch.isWhitespace()) {
                continue;
            }
            ++totalCharCount;
            float textWidth = ch.getTextWidth();
            float textHeight = ch.getTextHeight();

            if (minCharWidth > textWidth) {
                minCharWidth = textWidth;
            }
            if (minCharHeight > textHeight) {
                minCharHeight = textHeight;
            }

            totalCharHeight += ch.getTextHeight();
            totalCharWidth += ch.getTextWidth();

            double left = ch.getMinX();
            double top = ch.getMinY();
            double right = ch.getMaxX();
            double bottom = ch.getMaxY();

            if (minX > left) {
                minX = left;
            }
            if (maxX < right) {
                maxX = right;
            }
            if (minY > top) {
                minY = top;
            }
            if (maxY < bottom) {
                maxY = bottom;
            }
        }


        float avgCharWidth = totalCharCount != 0 ?
                FastMath.max(totalCharWidth / totalCharCount, Page.DEFAULT_MIN_CHAR_WIDTH) : Page.DEFAULT_MIN_CHAR_WIDTH;
        float avgCharHeight = totalCharCount != 0 ?
                FastMath.max( totalCharHeight / totalCharCount, Page.DEFAULT_MIN_CHAR_HEIGHT) : Page.DEFAULT_MIN_CHAR_HEIGHT;

        TextElementsInfo textElementsInfo = new TextElementsInfo();
        textElementsInfo.characters = characters;
        textElementsInfo.totalCharCount = totalCharCount;

        textElementsInfo.textArea = new Rectangle((float)minX, (float)minY, (float)(maxX - minX), (float)(maxY - minY));

        textElementsInfo.minCharWidth = minCharWidth;
        textElementsInfo.minCharHeight = minCharHeight;
        textElementsInfo.avgCharWidth = avgCharWidth;
        textElementsInfo.avgCharHeight = avgCharHeight;

        textElementsInfo.characterIndex = new RectangleSpatialIndex<>();
        textElementsInfo.characterIndex.addAll(characters);

        return textElementsInfo;
    }

    private static void collectClipAreasAndRulings(
            TableExtractParameters params, int pageNumber, List<Rectangle2D> clipAreas, List<Ruling> rulings, ContentGroup group,
            float pageWidth, float pageHeight, ClosureWrapper<PaperParameter> paper,
            boolean isPagination, ClosureBoolean hasHeaderLine, ClosureBoolean hasFooterLine) {
        List<ContentItem> items = group.getItems();
        Rectangle2D clipPath = group.getClipArea();
        if (!isPagination && clipPath != null
                && !(FloatUtils.feq(clipPath.getWidth(), pageWidth, 2)
                && FloatUtils.feq(clipPath.getHeight(), pageHeight, 2))
                && clipPath.getWidth() > 4
                && clipPath.getHeight() > 4) {
            clipAreas.add(clipPath.getBounds2D());
        }

        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                collectClipAreasAndRulings(params, pageNumber, clipAreas, rulings, ((ChildItem) item).getItem(),
                        pageWidth, pageHeight, paper, item.isPagination(), hasHeaderLine, hasFooterLine);
            } else if (item instanceof ImageItem) {
                // TODO 考虑缓存提取和判定的结果，加快速度？
                ImageItem imageItem = (ImageItem)item;
                PDImage image = imageItem.getItem();

                Rectangle2D imageBounds = imageItem.getBounds();
                int width = (int) imageBounds.getWidth();
                int height = (int) imageBounds.getHeight();
                if (width < Ruling.IMAGE_LINE_WIDTH_THRESHOLD || height < Ruling.IMAGE_LINE_WIDTH_THRESHOLD) {
                    // 小图、或者内联图片
                    Ruling r = new Ruling(imageBounds);
                    boolean isDot = width < Ruling.IMAGE_LINE_WIDTH_THRESHOLD && height < Ruling.IMAGE_LINE_WIDTH_THRESHOLD;
                    r.setDrawType(isDot ? Ruling.DrawType.IMAGE_DOT : Ruling.DrawType.IMAGE_LINE);
                    r.setColor(Color.BLACK);
                    r.setFill(true);
                    rulings.add(r);
                } else if (TableUtils.isCellGridImage(image)) {
                    // 这里一般都是 XObject 图片
                    // TODO 但如果是内联图片怎么办？
                    rulings.addAll(TableUtils.extractCellGridImageRulings(imageItem));
                }
            } else if (item instanceof PathItem) {
                PathItem pathItem = (PathItem) item;
                List<PathInfo> pathInfos = pathItem.getPathInfos();
                if (item.isFigure() && (pathInfos.isEmpty()
                                || pathInfos.size() > 1
                                || pathInfos.get(0).getLineCount() > 4)) {
                    // /Figure 时，如果操作较多，高概率就是 PPT 的图画
                    continue;
                }

                TableUtils.DrawPathContext drawPathContext = TableUtils.DrawPathContext.poolDrawPathContext()
                        .setPath(pathItem.getItem())
                        .setPathInfos(pathItem.getPathInfos())
                        .setFill(pathItem.isFill())
                        .setDashed(pathItem.isDashed())
                        .setStrokingColor(pathItem.getColor())
                        .setNonStrokingColor(pathItem.getColor())
                        .setClipPath(clipPath)
                        .setPageNumber(pageNumber)
                        .setParams(params)
                        .setPageSize(pageWidth, pageHeight)
                        .setPaper(paper.get())
                        .setPagination(item.isPagination())
                        .setHeaderState(item.isPageHeader(), hasHeaderLine.get())
                        .setFooterState(item.isPageFooter(), hasFooterLine.get());
                rulings.addAll(TableUtils.pathToRulings(drawPathContext));
                hasHeaderLine.set(drawPathContext.hasHeaderLine);
                hasFooterLine.set(drawPathContext.hasFooterLine);
                paper.set(drawPathContext.paper);
                TableUtils.DrawPathContext.releaseDrawPathContext(drawPathContext);
            }
        }
    }

    // TODO 下面的代码是否需要移动到别处？

    private static final class TextElementsInfo {

        List<TextElement> characters;
        float minCharWidth;
        float minCharHeight;
        int totalCharCount;
        float avgCharWidth;
        float avgCharHeight;

        RectangleSpatialIndex<TextElement> characterIndex;


        Rectangle textArea;
    }

}
