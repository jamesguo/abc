/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractorUtil;
import com.abcft.pdfextract.core.PdfExtractor;
import com.abcft.pdfextract.spi.Document;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.TimeoutException;

/**
 * This class will take a pdf document and strip out all of the text and ignore the formatting and such. Please note; it
 * is up to clients of this class to verify that a specific user has the correct permissions to write text from the
 * PDF document.
 *
 * The basic flow of this process is that we get a document and use a series of processXXX() functions that work on
 * smaller and smaller chunks of the page. Eventually, we fully process each page and then print it.
 *
 * @author Cheng Zhong, Ben Litchfield
 */
public final class ContentExtractor implements PdfExtractor<ContentExtractParameters, Page, Fulltext, ContentExtractorCallback> {


    @Override
    public int getVersion() {
        return 40;
    }

    @SuppressWarnings("unused")
    private static final Logger logger = LogManager.getLogger(ContentExtractor.class);


    /**
     * Process a PDF document, notifying extraction events to specified handler.
     * @param doc The document to get the text from.
     * @param params the parameter of the algorithm.
     * @param callback the callback to receive events.
     */
    public Fulltext process(Document<PDDocument> doc, ContentExtractParameters params, ContentExtractorCallback callback) {
        return processDocument(doc, params,null, callback);
    }

    @Override
    public void processPage(PDDocument document, int pageIndex, PDPage page,
                            ContentExtractParameters params, Fulltext result, ContentExtractorCallback callback) {
        processPage(document, pageIndex, page, params, result, callback, null);
    }

    private Fulltext processDocument(Document<PDDocument> doc, ContentExtractParameters parameters, Writer outputStream, ContentExtractorCallback callback) {
        if (callback != null) {
            callback.onStart(doc.getDocument());
        }
        Fulltext fulltext = processPages(doc, parameters, outputStream, callback);
        postProcessing(doc.getDocument(), parameters, fulltext, callback);
        if (callback != null) {
            callback.onFinished(fulltext);
        }
        return fulltext;
    }

    private void buildOutline(PDDocument document, Fulltext fulltext, ContentExtractParameters parameters) {
        PDDocumentCatalog pdCatalog = document.getDocumentCatalog();
        PDDocumentOutline pdOutline = pdCatalog.getDocumentOutline();
        boolean usePdfOutline = false;
        Outline pdfOutline = new Outline(document, true);
        if (pdOutline != null) {
            try {
                buildFromPDOutline(pdfOutline, pdOutline, document, parameters);
                if (OutlineNode.isVaildOutline(pdfOutline)) {
                    usePdfOutline = true;
                }
            } catch (Exception e) {
                logger.debug("builtin outline bulid error.", e);
                usePdfOutline = false;
            }
        }

        // 增加抽取开关，如果开启则全部走抽取流程
        if (parameters.useExtractOutline) {
            if (usePdfOutline) {
                fulltext.setOutline(pdfOutline);
            } else {
                Outline outline = new Outline(document, false);
                fulltext.setOutline(outline);
            }

        } else {
            if (pdOutline != null && usePdfOutline) {
                fulltext.setOutline(pdfOutline);
            }
        }
    }

    private void buildFromPDOutline(OutlineNode node, PDOutlineNode pdNode, PDDocument document, ContentExtractParameters parameters) {
        PDOutlineItem pdItem = pdNode.getFirstChild();
        while (pdItem != null) {
            OutlineItem item = new OutlineItem(pdItem, document, parameters.context);
            node.addChild(item);
            buildFromPDOutline(item, pdItem, document, parameters);
            pdItem = pdItem.getNextSibling();
        }
    }

    private Fulltext processPages(Document<PDDocument> document, ContentExtractParameters parameters, Writer output, ContentExtractorCallback callback) {
        Fulltext fulltext = new Fulltext(document);
        ExtractorUtil.forEachEffectivePages(document.getDocument(), parameters, (pageIndex, page) -> {
            processPage(document.getDocument(), pageIndex, page, parameters, fulltext, callback, output);
        });
        return fulltext;
    }

    private void processPage(PDDocument document, int pageIndex, PDPage pdPage, ContentExtractParameters parameters,
                             Fulltext fulltext, ContentExtractorCallback callback, Writer output) {
        if (pdPage.hasContents()) {
            try {
                TextExtractEngine engine = new TextExtractEngine(document, parameters, callback);
                Page page = engine.processPage(pageIndex + 1, pdPage);
                fulltext.addPage(page);
                if (output != null) {
                    output.write(fulltext.getText());
                }
                writeLineSeparator(output);
                writeLineSeparator(output);
            } catch (TimeoutException e) {
                logger.error("Timeout handling page #" + (pageIndex + 1), e);
            } catch (Exception e) {
                logger.error("Error handling page #" + (pageIndex + 1), e);
                fulltext.recordError(e);
                if (callback != null) {
                    callback.onExtractionError(e);
                }
            }
        }
    }

    @Override
    public void postProcessing(PDDocument document, ContentExtractParameters params, Fulltext result, ContentExtractorCallback callback) {
        result.setExtractContext(params.context);
        if (params.generateOutline) {
            buildOutline(document, result, params);
        }
        result.buildPages();
    }

    /**
     * Write the line separator value to the output stream.
     *
     * @throws IOException If there is a problem writing out the lineseparator to the document.
     */
    private void writeLineSeparator(Writer output) throws IOException
    {
        if (output != null) {
            output.write(ExtractorUtil.LINE_SEPARATOR);
        }
    }

}
