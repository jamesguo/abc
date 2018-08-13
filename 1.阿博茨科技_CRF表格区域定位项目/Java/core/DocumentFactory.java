package com.abcft.pdfextract.core;

import com.abcft.pdfextract.core.office.ExcelDocument;
import com.abcft.pdfextract.core.office.PowerPointDocument;
import com.abcft.pdfextract.core.office.WordDocument;
import com.abcft.pdfextract.spi.Document;
import com.abcft.pdfextract.spi.FileType;
import com.abcft.pdfextract.spi.Meta;
import com.abcft.pdfextract.util.TextUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Created by dhu on 2018/3/15.
 */
public class DocumentFactory {
    private static final MemoryUsageSetting MEMORY_USAGE_SETTING = MemoryUsageSetting.setupMainMemoryOnly();

    public static Document load(String path) throws Exception {
        return load(new File(path), "");
    }

    public static Document load(File file) throws Exception {
        return load(file, "");
    }

    public static Document load(File file, String password) throws Exception {
        FileType fileType = FileType.fromPath(file.getPath());
        try (FileInputStream inputStream = new FileInputStream(file)) {
            return load(inputStream, fileType, password, "");
        }
    }

    public static Document load(InputStream inputStream, FileType fileType) throws Exception {
        return load(inputStream, fileType, "", "");
    }

    public static Document load(InputStream inputStream, FileType fileType, String password, String baseUrl) throws Exception {
        switch (fileType) {
            case PDF:
                return new PDFDocument(PDDocument.load(inputStream, password, MEMORY_USAGE_SETTING));
            case HTML:
                return new HTMLDocument(Jsoup.parse(TextUtils.loadHtmlString(inputStream), baseUrl));
            case EXCEL:
                return new ExcelDocument(inputStream);
            case WORD:
                return WordDocument.getInstance(inputStream);
            case POWERPOINT:
                return PowerPointDocument.getInstance(inputStream);
            default:
                throw new UnsupportedDocumentException("Unsupported file type: " + fileType);
        }
    }

    public static Document wrap(Object document) {
        if (document instanceof PDDocument) {
            return new PDFDocument((PDDocument) document);
        } else if (document instanceof org.jsoup.nodes.Document) {
            return new HTMLDocument((org.jsoup.nodes.Document) document);
        } else if (document instanceof Workbook) {
            return new ExcelDocument((Workbook) document);
        } else if (document instanceof SlideShow) {
            return new PowerPointDocument((SlideShow) document);
        } else if (document instanceof XWPFDocument || document instanceof HWPFDocument) {
            return WordDocument.getInstance(document);
        } else {
            return null;
        }
    }

    public static class PDFDocument implements Document<PDDocument> {
        private final PDDocument document;
        private Meta meta;

        private PDFDocument(PDDocument document) {
            this.document = document;
        }

        @Override
        public PDDocument getDocument() {
            return document;
        }

        @Override
        public FileType getFileType() {
            return FileType.PDF;
        }

        @Override
        public Meta getMeta() {
            if (meta == null) {
                meta = ExtractorUtil.getPDFMeta(document);
            }
            return meta;
        }
    }

    public static class HTMLDocument implements Document<org.jsoup.nodes.Document> {
        private final org.jsoup.nodes.Document document;

        private HTMLDocument(org.jsoup.nodes.Document document) {
            this.document = document;
        }

        @Override
        public org.jsoup.nodes.Document getDocument() {
            return document;
        }

        @Override
        public FileType getFileType() {
            return FileType.HTML;
        }

        @Override
        public Meta getMeta() {
            Meta meta = new Meta();
            meta.creator = "HTML";
            meta.title = document.title();
            return meta;
        }
    }

}
