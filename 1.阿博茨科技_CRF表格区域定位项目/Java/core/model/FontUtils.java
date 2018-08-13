package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.util.ClosureInt;
import com.abcft.pdfextract.util.TextUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.util.BoundingBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.tensorflow.Session;
import org.tensorflow.Tensor;
import org.tensorflow.types.UInt8;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/11/13.
 */
public class FontUtils {

    private static Logger logger = LogManager.getLogger();
    private static GlyphList glyphList;

    private static final Set<String> WINGDINGS_1_NAMES = Sets.newHashSet("Wingdings");
    private static final Set<String> WINGDINGS_2_NAMES = Sets.newHashSet("Wingdings2", "Wingdings 2");
    private static final Map<Integer, String> WINGDINGS_1_MAP;
    private static final Map<Integer, String> WINGDINGS_2_MAP;
    private static final Map<String, String>  WINGDINGS_NAME_MAP;

    public static final String OCR_SPACE = "<SPACE>";

    static {
        // load additional glyph list for Unicode mapping
        String path = "org/apache/pdfbox/resources/glyphlist/additional.txt";
        InputStream input = GlyphList.class.getClassLoader().getResourceAsStream(path);
        try {
            glyphList = new GlyphList(GlyphList.getAdobeGlyphList(), input);
        } catch (IOException e) {
            logger.warn("load glyphList failed", e);
            glyphList = null;
        }

        // 目前只添加常用的字符
        // 想要添加其他字符的话, 可以在word里面输入这些字符然后保存为PDF, 然后在PDFBOX里查看PDF就知道字符对应的code
        WINGDINGS_1_MAP = new HashMap<>();
        WINGDINGS_1_MAP.put(56, "✘");
        WINGDINGS_1_MAP.put(57, "✔");
        WINGDINGS_1_MAP.put(58, "☒");
        WINGDINGS_1_MAP.put(59, "☑");
        WINGDINGS_1_MAP.put(121, "●");
        WINGDINGS_1_MAP.put(122, "●");
        WINGDINGS_1_MAP.put(131, "■");
        WINGDINGS_1_MAP.put(132, "■");
        WINGDINGS_1_MAP.put(139, "◆");
        WINGDINGS_1_MAP.put(151, "⟡");
        WINGDINGS_1_MAP.put(153, "❖");
        WINGDINGS_1_MAP.put(190, "●");

        WINGDINGS_2_MAP = new HashMap<>();
        WINGDINGS_2_MAP.put(50, "✘");
        WINGDINGS_2_MAP.put(51, "✔");
        WINGDINGS_2_MAP.put(52, "☒");
        WINGDINGS_2_MAP.put(53, "☑");
        WINGDINGS_2_MAP.put(54, "☒");
        WINGDINGS_2_MAP.put(55, "☒");
        WINGDINGS_2_MAP.put(121, "●");
        WINGDINGS_2_MAP.put(122, "●");
        WINGDINGS_2_MAP.put(131, "■");
        WINGDINGS_2_MAP.put(132, "■");
        WINGDINGS_2_MAP.put(144, "◆");
        WINGDINGS_2_MAP.put(161, "◆");

        WINGDINGS_NAME_MAP = new HashMap<>();
        WINGDINGS_NAME_MAP.put("circle4", "●");
        WINGDINGS_NAME_MAP.put("circle5", "●");
        WINGDINGS_NAME_MAP.put("circle6", "●");
        WINGDINGS_NAME_MAP.put("square4", "■");
        WINGDINGS_NAME_MAP.put("square5", "■");
        WINGDINGS_NAME_MAP.put("square6", "■");
        WINGDINGS_NAME_MAP.put("rhombus4", "◆");
        WINGDINGS_NAME_MAP.put("rhombus5", "◆");
        WINGDINGS_NAME_MAP.put("rhombus6", "◆");
        WINGDINGS_NAME_MAP.put("xrhombus", "❖");
        WINGDINGS_NAME_MAP.put("head2right", "●");
        WINGDINGS_NAME_MAP.put("xmark", "✘");
        WINGDINGS_NAME_MAP.put("xmarkbld", "✘");
        WINGDINGS_NAME_MAP.put("check", "✔");
        WINGDINGS_NAME_MAP.put("checkbld", "✔");
        WINGDINGS_NAME_MAP.put("boxx", "☒");
        WINGDINGS_NAME_MAP.put("boxxmark", "☒");
        WINGDINGS_NAME_MAP.put("boxxmarkbld", "☒");
        WINGDINGS_NAME_MAP.put("boxcheck", "☑");
        WINGDINGS_NAME_MAP.put("boxcheckbld", "☑");
    }

    enum FontStyleSuffix {

        BOLD_ITALIC(TextElement.TEXT_STYLE_BOLD | TextElement.TEXT_STYLE_ITALIC,
                "Bold-Italic", "Bold Italic", "Bold,Italic", "BoldItalic"),
        BOLD(TextElement.TEXT_STYLE_BOLD,
                "Semibold", "Bold", "Black", "bold", "black", "Heavy", "heavy"),
        ITALIC(TextElement.TEXT_STYLE_ITALIC,
                "Italic", "italic", "Oblique", "oblique"),
        REGULAR(TextElement.TEXT_STYLE_NORMAL, "Regular"),
        LIGHT(TextElement.TEXT_STYLE_NORMAL, "Light");

        final int style;
        final String[] suffixes;
        final int suffixLength;

        FontStyleSuffix(int style, String... suffixes) {
            this.style = style;
            this.suffixes = suffixes;
            this.suffixLength = suffixes[0].length();
        }

    }


    public static String getFontName(PDFont font, ClosureInt style) {
        if (null == font) {
            return null;
        }
        String fontName = font.getName();

        if (StringUtils.contains(fontName, '+')) {
            int pos = StringUtils.indexOf(fontName, '+');
            fontName = StringUtils.substring(fontName, pos + 1);
            fontName = TextUtils.unifyCOSFontName(fontName);
        }

        if (StringUtils.contains(fontName, '_')) {
            int pos = StringUtils.indexOf(fontName, '_');
            fontName = StringUtils.substring(fontName, 0, pos);
        }

        if (fontName != null) {
            for(FontStyleSuffix suffix : FontStyleSuffix.values()) {
                if (StringUtils.containsAny(fontName, suffix.suffixes)) {
                    style.bitOr(suffix.style);
                    break;
                }
            }
        }

        if (StringUtils.contains(fontName, '-')) {
            int pos = StringUtils.indexOf(fontName, '-');
            fontName = StringUtils.substring(fontName, 0, pos);
        }
        if (StringUtils.contains(fontName, ',')) {
            int pos = StringUtils.indexOf(fontName, ',');
            fontName = StringUtils.substring(fontName, 0, pos);
        }

        PDFontDescriptor descriptor = font.getFontDescriptor();
        if (descriptor != null) {
            if (descriptor.isForceBold()) {
                style.bitOr(TextElement.TEXT_STYLE_BOLD);
            }
            if (descriptor.isItalic()) {
                style.bitOr(TextElement.TEXT_STYLE_ITALIC);
            }
        }
        return fontName;
    }

    public static String getFontFamily(PDFont font) {
        PDFontDescriptor descriptor = font.getFontDescriptor();
        if (descriptor != null) {
            return descriptor.getFontFamily();
        }
        return null;
    }

    private static String getWingdingsFontUnicode(PDFont font, String fontName, int code) {
        if (font instanceof PDTrueTypeFont) {
            String name = ((PDTrueTypeFont) font).getEncoding().getName(code);
            if (StringUtils.isNoneEmpty(name) && WINGDINGS_NAME_MAP.containsKey(name)) {
                return WINGDINGS_NAME_MAP.get(name);
            }
        }
        if (WINGDINGS_1_NAMES.contains(fontName)) {
            return WINGDINGS_1_MAP.get(code);
        } else if (WINGDINGS_2_NAMES.contains(fontName)) {
            return WINGDINGS_2_MAP.get(code);
        }
        return null;
    }


    private static class Glyph {
        String unicode;
        int code;
        GeneralPath generalPath;
        Rectangle2D bounds;
        BufferedImage image;
    }

    private static boolean hasGlyph(PDCIDFont cidFont, int code) {
        try {
            int gid = cidFont.codeToGID(code);
            return gid > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Glyph> loadFontGlyphs(PDType0Font font) throws IOException {
        PDCIDFont descendantFont = font.getDescendantFont();
        List<Glyph> glyphs = new ArrayList<>(256);
        // 检查是否有缺少unicode map的文字
        int missedUnicodeCount = 0;
        for (int code = 0; code < 65535; ++code) {
            if (hasGlyph(descendantFont, code)) {
                String unicode = font.toUnicode(code);
                if (unicode == null) {
                    GeneralPath generalPath = descendantFont.getPath(code);
                    if (!generalPath.getBounds2D().isEmpty()) {
                        missedUnicodeCount++;
                    }
                }
            }
        }
        if (missedUnicodeCount == 0) {
            return null;
        }
        logger.info("Font {} missed {} unicode", font.getName(), missedUnicodeCount);
        for (int code = 0; code < 65535; ++code) {
            if (hasGlyph(descendantFont, code)) {
                GeneralPath generalPath = descendantFont.getPath(code);
                if (generalPath == null) {
                    continue;
                }
                Rectangle2D bounds = generalPath.getBounds2D();
                if (bounds.isEmpty()) {
                    continue;
                }
                Glyph glyph = new Glyph();
                glyph.code = code;
                glyph.generalPath = generalPath;
                glyph.bounds = bounds;
                glyph.unicode = bounds.isEmpty() ? " " : font.toUnicode(code);
                glyphs.add(glyph);
            }
        }
        return glyphs;
    }

    private static List<Glyph> loadFontGlyphs(PDSimpleFont font) throws IOException {
        if (font instanceof PDType3Font) {
            // 暂时还不支持type 3 font
            return null;
        }
        List<Glyph> glyphs = new ArrayList<>(256);
        // 检查是否有缺少unicode map的文字
        int missedUnicodeCount = 0;
        for (int code = 0; code < 255; ++code) {
            if (font.getEncoding().contains(code)) {
                String unicode = font.toUnicode(code);
                if (unicode == null) {
                    String glyphName = font.getEncoding().getName(code);
                    GeneralPath generalPath;
                    if (font instanceof PDVectorFont) {
                        // using names didn't work with the file from PDFBOX-3445
                        generalPath = ((PDVectorFont) font).getPath(code);
                    } else {
                        generalPath = font.getPath(glyphName);
                    }
                    if (!generalPath.getBounds2D().isEmpty()) {
                        missedUnicodeCount++;
                    }
                }
            }
        }
        if (missedUnicodeCount == 0) {
            return null;
        }
        logger.info("Font {} missed {} unicode", font.getName(), missedUnicodeCount);
        for (int code = 0; code < 255; ++code) {
            if (font.getEncoding().contains(code)) {
                String glyphName = font.getEncoding().getName(code);
                GeneralPath generalPath;
                if (font instanceof PDVectorFont) {
                    // using names didn't work with the file from PDFBOX-3445
                    generalPath = ((PDVectorFont) font).getPath(code);
                } else {
                    generalPath = font.getPath(glyphName);
                }
                if (generalPath == null) {
                    continue;
                }
                Rectangle2D bounds = generalPath.getBounds2D();
                Glyph glyph = new Glyph();
                glyph.code = code;
                glyph.generalPath = generalPath;
                glyph.bounds = bounds;
                glyph.unicode = bounds.isEmpty() ? " " : font.toUnicode(code);
                glyphs.add(glyph);
            }
        }
        return glyphs;
    }

    public static void enableFixFontsUnicodeMap(PDResources resources) {
        for (COSName name : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(name);
                font.getCOSObject().setBoolean(FONT_MAPPING_FIXE_ENABLE, true);
            } catch (Exception ignored) {
            }
        }
        Iterable<COSName> xObjectNames = resources.getXObjectNames();
        for (COSName xObjName : xObjectNames) {
            try {
                PDXObject xObj = resources.getXObject(xObjName);
                if (xObj instanceof PDFormXObject) {
                    PDFormXObject xForm = (PDFormXObject) xObj;
                    enableFixFontsUnicodeMap(xForm.getResources());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void fixFontUnicodeMap(PDFont font) {
        if (!TensorflowManager.INSTANCE.isModelAvailable(TensorflowManager.FONT_OCR)) {
            logger.info("Try to enable ocr to fix unicode map, but model isn't available");
            return;
        }
        if (!font.getCOSObject().getBoolean(FONT_MAPPING_FIXE_ENABLE, false)) {
            return;
        }
        if (font.getCOSObject().getBoolean(FONT_MAPPING_FIXED, false)) {
            return;
        }
        logger.debug("fixFontUnicodeMap {} start...", font.getName());
        long tick1 = System.currentTimeMillis();
        List<Glyph> glyphs = null;
        if (font instanceof PDType0Font) {
            try {
                glyphs = loadFontGlyphs((PDType0Font) font);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (font instanceof PDSimpleFont) {
            try {
                glyphs = loadFontGlyphs((PDSimpleFont) font);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            return;
        }
        if (glyphs == null || glyphs.isEmpty()) {
            return;
        }

        double minY = glyphs.stream().map(glyph -> glyph.bounds.getMinY()).min(Double::compareTo).orElse((double)0);
        double maxY = glyphs.stream().map(glyph -> glyph.bounds.getMaxY()).max(Double::compareTo).orElse((double)0);
//        minY = font.getFontDescriptor().getDescent();
//        maxY = font.getFontDescriptor().getAscent();
        int width = 64;
        int height = 64;
        long tick2 = System.currentTimeMillis();
        logger.debug("loadFontGlyphs costs {}ms", (tick2 - tick1));
        Map<Integer, String> map = new HashMap<>();
        for (Glyph glyph : glyphs) {
            if (glyph.unicode != null) {
                // 使用已有的unicode信息, 不做OCR
                map.put(glyph.code, glyph.unicode);
            } else {
                glyph.image = renderGlyph(glyph.generalPath, width, height, (float) minY, (float) maxY);
            }
        }
        long tick3 = System.currentTimeMillis();
        logger.debug("renderGlyph costs {}ms", (tick3 - tick2));
        ocrGlyphs(glyphs.stream().filter(glyph -> glyph.image != null).collect(Collectors.toList()), map);
        CMap cMap = font.getToUnicodeCMap();
        if (cMap == null) {
            cMap = new CMap();
            font.setToUnicodeCMap(cMap);
        }
        for (Map.Entry<Integer, String> entry : map.entrySet()) {
            cMap.addCharMapping(entry.getKey(), entry.getValue());
        }
        long tick4 = System.currentTimeMillis();
        logger.debug("fixFontUnicodeMap {} costs {}ms", font.getName(), (tick4 - tick1));
        font.getCOSObject().setBoolean(FONT_MAPPING_FIXED, true);
    }


    private static void ocrGlyphs(List<Glyph> glyphs, Map<Integer, String> map) {
        long tick3 = System.currentTimeMillis();
        int batchSize = 16;
        int width = 64;
        int height = 64;
        Session session = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.FONT_OCR).session();

        for (int i = 0; i < glyphs.size(); i+= batchSize) {
            int batch = Math.min(batchSize, glyphs.size() - i);
            ByteBuffer byteBuffer = ByteBuffer.allocate(batch * width * height);
            for (int j = 0; j < batch; j++) {
                Glyph glyph = glyphs.get(i + j);
                byte[] bytes = ((DataBufferByte) glyph.image.getRaster().getDataBuffer()).getData();
                byteBuffer.position(j * width * height);
                byteBuffer.put(bytes);
            }
            byteBuffer.rewind();

            Tensor inputTensor = Tensor.create(UInt8.class, new long[] {batch, width, height, 1}, byteBuffer);
            List<Tensor<?>> result = session.runner()
                    .feed("uint8_images", inputTensor)
                    .feed("image_bytes", Tensor.create(new byte[0]))
                    .feed("use_uint8", Tensor.create(true))
                    .fetch("top3_texts")
                    .run();
            byte[][][] predictions = result.get(0).copyTo(new byte[batch][3][]);

            for (int j = 0; j < batch; j++) {
                Glyph glyph = glyphs.get(i + j);
                if (glyph.bounds.isEmpty()) {
                    glyph.unicode = " ";
                } else {
                    try {
                        String[] top3Texts = new String[3];
                        for (int k = 0; k < 3; k++) {
                            top3Texts[k] = new String(predictions[j][k], "utf-8");
                        }
                        if (StringUtils.isNoneBlank(glyph.unicode) && ArrayUtils.contains(top3Texts, glyph.unicode)) {
                            // 如果原始的字体里面有unicode信息, 而且在ocr识别出来的top3的结果里, 就使用这个结果
                            continue;
                        } else {
                            glyph.unicode = top3Texts[0];
                            // 模型返回的是<SPACE>需要替换成原始的空格
                            if (OCR_SPACE.equals(glyph.unicode)) {
                                glyph.unicode = " ";
                            }
                        }
                    } catch (UnsupportedEncodingException e) {
                        glyph.unicode = " ";
                    }
                }
                map.put(glyph.code, glyph.unicode);
            }
        }
        long tick4 = System.currentTimeMillis();
        logger.debug("orc glyphs costs {}ms", (tick4 - tick3));
    }

    private static BufferedImage renderGlyph(GeneralPath path, int width, int height, float minY, float maxY) {
        BufferedImage bim = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = (Graphics2D) bim.getGraphics();
        g.setBackground(Color.WHITE);
        g.clearRect(0, 0, bim.getWidth(), bim.getHeight());

        Rectangle2D bounds2D = path.getBounds2D();
        if (bounds2D.isEmpty()) {
            return bim;
        }

        float scale = height / (maxY - minY);
        // flip
        g.scale(1, -1);
        g.translate(0, -height);

        // horizontal center
        g.translate(width / 2, 0);

        // scale from the glyph to the cell
        g.scale(scale, scale);

        // Adjust for negative y min bound
        g.translate(-bounds2D.getCenterX(), -minY);

        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.fill(path);

        g.dispose();
        return bim;
    }

    private static BufferedImage renderGlyph(PDVectorFont font, int code, int width, int height) {

        BufferedImage bim = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = (Graphics2D) bim.getGraphics();
        g.setBackground(Color.white);
        g.clearRect(0, 0, bim.getWidth(), bim.getHeight());

        GeneralPath path = null;
        try {
            path = font.getNormalizedPath(code);
        } catch (IOException e) {
            return null;
        }
        if (path == null) {
            return null;
        }
        Rectangle2D bounds2D = path.getBounds2D();
        if (bounds2D.isEmpty()) {
            return null;
        }

        double padding = 5;
        double scaleX = (width - padding * 2) / bounds2D.getWidth();
        double scaleY = (height - padding * 2) / bounds2D.getHeight();
        double scale = Math.min(scaleX, scaleY);

        // move to center
        g.translate(width / 2, height / 2);

        // scale from the glyph to the cell
        g.scale(scale, -scale);

        g.translate(-bounds2D.getCenterX(), -bounds2D.getCenterY());

        g.setColor(Color.black);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.fill(path);
        g.dispose();
        return bim;
    }

    public static String getUnicode(PDFont font, int code) {
        String unicode = null;
        String fontName = getFontName(font, new ClosureInt());
        boolean isWingdingsFont = StringUtils.contains(fontName, "Wingdings");
        if (isWingdingsFont) {
            unicode = getWingdingsFontUnicode(font, fontName, code);
            if (unicode == null) {
                return null;
            }
        } else {
            // use our additional glyph list for Unicode mapping
            try {
                unicode = font.toUnicode(code, glyphList);
            } catch (Exception e) {
                logger.warn("toUnicode failed", e);
            }

            if (unicode == null) {
                try {
                    // 判断是否是空白字符
                    if (font.getHeight(code) == 0) {
                        return null;
                    }
                } catch (Exception ignored) {
                }
            }

            // when there is no Unicode mapping available, Acrobat simply coerces the character code
            // into Unicode, so we do the same. Subclasses of PDFStreamEngine don't necessarily want
            // this, which is why we leave it until this point in PDFTextStreamEngine.
            if (unicode == null) {
                fixFontUnicodeMap(font);
                try {
                    unicode = font.toUnicode(code, glyphList);
                } catch (IOException e) {
                    logger.warn("toUnicode failed", e);
                }
                if (unicode == null) {
                    logMappingFailureFont(font);
                    // Acrobat doesn't seem to coerce composite font's character codes, instead it
                    // skips them. See the "allah2.pdf" TestTextStripper file.
                    return null;
                }
            }
        }

        // Fix repeated unicode (ST煤气-2016年年度报告）。
        if (needNormalize(fontName, unicode)) {
            // 某些公司会使用全角空格或者奇怪的空格字符 (http://jkorpela.fi/chars/spaces.html)
            // 也会才有特殊的字符，如：'⾦' 0x2FA6 '金' 0x91D1 '金' 0xF90A
            // 所以这里需要规范化 Unicode，顺带把特殊空格也解决了
            // 替换unicode NARROW NO-BREAK SPACE("\u202f"), NO-BREAK SPACE("\u00A0")为普通的空格
            unicode = Normalizer.normalize(unicode, Normalizer.Form.NFKD);

            if (unicode.length() > 1) {
                boolean isRepeatedUnicode = true;
                char base = unicode.charAt(0);
                Character.UnicodeBlock[] blocks = new Character.UnicodeBlock[unicode.length()];
                blocks[0] = Character.UnicodeBlock.of(base);
                for (int i = 1; i < unicode.length(); ++i) {
                    char current = unicode.charAt(i);
                    blocks[i] = Character.UnicodeBlock.of(current);
                    if (base != current) {
                        isRepeatedUnicode = false;
                        break;
                    }
                }
                if (isRepeatedUnicode && TextUtils.isCJK(base)) {
                    logger.warn("toUnicode contains repeated unicode： Font: {}, code: {}, unicode: {}",
                            fontName, code, unicode);
                    unicode = new String(new char[]{ base });
                } else if (Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT == blocks[0]) {
                    logger.warn("toUnicode contains supplement： Font: {}, code: {}, unicode: {}",
                            fontName, code, unicode);
                    // 处理偏旁部首 '' 0x2EC5
                    unicode = unicode.substring(1, 2);
                }
            }
        } else {
            // 其余字体简化处理，只规范化空格
            unicode = unicode.replaceAll("[\u00A0\u2000-\u200B\u202F\u205F\u3000]", " ");
        }

        // "\u007F"为unicode DELETE
        unicode = unicode.replace("\u007F", "");
        return unicode;
    }

    private static Set<Character.UnicodeBlock> NEED_NORMALIZE_UNICODE_BLOCK = Sets.newHashSet(
            Character.UnicodeBlock.KANGXI_RADICALS,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    );

    private static boolean needNormalize(String fontName, String unicode) {
        if ("PingFangSC".equals(fontName)) {
            return true;
        }
        if (StringUtils.isEmpty(unicode)) {
            return false;
        }
        char ch = unicode.charAt(0);
        Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(ch);
        return NEED_NORMALIZE_UNICODE_BLOCK.contains(unicodeBlock);
    }

    public static final COSName RESOURCE_NAME = COSName.getPDFName("ABCFT.ResourceName");
    public static final COSName PRODUCER_NAME = COSName.getPDFName("ABCFT.Producer");
    private static final COSName NO_MAPPING_WARN = COSName.getPDFName("ABCFT.NoMappingWarn");
    private static final COSName NO_MAPPING = COSName.getPDFName("ABCFT.NoMapping");
    private static final COSName MAPPING_FAILURE = COSName.getPDFName("ABCFT.MappingFailure");
    private static final COSName MAPPING_FAILURE_WARN = COSName.getPDFName("ABCFT.MappingFailureWarn");
    private static final COSName MAPPING_INCOMPLETE_WARN = COSName.getPDFName("ABCFT.MappingIncompleteWarn");
    private static final COSName TYPE3_FONT_BBOX_FIXED = COSName.getPDFName("ABC.Type3BBoxFixed");
    private static final COSName FONT_MAPPING_FIXED = COSName.getPDFName("ABC.FontMappingFixed");
    private static final COSName FONT_MAPPING_FIXE_ENABLE = COSName.getPDFName("ABC.FontMappingFixEnable");

    private static boolean logOnce(PDFont font, COSName name, String message, Object ... args) {
        boolean logged = false;
        COSDictionary dict = font.getCOSObject();

        if (!dict.getBoolean(name, false)) {
            logger.warn(message, args);
            dict.setBoolean(name, true);
            logged = true;
        }
        return logged;

    }

    private static void logMappingFailureFont(PDFont font) {
        if(logOnce(font, MAPPING_FAILURE_WARN, "Unable to find unicode mapping for font \"{}\".", font)) {
            COSDictionary dict = font.getCOSObject();
            dict.setBoolean(MAPPING_FAILURE, true);
        }
    }

    public static boolean isNoMappingFont(PDFont font) {
        COSDictionary dict = font.getCOSObject();
        return dict.getBoolean(NO_MAPPING, false);
    }

    public static boolean isMappingFailureFont(PDFont font) {
        COSDictionary dict = font.getCOSObject();
        return dict.getBoolean(MAPPING_FAILURE, false);
    }

    public static boolean isMappingIncompleteFont(PDFont font) {
        COSDictionary dict = font.getCOSObject();
        return dict.getBoolean(MAPPING_INCOMPLETE_WARN, false);
    }

    public static BoundingBox getFontBBox(PDFont font) throws IOException {
        if (font instanceof PDType3Font) {
            return getType3FontBBox((PDType3Font)font);
        } else {
            return font.getBoundingBox();
        }
    }

    private static Field fontBBoxField;
    private static COSArray ZERO_RECT;

    private static BoundingBox getType3FontBBox(PDType3Font font) {
        try {
            if (null == fontBBoxField) {
                fontBBoxField = PDType3Font.class.getDeclaredField("fontBBox");
                fontBBoxField.setAccessible(true);
            }
            COSDictionary dict = font.getCOSObject();
            if (dict.getBoolean(TYPE3_FONT_BBOX_FIXED, false)) {
                return font.getBoundingBox();
            }

            // Type3 字体的 BBox 经常出错(see PDFBOX-1917)
            // 因此这里进行强制修复（通过所有字符的边缘判定）
            COSArray rect = (COSArray) dict.getDictionaryObject(COSName.FONT_BBOX);
            //noinspection Duplicates
            if (null == ZERO_RECT) {
                ZERO_RECT = new COSArray();
                ZERO_RECT.add(new COSFloat(0f));
                ZERO_RECT.add(new COSFloat(0f));
                ZERO_RECT.add(new COSFloat(0f));
                ZERO_RECT.add(new COSFloat(0f));
            }
            // 清空 fontBBox 字段和 FONT_BBOX，强制字体根据每个字符重新计算 BBOX
            dict.setItem(COSName.FONT_BBOX, ZERO_RECT);
            fontBBoxField.set(font, null);
            BoundingBox bbox = font.getBoundingBox();
            // 还原 BBox
            // TODO 是否需要把 FONT_BBOX 也改掉呢？
            dict.setItem(COSName.FONT_BBOX, rect);
            // 记录已修复的结果，不再重新计算
            dict.setBoolean(TYPE3_FONT_BBOX_FIXED, true);

            return bbox;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // 一切努力都失败了
            return font.getBoundingBox();
        }
    }



}
