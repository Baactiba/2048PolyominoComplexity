package omino2048;

import java.util.Objects;

/**
 * One maximal contiguous interval of the upper envelope on {@code [0, 1]} where
 * a single {@link Polynomial} achieves the optimal 2048 success probability.
 *
 * <p>Because polynomial crossing points are roots of rational polynomials, rational endpoints
 * (including {@code 0} and {@code 1}) satisfy {@code lower.equals(upper)}, whereas irrational
 * algebraic endpoints are returned as tight exact rational brackets {@code [lower, upper]}.</p>
 */
public record EnvelopeSegment(
        Fraction startLower,
        Fraction startUpper,
        Fraction endLower,
        Fraction endUpper,
        Polynomial polynomial) {

    public EnvelopeSegment {
        Objects.requireNonNull(startLower, "startLower must not be null");
        Objects.requireNonNull(startUpper, "startUpper must not be null");
        Objects.requireNonNull(endLower, "endLower must not be null");
        Objects.requireNonNull(endUpper, "endUpper must not be null");
        Objects.requireNonNull(polynomial, "polynomial must not be null");
        if (startLower.compareTo(Fraction.ZERO) < 0 || endUpper.compareTo(Fraction.ONE) > 0) {
            throw new IllegalArgumentException("Envelope segment endpoints must lie in [0, 1]");
        }
        if (startLower.compareTo(startUpper) > 0
                || startUpper.compareTo(endLower) > 0
                || endLower.compareTo(endUpper) > 0) {
            throw new IllegalArgumentException("Invalid segment endpoint ordering");
        }
    }

    public boolean isExactStart() {
        return startLower.equals(startUpper);
    }

    public boolean isExactEnd() {
        return endLower.equals(endUpper);
    }

    public double startDouble() {
        return isExactStart()
                ? startLower.doubleValue()
                : 0.5 * (startLower.doubleValue() + startUpper.doubleValue());
    }

    public double endDouble() {
        return isExactEnd()
                ? endLower.doubleValue()
                : 0.5 * (endLower.doubleValue() + endUpper.doubleValue());
    }

    public String formatStart() {
        return isExactStart()
                ? startLower.toString()
                : String.format(java.util.Locale.US, "~%.8f", startDouble());
    }

    public String formatEnd() {
        return isExactEnd()
                ? endLower.toString()
                : String.format(java.util.Locale.US, "~%.8f", endDouble());
    }
}
