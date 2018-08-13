package com.abcft.pdfextract.core.table.detectors;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextBlock;
import com.abcft.pdfextract.core.model.TextChunk;
import com.abcft.pdfextract.core.model.TextDirection;
import com.abcft.pdfextract.core.table.*;
import com.abcft.pdfextract.core.util.TrainDataWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.helper.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.awt.geom.Rectangle2D;

/**
 * Created by jhqiu on 18-6-12.
 */
public class TableRegionCrfAlgorithm {
    static int otherLine=1;
    static int tableLine=2;
    static int tableEnd=3;
    static List<TrainDataWriter.LineInfo> lineInfos;
    static int[] tags;
    static TableRegion tableRegion;
    static List<Integer> oldOneTables;
    static List<Integer> oneTables;
    static List<TableRegion> layoutTableRegions;
    public static List<Rectangle> layoutAnalysisRectList;

    private static final Logger logger = LogManager.getLogger(TableRegionCrfAlgorithm.class);
    //4元标注法:理想情况下的表格区域获取方法
    public static List<TableRegion> tableCrfAccuracy(ContentGroupPage tablePage,List<TrainDataWriter.LineInfo> Infos, int[] Pagetags){

        lineInfos = Infos;
        tags = Pagetags;
        layoutTableRegions = new ArrayList<>();
        oneTables = new ArrayList<>();
        oldOneTables = new ArrayList<>();

        for(int i = 0; i < lineInfos.size(); i++) {
            int tag = tags[i];
            int nextTag = i < tags.length-1 ? tags[i+1]:0;
            TrainDataWriter.LineInfo currLine = lineInfos.get(i);
            TrainDataWriter.LineInfo nextLine = i < lineInfos.size()-1 ? lineInfos.get(i+1):null;

            if (oneTables.size()==0) {
                if (tag == tableLine) {//表格行
                    oneTables.add(i);
                } else if (tag == tableEnd) {
                    boolean bFindOneLine = false;
                    //有条件的检回部分单行表格
                    if (lineInfos.get(i).hasRulingRegion) {
                        logger.info("存在有线边框检回");
                        bFindOneLine = true;
                    }else if((i-1>=0) && (lineInfos.get(i-1).patternMatchResult.get(0)==1)//前一行匹配表格开始的正则,当前行上下有线,则检回
                            && (lineInfos.get(i).hasTopRuling) && (lineInfos.get(i).hasBottomRuling)){
                        logger.info("前一行匹配表格开始的正则表达式,当前行上下有线,则检回");
                        bFindOneLine = true;
                    }else if((i == 0 || i == lineInfos.size()-1)
                            && (lineInfos.get(i).hasTopRuling) && (lineInfos.get(i).hasBottomRuling)
                            && (lineInfos.get(i).columnCount>2)){
                        logger.info("第一行或者最后一行满足上下都有线并且存在2列以上的检回");
                        bFindOneLine = true;
                    }
                    if (!bFindOneLine) {
                        logger.info("丢弃单行表格");
                    } else {
                        oneTables.add(i);
                        processOneTable(tablePage);
                    }
                }
            } else {
                if (tag == tableLine) {//表格行
                    //对跨版面的表格进行检查
                    if(nextTag == tableLine && !isInSameLayout(tablePage,currLine,nextLine)) {
                        //不在同一个版面里,但是下一行是表格则强行结束
                        tags[i]=tableEnd;
                        oneTables.add(i);
                        processOneTable(tablePage);
                    }else if(isTwoTables(tablePage,currLine,nextLine,i)){
                        processOneTable(tablePage);
                    }else {
                        oneTables.add(i);
                    }
                } else if (tag == tableEnd) {//表格结束
                    oneTables.add(i);
                    processOneTable(tablePage);
                } else {
                    boolean bFindMultiline = false;
                    int tableLineNum = 0;
                    //前面存在连续多行表格行
                    for (int j = 0; j < i; j++) {
                        if (tags[j] == tableLine) {
                            tableLineNum++;
                        } else {
                            tableLineNum = 0;
                        }
                    }
                    if (((float) tableLineNum / tags.length) >= 0.8) {
                        logger.info("整个页面80%都是连续表格行,检回");
                        bFindMultiline = true;
                    } else if ((tableLineNum == i) && ((float) tableLineNum / tags.length > 0.5)) {
                        logger.info("从页面的第一行开始就是表格的大段表格,检回");
                        bFindMultiline = true;
                    }
                    if (bFindMultiline) {
                        oneTables.add(i);
                        processOneTable(tablePage);
                    } else {
                        logger.info("表格结束异常,丢弃此表格");
                        oneTables.clear();
                    }
                }
            }
        }
        return layoutTableRegions;
    }

    //是否是两个表格合并为一个
    private static boolean isTwoTables(ContentGroupPage tablePage,TrainDataWriter.LineInfo currLine, TrainDataWriter.LineInfo nextLine,int i){
        if (currLine == null || nextLine == null || tablePage == null){
            return false;
        }

        boolean bFlag = false;
        if(currLine.hasTableEndKeyWord){
            //进一步检查
            if(nextLine.patternMatchResult.get(0) == 1 || nextLine.patternMatchResult.get(1) == 1){
                bFlag = true;
            }
            //存在表格行合并错误的情况
            List<TextChunk> chunks= tablePage.getTextChunks(nextLine.getTextChunk());
            for(TextChunk chunk : chunks){
                if(TrainDataWriter.TABLE_BEGINNING_KEYWORD_RE.matcher(chunk.getText()).find()
                        || TrainDataWriter.CHART_BEGINNING_KEYWORD_RE.matcher(chunk.getText()).find()){
                    bFlag = true;
                    tags[i]=otherLine;
                    tags[i+1]=otherLine;
                    break;
                }
            }
        }
        if(bFlag){
            logger.info("两个表格合并为一个");
            TableDebugUtils.writeCells(tablePage, Arrays.asList(currLine.getTextChunk()), "两个表格合并为一个1" );
            TableDebugUtils.writeCells(tablePage, Arrays.asList(nextLine.getTextChunk()), "两个表格合并为一个2" );
            return true;
        }
        return false;
    }

    //是否在同一个版面
    private static boolean isInSameLayout(ContentGroupPage tablePage,TrainDataWriter.LineInfo currLine, TrainDataWriter.LineInfo nextLine){
        if((currLine == null) || (nextLine == null)){
            return true;
        }

        if(layoutAnalysisRectList.size() == 1){
            return true;
        }
        boolean bFlag = false;
        //由于版面存在的可能错误,目前只接受一个版面表格在底部,另一个表格在另一个版面的顶部的特殊情况
        for(Rectangle rec : layoutAnalysisRectList){
            if(rec.contains(currLine.getTextChunk())
                    && !rec.contains(nextLine.getTextChunk())
                    && (currLine.getTextChunk().getCenterY()-nextLine.getTextChunk().getCenterY()>tablePage.getAvgCharHeight()*20)
                    //版面内的最后一行,已经没有文字了
                    && (!currLine.getTextChunk().isHorizontallyOverlap(nextLine.getTextChunk()))){
                bFlag = true;
            }
            if(bFlag){
                logger.info("表格跨版面合并");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(currLine.getTextChunk()), "表格跨版面合并1" );
                TableDebugUtils.writeCells(tablePage, Arrays.asList(nextLine.getTextChunk()), "表格跨版面合并2" );
                return false;
            }
        }
        return true;
    }

    public static void processOneTable(ContentGroupPage tablePage){
        if(oneTables.size()==0){
            return;
        }
        tableRegion=new TableRegion();//重新清0
        TableRegion newTableRegion=null;
        List<TableRegion> tableRegions=new ArrayList<>();
        int index=0;

        //对表格区域不完整问题进行修复
        changeTableLines(tablePage);

        for(int i = 0; i < oneTables.size(); i++){
            index=oneTables.get(i);
            if(removeFirstOrLastLine(tablePage,i)){
                continue;
            } else if(tableRegion.getArea()==0){
                tableRegion=new TableRegion(lineInfos.get(index).getTextChunk());
            }else {
                tableRegion.setRect(tableRegion.createUnion(lineInfos.get(index).getTextChunk()));
            }
        }
        if (tableRegion == null){
            return;
        }

        //对表格边框进行修复
        newTableRegion = repairTableRegion(tablePage);
        oldOneTables.addAll(oneTables);
        if (newTableRegion.getArea() != tableRegion.getArea()){
            oneTables.clear();
            oneTables.addAll(getNewTableRegion(newTableRegion));
        }
        //对此表格进行检查
        if(checkOneTable(tablePage,newTableRegion) && checkContainTables(tablePage,newTableRegion)) {
            layoutTableRegions.add(newTableRegion);
        }
        oneTables.clear();
        oldOneTables.clear();
        return;
    }


    //对表格区域不完整问题进行修复
    private static void changeTableLines(ContentGroupPage tablePage){
        int index = 0;
        int nextIndex = 0;
        List<Integer> tempTables=new ArrayList<>();
        float searchMinY = tablePage.getPaper().topMarginInPt;
        float searchMaxY = (float)tablePage.getHeight() - tablePage.getPaper().bottomMarginInPt;

        //如果上一行不是表格,但是含有表格行的关键字并且文本块不止一个(在后面统一修正)
        if((oneTables.get(0) >= 1) && (lineInfos.get(oneTables.get(0)-1).patternMatchResult.get(0) == 1)
                && (tablePage.getTextChunks(lineInfos.get(oneTables.get(0)-1).getTextChunk()).size()>=3) && (tags[oneTables.get(0)-1] == otherLine)){
            logger.info("前一行可能是由于标题表格行合并引起的行合并问题");
            index=oneTables.get(0)-1;
            oneTables.add(0,oneTables.get(0)-1);
            tags[index]=tableLine;
        }

        //对区域进行截断
        for(int i=0; i < oneTables.size(); i++){
            index=oneTables.get(i);
            if(lineInfos.get(index).getTextChunk().getText().equals("目录")){
                logger.info("错误的包含了目录");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(lineInfos.get(index).getTextChunk()), "错误的包含了目录" );
                break;//后面的都不要了
            }

            tempTables.add(index);
        }
        oneTables=tempTables;
    }

    //获取新的区域
    private static List<Integer> getNewTableRegion(TableRegion tableRegion){
        List<Integer> temp=new ArrayList<>();
        for(int i=0; i< lineInfos.size();i++){
            if (lineInfos.get(i).getTextChunk().overlapRatio(tableRegion,false)>0.8){
                temp.add(i);
            }
        }
        return temp;
    }



    public static boolean removeFirstOrLastLine(ContentGroupPage tablePage,int i){
        int index= oneTables.get(i);
        int num = 0;
        List<TextChunk> lines = lineInfos.stream().map(lineInfo -> lineInfo.getTextChunk()).collect(Collectors.toList());
        float searchMinY = tablePage.getPaper().topMarginInPt;
        float searchMaxY = (float)tablePage.getHeight() - tablePage.getPaper().bottomMarginInPt;
        //说明是第一行
        if(i == 0){
            if ((tags[index] == tableLine) && (lineInfos.get(index).patternMatchResult.get(0)==1 || lineInfos.get(index).patternMatchResult.get(1) == 1)){
                logger.info("表格行的第一行可能错误的包含了表格标题,丢弃");
                List<TextChunk> temp = tablePage.getTextChunks(lineInfos.get(index).getTextChunk());
                logger.info("表格行的第一行包含标题可能是因为表格行合并的错误,此处予以修正");
                for(TextChunk chunk:temp){
                    if(!TrainDataWriter.TABLE_BEGINNING_KEYWORD_RE.matcher(chunk.getText()).find()){
                        if(tableRegion.getArea()==0) {
                            tableRegion = new TableRegion(chunk);
                        }else {
                            tableRegion.setRect(tableRegion.createUnion(chunk));
                        }
                    }else {
                        num++;
                    }
                    if(num>1){
                        tableRegion = new TableRegion();//如果有两个trunk都满足正则,那么区域面积清0
                    }
                }
                TableDebugUtils.writeCells(tablePage, Arrays.asList(lineInfos.get(index).getTextChunk()), "由于表格行合并导致的错误包含标题" );
                return true;
            }
            //表格的第一行在页眉里面并且与下一行的布局不相似并且不包含空格
            if(((tags[index] == tableLine) || ((tags[index] == tableEnd) && (oneTables.size()==1))) && (!lineInfos.get(index).hasNextSimilarTableLine
                    && (lineInfos.get(index).getTextChunk().getBottom() < searchMinY) && lineInfos.get(index).columnCount<3)){
                logger.info("表格行的第一行包含了页眉,丢弃");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(lineInfos.get(index).getTextChunk()), "表格行的第一行包含了页眉" );
                return true;
            }
        }else if (i == oneTables.size()-1){//说明是最后一行
            //如果最后一行是页脚
            if((tags[index] == tableEnd) && (!lineInfos.get(index).hasPrevSimilarTableLine)
                    && (index>=2)
                    && (!TrainDataWriter.hasSimilarTableLayout(tablePage,lines,index,false))//考虑到表格行合并错误,也比较上上一行
                    && (lineInfos.get(index).getTextChunk().getBottom() > searchMaxY)){
                logger.info("表格行的最后一行包含了页脚,丢弃");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(lineInfos.get(index).getTextChunk()), "表格行的最后一行包含了页脚" );
                return true;
            }
            if(lineInfos.get(index).hasTableEndKeyWord){
                logger.info("包含表格结束标志");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(lineInfos.get(index).getTextChunk()), "包含表格结束标志" );
                return true;
            }
        }
        return false;
    }
    //检查由于复杂版面引起的表格包含的问题
    public static boolean checkContainTables(ContentGroupPage tablePage,TableRegion tableRegion){
        for(int i = 0;i < layoutTableRegions.size();i++){
            if(layoutTableRegions.get(i).overlapRatio(tableRegion,false) > 0.5){
                logger.info("表格被包含,替换为最大的那一个");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "表格包含问题1" );
                TableDebugUtils.writeCells(tablePage, Arrays.asList(layoutTableRegions.get(i)), "表格包含问题2" );
                if(tableRegion.getArea()>layoutTableRegions.get(i).getArea()){
                    layoutTableRegions.set(i,tableRegion);
                }
                return false;
            }
        }
        return true;
    }

    public static boolean checkOneTable(ContentGroupPage tablePage,TableRegion tableRegion){

        //检查对Chart的误识别
        if (!checkChartTable(tablePage,tableRegion)){
            return false;
        }

        //对特殊样本的误识别
        if(!checkOthers(tablePage)){
            return false;
        }

        return true;
    }

    public static boolean checkOthers(ContentGroupPage tablePage){
        Pattern OTHER_RE = Pattern.compile("(.*签名：$)");
        int lineNum = oneTables.get(0)-1;
        int lineNum2 = 0;
        if(lineNum>0 && OTHER_RE.matcher(lineInfos.get(lineNum).getTextChunk().getText()).find()){
            logger.info("特殊样本误检,丢弃");
            TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "特殊样本误检,丢弃" );
            return false;
        }
        //只有一列,并且含有冒号
        lineNum=oneTables.stream().filter(line->lineInfos.get(line).columnCount == 1 && lineInfos.get(line).getTextChunk().getText().contains("：")).collect(Collectors.toList()).size();
        if((float)lineNum/oneTables.size()>0.6){
            logger.info("特殊样本误检,丢弃");
            TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "特殊样本误检,丢弃" );
            return false;
        }
        //某一个表格中全是------
        for(Integer i : oneTables){
            String line=lineInfos.get(i).getTextChunk().getText();
            if(line.replace(" ","").length() == StringUtils.countMatches(line,'_')){
                logger.info("特殊样本误检,丢弃");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "特殊样本误检,丢弃" );
                return false;
            }
        }
        return true;
    }
    public static boolean checkParagraghTable(ContentGroupPage tablePage,TableRegion tableRegion,List<Integer> oneTables,List<TrainDataWriter.LineInfo> lineInfos){
        List<Ruling> rulings=Ruling.getRulingsFromArea(tablePage.getRulings(),tableRegion).stream()
                .filter(ruling -> ruling.length()> tablePage.getAvgCharWidth()).collect(Collectors.toList());

        List<Integer> RulingLines=oneTables.stream().filter(line->(lineInfos.get(line).hasBottomRuling
                || lineInfos.get(line).hasTopRuling || lineInfos.get(line).hasRulingRegion)).collect(Collectors.toList());
        int num=0;
        //对有下划线的段落误检
        if(RulingLines.size() == rulings.size()){
            for(int i = 0; i < RulingLines.size(); i++){ //有线行的数量与区域内的线一样多
                Rectangle LineAreas=new Rectangle(lineInfos.get(RulingLines.get(i)).getTextChunk().getVisibleBBox());
                List<TextChunk> lineChunks=tablePage.getTextChunks(LineAreas);
                if ((lineChunks.size() == 1) && (TableRegionDetectionUtils.numberStrRatio(lineChunks) == 0)){ //只有一列并且全是文字
                    num++;
                    if(num >= 2){
                        logger.info("可能是带有下划线的段落,丢弃");
                        TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "丢弃带下划线的段落" );
                        return false;
                    }
                }

            }
        }
        //对大段文字的处理
        if(RulingLines.size()==0) {
            for (int i = 0; i < oneTables.size(); i++) {
                Rectangle LineAreas = new Rectangle(lineInfos.get(oneTables.get(i)).getTextChunk());
                List<TextChunk> lineChunks = tablePage.getTextChunks(LineAreas);
                if ((lineChunks.size() == 1) && (TableRegionDetectionUtils.numberStrRatio(lineChunks) == 0)) { //只有一列并且全是文字
                    num++;
                    if (num >= 10) {
                        logger.info("可能是大段的文字");
                        TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "可能是大段的文字");
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static boolean checkChartTable(ContentGroupPage tablePage,TableRegion tableRegion){

        List<TableRegion> chartRegions = tablePage.getChartRegions("");
        //一定是表格的正则
        Pattern TABLE_RE = Pattern.compile("(^表[(（]?[0-9]{1,2}.*)|(^表[零一二三四五六七八九十].*)" +
                "|(^(table)\\s+((i|ii|iii|iv|v|vi|vii|viii|ix|I|II|III|IV|V|VI|VII|VIII|IX|Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|Ⅶ|Ⅷ|Ⅸ)|(\\d{1,2})).*)" +
                "|(^(the following table).*)|(.*(如下表|如下表所示|见下表)(：|:)$)|(^(下表|以下).*(：|:)$)" +
                "|(.*下表列出.*)|(.*(table)\\s+\\d{1,2}$)|(.*(as follows)[：:.]$)|(^表[0-9].*)|(^(表格|附表).*)|(^附.*表$)|(^表:.*)|(^表(\\d{0,2})?:.*)");

        Pattern CHART_RE = Pattern.compile("(^图.*)");
        int tableLine = 0;
        for(TableRegion chart : chartRegions){
            int num=0;
            if(chart.overlapRatio(tableRegion,false)>0.7){
                //做近一步的检查
                List<Ruling> all = Ruling.getRulingsFromArea(tablePage.getRulings(),chart.getArea()>tableRegion.getArea()?chart:tableRegion);
                List<Ruling> temp =all.stream().filter(ruling -> ruling.length()<tablePage.getAvgCharHeight()*5).collect(Collectors.toList());
                if((float)temp.size()/all.size() >= 0.4){
                    logger.info("短线过多,疑似chart");
                    TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "与chart相交但是短线过多的表格区域");
                    TableDebugUtils.writeCells(tablePage, Arrays.asList(chart), "与chart相交但是短线过多的chart区域");
                    return false;
                }

                //是表格
                if((oneTables.get(0)>0) && TABLE_RE.matcher(lineInfos.get(oneTables.get(0)-1).getTextChunk().getText()).find()){
                    logger.info("与Chart相交但是满足表格的标题，不丢弃");
                    TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "与Chart相交但是满足表格的标题，不丢弃");
                    continue;
                }
                if((oneTables.get(0)>0) && CHART_RE.matcher(lineInfos.get(oneTables.get(0)-1).getTextChunk().getText()).find()){
                    logger.info("与Chart但是满足图的标题，丢弃");
                    TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "与Chart但是满足图的标题，丢弃");
                    return false;
                }

                tableLine=oneTables.stream().filter(table -> lineInfos.get(table).hasNextSimilarTableLine || lineInfos.get(table).hasPrevSimilarTableLine).collect(Collectors.toList()).size();
                if((float)tableLine/oneTables.size()>=0.5){
                    logger.info("与Chart相交但是存在较多表格行,不丢弃");
                    TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "与Chart相交但是存在较多表格行,不丢弃");
                    continue;
                }

                logger.info("与Chart区域相交疑似Chart");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "与Chart区域相交的表格");
                TableDebugUtils.writeCells(tablePage, Arrays.asList(chart), "与Chart区域相交的chart");
                return false;

            }
        }
        tableLine=oldOneTables.stream().filter(table -> lineInfos.get(table).hasRulingRegion).collect(Collectors.toList()).size();
        if(((float)tableLine/oldOneTables.size()>0.8) && ((float)oldOneTables.size()/oneTables.size()<=0.5)){
            TableDebugUtils.writeCells(tablePage, Arrays.asList(tableRegion), "之前全是有线表格,区域扩充了一半以上疑似chart");
            return false;
        }

        return true;
    }
    //找出多出来的两条线
    private static  List<Ruling> findExtendRulings(List<Ruling> rulings,List<Ruling> extendRulings){
        List<Ruling> moreRulings = new ArrayList<>();
        boolean bFind=false;
        for(Ruling exRule : extendRulings){
            if(!rulings.contains(exRule)){
                moreRulings.add(exRule);
            }
        }
        return moreRulings;
    }
    //表格边框修复
    public static TableRegion repairTableRegion(ContentGroupPage tablePage){
        //通常无线区域的上方和下方刚好有线,上下扩展5个单位
        double extendHeight=tablePage.getAvgCharHeight();
        double maxRuleLenth=0;
        TableRegion extendTable=new TableRegion(tableRegion.getLeft(),tableRegion.getTop()-extendHeight,tableRegion.getWidth(),tableRegion.getHeight()+extendHeight*2);
        List<Ruling> rulings = Ruling.getRulingsFromArea(tablePage.getRulings(), tableRegion);
        List<Ruling> extendRulings = Ruling.getRulingsFromArea(tablePage.getRulings(), extendTable);
        List<Ruling> moreRulings = findExtendRulings(rulings,extendRulings);
        //找出原来区域里最长的线
        for(Ruling rule : rulings){
            if(rule.length()>maxRuleLenth){
                maxRuleLenth=rule.length();
            }
        }
        //TableDebugUtils.writeLines(tablePage, moreRulings, "扩展的线");
        //超过原来最大长度的百分之30的线不要
        if(maxRuleLenth != 0) {
            final double maxRulingLenth = maxRuleLenth * 1.3;
            extendRulings = extendRulings.stream().filter(ruling -> ruling.length() > tablePage.getAvgCharWidth()
                    && ruling.length() < maxRulingLenth).collect(Collectors.toList());//过滤一些无意义的线
        }

        if (extendRulings.size()==0){
            return tableRegion;
        }

        if((extendRulings.size()-rulings.size())<=2){
            rulings=extendRulings;
        }else{
            return tableRegion;
        }

        Rectangle2D bound=Ruling.getBounds(rulings).createUnion(tableRegion);
        List<TextChunk> newTextChunks=getNewTextChunks(tablePage.getTextChunks(new Rectangle(bound)),tablePage.getTextChunks(tableRegion));
        if(newTextChunks.size()==0){
            return new TableRegion(bound);
        } else{
            return new TableRegion(removeTextChunkAreas(tablePage,new Rectangle(bound),tableRegion,newTextChunks));
        }
    }

    private static void getNewTableIndex(Rectangle newArea){
        oneTables.clear();
        for(int i=0;i<lineInfos.size();i++){
            if(newArea.overlapRatio(lineInfos.get(i).getTextChunk(),false)>0.8){
                oneTables.add(i);
            }
        }
    }
    //再次修正表格区域
    private static Rectangle removeTextChunkAreas(ContentGroupPage tablePage,Rectangle newArea,Rectangle oldArea,List<TextChunk> newTextChunks){
        //合并表格行
        List<TextBlock> textLines = TextMerger.collectByRows(TextMerger.groupByBlock(newTextChunks, tablePage.getHorizontalRulings(), tablePage.getVerticalRulings()));
        if (textLines.size() < 3) {//如果新增的只多出来2行
            for(TextBlock line:textLines){
                if(TrainDataWriter.TABLE_BEGINNING_KEYWORD_RE.matcher(line.getText()).find() || TrainDataWriter.TABLE_END_KEYWORD_RE.matcher(line.getText()).find()){
                    return oldArea;
                }
            }
            return newArea;
        } else {
            logger.info("修正后的文本边框内新增超过3个表格行");
            TableDebugUtils.writeCells(tablePage, Arrays.asList(oldArea), "新增三个表格行前" );
            TableDebugUtils.writeCells(tablePage, Arrays.asList(newArea), "新增三个表格行后" );
            return newArea;
        }
    }

    //获取新增的TextChunks
    private static List<TextChunk> getNewTextChunks(List<TextChunk> newTextChunks,List<TextChunk> oldTextChunks){
        List<TextChunk> results=new ArrayList<>();
        for(int i=0; i<newTextChunks.size(); i++){
            boolean bFind=false;
            if(StringUtil.isBlank(newTextChunks.get(i).getText().trim())){//空的Chunk不考虑
                continue;
            }
            for(int j=0; j<oldTextChunks.size(); j++){
                if(newTextChunks.get(i).equals(oldTextChunks.get(j))){
                    bFind=true;
                    break;
                }
            }
            if(!bFind){
                results.add(newTextChunks.get(i));
            }
        }
        return results;
    }
    public static boolean chooseNolineTableByCRF(Table nolineTable,Table crf){
        List<TrainDataWriter.LineInfo> temlNoLines=lineInfos.stream().filter(lineInfo -> lineInfo.getTextChunk().intersects(nolineTable)).collect(Collectors.toList());
        List<TrainDataWriter.LineInfo> temlCRFLines=lineInfos.stream().filter(lineInfo -> lineInfo.getTextChunk().intersects(crf)).collect(Collectors.toList());
        TrainDataWriter.LineInfo vectorNextLine = null;
        TrainDataWriter.LineInfo vectorPrevLine = null;
        TrainDataWriter.LineInfo crfNextLine = null;
        TrainDataWriter.LineInfo crfPrevLine = null;
        for(int i=0; i < lineInfos.size();i++){
            vectorNextLine = i<lineInfos.size()-1 ? lineInfos.get(i+1):null;
            if(lineInfos.get(i).getTextChunk().intersects(nolineTable) && vectorNextLine != null
                    && !vectorNextLine.getTextChunk().intersects(nolineTable)){
                break;
            }
        }
        for(int i=0; i < lineInfos.size();i++){
            if(lineInfos.get(i).getTextChunk().intersects(nolineTable) && i > 0 && !lineInfos.get(i-1).getTextChunk().intersects(nolineTable)){
                vectorPrevLine = lineInfos.get(i-1);
                break;
            }
        }
        for(int i=0; i < lineInfos.size();i++){
            crfNextLine = i<lineInfos.size()-1 ? lineInfos.get(i+1):null;
            if(lineInfos.get(i).getTextChunk().intersects(crf) && crfNextLine != null
                    && !crfNextLine.getTextChunk().intersects(crf)){
                break;
            }
        }
        for(int i=0; i < lineInfos.size();i++){
            if(lineInfos.get(i).getTextChunk().intersects(crf) && i > 0 && !lineInfos.get(i-1).getTextChunk().intersects(crf)){
                crfPrevLine = lineInfos.get(i-1);
                break;
            }
        }
        if(vectorNextLine != null  && vectorNextLine.hasTableEndKeyWord && (crfNextLine == null || !crfNextLine.hasTableEndKeyWord)){
            logger.info("矢量存在清晰的结束边界,而CRF没有,选择矢量");
            return true;
        }
        if(vectorPrevLine != null && vectorPrevLine.patternMatchResult.get(0) == 1 && (crfPrevLine == null || crfPrevLine.patternMatchResult.get(0) == 0)){
            logger.info("矢量存在清晰的开始边界,而CRF没有,选择矢量");
            return true;
        }

        return false;
    }
    public static void repairVectorRegion(Table table){
        List<TrainDataWriter.LineInfo> temlLines = lineInfos.stream().filter(lineInfo -> lineInfo.getTextChunk().intersects(table)).collect(Collectors.toList());
        //矢量第一行经常包含表格开头
        if(temlLines.get(0).patternMatchResult.get(0) == 1){
            table.setTop(temlLines.get(0).getTextChunk().getBottom());
        }
        //矢量第一行经常包含表格结尾
        if(temlLines.get(temlLines.size()-1).hasTableEndKeyWord){
            table.setBottom(temlLines.get(temlLines.size()-1).getTextChunk().getTop());
        }
    }

    public static void removeTableByCrfFeatures(ContentGroupPage page, List<Table> lineTables,List<Table> nonLinesTables){
        List<Table> vectorNoLineTables = nonLinesTables.stream().filter(table -> !table.isCrfTable()).collect(Collectors.toList());
        List<Table> vectorLineTables = lineTables.stream().filter(table -> !table.isCrfTable()).collect(Collectors.toList());
        List<Table> removeNolineTables = new ArrayList<>();
        List<Table> removelineTables = new ArrayList<>();
        boolean bRemoveFlag = false;
        float avgChartHeight = page.getAvgCharHeight();
        float searchMinY = page.getPaper().topMarginInPt;
        float searchMaxY = (float)page.getHeight() - page.getPaper().bottomMarginInPt;
        List<TrainDataWriter.LineInfo> temlLines;
        int prevLineIndex = 0;
        for(Table table : vectorNoLineTables){
            //对单行表格进行检查
            bRemoveFlag = false;
            temlLines = lineInfos.stream().filter(lineInfo -> lineInfo.getTextChunk().intersects(table)).collect(Collectors.toList());
            if(temlLines.size() == 1) {
                prevLineIndex = lineInfos.indexOf(temlLines.get(0))-1;
                if(temlLines.get(0).numberRatio == 1){
                    bRemoveFlag = true;
                }else if(temlLines.get(0).getTextChunk().getTop()<searchMinY || temlLines.get(0).getTextChunk().getBottom()>searchMaxY){
                    bRemoveFlag = true;
                }else if(temlLines.get(0).hasTopSpaceLine && temlLines.get(0).hasBottomSpaceLine) {
                    bRemoveFlag = true;
                }else if(temlLines.get(0).hasTableEndKeyWord){
                    bRemoveFlag = true;
                }else if(temlLines.get(0).patternMatchResult.get(0) == 1 || temlLines.get(0).patternMatchResult.get(1) == 1){
                    bRemoveFlag = true;
                }else if(temlLines.get(0).widthLayout>0.9 || temlLines.get(0).heightLayout>0.9){
                    bRemoveFlag = true;//单独在一个版面内的无线表格丢弃
                }else if(prevLineIndex>0 && lineInfos.get(prevLineIndex).patternMatchResult.get(1) == 1){
                    bRemoveFlag = true;
                }

                if(!removeNolineTables.contains(table) && bRemoveFlag){
                    removeNolineTables.add(table);
                }
            } else if(temlLines.size() == 2 || temlLines.size() == 3){
                for(int i = 0;i < temlLines.size(); i++){
                    prevLineIndex = lineInfos.indexOf(temlLines.get(i))-1;
                    if(temlLines.get(i).hasTableEndKeyWord){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(i).numberTextChunks == 1 && ((!temlLines.get(i).hasTopRuling && !temlLines.get(i).hasBottomRuling) || (prevLineIndex > 0 && lineInfos.get(prevLineIndex).numberTextChunks== 1))){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(0).getTextChunk().getTop()<searchMinY || temlLines.get(0).getTextChunk().getBottom()>searchMaxY){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(0).patternMatchResult.get(0) == 1 || temlLines.get(0).patternMatchResult.get(1) == 1){
                        bRemoveFlag = true;
                        break;
                    }
                }
                if(!removeNolineTables.contains(table) && bRemoveFlag){
                    removeNolineTables.add(table);
                }
            }else if(table.getRowCount()<=3 && temlLines.size()<=5){
                int num = 0;
                for(int i = 0;i < temlLines.size(); i++){
                    if(temlLines.get(i).hasTableEndKeyWord){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(i).numberTextChunks == 1){
                        num++;
                        if(num>1){
                            bRemoveFlag = true;
                            break;
                        }
                    }else if(temlLines.get(0).getTextChunk().getTop()<searchMinY || temlLines.get(0).getTextChunk().getBottom()>searchMaxY){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(0).patternMatchResult.get(0) == 1 || temlLines.get(0).patternMatchResult.get(1) == 1){
                        bRemoveFlag = true;
                        break;
                    }
                }
                if(!removeNolineTables.contains(table) && bRemoveFlag){
                    removeNolineTables.add(table);
                }
            }else {
                int num = 0;
                List<TrainDataWriter.LineInfo> temp;
                temp=temlLines.stream().filter(lineInfo -> lineInfo.getTextChunk().getText().contains("··")).collect(Collectors.toList());
                if((float)temp.size()/temlLines.size()>0.8 && temp.size()>5){
                    bRemoveFlag = true;
                }
                if(!removeNolineTables.contains(table) && bRemoveFlag){
                    removeNolineTables.add(table);
                }
            }
        }
        TableDebugUtils.writeTables(page,removeNolineTables,"CRF特征过滤掉的表格");
        nonLinesTables.removeAll(removeNolineTables);
        //有线表格标准可以适当放松
        for(Table table : vectorLineTables){
            //对单行表格进行检查
            bRemoveFlag = false;
            temlLines = lineInfos.stream().filter(lineInfo -> lineInfo.getTextChunk().intersects(table)).collect(Collectors.toList());
            if (temlLines.size() == 1) {
                if(temlLines.get(0).hasRulingRegion){
                    continue;
                }
                if(temlLines.get(0).getTextChunk().getTop()<searchMinY || temlLines.get(0).getTextChunk().getBottom()>searchMaxY){
                    bRemoveFlag = true;
                }else if(temlLines.get(0).hasTableEndKeyWord){
                    bRemoveFlag = true;
                }else if(temlLines.get(0).patternMatchResult.get(0) == 1 || temlLines.get(0).patternMatchResult.get(1) == 1){
                    bRemoveFlag = true;
                }
                if(!removelineTables.contains(table) && bRemoveFlag){
                    removelineTables.add(table);
                }
            } else if(temlLines.size() == 2 || temlLines.size() == 3){
                for(int i = 0;i < temlLines.size();i++){
                    if(temlLines.get(i).hasTableEndKeyWord){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(0).getTextChunk().getTop()<searchMinY || temlLines.get(0).getTextChunk().getBottom()>searchMaxY){
                        bRemoveFlag = true;
                        break;
                    }else if(temlLines.get(0).patternMatchResult.get(0) == 1 || temlLines.get(0).patternMatchResult.get(1) == 1){
                        bRemoveFlag = true;
                        break;
                    }
                }
                if(!removelineTables.contains(table) && bRemoveFlag){
                    removelineTables.add(table);
                }
            }
        }
        TableDebugUtils.writeTables(page,removelineTables,"CRF特征过滤掉的表格");
        lineTables.removeAll(removelineTables);
    }
}