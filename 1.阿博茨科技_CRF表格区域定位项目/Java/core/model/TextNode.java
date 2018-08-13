package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.core.content.ParagraphMerger;
import com.abcft.pdfextract.core.content.TextChunkMerger;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2018/1/26.
 */
public class TextNode {

    protected final PDStructureNode structureNode;
    private final String structureType;
    private final List<TextChunk> textChunks;
    private TextNode parent;
    private List<TextNode> children;

    public TextNode(PDStructureNode structureNode) {
        this.structureNode = structureNode;
        this.textChunks = new ArrayList<>();
        this.children = new ArrayList<>();
        if (structureNode instanceof PDStructureElement) {
            structureType = getStandardStructureType((PDStructureElement)structureNode);
        } else {
            structureType = "Root";
        }
    }

    public boolean isRoot() {
        return parent == null;
    }

    public TextNode getParent() {
        return parent;
    }

    public boolean parentIsType(String type) {
        return parent != null && StringUtils.equals(parent.getStructureType(), type);
    }

    public void addTextChunk(TextChunk textChunk) {
        textChunks.add(textChunk);
    }

    public List<TextChunk> getTextChunks() {
        return textChunks;
    }

    public List<TextChunk> getAllTextChunks() {
        List<TextChunk> list = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            list.add(new TextChunk(textChunk));
        }
        for (TextNode child : children) {
            for (TextChunk textChunk : child.getAllTextChunks()) {
                list.add(new TextChunk(textChunk));
            }
        }
        list.sort(Comparator.comparing(TextChunk::getMcid).thenComparing(TextChunk::getGroupIndex));
        return list;
    }

    public TextBlock toTextBlock() {
        TextBlock textBlock = new TextBlock();
        textBlock.setAdjustText(false);
        // 把TextChunk合并成行, 然后在放入TextBlock, 和非结构化解析的结果保持一致
        List<TextChunk> textChunks = getAllTextChunks();

        //将textBlock中的textChunk合并成oneLine
        textChunks = new TextChunkMerger().merge(textChunks);
        textChunks = ParagraphMerger.mergeToLine(textChunks);
        if (textChunks.isEmpty()) {
            return textBlock;
        }

        textBlock.addElements(textChunks);
        return textBlock;
    }

    public List<TextNode> getChildren() {
        return children;
    }

    public PDStructureNode getStructureNode() {
        return structureNode;
    }

    /**
     * 获取结构化类型, 这个类型会使用RoleMap进行映射
     * @return
     */
    public String getStructureType() {
        return structureType;
    }

    /**
     * 获取原始的结构化类型, 这个类型不使用RoleMap进行映射
     * @return
     */
    public String getOriginalType() {
        if (structureNode instanceof PDStructureElement) {
            return ((PDStructureElement) structureNode).getStructureType();
        }
        return structureType;
    }

    public Set<String> getAllStructureType() {
        Set<String> types = new HashSet<>();
        types.add(structureType);
        for (TextNode tn : children) {
            types.addAll(tn.getAllStructureType());
        }
        return types;
    }

    public void addChild(TextNode textNode) {
        textNode.parent = this;
        this.children.add(textNode);
    }

    public boolean isEmpty() {
        return textChunks.isEmpty() && children.isEmpty();
    }

    public void removeEmptyNodes() {
        for (Iterator<TextNode> iterator = children.iterator(); iterator.hasNext(); ) {
            TextNode child = iterator.next();
            child.removeEmptyNodes();
            if (child.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public List<TextNode> findByStructureType(String type) {
        return findBy(t -> t.getStructureType().equals(type) || t.getOriginalType().equals(type));
    }

    public List<TextNode> findByStructureTypes(Set<String> types) {
        return findBy(t -> types.contains(t.getStructureType()) || types.contains(t.getOriginalType()));
    }

    public List<TextNode> deepFindByStructureTypes(Set<String> types) {
        return deepFindBy(t -> types.contains(t.getStructureType()) || types.contains(t.getOriginalType()));
    }

    /**
     * 查找树, 找到匹配的节点后, 不继续从该节点的子节点往下查找
     * @param predicate
     * @return
     */
    public List<TextNode> findBy(Predicate<TextNode> predicate) {
        List<TextNode> list = new ArrayList<>();
        if (predicate.test(this)) {
            list.add(this);
        } else if (!children.isEmpty()) {
            for (TextNode tn : children) {
                list.addAll(tn.findBy(predicate));
            }
        }
        return list;
    }

    /**
     * 查找树, 找到匹配的节点后, 如果该节点的子节点有匹配的结果则继续往下找, 舍弃该节点
     * @param predicate
     * @return
     */
    public List<TextNode> deepFindBy(Predicate<TextNode> predicate) {
        List<TextNode> list = new ArrayList<>();
        boolean found = false;
        if (!children.isEmpty()) {
            for (TextNode tn : children) {
                List<TextNode> subList = tn.deepFindBy(predicate);
                if (!subList.isEmpty()) {
                    found = true;
                    list.addAll(subList);
                }
            }
        }
        if (!found) {
            if (predicate.test(this)) {
                list.add(this);
            }
        }
        return list;
    }

    public String getText() {
        StringBuilder builder = new StringBuilder();
        for (TextChunk textChunk : getAllTextChunks()) {
            builder.append(textChunk.getActualTextOrText());
        }
        return builder.toString();
    }

    public Rectangle getBounds2D() {
        return Rectangle.union(getAllTextChunks());
    }

    public List<Integer> getMcids() {
        List<Integer> mcids = new ArrayList<>();
        for (Object kid : structureNode.getKids()) {
            if (kid instanceof Integer) {
                mcids.add((Integer) kid);
            }
        }
        for (TextNode child : children) {
            mcids.addAll(child.getMcids());
        }
        return mcids;
    }
    
    public List<TextNode> getAllLeafNodes() {
        List<TextNode> nodes = new ArrayList<>();
        if (this.children.isEmpty()) {
            nodes.add(this);
        } else {
            for (TextNode child : children) {
                nodes.addAll(child.getAllLeafNodes());
            }
        }
        return nodes;
    }


    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("TextNode[type=").append(structureType).append(", text=\"");
        builder.append(getText());
        builder.append("\", mcids=").append(getMcids());
        builder.append("]");
        return builder.toString();
    }

    /**
     * Returns the structure tree root.
     *
     * @return the structure tree root
     */
    private static PDStructureTreeRoot getStructureTreeRoot(PDStructureElement node) {
        PDStructureNode parent = node.getParent();
        while (parent instanceof PDStructureElement) {
            parent = ((PDStructureElement) parent).getParent();
        }
        if (parent instanceof PDStructureTreeRoot) {
            return (PDStructureTreeRoot) parent;
        }
        return null;
    }

    /**
     * Returns the standard structure type, the actual structure type is mapped
     * to in the role map.
     *
     * @return the standard structure type
     */
    public static String getStandardStructureType(PDStructureElement element) {
        String type = element.getStructureType();
        PDStructureTreeRoot root = getStructureTreeRoot(element);
        if (root == null) {
            return type;
        }
        Map<String,Object> roleMap = root.getRoleMap();
        if (roleMap != null && roleMap.containsKey(type)) {
            Object mappedValue = roleMap.get(type);
            if (mappedValue instanceof String) {
                type = (String)mappedValue;
            }
        }
        return type;
    }

    // 通过ParentTree自下往上获取的树状结构子节点的排序可能不对, 需要根据StructNode里面的信息重新排序
    public void fixChildrenOrder(PDPage currentPage) {
        List<Object> kids = structureNode.getKids();
        List<PDStructureElement> childNodes = kids.stream()
                .filter(kid -> kid instanceof PDStructureElement)
                .map(kid -> (PDStructureElement)kid)
                .collect(Collectors.toList());
        List<TextNode> fixedChidren = new ArrayList<>(childNodes.size());
        for (PDStructureElement childNode : childNodes) {
            Optional<TextNode> find = children.stream()
                    .filter(child -> child.getStructureNode().getCOSObject().equals(childNode.getCOSObject()))
                    .findFirst();
            if (find.isPresent()) {
                fixedChidren.add(find.get());
            } else {
                if (currentPage.equals(childNode.getPage())) {
                    // 一些空的没有MCID的节点会在解析ParentTree的时候丢失, 这里还原
                    fixedChidren.add(new TextNode(childNode));
                }
            }
        }
        children = fixedChidren;
        for (TextNode child : children) {
            child.fixChildrenOrder(currentPage);
        }
    }

    public void clear() {
        this.textChunks.clear();
        for (TextNode child : children) {
            child.clear();
        }
        this.children.clear();
        this.parent = null;
    }
}
