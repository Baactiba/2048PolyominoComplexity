package omino2048.gui;

import omino2048.EnvelopeExtrema;
import omino2048.EnvelopeResult;
import omino2048.EnvelopeSegment;
import omino2048.Fraction;
import omino2048.Polynomial;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plot-ready representation of an {@link EnvelopeResult}, with distinct high-contrast colors
 * and 50-digit {@link BigDecimal} polynomial coefficients for cancellation-free evaluation.
 */
public final class PlottedEnvelope {
    private static final MathContext HIGH_PREC = new MathContext(50, RoundingMode.HALF_EVEN);

    private static final Color[] BASE_PALETTE = {
            new Color(0x1F, 0x77, 0xB4), // Blue
            new Color(0xD6, 0x27, 0x28), // Crimson Red
            new Color(0x2C, 0xA0, 0x2C), // Forest Green
            new Color(0x94, 0x67, 0xBD), // Purple
            new Color(0xE6, 0x61, 0x00), // Burnt Orange
            new Color(0x00, 0x80, 0x80), // Teal
            new Color(0xC5, 0x1B, 0x8A), // Magenta
            new Color(0x8C, 0x56, 0x4B), // Brown
            new Color(0xB8, 0x86, 0x0B), // Dark Goldenrod
            new Color(0x3F, 0x51, 0xB5), // Indigo
            new Color(0x00, 0x64, 0x00), // Dark Green
            new Color(0x52, 0x52, 0x5B)  // Slate
    };

    public record StrategyCurve(
            int index,
            String label,
            Polynomial polynomial,
            Color color,
            BigDecimal[] coeffsAsc,
            List<EnvelopeSegment> activeSegments) {

        public double evaluateDouble(double x) {
            return evaluateBigDecimal(x).doubleValue();
        }

        public BigDecimal evaluateBigDecimal(double x) {
            if (coeffsAsc.length == 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal bx = BigDecimal.valueOf(x);
            BigDecimal acc = BigDecimal.ZERO;
            for (int i = coeffsAsc.length - 1; i >= 0; i--) {
                acc = acc.multiply(bx, HIGH_PREC).add(coeffsAsc[i], HIGH_PREC);
            }
            return acc;
        }

        public String activeIntervalsSummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < activeSegments.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                EnvelopeSegment seg = activeSegments.get(i);
                sb.append('[').append(seg.formatStart()).append(", ").append(seg.formatEnd()).append(']');
            }
            return sb.toString();
        }
    }

    public record SegmentEntry(
            int index,
            EnvelopeSegment segment,
            StrategyCurve strategy,
            double startX,
            double endX) {}

    private final EnvelopeResult result;
    private final List<StrategyCurve> strategies;
    private final List<SegmentEntry> segmentEntries;
    private final Map<Polynomial, StrategyCurve> byPolynomial;
    private final List<EnvelopeExtrema.Extremum> extrema;

    private PlottedEnvelope(
            EnvelopeResult result,
            List<StrategyCurve> strategies,
            List<SegmentEntry> segmentEntries,
            Map<Polynomial, StrategyCurve> byPolynomial,
            List<EnvelopeExtrema.Extremum> extrema) {
        this.result = result;
        this.strategies = strategies;
        this.segmentEntries = segmentEntries;
        this.byPolynomial = byPolynomial;
        this.extrema = extrema;
    }

    public static PlottedEnvelope from(EnvelopeResult result) {
        List<Polynomial> distinct = result.distinctPolynomials();
        List<EnvelopeSegment> segments = result.segments();

        Map<Polynomial, List<EnvelopeSegment>> segsByPoly = new HashMap<>(distinct.size() * 2);
        for (Polynomial poly : distinct) {
            segsByPoly.put(poly, new ArrayList<>());
        }
        for (EnvelopeSegment seg : segments) {
            segsByPoly.computeIfAbsent(seg.polynomial(), k -> new ArrayList<>()).add(seg);
        }

        List<StrategyCurve> curves = new ArrayList<>(distinct.size());
        Map<Polynomial, StrategyCurve> polyMap = new HashMap<>(distinct.size() * 2);
        for (int i = 0; i < distinct.size(); i++) {
            Polynomial poly = distinct.get(i);
            List<Fraction> fracCoeffs = poly.getCoefficients();
            BigDecimal[] decCoeffs = new BigDecimal[fracCoeffs.size()];
            for (int d = 0; d < fracCoeffs.size(); d++) {
                Fraction c = fracCoeffs.get(d);
                decCoeffs[d] = new BigDecimal(c.getNumerator())
                        .divide(new BigDecimal(c.getDenominator()), HIGH_PREC);
            }
            StrategyCurve curve = new StrategyCurve(
                    i,
                    "S" + (i + 1),
                    poly,
                    colorForIndex(i),
                    decCoeffs,
                    List.copyOf(segsByPoly.getOrDefault(poly, List.of())));
            curves.add(curve);
            polyMap.put(poly, curve);
        }

        List<SegmentEntry> entries = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            EnvelopeSegment seg = segments.get(i);
            StrategyCurve curve = polyMap.get(seg.polynomial());
            entries.add(new SegmentEntry(i, seg, curve, seg.startDouble(), seg.endDouble()));
        }

        return new PlottedEnvelope(
                result,
                List.copyOf(curves),
                List.copyOf(entries),
                polyMap,
                EnvelopeExtrema.findExtrema(result));
    }

    public List<EnvelopeExtrema.Extremum> extrema() {
        return extrema;
    }

    public EnvelopeResult result() {
        return result;
    }

    public List<StrategyCurve> strategies() {
        return strategies;
    }

    public List<SegmentEntry> segments() {
        return segmentEntries;
    }

    public StrategyCurve strategyFor(Polynomial poly) {
        return byPolynomial.get(poly);
    }

    public SegmentEntry segmentAt(double x) {
        if (segmentEntries.isEmpty()) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(1.0, x));
        for (SegmentEntry entry : segmentEntries) {
            if (clamped >= entry.startX() - 1e-15 && clamped <= entry.endX() + 1e-15) {
                return entry;
            }
        }
        return clamped <= 0.5 ? segmentEntries.get(0) : segmentEntries.get(segmentEntries.size() - 1);
    }

    private static Color colorForIndex(int index) {
        if (index < BASE_PALETTE.length) {
            return BASE_PALETTE[index];
        }
        int extra = index - BASE_PALETTE.length;
        float hue = (float) (((extra * 0.6180339887498949) + 0.08) % 1.0);
        // Avoid low-contrast pale yellow near hue 0.14-0.20 on white backgrounds.
        float[] satBands = {0.82f, 0.72f, 0.90f};
        float[] briBands = {0.72f, 0.58f, 0.80f};
        float sat = satBands[extra % satBands.length];
        float bri = briBands[(extra / satBands.length) % briBands.length];
        if (hue > 0.12f && hue < 0.21f && bri > 0.65f) {
            bri = 0.62f;
        }
        return Color.getHSBColor(hue, sat, bri);
    }
}
