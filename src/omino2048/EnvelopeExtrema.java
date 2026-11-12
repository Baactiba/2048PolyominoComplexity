package omino2048;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exact local and global extremum finder (minimums and maximums over {@code x in [0, 1]})
 * for a solved piecewise-polynomial {@link EnvelopeResult}.
 *
 * <p>Identifies:
 * <ul>
 *   <li>Domain boundary extrema at {@code x = 0} and {@code x = 1}</li>
 *   <li>Smooth interior extrema inside each segment where {@code P_i'(x) = 0} with strict sign change</li>
 *   <li>Cusp / V-shaped breakpoint extrema where adjacent strategies meet with opposite slope signs</li>
 * </ul>
 */
public final class EnvelopeExtrema {
    private static final MathContext HIGH_PREC = new MathContext(50, RoundingMode.HALF_EVEN);
    private static final BigDecimal GLOBAL_MATCH_TOL = new BigDecimal("1e-18");

    public enum Kind {
        MIN,
        MAX
    }

    public enum Source {
        BOUNDARY_ZERO,
        BOUNDARY_ONE,
        INTERIOR,
        BREAKPOINT
    }

    public record Extremum(
            Kind kind,
            Source source,
            Fraction xLower,
            Fraction xUpper,
            Fraction exactX,
            Fraction exactY,
            double x,
            double y,
            boolean isGlobal) {}

    private record RawExtremum(
            Kind kind,
            Source source,
            Fraction xLower,
            Fraction xUpper,
            Fraction exactX,
            Fraction exactY,
            BigDecimal xDec,
            BigDecimal yDec) {}

    private EnvelopeExtrema() {}

    /**
     * Finds all strict local minimums and maximums across {@code [0, 1]} for {@code envelope},
     * sorted ascending by {@code x}, with {@code isGlobal} flagged on extrema attaining the
     * envelope's extreme y value among the discovered extrema.
     */
    public static List<Extremum> findExtrema(EnvelopeResult envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        List<EnvelopeSegment> segs = envelope.segments();
        if (segs.isEmpty()) {
            return List.of();
        }

        int n = segs.size();
        Polynomial[] derivs = new Polynomial[n];
        @SuppressWarnings("unchecked")
        List<ExactRootFinder.RootBracket>[] interiorRootsBySeg = new List[n];
        int[] startRightSigns = new int[n];
        int[] endLeftSigns = new int[n];

        for (int i = 0; i < n; i++) {
            EnvelopeSegment seg = segs.get(i);
            Polynomial d = seg.polynomial().derivative();
            derivs[i] = d;
            if (d.isZero()) {
                interiorRootsBySeg[i] = List.of();
                startRightSigns[i] = 0;
                endLeftSigns[i] = 0;
                continue;
            }

            List<ExactRootFinder.RootBracket> allRoots = ExactRootFinder.findDistinctRealRootsInInterval(
                    d, seg.startLower(), seg.endUpper());
            List<ExactRootFinder.RootBracket> interior = new ArrayList<>();
            for (ExactRootFinder.RootBracket r : allRoots) {
                if (r.lower().compareTo(seg.startUpper()) > 0 && r.upper().compareTo(seg.endLower()) < 0) {
                    interior.add(r);
                }
            }
            interiorRootsBySeg[i] = interior;

            if (seg.startUpper().compareTo(seg.endLower()) < 0) {
                Fraction firstUpper = interior.isEmpty() ? seg.endLower() : interior.get(0).lower();
                startRightSigns[i] = sampleSign(d, seg.startUpper(), firstUpper);
                Fraction lastLower = interior.isEmpty()
                        ? seg.startUpper()
                        : interior.get(interior.size() - 1).upper();
                endLeftSigns[i] = sampleSign(d, lastLower, seg.endLower());
            } else {
                // Degenerate zero-gap safety fallback
                Fraction mid = midpoint(seg.startLower(), seg.endUpper());
                int s = d.evaluate(mid).signum();
                startRightSigns[i] = s;
                endLeftSigns[i] = s;
            }
        }

        List<RawExtremum> raw = new ArrayList<>();

        // 1. Left domain boundary x = 0
        int slopeAtZeroPlus = derivs[0].compareAtZeroPlusEpsilon(Polynomial.ZERO);
        if (slopeAtZeroPlus > 0) {
            raw.add(makeExactPoint(Kind.MIN, Source.BOUNDARY_ZERO, Fraction.ZERO, segs.get(0).polynomial()));
        } else if (slopeAtZeroPlus < 0) {
            raw.add(makeExactPoint(Kind.MAX, Source.BOUNDARY_ZERO, Fraction.ZERO, segs.get(0).polynomial()));
        }

        // 2. Smooth interior extrema and breakpoint V-minima / A-maxima
        for (int i = 0; i < n; i++) {
            EnvelopeSegment seg = segs.get(i);
            Polynomial poly = seg.polynomial();
            Polynomial d = derivs[i];
            List<ExactRootFinder.RootBracket> interior = interiorRootsBySeg[i];

            if (!interior.isEmpty() && !d.isZero()) {
                BigDecimal[] decCoeffs = null;
                for (int j = 0; j < interior.size(); j++) {
                    ExactRootFinder.RootBracket root = interior.get(j);
                    Fraction prevRight = j == 0 ? seg.startUpper() : interior.get(j - 1).upper();
                    Fraction nextLeft = (j + 1 < interior.size()) ? interior.get(j + 1).lower() : seg.endLower();
                    int sLeft = sampleSign(d, prevRight, root.lower());
                    int sRight = sampleSign(d, root.upper(), nextLeft);
                    Kind kind = classifySlopeChange(sLeft, sRight);
                    if (kind != null) {
                        if (root.isExact()) {
                            raw.add(makeExactPoint(kind, Source.INTERIOR, root.lower(), poly));
                        } else {
                            if (decCoeffs == null) {
                                decCoeffs = toHighPrecCoeffs(poly);
                            }
                            raw.add(makeBracketPoint(kind, Source.INTERIOR, root.lower(), root.upper(), decCoeffs));
                        }
                    }
                }
            }

            if (i + 1 < n) {
                Kind bpKind = classifySlopeChange(endLeftSigns[i], startRightSigns[i + 1]);
                if (bpKind != null) {
                    Fraction bLo = seg.endLower();
                    Fraction bHi = seg.endUpper();
                    if (bLo.equals(bHi)) {
                        raw.add(makeExactPoint(bpKind, Source.BREAKPOINT, bLo, poly));
                    } else {
                        raw.add(makeBracketPoint(bpKind, Source.BREAKPOINT, bLo, bHi, toHighPrecCoeffs(poly)));
                    }
                }
            }
        }

        // 3. Right domain boundary x = 1
        Polynomial lastPoly = segs.get(n - 1).polynomial();
        int slopeAtOneMinus = derivs[n - 1].compareAtOneMinusEpsilon(Polynomial.ZERO);
        if (slopeAtOneMinus > 0) {
            raw.add(makeExactPoint(Kind.MAX, Source.BOUNDARY_ONE, Fraction.ONE, lastPoly));
        } else if (slopeAtOneMinus < 0) {
            raw.add(makeExactPoint(Kind.MIN, Source.BOUNDARY_ONE, Fraction.ONE, lastPoly));
        }

        if (raw.isEmpty()) {
            return List.of();
        }

        raw.sort(Comparator.comparing(RawExtremum::xDec));

        BigDecimal globalMinY = null;
        BigDecimal globalMaxY = null;
        for (RawExtremum r : raw) {
            if (r.kind() == Kind.MIN && (globalMinY == null || r.yDec().compareTo(globalMinY) < 0)) {
                globalMinY = r.yDec();
            }
            if (r.kind() == Kind.MAX && (globalMaxY == null || r.yDec().compareTo(globalMaxY) > 0)) {
                globalMaxY = r.yDec();
            }
        }

        List<Extremum> result = new ArrayList<>(raw.size());
        for (RawExtremum r : raw) {
            boolean isGlobal = false;
            if (r.kind() == Kind.MIN && globalMinY != null) {
                isGlobal = r.yDec().subtract(globalMinY, HIGH_PREC).abs().compareTo(GLOBAL_MATCH_TOL) <= 0;
            } else if (r.kind() == Kind.MAX && globalMaxY != null) {
                isGlobal = r.yDec().subtract(globalMaxY, HIGH_PREC).abs().compareTo(GLOBAL_MATCH_TOL) <= 0;
            }
            result.add(new Extremum(
                    r.kind(),
                    r.source(),
                    r.xLower(),
                    r.xUpper(),
                    r.exactX(),
                    r.exactY(),
                    r.xDec().doubleValue(),
                    r.yDec().doubleValue(),
                    isGlobal));
        }
        return List.copyOf(result);
    }

    private static Kind classifySlopeChange(int slopeLeft, int slopeRight) {
        if (slopeLeft < 0 && slopeRight > 0) {
            return Kind.MIN;
        }
        if (slopeLeft > 0 && slopeRight < 0) {
            return Kind.MAX;
        }
        return null;
    }

    private static int sampleSign(Polynomial deriv, Fraction lo, Fraction hi) {
        Fraction probe = ExactRootFinder.simplestRationalStrictlyBetween(lo, hi);
        if (probe == null) {
            probe = midpoint(lo, hi);
        }
        return deriv.evaluate(probe).signum();
    }

    private static Fraction midpoint(Fraction a, Fraction b) {
        return a.add(b).divide(Fraction.of(2));
    }

    private static RawExtremum makeExactPoint(Kind kind, Source source, Fraction x, Polynomial poly) {
        Fraction y = poly.evaluate(x);
        return new RawExtremum(kind, source, x, x, x, y, fractionToDec(x), fractionToDec(y));
    }

    private static RawExtremum makeBracketPoint(
            Kind kind,
            Source source,
            Fraction xLo,
            Fraction xHi,
            BigDecimal[] coeffsAsc) {
        Fraction mid = midpoint(xLo, xHi);
        BigDecimal xDec = fractionToDec(mid);
        BigDecimal yDec = evalHornerDec(coeffsAsc, xDec);
        return new RawExtremum(kind, source, xLo, xHi, null, null, xDec, yDec);
    }

    private static BigDecimal[] toHighPrecCoeffs(Polynomial poly) {
        List<Fraction> coeffs = poly.getCoefficients();
        BigDecimal[] dec = new BigDecimal[coeffs.size()];
        for (int i = 0; i < coeffs.size(); i++) {
            dec[i] = fractionToDec(coeffs.get(i));
        }
        return dec;
    }

    private static BigDecimal fractionToDec(Fraction f) {
        return new BigDecimal(f.getNumerator()).divide(new BigDecimal(f.getDenominator()), HIGH_PREC);
    }

    private static BigDecimal evalHornerDec(BigDecimal[] coeffsAsc, BigDecimal x) {
        if (coeffsAsc.length == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = coeffsAsc.length - 1; i >= 0; i--) {
            acc = acc.multiply(x, HIGH_PREC).add(coeffsAsc[i], HIGH_PREC);
        }
        return acc;
    }
}
