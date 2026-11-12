package omino2048;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * High-speed certified floating-point + exact primitive-polynomial DP solver for {@link EnvelopeSolver}.
 * Uses rigorous IEEE-754 error-radius propagation to certify optimal move selections, exact
 * {@link FastPoly} closure verification for tied symmetric states, and {@code double}-precision
 * locked-state delta propagation with reach-weight ordering and conservative early stopping.
 */
final class FastEnvelopeDp {
    private static final double ULP_UNIT = 2.220446049250313e-16;

    static final class PolicySignature {
        private final int[] packedMoves;
        private final int hash;

        PolicySignature(int[] packedMoves) {
            this.packedMoves = packedMoves;
            this.hash = Arrays.hashCode(packedMoves);
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof PolicySignature other) && Arrays.equals(packedMoves, other.packedMoves);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static final class Workspace {
        final int totalStates;
        final int maxLayer;
        final double[] val;
        final double[] err;
        final byte[] bestMove;
        final boolean[] stateHasTie;
        final double[] secondMoveRate;
        final double[][] moveRates;
        final double[][] moveErrs;
        final boolean[] reach;
        final int[] bfsQueue;
        final int[] packedSigBuf;
        final FastPoly[] memoPoly;
        final boolean[] inStack;

        // Scratch arrays for second-highest delta propagation
        final double[] curOpt;
        final double[] reachWeight;
        final boolean[] isDirty;
        final int[][] dirtyLayers;
        final int[] dirtyCounts;
        final int[] touchedIds;
        final int[] candIds;
        final double[] candScores;
        final int[] candOrder;

        Workspace(GameSolver.CompiledGame game) {
            int n = game.totalStates;
            this.totalStates = n;
            this.maxLayer = game.maxLayer;
            this.val = new double[n];
            this.err = new double[n];
            this.bestMove = new byte[n];
            this.stateHasTie = new boolean[n];
            this.secondMoveRate = new double[n];
            this.moveRates = new double[n][4];
            this.moveErrs = new double[n][4];
            this.reach = new boolean[n];
            this.bfsQueue = new int[n];
            this.packedSigBuf = new int[n];
            this.memoPoly = new FastPoly[n];
            this.inStack = new boolean[n];

            this.curOpt = new double[n];
            this.reachWeight = new double[n];
            this.isDirty = new boolean[n];
            this.dirtyLayers = new int[maxLayer + 1][];
            for (int l = 2; l <= maxLayer; l++) {
                this.dirtyLayers[l] = new int[game.idsInLayer[l].length];
            }
            this.dirtyCounts = new int[maxLayer + 1];
            this.touchedIds = new int[n];
            this.candIds = new int[n];
            this.candScores = new double[n];
            this.candOrder = new int[n];
        }
    }

    /**
     * Primitive {@code long[]}/{@code long[]} rational polynomial arithmetic with automatic
     * {@link BigInteger} fallback on overflow.
     */
    static final class FastPoly {
        final int deg;
        final long[] lNum;
        final long[] lDen;
        final Polynomial bigPoly; // non-null only when overflowed out of signed 63-bit long

        static final FastPoly ZERO = new FastPoly(-1, new long[0], new long[0], null);
        static final FastPoly ONE = new FastPoly(0, new long[]{1L}, new long[]{1L}, null);

        private FastPoly(int deg, long[] lNum, long[] lDen, Polynomial bigPoly) {
            this.deg = deg;
            this.lNum = lNum;
            this.lDen = lDen;
            this.bigPoly = bigPoly;
        }

        private static long gcd(long a, long b) {
            if (a < 0L) {
                a = -a;
            }
            if (b < 0L) {
                b = -b;
            }
            while (b != 0L) {
                long t = a % b;
                a = b;
                b = t;
            }
            return a;
        }

        static FastPoly add(FastPoly a, FastPoly b) {
            if (a.deg < 0) {
                return b;
            }
            if (b.deg < 0) {
                return a;
            }
            if (a.bigPoly == null && b.bigPoly == null) {
                int maxLen = Math.max(a.deg, b.deg) + 1;
                long[] rn = new long[maxLen];
                long[] rd = new long[maxLen];
                Arrays.fill(rd, 1L);
                boolean ok = true;
                for (int i = 0; i < maxLen; i++) {
                    long an = i <= a.deg ? a.lNum[i] : 0L;
                    long bn = i <= b.deg ? b.lNum[i] : 0L;
                    if (an == 0L && bn == 0L) {
                        continue;
                    }
                    if (an == 0L) {
                        rn[i] = bn;
                        rd[i] = b.lDen[i];
                        continue;
                    }
                    if (bn == 0L) {
                        rn[i] = an;
                        rd[i] = a.lDen[i];
                        continue;
                    }
                    long ad = a.lDen[i];
                    long bd = b.lDen[i];
                    if (ad == bd) {
                        try {
                            long s = Math.addExact(an, bn);
                            if (s != 0L) {
                                long g = gcd(s, ad);
                                rn[i] = s / g;
                                rd[i] = ad / g;
                            }
                        } catch (ArithmeticException ex) {
                            ok = false;
                            break;
                        }
                    } else {
                        long gDen = gcd(ad, bd);
                        long adRed = ad / gDen;
                        long bdRed = bd / gDen;
                        try {
                            long t1 = Math.multiplyExact(an, bdRed);
                            long t2 = Math.multiplyExact(bn, adRed);
                            long s = Math.addExact(t1, t2);
                            if (s != 0L) {
                                long d = Math.multiplyExact(adRed, bd);
                                long g = gcd(s, d);
                                rn[i] = s / g;
                                rd[i] = d / g;
                            }
                        } catch (ArithmeticException ex) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) {
                    int nd = maxLen - 1;
                    while (nd >= 0 && rn[nd] == 0L) {
                        nd--;
                    }
                    return nd < 0 ? ZERO : new FastPoly(nd, rn, rd, null);
                }
            }
            return fromPolynomial(a.toPolynomial().add(b.toPolynomial()));
        }

        /**
         * Computes {@code ((1 - x) * s2 + x * s4) / k} in a single pass.
         */
        static FastPoly combineStep(FastPoly s2, FastPoly s4, int k) {
            if (s2.deg < 0 && s4.deg < 0) {
                return ZERO;
            }
            if (s2.bigPoly == null && s4.bigPoly == null) {
                int maxDeg = Math.max(s2.deg < 0 ? 0 : s2.deg + 1, s4.deg < 0 ? 0 : s4.deg + 1);
                long[] rn = new long[maxDeg + 1];
                long[] rd = new long[maxDeg + 1];
                Arrays.fill(rd, 1L);
                boolean ok = true;
                long lk = k;
                for (int i = 0; i <= maxDeg; i++) {
                    long nSum = 0L;
                    long dSum = 1L;
                    if (i <= s2.deg && s2.lNum[i] != 0L) {
                        nSum = s2.lNum[i];
                        dSum = s2.lDen[i];
                    }
                    if (i >= 1) {
                        if (i - 1 <= s2.deg && s2.lNum[i - 1] != 0L) {
                            long an;
                            try {
                                an = Math.negateExact(s2.lNum[i - 1]);
                            } catch (ArithmeticException ex) {
                                ok = false;
                                break;
                            }
                            long ad = s2.lDen[i - 1];
                            if (nSum == 0L) {
                                nSum = an;
                                dSum = ad;
                            } else {
                                long g = gcd(dSum, ad);
                                long d1 = dSum / g;
                                long d2 = ad / g;
                                try {
                                    nSum = Math.addExact(Math.multiplyExact(nSum, d2), Math.multiplyExact(an, d1));
                                    dSum = Math.multiplyExact(d1, ad);
                                    if (nSum == 0L) {
                                        dSum = 1L;
                                    } else {
                                        long g2 = gcd(nSum, dSum);
                                        nSum /= g2;
                                        dSum /= g2;
                                    }
                                } catch (ArithmeticException ex) {
                                    ok = false;
                                    break;
                                }
                            }
                        }
                        if (i - 1 <= s4.deg && s4.lNum[i - 1] != 0L) {
                            long bn = s4.lNum[i - 1];
                            long bd = s4.lDen[i - 1];
                            if (nSum == 0L) {
                                nSum = bn;
                                dSum = bd;
                            } else {
                                long g = gcd(dSum, bd);
                                long d1 = dSum / g;
                                long d2 = bd / g;
                                try {
                                    nSum = Math.addExact(Math.multiplyExact(nSum, d2), Math.multiplyExact(bn, d1));
                                    dSum = Math.multiplyExact(d1, bd);
                                    if (nSum == 0L) {
                                        dSum = 1L;
                                    } else {
                                        long g2 = gcd(nSum, dSum);
                                        nSum /= g2;
                                        dSum /= g2;
                                    }
                                } catch (ArithmeticException ex) {
                                    ok = false;
                                    break;
                                }
                            }
                        }
                    }
                    if (nSum != 0L && lk > 1L) {
                        long gk = gcd(nSum, lk);
                        long lkRed = lk / gk;
                        nSum /= gk;
                        try {
                            dSum = Math.multiplyExact(dSum, lkRed);
                        } catch (ArithmeticException ex) {
                            ok = false;
                            break;
                        }
                    }
                    rn[i] = nSum;
                    rd[i] = nSum == 0L ? 1L : dSum;
                }
                if (ok) {
                    int nd = maxDeg;
                    while (nd >= 0 && rn[nd] == 0L) {
                        nd--;
                    }
                    return nd < 0 ? ZERO : new FastPoly(nd, rn, rd, null);
                }
            }
            Polynomial ps2 = s2.toPolynomial();
            Polynomial ps4 = s4.toPolynomial();
            Polynomial combined = ps2.multiply(Polynomial.ONE_MINUS_X)
                    .add(ps4.multiply(Polynomial.X))
                    .multiply(Fraction.of(1, k));
            return fromPolynomial(combined);
        }

        static FastPoly fromPolynomial(Polynomial p) {
            if (p.isZero()) {
                return ZERO;
            }
            if (p.isOne()) {
                return ONE;
            }
            int d = p.degree();
            long[] ln = new long[d + 1];
            long[] ld = new long[d + 1];
            boolean fits = true;
            for (int i = 0; i <= d; i++) {
                Fraction f = p.coefficient(i);
                if (f.getNumerator().bitLength() > 62 || f.getDenominator().bitLength() > 62) {
                    fits = false;
                    break;
                }
                ln[i] = f.getNumerator().longValue();
                ld[i] = f.getDenominator().longValue();
            }
            return fits ? new FastPoly(d, ln, ld, null) : new FastPoly(d, null, null, p);
        }

        Polynomial toPolynomial() {
            if (bigPoly != null) {
                return bigPoly;
            }
            if (deg < 0) {
                return Polynomial.ZERO;
            }
            if (deg == 0 && lNum[0] == 1L && lDen[0] == 1L) {
                return Polynomial.ONE;
            }
            Fraction[] coeffs = new Fraction[deg + 1];
            for (int i = 0; i <= deg; i++) {
                coeffs[i] = Fraction.of(lNum[i], lDen[i]);
            }
            return Polynomial.of(coeffs);
        }

        boolean samePoly(FastPoly other) {
            if (this == other) {
                return true;
            }
            if (this.deg != other.deg) {
                return false;
            }
            if (this.bigPoly == null && other.bigPoly == null) {
                for (int i = 0; i <= deg; i++) {
                    if (this.lNum[i] != other.lNum[i] || this.lDen[i] != other.lDen[i]) {
                        return false;
                    }
                }
                return true;
            }
            return this.toPolynomial().equals(other.toPolynomial());
        }
    }

    private FastEnvelopeDp() {}

    static SolveResult solveForEnvelope(
            GameSolver.CompiledGame game,
            Fraction requestedSpawnRate,
            Fraction maxEpsilon,
            int maxPolynomials,
            double minStepForEarlyExit,
            int maxDegree,
            Map<PolicySignature, Polynomial> policyCache,
            Workspace ws) {

        boolean needSecondBest = minStepForEarlyExit > 0.0;
        if (requestedSpawnRate.isZero() || requestedSpawnRate.isOne()) {
            return GameSolver.solveCompiledExact(
                    game, requestedSpawnRate, maxEpsilon, maxPolynomials, needSecondBest);
        }

        checkCancelled();
        double p = requestedSpawnRate.doubleValue();
        double q = Fraction.ONE.subtract(requestedSpawnRate).doubleValue();

        // 1. Certified error-tracked double backward DP
        runCertifiedDoubleDp(game, p, q, ws);

        // 2. Trace reachable policy under bestMove & build exact signature
        int sigLen = 0;
        boolean anyReachableTie = false;
        Arrays.fill(ws.reach, false);
        int qHead = 0;
        int qTail = 0;
        for (int id : game.init22Ids) {
            if (!ws.reach[id]) {
                ws.reach[id] = true;
                ws.bfsQueue[qTail++] = id;
            }
        }
        for (int id : game.init24Ids) {
            if (!ws.reach[id]) {
                ws.reach[id] = true;
                ws.bfsQueue[qTail++] = id;
            }
        }
        for (int id : game.init44Ids) {
            if (!ws.reach[id]) {
                ws.reach[id] = true;
                ws.bfsQueue[qTail++] = id;
            }
        }
        while (qHead < qTail) {
            int id = ws.bfsQueue[qHead++];
            int bm = ws.bestMove[id];
            if (bm < 0) {
                continue;
            }
            ws.packedSigBuf[sigLen++] = (id << 3) | bm;
            if (ws.stateHasTie[id]) {
                anyReachableTie = true;
            }
            int[] m2 = game.moveChildren2[id][bm];
            int[] m4 = game.moveChildren4[id][bm];
            for (int c2 : m2) {
                if (!ws.reach[c2]) {
                    ws.reach[c2] = true;
                    ws.bfsQueue[qTail++] = c2;
                }
            }
            for (int c4 : m4) {
                if (!ws.reach[c4]) {
                    ws.reach[c4] = true;
                    ws.bfsQueue[qTail++] = c4;
                }
            }
        }

        // Canonical sort signature so BFS insertion order differences never fragment cache keys
        Arrays.sort(ws.packedSigBuf, 0, sigLen);
        PolicySignature sig = new PolicySignature(Arrays.copyOf(ws.packedSigBuf, sigLen));
        Polynomial optPoly = null;

        if (!anyReachableTie) {
            optPoly = policyCache != null ? policyCache.get(sig) : null;
            if (optPoly == null) {
                optPoly = buildPolicyPolyIterative(game, qTail, ws);
                if (policyCache != null) {
                    policyCache.putIfAbsent(sig, optPoly);
                }
            }
        } else {
            // Verify every reachable tie is an identical-polynomial tie; otherwise fall back to exact Fraction DP.
            optPoly = verifyTieClosureAndBuildPoly(game, qTail, ws);
            if (optPoly == null) {
                SolveResult exactNoSecond = GameSolver.solveCompiledExact(
                        game, requestedSpawnRate, maxEpsilon, maxPolynomials, false);
                if (!needSecondBest) {
                    return exactNoSecond;
                }
                Fraction secondFrac = computeFastSecondBestAfterExactFallback(
                        game, exactNoSecond, minStepForEarlyExit, maxDegree, ws);
                return new SolveResult(
                        exactNoSecond.successRate(),
                        exactNoSecond.optimalPolynomials(),
                        exactNoSecond.testedSpawnRate(),
                        secondFrac);
            }
            if (policyCache != null) {
                policyCache.putIfAbsent(sig, optPoly);
            }
        }

        // Compute exact rational start probability M = P(x)
        Fraction exactStartRate = optPoly.evaluate(requestedSpawnRate);

        // 3. Optional fast double 2nd-best bound for Phase 1 certified leaps
        Fraction secondHighest = null;
        if (needSecondBest) {
            SecondBestOutcome sb = computeFastSecondBestBound(
                    game, p, q, exactStartRate.doubleValue(), qTail, minStepForEarlyExit, maxDegree, ws);
            if (sb.requiresExactFallback) {
                return GameSolver.solveCompiledExact(
                        game, requestedSpawnRate, maxEpsilon, maxPolynomials, true);
            }
            secondHighest = sb.secondRate;
        }

        return new SolveResult(exactStartRate, List.of(optPoly), requestedSpawnRate, secondHighest);
    }

    private static void runCertifiedDoubleDp(
            GameSolver.CompiledGame game,
            double p,
            double q,
            Workspace ws) {
        double[] val = ws.val;
        double[] err = ws.err;
        byte[] bestMove = ws.bestMove;
        boolean[] stateHasTie = ws.stateHasTie;
        double[] secondMove = ws.secondMoveRate;
        double[][] moveRates = ws.moveRates;
        double[][] moveErrs = ws.moveErrs;

        for (int layer = game.maxLayer; layer >= 2; layer--) {
            checkCancelled();
            for (int id : game.idsInLayer[layer]) {
                if (game.isWinning[id]) {
                    val[id] = 1.0;
                    err[id] = 0.0;
                    bestMove[id] = -1;
                    stateHasTie[id] = false;
                    secondMove[id] = -1.0;
                    continue;
                }
                int[][] c2s = game.moveChildren2[id];
                int mc = c2s.length;
                if (mc == 0) {
                    val[id] = 0.0;
                    err[id] = 0.0;
                    bestMove[id] = -1;
                    stateHasTie[id] = false;
                    secondMove[id] = -1.0;
                    continue;
                }
                int[][] c4s = game.moveChildren4[id];
                double[] mRates = moveRates[id];
                double[] mErrs = moveErrs[id];

                double best = -1.0;
                double bestErr = 0.0;
                byte bm = 0;
                double second = -1.0;

                for (int m = 0; m < mc; m++) {
                    int[] m2 = c2s[m];
                    int[] m4 = c4s[m];
                    int k = m2.length;
                    double s2 = 0.0;
                    double s4 = 0.0;
                    double e2 = 0.0;
                    double e4 = 0.0;
                    for (int i = 0; i < k; i++) {
                        int id2 = m2[i];
                        int id4 = m4[i];
                        s2 += val[id2];
                        e2 += err[id2];
                        s4 += val[id4];
                        e4 += err[id4];
                    }
                    double r = (q * s2 + p * s4) / k;
                    double e = (s2 == 0.0 && s4 == 0.0 && e2 == 0.0 && e4 == 0.0)
                            ? 0.0
                            : (q * e2 + p * e4) / k + (8 * k + 24) * ULP_UNIT;
                    mRates[m] = r;
                    mErrs[m] = e;
                    if (r > best) {
                        second = best;
                        best = r;
                        bestErr = e;
                        bm = (byte) m;
                    } else if (r > second) {
                        second = r;
                    }
                }

                boolean tied = false;
                if (mc > 1 && best > 0.0) {
                    for (int m = 0; m < mc; m++) {
                        if (m != bm && best - mRates[m] <= bestErr + mErrs[m]) {
                            tied = true;
                            break;
                        }
                    }
                }

                val[id] = best;
                err[id] = bestErr;
                bestMove[id] = bm;
                stateHasTie[id] = tied;
                secondMove[id] = mc > 1 ? second : -1.0;
            }
        }
    }

    private static Polynomial buildPolicyPolyIterative(
            GameSolver.CompiledGame game,
            int qTail,
            Workspace ws) {
        FastPoly[] memo = ws.memoPoly;
        for (int i = 0; i < qTail; i++) {
            memo[ws.bfsQueue[i]] = null;
        }
        // Traverse reach states in layer-descending order (actually BFS queue doesn't sort layers, iterate layers!)
        for (int layer = game.maxLayer; layer >= 2; layer--) {
            for (int id : game.idsInLayer[layer]) {
                if (!ws.reach[id]) {
                    continue;
                }
                if (game.isWinning[id]) {
                    memo[id] = FastPoly.ONE;
                    continue;
                }
                int bm = ws.bestMove[id];
                if (bm < 0) {
                    memo[id] = FastPoly.ZERO;
                    continue;
                }
                int[] m2 = game.moveChildren2[id][bm];
                int[] m4 = game.moveChildren4[id][bm];
                int k = m2.length;
                FastPoly s2 = FastPoly.ZERO;
                FastPoly s4 = FastPoly.ZERO;
                for (int i = 0; i < k; i++) {
                    s2 = FastPoly.add(s2, memo[m2[i]]);
                    s4 = FastPoly.add(s4, memo[m4[i]]);
                }
                memo[id] = FastPoly.combineStep(s2, s4, k);
            }
        }
        return combineRootFastPolys(game, memo);
    }

    private static Polynomial verifyTieClosureAndBuildPoly(
            GameSolver.CompiledGame game,
            int qTail,
            Workspace ws) {
        Arrays.fill(ws.memoPoly, null);
        for (int id : game.init22Ids) {
            if (evalVerifiedPoly(id, game, ws) == null) {
                return null;
            }
        }
        for (int id : game.init24Ids) {
            if (evalVerifiedPoly(id, game, ws) == null) {
                return null;
            }
        }
        for (int id : game.init44Ids) {
            if (evalVerifiedPoly(id, game, ws) == null) {
                return null;
            }
        }
        return combineRootFastPolys(game, ws.memoPoly);
    }

    private static FastPoly evalVerifiedPoly(int id, GameSolver.CompiledGame game, Workspace ws) {
        FastPoly cached = ws.memoPoly[id];
        if (cached != null) {
            return cached;
        }
        if (game.isWinning[id]) {
            ws.memoPoly[id] = FastPoly.ONE;
            return FastPoly.ONE;
        }
        int bm = ws.bestMove[id];
        if (bm < 0) {
            ws.memoPoly[id] = FastPoly.ZERO;
            return FastPoly.ZERO;
        }

        FastPoly bestPoly = evalMovePoly(id, bm, game, ws);
        if (bestPoly == null) {
            return null;
        }

        if (ws.stateHasTie[id]) {
            int mc = game.moveChildren2[id].length;
            double bestVal = ws.val[id];
            double bestErr = ws.err[id];
            double[] mRates = ws.moveRates[id];
            double[] mErrs = ws.moveErrs[id];
            for (int m = 0; m < mc; m++) {
                if (m == bm || bestVal - mRates[m] > bestErr + mErrs[m]) {
                    continue;
                }
                // Check identical sorted child arrays quickly before recursing into m
                if (!sameMoveChildMultisets(game.moveChildren2[id][bm], game.moveChildren2[id][m],
                        game.moveChildren4[id][bm], game.moveChildren4[id][m])) {
                    FastPoly altPoly = evalMovePoly(id, m, game, ws);
                    if (altPoly == null || !altPoly.samePoly(bestPoly)) {
                        return null;
                    }
                }
            }
        }

        ws.memoPoly[id] = bestPoly;
        return bestPoly;
    }

    private static FastPoly evalMovePoly(int id, int moveIdx, GameSolver.CompiledGame game, Workspace ws) {
        int[] m2 = game.moveChildren2[id][moveIdx];
        int[] m4 = game.moveChildren4[id][moveIdx];
        int k = m2.length;
        FastPoly s2 = FastPoly.ZERO;
        FastPoly s4 = FastPoly.ZERO;
        for (int i = 0; i < k; i++) {
            FastPoly p2 = evalVerifiedPoly(m2[i], game, ws);
            if (p2 == null) {
                return null;
            }
            s2 = FastPoly.add(s2, p2);

            FastPoly p4 = evalVerifiedPoly(m4[i], game, ws);
            if (p4 == null) {
                return null;
            }
            s4 = FastPoly.add(s4, p4);
        }
        return FastPoly.combineStep(s2, s4, k);
    }

    private static boolean sameMoveChildMultisets(int[] a2, int[] b2, int[] a4, int[] b4) {
        int k = a2.length;
        if (k != b2.length) {
            return false;
        }
        if (k == 1) {
            return a2[0] == b2[0] && a4[0] == b4[0];
        }
        int[] ca2 = a2.clone();
        int[] cb2 = b2.clone();
        int[] ca4 = a4.clone();
        int[] cb4 = b4.clone();
        Arrays.sort(ca2);
        Arrays.sort(cb2);
        if (!Arrays.equals(ca2, cb2)) {
            return false;
        }
        Arrays.sort(ca4);
        Arrays.sort(cb4);
        return Arrays.equals(ca4, cb4);
    }

    private static Polynomial combineRootFastPolys(GameSolver.CompiledGame game, FastPoly[] memo) {
        FastPoly s22 = FastPoly.ZERO;
        for (int id : game.init22Ids) {
            s22 = FastPoly.add(s22, memo[id]);
        }
        FastPoly s24 = FastPoly.ZERO;
        for (int id : game.init24Ids) {
            s24 = FastPoly.add(s24, memo[id]);
        }
        FastPoly s44 = FastPoly.ZERO;
        for (int id : game.init44Ids) {
            s44 = FastPoly.add(s44, memo[id]);
        }
        Fraction invPairs = Fraction.of(1, game.numPairs);
        Polynomial w22 = Polynomial.ONE_MINUS_X.multiply(Polynomial.ONE_MINUS_X).multiply(invPairs);
        Polynomial w24 = Polynomial.X.multiply(Polynomial.ONE_MINUS_X).multiply(invPairs);
        Polynomial w44 = Polynomial.X.multiply(Polynomial.X).multiply(invPairs);
        return s22.toPolynomial().multiply(w22)
                .add(s24.toPolynomial().multiply(w24))
                .add(s44.toPolynomial().multiply(w44));
    }

    private record SecondBestOutcome(boolean requiresExactFallback, Fraction secondRate) {}

    private static SecondBestOutcome computeFastSecondBestBound(
            GameSolver.CompiledGame game,
            double p,
            double q,
            double mDouble,
            int qTail,
            double minStepForEarlyExit,
            int maxDegree,
            Workspace ws) {

        double[] opt = ws.val;
        double[] err = ws.err;
        double[] reachWeight = ws.reachWeight;
        double[] secondMove = ws.secondMoveRate;
        byte[] bestMove = ws.bestMove;

        for (int i = 0; i < qTail; i++) {
            reachWeight[ws.bfsQueue[i]] = 0.0;
        }
        double invPairs = 1.0 / game.numPairs;
        double w22 = q * q * invPairs;
        double w24 = p * q * invPairs;
        double w44 = p * p * invPairs;
        for (int id : game.init22Ids) {
            reachWeight[id] += w22;
        }
        for (int id : game.init24Ids) {
            reachWeight[id] += w24;
        }
        for (int id : game.init44Ids) {
            reachWeight[id] += w44;
        }

        double maxReachErr = 0.0;
        int candCount = 0;
        for (int layer = 2; layer <= game.maxLayer; layer++) {
            for (int id : game.idsInLayer[layer]) {
                if (!ws.reach[id]) {
                    continue;
                }
                if (err[id] > maxReachErr) {
                    maxReachErr = err[id];
                }
                int bm = bestMove[id];
                if (bm < 0) {
                    continue;
                }
                double w = reachWeight[id];
                int[] m2 = game.moveChildren2[id][bm];
                int[] m4 = game.moveChildren4[id][bm];
                int k = m2.length;
                double w2Child = w * q / k;
                double w4Child = w * p / k;
                for (int i = 0; i < k; i++) {
                    reachWeight[m2[i]] += w2Child;
                    reachWeight[m4[i]] += w4Child;
                }
                if (w > 0.0 && secondMove[id] >= 0.0) {
                    double gap = opt[id] - secondMove[id];
                    if (gap > 0.0) {
                        ws.candIds[candCount] = id;
                        ws.candScores[candCount] = w * gap;
                        ws.candOrder[candCount] = candCount;
                        candCount++;
                    }
                }
            }
        }

        if (candCount == 0) {
            return new SecondBestOutcome(false, null);
        }

        // Test lowest first-order loss candidates first so deltaRight < minStep breaks after 1-2 locks!
        sortOrderByScore(ws.candOrder, ws.candScores, 0, candCount - 1);

        double safetyErr = Math.max(1e-11, 8.0 * maxReachErr + 64.0 * ULP_UNIT);
        System.arraycopy(opt, 0, ws.curOpt, 0, game.totalStates);
        double[] cur = ws.curOpt;
        boolean[] dirty = ws.isDirty;
        int[][] dirtyLayers = ws.dirtyLayers;
        int[] dirtyCounts = ws.dirtyCounts;
        int[] touched = ws.touchedIds;

        double bestSecondStart = -1.0;
        boolean sawDubiousZeroGapCandidate = false;
        double startMarginForExactComparison = Math.max(1e-11, 16.0 * maxReachErr);

        for (int ci = 0; ci < candCount; ci++) {
            checkCancelled();
            int lockedId = ws.candIds[ws.candOrder[ci]];
            double lockedVal = secondMove[lockedId];
            if (lockedVal == opt[lockedId]) {
                continue;
            }
            int lockedLayer = game.layerById[lockedId];
            int touchedCount = 0;
            cur[lockedId] = lockedVal;
            touched[touchedCount++] = lockedId;

            for (int pid : game.parentsById[lockedId]) {
                if (!dirty[pid]) {
                    dirty[pid] = true;
                    int pl = game.layerById[pid];
                    dirtyLayers[pl][dirtyCounts[pl]++] = pid;
                }
            }

            for (int l = lockedLayer - 1; l >= 2; l--) {
                int dCount = dirtyCounts[l];
                if (dCount == 0) {
                    continue;
                }
                int[] dList = dirtyLayers[l];
                for (int idx = 0; idx < dCount; idx++) {
                    int id = dList[idx];
                    dirty[id] = false;
                    int[][] c2s = game.moveChildren2[id];
                    int[][] c4s = game.moveChildren4[id];
                    double nb = -1.0;
                    for (int m = 0; m < c2s.length; m++) {
                        int[] m2 = c2s[m];
                        int[] m4 = c4s[m];
                        int k = m2.length;
                        double s2 = 0.0;
                        double s4 = 0.0;
                        for (int i = 0; i < k; i++) {
                            s2 += cur[m2[i]];
                            s4 += cur[m4[i]];
                        }
                        double r = (q * s2 + p * s4) / k;
                        if (r > nb) {
                            nb = r;
                        }
                    }
                    if (nb != cur[id]) {
                        cur[id] = nb;
                        touched[touchedCount++] = id;
                        for (int pid : game.parentsById[id]) {
                            if (!dirty[pid]) {
                                dirty[pid] = true;
                                int pl = game.layerById[pid];
                                dirtyLayers[pl][dirtyCounts[pl]++] = pid;
                            }
                        }
                    }
                }
                dirtyCounts[l] = 0;
            }

            double s22 = 0.0;
            for (int id : game.init22Ids) {
                s22 += cur[id];
            }
            double s24 = 0.0;
            for (int id : game.init24Ids) {
                s24 += cur[id];
            }
            double s44 = 0.0;
            for (int id : game.init44Ids) {
                s44 += cur[id];
            }
            double candStart = s22 * w22 + s24 * w24 + s44 * w44;

            for (int i = 0; i < touchedCount; i++) {
                int tid = touched[i];
                cur[tid] = opt[tid];
            }

            if (mDouble - candStart > startMarginForExactComparison) {
                // Strictly proved < M; shift conservatively upwards toward M so H is strictly <= true H
                double safeS = Math.min(mDouble, candStart + safetyErr);
                if (safeS > bestSecondStart) {
                    bestSecondStart = safeS;
                }
                double conservativeRight = conservativeRightBound(p, mDouble, safeS, maxDegree);
                if (conservativeRight - p < minStepForEarlyExit) {
                    return new SecondBestOutcome(false, conservativeFractionFromDouble(safeS));
                }
            } else {
                sawDubiousZeroGapCandidate = true;
            }
        }

        if (bestSecondStart >= 0.0) {
            return new SecondBestOutcome(false, conservativeFractionFromDouble(bestSecondStart));
        }
        if (sawDubiousZeroGapCandidate) {
            return new SecondBestOutcome(true, null);
        }
        return new SecondBestOutcome(false, null);
    }

    private static Fraction computeFastSecondBestAfterExactFallback(
            GameSolver.CompiledGame game,
            SolveResult exactNoSecond,
            double minStepForEarlyExit,
            int maxDegree,
            Workspace ws) {
        Fraction x = exactNoSecond.testedSpawnRate();
        if (x.isZero() || x.isOne()) {
            return null;
        }
        double p = x.doubleValue();
        double q = Fraction.ONE.subtract(x).doubleValue();
        runCertifiedDoubleDp(game, p, q, ws);
        Arrays.fill(ws.reach, false);
        int qHead = 0;
        int qTail = 0;
        for (int id : game.init22Ids) {
            if (!ws.reach[id]) {
                ws.reach[id] = true;
                ws.bfsQueue[qTail++] = id;
            }
        }
        for (int id : game.init24Ids) {
            if (!ws.reach[id]) {
                ws.reach[id] = true;
                ws.bfsQueue[qTail++] = id;
            }
        }
        for (int id : game.init44Ids) {
            if (!ws.reach[id]) {
                ws.reach[id] = true;
                ws.bfsQueue[qTail++] = id;
            }
        }
        while (qHead < qTail) {
            int id = ws.bfsQueue[qHead++];
            int bm = ws.bestMove[id];
            if (bm < 0) {
                continue;
            }
            for (int c2 : game.moveChildren2[id][bm]) {
                if (!ws.reach[c2]) {
                    ws.reach[c2] = true;
                    ws.bfsQueue[qTail++] = c2;
                }
            }
            for (int c4 : game.moveChildren4[id][bm]) {
                if (!ws.reach[c4]) {
                    ws.reach[c4] = true;
                    ws.bfsQueue[qTail++] = c4;
                }
            }
        }
        SecondBestOutcome sb = computeFastSecondBestBound(
                game, p, q, exactNoSecond.successRate().doubleValue(), qTail, minStepForEarlyExit, maxDegree, ws);
        if (sb.requiresExactFallback) {
            SolveResult full = GameSolver.solveCompiledExact(
                    game, x, Fraction.ZERO, GameSolver.DEFAULT_MAX_POLYNOMIALS, true);
            return full.secondHighestSuccessRate();
        }
        return sb.secondRate;
    }

    private static void sortOrderByScore(int[] order, double[] scores, int lo, int hi) {
        if (lo >= hi) {
            return;
        }
        if (hi - lo < 16) {
            for (int i = lo + 1; i <= hi; i++) {
                int key = order[i];
                double ks = scores[key];
                int j = i - 1;
                while (j >= lo && scores[order[j]] > ks) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = key;
            }
            return;
        }
        int mid = (lo + hi) >>> 1;
        double pivot = scores[order[mid]];
        int i = lo;
        int j = hi;
        while (i <= j) {
            while (scores[order[i]] < pivot) {
                i++;
            }
            while (scores[order[j]] > pivot) {
                j--;
            }
            if (i <= j) {
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
                i++;
                j--;
            }
        }
        if (lo < j) {
            sortOrderByScore(order, scores, lo, j);
        }
        if (i < hi) {
            sortOrderByScore(order, scores, i, hi);
        }
    }

    private static double conservativeRightBound(double x0, double m, double s, int maxDegree) {
        double uM = Math.max(-1.0, Math.min(1.0, 2.0 * m - 1.0));
        double uS = Math.max(-1.0, Math.min(1.0, 2.0 * s - 1.0));
        double g = Math.max(0.0, Math.asin(uM) - Math.asin(uS));
        double h = g / (8.0 * Math.max(1, maxDegree));
        double t = Math.asin(Math.sqrt(Math.max(0.0, Math.min(1.0, x0))));
        double tRight = t + h;
        return (tRight >= Math.PI / 2.0) ? 1.0 : Math.pow(Math.sin(tRight), 2);
    }

    private static Fraction conservativeFractionFromDouble(double s) {
        double clamped = Math.max(0.0, Math.min(1.0, s));
        long num = Math.min(1_000_000_000_000L, Math.max(0L, (long) Math.ceil(clamped * 1_000_000_000_000.0)));
        return Fraction.of(num, 1_000_000_000_000L);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Solve cancelled");
        }
    }
}
