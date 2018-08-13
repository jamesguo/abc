package com.abcft.pdfextract.core.util;

import com.abcft.pdfextract.core.model.Rectangle;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.JavaVersion;
import org.apache.pdfbox.util.QuickSort;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Utility methods for number.
 *
 * Created by chzhong on 17-5-18.
 */
public final class NumberUtil {

    private static final boolean useQuickSort = useCustomQuickSort();

    private static boolean useCustomQuickSort() {
        // check if we need to use the custom quicksort algorithm as a
        // workaround to the PDFBOX-1512 transitivity issue of TextPositionComparator:
        return JavaVersion.JAVA_RECENT.atLeast(JavaVersion.JAVA_1_7);
    }


    /**
     * Wrap Collections.sort so we can fallback to a non-stable quick sort
     * if we're running on JDK7+
     */
    public static <T extends Comparable<? super T>> void sort(List<T> list) {
        if (useQuickSort) {
            QuickSort.sort(list);
        } else {
            Collections.sort(list);
        }
    }

    /**
     * Same as {@link Collections#sort(List, Comparator)}.
     */
    public static <T> void sort(List<T> list, Comparator<T> comparator) {
        if (useQuickSort) {
            QuickSort.sort(list, comparator);
        } else {
            list.sort(comparator);
        }
    }

    public static void sortByReadingOrder(List<? extends Rectangle> rectangles) {
        if (rectangles == null || rectangles.isEmpty()) {
            return;
        }
        // 先按照y排序
        sort(rectangles, Comparator.comparingDouble(Rectangle::getTop));
        Map<Rectangle, Integer> lineMap = new HashMap<>();
        // 合并行
        List<Rectangle> lines = new ArrayList<>();
        for (Rectangle rectangle : rectangles) {
            int bestLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                Rectangle line = lines.get(i);
                if (rectangle.verticalOverlapRatio(line) > 0.5) {
                    bestLine = i;
                    break;
                }
            }
            if (bestLine == -1) {
                lineMap.put(rectangle, lines.size());
                lines.add(rectangle);
            } else {
                lineMap.put(rectangle, bestLine);
            }
        }

        // 先按照行号排序, 同一行的按照x排序
        sort(rectangles, (o1, o2) -> {
            int v = Integer.compare(lineMap.get(o1), lineMap.get(o2));
            if (v != 0) {
                return v;
            }
            return Double.compare(o1.getLeft(), o2.getLeft());
        });
    }

    private static class RectangleWrapper<T> extends Rectangle {
        private final T obj;

        RectangleWrapper(T obj, Function<T, Rectangle> toRectangleFunction) {
            super();
            setRect(toRectangleFunction.apply(obj));
            this.obj = obj;
        }

    }

    public static <T> void sortByReadingOrder(List<T> rectangles, Function<T, Rectangle> toRectangleFunction) {
        if (rectangles == null || rectangles.isEmpty()) {
            return;
        }
        List<RectangleWrapper<T>> wrappers = rectangles.stream()
                .map(rect -> new RectangleWrapper<T>(rect, toRectangleFunction))
                .collect(Collectors.toList());
        sortByReadingOrder(wrappers);
        sort(rectangles, (o1, o2) -> {
            if (toRectangleFunction.apply(o1).equals(toRectangleFunction.apply(o2))) {
                return 0;
            }
            for (RectangleWrapper<T> wrapper : wrappers) {
                if (wrapper.obj == o1) {
                    return -1;
                } else if (wrapper.obj == o2) {
                    return 1;
                }
            }
            return 0;
        });
    }

    public static List<Float> parseFloatList(String option) throws ParseException {
        String[] f = option.split(",");
        List<Float> rv = new ArrayList<>();
        try {
            for (int i = 0; i < f.length; i++) {
                rv.add(Float.parseFloat(f[i]));
            }
            return rv;
        } catch (NumberFormatException e) {
            throw new ParseException("Wrong number syntax");
        }
    }

    private NumberUtil() {}


    // range iterator
    public static List<Integer> range(final int begin, final int end) {
        return new AbstractList<Integer>() {
            @Override
            public Integer get(int index) {
                return begin + index;
            }

            @Override
            public int size() {
                return end - begin;
            }
        };
    }

    public static int ringedNextIndex(int index, int max) {
        if (++index >= max) {
            return 0;
        } else {
            return index;
        }
    }

}
