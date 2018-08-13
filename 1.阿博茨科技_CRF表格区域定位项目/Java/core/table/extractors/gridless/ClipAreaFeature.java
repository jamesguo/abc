package com.abcft.pdfextract.core.table.extractors.gridless;

import com.abcft.pdfextract.core.table.Page;
import com.abcft.pdfextract.core.table.Table;
import com.abcft.pdfextract.core.table.TableRegion;
import com.abcft.pdfextract.core.table.extractors.VectorTableExtractionAlgorithm;
import com.abcft.pdfextract.core.table.extractors.GridlessSheetExtractionAlgorithm;
import com.abcft.pdfextract.core.table.extractors.TableExtractionAlgorithmType;

import java.util.List;
import java.util.stream.Collectors;


@Deprecated
public final class ClipAreaFeature extends AbstractGridlessFeature {

    private static final String ALGORITHM_NAME = TableExtractionAlgorithmType.CLIPAREAS_NAME;
    // 算法发布日期。
    private static final String ALGORITHM_DATE = "20180703";
    // 版本号不能带小数点(.),否则保存时会出错
    public static final String ALGORITHM_VERSION =  ALGORITHM_NAME + "-v" + ALGORITHM_DATE;

    private static final VectorTableExtractionAlgorithm delegate = new VectorTableExtractionAlgorithm();

    @Override
    public String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    public String getVersion() {
        return ALGORITHM_DATE;
    }

    @Override
    public List<TableRegion> detect(Page page, GridlessSheetExtractionAlgorithm algorithm) {
        return delegate.extract(page).stream().map(TableRegion::new).collect(Collectors.toList());
    }

    @Override
    public List<? extends Table> extract(Page page, GridlessSheetExtractionAlgorithm algorithm) {
        return delegate.extract(page);
    }

    @Override
    public boolean isHit(Page page) {
        return true;
    }

}
