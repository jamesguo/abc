package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.table.detectors.DetectionAlgorithm;
import com.abcft.pdfextract.core.table.extractors.*;
import com.abcft.pdfextract.core.table.extractors.gridless.ClipAreaFeature;
import com.abcft.pdfextract.core.util.Version;

import java.util.ArrayList;
import java.util.List;

public enum ExtractionMethod {
    @Deprecated
    GRIDLESS {
        @Override
        public String getAlgorithmName() {
            return "gridless";
        }

        @Override
        public ExtractionAlgorithm getExtractionAlgorithm(Page page) {
            return new GridlessSheetExtractionAlgorithm();
        }

        @Override
        public DetectionAlgorithm getDetectionAlgorithm(Page page) {
            return null;
        }
    },
    @Deprecated
    CLIP_AND_REGROUP {
        @Override
        public String getAlgorithmName() {
            if (Version.isRelease()) {
                return ClipAreaFeature.ALGORITHM_VERSION;
            } else {
                return ClipAreaFeature.ALGORITHM_VERSION + "a";
            }
        }

        @Override
        public ExtractionAlgorithm getExtractionAlgorithm(Page page) {
            return new GridlessSheetExtractionAlgorithm(ClipAreaFeature.class);
        }

        @Override
        public DetectionAlgorithm getDetectionAlgorithm(Page page) {
            return null;
        }
    },
    COMPOUND {
        @Override
        public String getAlgorithmName() {
            return CompoundTableExtractionAlgorithm.ALGORITHM_NAME;
        }

        @Override
        public ExtractionAlgorithm getExtractionAlgorithm(Page page) {
            return CompoundTableExtractionAlgorithm.INSTANCE;
        }

        @Override
        public DetectionAlgorithm getDetectionAlgorithm(Page page) {
            return null;
        }
    },
    BITMAP_PAGE {
        @Override
        public String getAlgorithmName() {
            return BitmapPageExtractionAlgorithm.ALGORITHM_NAME;
        }

        @Override
        public ExtractionAlgorithm getExtractionAlgorithm(Page page) {
            return new BitmapPageExtractionAlgorithm();
        }

        @Override
        public DetectionAlgorithm getDetectionAlgorithm(Page page) {
            return null;
        }

        @Override
        public List<Table> extractTables(Page page, boolean guessArea) {
            ExtractionAlgorithm extractor = getExtractionAlgorithm(page);
            return new ArrayList<>(extractor.extract(page));
        }

    },
    VECTOR {
        @Override
        public String getAlgorithmName() {
            return VectorTableExtractionAlgorithm.ALGORITHM_NAME;
        }

        @Override
        public ExtractionAlgorithm getExtractionAlgorithm(Page page) {
            return new VectorTableExtractionAlgorithm();
        }

        @Override
        public DetectionAlgorithm getDetectionAlgorithm(Page page) {
            return null;
        }
    },
    STRUCT {

        @Override
        public String getAlgorithmName() {
            return "Structure";
        }

        @Override
        public ExtractionAlgorithm getExtractionAlgorithm(Page page) {
            return StructTableExtractionAlgorithm.INSTANCE;
        }

        @Override
        public DetectionAlgorithm getDetectionAlgorithm(Page page) {
            return null;
        }

    };

    public abstract String getAlgorithmName();

    public abstract ExtractionAlgorithm getExtractionAlgorithm(Page page);

    public abstract DetectionAlgorithm getDetectionAlgorithm(Page page);

    public List<Table> extractTables(Page page, boolean guessArea) {
        ExtractionAlgorithm extractor = getExtractionAlgorithm(page);

        //noinspection LoopStatementThatDoesntLoop,ConstantConditions
        do {
            if (!guessArea)
                break;
            DetectionAlgorithm detector = getDetectionAlgorithm(page);
            if (null == detector)
                break;
            // guess the page areas to writeTables using a detection algorithm
            // currently we only have a detector that uses spreadsheets to find table areas
            List<Rectangle> guesses = detector.detect(page);
            if (null == guesses)
                break;
            List<Table> tables = new ArrayList<>(Math.max(guesses.size(), 10 /* DEFAULT_CAPACITY */));
            for (Rectangle guessRect : guesses) {
                Page guess = page.getArea(guessRect);
                tables.addAll(extractor.extract(guess));
            }
            return tables;
        } while (false);

        return new ArrayList<>(extractor.extract(page));
    }

    public static String[] formatNames() {
        ExtractionMethod[] values = ExtractionMethod.values();
        String[] rv = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            rv[i] = values[i].name();
        }
        return rv;
    }

}
