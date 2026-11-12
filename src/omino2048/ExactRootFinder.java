package omino2048;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Exact real-root isolator over {@code [0, 1]} (and arbitrary sub-intervals {@code [lo, hi]})
 * for rational polynomials using certified Bernstein interval sign screening, modular square-free
 * certification, Vincent-Collins-Akritas (Descartes) isolation, exact dyadic bisection, and
 * Stern-Brocot rational root recovery.
 */
final class ExactRootFinder {
    private static final int RATIONAL_CHECK_BITS = 42; // width <= 2^-42 (~2.2e-13)
    private static final int DEFAULT_TARGET_BITS = 64; // width <= 2^-64 (~5.4e-20)
    private static final int DEFAULT_MAX_DEN_BITS = 35;
    private static final long MOD_PRIME_1 = 2_147_483_647L; // 2^31 - 1
    private static final long MOD_PRIME_2 = 2_147_483_629L;

    record RootBracket(Fraction lower, Fraction upper) {
        boolean isExact() {
            return lower.equals(upper);
        }

        Fraction midpoint() {
            return lower.equals(upper)
                    ? lower
                    : lower.add(upper).divide(Fraction.of(2));
        }
    }

    private ExactRootFinder() {}

    /**
     * Conservative fast sign certificate of {@code poly} over exact {@code [lo, hi] subset [0, 1]}.
     * Returns {@code +1} if strictly {@code > 0} on all of {@code [lo, hi]}, {@code -1} if strictly
     * {@code < 0} on all of {@code [lo, hi]}, or {@code 0} if uncertain / roots may exist.
     */
    static int fastIntervalSign(Polynomial poly, Fraction lo, Fraction hi) {
        if (poly == null || poly.isZero()) {
            return 0;
        }
        int d = poly.degree();
        if (d == 0) {
            return poly.coefficient(0).signum();
        }
        if (lo.compareTo(hi) > 0) {
            return 0;
        }

        double[] qMid = new double[d + 1];
        double[] qRad = new double[d + 1];
        for (int i = 0; i <= d; i++) {
            double v = poly.coefficient(i).doubleValue();
            if (!Double.isFinite(v)) {
                return 0;
            }
            qMid[i] = v;
            qRad[i] = Math.abs(v) * 2.220446049250313e-16 + Double.MIN_NORMAL;
        }

        double a = lo.doubleValue();
        double b = hi.doubleValue();
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return 0;
        }
        double aRad = Math.ulp(a) + Double.MIN_NORMAL;
        double h = Math.max(0.0, b - a);
        double hRad = Math.ulp(a) + Math.ulp(b) + Math.ulp(h) + Double.MIN_NORMAL;

        // Interval Horner-Taylor shift: q(a + y) = sum q_i y^i where a in [a +/- aRad]
        double absA = Math.abs(a);
        for (int k = 0; k < d; k++) {
            for (int i = d - 1; i >= k; i--) {
                double mNext = qMid[i + 1];
                double rNext = qRad[i + 1];
                double pMid = a * mNext;
                double pRad = absA * rNext + aRad * Math.abs(mNext) + aRad * rNext
                        + Math.ulp(pMid) + Double.MIN_NORMAL;
                double sMid = qMid[i] + pMid;
                double sRad = qRad[i] + pRad + Math.ulp(sMid) + Double.MIN_NORMAL;
                if (!Double.isFinite(sMid) || !Double.isFinite(sRad)) {
                    return 0;
                }
                qMid[i] = sMid;
                qRad[i] = sRad;
            }
        }

        // Scale coefficients by h^i (h in [h +/- hRad]) to obtain polynomial on u in [0, 1]
        double hpMid = 1.0;
        double hpRad = 0.0;
        for (int i = 0; i <= d; i++) {
            double cMid = qMid[i];
            double cRad = qRad[i];
            double m = cMid * hpMid;
            double r = Math.abs(cMid) * hpRad + cRad * Math.abs(hpMid) + cRad * hpRad
                    + Math.ulp(m) + Double.MIN_NORMAL;
            if (!Double.isFinite(m) || !Double.isFinite(r)) {
                return 0;
            }
            qMid[i] = m;
            qRad[i] = r;

            double nhMid = hpMid * h;
            double nhRad = Math.abs(hpMid) * hRad + hpRad * h + hpRad * hRad
                    + Math.ulp(nhMid) + Double.MIN_NORMAL;
            if (!Double.isFinite(nhMid) || !Double.isFinite(nhRad)) {
                return 0;
            }
            hpMid = nhMid;
            hpRad = nhRad;
        }

        // Convert to Bernstein basis on [0, 1]: B_j = sum_{i=0..j} q_i * C(j, i) / C(d, i)
        // Since 0 <= C(j, i) / C(d, i) <= 1, multipliers are exact-bounded in [0, 1] with no sign blowup.
        boolean allPos = true;
        boolean allNeg = true;
        for (int j = 0; j <= d; j++) {
            double bMid = 0.0;
            double bRad = 0.0;
            double ratioMid = 1.0;
            double ratioRad = 0.0;
            for (int i = 0; i <= j; i++) {
                double m = qMid[i] * ratioMid;
                double r = Math.abs(qMid[i]) * ratioRad + qRad[i] * Math.abs(ratioMid)
                        + qRad[i] * ratioRad + Math.ulp(m) + Double.MIN_NORMAL;
                double nm = bMid + m;
                double nr = bRad + r + Math.ulp(nm) + Double.MIN_NORMAL;
                if (!Double.isFinite(nm) || !Double.isFinite(nr)) {
                    return 0;
                }
                bMid = nm;
                bRad = nr;
                if (i < j) {
                    double factor = (double) (j - i) / (double) (d - i);
                    double fRad = Math.ulp(factor) + Double.MIN_NORMAL;
                    double nRM = ratioMid * factor;
                    double nRR = Math.abs(ratioMid) * fRad + ratioRad * factor
                            + ratioRad * fRad + Math.ulp(nRM) + Double.MIN_NORMAL;
                    ratioMid = nRM;
                    ratioRad = nRR;
                }
            }
            if (bMid - bRad <= 0.0) {
                allPos = false;
            }
            if (bMid + bRad >= 0.0) {
                allNeg = false;
            }
            if (!allPos && !allNeg) {
                return 0;
            }
        }
        return allPos ? 1 : (allNeg ? -1 : 0);
    }

    /**
     * Returns all distinct real roots of {@code poly} in {@code [0, 1]}, sorted ascending.
     * Exact rational roots (including boundaries and interior rationals with modest denominators)
     * are collapsed to {@code lower == upper}; irrational roots are bracketed to width <= 2^-64.
     */
    static List<RootBracket> findDistinctRealRootsInUnitInterval(Polynomial poly) {
        return findDistinctRealRootsInInterval(poly, Fraction.ZERO, Fraction.ONE);
    }

    /**
     * Returns all distinct real roots of {@code poly} in {@code [lo, hi] subset [0, 1]}, sorted ascending.
     */
    static List<RootBracket> findDistinctRealRootsInInterval(Polynomial poly, Fraction lo, Fraction hi) {
        checkCancelled();
        if (poly == null || poly.isZero() || poly.degree() <= 0 || lo.compareTo(hi) > 0) {
            return List.of();
        }
        if (lo.equals(hi)) {
            return poly.evaluate(lo).isZero() ? List.of(new RootBracket(lo, lo)) : List.of();
        }
        if (poly.degree() == 1) {
            Fraction root = poly.coefficient(0).negate().divide(poly.coefficient(1));
            if (root.compareTo(lo) >= 0 && root.compareTo(hi) <= 0) {
                return List.of(new RootBracket(root, root));
            }
            return List.of();
        }
        if (fastIntervalSign(poly, lo, hi) != 0) {
            return List.of();
        }

        BigInteger[] primitive = toPrimitiveIntPoly(poly);
        List<RootBracket> roots = new ArrayList<>();

        // Handle x = 0 cleanly.
        int zeroMult = 0;
        while (zeroMult < primitive.length && primitive[zeroMult].signum() == 0) {
            zeroMult++;
        }
        if (zeroMult > 0) {
            if (lo.isZero()) {
                roots.add(new RootBracket(Fraction.ZERO, Fraction.ZERO));
            }
            primitive = divideByPositiveContent(Arrays.copyOfRange(primitive, zeroMult, primitive.length));
        }

        if (primitive.length <= 1) {
            return roots;
        }

        // Factor out x = 1 cleanly if present.
        boolean hadOne = false;
        while (evalSign(primitive, BigInteger.ONE, BigInteger.ONE) == 0) {
            hadOne = true;
            primitive = divideByOneMinusX(primitive);
        }
        if (hadOne && hi.equals(Fraction.ONE)) {
            roots.add(new RootBracket(Fraction.ONE, Fraction.ONE));
        }

        if (primitive.length <= 1) {
            roots.sort(Comparator.comparing(RootBracket::lower));
            return roots;
        }

        BigInteger[] sqFree = isGuaranteedSquareFreeModPrime(primitive, MOD_PRIME_1)
                || isGuaranteedSquareFreeModPrime(primitive, MOD_PRIME_2)
                ? primitive
                : makeSquareFree(primitive);

        if (sqFree.length <= 1) {
            roots.sort(Comparator.comparing(RootBracket::lower));
            return roots;
        }

        if (sqFree.length == 2) {
            Fraction root = Fraction.of(sqFree[0].negate(), sqFree[1]);
            if (root.compareTo(lo) >= 0 && root.compareTo(hi) <= 0) {
                roots.add(new RootBracket(root, root));
            }
            roots.sort(Comparator.comparing(RootBracket::lower));
            return roots;
        }

        // Invariant: sqFree(0) != 0, sqFree(1) != 0, and sqFree is primitive and square-free.
        vcaIsolateOpen(sqFree, BigInteger.ZERO, 0, lo, hi, roots);
        roots.sort(Comparator.comparing(RootBracket::lower));
        return roots;
    }

    /**
     * Vincent-Collins-Akritas root isolation over u in (0, 1), mapped to x = (numLo + u) / 2^shift.
     * Precondition: cur(0) != 0, cur(1) != 0, and cur is primitive & square-free.
     */
    private static void vcaIsolateOpen(
            BigInteger[] cur,
            BigInteger numLo,
            int shift,
            Fraction filterLo,
            Fraction filterHi,
            List<RootBracket> out) {
        checkCancelled();
        int d = cur.length - 1;
        if (d <= 0) {
            return;
        }

        // Prune dyadic interval [numLo/2^shift, (numLo+1)/2^shift] disjoint from [filterLo, filterHi].
        if (!dyadicOverlaps(numLo, shift, filterLo, filterHi)) {
            return;
        }

        // Descartes sign variations on Q(t) = (1 + t)^d * cur(1 / (1 + t)) = taylorShift(reverse(cur), +1).
        int variations = descartesVariationsOnUnitOpen(cur);
        if (variations == 0) {
            return;
        }

        if (variations == 1) {
            int sLo = cur[0].signum();
            RootBracket bracket = refineSingleUnitRoot(cur, sLo, numLo, shift);
            if (bracket.upper().compareTo(filterLo) >= 0 && bracket.lower().compareTo(filterHi) <= 0) {
                out.add(bracket);
            }
            return;
        }

        // Subdivide cur(u) into left(v) = 2^d cur(v/2) and right(v) = left(v + 1).
        BigInteger[] left = new BigInteger[d + 1];
        for (int i = 0; i <= d; i++) {
            left[i] = cur[i].shiftLeft(d - i);
        }
        BigInteger[] right = left.clone();
        for (int k = 0; k < d; k++) {
            for (int i = d - 1; i >= k; i--) {
                right[i] = right[i].add(right[i + 1]);
            }
        }

        int nextShift = shift + 1;
        BigInteger nextLeftNum = numLo.shiftLeft(1);
        BigInteger midNum = nextLeftNum.add(BigInteger.ONE);

        // Because cur is square-free and left(1) == right(0) == right[0], midpoint root has multiplicity 1.
        if (right[0].signum() == 0) {
            Fraction exactMid = Fraction.of(midNum, BigInteger.ONE.shiftLeft(nextShift));
            if (exactMid.compareTo(filterLo) >= 0 && exactMid.compareTo(filterHi) <= 0) {
                out.add(new RootBracket(exactMid, exactMid));
            }
            left = divideByOneMinusX(left);
            right = divideByPositiveContent(Arrays.copyOfRange(right, 1, right.length));
        } else {
            left = divideByPositiveContent(left);
            right = divideByPositiveContent(right);
        }

        vcaIsolateOpen(left, nextLeftNum, nextShift, filterLo, filterHi, out);
        vcaIsolateOpen(right, midNum, nextShift, filterLo, filterHi, out);
    }

    private static boolean dyadicOverlaps(BigInteger numLo, int shift, Fraction filterLo, Fraction filterHi) {
        if (shift == 0 && numLo.signum() == 0) {
            return true;
        }
        // Check (numLo + 1)/2^shift < filterLo <=> (numLo + 1)*denLo < numLoFilter*2^shift
        if (!filterLo.isZero()) {
            BigInteger numHi = numLo.add(BigInteger.ONE);
            if (numHi.multiply(filterLo.getDenominator()).compareTo(filterLo.getNumerator().shiftLeft(shift)) < 0) {
                return false;
            }
        }
        // Check numLo/2^shift > filterHi <=> numLo*denHi > numHiFilter*2^shift
        if (!filterHi.equals(Fraction.ONE)) {
            if (numLo.multiply(filterHi.getDenominator()).compareTo(filterHi.getNumerator().shiftLeft(shift)) > 0) {
                return false;
            }
        }
        return true;
    }

    private static int descartesVariationsOnUnitOpen(BigInteger[] cur) {
        int d = cur.length - 1;
        BigInteger[] q = new BigInteger[d + 1];
        for (int i = 0; i <= d; i++) {
            q[i] = cur[d - i];
        }
        for (int k = 0; k < d; k++) {
            for (int i = d - 1; i >= k; i--) {
                q[i] = q[i].add(q[i + 1]);
            }
        }
        int vars = 0;
        int prev = 0;
        for (int i = 0; i <= d; i++) {
            int s = q[i].signum();
            if (s != 0) {
                if (prev != 0 && s != prev) {
                    vars++;
                }
                prev = s;
            }
        }
        return vars;
    }

    private static RootBracket refineSingleUnitRoot(BigInteger[] cur, int signLo, BigInteger numLo, int shift) {
        BigInteger aNum = BigInteger.ZERO;
        BigInteger bNum = BigInteger.ONE;
        BigInteger den = BigInteger.ONE;
        boolean checkedRational = false;
        int steps = Math.max(1, DEFAULT_TARGET_BITS - shift);

        for (int iter = 1; iter <= steps; iter++) {
            den = den.shiftLeft(1);
            aNum = aNum.shiftLeft(1);
            bNum = bNum.shiftLeft(1);
            BigInteger midNum = aNum.add(BigInteger.ONE);
            int sMid = evalSign(cur, midNum, den);
            if (sMid == 0) {
                Fraction exact = Fraction.of(numLo.multiply(den).add(midNum), den.shiftLeft(shift));
                return new RootBracket(exact, exact);
            }
            if (sMid == signLo) {
                aNum = midNum;
            } else {
                bNum = midNum;
            }

            if (!checkedRational && shift + iter >= RATIONAL_CHECK_BITS) {
                checkedRational = true;
                Fraction curLo = Fraction.of(aNum, den);
                Fraction curHi = Fraction.of(bNum, den);
                // Map global interval for Stern-Brocot recovery of small global rationals
                Fraction globalLo = Fraction.of(numLo.multiply(den).add(aNum), den.shiftLeft(shift));
                Fraction globalHi = Fraction.of(numLo.multiply(den).add(bNum), den.shiftLeft(shift));
                Fraction simplest = simplestRationalStrictlyBetween(globalLo, globalHi);
                if (simplest != null) {
                    // Convert global x to local u = 2^shift * x - numLo
                    Fraction localU = simplest.multiply(Fraction.of(BigInteger.ONE.shiftLeft(shift)))
                            .subtract(Fraction.of(numLo));
                    if (localU.compareTo(curLo) > 0 && localU.compareTo(curHi) < 0
                            && evalSign(cur, localU.getNumerator(), localU.getDenominator()) == 0) {
                        return new RootBracket(simplest, simplest);
                    }
                }
            }
        }

        Fraction finalLo = Fraction.of(numLo.multiply(den).add(aNum), den.shiftLeft(shift));
        Fraction finalHi = Fraction.of(numLo.multiply(den).add(bNum), den.shiftLeft(shift));
        Fraction simplest = simplestRationalStrictlyBetween(finalLo, finalHi);
        if (simplest != null) {
            Fraction localU = simplest.multiply(Fraction.of(BigInteger.ONE.shiftLeft(shift)))
                    .subtract(Fraction.of(numLo));
            if (evalSign(cur, localU.getNumerator(), localU.getDenominator()) == 0) {
                return new RootBracket(simplest, simplest);
            }
        }
        return new RootBracket(finalLo, finalHi);
    }

    /**
     * Finds the simplest rational strictly inside {@code (a, b)} using accelerated Stern-Brocot search
     * over exact {@link Fraction} comparisons (default max denominator bit-length 35).
     */
    static Fraction simplestRationalStrictlyBetween(Fraction a, Fraction b) {
        return simplestRationalStrictlyBetween(a, b, DEFAULT_MAX_DEN_BITS);
    }

    static Fraction simplestRationalStrictlyBetween(Fraction a, Fraction b, int maxDenBits) {
        if (a.compareTo(b) >= 0) {
            return null;
        }
        BigInteger p0 = BigInteger.ZERO;
        BigInteger q0 = BigInteger.ONE;
        BigInteger p1 = BigInteger.ONE;
        BigInteger q1 = BigInteger.ONE;

        if (a.signum() < 0 || b.compareTo(Fraction.ONE) > 0) {
            return a.add(b).divide(Fraction.of(2));
        }

        BigInteger aNum = a.getNumerator();
        BigInteger aDen = a.getDenominator();
        BigInteger bNum = b.getNumerator();
        BigInteger bDen = b.getDenominator();

        for (int iter = 0; iter < 256; iter++) {
            BigInteger pm = p0.add(p1);
            BigInteger qm = q0.add(q1);
            if (qm.bitLength() > maxDenBits) {
                return null;
            }
            int cmpA = pm.multiply(aDen).compareTo(aNum.multiply(qm));
            if (cmpA <= 0) {
                BigInteger numK = aNum.multiply(q0).subtract(p0.multiply(aDen));
                BigInteger denK = p1.multiply(aDen).subtract(aNum.multiply(q1));
                BigInteger k = numK.divide(denK);
                if (k.signum() < 1) {
                    k = BigInteger.ONE;
                }
                p0 = p0.add(k.multiply(p1));
                q0 = q0.add(k.multiply(q1));
                continue;
            }
            int cmpB = pm.multiply(bDen).compareTo(bNum.multiply(qm));
            if (cmpB >= 0) {
                BigInteger numK = p1.multiply(bDen).subtract(bNum.multiply(q1));
                BigInteger denK = bNum.multiply(q0).subtract(p0.multiply(bDen));
                BigInteger k = numK.divide(denK);
                if (k.signum() < 1) {
                    k = BigInteger.ONE;
                }
                p1 = p1.add(k.multiply(p0));
                q1 = q1.add(k.multiply(q0));
                continue;
            }
            return Fraction.of(pm, qm);
        }
        return null;
    }

    private static boolean isGuaranteedSquareFreeModPrime(BigInteger[] prim, long prime) {
        int d = prim.length - 1;
        if (d <= 1) {
            return true;
        }
        BigInteger modBi = BigInteger.valueOf(prime);
        long lc = positiveMod(prim[d], modBi, prime);
        if (lc == 0L) {
            return false;
        }
        long[] a = new long[d + 1];
        long[] b = new long[d];
        for (int i = 0; i <= d; i++) {
            a[i] = positiveMod(prim[i], modBi, prime);
        }
        for (int i = 1; i <= d; i++) {
            b[i - 1] = (a[i] * i) % prime;
        }
        int degA = d;
        int degB = d - 1;
        while (degB >= 0 && b[degB] == 0L) {
            degB--;
        }
        if (degB < 0) {
            return false;
        }
        while (degB > 0) {
            long invLcB = modPow(b[degB], prime - 2L, prime);
            while (degA >= degB) {
                if (a[degA] != 0L) {
                    long factor = (a[degA] * invLcB) % prime;
                    int shift = degA - degB;
                    for (int j = 0; j <= degB; j++) {
                        long sub = (factor * b[j]) % prime;
                        long val = a[shift + j] - sub;
                        a[shift + j] = val < 0L ? val + prime : val;
                    }
                }
                degA--;
            }
            while (degA >= 0 && a[degA] == 0L) {
                degA--;
            }
            if (degA < 0) {
                return false;
            }
            long[] tmp = a;
            a = b;
            b = tmp;
            int td = degA;
            degA = degB;
            degB = td;
        }
        return true;
    }

    private static long positiveMod(BigInteger v, BigInteger modBi, long prime) {
        long r = v.remainder(modBi).longValue();
        return r < 0L ? r + prime : r;
    }

    private static long modPow(long base, long exp, long mod) {
        long res = 1L;
        base %= mod;
        while (exp > 0L) {
            if ((exp & 1L) != 0L) {
                res = (res * base) % mod;
            }
            base = (base * base) % mod;
            exp >>= 1;
        }
        return res;
    }

    private static BigInteger[] toPrimitiveIntPoly(Polynomial poly) {
        int deg = poly.degree();
        BigInteger lcm = BigInteger.ONE;
        for (int i = 0; i <= deg; i++) {
            BigInteger den = poly.coefficient(i).getDenominator();
            if (!den.equals(BigInteger.ONE)) {
                lcm = lcm.multiply(den.divide(lcm.gcd(den)));
            }
        }
        BigInteger[] coeffs = new BigInteger[deg + 1];
        for (int i = 0; i <= deg; i++) {
            Fraction c = poly.coefficient(i);
            coeffs[i] = c.getNumerator().multiply(lcm.divide(c.getDenominator()));
        }
        return divideByPositiveContent(trim(coeffs));
    }

    /**
     * Exact synthetic division of primitive A(x) where A(1) == 0 by (x - 1) (up to sign -> returns primitive quotient).
     */
    private static BigInteger[] divideByOneMinusX(BigInteger[] p) {
        int d = p.length - 1;
        BigInteger[] q = new BigInteger[d];
        BigInteger acc = BigInteger.ZERO;
        for (int i = d; i >= 1; i--) {
            acc = acc.add(p[i]);
            q[i - 1] = acc;
        }
        return divideByPositiveContent(q);
    }

    private static BigInteger[] makeSquareFree(BigInteger[] p) {
        List<BigInteger[]> chain = buildSturmChain(p);
        BigInteger[] g = chain.get(chain.size() - 1);
        if (g.length <= 1) {
            return p;
        }
        return exactDividePoly(p, g);
    }

    private static List<BigInteger[]> buildSturmChain(BigInteger[] p) {
        List<BigInteger[]> chain = new ArrayList<>(p.length);
        chain.add(p);
        BigInteger[] deriv = derivative(p);
        chain.add(deriv);

        while (chain.get(chain.size() - 1).length > 1) {
            checkCancelled();
            BigInteger[] prev2 = chain.get(chain.size() - 2);
            BigInteger[] prev1 = chain.get(chain.size() - 1);
            BigInteger[] rem = positiveScaledNegatedRemainder(prev2, prev1);
            if (rem.length == 0) {
                break;
            }
            chain.add(rem);
        }
        return chain;
    }

    private static BigInteger[] derivative(BigInteger[] p) {
        if (p.length <= 1) {
            return new BigInteger[0];
        }
        BigInteger[] d = new BigInteger[p.length - 1];
        for (int i = 1; i < p.length; i++) {
            d[i - 1] = p[i].multiply(BigInteger.valueOf(i));
        }
        return divideByPositiveContent(trim(d));
    }

    private static BigInteger[] positiveScaledNegatedRemainder(BigInteger[] a, BigInteger[] b) {
        int degB = b.length - 1;
        BigInteger[] r = a.clone();
        BigInteger lcB = b[degB];
        BigInteger absLcB = lcB.abs();
        int signLcB = lcB.signum();

        for (int i = r.length - 1; i >= degB; i--) {
            BigInteger coeff = r[i];
            if (coeff.signum() == 0) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                r[j] = r[j].multiply(absLcB);
            }
            r[i] = BigInteger.ZERO;
            BigInteger mult = signLcB > 0 ? coeff : coeff.negate();
            int offset = i - degB;
            for (int j = 0; j < degB; j++) {
                r[offset + j] = r[offset + j].subtract(mult.multiply(b[j]));
            }
        }
        for (int i = 0; i < r.length; i++) {
            r[i] = r[i].negate();
        }
        BigInteger[] trimmed = trim(r);
        return trimmed.length == 0 ? trimmed : divideByPositiveContent(trimmed);
    }

    private static BigInteger[] exactDividePoly(BigInteger[] a, BigInteger[] g) {
        int degA = a.length - 1;
        int degG = g.length - 1;
        if (degG == 0) {
            return a;
        }
        Fraction[] rem = new Fraction[a.length];
        for (int i = 0; i < a.length; i++) {
            rem[i] = Fraction.of(a[i]);
        }
        Fraction[] q = new Fraction[degA - degG + 1];
        Arrays.fill(q, Fraction.ZERO);
        Fraction lcG = Fraction.of(g[degG]);

        for (int i = degA; i >= degG; i--) {
            if (!rem[i].isZero()) {
                Fraction term = rem[i].divide(lcG);
                int shift = i - degG;
                q[shift] = term;
                for (int j = 0; j <= degG; j++) {
                    rem[shift + j] = rem[shift + j].subtract(term.multiply(Fraction.of(g[j])));
                }
            }
        }
        return toPrimitiveIntPoly(Polynomial.of(q));
    }

    /**
     * Evaluates sign of sum_{i=0..d} p[i] * (num / den)^i exactly via homogeneous form
     * sum_{i=0..d} p[i] * num^i * den^{d-i} (valid since den > 0).
     */
    static int evalSign(BigInteger[] p, BigInteger num, BigInteger den) {
        if (p.length == 0) {
            return 0;
        }
        int d = p.length - 1;
        BigInteger acc = p[d];
        BigInteger denPow = BigInteger.ONE;
        for (int i = d - 1; i >= 0; i--) {
            denPow = denPow.multiply(den);
            acc = acc.multiply(num).add(p[i].multiply(denPow));
        }
        return acc.signum();
    }

    private static BigInteger[] divideByPositiveContent(BigInteger[] p) {
        if (p.length == 0) {
            return p;
        }
        BigInteger g = BigInteger.ZERO;
        for (BigInteger c : p) {
            if (c.signum() != 0) {
                g = g.signum() == 0 ? c.abs() : g.gcd(c.abs());
                if (g.equals(BigInteger.ONE)) {
                    return p;
                }
            }
        }
        if (g.signum() == 0 || g.equals(BigInteger.ONE)) {
            return p;
        }
        BigInteger[] out = new BigInteger[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = p[i].divide(g);
        }
        return out;
    }

    private static BigInteger[] trim(BigInteger[] p) {
        int end = p.length - 1;
        while (end >= 0 && p[end].signum() == 0) {
            end--;
        }
        if (end < 0) {
            return new BigInteger[0];
        }
        return end == p.length - 1 ? p : Arrays.copyOf(p, end + 1);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Solve cancelled");
        }
    }
}
