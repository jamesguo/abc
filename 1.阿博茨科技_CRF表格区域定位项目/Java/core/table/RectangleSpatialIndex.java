package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.util.NumberUtil;
import gnu.trove.procedure.TIntProcedure;
import net.sf.jsi.SpatialIndex;
import net.sf.jsi.rtree.RTree;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("PackageAccessibility")
public class RectangleSpatialIndex<T extends Rectangle> {

    public interface RectangleSpatialIndexSpi<T extends Rectangle> {
        void clear();
        void add(T rectangle);
        List<T> contains(Rectangle rectangle);
        List<T> intersects(Rectangle rectangle);
        Rectangle getBounds();

    }

    private static final class JsiImpl<T extends Rectangle> implements RectangleSpatialIndexSpi<T> {

        private final SpatialIndex si;
        private final List<T> rectangles;
        private Rectangle bounds = null;

        JsiImpl() {
            si = new RTree();
            si.init(null);
            rectangles = new ArrayList<>();
        }

        public void clear() {
            for (int i = 0; i < rectangles.size(); ++i) {
                T rect = rectangles.get(i);
                net.sf.jsi.Rectangle r = rectangleToSpatialIndexRectangle(rect);
                si.delete(r, i);
            }
            rectangles.clear();
        }

        public void add(T te) {
            rectangles.add(te);
            if (bounds == null) {
                bounds = new Rectangle();
                bounds.setRect(te);
            } else {
                bounds.merge(te);
            }
            si.add(rectangleToSpatialIndexRectangle(te), rectangles.size() - 1);
        }

        public List<T> contains(Rectangle r) {
            SaveToListProcedure proc = new SaveToListProcedure();
            si.contains(rectangleToSpatialIndexRectangle(r), proc);
            ArrayList<T> rv = new ArrayList<>();
            for (int i : proc.getIds()) {
                rv.add(rectangles.get(i));
            }
            NumberUtil.sortByReadingOrder(rv);
            return rv;
        }

        public List<T> intersects(Rectangle r) {
            SaveToListProcedure proc = new SaveToListProcedure();
            si.intersects(rectangleToSpatialIndexRectangle(r), proc);
            ArrayList<T> rv = new ArrayList<>();
            for (int i : proc.getIds()) {
                rv.add(rectangles.get(i));
            }
            NumberUtil.sortByReadingOrder(rv);
            return rv;
        }

        /**
         * Minimum bounding box of all the Rectangles contained on this RectangleSpatialIndex
         *
         * @return a Rectangle
         */
        public Rectangle getBounds() {
            return bounds;
        }

        private static net.sf.jsi.Rectangle rectangleToSpatialIndexRectangle(Rectangle r) {
            return new net.sf.jsi.Rectangle((float) r.getX(),
                    (float) r.getY(),
                    (float) (r.getX() + r.getWidth()),
                    (float) (r.getY() + r.getHeight()));
        }

    }

    private final RectangleSpatialIndexSpi<T> impl;

    public RectangleSpatialIndex() {
        impl = new JsiImpl<>();
    }

    public void clear() {
        impl.clear();
    }

    public void add(T te) {
        impl.add(te);
    }

    public void addAll(List<T> items) {
        for (T item : items) {
            impl.add(item);
        }
    }


    public List<T> contains(Rectangle r) {
        return impl.contains(r);
    }

    public List<T> intersects(Rectangle r) {
        return impl.intersects(r);
    }


    /**
     * Minimum bounding box of all the Rectangles contained on this RectangleSpatialIndex
     *
     * @return a Rectangle
     */
    public Rectangle getBounds() {
        return impl.getBounds();
    }


    private static class SaveToListProcedure implements TIntProcedure {
        private List<Integer> ids = new ArrayList<>();

        public boolean execute(int id) {
            ids.add(id);
            return true;
        }

        private List<Integer> getIds() {
            return ids;
        }
    }

}
