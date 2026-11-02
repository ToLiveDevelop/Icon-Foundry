package dev.iconmanager;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Ordered filters applied to system icons that have no pack or manual replacement. */
final class IconFilters {
    static final int MAX_FILTERS = 10;

    private IconFilters() {}

    /** One step of the chain; new filter types implement this and register in {@link #fromJson}. */
    interface Filter {
        String type();
        int label();
        boolean neutral();
        Filter copy();
        JSONObject toJson() throws JSONException;
        void apply(Bitmap bitmap);
    }

    abstract static class AdjustableFilter implements Filter {
        final String[] fields;
        final int[] labels, min, max, defaults, values;

        AdjustableFilter(String[] fields, int[] labels, int[] min, int[] max, int[] defaults) {
            this.fields = fields;
            this.labels = labels;
            this.min = min;
            this.max = max;
            this.defaults = defaults;
            values = defaults.clone();
        }

        abstract AdjustableFilter newInstance();

        int valueFormat(int index) { return R.string.filter_value; }

        @Override public boolean neutral() { return java.util.Arrays.equals(values, defaults); }

        @Override public Filter copy() {
            AdjustableFilter copy = newInstance();
            System.arraycopy(values, 0, copy.values, 0, values.length);
            return copy;
        }

        void set(int index, int value) {
            if (value < min[index] || value > max[index]) throw new IllegalArgumentException(App.text(R.string.filters_invalid));
            values[index] = value;
        }

        @Override public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject().put("type", type());
            for (int i = 0; i < fields.length; i++) if (values[i] != defaults[i]) json.put(fields[i], values[i]);
            return json;
        }

        void readValues(JSONObject json) throws JSONException {
            for (int i = 0; i < fields.length; i++) {
                Object raw = json.opt(fields[i]);
                if (raw == null) continue;
                if (!(raw instanceof Integer value)) throw new JSONException(App.text(R.string.filters_invalid));
                try { set(i, value); }
                catch (IllegalArgumentException error) { throw new JSONException(error.getMessage()); }
            }
            for (var keys = json.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!key.equals("type") && !java.util.Arrays.asList(fields).contains(key)) {
                    throw new JSONException(App.text(R.string.filters_invalid));
                }
            }
        }
    }

    static final class ColorCorrection extends AdjustableFilter {
        static final String TYPE = "color";
        /** Order matches the editor; every value is a percentage. */
        static final String[] FIELDS = {"brightness", "contrast", "saturation", "red", "green", "blue", "black", "opacity"};
        static final int[] LABELS = {R.string.filter_brightness, R.string.filter_contrast, R.string.filter_saturation,
                R.string.filter_red, R.string.filter_green, R.string.filter_blue, R.string.filter_black,
                R.string.filter_opacity};
        static final int[] MIN = {-100, -100, -100, -100, -100, -100, -100, 0};
        static final int[] MAX = {100, 100, 100, 100, 100, 100, 100, 100};
        static final int[] DEFAULT = {0, 0, 0, 0, 0, 0, 0, 100};

        ColorCorrection() { super(FIELDS, LABELS, MIN, MAX, DEFAULT); }
        @Override public String type() { return TYPE; }
        @Override public int label() { return R.string.filter_color; }
        @Override AdjustableFilter newInstance() { return new ColorCorrection(); }

        static ColorCorrection fromJson(JSONObject j) throws JSONException {
            ColorCorrection filter = new ColorCorrection();
            filter.readValues(j);
            return filter;
        }

        ColorMatrix matrix() {
            ColorMatrix result = new ColorMatrix();
            ColorMatrix saturation = new ColorMatrix();
            saturation.setSaturation(1 + values[2] / 100f);
            result.postConcat(saturation);
            ColorMatrix channels = new ColorMatrix();
            channels.setScale(1 + values[3] / 100f, 1 + values[4] / 100f, 1 + values[5] / 100f, 1);
            result.postConcat(channels);
            // Contrast pivots around mid-grey; positive values reach three times the original slope.
            float contrast = values[1] < 0 ? 1 + values[1] / 100f : 1 + values[1] / 50f;
            result.postConcat(linear(contrast, 127.5f * (1 - contrast)));
            result.postConcat(linear(1, values[0] * 1.28f));
            // Positive black deepens shadows by moving the black point up; negative lifts them to grey.
            float black = values[6] / 100f * .5f;
            result.postConcat(black >= 0 ? linear(1 / (1 - black), -255 * black / (1 - black))
                    : linear(1 + black, -255 * black));
            ColorMatrix alpha = new ColorMatrix();
            alpha.setScale(1, 1, 1, values[7] / 100f);
            result.postConcat(alpha);
            return result;
        }

        private static ColorMatrix linear(float scale, float offset) {
            return new ColorMatrix(new float[]{
                    scale, 0, 0, 0, offset,
                    0, scale, 0, 0, offset,
                    0, 0, scale, 0, offset,
                    0, 0, 0, 1, 0});
        }

        @Override public void apply(Bitmap bitmap) {
            if (neutral()) return;
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                paint.setColorFilter(new ColorMatrixColorFilter(matrix()));
                canvas.drawBitmap(copy, 0, 0, paint);
            } finally { copy.recycle(); }
        }
    }

    static final class Position extends AdjustableFilter {
        static final String TYPE = "position";
        static final String[] FIELDS = {"scale", "offsetX", "offsetY", "skewX", "skewY"};
        static final int[] LABELS = {R.string.filter_position_scale, R.string.filter_position_x,
                R.string.filter_position_y, R.string.filter_position_skew_x, R.string.filter_position_skew_y};
        static final int[] MIN = {IconOverlay.MIN_SCALE, -100, -100, -45, -45};
        static final int[] MAX = {IconOverlay.MAX_SCALE, 100, 100, 45, 45};
        static final int[] DEFAULT = {100, 0, 0, 0, 0};

        Position() { super(FIELDS, LABELS, MIN, MAX, DEFAULT); }
        @Override public String type() { return TYPE; }
        @Override public int label() { return R.string.filter_position; }
        @Override AdjustableFilter newInstance() { return new Position(); }
        @Override int valueFormat(int index) {
            return index < 3 ? R.string.filter_value_percent : R.string.filter_value_degrees;
        }

        static Position fromJson(JSONObject json) throws JSONException {
            Position filter = new Position();
            filter.readValues(json);
            return filter;
        }

        @Override public void apply(Bitmap bitmap) {
            if (neutral()) return;
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                float cx = bitmap.getWidth() / 2f, cy = bitmap.getHeight() / 2f;
                float scale = values[0] / 100f;
                float skewX = (float) Math.tan(Math.toRadians(values[3]));
                float skewY = (float) Math.tan(Math.toRadians(values[4]));
                canvas.translate(cx + bitmap.getWidth() * values[1] / 100f,
                        cy + bitmap.getHeight() * values[2] / 100f);
                canvas.skew(0, skewY);
                canvas.skew(skewX, 0);
                canvas.scale(scale, scale);
                canvas.translate(-cx, -cy);
                canvas.drawBitmap(copy, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            } finally { copy.recycle(); }
        }
    }

    static Filter create(String type) {
        if (ColorCorrection.TYPE.equals(type)) return new ColorCorrection();
        if (Position.TYPE.equals(type)) return new Position();
        throw new IllegalArgumentException(App.text(R.string.filters_invalid));
    }

    /** Filter types offered by the "Add filter" menu, in display order. */
    static final String[] TYPES = {ColorCorrection.TYPE, Position.TYPE};

    static int label(String type) { return create(type).label(); }

    static boolean active(List<Filter> filters) {
        for (Filter filter : filters) if (!filter.neutral()) return true;
        return false;
    }

    static boolean hasActivePosition(List<Filter> filters) {
        for (Filter filter : filters) if (filter instanceof Position && !filter.neutral()) return true;
        return false;
    }

    /** Applies the chain in order to a mutable bitmap. */
    static void apply(List<Filter> filters, Bitmap bitmap) {
        for (Filter filter : filters) filter.apply(bitmap);
    }

    static List<Filter> copy(List<Filter> filters) {
        List<Filter> result = new ArrayList<>();
        for (Filter filter : filters) result.add(filter.copy());
        return result;
    }

    static JSONArray toJson(List<Filter> filters) throws JSONException {
        JSONArray array = new JSONArray();
        for (Filter filter : filters) array.put(filter.toJson());
        return array;
    }

    static List<Filter> fromJson(JSONArray array) throws JSONException {
        List<Filter> result = new ArrayList<>();
        if (array == null) return result;
        if (array.length() > MAX_FILTERS) throw new JSONException(App.text(R.string.filters_invalid));
        for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.getJSONObject(i);
            String type = j.optString("type");
            if (ColorCorrection.TYPE.equals(type)) result.add(ColorCorrection.fromJson(j));
            else if (Position.TYPE.equals(type)) result.add(Position.fromJson(j));
            else throw new JSONException(App.text(R.string.filters_invalid));
        }
        return result;
    }

    static String key(List<Filter> filters) {
        try { return toJson(filters).toString(); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }
}
