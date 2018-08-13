package com.abcft.pdfextract.core.gson;

import java.lang.annotation.*;

/**
 * 指定一组额外的数据字段。
 *
 * Created by chzhong on 2018/4/13.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ExtraDataFields.class)
public @interface DataFields {

    String[] value();

    /**
     * 表明这是一组高级数据字段。
     */
    boolean advanced() default false;

    /**
     * 表明这是一组内联数据字段。
     */
    boolean inline() default true;
}
