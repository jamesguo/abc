package com.abcft.pdfextract.core.watermark;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

import java.io.IOException;

/**
 * Represents a watermark.
 */
public interface Watermark<T> {

    /**
     * Retrieve a watermark candidate item.
     *
     * @param resources the resources.
     * @return an {@link Iterable} of candidate names.
     */
    Iterable<COSName> getCandidatesNames(PDResources resources);

    /**
     * Retrieve a watermark candidate item with the specified name from resource.
     *
     * @param resources the resources.
     * @param name the name of the candidate item.
     * @return an candidate item of specified name.
     * @throws IOException Any I/O Error.
     */
    T getCandidateItem(PDResources resources, COSName name) throws IOException;

    /**
     * Remove a watermark item with the specified name from resource.
     *
     * @param resources the resources.
     * @param name the name of the candidate item to remove.
     */
    void removeCandidateItem(PDResources resources, COSName name);

    /**
     * Determine whether an T matches features of this watermark.
     * @param name the name of the item to test.
     * @param item the item to test.
     * @return {@code true} if the specified object matches features of this watermark, {@code false} otherwise.
     */
    boolean match(COSName name, T item);

    /**
     * Remove watermarks from a PDPage.
     * @param page the original page.
     * @return a modified page with all watermark of this type get removed.
     */
    PDPage remove(PDPage page) throws IOException;
    /**
     * Adds watermarks to a PDPage.
     * @param page the original page.
     * @param x the x position of the watermark in page coordinate.
     * @param y the y position of the watermark in page coordinate.
     * @return a modified page with a news watermark at specified position.
     */
    PDPage add(PDPage page, float x, float y);

    /**
     * Indicates whether this watermark supports adding new watermark.
     * Only in case of {@code true}, {@link #add(PDPage, float, float)} might work.
     *
     * @return {@code true} if we supports adding new watermark.
     */
    boolean supportAdd();
    /**
     * Indicates whether this watermark supports removing existing watermarks.
     * Only in case of {@code true}, {@link #remove(PDPage)} might work.
     *
     * @return {@code true} if we supports watermark removal.
     */
    boolean supportRemove();
}
