package tk.glucodata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The live notification's optional gauge ({@link LiveGlucoseNotification}): a bar over
 * the chart's glucose range in whole progress units, in the colours of the glucose
 * ranges, with the current value's position on it. Values and thresholds arrive in the
 * user's unit; nothing here reads settings or Android.
 */
final class LiveGlucoseGauge {
    /** Progress units per mmol/L: tenths, the precision mmol/L values are shown with. */
    static final int UNITS_PER_MMOL = 10;
    static final int UNITS_PER_MGDL = 1;
    /** The bar's span when the chart range cannot be used. */
    static final float FALLBACK_LOW_MMOL = 2.0f;
    static final float FALLBACK_HIGH_MMOL = 22.0f;
    static final float FALLBACK_LOW_MGDL = 40f;
    static final float FALLBACK_HIGH_MGDL = 400f;

    private LiveGlucoseGauge() {
    }

    /** A stretch of the bar in one range's colour, in progress units from the bar's start. */
    static final class Segment {
        final GlucoseRangeColors.Band band;
        final int start;
        final int length;

        Segment(GlucoseRangeColors.Band band, int start, int length) {
            this.band = band;
            this.start = start;
            this.length = length;
        }
    }

    /** The bar: its length, where the value sits on it, the value's range, and the ranges along it. */
    static final class Bar {
        final int max;
        final int progress;
        final GlucoseRangeColors.Band band;
        final List<Segment> segments;

        Bar(int max, int progress, GlucoseRangeColors.Band band, List<Segment> segments) {
            this.max = max;
            this.progress = progress;
            this.band = band;
            this.segments = Collections.unmodifiableList(segments);
        }

        /** How far along the bar {@code units} are, from 0 to 1. */
        float fraction(int units) {
            return Math.max(0f, Math.min(1f, units / (float) max));
        }
    }

    /** {@code glucose} in progress units: tenths of mmol/L, or mg/dL. */
    static int units(float glucose, boolean mmol) {
        return Math.round(glucose * (mmol ? UNITS_PER_MMOL : UNITS_PER_MGDL));
    }

    /**
     * The bar for {@code value} over the chart range, or over the fallback span when
     * the chart range is unset or empty. A value outside the span sits at its end. The
     * ranges are those of {@link GlucoseRangeColors#colorForValue}: very low up to and
     * including the very low threshold, low below the target, in range up to and
     * including its top, high below the very high threshold, very high from it; an
     * unset threshold takes its default. Ranges outside the span are left out. Null
     * without a value.
     */
    static Bar bar(float value, boolean mmol, float chartLow, float chartHigh,
            float targetLow, float targetHigh, float veryLowThreshold, float veryHighThreshold) {
        if (!Float.isFinite(value) || value <= 0f)
            return null;
        int start = units(chartLow, mmol);
        int end = units(chartHigh, mmol);
        if (!Float.isFinite(chartLow) || !Float.isFinite(chartHigh) || chartLow < 0f || end <= start) {
            start = units(mmol ? FALLBACK_LOW_MMOL : FALLBACK_LOW_MGDL, mmol);
            end = units(mmol ? FALLBACK_HIGH_MMOL : FALLBACK_HIGH_MGDL, mmol);
        }
        final int max = end - start;

        final float low = Float.isFinite(targetLow) && targetLow > 0f ? targetLow : GlucoseRangeColors.defaultLow(mmol);
        final float high = Math.max(Float.isFinite(targetHigh) && targetHigh > low
                ? targetHigh : GlucoseRangeColors.defaultHigh(mmol), low + 0.1f);
        final float veryLow = Math.min(Float.isFinite(veryLowThreshold) && veryLowThreshold > 0f
                ? veryLowThreshold : GlucoseRangeColors.defaultVeryLow(mmol), low - 0.1f);
        final float veryHigh = Math.max(Float.isFinite(veryHighThreshold) && veryHighThreshold > 0f
                ? veryHighThreshold : GlucoseRangeColors.defaultVeryHigh(mmol), high + 0.1f);

        // Where each range ends along the bar, very low to very high.
        final int[] ends = {
                position(veryLow, mmol, start, max),
                position(low, mmol, start, max),
                position(high, mmol, start, max),
                position(veryHigh, mmol, start, max),
                max,
        };
        final GlucoseRangeColors.Band[] bands = GlucoseRangeColors.Band.values();
        final List<Segment> segments = new ArrayList<>(bands.length);
        int from = 0;
        for (int i = 0; i < bands.length; i++) {
            final int to = Math.max(from, ends[i]);
            if (to > from)
                segments.add(new Segment(bands[i], from, to - from));
            from = to;
        }

        final GlucoseRangeColors.Band band;
        if (value <= veryLow)
            band = GlucoseRangeColors.Band.VERY_LOW;
        else if (value < low)
            band = GlucoseRangeColors.Band.LOW;
        else if (value >= veryHigh)
            band = GlucoseRangeColors.Band.VERY_HIGH;
        else if (value > high)
            band = GlucoseRangeColors.Band.HIGH;
        else
            band = GlucoseRangeColors.Band.IN_RANGE;
        return new Bar(max, position(value, mmol, start, max), band, segments);
    }

    private static int position(float glucose, boolean mmol, int start, int max) {
        return Math.max(0, Math.min(max, units(glucose, mmol) - start));
    }
}
