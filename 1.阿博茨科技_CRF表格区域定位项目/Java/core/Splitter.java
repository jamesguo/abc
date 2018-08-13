package com.abcft.pdfextract.core;

import com.abcft.pdfextract.util.IntegerInterval;
import org.apache.commons.math3.util.FastMath;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Splitter {

    public static List<IntegerInterval> generateSegments(int numberOfPages, int splitLength) {
        ArrayList<IntegerInterval> segments = new ArrayList<>((int)Math.ceil(numberOfPages / (double)splitLength));
        for (int i = 1, start = 1; i <= numberOfPages; ++i) {
            if (i % splitLength == 0) {
                segments.add(IntegerInterval.create(start, i));
                start = i + 1;
            }
        }
        return segments;
    }

    public static List<IntegerInterval> generateSegments(int numberOfPages, int[] splitSteps) {
        ArrayList<IntegerInterval> segments = new ArrayList<>();
        int stepIndex = -1;
        int splitLength = splitSteps[++stepIndex];
        int start = 1;

        int end = start;
        while (end <= numberOfPages) {
            while (--splitLength > 0) {
                ++end;
            }
            segments.add(IntegerInterval.create(start, FastMath.min(end, numberOfPages)));
            start = end + 1;
            if (stepIndex >= splitSteps.length - 1) {
                stepIndex = -1;
            }
            splitLength = splitSteps[++stepIndex];
            end = start;
        }

        return segments;
    }

    public static List<IntegerInterval> generateSegments(int numberOfPages) {
        return generateSegments(numberOfPages, 1);
    }


    private MemoryUsageSetting memoryUsageSetting = null;

    /**
     * @return the current memory setting.
     */
    public MemoryUsageSetting getMemoryUsageSetting()
    {
        return memoryUsageSetting;
    }

    /**
     * Set the memory setting.
     *
     * @param memoryUsageSetting settings on how to use memory when loading PDF.
     */
    public void setMemoryUsageSetting(MemoryUsageSetting memoryUsageSetting)
    {
        this.memoryUsageSetting = memoryUsageSetting;
    }

    /**
     * Split specified document into specified segments.
     *
     * @param document The document to split.
     * @param segments the segments of each part.
     * @param callback optional callback.
     * @return A list of all the split documents.
     */
    public List<PDDocument> split(PDDocument document, List<IntegerInterval> segments, SplitCallback callback)
    {
        List<PDDocument> documents = new ArrayList<>(document.getNumberOfPages());
        if (callback != null) {
            callback.onStart(document);
        }
        int index = 0;
        for (IntegerInterval segment : segments) {
            try {
                PDDocument part = extractPages(document, segment.min, segment.max);
                boolean accept = true;
                if (callback != null) {
                    accept = callback.onSplit(index, segment, part);
                }
                if (accept) {
                    documents.add(document);
                }
            } catch (Throwable e) {
                if (callback != null) {
                    callback.onSplitError(segment, e);
                }
            } finally {
                ++index;
            }
        }
        if (callback != null) {
            callback.onFinished(documents);
        }
        return documents;
    }

    private PDDocument extractPages(PDDocument sourceDocument, int startPage, int endPage) throws IOException {
        PDDocument document = createNewDocument(sourceDocument);
        for (int i = startPage; i <= endPage; i++)
        {
            PDPage page = sourceDocument.getPage(i - 1);
            PDPage imported = document.importPage(page);
            imported.setResources(page.getResources());
            // remove page links to avoid copying not needed resources
            processAnnotations(imported);
        }
        return document;
    }


    /**
     * Create a new document to write the split contents to.
     *
     * @return the newly created PDDocument.
     * @throws IOException If there is an problem creating the new document.
     */
    private PDDocument createNewDocument(PDDocument sourceDocument) throws IOException {
        PDDocument document = memoryUsageSetting == null ?
                new PDDocument() : new PDDocument(memoryUsageSetting);
        document.getDocument().setVersion(sourceDocument.getVersion());
        document.setDocumentInformation(ExtractorUtil.getPdfDocumentInformation(sourceDocument));
        document.getDocumentCatalog().setViewerPreferences(
                sourceDocument.getDocumentCatalog().getViewerPreferences());
        return document;
    }

    private void processAnnotations(PDPage imported) throws IOException
    {
        List<PDAnnotation> annotations = imported.getAnnotations();
        for (PDAnnotation annotation : annotations)
        {
            if (annotation instanceof PDAnnotationLink)
            {
                PDAnnotationLink link = (PDAnnotationLink)annotation;
                PDDestination destination = link.getDestination();
                if (destination == null && link.getAction() != null)
                {
                    PDAction action = link.getAction();
                    if (action instanceof PDActionGoTo)
                    {
                        destination = ((PDActionGoTo)action).getDestination();
                    }
                }
                if (destination instanceof PDPageDestination)
                {
                    int pageNumber = ((PDPageDestination)destination).retrievePageNumber();
                    ((PDPageDestination)destination).setPage(null);
                    ((PDPageDestination)destination).setPageNumber(pageNumber);
                }
            }
            // TODO preserve links to pages within the split result
            annotation.setPage(null);
        }
    }

}
