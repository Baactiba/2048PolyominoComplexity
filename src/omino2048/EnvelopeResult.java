package omino2048;

import java.util.List;
import java.util.Objects;

/**
 * Piecewise-polynomial upper envelope of the optimal 2048 success probability over {@code x in [0, 1]}.
 *
 * <p>{@code segments} contains the ordered contiguous intervals partitioning {@code [0, 1]} (adjacent
 * identical strategies are merged, and single-point ties that never take the strict lead on a
 * positive-width interval are omitted). {@code distinctPolynomials} contains the deduplicated set of
 * distinct strategy polynomials appearing across {@code segments} (so a strategy that leads, yields
 * to another, and later reclaims the lead counts once in {@code distinctPolynomials}).</p>
 */
public record EnvelopeResult(
        Polyomino polyomino,
        int tileGoal,
        int maxPolynomialDegree,
        List<EnvelopeSegment> segments,
        List<Polynomial> distinctPolynomials,
        int solverCalls) {

    public EnvelopeResult {
        Objects.requireNonNull(polyomino, "polyomino must not be null");
        segments = List.copyOf(segments);
        distinctPolynomials = List.copyOf(distinctPolynomials);
        if (segments.isEmpty() || distinctPolynomials.isEmpty()) {
            throw new IllegalArgumentException("Envelope must contain at least one segment and polynomial");
        }
    }

    /**
     * Evaluates the exact optimal success probability at {@code spawnRate} using the computed envelope.
     */
    public Fraction evaluate(Fraction spawnRate) {
        Objects.requireNonNull(spawnRate, "spawnRate must not be null");
        if (spawnRate.compareTo(Fraction.ZERO) < 0 || spawnRate.compareTo(Fraction.ONE) > 0) {
            throw new IllegalArgumentException("spawnRate must be in [0, 1], got: " + spawnRate);
        }
        Fraction best = null;
        for (Polynomial poly : distinctPolynomials) {
            Fraction val = poly.evaluate(spawnRate);
            if (best == null || val.compareTo(best) > 0) {
                best = val;
            }
        }
        return best;
    }

    /**
     * Returns an optimal envelope {@link Polynomial} at {@code spawnRate}, resolving exact boundary
     * ties at {@code 0} via {@code 0 + epsilon} and at {@code 1} via {@code 1 - epsilon}.
     */
    public Polynomial polynomialAt(Fraction spawnRate) {
        Objects.requireNonNull(spawnRate, "spawnRate must not be null");
        if (spawnRate.isZero()) {
            return segments.get(0).polynomial();
        }
        if (spawnRate.isOne()) {
            return segments.get(segments.size() - 1).polynomial();
        }
        if (spawnRate.compareTo(Fraction.ZERO) < 0 || spawnRate.compareTo(Fraction.ONE) > 0) {
            throw new IllegalArgumentException("spawnRate must be in [0, 1], got: " + spawnRate);
        }
        Polynomial best = null;
        Fraction bestVal = null;
        for (Polynomial poly : distinctPolynomials) {
            Fraction val = poly.evaluate(spawnRate);
            if (bestVal == null || val.compareTo(bestVal) > 0) {
                bestVal = val;
                best = poly;
            }
        }
        return best;
    }
}
