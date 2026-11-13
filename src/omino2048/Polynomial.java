package omino2048;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Exact univariate polynomial P(x) = sum_{i=0..d} c_i x^i with {@link Fraction} coefficients.
 * Stored canonically with trailing zero coefficients stripped (the zero polynomial has an empty list).
 */
public final class Polynomial {
    public static final Polynomial ZERO = new Polynomial(List.of());
    public static final Polynomial ONE = new Polynomial(List.of(Fraction.ONE));
    public static final Polynomial X = new Polynomial(List.of(Fraction.ZERO, Fraction.ONE));
    public static final Polynomial ONE_MINUS_X = new Polynomial(List.of(Fraction.ONE, Fraction.MINUS_ONE));

    private final List<Fraction> coefficients;
    private transient volatile Polynomial shiftedOneMinusX;

    private Polynomial(List<Fraction> canonicalCoefficients) {
        this.coefficients = canonicalCoefficients;
    }

    public static Polynomial constant(Fraction c) {
        Objects.requireNonNull(c, "constant must not be null");
        if (c.isZero()) {
            return ZERO;
        }
        if (c.isOne()) {
            return ONE;
        }
        return new Polynomial(List.of(c));
    }

    public static Polynomial of(Fraction... coefficients) {
        Objects.requireNonNull(coefficients, "coefficients must not be null");
        return of(Arrays.asList(coefficients));
    }

    public static Polynomial of(List<Fraction> coefficients) {
        Objects.requireNonNull(coefficients, "coefficients must not be null");
        int lastNonZero = coefficients.size() - 1;
        for (Fraction c : coefficients) {
            Objects.requireNonNull(c, "coefficient must not be null");
        }
        while (lastNonZero >= 0 && coefficients.get(lastNonZero).isZero()) {
            lastNonZero--;
        }
        if (lastNonZero < 0) {
            return ZERO;
        }
        if (lastNonZero == 0 && coefficients.get(0).isOne()) {
            return ONE;
        }
        List<Fraction> trimmed = new ArrayList<>(lastNonZero + 1);
        for (int i = 0; i <= lastNonZero; i++) {
            trimmed.add(coefficients.get(i));
        }
        return new Polynomial(Collections.unmodifiableList(trimmed));
    }

    /**
     * Returns the canonical unmodifiable coefficient list where index i is the x^i coefficient.
     */
    public List<Fraction> getCoefficients() {
        return coefficients;
    }

    public Fraction coefficient(int degree) {
        if (degree < 0) {
            throw new IllegalArgumentException("degree must be non-negative: " + degree);
        }
        return degree < coefficients.size() ? coefficients.get(degree) : Fraction.ZERO;
    }

    /**
     * Returns the degree of the polynomial, or -1 for the zero polynomial.
     */
    public int degree() {
        return coefficients.size() - 1;
    }

    public boolean isZero() {
        return coefficients.isEmpty();
    }

    public boolean isOne() {
        return coefficients.size() == 1 && coefficients.get(0).isOne();
    }

    public Polynomial negate() {
        if (isZero()) {
            return ZERO;
        }
        List<Fraction> negated = new ArrayList<>(coefficients.size());
        for (Fraction c : coefficients) {
            negated.add(c.negate());
        }
        return new Polynomial(Collections.unmodifiableList(negated));
    }

    public Polynomial add(Polynomial other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this.isZero()) {
            return other;
        }
        if (other.isZero()) {
            return this;
        }
        int maxLen = Math.max(this.coefficients.size(), other.coefficients.size());
        List<Fraction> sum = new ArrayList<>(maxLen);
        for (int i = 0; i < maxLen; i++) {
            sum.add(this.coefficient(i).add(other.coefficient(i)));
        }
        return of(sum);
    }

    public Polynomial subtract(Polynomial other) {
        Objects.requireNonNull(other, "other must not be null");
        if (other.isZero()) {
            return this;
        }
        if (this.isZero()) {
            return other.negate();
        }
        int maxLen = Math.max(this.coefficients.size(), other.coefficients.size());
        List<Fraction> diff = new ArrayList<>(maxLen);
        for (int i = 0; i < maxLen; i++) {
            diff.add(this.coefficient(i).subtract(other.coefficient(i)));
        }
        return of(diff);
    }

    public Polynomial multiply(Fraction scalar) {
        Objects.requireNonNull(scalar, "scalar must not be null");
        if (isZero() || scalar.isZero()) {
            return ZERO;
        }
        if (scalar.isOne()) {
            return this;
        }
        List<Fraction> scaled = new ArrayList<>(coefficients.size());
        for (Fraction c : coefficients) {
            scaled.add(c.multiply(scalar));
        }
        return new Polynomial(Collections.unmodifiableList(scaled));
    }

    public Polynomial multiply(Polynomial other) {
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
        int resultLen = this.coefficients.size() + other.coefficients.size() - 1;
        Fraction[] prod = new Fraction[resultLen];
        Arrays.fill(prod, Fraction.ZERO);
        for (int i = 0; i < this.coefficients.size(); i++) {
            Fraction a = this.coefficients.get(i);
            if (a.isZero()) {
                continue;
            }
            for (int j = 0; j < other.coefficients.size(); j++) {
                Fraction b = other.coefficients.get(j);
                if (!b.isZero()) {
                    prod[i + j] = prod[i + j].add(a.multiply(b));
                }
            }
        }
        return of(Arrays.asList(prod));
    }

    /**
     * Evaluates P(x) in floating point using Horner's method.
     */
    public double evaluate(double x) {
        if (isZero()) {
            return 0.0;
        }
        double result = 0.0;
        for (int i = coefficients.size() - 1; i >= 0; i--) {
            result = result * x + coefficients.get(i).doubleValue();
        }
        return result;
    }

    /**
     * Evaluates P(x) exactly using Horner's method.
     */
    public Fraction evaluate(Fraction x) {
        Objects.requireNonNull(x, "x must not be null");
        if (isZero()) {
            return Fraction.ZERO;
        }
        if (x.isZero()) {
            return coefficients.get(0);
        }
        Fraction result = Fraction.ZERO;
        for (int i = coefficients.size() - 1; i >= 0; i--) {
            result = result.multiply(x).add(coefficients.get(i));
        }
        return result;
    }

    /**
     * Returns the exact formal derivative P'(x).
     */
    public Polynomial derivative() {
        if (coefficients.size() <= 1) {
            return ZERO;
        }
        List<Fraction> deriv = new ArrayList<>(coefficients.size() - 1);
        for (int i = 1; i < coefficients.size(); i++) {
            deriv.add(coefficients.get(i).multiply(Fraction.of(i)));
        }
        return of(deriv);
    }

    /**
     * Computes P(inner(t)) using Horner's method.
     */
    public Polynomial compose(Polynomial inner) {
        Objects.requireNonNull(inner, "inner must not be null");
        if (isZero()) {
            return ZERO;
        }
        Polynomial result = ZERO;
        for (int i = coefficients.size() - 1; i >= 0; i--) {
            result = result.multiply(inner).add(constant(coefficients.get(i)));
        }
        return result;
    }

    /**
     * Compares P(0 + epsilon) vs other(0 + epsilon) for infinitesimal epsilon > 0.
     * Equivalently compares coefficients lexicographically from degree 0 upwards.
     */
    public int compareAtZeroPlusEpsilon(Polynomial other) {
        Objects.requireNonNull(other, "other must not be null");
        int maxLen = Math.max(this.coefficients.size(), other.coefficients.size());
        for (int i = 0; i < maxLen; i++) {
            int cmp = this.coefficient(i).compareTo(other.coefficient(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Compares P(1 - epsilon) vs other(1 - epsilon) for infinitesimal epsilon > 0.
     * Equivalently compares P(1 - t) vs other(1 - t) at t = 0 + epsilon.
     */
    public int compareAtOneMinusEpsilon(Polynomial other) {
        Objects.requireNonNull(other, "other must not be null");
        return this.asOneMinusT().compareAtZeroPlusEpsilon(other.asOneMinusT());
    }

    private Polynomial asOneMinusT() {
        Polynomial cached = shiftedOneMinusX;
        if (cached == null) {
            cached = compose(ONE_MINUS_X);
            shiftedOneMinusX = cached;
        }
        return cached;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Polynomial other)) {
            return false;
        }
        return coefficients.equals(other.coefficients);
    }

    @Override
    public int hashCode() {
        return coefficients.hashCode();
    }

    @Override
    public String toString() {
        if (isZero()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        for (int deg = coefficients.size() - 1; deg >= 0; deg--) {
            Fraction c = coefficients.get(deg);
            if (c.isZero()) {
                continue;
            }
            boolean negative = c.signum() < 0;
            Fraction absC = c.abs();
            if (sb.length() == 0) {
                if (negative) {
                    sb.append('-');
                }
            } else {
                sb.append(negative ? " - " : " + ");
            }
            if (deg == 0) {
                sb.append(absC);
            } else {
                if (!absC.isOne()) {
                    if (absC.getDenominator().equals(BigInteger.ONE)) {
                        sb.append(absC);
                    } else {
                        sb.append('(').append(absC).append(')');
                    }
                }
                sb.append('x');
                if (deg > 1) {
                    sb.append('^').append(deg);
                }
            }
        }
        return sb.toString();
    }
}
