package com.abcft.pdfextract.core.table.extractors;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.table.detectors.DetectionAlgorithm;
import com.abcft.pdfextract.core.table.extractors.gridless.ClipAreaFeature;
import com.abcft.pdfextract.core.table.extractors.gridless.GridlessFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

@Deprecated
public class GridlessSheetExtractionAlgorithm implements ExtractionAlgorithm, DetectionAlgorithm {

    public static final Logger logger = LogManager.getFormatterLogger(GridlessSheetExtractionAlgorithm.class);


    private GridlessFeature primaryFeature;


    public GridlessSheetExtractionAlgorithm(Class<? extends GridlessFeature> primaryFeatureClass) {
        try {
            primaryFeature = primaryFeatureClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new AssertionError("Failed to initialize feature.");
        }
    }

    public GridlessSheetExtractionAlgorithm() {
        this(ClipAreaFeature.class);
    }


    /**
     * Get the version of the algorithm.
     *
     * @return the version of the algorithm.
     */
    @Override
    public String toString() {
        if (primaryFeature != null) {
            return primaryFeature.getName();
        } else {
            return "gridless";
        }
    }

    @Override
    public String getVersion() {
        if (primaryFeature != null) {
            return primaryFeature.getVersion();
        } else {
            return "<Unknown>";
        }
    }

    @Override
    public List<? extends Table> extract(Page page) {
        primaryFeature.prepare(page, page.params);
        List<? extends Table> tables;
        TableExtractParameters params = page.params;

        TableDebugUtils.dumpDebugDocumentInfo(params, logger);

        // 触发预计算
        primaryFeature.isHit(page);
        tables = primaryFeature.extract(page, this);
        primaryFeature.reset();

        return tables;
    }

    @Override
    public List<Rectangle> detect(Page page) {
        List<TableRegion> regions = primaryFeature.detect(page, this);
        if (null == regions) {
            return null;
        }
        return regions.stream().map(Rectangle::new).collect(Collectors.toList());
    }


}

