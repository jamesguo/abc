package com.abcft.pdfextract.core.gson;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注明该类型下相关的额外数据字段。
 *
 * Created by chzhong on 2018/4/13.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExtraDataFields {

    DataFields[] value();
}
