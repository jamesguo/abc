package com.abcft.pdfextract.core.content;

import com.abcft.pdfextract.core.ExtractParameters;
import com.abcft.pdfextract.util.MapUtils;

import java.io.Writer;
import java.util.Map;

/**
 * Parameters for content extraction.
 *
 * Created by chzhong on 17-1-25.
 */
public final class ContentExtractParameters extends ExtractParameters {

    public static final class Builder extends ExtractParameters.Builder<ContentExtractParameters> {

        // 开启生成outline
        boolean generateOutline = true;
        boolean htmlWholeText = false;
        boolean enableGroup = true;
        boolean mergeCrossPageParagraphs = true;
        boolean useStructureFeatures = true;
        // 开启将全部走抽取目录的流程
        boolean useExtractOutline = true;
        TextSplitStrategy textSplitStrategy = TextSplitStrategy.DEFAULT;
        // 不解析没有结构化信息的页面, 测试使用, 不要开启
        public boolean skipNoStructureInfoPage = false;
        //使用版面信息获取结构化训练数据，测试使用，不要开启
        public boolean useLayoutInfoPage = false;

        public Builder() {
        }

        public Builder(Map<String, String> params) {
            super(params);
            this.enableGroup = MapUtils.getBoolean(params,"text.enableGroup", enableGroup);
            this.mergeCrossPageParagraphs = MapUtils.getBoolean(params,"text.mergeCPP", mergeCrossPageParagraphs);
            this.htmlWholeText = MapUtils.getBoolean(params,"html.wholeText", htmlWholeText);
            this.useStructureFeatures = MapUtils.getBoolean(params, "text.structureFeatures", useStructureFeatures);
            this.textSplitStrategy = TextSplitStrategy.fromLevel(MapUtils.getInt(params, "text.split_level", textSplitStrategy.getLevel()));
        }

        public Builder setGenerateOutline(boolean generateOutline) {
            this.generateOutline = generateOutline;
            return this;
        }

        public Builder setMergeCrossPageParagraphs(boolean mergeCrossPageParagraphs) {
            this.mergeCrossPageParagraphs = mergeCrossPageParagraphs;
            return this;
        }

        public Builder setHtmlWholeText(boolean htmlWholeText) {
            this.htmlWholeText = htmlWholeText;
            return this;
        }

        public Builder setEnableGroup(boolean enableGroup) {
            this.enableGroup = enableGroup;
            return this;
        }

        public Builder setUseStructureFeature(boolean useStructureFeatures) {
            this.useStructureFeatures = useStructureFeatures;
            return this;
        }

        public Builder setUseExtractOutline(boolean useExtractOutline) {
            this.useExtractOutline = useExtractOutline;
            return this;
        }

        public Builder setTextSplitStrategy(TextSplitStrategy textSplitStrategy) {
            this.textSplitStrategy = textSplitStrategy;
            return this;
        }
        
        public Builder setSkipNoStructureInfoPage(boolean skipNoStructureInfoPage) {
            this.skipNoStructureInfoPage = skipNoStructureInfoPage;
            return this;
        }

        public Builder setUseLayoutInfoPage(boolean useLayoutInfoPage) {
            this.useLayoutInfoPage = useLayoutInfoPage;
            return this;
        }

        @Override
        public ContentExtractParameters build() {
            return new ContentExtractParameters(this);
        }
    }

    private ContentExtractParameters(Builder builder) {
        super(builder);
        this.enableGroup = builder.enableGroup;
        this.generateOutline = builder.generateOutline;
        this.mergeCrossPageParagraphs = builder.mergeCrossPageParagraphs;
        this.htmlWholeText = builder.htmlWholeText;
        this.useStructureFeatures = builder.useStructureFeatures;
        this.useExtractOutline = builder.useExtractOutline;
        this.textSplitStrategy = builder.textSplitStrategy;
        this.skipNoStructureInfoPage = builder.skipNoStructureInfoPage;
        this.useLayoutInfoPage = builder.useLayoutInfoPage;
    }

    @Override
    public ContentExtractParameters.Builder buildUpon() {
        return buildUpon(new ContentExtractParameters.Builder())
                .setEnableGroup(enableGroup)
                .setGenerateOutline(generateOutline)
                .setMergeCrossPageParagraphs(mergeCrossPageParagraphs)
                .setHtmlWholeText(htmlWholeText)
                .setUseStructureFeature(useStructureFeatures)
                .setUseExtractOutline(useExtractOutline)
                .setSkipNoStructureInfoPage(skipNoStructureInfoPage)
                .setUseLayoutInfoPage(useLayoutInfoPage);
    }

    public final boolean generateOutline;
    public final boolean enableGroup;
    public final boolean htmlWholeText;
    public final boolean mergeCrossPageParagraphs;
    public final boolean useStructureFeatures;
    public final boolean useExtractOutline;
    public final TextSplitStrategy textSplitStrategy;
    public final boolean skipNoStructureInfoPage;
    public final boolean useLayoutInfoPage;

}
