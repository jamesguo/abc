package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.content.ParagraphMerger;
import com.abcft.pdfextract.core.content.TextChunkMerger;
import com.abcft.pdfextract.core.content.TextUtils;
import com.abcft.pdfextract.core.model.*;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.core.util.TrainDataWriter;
import com.abcft.pdfextract.util.ClosureInt;
import com.abcft.pdfextract.util.DummyGraphics2D;
import com.abcft.pdfextract.util.FloatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.fontbox.util.BoundingBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.*;
import java.awt.geom.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/11/7.
 */
public class ContentGroupRenderer extends PDFRenderer {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final boolean DEBUG = false;
    private static final long PAGE_PROCESS_TIMEOUT = 60L * 1000L;

    private final AtomicLong elementId = new AtomicLong();

    /**
     * Creates a new PDFRenderer.
     *
     * @param document the document to render
     */
    public ContentGroupRenderer(PDDocument document) {
        super(document);
    }

    private PageStructTree collectStructTreeInfo(PDDocument document, PDPage page) {
        PDStructureTreeRoot root = getStructureTreeRoot(document);
        if (root == null)
            return null;
        PageStructTree structTree = new PageStructTree(root, page, elementId);

        PDNumberTreeNode parentTree = root.getParentTree();
        // int structParent = page.getStructParents();
        int structParent = page.getCOSObject().getInt(COSName.STRUCT_PARENTS, -1);
        boolean collected = false;
        if (parentTree != null && structParent >= 0) {
            try {
                collected = collectStructureNode(parentTree, structParent, structTree, structTree.tree);
                if (!collected) {
                    structTree.clear();
                    LOGGER.warn("Failed to collect struct node from parentTree; this might be a merged PDF.");
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to collect struct node from parentTree.", e);
                structTree.clear();
                collected = false;
            }
        }
        if (!collected) {
            collectStructureNode(page, root, structTree, structTree.tree);
        }
        return structTree;
    }

    private boolean collectStructureNode(PDNumberTreeNode parentTree, int structParent, PageStructTree structTree, TextNode textNode) {
        COSArray namesArray = (COSArray) parentTree.getCOSObject().getDictionaryObject(COSName.NUMS);
        COSArray leafs = null;
        if (namesArray != null && namesArray.size() > 0) {
            leafs = getStructNode(namesArray, structParent);
        }
        List<PDNumberTreeNode> kids = parentTree.getKids();
        if (null == leafs && kids != null) {
            COSArray mergedChildren = null;
            boolean fixed = parentTree.getCOSObject().getBoolean(COSName.getPDFName("Fixed"), false);
            for (PDNumberTreeNode child : parentTree.getKids()) {
                Integer min = child.getLowerLimit();
                Integer max = child.getUpperLimit();
                if (!fixed && (mergedChildren != null || null == min || null == max || min < 0 || max < 0)) {
                    // Children might not incomplete, merge then together
                    if (null == mergedChildren) {
                        LOGGER.warn("Bad kids for {}", parentTree);
                        mergedChildren = new COSArray();
                    }
                    COSArray numbersArray = (COSArray)child.getCOSObject().getDictionaryObject( COSName.NUMS );
                    if( numbersArray != null && numbersArray.size() > 0 ) {
                        mergedChildren.addAll(numbersArray);
                    }
                } else if (null == min || null == max
                    || (min >= 0 && max >= 0 && min <= structParent && structParent <= max)) {
                    return collectStructureNode(child, structParent, structTree, textNode);
                }
            }
            if (mergedChildren != null) {
                fixParentNode(parentTree, mergedChildren);
                leafs = getStructNode(mergedChildren, structParent);
            }
        }
        if (null == leafs) {
            return false;
        }
        Map<Integer, PDStructureElement> map = structTree.mcidMap;
        AtomicLong elementId = structTree.elementId;
        PDPage page = structTree.page;
        int pageNumber = document.getPages().indexOf(page) + 1;
        List<PDStructureElement> leafNodes = convertToNodes(leafs);
        Map<String, TextNode> nodeMap = new HashMap<>();
        nodeMap.put("Root", textNode);
        int matchCount = 0;
        for (PDStructureElement leafElement : leafNodes) {
            ensureElementId(elementId, leafElement);
            if (null == leafElement.getPage()) {
                leafElement.setPage(page);
            } else if(!page.equals(leafElement.getPage())) {
                // 多个 PDF 合并，StructParent 可能重复
                continue;
            }
            ++matchCount;
            TextNode leafText = new TextNode(leafElement);
            buildMcidMap(map, leafElement);
            nodeMap.put(leafElement.getElementIdentifier(), leafText);

            TextNode currentText = leafText;
            PDStructureElement parentElement;
            PDStructureNode parentNode = leafElement.getParent();
            while(parentNode != null) {
                String nodeKey;
                PDStructureNode node = parentNode;
                if (parentNode instanceof PDStructureElement) {
                    parentElement = (PDStructureElement)parentNode;
                    ensureElementId(elementId, parentElement);
                    nodeKey = parentElement.getElementIdentifier();
                    if (null == parentElement.getPage()) {
                        parentElement.setPage(page);
                    }
                } else {
                    parentElement = null;
                    nodeKey = "Root";
                }
                TextNode parentText;
                if (!nodeMap.containsKey(nodeKey)) {
                    parentText = new TextNode(node);
                    nodeMap.put(nodeKey, parentText);
                } else {
                    parentText = nodeMap.get(nodeKey);
                }
                if (null == currentText.getParent()) {
                    parentText.addChild(currentText);
                }
                currentText = parentText;

                if (parentElement != null) {
                    parentNode = parentElement.getParent();
                } else {
                    parentNode = null;
                }
            }
            if (!StringUtils.equalsIgnoreCase(currentText.getStructureType(), "Root")) {
                LOGGER.warn("Page {}: structure info mismatched, incomplete or broken",
                        pageNumber);
                structTree.tree.setBroken(true);
            }
        }
        if (matchCount > 0) {
            structTree.tree.fixChildrenOrder(structTree.page);
        }
        return matchCount > 0;
    }

    private void fixParentNode(PDNumberTreeNode parentTree, COSArray mergedChildren) {
        LOGGER.info("Fixing kids for {}...", parentTree);
        COSDictionary parentNode = parentTree.getCOSObject();
        parentNode.setItem(COSName.NUMS, mergedChildren);
        parentNode.setBoolean(COSName.getPDFName("Fixed"), true);

        if (mergedChildren.size() >= 2 && mergedChildren.getObject(0) instanceof COSInteger
                && mergedChildren.getObject(mergedChildren.size() - 2) instanceof COSInteger) {
            COSArray limits = new COSArray();
            limits.add(mergedChildren.getObject(0));
            limits.add(mergedChildren.getObject(mergedChildren.size() - 2));
            parentNode.setItem(COSName.LIMITS, limits);
        }
    }


    private static COSArray getStructNode(COSArray namesArray, int structParent)
    {
        if( namesArray != null && namesArray.size() > 0 )
        {
            for( int i = 0; i + 1 < namesArray.size(); i+=2 )
            {
                COSInteger key = (COSInteger)namesArray.getObject(i);
                if (key.intValue() != structParent)
                    continue;
                COSBase cosValue = namesArray.getObject( i+1 );
                if (cosValue instanceof COSArray) {
                    return (COSArray) cosValue;
                }
            }
        }
        return null;
    }

    private void buildMcidMap(Map<Integer, PDStructureElement> map, PDStructureElement structElement) {
        List<Object> mcids = structElement.getKids();
        for (Object mcid : mcids) {
            if (mcid instanceof Integer) {
                map.put((Integer) mcid, structElement);
            }
        }
    }

    private List<PDStructureElement> convertToNodes(COSArray leafs) {
        List<PDStructureElement> nodes = new ArrayList<>(leafs.size());
        for (int i = 0; i < leafs.size(); ++i) {
            COSBase item = leafs.getObject(i);
            if (null == item)
                continue;
            if (!(item instanceof COSDictionary))
                continue;
            // 某些情况下, leafs拿出来的item可能会重复
            if (nodes.stream().anyMatch(node -> node.getCOSObject().equals(item))) {
                continue;
            }
            nodes.add((PDStructureElement) PDStructureElement.create((COSDictionary) item));
        }
        return nodes;
    }


    private PDStructureTreeRoot getStructureTreeRoot(PDDocument document) {
        PDDocumentCatalog catalog  = document.getDocumentCatalog();
        if (catalog == null) {
            return null;
        }
        PDStructureTreeRoot root = catalog.getStructureTreeRoot();
        if (root == null) {
            return null;
        }
        return root;
    }

    private boolean collectStructureNode(PDPage page, PDStructureNode node, PageStructTree structTree, TextNode textNode) {
        Map<Integer, PDStructureElement> map = structTree.mcidMap;
        AtomicLong elementId = structTree.elementId;
        boolean matchedPage = false;
        if (node instanceof PDStructureElement) {
            PDStructureElement element = (PDStructureElement) node;
            PDPage pg = element.getPage();
            if (pg != null && pg.equals(page)) {
                ensureElementId(elementId, element);
                matchedPage = true;
                buildMcidMap(map, element);
            } else if (null == pg) {
                matchedPage = true;
            }
        }
        List<Object> kids = node.getKids();
        for (Object kid : kids) {
            if (kid instanceof PDStructureNode) {
                PDStructureNode childStruct = (PDStructureNode) kid;
                TextNode childTextNode = new TextNode(childStruct);
                if(collectStructureNode(page, childStruct, structTree, childTextNode)) {
                    textNode.addChild(childTextNode);
                    matchedPage = true;
                }
            }
        }
        return matchedPage;
    }

    private void ensureElementId(AtomicLong elementId, PDStructureElement element) {
        if (null == element.getElementIdentifier()) {
            element.setElementIdentifier("ABCFT_AutoElem." + String.valueOf(elementId.incrementAndGet()));
        }
    }

    private void buildTextTree(TextTree tree, PDDocument document, PDPage page, ContentGroup contentGroup) {
        if (tree == null) {
            return;
        }
        // 获取所有的文字, 包括隐藏的, 因为有时候一个空的TD里面的文字是不可见的, 但是这个信息也很重要
        List<TextChunk> textChunks = contentGroup.getAllTextChunks(null);
        collectTextChunks(page, tree, textChunks);
//        tree.removeEmptyNodes();
        tree.setNoStructTextChunk(textChunks);
        boolean hasFigure = tree.findByStructureType("Figure").stream().anyMatch(node -> !node.isEmpty());
        tree.setHasFigure(hasFigure);
        tree.setPdfVersion(document.getVersion());
        PDDocumentInformation information = ExtractorUtil.getPdfDocumentInformation(document);
        if (information != null) {
            tree.setProducer(information.getProducer());
        }
        contentGroup.setTextTree(tree);
    }


    private static void collectTextChunks(PDPage page, TextNode textNode, List<TextChunk> textChunks) {
        List<TextNode> children = textNode.getChildren();
        for (TextNode child : children) {
            PDStructureNode node = child.getStructureNode();
            if (!(node instanceof PDStructureElement)) {
                continue;
            }
            PDStructureElement structElement = (PDStructureElement)node;
            if (page.equals(structElement.getPage())) {
                List<Object> kids = node.getKids();
                for (Object kid : kids) {
                    if (kid instanceof Integer) {
                        textChunks.stream()
                                .filter(textChunk -> textChunk.getMcid() == (Integer) kid)
                                .forEach(child::addTextChunk);
                        textChunks.removeAll(child.getTextChunks());
                    }
                }
            }
            collectTextChunks(page, child, textChunks);
        }
    }

    /*
    private static TextNode processStructureNode(PDPage page, PDStructureNode node, List<TextChunk> textChunks) {
        TextNode textNode = new TextNode(node);
        List<Object> kids = node.getKids();
        for (Object kid : kids) {
            if (kid instanceof Integer) {
                textChunks.stream()
                        .filter(textChunk -> textChunk.getMcid() == (Integer) kid)
                        .forEach(textNode::addTextChunk);
                textChunks.removeAll(textNode.getTextChunks());
            } else if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;
                PDPage pg = element.getPage();
                if (pg == null || pg.equals(page)) {
                    textNode.addChild(processStructureNode(page, (PDStructureNode) kid, textChunks));
                }
            }
        }
        return textNode;
    }
    */

    public static AffineTransform buildPageTransform(PDPage page, float scale) {
        AffineTransform pageTransform = new AffineTransform();
        pageTransform.scale(scale, scale);
        int rotationAngle = page.getRotation();
        PDRectangle cropBox = page.getCropBox();

        if (rotationAngle != 0) {
            float translateX = 0;
            float translateY = 0;
            switch (rotationAngle) {
                case 90:
                    translateX = cropBox.getHeight();
                    break;
                case 270:
                    translateY = cropBox.getWidth();
                    break;
                case 180:
                    translateX = cropBox.getWidth();
                    translateY = cropBox.getHeight();
                    break;
                default:
                    break;
            }
            pageTransform.translate(translateX, translateY);
            pageTransform.rotate((float) Math.toRadians(rotationAngle));
        }
        pageTransform.translate(0, cropBox.getHeight());
        pageTransform.scale(1, -1);

        // adjust for non-(0,0) crop box
        pageTransform.translate(-cropBox.getLowerLeftX(), -cropBox.getLowerLeftY());
        return pageTransform;
    }


    public ContentGroup processPage(PDPage page, Map<String, String> pageParams) throws TimeoutException {
        try {
            // 我们实际上不会使用render出来的图片, 直接使用DummyGraphics2D代替, 避免分配内存
            Graphics2D graphics = DummyGraphics2D.INSTANCE;
            boolean enableOCR = pageParams != null && "true".equals(pageParams.get(Tags.PARAM_ENABLE_OCR));
            if (enableOCR) {
                FontUtils.enableFixFontsUnicodeMap(page.getResources());
            }
            ExtractorUtil.pdfPreCheckFonts(document, page);
            PageDrawerParameters parameters = new PageDrawerParameters(this, page);
            PageStructTree structTree = collectStructTreeInfo(document, page);
            int pageIndex = document.getPages().indexOf(page);
            ContentGroupPageDrawer drawer = new ContentGroupPageDrawer(parameters,
                    structTree != null ? structTree.mcidMap : null, pageIndex);
            drawer.drawPage(graphics, page.getCropBox());
            ExtractorUtil.pdfPostCheckFonts(document, page);
            ContentGroup contentGroup = drawer.pageContentGroup;
            if (structTree != null) {
                buildTextTree(structTree.tree, document, page, drawer.pageContentGroup);
            }
            detectPaginationFrame(contentGroup, (PaperParameter) contentGroup.getTag(Tags.PAPER));
            return contentGroup;
        } catch (InterruptedIOException e) {
            throw new TimeoutException(e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("processPage failed", e);
            return null;
        }
    }

    private static final float MIN_EDGE = 5.0f;

    private static float boundedMax(double f1, double f2, float bound) {
        return (float) (f2 < bound ? f2 : f1);
    }

    private static float boundedMin(double f1, double f2, float bound) {
        return (float) (f1 > bound ? f1 : f2);
    }

    private void detectPaginationFrame(ContentGroup contentGroup, PaperParameter paper) {
        if (null == paper) {
            return;
        }
        List<PathItem> pathItems = contentGroup.getAllPathItems();
        pathItems.sort(Comparator.comparingDouble(o -> o.getBounds().getMinY()));
        double pageWidth = contentGroup.getArea().getWidth();
        double pageHeight = contentGroup.getArea().getHeight();

        // 主内容边框 根据经验总结的内容区域，不会在此区域内查找页眉页脚线
        float contentLeft = paper.leftMarginInPt;
        float contentTop = paper.topMarginInPt;
        float contentRight = (float) (pageWidth - paper.rightMarginInPt);
        float contentBottom = (float) (pageHeight - paper.bottomMarginInPt);

        // 页边缘边框 某项线、填充区可能绘制到了页面边缘 因此不会在边缘附近查找页眉页脚线
        float edgeRight = (float) (pageWidth - MIN_EDGE);
        float edgeBottom = (float) (pageHeight - MIN_EDGE);

        // 页眉页脚区的边线，以尽可能靠近主内容边框为主
        Float leftWing = null;
        Float headerTop = null;
        Float rightWing = null;
        Float footerBottom = null;

        double minTopBottomLineWidth = pageWidth * .5;
        double minLeftRightLineHeight = pageHeight * .5;

        for (PathItem pathItem : pathItems) {
            Rectangle2D line = pathItem.getBounds();
            double l = line.getMinX(), t = line.getMinY(),
                    r = line.getMaxX(), b = line.getMaxY();
            if (contentLeft < l && contentTop < t && r < contentRight && b < contentBottom)
                continue; // 如果在页边框内，不需要再搜索了
            double lineWidth = line.getWidth();
            double lineHeight = line.getHeight();

            // 和页面大小几乎一样的框线
            if (FloatUtils.feq(lineWidth, pageWidth, 2.) && FloatUtils.feq(lineHeight, pageHeight, 2.))
                continue;

            if (lineWidth < minTopBottomLineWidth && lineHeight < minLeftRightLineHeight)
                continue; // 太短小的线不考虑

            if (null == leftWing && lineHeight >= minLeftRightLineHeight
                    && (MIN_EDGE < l || r < contentLeft) && l < contentLeft) {
                leftWing = boundedMax(l, r, contentLeft);
            }
            // 页眉线 要求类似线的形状
            if (null == headerTop && lineWidth >= minTopBottomLineWidth && lineHeight < MIN_EDGE
                    & (MIN_EDGE < t || b < contentTop) && t < contentTop) {
                headerTop = boundedMax(t, b, contentTop);
            }
            if (null == rightWing && lineHeight >= minLeftRightLineHeight && contentRight < r && r < edgeRight) {
                rightWing = boundedMin(l, r, contentRight);
            }
            if (null == footerBottom && lineWidth >= minTopBottomLineWidth && contentBottom < b) {
                footerBottom = boundedMin(t, b, contentBottom);
            }
            if (leftWing != null && headerTop != null && rightWing != null && footerBottom != null) {
                break;
            }
        }
        List<TextChunk> textChunks = contentGroup.getAllTextChunks();
        if (null == leftWing) {
            leftWing = contentLeft;
        } else {
            contentGroup.addTag(Tags.PAGINATION_LEFT_LINE, leftWing);
        }
        if (null == headerTop) {
            TextChunk header = textChunks.stream().filter(TextChunk::isPageHeader)
                    .filter(TextChunk::isHorizontal)
                    .max(Comparator.comparingDouble(TextChunk::getBottom)).orElse(null);
            if (header != null && header.getBottom() < contentTop * 1.5) {
                // 已经有页眉了, 直接设置
                headerTop = header.getBottom() + 0.1f;
            } else {
                List<TextChunk> merged = ParagraphMerger.mergeToLine(new TextChunkMerger().merge(textChunks));
                TextChunk topText = merged.stream().min(Comparator.comparingDouble(TextChunk::getTop))
                        .orElse(null);
                List<PathItem> tempPathItems = pathItems.stream()
                        .filter(pathItem -> pathItem.getBounds().getMinY() > 0)
                        .collect(Collectors.toList());
                if (topText != null && TextUtils.isParagraphHeader(topText.getText())
                        && FloatUtils.flte(TextUtils.getNoneBlankChineseTextCount(topText.getText()), 25., 6.)
                        && FloatUtils.flte(topText.getBottom(), contentTop, 30.)
                        && !tempPathItems.isEmpty()
                        && FloatUtils.flte(topText.getTop(), tempPathItems.get(0).getBounds().getMinY(), 1.5 * topText.getHeight())
                    ) {
                    headerTop = topText.getBottom() + 0.1f;
                } else {
                    headerTop = contentTop;
                }
            }
        } else {
            contentGroup.addTag(Tags.PAGINATION_TOP_LINE, headerTop);
        }
        if (null == rightWing) {
            rightWing = contentRight;
        } else {
            contentGroup.addTag(Tags.PAGINATION_RIGHT_LINE, rightWing);
        }
        if (null == footerBottom) {
            // 没有页脚线, 通过文字来判断
            TextChunk footer = textChunks.stream().filter(TextChunk::isPageFooter)
                    .filter(TextChunk::isHorizontal)
                    .min(Comparator.comparingDouble(TextChunk::getTop)).orElse(null);
            if (footer != null && (pageHeight - footer.getTop()) < paper.bottomMarginInPt * 1.5) {
                // 已经有页脚了, 直接设置
                footerBottom = footer.getTop() - 0.1f;
            } else {
                List<TextChunk> merged = ParagraphMerger.mergeToLine(new TextChunkMerger().merge(textChunks));
                TextChunk bottomText = merged.stream().max(Comparator.comparingDouble(TextChunk::getBottom))
                        .orElse(null);
                if (bottomText != null && TextUtils.isParagraphFooter(bottomText.getText())
                        && FloatUtils.fgte(bottomText.getTop(), contentBottom, 15.)
                        && FloatUtils.flte(TextUtils.getNoneBlankChineseTextCount(bottomText.getText()), 25., 6.)
                        && !pathItems.isEmpty()
                        && FloatUtils.fgte(bottomText.getTop(), pathItems.get(pathItems.size() - 1).getBounds().getMaxY(), 1.5 * bottomText.getHeight())) {
                    footerBottom = bottomText.getTop() - 0.1f;
                } else {
                    footerBottom = contentBottom;
                }
            }
        } else {
            contentGroup.addTag(Tags.PAGINATION_BOTTOM_LINE, footerBottom);
        }

        Rectangle paginationFrame = new Rectangle(leftWing, headerTop,
                rightWing - leftWing, footerBottom - headerTop);

        List<TextChunk> textChunkList = new ArrayList<>();
        contentGroup.forEachRecursive(TextChunkItem.class, textChunkItem -> {
            TextChunk textChunk = textChunkItem.getItem();
            if (!textChunk.isVisible() || textChunk.isPagination()) {
                return;
            }

            if (FloatUtils.flte(textChunk.getBottom(), paginationFrame.getTop(), 5.)
                    && (textChunk.isHorizontal() || textChunk.getDirection() == TextDirection.UNKNOWN)) {
                textChunkList.add(textChunk);
            } else if (FloatUtils.fgte(textChunk.getTop(), paginationFrame.getBottom(),3.)
                    && (textChunk.isHorizontal() || textChunk.getDirection() == TextDirection.UNKNOWN)) {
                textChunkList.add(textChunk);
            } else if (textChunk.getRight() < paginationFrame.getMinX() && textChunk.isVertical()) {
                // 设置左侧翼属性
                textChunk.setPaginationType(PaginationType.LEFT_WING);
            } else if (textChunk.getLeft() > paginationFrame.getMaxX() && textChunk.isVertical()) {
                // 设置右侧翼属性
                textChunk.setPaginationType(PaginationType.RIGHT_WING);
            }
        });

        List<TextChunk> tempTextChunks = new TextChunkMerger().merge(textChunkList);
        tempTextChunks.removeIf(textChunk -> !textChunk.isHorizontal());
        List<TextChunk> lineChunks = ParagraphMerger.mergeToLine(tempTextChunks);
        // 设置页眉属性
        lineChunks.stream()
                .filter(textChunk -> FloatUtils.flte(textChunk.getBottom(), paginationFrame.getTop(), 5.))
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrLabel(textChunk.getText()))
                .filter(textChunk -> !TextUtils.isSpetialTable(textChunk.getText()))
                .filter(textChunk -> !TrainDataWriter.CHART_BEGINNING_KEYWORD_RE.matcher(textChunk.getText()).find())
                .filter(textChunk -> FloatUtils.flte(TextUtils.getNoneBlankChineseTextCount(textChunk.getText()), 25., 6.))
                .filter(textChunk -> TextUtils.isParagraphHeader(textChunk.getText()))
                .forEach(textChunk -> {
                    for (TextChunk chunk : textChunkList) {
                        if (textChunk.contains(chunk)) {
                            chunk.setPaginationType(PaginationType.HEADER);
                        }
                    }
                });

        // 设置页脚属性
        lineChunks.stream()
                .filter(textChunk -> FloatUtils.fgte(textChunk.getTop(), paginationFrame.getBottom(),3.))
                .filter(textChunk -> !TextUtils.isDigitalAndBlankOrLabel(textChunk.getText()))
                .filter(textChunk -> FloatUtils.flte(TextUtils.getNoneBlankChineseTextCount(textChunk.getText()), 25., 6.))
                .filter(textChunk -> TextUtils.isParagraphFooter(textChunk.getText()))
                .forEach(textChunk -> {
                    for (TextChunk chunk : textChunkList) {
                        if (textChunk.contains(chunk)) {
                            chunk.setPaginationType(PaginationType.FOOTER);
                        }
                    }
                });
        contentGroup.addTag(Tags.PAGINATION_FRAME, paginationFrame);
    }

    private static final class PageStructTree {

        final Map<Integer, PDStructureElement> mcidMap = new HashMap<>();
        final PDPage page;
        final TextTree tree;
        final AtomicLong elementId;

        PageStructTree(PDStructureTreeRoot rootNode, PDPage page, AtomicLong elementId) {
            this.tree = new TextTree(rootNode);
            this.page = page;
            this.elementId = elementId;
        }

        void clear() {
            this.tree.clear();
        }
    }

    private static class ContentGroupPageDrawer extends PageDrawer {

        private static final Logger LOGGER = LogManager.getLogger();
        private final Map<Integer, PDStructureElement> structTreeInfo;
        private final int pageIndex;
        private Stack<Operator> operatorStack = new Stack<>();
        private ContentGroup pageContentGroup;
        private ContentGroup currentContentGroup;
        private boolean inForm = false;
        private boolean inPDTilingPattern = false;
        private int textGroup = 0;
        private int pageRotation;
        private PDRectangle pageSize;

        private long startTime;
        private AffineTransform pageTransform;
        private float pageWidth;
        private float pageHeight;
        private TextChunk lastTextChunk;
        // IDE 检测到 textElements 看起来没有被用过？这里暂且注释掉
        // private List<TextElement> textElements;
        private float pageScale = 1;
        private final Stack<PDMarkedContent> currentMarkedContents = new Stack<>();
        private boolean clipping;
        private boolean fillAndStroke;
        private int glyphCount;

        private PathInfoRecorder currentPathInfo = new PathInfoRecorder();

        /**
         * Constructor.
         *
         * @param parameters Parameters for page drawing.
         * @param structTreeInfo
         * @throws IOException If there is an error loading properties from the file.
         */
        public ContentGroupPageDrawer(PageDrawerParameters parameters,
                                      Map<Integer, PDStructureElement> structTreeInfo, int pageIndex) throws IOException {
            super(parameters);
            addOperator(new BeginMarkedContentSequenceWithProperties());
            addOperator(new BeginMarkedContentSequence());
            addOperator(new EndMarkedContentSequence());
            this.structTreeInfo = structTreeInfo;
            this.pageIndex = pageIndex;
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            this.pageRotation = page.getRotation();
            this.pageSize = page.getCropBox();
            // calculate page transform
            this.pageTransform = new AffineTransform();
            //this.textElements = new ArrayList<>();

            if (Math.abs(pageRotation) == 90 || Math.abs(pageRotation) == 270) {
                this.pageWidth = pageSize.getHeight();
                this.pageHeight = pageSize.getWidth();
            } else {
                this.pageHeight = pageSize.getHeight();
                this.pageWidth = pageSize.getWidth();
            }
            this.pageTransform = buildPageTransform(page, pageScale);

            textGroup = 0;
            PDGraphicsState graphicsState = new PDGraphicsState(page.getCropBox());
            currentContentGroup = pageContentGroup = new ContentGroup(graphicsState, pageTransform);
            PaperParameter paper = PaperParameter.findPage(pageWidth, pageHeight, pageWidth < pageHeight);
            currentContentGroup.addTag(Tags.PAPER, paper);
            pageContentGroup.setArea(pageTransform.createTransformedShape(
                    new Rectangle2D.Float(pageSize.getLowerLeftX(), pageSize.getLowerLeftY(),
                            pageSize.getWidth(), pageSize.getHeight())).getBounds2D());

            startTime = System.currentTimeMillis();
            int actualTextCount = 0;
            if (structTreeInfo != null) {
                actualTextCount = (int) structTreeInfo.values().stream()
                        .filter(item -> StringUtils.isNotEmpty(item.getActualText()))
                        .count();
            }
            if (ExtractorUtil.isPainting(page, actualTextCount)) {
                LOGGER.warn("Page #{}: too many XObjects in page, might be a painting or cover.", pageIndex+1);
            } else {
                super.processPage(page);
            }

            pageContentGroup.setGlyphCount(glyphCount);
            pageContentGroup.end();
        }

        @Override
        public void showAnnotation(PDAnnotation annotation) {
            // ignore
        }

        @Override
        protected void processStreamOperators(PDContentStream contentStream) throws IOException {
            if (contentStream instanceof PDFormXObject) {
                onContentGroupBegin();
                PDFormXObject form = (PDFormXObject) contentStream;
                PDRectangle bbox = form.getBBox();
                Rectangle2D area = bbox.transform(getGraphicsState().getCurrentTransformationMatrix()).getBounds2D();
                currentContentGroup.setForm(form, area);
                inForm = true;
                if (ExtractorUtil.isPainting(form)) {
                    LOGGER.warn("Form {}: too many XObjects, might be a paining form", form.getName());
                    onContentGroupEnd();
                    inForm = false;
                    return;
                }
            } else if (contentStream instanceof PDTilingPattern) {
                inPDTilingPattern = true;
            }

            super.processStreamOperators(contentStream);
            if (inForm) {
                onContentGroupEnd();
                inForm = false;
            } else if (inPDTilingPattern) {
                inPDTilingPattern = false;
            }
        }

        @Override
        protected void processOperator(Operator operator, java.util.List<COSBase> operands) throws IOException {
            // 计算当前解析时间 如果处于 release 运行状态且运行时间超过一分钟 则抛异常
            long endTime = System.currentTimeMillis();
            if (!DEBUG && endTime - startTime > PAGE_PROCESS_TIMEOUT) {
                LOGGER.info("Page process time exceeded {} ms.", PAGE_PROCESS_TIMEOUT);
                throw new InterruptedIOException(String.format("Page process time exceeded %,d ms", PAGE_PROCESS_TIMEOUT));
            }
            String opName = operator.getName();
            if (PDFOperator.BEGIN_GROUP.contains(opName) && !inPDTilingPattern) {
                // group begin
                onContentGroupBegin();
            }

            operatorStack.push(operator);
            super.processOperator(operator, operands);
            operatorStack.pop();

            // 存在多个嵌套的 Do 操作
            if (operatorStack.isEmpty() || operatorStack.peek().getName().equals(PDFOperator.DRAW_OBJECT)) {
                // operatorStack不为空表示正在处理子命令, 忽略, 比如TD会被拆成TL, Td来执行
                if (!inPDTilingPattern) {
                    currentContentGroup.addOperator(operator, operands, getGraphicsState());
                }
            }

            if (PDFOperator.END_GROUP.contains(opName)  && !inPDTilingPattern) {
                // group end
                onContentGroupEnd();
            }

            //if (inText && operator.getName().equalsIgnoreCase("TJ")) {
            if (operator.getName().equalsIgnoreCase("TJ")) {
                textGroup++;
            }
        }

        private void onContentGroupBegin() {
            ContentGroup contentGroup = new ContentGroup(getGraphicsState(), pageTransform);
            currentContentGroup.add(contentGroup);
            currentContentGroup = contentGroup;
        }

        private void onContentGroupEnd() {
            currentContentGroup.end();
            currentContentGroup = currentContentGroup.getParent();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            super.appendRectangle(p0, p1, p2, p3);
            currentPathInfo.appendRectangle(p0, p1, p2, p3);
        }

        @Override
        public void moveTo(float x, float y) {
            super.moveTo(x, y);
            currentPathInfo.moveTo(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            super.lineTo(x, y);
            currentPathInfo.lineTo(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            super.curveTo(x1, y1, x2, y2, x3, y3);
            currentPathInfo.curveTo(x1, y1, x2, y2, x3, y3);
        }

        @Override
        public void clip(int windingRule) {
            super.clip(windingRule);
            clipping = windingRule != -1;
        }

        @Override
        public void closePath() {
            super.closePath();
            currentPathInfo.closePath();
        }

        @Override
        public void endPath() {
            super.endPath();
            currentPathInfo.endPath(pageTransform);

            if (clipping) {
                Shape clippingPath = getGraphicsState().getCurrentClippingPath();
                Shape transformedClippingPath = pageTransform.createTransformedShape(clippingPath);
                currentContentGroup.setClipArea(transformedClippingPath.getBounds2D());
                clipping = false;
            }
        }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException {
            fillAndStroke = true;
            super.fillAndStrokePath(windingRule);
            fillAndStroke = false;
        }

        @Override
        public void fillPath(int windingRule) {
            if (inPDTilingPattern) {
                return;
            }
            GeneralPath path = getLinePath();

            List<PathInfo> pathInfos;
            if (fillAndStroke) {
                pathInfos = currentPathInfo.fillFirst(pageTransform);
            } else {
                pathInfos = currentPathInfo.fillPath(pageTransform);
            }
            path.setWindingRule(windingRule);
            GeneralPath displayPath = new GeneralPath(pageTransform.createTransformedShape(path));
            PathItem item = currentContentGroup.fillPath(displayPath, getGraphicsState(),
                    GraphicsUtil.getColor(getGraphicsState().getNonStrokingColor(), this));
            item.setMarkedContent(getCurrentMarkedContent());
            item.setPathInfos(pathInfos);
            path.reset();
        }

        @Override
        public void shadingFill(COSName shadingName) throws IOException {
            PDShading shading = getResources().getShading(shadingName);
            if (shading == null) {
                return;
            }
            // get the transformed BBox and intersect with current clipping path
            // need to do it here and not in shading getRaster() because it may have been rotated
            PDRectangle bbox = shading.getBBox();
            GeneralPath displayPath;
            if (bbox != null) {
                Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                Rectangle2D bboxArea = bbox.transform(ctm).getBounds2D();
                Rectangle2D.intersect(bboxArea, getGraphicsState().getCurrentClippingPath().getBounds2D(), bboxArea);
                displayPath = new GeneralPath(pageTransform.createTransformedShape(bboxArea));
            } else {
                displayPath = new GeneralPath(pageTransform.createTransformedShape(getGraphicsState().getCurrentClippingPath()));
            }
            Color color = GraphicsUtil.getShadingColor(shadingName, shading, getGraphicsState());
            PathItem item = currentContentGroup.fillPath(displayPath, getGraphicsState(), color);
            item.setShadingName(shadingName.getName());
            item.setMarkedContent(getCurrentMarkedContent());
        }

        @Override
        public void strokePath() {
            if (inPDTilingPattern) {
                return;
            }
            GeneralPath path = getLinePath();
            List<PathInfo> pathInfos;
            if (fillAndStroke) {
                pathInfos = currentPathInfo.thenStroke(pageTransform);
            } else {
                pathInfos = currentPathInfo.strokePath(pageTransform);
            }
            GeneralPath displayPath = new GeneralPath(pageTransform.createTransformedShape(path));
            PathItem item = currentContentGroup.strokePath(displayPath, getGraphicsState(),
                    GraphicsUtil.getColor(getGraphicsState().getStrokingColor(), this));
            item.setMarkedContent(getCurrentMarkedContent());
            item.setPathInfos(pathInfos);
            path.reset();
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode, Vector displacement) {
            TextElement element;
            try {
                ++glyphCount;
                element = createTextElement(textRenderingMatrix, font, code, unicode, displacement);
                if (element == null) {
                    return;
                }
            } catch (Exception e) {
                LOGGER.warn("createTextElement failed", e);
                return;
            }
            element.setGroupIndex(textGroup);
            if (lastTextChunk != null) {
                // 处理阴影
                lastTextChunk.mergeShadow(element);
            }
            TextChunkItem item = currentContentGroup.showGlyph(element);
            lastTextChunk = item.getItem();
            item.setMarkedContent(getCurrentMarkedContent());

            // textElements.add(element);
            // 忽略不可见的文字
            if (element.getWidth() < 1 || element.getHeight() < 1) {
                element.setHidden(true);
                return;
            }
            float halfWidth = (float) element.getWidth();
            float halfHeight = (float) element.getHeight();
            Rectangle2D clipBounds = pageTransform.createTransformedShape(getGraphicsState().getCurrentClippingPath()).getBounds2D();
            GraphicsUtil.extendRect(clipBounds, halfWidth, halfHeight, halfWidth, halfHeight); // 稍微扩大下裁剪区域
            if (!GraphicsUtil.contains(clipBounds, element)) {
                element.setHidden(true);
            }
        }

        @Override
        public void drawImage(PDImage pdImage) {
            if (inPDTilingPattern) {
                return;
            }

            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Rectangle2D imageBounds = new Rectangle2D.Float(ctm.getTranslateX(), ctm.getTranslateY(),
                    ctm.getScalingFactorX(), ctm.getScalingFactorY());
            imageBounds = GraphicsUtil.normalize(imageBounds);
            imageBounds = pageTransform.createTransformedShape(imageBounds).getBounds2D();

            // 保存完整的映射矩阵
            int width = pdImage.getWidth();
            int height = pdImage.getHeight();
            AffineTransform imageTransform = new AffineTransform(ctm.createAffineTransform());
            imageTransform.scale(1.0 / width, -1.0 / height);
            imageTransform.translate(0, -height);
            imageTransform.preConcatenate(pageTransform);

            PDStructureElement structureElement = getCurrentStructureElement();
            if (structureElement != null && StringUtils.isNotEmpty(structureElement.getActualText())) {
                textGroup++;
                // 如果图片包含了ActualText, 则认为这是一个文字图片, 这里构造一个TextChunkItem,
                // 示例: hb_id = 12714199, http://121.40.131.65:8080/documents/hb/12714199/text
                String actualText = structureElement.getActualText();
                TextElement textElement = new TextElement(imageBounds, imageBounds, imageBounds,
                        0, actualText, null, null, getGraphicsState().getTextState().getFontSize(),
                        0, Color.BLACK, 0);
                textElement.setGroupIndex(textGroup);
                TextChunk textChunk = new TextChunk(textElement);
                textChunk.setMcid(getCurrentMarkedContent().getMCID());
                TextChunkItem item = currentContentGroup.addTextChunk(textChunk);
                item.setMarkedContent(getCurrentMarkedContent());
            } else {
                ImageItem item = currentContentGroup.drawImage(pdImage, imageBounds, imageTransform);
                item.setMarkedContent(getCurrentMarkedContent());
            }
        }

        @Override
        public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
            if (null == properties) {
                // Ensure properties would make our life easier.
                properties = new COSDictionary();
            }
            PDMarkedContent markedContent = PDMarkedContent.create(tag, properties);
            if (markedContent.getMCID() ==  -1
                    && !currentMarkedContents.isEmpty() && currentMarkedContents.peek().getMCID() != -1)  {
                // 继承parent marked content mcid
                markedContent.getProperties().setInt(COSName.MCID, currentMarkedContents.peek().getMCID());
            }
            // 根据structTreeInfo里面的信息把actual text写回marked content
            if (structTreeInfo != null) {
                int mcid = markedContent.getMCID();
                if (structTreeInfo.containsKey(mcid)) {
                    PDStructureElement element = structTreeInfo.get(mcid);
                    // 把PDStructureElement保存到marked content里
                    markedContent.getProperties().setItem(COSName.K, element);
                    String actualText = element.getActualText();
                    if (actualText != null) {
                        markedContent.getProperties().setString(COSName.ACTUAL_TEXT, actualText);
                    }
                }
            }
            currentMarkedContents.add(markedContent);
        }

        @Override
        public void endMarkedContentSequence() {
            if (!currentMarkedContents.isEmpty()) {
                currentMarkedContents.pop();
            }
        }

        private PDMarkedContent getCurrentMarkedContent() {
            if (currentMarkedContents.isEmpty()) {
                return null;
            }
            return currentMarkedContents.peek();
        }

        private PDStructureElement getCurrentStructureElement() {
            PDMarkedContent markedContent = getCurrentMarkedContent();
            if (markedContent == null) {
                return null;
            }
            int mcid = markedContent.getMCID();
            if (structTreeInfo != null && structTreeInfo.containsKey(mcid)) {
                return structTreeInfo.get(mcid);
            }
            return null;
        }

        private TextElement createTextElement(Matrix textRenderingMatrix, PDFont font, int code, String unicode, Vector displacement) throws IOException {
            unicode = FontUtils.getUnicode(font, code);
            if (StringUtils.isEmpty(unicode)) {
                return null;
            }

            Matrix textMatrix = getTextMatrix();
            BoundingBox bbox = FontUtils.getFontBBox(font);
            //BoundingBox bbox = font.getBoundingBox();
            if (bbox.getLowerLeftY() < Short.MIN_VALUE) {
                // PDFBOX-2158 and PDFBOX-3130
                // files by Salmat eSolutions / ClibPDF Library
                bbox.setLowerLeftY(-(bbox.getLowerLeftY() + 65536));
            }
            // advance width, bbox height (glyph space)
            float xadvance = font.getWidth(code);
            float bboxHeight = bbox.getHeight();
            boolean isCJK = com.abcft.pdfextract.util.TextUtils.isCJK(unicode.charAt(0));
            if (bboxHeight == 0
                    || (isCJK && (bboxHeight / xadvance > 3 || xadvance / bboxHeight > 3))) {
                // 某些情况下字体高度为0, 或者宽高比严重失衡(只针对中文), 这里根据经验值设置成宽度的1.2倍
                bboxHeight = xadvance * 1.2f;
            }
            Rectangle2D.Float rect = new Rectangle2D.Float(0, bbox.getLowerLeftY(), xadvance, bboxHeight);

            // glyph space -> display space
            AffineTransform at = (AffineTransform) pageTransform.clone();
            at.concatenate(textRenderingMatrix.createAffineTransform());

            // for visible space
            Rectangle2D visibleBBox = null;
            AffineTransform visibleAt = (AffineTransform) at.clone();
            visibleAt.concatenate(font.getFontMatrix().createAffineTransform());

            if (font instanceof PDType3Font) {
                // bbox and font matrix are unscaled
                at.concatenate(font.getFontMatrix().createAffineTransform());
            } else {
                // bbox and font matrix are already scaled to 1000
                at.scale(1 / 1000f, 1 / 1000f);
            }
            // 如果是旋转的文字, 这里的shape就是一个旋转的框, 直接getBounds2D得到的矩形并不能准确的表示这个区域
            Shape shape = at.createTransformedShape(rect);
            Rectangle2D bounds = shape.getBounds2D();

            GeneralPath path = null;
            if (font instanceof PDVectorFont) {
                // get the path
                PDVectorFont vectorFont = (PDVectorFont) font;
                try {
                    path = vectorFont.getNormalizedPath(code);
                } catch (Exception e) {
                    LOGGER.warn("get visible path failed!", e);
                }
            } else {
                // TODO Find out its bounds
            }
            if (path != null && !path.getBounds2D().isEmpty()) {
                // stretch non-embedded glyph if it does not match the width contained in the PDF
                if (!font.isEmbedded())
                {
                    float fontWidth = font.getWidthFromFont(code);
                    if (fontWidth > 0 && // ignore spaces
                            Math.abs(fontWidth - displacement.getX() * 1000) > 0.0001)
                    {
                        float pdfWidth = displacement.getX() * 1000;
                        visibleAt.scale(pdfWidth / fontWidth, 1);
                    }
                }

                Shape glyph = visibleAt.createTransformedShape(path);
                visibleBBox = glyph.getBounds2D();
            }

            if (visibleBBox == null) {
                visibleBBox = bounds;
            } else if (bbox.getWidth() == 0 || bbox.getHeight() == 0) {
                // 某些情况下, 获取字体的区域为空, 这里使用visibleBBox作为字体区域
                bounds = visibleBBox;
                shape = visibleBBox;
            }


            // Note on variable names. There are three different units being used in this code.
            // Character sizes are given in glyph units, text locations are initially given in text
            // units, and we want to save the data in display units. The variable names should end with
            // Text or Disp to represent if the values are in text or disp units (no glyph units are
            // saved).

            float glyphSpaceToTextSpaceFactor = 1 / 1000f;
            if (font instanceof PDType3Font) {
                glyphSpaceToTextSpaceFactor = font.getFontMatrix().getScaleX();
            }

            float spaceWidthText = 0;
            try {
                // to avoid crash as described in PDFBOX-614, see what the space displacement should be
                spaceWidthText = font.getSpaceWidth() * glyphSpaceToTextSpaceFactor;
            } catch (Throwable exception) {
                LOGGER.warn(exception, exception);
            }

            if (spaceWidthText == 0) {
                spaceWidthText = font.getAverageFontWidth() * glyphSpaceToTextSpaceFactor;
                // the average space width appears to be higher than necessary so make it smaller
                spaceWidthText *= .80f;
            }
            if (spaceWidthText == 0) {
                spaceWidthText = 1.0f; // if could not find font, use a generic value
            }

            // the space width has to be transformed into display units
            float spaceWidthDisplay = Math.abs(spaceWidthText * textRenderingMatrix.getScalingFactorX());

            int rotate = GraphicsUtil.getAngleInDegrees(at);
            // 有时候Font bbox不准, 这里合并字体绘制的区域来修正
            if (rotate == 0 && visibleBBox != null) {
                Rectangle2D.union(bounds, visibleBBox, bounds);
                shape = bounds;
            }
            // 样式
            PDGraphicsState state = getGraphicsState();
            PDTextState textState = state.getTextState();
            PDColor color = state.getNonStrokingColor();
            ClosureInt textStyle = new ClosureInt(0);
            String fontName = FontUtils.getFontName(font, textStyle);
            // 正常字体渲染为粗体时，往往需要把整体笔画变粗、描边变细确保字体清晰
            if (RenderingMode.FILL_STROKE == textState.getRenderingMode() && state.getLineWidth() < 1) {
                textStyle.bitOr(TextElement.TEXT_STYLE_BOLD);
            }
            // 某些斜体字是通过 textMatrix 用 Shear 来渲染的
            if (textMatrix.getShearX() >= 0.3) {
                textStyle.bitOr(TextElement.TEXT_STYLE_ITALIC);
            }
            return new TextElement(visibleBBox, bounds, shape, spaceWidthDisplay, unicode, new int[]{code},
                    fontName, textState.getFontSize(), rotate, GraphicsUtil.getColor(color, this), textStyle.get());
        }

    }
}
