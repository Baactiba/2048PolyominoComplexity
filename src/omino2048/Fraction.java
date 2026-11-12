package omino2048;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Exact rational number backed by {@link BigInteger} numerator and denominator.
 * Canonicalized so denominator is strictly positive, gcd(|num|, den) == 1,
 * and zero is always represented as 0/1.
 */
public final class Fraction implements Comparable<Fraction> {
    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE, true);
    public static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE, true);
    public static final Fraction MINUS_ONE = new Fraction(BigInteger.ONE.negate(), BigInteger.ONE, true);

    private final BigInteger numerator;
    private final BigInteger denominator;

    private Fraction(BigInteger numerator, BigInteger denominator, boolean canonical) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    public static Fraction of(long numerator) {
        return of(BigInteger.valueOf(numerator), BigInteger.ONE);
    }

    public static Fraction of(long numerator, long denominator) {
        return of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Fraction of(BigInteger numerator) {
        return of(numerator, BigInteger.ONE);
    }

    public static Fraction of(BigInteger numerator, BigInteger denominator) {
        Objects.requireNonNull(numerator, "numerator must not be null");
        Objects.requireNonNull(denominator, "denominator must not be null");
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Denominator cannot be zero");
        }
        if (numerator.signum() == 0) {
            return ZERO;
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.abs().gcd(denominator);
        if (!gcd.equals(BigInteger.ONE)) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
        if (denominator.equals(BigInteger.ONE)) {
            if (numerator.equals(BigInteger.ONE)) {
                return ONE;
            }
            if (numerator.equals(BigInteger.ONE.negate())) {
                return MINUS_ONE;
            }
        }
        return new Fraction(numerator, denominator, true);
    }

    /**
     * Parses an integer ("3"), fraction ("2/5"), or decimal ("0.4", "1e-3") into an exact Fraction.
     */
    public static Fraction parse(String text) {
        Objects.requireNonNull(text, "text must not be null");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty fraction string");
        }
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx >= 0) {
            if (trimmed.indexOf('/', slashIdx + 1) >= 0) {
                throw new IllegalArgumentException("Invalid fraction string: " + text);
            }
            BigInteger num = new BigInteger(trimmed.substring(0, slashIdx).trim());
            BigInteger den = new BigInteger(trimmed.substring(slashIdx + 1).trim());
            return of(num, den);
        }
        if (trimmed.indexOf('.') >= 0 || trimmed.indexOf('e') >= 0 || trimmed.indexOf('E') >= 0) {
            BigDecimal bd = new BigDecimal(trimmed);
            if (bd.scale() <= 0) {
                return of(bd.toBigIntegerExact(), BigInteger.ONE);
            }
            BigInteger num = bd.unscaledValue();
            BigInteger den = BigInteger.TEN.pow(bd.scale());
            return of(num, den);
        }
        return of(new BigInteger(trimmed), BigInteger.ONE);
    }

    public BigInteger getNumerator() {
        return numerator;
    }

    public BigInteger getDenominator() {
        return denominator;
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    public boolean isOne() {
        return numerator.equals(BigInteger.ONE) && denominator.equals(BigInteger.ONE);
    }

    public int signum() {
        return numerator.signum();
    }

    public Fraction negate() {
        if (isZero()) {
            return ZERO;
        }
        return new Fraction(numerator.negate(), denominator, true);
    }

    public Fraction abs() {
        return numerator.signum() < 0 ? negate() : this;
    }

    public Fraction add(Fraction other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this.isZero()) {
            return other;
        }
        if (other.isZero()) {
            return this;
        }
        if (this.denominator.equals(other.denominator)) {
            return of(this.numerator.add(other.numerator), this.denominator);
        }
        BigInteger num = this.numerator.multiply(other.denominator)
                .add(other.numerator.multiply(this.denominator));
        BigInteger den = this.denominator.multiply(other.denominator);
        return of(num, den);
    }

    public Fraction subtract(Fraction other) {
        Objects.requireNonNull(other, "other must not be null");
        if (other.isZero()) {
            return this;
        }
        if (this.isZero()) {
            return other.negate();
        }
        if (this.denominator.equals(other.denominator)) {
            return of(this.numerator.subtract(other.numerator), this.denominator);
        }
        BigInteger num = this.numerator.multiply(other.denominator)
                .subtract(other.numerator.multiply(this.denominator));
        BigInteger den = this.denominator.multiply(other.denominator);
        return of(num, den);
    }

    public Fraction multiply(Fraction other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this.isZero() || other.isZero()) {
            return ZERO;
        }
        if (this.isOne()) {
            return other;
        }
        if (other.isOne()) {
            return this;
        }
        // Cross-cancel before multiplying to keep intermediate BigIntegers smaller.
        BigInteger g1 = this.numerator.abs().gcd(other.denominator);
        BigInteger g2 = other.numerator.abs().gcd(this.denominator);
        BigInteger num = this.numerator.divide(g1).multiply(other.numerator.divide(g2));
        BigInteger den = this.denominator.divide(g2).multiply(other.denominator.divide(g1));
        return new Fraction(num, den, true);
    }

    public Fraction divide(Fraction other) {
        Objects.requireNonNull(other, "other must not be null");
        if (other.isZero()) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.isZero()) {
            return ZERO;
        }
        if (other.isOne()) {
            return this;
        }
        return multiply(new Fraction(
                other.numerator.signum() < 0 ? other.denominator.negate() : other.denominator,
                other.numerator.abs(),
                true
        ));
    }

    /**
     * Converts to BigDecimal rounded to the requested number of decimal places.
     * Safe even when numerator/denominator exceed Double.MAX_VALUE.
     */
    public BigDecimal toBigDecimal(int scale) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP);
    }

    /**
     * Converts to double via BigDecimal so large numerators/denominators do not overflow to Infinity/NaN.
     */
    public double doubleValue() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL128).doubleValue();
    }

    @Override
    public int compareTo(Fraction other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this == other) {
            return 0;
        }
        if (this.denominator.equals(other.denominator)) {
            return this.numerator.compareTo(other.numerator);
        }
        return this.numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(this.denominator));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Fraction other)) {
            return false;
        }
        return numerator.equals(other.numerator) && denominator.equals(other.denominator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numerator, denominator);
    }

    @Override
    public String toString() {
        if (denominator.equals(BigInteger.ONE)) {
            return numerator.toString();
        }
        return numerator + "/" + denominator;
    }
}
