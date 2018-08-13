package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.ExtractParameters;
import com.abcft.pdfextract.util.MapUtils;
import com.abcft.pdfextract.util.ProfileStopWatch;

import java.util.Map;

/**
 * Parameters for table extract algorithm.
 * Created by chzhong on 17-3-7.
 */
public class TableExtractParameters extends ExtractParameters {

    private static ExtractionMethod DEFAULT_ALGORITHM = ExtractionMethod.COMPOUND;
    private static OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.CSV;

    public static final class Builder extends ExtractParameters.Builder<TableExtractParameters> {

        ExtractionMethod algorithm;
        OutputFormat outputFormat;
        // 调试用，不支持从参数开启
        private boolean enableStopWatch = false;

        boolean guessAreas = true;
        boolean useBitmapPageFallback = true;
        boolean useNoRulingVectorFeatures = true;
        boolean useStructureFeatures = true;
        boolean useCrossPageTableMerge = true;      // 跨页表格合并，需要在自动化测试中关闭
        boolean useVectorRuleFilterTable = false;   // 利用矢量规则过滤表格
        boolean useChartResultInTableTest = false;  //在表格测试中时开启chart检测
        boolean useTableClassify = false;
        boolean useCRFModel = false;                //是否使用CRF模型进行表格区域获取
        boolean useCRFTableLayout = false;          //是否使用CRF模型进行表格区域获取
        String jsonDataFilesPath = null;            //已标注的Json数据路径


        public Builder() {
            this.algorithm = DEFAULT_ALGORITHM;
            this.outputFormat = DEFAULT_OUTPUT_FORMAT;
            this.savePreviewImage = false;
        }

        public Builder(Map<String, String> params) {
            super(params);
            this.savePreviewImage = MapUtils.getBoolean(params, "table.savePreviewImage", false);
            this.algorithm = MapUtils.getEnum(params, "table.algorithm",
                    ExtractionMethod.class, DEFAULT_ALGORITHM);
            this.outputFormat = OutputFormat.JSON;
            this.guessAreas = MapUtils.getBoolean(params, "table.guessAreas", guessAreas);
            this.useBitmapPageFallback = MapUtils.getBoolean(params, "table.bitmapPageFallback", useBitmapPageFallback);
            this.useNoRulingVectorFeatures = MapUtils.getBoolean(params, "table.noRulingVectorFeatures", useNoRulingVectorFeatures);
            this.useStructureFeatures = MapUtils.getBoolean(params, "table.structureFeatures", useStructureFeatures);
            this.useCrossPageTableMerge = MapUtils.getBoolean(params, "table.crossPageTableMerge", useCrossPageTableMerge);
            this.useVectorRuleFilterTable = MapUtils.getBoolean(params, "table.vectorRuleFilterTable", useVectorRuleFilterTable);
            this.useChartResultInTableTest = MapUtils.getBoolean(params, "table.useChartResultInTableTest", useChartResultInTableTest);
            this.useTableClassify = MapUtils.getBoolean(params, "table.useTableClassify", useTableClassify);
            this.useCRFModel = MapUtils.getBoolean(params, "table.useCRFModel", useCRFModel);
            setIgnoreAreas(params.get("table.ignoredAreas"));
            setHintAreas(params.get("table.hintAreas"));
        }

        public Builder setAlgorithm(ExtractionMethod algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder setOutputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder setGuessAreas(boolean guessAreas) {
            this.guessAreas = guessAreas;
            return this;
        }

        public Builder setUseBitmapPageFallback(boolean useBitmapPageFallback) {
            this.useBitmapPageFallback = useBitmapPageFallback;
            return this;
        }

        public Builder setUseNoRulingVectorFeature(boolean useNoRulingVectorFeatures) {
            this.useNoRulingVectorFeatures = useNoRulingVectorFeatures;
            return this;
        }

        public Builder setUseStructureFeature(boolean useStructureFeatures) {
            this.useStructureFeatures = useStructureFeatures;
            return this;
        }

        public Builder setUseCrossPageTableMerge(boolean useTableCombine) {
            this.useCrossPageTableMerge = useTableCombine;
            return this;
        }

        public Builder setUseVectorRuleFilterTable(boolean isUseVectorRule) {
            this.useVectorRuleFilterTable = isUseVectorRule;
            return this;
        }

        public Builder setUseChartResultInTableTest(boolean useChartResultInTableTest) {
            this.useChartResultInTableTest = useChartResultInTableTest;
            return this;
        }

        public Builder setUseTableClassify(boolean useTableClassify) {
            this.useTableClassify = useTableClassify;
            return this;
        }

        public Builder setStopWatchEnable(boolean enable) {
            this.enableStopWatch = enable;
            return this;
        }
        public Builder setJsonFilePaths(String path) {
            this.jsonDataFilesPath = path;
            return this;
        }
        public Builder setUseCRFModel(boolean enable){
            this.useCRFModel=enable;
            return this;
        }
        public Builder setUseCRFTableLayout(boolean enable){
            this.useCRFTableLayout=enable;
            return this;
        }
        @Override
        public TableExtractParameters build() {
            return new TableExtractParameters(this);
        }
    }


    TableExtractParameters(Builder builder) {
        super(builder);
        this.algorithm = builder.algorithm;
        this.outputFormat = builder.outputFormat;
        this.guessAreas = builder.guessAreas;
        this.useBitmapPageFallback = builder.useBitmapPageFallback;
        this.useNoRulingVectorFeatures = builder.useNoRulingVectorFeatures;
        this.useStructureFeatures = builder.useStructureFeatures;
        this.useCrossPageTableMerge = builder.useCrossPageTableMerge;
        this.useVectorRuleFilterTable = builder.useVectorRuleFilterTable;
        this.jsonDataFilesPath= builder.jsonDataFilesPath;
        this.useChartResultInTableTest = builder.useChartResultInTableTest;
        this.useTableClassify = builder.useTableClassify;
        this.useCRFModel= builder.useCRFModel;
        this.useCRFTableLayout=builder.useCRFTableLayout;
        this.stopWatch = ProfileStopWatch.createAccumulate("Table Extraction", builder.enableStopWatch);
    }

    @Override
    public Builder buildUpon() {
        return buildUpon(new Builder())
                .setAlgorithm(algorithm)
                .setGuessAreas(guessAreas)
                .setOutputFormat(outputFormat)
                .setUseBitmapPageFallback(useBitmapPageFallback)
                .setUseNoRulingVectorFeature(useNoRulingVectorFeatures)
                .setUseStructureFeature(useStructureFeatures)
                .setUseCrossPageTableMerge(useCrossPageTableMerge)
                .setUseVectorRuleFilterTable(useVectorRuleFilterTable)
                .setJsonFilePaths(jsonDataFilesPath)
                .setUseChartResultInTableTest(useChartResultInTableTest)
                .setUseCRFModel(useCRFModel)
                .setUseTableClassify(useTableClassify)
                .setUseCRFTableLayout(useCRFTableLayout);
    }

    public final ExtractionMethod algorithm;
    public final OutputFormat outputFormat;
    public final boolean guessAreas;
    public final boolean useBitmapPageFallback;
    public final boolean useNoRulingVectorFeatures;
    public final boolean useStructureFeatures;
    public final boolean useVectorRuleFilterTable;
    public final boolean useCrossPageTableMerge;
    public final boolean useChartResultInTableTest;
    public final boolean useTableClassify;
    public final boolean useCRFModel;
    public final boolean useCRFTableLayout;
    public final String jsonDataFilesPath;
    public final ProfileStopWatch stopWatch;

}
