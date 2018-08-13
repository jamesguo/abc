package com.abcft.pdfextract.core.model;

import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Created by dhu on 2017/11/7.
 */
public class PDFOperator {
    public static final String BEGIN_TEXT_OBJECT = "BT";
    public static final String END_TEXT_OBJECT = "ET";
    public static final String SAVE_GRAPHICS_STATE = "q";
    public static final String RESTORE_GRAPHICS_STATE = "Q";
    public static final String SET_FONT_AND_SIZE = "Tf";
    public static final String BEGIN_MARKED_CONTENT1 = "BMC";
    public static final String BEGIN_MARKED_CONTENT2 = "BDC";
    public static final String END_MARKED_CONTENT = "EMC";
    public static final String DRAW_OBJECT = "Do";

    public static final Set<String> BEGIN_GROUP = Sets.newHashSet(BEGIN_TEXT_OBJECT, SAVE_GRAPHICS_STATE);
    public static final Set<String> END_GROUP = Sets.newHashSet(END_TEXT_OBJECT, RESTORE_GRAPHICS_STATE);

}
