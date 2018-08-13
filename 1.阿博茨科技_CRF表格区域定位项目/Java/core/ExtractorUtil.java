package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.model.FontUtils;
import com.abcft.pdfextract.core.table.TableExtractParameters;
import com.abcft.pdfextract.spi.Meta;
import com.google.common.collect.Iterators;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Calendar;
import java.util.Iterator;
import java.util.function.Predicate;

/**
 * Helper class.
 *
 * Created by chzhong on 2017/3/14.
 */
public class ExtractorUtil {

    private static Logger logger = LogManager.getLogger(ExtractorUtil.class);
    private static final int XOBJECT_COUNT_THRESHOLD = 100;
    private static final int SHADING_COUNT_THRESHOLD = 200;

    public static final FilenameFilter PDF_FILTER = (dir, name) -> StringUtils.endsWithIgnoreCase(name.toLowerCase(), ".pdf");

    public static Point2D.Float determinePageSize(PDPage p) {
        float w, h;
        int pageRotation = p.getRotation();
        if (Math.abs(pageRotation) == 90 || Math.abs(pageRotation) == 270) {
            w = p.getCropBox().getHeight();
            h = p.getCropBox().getWidth();
        } else {
            w = p.getCropBox().getWidth();
            h = p.getCropBox().getHeight();
        }
        return new Point2D.Float(w, h);
    }

    public static Rectangle2D getPageRect(PDPage p) {
        Point2D.Float size = determinePageSize(p);
        return new Rectangle2D.Float(0f, 0f, (float) size.getX(), (float) size.getY());
    }

    public static Rectangle2D getPageCropBox(PDPage p) {
        PDRectangle rectangle = p.getCropBox();
        return new Rectangle2D.Float(rectangle.getLowerLeftX(), rectangle.getLowerLeftY(), rectangle.getWidth(), rectangle.getHeight());
    }


    /**
     * 判定一个批量解析公告是否为公告。
     *
     * @param params 解析参数。
     * @return 如果解析参数中的“源”为{@code juchao}，则返回 true；否则为 false。
     */
    public static boolean isNotice(TableExtractParameters params) {
        return "juchao".equals(params.source);
    }

    /**
     * 判定一个批量解析公告是否为港股公告。
     *
     * @param params 解析参数。
     * @return 如果解析参数中的“plate”为{@code 香港主板}，则返回 true；否则为 false。
     */
    public static boolean isHKNotice(TableExtractParameters params) {
        return "香港主板".equals(params.meta.get("plate"));
    }

    /**
     * 判定一个 PDF 是否由 Word 生成。
     *
     * @param params 解析参数。
     * @return 如果 PDF 元数据的“producer”有效并且包含 {@code Office Word}，
     * - 或者 -
     * 元数据没有“producer”但“creator”包含 {@code Office Word}，
     * 则返回 true；否则为 false。
     */
    public static boolean isWordGeneratedPDF(ExtractParameters params) {
        PdfExtractContext context = params.getExtractContext();
        return context != null && (StringUtils.contains(context.producer, " Word")
                || (StringUtils.isEmpty(context.producer) && StringUtils.contains(context.creator, " Word")));
    }

    /**
     * 判定一个 PDF 是否由 PPT 生成。
     *
     * @param params 解析参数。
     * @return 如果 PDF 元数据的“producer”有效并且包含 {@code Office PowerPoint}，
     * - 或者 -
     * 元数据没有“producer”但“creator”包含 {@code Office PowerPoint}，
     * 则返回 true；否则为 false。
     */
    public static boolean isPPTGeneratedPDF(ExtractParameters params) {
        PdfExtractContext context = params.getExtractContext();
        return context != null && (StringUtils.contains(context.producer, " PowerPoint")
                || (StringUtils.isEmpty(context.producer) && StringUtils.contains(context.creator, " PowerPoint")));
    }

    @FunctionalInterface
    public interface PageProcessor {
        void processPage(int pageIndex, PDPage page);
    }

    public static final String LINE_SEPARATOR = System.getProperty("line.separator");

    public static <TParams extends ExtractParameters> void forEachEffectivePages(PDDocument document, TParams params, PageProcessor processor) {
        int startPageIndex = params.startPageIndex;
        int endPageIndex = params.endPageIndex;
        int numberOfPages = document.getNumberOfPages();
        if (startPageIndex < 0) {
            startPageIndex = 0;
        }
        if (endPageIndex < 0 || endPageIndex >= numberOfPages) {
            endPageIndex = numberOfPages - 1;
        }
        PDPageTree pages = document.getPages();
        for(int i = startPageIndex; i <= endPageIndex; ++i) {
            PDPage page = getPdfPage(pages, i);
            if (null == page) {
                continue;
            }
            processor.processPage(i, page);
        }
    }

    /**
     * 尝试获取一个 {@link PDDocumentInformation}。
     * <p>
     * 如果没有有效的信息，则返回 {@code null}.
     * </p>
     * @param document PDF 文档
     * @return 文档的 {@link PDDocumentInformation} 或者 null。
     */
    public static PDDocumentInformation getPdfDocumentInformation(PDDocument document) {
        try {
            return document.getDocumentInformation();
        } catch (Exception e) {
            logger.warn("Failed to document information for {}.", document);
            return null;
        }
    }

    /**
     * 尝试获取一个 {@link PDPage}。
     * <p>
     * 如果没有有效的信息，则返回 {@code null}.
     * </p>
     * @param document PDF 文档
     * @param pageIndex 页索引（从零起）。
     * @return 给定页索引的 {@link PDPage} 或者 null。
     */
    public static PDPage getPdfPage(PDDocument document, int pageIndex) {
        try {
            return document.getPage(pageIndex);
        } catch (Exception e) {
            logger.warn("Failed to get page: index={}.", pageIndex);
            return null;
        }
    }

    /**
     * 尝试获取一个 {@link PDPage}。
     * <p>
     * 如果没有有效的信息，则返回 {@code null}.
     * </p>
     * @param pages PDF 所有页面树信息。
     * @param pageIndex 页索引（从零起）。
     * @return 给定页索引的 {@link PDPage} 或者 null。
     */
    public static PDPage getPdfPage(PDPageTree pages, int pageIndex) {
        try {
            return pages.get(pageIndex);
        } catch (Exception e) {
            logger.warn("Failed to get page: index={}.", pageIndex);
            return null;
        }
    }

    /**
     * 验证 {@link PDDocument} 是否有效。
     * <p>
     * 如果 {@link PDDocument} 缺乏必要的信息，则会抛出 {@link MalformedDocumentException}.
     * </p>
     * @param document 待验证的 {@link PDDocument}.
     * @exception MalformedDocumentException PDF 缺乏必须的信息。
     */
    public static void validatePdfOrThrow(PDDocument document) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (null == catalog) {
            throw new MalformedDocumentException("Failed to load PDF: Document catalog is missing in PDF document.");
        }
        COSDictionary rootDict = catalog.getCOSObject();
        if (!(rootDict.getDictionaryObject(COSName.PAGES) instanceof COSDictionary)) {
            throw new MalformedDocumentException("Failed to load PDF: Pages is missing or have a wrong type.");
        }
    }

    /**
     * 验证 {@link PDDocument} 是否有效。
     *
     * @param document 待验证的 {@link PDDocument}.
     * @return 如果 {@link PDDocument} 有问题（比如无法获取 pages），则返回 false;否则返回 true。
     */
    public static boolean validatePdf(PDDocument document) {
        try {
            validatePdfOrThrow(document);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static final COSName FONT_FLAGS = COSName.getPDFName("ABCFT.FontFlags");

    private static final COSName FONT_CHECK_FLAGS = COSName.getPDFName("ABCFT.FontsChecked");

    private static final COSName FONT_VALID_FLAGS = COSName.getPDFName("ABCFT.FontsValid");

    public static final int FLAG_FONT_TEXT_FAILURE = 4;
    public static final int FLAG_PREDEFINED_INCOMPLETE = 2;
    public static final int FLAG_FONT_CMAP_MISSSING = 1;

    private static final int FLAG_FONT_MASKS = FLAG_FONT_CMAP_MISSSING
            | FLAG_FONT_TEXT_FAILURE
            | FLAG_PREDEFINED_INCOMPLETE;

    /**
     * 预先检查所有 PDF 的字体，添加辅助信息。
     * @param document 待验证的 {@link PDContentStream}。
     */
    public static void pdfPreCheckFonts(PDDocument document, PDContentStream page) {
        PDResources resources;
        try {
            resources = page.getResources();
            if (null == resources) {
                logger.warn("Resources missing in page: {}", page);
                return;
            }
            String producer = null;
            PDDocumentInformation info = getPdfDocumentInformation(document);
            if (info != null) {
                producer = info.getProducer();
            }
            for (COSName fontName : resources.getFontNames()) {
                PDFont font = resources.getFont(fontName);
                COSDictionary fontDict = font.getCOSObject();
                fontDict.setItem(FontUtils.RESOURCE_NAME, fontName);
                fontDict.setString(FontUtils.PRODUCER_NAME, producer);
            }
        } catch (Throwable e) {
            logger.warn("Failed to prepare font resource.");
        }
    }

    /**
     * 事后检查所有 PDF 的字体，记录异常信息。
     * @param document 待验证的 {@link PDContentStream}。
     */
    public static void pdfPostCheckFonts(PDDocument document, PDContentStream page) {
        PDResources resources;
        try {
            resources = page.getResources();
            if (null == resources) {
                logger.warn("Resources missing in page: {}", page);
                return;
            }
            COSDictionary dict = document.getDocument().getTrailer();
            int fontFlags = dict.getInt(FONT_FLAGS, 0);
            if ((fontFlags & FLAG_FONT_MASKS) == FLAG_FONT_MASKS) {
                // 所有 flag 都置位了，没必要再检查了
                return;
            }
            for (COSName fontName : resources.getFontNames()) {
                PDFont font = resources.getFont(fontName);
                if (FontUtils.isNoMappingFont(font)) {
                    fontFlags |= FLAG_FONT_CMAP_MISSSING;
                }
                if (FontUtils.isMappingIncompleteFont(font)) {
                    fontFlags |= FLAG_PREDEFINED_INCOMPLETE;
                }
                if (FontUtils.isMappingFailureFont(font)) {
                    fontFlags |= FLAG_FONT_TEXT_FAILURE;
                }
            }
            dict.setInt(FONT_FLAGS, fontFlags);
        } catch (Throwable e) {
            logger.warn("Failed to get check font resources.");
        }
    }

    /**
     * 判定一个 {@link PDResources} 是否包含字体。
     * @param resources 待验证的 {@link PDResources}。
     * @return 如果 {@link PDResources} 包含至少一个字体，则返回 true；否则（包括无法无法获取 {@link PDResources}）返回 false。
     */
    public static boolean resourcesHasFont(PDResources resources) {
        try {
            if (null == resources) {
                return false;
            }
            COSDictionary resourceDict = resources.getCOSObject();
            if (resourceDict.containsKey(FONT_VALID_FLAGS)) {
                return resourceDict.getBoolean(FONT_VALID_FLAGS, false);
            }
            if (resources.getFontNames().iterator().hasNext()) {
                resourceDict.setBoolean(FONT_VALID_FLAGS, true);
                return true;
            }
            if (resourceDict.getBoolean(FONT_CHECK_FLAGS, false)) {
                return false;
            }
            resourceDict.setBoolean(FONT_CHECK_FLAGS, true);
            Iterable<COSName> xObjectNames = resources.getXObjectNames();
            for (COSName xObjName : xObjectNames) {
                try {
                    PDXObject xObj = resources.getXObject(xObjName);
                    if (xObj instanceof PDFormXObject) {
                        PDFormXObject xForm = (PDFormXObject) xObj;
                        if (resourcesHasFont(xForm.getResources())) {
                            resourceDict.setBoolean(FONT_VALID_FLAGS, true);
                            return true;
                        }
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }
            resourceDict.setBoolean(FONT_VALID_FLAGS, false);
        } catch (Throwable e) {
            logger.warn("Failed to check fonts in resources.", e);
        }
        return false;
    }

    /**
     * 返回一个 {@link PDContentStream} 是否的 XObject 的个数。
     * @param stream 待检查的 {@link PDContentStream}。
     * @return 给定的 {@link PDContentStream} 内包含的 XObject 的个数；如果无法获取或计算，则返回 -1。
     */
    public static int getPdfPageXObjectSize(PDContentStream stream) {
        PDResources resources;
        try {
            resources = stream.getResources();
            if (null == resources) {
                logger.warn("Resources missing in stream: {}", stream);
                return -1;
            }
            return Iterators.size(resources.getXObjectNames().iterator());
        } catch (Throwable e) {
            logger.warn("Failed check XObject size.", e);
            return -1;
        }
    }

    /**
     * 返回一个 {@link PDContentStream} 是否疑似为不规则的图画或者封面。
     *
     * @param stream 待检查的 {@link PDContentStream}。
     * @return 给定的 {@link PDContentStream} 内包含的 XObject 或 Shading 的个数过多则返回 true；否则返回 false。
     */
    public static boolean isPainting(PDContentStream stream) {
        return isPainting(stream, 0);
    }

    /**
     * 返回一个 {@link PDContentStream} 是否疑似为不规则的图画或者封面。
     *
     * @param stream 待检查的 {@link PDContentStream}。
     * @return 给定的 {@link PDContentStream} 内包含的 XObject 或 Shading 的个数过多则返回 true；否则返回 false。
     */
    public static boolean isPainting(PDContentStream stream, int actualTextCount) {
        PDResources resources;
        try {
            resources = stream.getResources();
            if (null == resources) {
                logger.warn("Resources missing: {}", stream);
                return false;
            }
            return iteratorExceeds(resources.getXObjectNames().iterator(),
                    name -> {
                        try {
                            return !(resources.getXObject(name) instanceof PDImageXObject);
                        } catch (IOException e) {
                            return true;
                        }
                    },
                    XOBJECT_COUNT_THRESHOLD + actualTextCount)
                    || iteratorExceeds(resources.getShadingNames().iterator(), SHADING_COUNT_THRESHOLD);
        } catch (Throwable e) {
            logger.warn("Failed to determine painting.", e);
            return false;
        }
    }

    private static <T> boolean iteratorExceeds(Iterator<T> iterator, Predicate<T> predicate, int threshold) {
        long count = 0L;
        while (iterator.hasNext()) {
            T item = iterator.next();
            if (predicate != null && !predicate.test(item))
                continue;
            count++;
            if (count >= threshold) {
                return true;
            }
        }
        return false;
    }

    private static <T> boolean iteratorExceeds(Iterator<T> iterator, int threshold) {
        return iteratorExceeds(iterator, null, threshold);
    }

    // Scanner Names
    private static final String[] SCANNER_PRODUCERS_PREFIX = {
            "Adobe PSL",
            "IntSig Information Co., Ltd",
            "intsig.com pdf producer",
            "EPSON Scan",
            "ApeosPort-V",
            "KONICA MINOLTA bizhub",
            "Canon"
    };

    private static final String[] SCANNER_PRODUCERS_SUFFIX = {
            "Paper Capture Plug-in"
    };
    private static final float SCANNING_COPY_AREA_THRESHOLD = 0.7f;
    private static final float SCANNING_COPY_PAGE_COUNT_THRESHOLD = 0.8f;

    public static Meta getPDFMeta(PDDocument document) {
        Meta meta = new Meta();
        PDDocumentInformation docInfo = ExtractorUtil.getPdfDocumentInformation(document);
        meta.version = document.getVersion();
        try {
            meta.pageCount = document.getNumberOfPages();
        } catch (Throwable e) {
            // Exception while getting page.
            meta.pageCount = -1;
        }

        PDDocumentCatalog docCatalog = document.getDocumentCatalog();
        if (docCatalog != null) {
            meta.language = docCatalog.getLanguage();
        }

        boolean scanCopyConfirmed = false;
        boolean pptConfirmed = false;

        boolean isScanCopy = false;
        if (docInfo != null) {
            meta.title = StringUtils.trim(docInfo.getTitle());
            meta.author = StringUtils.trim(docInfo.getAuthor());
            meta.subject = StringUtils.trim(docInfo.getSubject());
            meta.keywords = StringUtils.trim(docInfo.getKeywords());
            meta.creator = StringUtils.trim(docInfo.getCreator());
            meta.producer = StringUtils.trim(docInfo.getProducer());
            isScanCopy = StringUtils.startsWithAny(meta.producer, SCANNER_PRODUCERS_PREFIX)
                    || StringUtils.endsWithAny(meta.producer, SCANNER_PRODUCERS_SUFFIX)
                    || StringUtils.startsWithAny(meta.creator, SCANNER_PRODUCERS_PREFIX)
                    || StringUtils.endsWithAny(meta.creator, SCANNER_PRODUCERS_SUFFIX);
            if (isScanCopy) {
                if (docCatalog != null &&
                        (docCatalog.getDocumentOutline() != null || docCatalog.getStructureTreeRoot() != null)) {
                    isScanCopy = false;
                } else {
                    scanCopyConfirmed = true;
                }
            }
            Calendar creationDate = docInfo.getCreationDate();
            meta.creationDate = creationDate != null ? creationDate.getTime() : null;
            Calendar modificationDate = docInfo.getModificationDate();
            meta.modificationDate = modificationDate != null ? modificationDate.getTime() : null;
        }

        boolean isPPT = true;
        PDPageTree pages = document.getPages();
        int pageCount = pages.getCount();
        int scanningCopyPageCount = 0;
        for (int i = 0; i < pageCount; i++) {
            PDPage page = ExtractorUtil.getPdfPage(pages, i);
            if (null == page) {
                continue;
            }
            Point2D.Float pageSize = ExtractorUtil.determinePageSize(page);

            if (!pptConfirmed && pageSize.x < pageSize.y) {
                isPPT = false;
                pptConfirmed = true;
            }
            if (!scanCopyConfirmed) {
                float pageAreaThreshold = pageSize.x * pageSize.y * SCANNING_COPY_AREA_THRESHOLD;
                boolean scanningCopyPage = checkScanningCopyPage(page, pageAreaThreshold);
                if (scanningCopyPage) {
                    ++scanningCopyPageCount;
                }
            }
        }
        if (!scanCopyConfirmed) {
            isScanCopy = scanningCopyPageCount >= (pageCount * SCANNING_COPY_PAGE_COUNT_THRESHOLD);
        }
        meta.isPPT = isPPT;
        meta.isScanCopy = isScanCopy;
        return meta;
    }

    private static boolean checkScanningCopyPage(PDPage page, float pageArea) {
        return !ExtractorUtil.resourcesHasFont(page.getResources());
    }

    private ExtractorUtil() {}


}
