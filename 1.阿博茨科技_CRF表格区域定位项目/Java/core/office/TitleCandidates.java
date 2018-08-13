package com.abcft.pdfextract.core.office;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.abcft.pdfextract.core.office.OfficeRegex.FILTERED_PATTERNS;


public class TitleCandidates {

    private static class TitleInfo {
        // TODO: add more info like fontSize, color, centered
        public final String text;

        TitleInfo(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return "TitleInfo{" +
                    "text='" + text + '\'' +
                    '}';
        }
    }



    private static final Logger logger = LogManager.getLogger(TitleCandidates.class);
    private static final int CANDIDATES_MAX_COUNT = 10;



    private TitleInfo[] titles;
    private int count;

    TitleCandidates() {
        this.titles = new TitleInfo[CANDIDATES_MAX_COUNT];
        this.count = 0;
    }

    public void addTitle(String title) {
        title = replaceSpecial(title);
        title = title.trim();
        if (StringUtils.isEmpty(title)) {
            return;
        }
        int len = title.length();
        if (len == 1 || len >= 40) {
            return;
        }
        if(this.count==10){
            clear();
        }
        int index = this.count % CANDIDATES_MAX_COUNT;
        TitleInfo titleInfo1 = new TitleInfo(title);
        if (count >= 2  && shouldBeChange(this.titles[index - 1], titleInfo1)) {
            this.titles[index] = this.titles[index-1];
            this.titles[index-1] = titleInfo1;
        }else {
            this.titles[index] = titleInfo1;
        }
        this.count += 1;
    }

    public int getSize() {
        return (int) Arrays.stream(titles).filter(Objects::nonNull).count();
    }

    public boolean shouldBeChange(TitleInfo t1, TitleInfo t2) {
        if(t1==null || t2== null){
            return false;
        }
        Matcher matcher1 = OfficeRegex.TITLE_TBL_CHART_PATTERN.matcher(t1.text);
        Matcher matcher2 = OfficeRegex.TITLE_TBL_CHART_PATTERN.matcher(t2.text);
        if (matcher1.find() && matcher2.find()) {
            int num1 = Integer.parseInt(matcher1.group().substring(1));
            int num2 = Integer.parseInt(matcher2.group().substring(1));
            return num1 < num2;
        }
        return false;
    }


    @Nullable
    public String getPossibleTitle() {
        String title = null;
        int maxProbability = -1;
        for (int i = this.count - 1, j = CANDIDATES_MAX_COUNT; i >= 0 && j > 0; i--, j--) {
            int index = i % CANDIDATES_MAX_COUNT;
            TitleInfo titleInfo = this.titles[index];
            if(titleInfo == null){
                continue;
            }
            int p = getTitleProbability(titleInfo);
            if (maxProbability < p) {
                maxProbability = p;
                title = titleInfo.text;
                this.titles[index] = null;
                this.count -= 1;
            }
        }
        return title;
    }

    private static int getTitleProbability(TitleInfo titleInfo) {
        // TODO: and more condition

        if (shouldBeFiltered(titleInfo.text)) {
            return 0;
        }
        return 10;
    }

    public static boolean shouldBeFiltered(String text) {
        for (Pattern pattern : FILTERED_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Set<Character> SPECIAL_CHARS = new HashSet() {{
        add('　'); //中文空白符
        add('。');
    }};

    private static String replaceSpecial(String text) {
        for (Character c : SPECIAL_CHARS) {
            text = text.replace(c, ' ');
        }
        return text;
    }

    public void clear() {
        this.titles = new TitleInfo[CANDIDATES_MAX_COUNT];
        this.count = 0;
    }


    public static void main(String[] args) {
        Matcher t = OfficeRegex.TITLE_TBL_CHART_PATTERN.matcher("图13 标题");
        boolean b = t.find();
        if (b) {
            System.out.println(t.group());
        }
    }
}
