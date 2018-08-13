package com.abcft.pdfextract.core.gson;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by dhu on 2018/1/6.
 * 表示字段会在输出详细信息的时候被输出
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Detail {
}
