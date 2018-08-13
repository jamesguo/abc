package com.abcft.pdfextract.core.table.extractors.gridless;

import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.core.table.TableExtractParameters;
import com.abcft.pdfextract.core.table.TableRegion;
import com.abcft.pdfextract.core.table.extractors.GridlessSheetExtractionAlgorithm;

import java.util.List;

/**
 * Algorithm to extract tables without grid lines.
 * <p>
 * Created by xcheng.abcft on 2017/3/21 0021.
 */
@Deprecated
public interface GridlessFeature {

    String getName();

    String getVersion();

    void prepare(Page page, TableExtractParameters params);
    default void reset() {
    }

    List<TableRegion> detect(Page page, GridlessSheetExtractionAlgorithm algorithm);

    List<? extends Table> extract(Page page, GridlessSheetExtractionAlgorithm algorithm);

    boolean isHit(Page page);
}
