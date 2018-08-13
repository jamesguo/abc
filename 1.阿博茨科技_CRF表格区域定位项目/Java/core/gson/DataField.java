package com.abcft.pdfextract.core.gson;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 表明此字段是一个数据字段。
 *
 * Created by chzhong on 2018/4/13.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DataField {

    /**
     * 表明此字段是一个高级数据。
     *
     * @return true 表示高级数据，false 表示常用数据。
     */
    boolean advanced() default false;

    /**
     * 表明此字段包含内联数据。
     *
     * @return true 表示内联数据，false 表示数据可能在别处。
     */
    boolean inline() default true;

}
