package com.abcft.pdfextract.core.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextTree extends TextNode {

    private static final Pattern WORD_PATTERN = Pattern.compile("Word (\\d+)");
    private final PDStructureTreeRoot root;
    private List<TextChunk> noStructTextChunk;
    private boolean broken;
    private boolean hasFigure;
    private float pdfVersion;
    private String producer;

    public TextTree(PDStructureTreeRoot treeRoot) {
        super(treeRoot);
        this.root = treeRoot;
        this.noStructTextChunk = new ArrayList<>();
    }


    public void setNoStructTextChunk(List<TextChunk> noStructTextChunk) {
        this.noStructTextChunk = new ArrayList<>(noStructTextChunk);
    }

    public List<TextChunk> getNoStructTextChunk() {
        return noStructTextChunk;
    }

    public boolean allTextChunkHasStructInfo() {
        return getNoStructTextChunk().stream().allMatch(textChunk -> textChunk.getPaginationType() != null);
    }

    public void setBroken(boolean broken) {
        this.broken = broken;
    }

    public boolean isBroken() {
        return broken;
    }

    public boolean hasRoleMap() {
        Map<String, Object> roleMap = root.getRoleMap();
        return roleMap != null && !roleMap.isEmpty();
    }

    public void setHasFigure(boolean hasFigure) {
        this.hasFigure = hasFigure;
    }

    public boolean hasFigure() {
        return hasFigure;
    }

    public void setPdfVersion(float pdfVersion) {
        this.pdfVersion = pdfVersion;
    }

    public void setProducer(String producer) {
        this.producer = producer;
    }

    public float getPdfVersion() {
        return pdfVersion;
    }

    public boolean canUseStructInfoForTableExtraction() {
        return !broken && allTextChunkHasStructInfo();
    }

    public boolean canUseStructInfoForTextExtraction() {
        return !broken && (pdfVersion >= 1.6 || getWordVersion() > 2007) && allTextChunkHasStructInfo();
    }

    private int getWordVersion() {
        if (StringUtils.isBlank(producer)) {
            return 0;
        }
        Matcher matcher = WORD_PATTERN.matcher(producer);
        if (matcher.find()) {
            String version = matcher.group(1);
            return NumberUtils.toInt(version, 0);
        } else {
            return 0;
        }
    }

    public void clear() {
        this.noStructTextChunk.clear();
        super.clear();
    }
}
