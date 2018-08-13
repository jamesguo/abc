package com.abcft.pdfextract.core.gson;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;

/**
 * Created by dhu on 2018/1/6.
 */
public final class DocumentExclusionStrategy {

    public static final ExclusionStrategy SUMMARY = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            return fieldAttributes.getAnnotation(Summary.class) == null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> aClass) {
            return false;
        }

    };

    public static final ExclusionStrategy DETAIL = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            return fieldAttributes.getAnnotation(Summary.class) == null
                    && fieldAttributes.getAnnotation(Detail.class) == null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> aClass) {
            return false;
        }

    };

    public static final ExclusionStrategy LITE = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            DataField dataFieldAnnotation = fieldAttributes.getAnnotation(DataField.class);
            return fieldAttributes.getAnnotation(MetaField.class) == null
                    && fieldAttributes.getAnnotation(StatusFields.class) == null
                    && (null == dataFieldAnnotation || dataFieldAnnotation.advanced());

        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };


    public static final ExclusionStrategy DATA_JSON = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            DataField dataFieldAnnotation = fieldAttributes.getAnnotation(DataField.class);
            return fieldAttributes.getAnnotation(MetaField.class) == null
                    && (null == dataFieldAnnotation || dataFieldAnnotation.inline());
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };


    private DocumentExclusionStrategy() {}
}
