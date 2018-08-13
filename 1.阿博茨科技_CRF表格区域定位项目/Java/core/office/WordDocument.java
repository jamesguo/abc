package com.abcft.pdfextract.core.office;

import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.spi.FileType;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.*;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.abcft.pdfextract.core.office.OfficeRegex.TITLE_PATTERNS;

public abstract class WordDocument extends OfficeDocument implements Document<WordDocument> {

    private static final Logger logger = LogManager.getLogger(WordDocument.class);


    protected boolean processed;

    public static WordDocument getInstance(Object document) {
        try {
            if (document instanceof XWPFDocument) {
                return new WordXWPFDocument((XWPFDocument) document);
            } else if (document instanceof HWPFDocument) {
                return new WordHWPFDocument((HWPFDocument) document);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static WordDocument getInstance(XWPFDocument document) {
        try {
            return new WordXWPFDocument(document);
        } catch (Exception e) {
            return null;
        }
    }

    public static WordDocument getInstance(InputStream stream) throws IOException {
        String fileName = null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        org.apache.commons.io.IOUtils.copy(stream, baos);
        byte[] bytes = baos.toByteArray();
        if (OfficeExtractor.isSavePdf()) {
            File file = createOfficePDF();
            fileName = file.getAbsolutePath();
            OfficeConverter.convert(new ByteArrayInputStream(bytes), file, OfficeConverter::convertWord2PDF);
        }
        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        boolean converted = OfficeConverter.convert(new ByteArrayInputStream(bytes), baos1, OfficeConverter::convertDocToDocx);
        stream = new ByteArrayInputStream(baos1.toByteArray());
        if (converted) {
            return new WordXWPFDocument(new XWPFDocument(stream), fileName);
        } else {
            return new WordHWPFDocument(new HWPFDocument(stream), fileName);
        }
    }

    @Override
    public WordDocument getDocument() {
        return this;
    }

    @Override
    public FileType getFileType() {
        return FileType.WORD;
    }

    protected int getAndIncChartIndex() {
        int x = currentChartIndex;
        currentChartIndex += 1;
        return x;
    }

    protected int getAndIncTableIndex() {
        int x = currentTableIndex;
        currentTableIndex += 1;
        return x;
    }

    protected void postProcess(Table table) {
        table.removeTableId();
        if (table.isAllBlank()) {
            return;
        }
        if (table.shouldBeChart()) {

            tableToCharts(table);
            return;
        }
        if (table.shouldBeParagraph()) {
            tableToParagraphs(table);
            return;
        }
        table.detectTitle();
        this.tables.add(table);
    }





    protected static boolean maybeTitle(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        for (Pattern pattern : TITLE_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void test(String filename) {
        File files = new File(filename);
        for (File file : Objects.requireNonNull(files.listFiles())) {
            if (file.isDirectory()) {
                continue;
            }
            logger.info(file.getName());
            try {
                WordDocument wordDocument = WordDocument.getInstance(new FileInputStream(file));
                wordDocument.process();
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        WordDocument wordDocument = WordDocument.getInstance(new FileInputStream(args[0]));
        System.out.println("CREATOR: " + wordDocument.getMeta().creator);
        wordDocument.process();
//        wordDocument.getParagraphs().forEach(p->{
//            p.getTexts().forEach(t->{
//                System.out.println(t.getText());
//            });
//        });
    }

}
