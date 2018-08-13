package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.core.ContentGroupImageDrawer;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.util.Taggable;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by dhu on 2017/5/8.
 */
public class ContentGroup implements Taggable {

    private static final Set<String> GRAPHICS_START_OP = Sets.newHashSet(
            "sc", "scn", "cs", "k", "g", "rg", // set non stroking color
            "SC", "SCN", "CS", "K", "G", "RG", // set stroking color,
            "re", "m", "gs", "J", "j", "d", "w" // draw
    );
    private static final Set<String> GRAPHICS_END_OP = Sets.newHashSet(
            "s", "S", "b", "B", "f", "f*", "b*", "B*"
    );


    private PDFormXObject form;
    private ContentGroup parent;
    private Rectangle2D area = new Rectangle2D.Double();    // 实际可见区域
    private Rectangle2D clipArea = new Rectangle2D.Double();    // 绘制时的裁剪区域
    private List<Object> tokens = new ArrayList<>();
    private PDGraphicsState graphicsState;
    private boolean ended = false;
    private List<ContentItem> items = new ArrayList<>();
    private ContentGroup fakeContentGroup;
    private final boolean fake;
    private AffineTransform pageTransform;
    private TextTree textTree;
    private int glyphCount;
    private List<TextChunk> allTextChunks = null;

    private final Map<String, Object> tags = new HashMap<>();  // 辅助标记信息

    public ContentGroup(PDGraphicsState state, AffineTransform pageTransform) {
        this(state,  pageTransform,false);
    }

    public ContentGroup(PDGraphicsState state, AffineTransform pageTransform, boolean fake) {
        this.graphicsState = state != null ? state.clone() : null;
        this.pageTransform = pageTransform;
        if (state != null) {
            Shape clippingPath = state.getCurrentClippingPath();
            Shape pageClippingPath = pageTransform.createTransformedShape(clippingPath);
            clipArea = pageClippingPath.getBounds2D();
        }
        this.fake = fake;
    }

    public void clear() {
        form = null;
        parent = null;
        tokens.clear();
        graphicsState = null;
        items.clear();
        if (fakeContentGroup != null) {
            fakeContentGroup.clear();
            fakeContentGroup = null;
        }
    }

    public void end() {
        if (ended) return;
        ended = true;
        endFakeContentGroup();
        merge();
        mergeTextChunkItem();
        // merge area
        forEach(ChildItem.class, item -> adjustBbox(item.getItem().getArea()));
        // apply clip

        Shape clippingPath = this.getGraphicsState().getCurrentClippingPath();
        Shape transformedClippingPath = getPageTransform().createTransformedShape(clippingPath);
        Rectangle2D transformedClippingPathBounds = transformedClippingPath.getBounds2D();

        Rectangle2D.intersect(area, transformedClippingPathBounds, area);

        if (isRoot()) {
            // 整个Page的root contentgroup结束后, 合并阴影的文字
            mergeShadowText();
        }
    }

    private void mergeTextChunkItem() {
        // 合并那种有指定ActualText的marked content包含的TextChunk
        // 类似于这样的:
        // /Span << /ActualText "þÿ\0\9\0\9\0\9\0\9\0\9\0\9" >> BDC
        // 8.5 0 0 8.5 85.6585 450.0455 Tm
        // " " Tj
        // 8.5 0 0 8.5 44 450.0455 Tm
        // 10.589 0 Td
        // " " Tj
        // 7.058 0 Td
        // "  " Tj
        // 7.647 0 Td
        // [ "  " ] TJ
        // EMC
        ContentItem lastItem = null;
        for (Iterator<ContentItem> iterator = items.iterator(); iterator.hasNext(); ) {
            ContentItem item = iterator.next();
            if (lastItem instanceof TextChunkItem
                    && item instanceof TextChunkItem
                    && ((TextChunkItem) lastItem).canMerge((TextChunkItem) item)) {
                ((TextChunkItem) lastItem).merge((TextChunkItem) item);
                iterator.remove();
                continue;
            }
            lastItem = item;
        }
    }

    // 移除特殊阴影。(星源材质、精功科技 等 GPL Ghostscript 生成的）
    // 有的阴影是下面这种形式的：
    //       /R13 12 Tf
    //      0.99941 0 0 1 56.88 688.76 Tm
    //      "一" Tj
    //      -0.240142 -0.24 Td
    //      [ "一一" ] TJ
    //      0 0.24 Td
    //      [ "一、" ] TJ
    //      12.1272 -0.24 Td
    //      [ "、、" ] TJ
    //      0 0.24 Td
    //      [ "、财务报表" ] TJ
    //      12.0071 -0.24 Td
    //      [ "财务报表" ] TJ
    //      0.240142 0 Td
    //      [ "财务报表" ] TJ
    //      -0.240142 0.24 Td
    //      [ "财务报表" ] TJ
    //
    private void mergeShadowText() {
        List<TextChunk> textChunks = getAllTextChunks();
        TextChunk lastTextChunk = null;
        for (TextChunk textChunk : textChunks) {
            if (lastTextChunk != null) {
                lastTextChunk.mergeShadow(textChunk);
            }
            lastTextChunk = textChunk;
        }
    }

    public AffineTransform getPageTransform() {
        return pageTransform;
    }

    public PDGraphicsState getGraphicsState() {
        return graphicsState;
    }

    public void setForm(PDFormXObject form, Rectangle2D area) {
        this.form = form;
        adjustBbox(area);
    }

    public boolean isPDFormXObject() {
        return form != null;
    }

    public boolean inForm() {
        //return parent != null && parent.isPDFormXObject();
        if (parent != null && parent.isPDFormXObject()) {
            return true;
        }
        else if (parent != null) {
            return parent.inForm();
        }
        else {
            return false;
        }
    }

    public boolean hasPDFormXObject() {
        if (isPDFormXObject()) {
            return true;
        }
        for (ContentItem item : items) {
            if (item instanceof ChildItem && ((ChildItem) item).getItem().hasPDFormXObject()) {
                return true;
            }
        }
        return false;
    }

    public ContentGroup getFormContent() {
        if (isPDFormXObject()) {
            return this;
        }
        for (ContentItem item : items) {
            if (item instanceof ChildItem && ((ChildItem) item).getItem().hasPDFormXObject()) {
                return ((ChildItem) item).getItem().getFormContent();
            }
        }
        return null;
    }

    public PDFormXObject getForm() {
        if (form != null) {
            return form;
        }
        else if (inForm()) {
            return parent.getForm();
        }
        return form;
    }

    public boolean hasOnlyChildItem() {
        if (items.isEmpty()) {
            return false;
        }
        for (ContentItem item : items) {
            if (!(item instanceof ChildItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean isTextContent() {
        Operator operator = getFirstOperator();
        if (operator == null) {
            return false;
        }
        String name = operator.getName();
        return PDFOperator.BEGIN_TEXT_OBJECT.equals(name);
    }

    public Rectangle2D getArea() {
        return (Rectangle2D) area.clone();
    }

    private boolean isRoot() {
        return parent == null;
    }

    public ContentGroup getRoot() {
        ContentGroup p = this;
        while (p.parent != null) {
            p = p.parent;
        }
        return p;
    }

    private Operator getFirstOperator() {
        for (Object token : tokens) {
            if (token instanceof Operator) {
                return (Operator) token;
            }
        }
        return null;
    }

    public List<ContentItem> getItems() {
        return items;
    }

    public ContentGroup getSubgroup(Rectangle2D area) {
        ContentGroup subgroup = new ContentGroup(getGraphicsState(), getPageTransform(), true);
        for (ContentItem item : items) {
            Rectangle2D itemArea = null;
            if (item instanceof ChildItem) {
                ContentGroup itemGroup = (ContentGroup) item.getItem();
                ContentGroup childSubgroup = itemGroup.getSubgroup(area);
                if (!childSubgroup.items.isEmpty()) {
                    subgroup.add(childSubgroup);
                }
            } else if (item instanceof TextChunkItem) {
                itemArea = ((TextChunkItem)item).getItem().getBounds2D();
            } else if (item instanceof PathItem) {
                itemArea = ((PathItem)item).getItem().getBounds2D();
            }
            if (itemArea != null && GraphicsUtil.nearlyContains(area, itemArea, 1.0f)) {
                subgroup.items.add(item);
            }
        }
        subgroup.endFakeContentGroup();
        return subgroup;
    }

    public ContentGroup getSubgroup(ContentItem item) {
        ContentGroup subgroup = new ContentGroup(getGraphicsState(), getPageTransform(), true);
        subgroup.setArea(getArea());
        subgroup.setClipArea(clipArea);
        subgroup.endFakeContentGroup();
        subgroup.ended = this.ended;
        subgroup.form = form;
        subgroup.glyphCount = this.glyphCount;
        subgroup.textTree = this.textTree;
        subgroup.tokens = this.tokens;
        subgroup.parent = this.parent;
        subgroup.items.add(item);
        subgroup.addTags(tags);
        return subgroup;
    }

    public Rectangle2D getItemsArea(double expandLen) {
        List<ContentItem> allItems = getAllItems();
        int n = allItems.size();
        if (n == 0 || n >= 2) {
            return getArea();
        }
        ContentItem item = allItems.get(0);
        Rectangle2D itemArea = null;
        if (item instanceof TextChunkItem) {
            itemArea = ((TextChunkItem)item).getItem().getBounds2D();
        } else if (item instanceof PathItem) {
            itemArea = ((PathItem)item).getItem().getBounds2D();
        }
        else {
            return getArea();
        }
        GraphicsUtil.extendRect(itemArea, expandLen);
        return itemArea;
    }


    public List<ContentGroup> getSubgroup() {
        // 如果 内容非纯pathItem的情况　直接返回
        List<ContentGroup> groups = new ArrayList<>();
        if (!hasOnlyPathItems()) {
            groups.add(this);
            return groups;
        }

        List<ContentItem> subItems = getAllItems();
        int n = subItems.size();
        if (n <= 1) {
            groups.add(this);
        }
        else {
            for (int i = 0; i < n; i++) {
                ContentGroup subgroup = getSubgroup(subItems.get(i));
                groups.add(subgroup);
            } // end for i
            if (groups.isEmpty()) {
                groups.add(this);
            }
        }
        return groups;
    }

    public <T extends ContentItem> void forEach(Class<T> clazz, Consumer<T> consumer) {
        items.stream()
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .forEach(consumer);
    }

    public <T extends ContentItem> void forEachRecursive(Class<T> clazz, Consumer<T> consumer) {
        for (ContentItem child : items) {
            if (child instanceof ChildItem) {
                ContentGroup childGroup = (ContentGroup) child.getItem();
                childGroup.forEachRecursive(clazz, consumer);
            } else if (clazz.isInstance(child)) {
                T childItem = clazz.cast(child);
                consumer.accept(childItem);
            }
        }
    }

    // 合并小的组
    private void merge() {
        Operator operator = getFirstOperator();
        if (operator == null) {
            return;
        }
        String name = operator.getName();
        if (PDFOperator.SAVE_GRAPHICS_STATE.equals(name)) {
            //Set<Class> types = getItemTypes();
            Set<Integer> types = getItemTypesID();
            if (types.size() <= 1) {
                doMerge();
            }
        }
    }

    public List<ContentItem> getAllItems() {
        List<ContentItem> list = new ArrayList<>();
        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                list.addAll(((ChildItem) item).getItem().getAllItems());
            } else {
                list.add(item);
            }
        }
        return list;
    }

    public int getGlyphCount() {
        return glyphCount;
    }

    public void setGlyphCount(int glyphCount) {
        this.glyphCount = glyphCount;
    }

    public List<TextChunk> getAllTextChunks() {
        return getAllTextChunks(TextChunk::isVisible);
    }

    public void setTextChunksPagination(List<TextChunk> textChunks) {
        setTextChunksPagination(null, TextChunk::isVisible, textChunks);
    }

    public List<TextChunk> getAllTextChunks(Predicate<TextChunk> textChunkFilter) {
        // 只针对root节点开启缓存
        if (isRoot() && ended) {
            // Cache all TextChunk
            if (allTextChunks == null) {
                allTextChunks = new ArrayList<>(128);
                getAllTextChunks(allTextChunks, this.items, null);
            }
            return allTextChunks.stream()
                    .filter(item -> textChunkFilter == null || textChunkFilter.test(item))
                    .map(TextChunk::new)
                    .collect(Collectors.toList());
        }
        ArrayList<TextChunk> list = new ArrayList<>();
        getAllTextChunks(list, this.items, textChunkFilter);
        List<TextChunk> listDeepCopy = list.stream().map(TextChunk::new).collect(Collectors.toList());
        return listDeepCopy;
    }

    private static void getAllTextChunks(List<TextChunk> textChunks, List<ContentItem> items,
                                         Predicate<TextChunk> textChunkFilter) {
        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                List<ContentItem> childrenItems = ((ChildItem) item).getItem().items;
                getAllTextChunks(textChunks, childrenItems, textChunkFilter);
            } else if (item instanceof TextChunkItem) {
                TextChunk textChunk = ((TextChunkItem) item).getItem();
                if (textChunkFilter != null && !textChunkFilter.test(textChunk))
                    continue;
                textChunks.add(textChunk);
            }
        }
    }

    public void setTextChunksPagination(Predicate<ContentItem> itemFilter, Predicate<TextChunk> textChunkFilter, List<TextChunk> textChunks) {
        for (ContentItem item : items) {
            if (itemFilter != null && !itemFilter.test(item))
                continue;
            if (item instanceof ChildItem) {
                ((ChildItem) item).getItem().setTextChunksPagination(itemFilter, textChunkFilter, textChunks);
            } else if (item instanceof TextChunkItem){
                TextChunk textChunk = ((TextChunkItem) item).getItem();
                if (textChunkFilter != null && !textChunkFilter.test(textChunk))
                    continue;
                List<TextChunk> tempTextChunks = textChunks.stream()
                        .filter(textChunk1 -> textChunk1.contains(textChunk))
                        .collect(Collectors.toList());
                if (tempTextChunks.size() == 1
                        && tempTextChunks.get(0).getPaginationType() != textChunk.getPaginationType())
                    textChunk.setPaginationType(tempTextChunks.get(0).getPaginationType());
            }
        }
    }

    public List<PathItem> getAllPathItems() {
        List<PathItem> list = new ArrayList<>();
        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                list.addAll(((ChildItem) item).getItem().getAllPathItems());
            } else if (item instanceof PathItem){
                list.add((PathItem) item);
            }
        }
        return list;
    }

    public List<ContentGroup> getAllContentGroups(boolean intoForm) {
        return getAllContentGroups(intoForm, false);
    }

    public List<ContentGroup> getAllContentGroups(boolean intoForm, boolean itemLevel) {
        return getAllContentGroups(intoForm, itemLevel, true);
    }

    /**
     * 获取子ContentGroup集
     * 由于矢量Chart解析的特殊性 　需要将含有pathItem的对象细分　以便于解析
     * @param intoForm           是否考虑Form对象
     * @param itemLevel          是否细分只包含pathItem的对象
     * @param filterBigGroup     是否需要过滤掉数目过大的对象
     * @return
     */
    public List<ContentGroup> getAllContentGroups(boolean intoForm, boolean itemLevel, boolean filterBigGroup) {
        if (isPDFormXObject() && !intoForm) {
            return Lists.newArrayList(this);
        }
        List<ContentGroup> list = new ArrayList<>();
        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                list.addAll(((ChildItem) item).getItem().getAllContentGroups(intoForm, itemLevel));
            }
        }
        if (list.isEmpty()) {
            if (itemLevel) {
                List<ContentGroup> subGroups = getSubgroup();
                list.addAll(subGroups);
            }
            else {
                list.add(this);
            }
        }

        // 过滤掉内部只包含pathitem并且数目过大的对象 这种对象处理起来速度非常慢　适当的舍弃这一部分
        // 用来加快处理某些PDF内复杂背景图形的情况
        //　原则上有可能会舍弃掉一部　内部包含复杂数据且path对象数目过大的Chart对象
        // 初步评估这一部分比例比较小  如果将来测试发现比例比较大不能舍弃　再想其他的解决方案　
        if (itemLevel && filterBigGroup && list.size() > 2000) {
            boolean bigPathGroup = list.stream().allMatch(ele -> ele.hasOnlyPathItems());
            if (bigPathGroup) {
                //System.out.println("has big path group, filter this group !!!!!!!!!!!!!!!!!!!!!");
                list.clear();
                return list;
            }
        }
        return list;
    }

    private void doMerge() {
        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);
            if (token instanceof ContentGroup) {
                ContentGroup child = (ContentGroup) token;
                tokens.remove(i);
                tokens.addAll(i, child.tokens);
                i += child.tokens.size() - 1;

                replaceChildItem(child);
                adjustBbox(child.area);
            }
        }
    }

    /**
     * 找出当前对象的 颜色状态和基本内容 并基于深度优先遍历的顺序 存储起来
     * @return
     */
    public List<Object> getAllContentAndGraphicsStateObject(boolean baseOnForm) {
        List<Object> basicObjects = new ArrayList<>();
        if (!baseOnForm || (!inForm() && !isPDFormXObject())) {
            basicObjects.add(graphicsState);
        }
        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);
            if (token instanceof ContentGroup) {
                ContentGroup child = (ContentGroup) token;
                basicObjects.addAll(child.getAllContentAndGraphicsStateObject(baseOnForm));
            }
            else {
                basicObjects.add(token);
            }
        }
        return basicObjects;
    }

    private void replaceChildItem(ContentGroup child) {
        for (int i = 0; i < items.size(); i++) {
            ContentItem item = items.get(i);
            if (item instanceof ChildItem && ((ChildItem) item).getItem() == child) {
                items.remove(i);
                items.addAll(i, child.items);
            }
        }
    }

    public Set<Integer> getItemTypesID() {
        Set<Integer> typeSet = new HashSet<>();
        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                typeSet.addAll(((ChildItem) item).getItem().getItemTypesID());
            } else {
                if (item instanceof ImageItem) {
                    typeSet.add(1);
                }
                else if (item instanceof TextChunkItem) {
                    typeSet.add(2);
                }
                else if (item instanceof PathItem) {
                    if (((PathItem) item).isFill()) {
                        typeSet.add(3);
                    }
                    else {
                        typeSet.add(4);
                    }
                }
                else {
                    typeSet.add(0);
                }
            }
        }
        return typeSet;
    }

    public Set<Class> getItemTypes() {
        Set<Class> typeSet = new HashSet<>();
        for (ContentItem item : items) {
            if (item instanceof ChildItem) {
                typeSet.addAll(((ChildItem) item).getItem().getItemTypes());
            } else {
                typeSet.add(item.getClass());
            }
        }
        return typeSet;
    }

    public boolean hasOnlyPathItems() {
        Set<Class> itemTypes = getItemTypes();
        return itemTypes.size() == 1 && itemTypes.contains(PathItem.class);
    }

    public boolean hasText() {
        Set<Class> itemTypes = getItemTypes();
        return itemTypes.contains(TextChunkItem.class);
    }

    public boolean hasImage() {
        Set<Class> itemTypes = getItemTypes();
        return itemTypes.contains(ImageItem.class);
    }

    public void addOperator(Operator operator, List<COSBase> operands, PDGraphicsState currentGraphicsState) {
        if (operator.getName().equals(PDFOperator.DRAW_OBJECT)) {
            Object lastToken;
            if (fakeContentGroup != null) {
                lastToken = fakeContentGroup.tokens.get(fakeContentGroup.tokens.size()-1);
            } else {
                lastToken = tokens.get(tokens.size()-1);
            }
            if (lastToken instanceof ContentGroup) {
                // 忽略 /MetaXXX Do操作, 因为我们已经把FormXObject加入到tokens里面了
                return;
            }
        }
        String opName = operator.getName();
        if ((!isTextContent() && !fake)
                && GRAPHICS_START_OP.contains(opName)
                && fakeContentGroup == null)  {
            // 有些PDF没有 MarkedContent, 或者是Form里面某些操作没有放到组里面, 这里创建一个虚拟的组把操作和生成的对象存储起来
            ContentGroup fake = new ContentGroup(currentGraphicsState, pageTransform, true);
            add(fake);
            fakeContentGroup = fake;
        }
        if (fakeContentGroup != null) {
            if (GRAPHICS_END_OP.contains(opName)) {
                fakeContentGroup.addOperator(operator, operands, currentGraphicsState);
                endFakeContentGroup();
            } else {
                fakeContentGroup.addOperator(operator, operands, currentGraphicsState);
            }
        } else {
            tokens.addAll(operands);
            tokens.add(operator);
        }
    }

    private void checkTextLine(PathItem pathItem) {
        // 判断是否是下划线或删除线
        Rectangle2D rect;
        GeneralPath path = pathItem.getItem();
        if (GraphicsUtil.isRect(path)) {
            rect = path.getBounds2D();
            // 线一般高度比较小
            if (rect.getHeight() > 3) {
                return;
            }
        } else if (GraphicsUtil.getLine(path) != null) {
            rect = path.getBounds2D();
            GraphicsUtil.extendRect(rect, pathItem.getLineWidth());
        } else {
            return;
        }
        boolean isUnderLine = false;
        boolean isDeleteLine = false;
        List<TextChunk> textChunks = getRoot().getAllTextChunks();
        List<TextChunk> underLineTextChunks = new ArrayList<>();
        for (TextChunk textChunk : textChunks) {
            Rectangle2D intersection = textChunk.createIntersection(rect);
            if (intersection.isEmpty()) {
                continue;
            }
            if (intersection.getWidth() / textChunk.getWidth() >= 0.9) {
                double delta = textChunk.getHeight() / 4;
                if (Math.abs(rect.getCenterY() - textChunk.getCenterY()) < delta) {
                    textChunk.setHasDeleteLine(true);
                    isDeleteLine = true;
                } else if (Math.abs(rect.getCenterY() - textChunk.getMaxY()) < delta) {
                    underLineTextChunks.add(textChunk);
                    isUnderLine = true;
                }
            }
        }
        if (underLineTextChunks.size() > 0) {
            // 检测线的长度是否和文字的长度一致, 不一致的可能是表格的线而不是下划线
            double lineWidth = rect.getWidth();
            double totalTextWidth = 0;
            for (TextChunk textChunk : underLineTextChunks) {
                totalTextWidth += textChunk.getWidth();
            }
            if (lineWidth < totalTextWidth + underLineTextChunks.get(0).getWidthOfSpace() * 2) {
                underLineTextChunks.forEach(textChunk -> textChunk.setHasUnderLine(true));
            }
        }
        if (isDeleteLine) {
            pathItem.addTag(Tags.IS_DELETE_LINE, true);
        }
        if (isUnderLine) {
            pathItem.addTag(Tags.IS_UNDER_LINE, true);
        }
    }

    public PathItem fillPath(GeneralPath path, PDGraphicsState graphicsState, Color color) {
        if (fakeContentGroup != null) {
            return fakeContentGroup.fillPath(path, graphicsState, color);
        }
        Rectangle2D box = path.getBounds2D();
        // 不需要考虑线宽, fillPath不受线宽的影响
        adjustBbox(box);
        PathItem pathItem = new PathItem(path, box, graphicsState, true, color);
        checkTextLine(pathItem);
        items.add(pathItem);
        return pathItem;
    }

    public PathItem strokePath(GeneralPath path, PDGraphicsState graphicsState, Color color) {
        if (fakeContentGroup != null) {
            return fakeContentGroup.strokePath(path, graphicsState, color);
        }
        Rectangle2D box = path.getBounds2D();
        // 考虑到有线宽, 稍微扩大一点
        GraphicsUtil.extendRect(box, GraphicsUtil.getTransformedLineWidth(graphicsState)/2);
        adjustBbox(box);
        PathItem pathItem = new PathItem(path, box, graphicsState, false, color);
        checkTextLine(pathItem);
        items.add(pathItem);
        return pathItem;
    }

    public TextChunkItem showGlyph(TextElement element) {
        if (fakeContentGroup != null) {
            return fakeContentGroup.showGlyph(element);
        }
        ContentItem lastItem = getLastItem();
        if (lastItem != null
                && lastItem instanceof TextChunkItem
                && ((TextChunkItem) lastItem).getItem().inSameGroup(element)) {
            TextChunk chunk = ((TextChunkItem) lastItem).getItem();
            chunk.addElement(element);
            adjustBbox(chunk);
            return (TextChunkItem) lastItem;
        } else if (lastItem != null
                && lastItem instanceof TextChunkItem
                && element.isDiacritic()) {
            TextChunk chunk = ((TextChunkItem) lastItem).getItem();
            chunk.mergeDiacritic(element);
            adjustBbox(chunk);
            return (TextChunkItem) lastItem;
        } else {
            TextChunk chunk = new TextChunk();
            chunk.addElement(element);
            TextChunkItem item = new TextChunkItem(chunk);
            items.add(item);
            adjustBbox(chunk);
            return item;
        }
    }

    public TextChunkItem addTextChunk(TextChunk textChunk) {
        if (fakeContentGroup != null) {
            return fakeContentGroup.addTextChunk(textChunk);
        }
        TextChunkItem item = new TextChunkItem(textChunk);
        items.add(item);
        adjustBbox(textChunk);
        return item;
    }

    public ImageItem drawImage(PDImage image, Rectangle2D imageBounds, AffineTransform imageTransform) {
        if (fakeContentGroup != null) {
            return fakeContentGroup.drawImage(image, imageBounds, imageTransform);
        }
        ImageItem imageItem = new ImageItem(image, imageBounds, imageTransform);
        items.add(imageItem);
        adjustBbox(imageBounds);
        return imageItem;
    }

    public ContentItem getLastItem() {
        if (items.isEmpty()) {
            return null;
        }
        return items.get(items.size()-1);
    }

    private void adjustBbox(Rectangle2D box) {
        if (box == null || (box.getWidth() <= 0.0 && box.getHeight() <= 0.0)) {
            return;
        }
        if (area.isEmpty()) {
            area = box.getBounds2D();
        } else {
            Rectangle2D.union(area, box, area);
        }
    }

    public void setArea(Rectangle2D area) {
        this.area = area.getBounds2D();
    }

    public void add(ContentGroup contentGroup) {
        if (!fake) {
            contentGroup.parent = this;
        }
        tokens.add(contentGroup);
        items.add(new ChildItem(contentGroup));
        endFakeContentGroup();
    }

    private void endFakeContentGroup() {
        if (fakeContentGroup != null) {
            fakeContentGroup.end();
            fakeContentGroup = null;
        }
    }

    public ContentGroup getParent() {
        return parent;
    }

    public int getLevel() {
        if (parent == null) {
            return 0;
        } else {
            return parent.getLevel() + 1;
        }
    }

    public List<Object> getAllTokens() {
        List<Object> all = new ArrayList<>();
        tokens.forEach(token -> {
            if (token instanceof ContentGroup) {
                all.addAll(((ContentGroup) token).getAllTokens());
            } else {
                all.add(token);
            }
        });
        return all;
    }

    /**
     * 在 tokens 中查找给定 数字类型字符串信息 如果都包含其中 则返回 true (调试程序用)
     * @param content
     * @return
     */
    public boolean hasContent(String content) {
        if (tokens.isEmpty() || StringUtils.isEmpty(content)) {
            return false;
        }

        List<String> parts = Arrays.asList(content.split(" "));
        List<String> contentparts = new ArrayList<>();
        parts.stream().map((s) -> s.trim()).filter((s) -> s.length() >= 1).forEach(s -> contentparts.add(s));
        for (String str : contentparts) {
            boolean bFind = tokens.stream().filter(obj -> obj instanceof COSNumber)
                    .anyMatch(obj -> obj.toString().contains(str));
            if (!bFind) {
                return false;
            }
        }
        return true;
    }

    public void setClipArea(Rectangle2D clipArea) {
        this.clipArea = clipArea;
    }

    public Rectangle2D getClipArea() {
        return clipArea;
    }

    // 添加给定tag集信息
    private void addTags(Map<String, Object> tagMap) {
        for (Map.Entry<String, Object> entry : tagMap.entrySet()) {
            addTag(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void addTag(String key, Object value) {
        tags.put(key, value);
    }

    @Override
    public Object getTag(String key) {
        return tags.get(key);
    }

    @Override
    public boolean hasTag(String key) {
        return tags.containsKey(key);
    }

    @Override
    public void clearTags() {
        tags.clear();
    }

    @Override
    public void removeTag(String key) {
        tags.remove(key);
    }

    public void setTextTree(TextTree textTree) {
        this.textTree = textTree;
    }

    public TextTree getTextTree() {
        return textTree;
    }

    public BufferedImage toBufferedImage(PDPage pdPage, float scale, boolean drawText) throws IOException {
        ContentGroupImageDrawer drawer = new ContentGroupImageDrawer(pdPage, new ContentGroupDrawable() {
            @Override
            public ContentGroup getContentGroup() {
                return ContentGroup.this;
            }

            @Override
            public Rectangle2D getDrawArea() {
                return ContentGroup.this.getArea();
            }
        });
        drawer.setDrawText(drawText);
        int width = (int) (getArea().getWidth() * scale);
        int height = (int) (getArea().getHeight() * scale);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setBackground(Color.WHITE);
        graphics.clearRect(0, 0, image.getWidth(), image.getHeight());
        drawer.renderToGraphics(graphics, scale);
        graphics.dispose();
        return image;
    }
}

