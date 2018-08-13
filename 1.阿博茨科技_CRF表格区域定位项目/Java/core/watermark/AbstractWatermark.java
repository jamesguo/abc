package com.abcft.pdfextract.core.watermark;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public abstract class AbstractWatermark<T> implements Watermark<T> {

    private final PDDocument document;

    protected AbstractWatermark(PDDocument document) {
        this.document = document;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public PDPage remove(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        Set<String> watermarkNames = new HashSet<>();
        Iterable<COSName> xobjectNames = getCandidatesNames(resources);
        boolean changed = false;
        for (COSName xobjectName : xobjectNames) {
            T pdxObject = getCandidateItem(resources, xobjectName);
            if (match(xobjectName, pdxObject)) {
                changed |= true;
                watermarkNames.add(xobjectName.getName());
                removeCandidateItem(resources, xobjectName);
            }
        }

        // No watermark found.
        if (!changed) {
            return page;
        }

        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();
        Stack<Boolean> textState = new Stack<>();
        boolean removeText = false;
        textState.push(false);
        for (Object token : tokens)
        {
            if( token instanceof Operator)
            {
                Operator op = (Operator)token;
                String opName = op.getName();
                if (StringUtils.equalsAny(opName, "q", "BDC", "BMC", "BT")) {
                    textState.push(removeText);
                } else if (StringUtils.equalsAny(opName, "Q", "EMC", "ET")) {
                    if (!textState.isEmpty()) {
                        removeText = textState.pop();
                    } else {
                        removeText = false;
                    }
                }

                if(!StringUtils.equalsAny(opName, "TJ", "Tj", "gs", "Do")) {
                    newTokens.add( token );
                    continue;
                }

                Object operand = newTokens.get(newTokens.size() - 1);
                if (StringUtils.equalsAny(opName, "gs", "Do")) {
                    if (!(operand instanceof COSName)) {
                        newTokens.add( token );
                        continue;
                    }
                    COSName objectName = (COSName) operand;
                    if (watermarkNames.contains(objectName.getName())) {
                        if ("gs".equals(opName)) {
                            removeText = true;
                            if (textState.isEmpty()) {
                                textState.push(true);
                            } else {
                                textState.set(textState.size() - 1, true);
                            }
                        }
                        //remove the one argument to this operator
                        newTokens.remove( newTokens.size() -1 );
                        continue;
                    } else {
                        if ("gs".equals(opName) && removeText) {
                            removeText = false;
                            if (textState.isEmpty()) {
                                textState.push(false);
                            } else {
                                textState.set(textState.size() - 1, false);
                            }
                        }
                    }
                } else if (StringUtils.equalsAny(opName, "TJ", "Tj")) {
                    if (removeText) {
                        //remove the one argument to this operator
                        newTokens.remove( newTokens.size() -1 );
                        continue;
                    }
                }

            }
            newTokens.add( token );
        }

        // Ensure compress the content stream, otherwise the size of PDF might grow...
        PDStream newContents = new PDStream(document);
        OutputStream os = newContents.createOutputStream(COSName.FLATE_DECODE);
        ContentStreamWriter writer = new ContentStreamWriter(os);
        writer.writeTokens(newTokens);
        os.close();
        page.setContents(newContents);

        return page;
    }

    @Override
    public PDPage add(PDPage page, float x, float y) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean supportAdd() {
        return false;
    }

    @Override
    public boolean supportRemove() {
        return true;
    }
}
