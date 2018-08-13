package com.abcft.pdfextract.core.chart;


import com.abcft.pdfextract.core.*;
import com.abcft.pdfextract.core.chart.model.PathInfo;
import com.abcft.pdfextract.core.gson.*;
import com.abcft.pdfextract.core.model.ContentGroup;
import com.abcft.pdfextract.core.model.ContentGroupDrawable;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.util.FileId;
import com.abcft.pdfextract.core.util.GraphicsUtil;
import com.abcft.pdfextract.spi.algorithm.ImageClassifyResult;
import com.abcft.pdfextract.util.Confident;
import com.abcft.pdfextract.spi.BaseChart;
import com.abcft.pdfextract.spi.ChartType;
import com.abcft.pdfextract.util.FloatUtils;
import com.abcft.pdfextract.util.JsonUtil;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Created by dhu on 2016/12/28.
 */

@DeprecatedFields(value = { "svgFile", "isBitMap" })   // TODO 补充其他需要删除的字段
@DataFields(value = { "page_area", "rawTexts" }, advanced = true) // TODO 添加需要补充的，不用于搜索的字段，比如 rawTexts
@DataFields(value = { "is_bitmap" })
@StatusFields({"state", "deleted"})
@MetaFields({"file_data", "from", "create_time", "last_updated", "table_version" })
public class Chart implements BaseChart, ExtractedItem, ContentGroupDrawable, Confident {

    public PDPage page;
    public float pageHeight;
    private int chartIndex;
    public PDFormXObject form;
    public ContentGroup contentGroup;
    private com.abcft.pdfextract.core.office.Chart officeChart = null;

    public com.abcft.pdfextract.core.office.Chart getOfficeChart() {
        return officeChart;
    }

    public void setOfficeChart(com.abcft.pdfextract.core.office.Chart officeChart) {
        this.officeChart = officeChart;
    }

    @Override
    public ChartType getType() {
        return type;
    }

    @Override
    public String getId() {
        return chartId;
    }

    @Override
    public int getPageIndex() {
        return this.pageIndex;
    }

    @Override
    public int getIndex() {
        return this.chartIndex;
    }

    @Override
    public String getTitle() {
        return this.title;
    }

    @Override
    public String toHtml() {
        StringBuilder builder = new StringBuilder();
        if (this.type == ChartType.BITMAP_CHART) {
            String src = this.getImageFile();
            builder.append("<img id=\"").append(this.chartId).append("\" class=\"pdf-bitmap-chart\" data-page=\"")
                .append(this.pageIndex + 1).append("\" data-rect=\"")
                .append((int) this.getArea().getMinX()).append(",").append((int) this.getArea().getMinY())
                .append(",").append((int) this.getArea().getWidth()).append(",").append((int) this.getArea().getHeight())
                .append("\" src=\"").append(src).append("\"></img>");
        } else {
            builder.append("<div id=\"").append(this.chartId).append("\" class=\"pdf-chart\" data-page=\"")
                .append(this.pageIndex + 1).append("\" data-rect=\"")
                .append((int) this.getArea().getMinX()).append(",").append((int) this.getArea().getMinY())
                .append(",").append((int) this.getArea().getWidth()).append(",").append((int) this.getArea().getHeight())
                .append("\" data-confidence=\"").append(FloatUtils.round(confidence, 2)).append("\"></div>")
                .append("<script type=\"text/javascript\">\n")
                .append("window.").append(this.chartId)
                .append(" = ").append(JsonUtil.toString(this.data, false)).append(";\n")
                .append("try { Highcharts.chart('").append(this.chartId).append("', ")
                .append(this.chartId)
                .append("); } catch(err) {}</script>");
        }
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public JsonObject getHighcharts() {
        return this.data;
    }

    @Detail
    @SerializedName("is_ppt")
    public boolean isPPT;            // Chart为整个PPT样式Page保存的位图对象

    @Detail
    @SerializedName("is_parsed_ppt")
    public boolean isParsedPPT;      // PPT样式的位图Chart并且内部解析出了矢量Chart对象

    @Override
    public ContentGroup getContentGroup() {
        return contentGroup;
    }

    @Override
    public Rectangle2D getDrawArea() {
        return getLowerLeftArea().withCenterExpand(10);
    }

    public Rectangle getArea() {
        if (area == null) {
            return null;
        }
        return new Rectangle(area);
    }

    public void setArea(Rectangle area) {
        if (area == null) {
            this.area = null;
        } else {
            this.area = new Rectangle(area);
        }
    }

    public void setArea(Rectangle2D area) {
        if (area == null) {
            this.area = null;
        } else {
            this.area = new Rectangle(area);
        }
    }

    public float getWidth() {
        if (area == null) {
            return 0;
        }
        return (float) area.getWidth();
    }

    public float getHeight() {
        if (area == null) {
            return 0;
        }
        return (float) area.getHeight();
    }

    public float getLeft() {
        if (area == null) {
            return 0;
        }
        return area.getLeft();
    }

    public float getTop() {
        if (area == null) {
            return 0;
        }
        return area.getTop();
    }

    public float getBottom() {
        if (area == null) {
            return 0;
        }
        return area.getBottom();
    }

    public float getRight() {
        if (area == null) {
            return 0;
        }
        return area.getRight();
    }

    public float getTitleSearchHeight() {
        if (area == null) {
            return 40;
        }
        return (float) Math.max(40, area.getHeight() * 0.4f);
    }

    public Rectangle getLowerLeftArea() {
        if (lowerLeftArea == null) {
            return null;
        }
        return new Rectangle(lowerLeftArea);
    }

    public void setLowerLeftArea(Rectangle pageArea) {
        this.lowerLeftArea = pageArea;
    }

    public void setLowerLeftArea(Rectangle2D area) {
        if (area == null) {
            this.lowerLeftArea = null;
        } else {
            this.lowerLeftArea = new Rectangle(area);
        }
    }

    public String getImageFile() {
        return imageFile;
    }

    public void setImageFile(String imageFile) {
        this.imageFile = imageFile;
        if (officeChart != null && officeChart.getImage() != null) {
            officeChart.getImage().setImageFile(imageFile);
        }
    }

    public static class Legend {

        @Summary
        public String text;
        @Detail
        public Rectangle2D line;
        @Detail
        public Color color;
        @Detail
        public PathInfo.PathType type = PathInfo.PathType.UNKNOWN;

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * 饼图数据结构
     */
    public static class PieInfo {
        /**
         * 饼图中每一个部分数据结构
         */
        public static class PiePartInfo {
            public String text;                         // 扇形的内容
            public Color color;                         // 填充颜色
            public double weight;                       // 所占比例
            public GeneralPath path;                    // 扇形Path
            public int id;                              // 扇形所在的Chart中饼图的序号　存在Chart包含多个饼图
            public boolean isPie;                       // 扇形是否为环形图一部分标示
            public double x;                            // 扇形圆心X
            public double y;                            // 扇形圆心Y
            public double r;                            // 扇形半径
            public boolean hasPercentText;              // 是否有百分比信息
            public String percentText;                  // 获取到的:百分比
            public boolean hasNumberText;               // 是否有数字信息
            public String  numberText;                  // 获取到的数值
            public double startAngle;                   //扇形的绝对开始角度
            public double endAngle;                     //扇形的绝对停止角度

            PiePartInfo() {
                text = "";                         // 空字符串
                color = new Color(255, 255, 255);   // 背景白色
                weight = 0.0f;                               // 权重为0
                path = null;
                id = 0;
                isPie = true;
                hasPercentText = false;
                hasNumberText = false;
            }

            // 转化为字符串标识　用百分比表示
            @Override
            public String toString() {
                java.text.DecimalFormat df = new java.text.DecimalFormat(".00");
                String percent = df.format(100.0 * weight) + "%";
                return text + " " + percent + " " + color;
            }
        } // end class PiePartInfo

        public List<PiePartInfo> parts;       // 包含的内容 即所有的扇形信息内容

        PieInfo() {
            parts = new ArrayList<>();
        }
    } // end class PieInfo

    @Detail
    @DataField
    @SerializedName("algorithmCommitTime")
    public final String algorithmCommitTime = "2018-08-07 21:00:00";  // 算法提交最新时间信息


    @Detail
    @DataField
    @SerializedName("algorithmVersion")
    public final int algorithmVersion = 7;  // 算法提交版本号　和大算法版本号区别，方便测试和产品同事查看

    @Detail
    @MetaField
    @SerializedName("_id")
    public String chartId;

    @Detail
    @MetaField
    @SerializedName("fileId")
    public FileId fileId;

    @Detail
    @MetaField
    @SerializedName("file_source")
    public String fileSource;

    @Detail
    @DataField
    @SerializedName("pngFile")
    private String imageFile;

    private String imageName;

    @DataField(inline = false)
    public String dataFile;

    public String svgFile;
    private float titleSearchHeight;

    @Summary
    @DataField(advanced = true)
    @SerializedName("page_area")
    private Rectangle area;             // 在页面里面的区域, 页面左上角为原点, Y轴向下

    @Detail
    @SerializedName("area")
    private Rectangle lowerLeftArea;    // 在页面里面的区域, 页面左下角为原点, Y轴向上

    @Detail
    @DataField(advanced = true)
    @SerializedName("source")
    public String dataSource;                                           // 数据来源信息
    public TextChunk dataSourceChunk;                                   // 数据来源信息对应的TextChunk

    @Summary
    @MetaField
    public int pageIndex;

    @Summary
    @DataField
    @SerializedName("chartType")
    public ChartType type = ChartType.UNKNOWN_CHART;

    @Summary
    @DataField
    @SerializedName("subtypes")
    public List<ChartType> subTypes = new ArrayList<>();                // 复合图包含的子类型集 下一步细分复合类型

    public List<ImageClassifyResult> imageClassifyTypes = new ArrayList<>();      // 调用位图分类服务得到的类型信息

    AxisLegendTextGroupInfo groupInfo = new AxisLegendTextGroupInfo();  // Chart内刻度和图例的TextElement的组号状态信息

    @Summary
    @DataField(advanced = true)
    @SerializedName("legends")
    public List<Legend> legends = new ArrayList<>();
    public List<Legend> invalidLegends = new ArrayList<>();   // 存储无效图例信息 方便计算置信度
    public List<String> texts = new ArrayList<>();            // Chart内部除刻度和图例之外的信息集

    @Summary
    @DataField(advanced = true)
    @SerializedName("vAxisTextL")
    public List<String> vAxisTextL = new ArrayList<>();       // 垂直方向左侧刻度
    @Summary
    @DataField(advanced = true)
    @SerializedName("vAxisTextR")
    public List<String> vAxisTextR = new ArrayList<>();       // 垂直方向右侧刻度
    @Summary
    @DataField(advanced = true)
    @SerializedName("hAxisTextD")
    public List<String> hAxisTextD = new ArrayList<>();       // 水平方向下侧刻度
    @Summary
    @DataField(advanced = true)
    @SerializedName("hAxisTextU")
    public List<String> hAxisTextU = new ArrayList<>();       // 水平方向上侧刻度

    public String vAxisTextUnitL;                             // 左侧刻度单位
    public TextChunk vAxisChunkUnitL;                         // 左侧刻度单位 TextChunk
    public String vAxisTextUnitR;                             // 右侧刻度单位
    public TextChunk vAxisChunkUnitR;                         // 左侧刻度单位 TextChunk

    public List<TextChunk> vAxisChunksL = new ArrayList<>();  // 左侧刻度信息集
    public List<TextChunk> vAxisChunksR = new ArrayList<>();  // 右侧刻度信息集
    public List<TextChunk> hAxisChunksD = new ArrayList<>();  // 下侧刻度信息集
    public List<TextChunk> hAxisChunksU = new ArrayList<>();  // 上侧刻度信息集
    public List<ChartPathInfo> pathInfos = new ArrayList<>(); // 内部包含的path信息集
    public List<TextChunk> chunksRemain = new ArrayList<>();  // 内部信息集(标题或来源信息不确定是否包含)

    public List<TextChunk> textsChunks = new ArrayList<>();   // texts对应的TextChunk集
    public List<TextChunk> chunksPicMark = new ArrayList<>();  // 用于给Chart图片标记文字组的TextChunk集

    // 内部包含的候选柱状Path, 一般宽度或高度很小  比网格线或图例标识大小相当 (测试中)
    // 只有在检测出Chart的大部分信息如坐标轴和其他明显柱状部分后　才能判断是否为柱状图的一部分
    public List<ChartPathInfo> barsInfos = new ArrayList<>();

    public PieInfo pieInfo = new PieInfo();                           // 饼图或环形图内容信息(包括隐式图列信息)
    public List<PieInfo> pieInfos = new ArrayList<>();                // Chart内部可能包含多个饼图或环形图对象 每处理完一个保存一个
    public List<TextChunk> pieInfoLegendsChunks = new ArrayList<>();  // 饼图隐式图例对应的Chunk集

    public String text;
    @Summary
    @DataField
    @SerializedName("title")
    public String title;
    public TextChunk titleTextChunk;                                     // 标题的TextChunk
    public String subtitle;
    public TextChunk subtitleTextChunk;
    public List<TextChunk> legendsChunks = new ArrayList<>();  // 图例的TextChunk集

    @Summary
    @DataField
    @SerializedName("confidence")
    public double confidence = 0.0;                                   // Chart的有效性指数

    @Detail
    @DataField(advanced = true)
    @SerializedName("hAxis")
    public Line2D hAxis;
    public Line2D hAxisLogic;
    @Detail
    @DataField(advanced = true)
    @SerializedName("lvAxis")
    public Line2D lvAxis;
    @Detail
    @DataField(advanced = true)
    @SerializedName("rvAxis")
    public Line2D rvAxis;

    public List<Line2D> axisScaleLines = new ArrayList<>();          // 坐标轴轴标线集　用来判断坐标轴和分割水平长刻度信息用

    @Detail
    @DataField(advanced = true)
    @SerializedName("data")
    public JsonObject data = null;                                   // 内部对象解析数据序列化 HighCharts Json 格式
    private Set<String> fontNames = new HashSet<>();
    public PDImageXObject image;
    @Detail
    @DataField
    public String imageUrl = null;                                  // 图片的下载地址
    public BufferedImage cropImage;                                       // 从PDF Page 内部某个区域剪切的图片对象
    public String localImage = null;                                   // 本地图片
    public List<OCRPathInfo> ocrs = new ArrayList<>();          // 内部可能包含的斜着的刻度信息

    @Detail
    @DataField
    @SerializedName("ocrEngine")
    public JsonObject ocrEngineInfo = null;                               // 调用OCREngine的状态信息 是否调用以及检测几张OCR图片
    public boolean parserDetectArea = false;                              // 是否基于检测区域解析出来的
    public boolean parserDetectModelArea = false;                         // 是否基于检测模型检测出的区域解析出来的
    public boolean parserHintArea = false;                                // 是否基于Hint-Area解析出来的
    public boolean parserRecallArea = false;                              // 是否基于图表召回Area解析出来的
    public boolean isWaterFall = false;                                   // 是否为瀑布图
    public boolean hasShColor = false;                                    // 是否含有渐变色对象
    public boolean legendMatchPath = false;                               // 是否用图例匹配图形

    public List<List<Integer>> markPaths = new ArrayList<>();             // Chart内部标记好的同类型path组集
    public boolean onlyMatchXScale = false;                               // X刻度识别为标签并且需要插值折线顶点时，刻度正上方才赋刻度值, 其他赋空值 和位图解析一致

    @Summary
    @DataField
    @SerializedName("is3DChart")
    public boolean is3DChart = false;                                     // 判断是否为3D效果图表
    public List<Rectangle2D> innerImageBox = new ArrayList<>();           // 内部小位图对象包围框

    public void removeEmptyLegends() {
        final Iterator<Legend> each = legends.iterator();
        while (each.hasNext()) {
            Legend legend = each.next();
            if (StringUtils.isEmpty(legend.text)) {
                // 将无效legend存储起来 方便计算置信度和后续的改进
                invalidLegends.add(legend);
                each.remove();
            }
        }
    }

    public int getChartIndex() {
        return chartIndex;
    }

    public void setChartIndex(int chartIndex) {
        this.chartIndex = chartIndex;
    }

    public String getName() {
        if (StringUtils.isNoneBlank(imageName)) {
            return imageName;
        }
        // 使用位图的name作为chart的id, 这样可以保证同一个图, id总是不变的
        if (image != null) {
            return image.getName().getName();
        }
        // TODO: 使用更加合理的数据来表示chart的id, 比如在页面内的位置, 在PDF流里面的位置等等,
        // TODO: 这样不会因为多识别或者少识别导致index被打乱
        return String.valueOf(chartIndex);
    }

    public void setName(String name) {
        this.imageName = name;
    }

    public boolean isChart() {
        if (hAxis == null && lvAxis == null && rvAxis == null && legends.isEmpty() && type == ChartType.UNKNOWN_CHART) {
            // 存在少数Chart  没有坐标轴　没有图例　　这种情况少而且容易和表格搞混　
            // 如果是基于对象检测出来的Chart　则将其作为候选Chart  常规矢量方法检测出来的则认为是无效Chart
            if (!parserDetectArea) {
                if (!isWaterFall) {
                    return false;
                }
            }
        }
        if (legends.size() > 0) {
            Set<Integer> colorSet = new HashSet<>();
            //检查legend的颜色
            for (Legend legend : legends) {
                colorSet.add(legend.color.getRGB());
            }
            // 碰到过某几个图例的颜色相同的情况 所以加上类型判断 (调试中)
            if (colorSet.size() < legends.size() && type == ChartType.UNKNOWN_CHART) {
                //有重复颜色
                //return false;
            }
        }
        return true;
    }

    @Override
    public double getConfidence() {
        return confidence;
    }

    @Override
    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Set<String> getFontNames() {
        return fontNames;
    }

    public void addFontName(String name) {
        fontNames.add(name);
    }

    public void addFontName(Collection<String> names) {
        fontNames.addAll(names);
    }

    public int getPageNumber() {
        return pageIndex + 1;
    }

    @Override
    public String getFormatName() {
        if (officeChart != null && officeChart.getImage() != null) {
            return officeChart.getImage().getFormat();
        }
        if (image != null) {
            String suffix = image.getSuffix();
            if (suffix == null || "jb2".equals(suffix)) {
                suffix = "png";
            } else if ("jpx".equals(suffix)) {
                // use jp2 suffix for file because jpx not known by windows
                suffix = "jp2";
            }
            if (("jpg".equals(suffix) || "jp2".equals(suffix))
                    && !ChartUtils.hasMasks(image)) {
                return "jpg";
            }
        }
        if (StringUtils.isNoneBlank(imageUrl)) {
            if (StringUtils.endsWithIgnoreCase(imageUrl, "gif")) {
                return "gif";
            } else if (StringUtils.endsWithIgnoreCase(imageUrl, "jpg")
                    || StringUtils.endsWithIgnoreCase(imageUrl, "jpeg")) {
                return "jpg";
            }
        }
        // default to png
        return "png";
    }

    /**
     * 将饼图包含的子部分(扇形)信息序列化
     *
     * @param part 扇形信息
     * @return
     */
    public JsonObject toDocument(PieInfo.PiePartInfo part) {
        if (part == null) {
            return null;
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("text", part.text);
        jsonObject.addProperty("color", GraphicsUtil.color2String(part.color));
        jsonObject.addProperty("weight", part.weight);
        return jsonObject;
    }

    public JsonObject toDocument() {
        return toDocument(false);
    }

    public JsonObject toDocument(boolean detail) {
        JsonObject jsonObject = GsonUtil.toDocument(this, detail);
        if (detail && type == ChartType.BITMAP_CHART) {
            jsonObject.addProperty("is_bitmap", true);
        }
        return jsonObject;
    }

    public void fromDocument(JsonObject document, ExtractContext context) {
        page = ((PdfExtractContext)context).getNativeDocument().getPage(pageIndex);
        pageHeight = ExtractorUtil.determinePageSize(page).y;
        Rectangle2D pageArea = JsonUtil.getRectangle(document, "page_area");
        if (pageArea == null) {
            Rectangle2D area = JsonUtil.getRectangle(document, "area");
            AffineTransform pageTransform = ContentGroupRenderer.buildPageTransform(page, 1);
            pageArea = pageTransform.createTransformedShape(area).getBounds2D();
        }
        this.setArea(new Rectangle(pageArea));
    }

    public boolean contains(Rectangle2D area) {
        return GraphicsUtil.contains(this.getArea(), area);
    }

    public boolean contains(Point2D point) {
        return this.getArea().contains(point);
    }

    @Override
    public String toString() {
        if (getArea() == null) {
            return title;
        }
        return String.format("title: %s, area: (%.3f, %.3f, %.3f, %.3f)\n",
                title, getArea().getX(), getArea().getY(), getArea().getWidth(), getArea().getHeight());
    }
}

/**
 * Chart内部刻度和图例信息对应的组号信息 解析刻度和图例时用
 */
class AxisLegendTextGroupInfo {
    public Set<Integer> axis = new HashSet<>();
    public Set<Integer> legend = new HashSet<>();
}
