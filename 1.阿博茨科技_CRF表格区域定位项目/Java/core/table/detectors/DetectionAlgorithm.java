package com.abcft.pdfextract.core.table.detectors;


import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.model.Rectangle;

import java.util.List;

/**
 * Created by matt on 2015-12-14.
 */
public interface DetectionAlgorithm {
    List<Rectangle> detect(Page page);
}
