package omino2048;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Exact backward-DP solver for 2048 on arbitrary small polyominoes.
 * Computes the exact optimal success probability, deduplicated strategy polynomials,
 * tested 4-tile spawn rate, and 2nd-highest policy success probability.
 */
public final class GameSolver {
    public static final int DEFAULT_MAX_POLYNOMIALS = 1_000;
    public static final Fraction DEFAULT_EPSILON = Fraction.of(1, 1_000_000);
    public static final Fraction DEFAULT_MAX_EPSILON = Fraction.of(1, 1_000);

    private static final int BITS_PER_CELL = 5;
    private static final long CELL_MASK = 0x1FL;
    private static final int MAX_CELLS = Long.SIZE / BITS_PER_CELL; // 12 cells fit in a 64-bit long
    private static final int MAX_TWEAK_STEPS = 32;
    private static final int[] SINGLE_MOVE_ORDER = {0};

    private enum BoundaryMode {
        INTERIOR,
        ZERO_PLUS_EPSILON,
        ONE_MINUS_EPSILON
    }

    static final class CompiledGame {
        final Polyomino polyomino;
        final int n;
        final int goalExp;
        final int numPairs;
        final int maxLayer;
        final int totalStates;
        final int maxDegree;
        final long[] boardById;
        final int[] layerById;
        final int[][] idsInLayer;
        final boolean[] isWinning;
        final int[][][] moveChildren2;
        final int[][][] moveChildren4;
        final int[][] parentsById;
        final int[] init22Ids;
        final int[] init24Ids;
        final int[] init44Ids;

        CompiledGame(
                Polyomino polyomino,
                int n,
                int goalExp,
                int numPairs,
                int maxLayer,
                int totalStates,
                int maxDegree,
                long[] boardById,
                int[] layerById,
                int[][] idsInLayer,
                boolean[] isWinning,
                int[][][] moveChildren2,
                int[][][] moveChildren4,
                int[][] parentsById,
                int[] init22Ids,
                int[] init24Ids,
                int[] init44Ids) {
            this.polyomino = polyomino;
            this.n = n;
            this.goalExp = goalExp;
            this.numPairs = numPairs;
            this.maxLayer = maxLayer;
            this.totalStates = totalStates;
            this.maxDegree = maxDegree;
            this.boardById = boardById;
            this.layerById = layerById;
            this.idsInLayer = idsInLayer;
            this.isWinning = isWinning;
            this.moveChildren2 = moveChildren2;
            this.moveChildren4 = moveChildren4;
            this.parentsById = parentsById;
            this.init22Ids = init22Ids;
            this.init24Ids = init24Ids;
            this.init44Ids = init44Ids;
        }
    }

    private record LayerGraph(
            int n,
            int goalExp,
            int numPairs,
            int maxLayer,
            List<Long> init22,
            List<Long> init24And42,
            List<Long> init44,
            Map<Integer, Set<Long>> layers) {}

    private static final class PolynomialExplosionException extends RuntimeException {
        private static final PolynomialExplosionException INSTANCE = new PolynomialExplosionException();

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private GameSolver() {}

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Solve cancelled");
        }
    }

    /**
     * Solves 2048 on the given polyomino for exact {@code tileGoal} using
     * {@link #DEFAULT_MAX_EPSILON} and {@link #DEFAULT_MAX_POLYNOMIALS}.
     *
     * <p>Note: {@code requestedSpawnRate} is only a <em>requested</em> 4-tile spawn rate in [0, 1].
     * If the solver predicts (or detects during DP) that the number of tied optimal polynomials at
     * {@code requestedSpawnRate} would exceed the complexity budget, it will tweak the spawn rate
     * by a small epsilon within {@code +/- DEFAULT_MAX_EPSILON} (staying in (0, 1)) and report
     * the actual rate solved in {@link SolveResult#testedSpawnRate()}.</p>
     *
     * <p>Boundary handling: at spawn rate 0 (resp. 1), ties are broken at {@code 0 + epsilon}
     * (resp. {@code 1 - epsilon}) without modifying {@code testedSpawnRate}, selecting the
     * dominant right-hand (resp. left-hand) continuation.</p>
     */
    public static SolveResult solve(Polyomino polyomino, int tileGoal, Fraction requestedSpawnRate) {
        return solve(polyomino, tileGoal, requestedSpawnRate, DEFAULT_MAX_EPSILON, DEFAULT_MAX_POLYNOMIALS);
    }

    /**
     * Solves 2048 with a caller-specified maximum epsilon perturbation bound and {@link #DEFAULT_MAX_POLYNOMIALS}.
     *
     * <p>{@code requestedSpawnRate} is only a requested 4-tile spawn rate and may be tweaked by up to
     * {@code maxEpsilon} if the expected number of polynomials would be too high; the actual solved
     * rate is returned in {@link SolveResult#testedSpawnRate()}.</p>
     */
    public static SolveResult solve(
            Polyomino polyomino,
            int tileGoal,
            Fraction requestedSpawnRate,
            Fraction maxEpsilon) {
        return solve(polyomino, tileGoal, requestedSpawnRate, maxEpsilon, DEFAULT_MAX_POLYNOMIALS);
    }

    /**
     * Solves 2048 with {@link #DEFAULT_MAX_EPSILON} and a caller-specified polynomial cap.
     *
     * <p>{@code requestedSpawnRate} is only a requested 4-tile spawn rate and may be tweaked by up to
     * {@link #DEFAULT_MAX_EPSILON} if the expected number of polynomials exceeds {@code maxPolynomials};
     * the actual solved rate is returned in {@link SolveResult#testedSpawnRate()}.</p>
     */
    public static SolveResult solve(
            Polyomino polyomino,
            int tileGoal,
            Fraction requestedSpawnRate,
            int maxPolynomials) {
        return solve(polyomino, tileGoal, requestedSpawnRate, DEFAULT_MAX_EPSILON, maxPolynomials);
    }

    /**
     * Solves 2048 on the given polyomino for exact {@code tileGoal} with configurable
     * {@code maxEpsilon} and {@code maxPolynomials}.
     *
     * <p>{@code requestedSpawnRate} is only a requested 4-tile spawn rate and may be tweaked by at most
     * {@code maxEpsilon} if the predicted or observed number of optimal polynomials exceeds
     * {@code maxPolynomials}; the actual solved rate is returned in {@link SolveResult#testedSpawnRate()}.</p>
     */
    public static SolveResult solve(
            Polyomino polyomino,
            int tileGoal,
            Fraction requestedSpawnRate,
            Fraction maxEpsilon,
            int maxPolynomials) {
        CompiledGame game = compileGame(polyomino, tileGoal);
        return solveCompiled(game, requestedSpawnRate, maxEpsilon, maxPolynomials);
    }

    /**
     * Computes the full piecewise-polynomial upper envelope over all 4-tile spawn rates in {@code [0, 1]}.
     */
    public static EnvelopeResult solveEnvelope(Polyomino polyomino, int tileGoal) {
        return EnvelopeSolver.solve(polyomino, tileGoal);
    }

    /**
     * Computes the full piecewise-polynomial upper envelope over all 4-tile spawn rates in {@code [0, 1]}
     * using the requested number of worker threads.
     */
    public static EnvelopeResult solveEnvelope(Polyomino polyomino, int tileGoal, int threads) {
        return EnvelopeSolver.solve(polyomino, tileGoal, threads, null);
    }

    /**
     * Computes the full piecewise-polynomial upper envelope over all 4-tile spawn rates in {@code [0, 1]}
     * with custom minimum sweep step and epsilon/polynomial budgets.
     */
    public static EnvelopeResult solveEnvelope(
            Polyomino polyomino,
            int tileGoal,
            double minStep,
            Fraction maxEpsilon,
            int maxPolynomials) {
        return EnvelopeSolver.solve(polyomino, tileGoal, minStep, maxEpsilon, maxPolynomials);
    }

    static CompiledGame compileGame(Polyomino polyomino, int tileGoal) {
        Objects.requireNonNull(polyomino, "polyomino must not be null");
        if (polyomino.size() < 2) {
            throw new IllegalArgumentException(
                    "Polyomino must have at least 2 cells, got: " + polyomino.size());
        }
        if (polyomino.size() > MAX_CELLS) {
            throw new IllegalArgumentException(
                    "Polyomino has " + polyomino.size() + " cells; at most " + MAX_CELLS + " are supported");
        }
        if (tileGoal < 2 || (tileGoal & (tileGoal - 1)) != 0) {
            throw new IllegalArgumentException("tileGoal must be a power of 2 >= 2, got: " + tileGoal);
        }

        LayerGraph graph = buildLayerGraph(polyomino, tileGoal);
        int n = graph.n();
        int goalExp = graph.goalExp();
        int maxLayer = graph.maxLayer();

        int totalStates = 0;
        for (int layer = 2; layer <= maxLayer; layer++) {
            Set<Long> boards = graph.layers().get(layer);
            if (boards != null) {
                totalStates += boards.size();
            }
        }

        Map<Long, Integer> idByBoard = new HashMap<>(totalStates * 2);
        long[] boardById = new long[totalStates];
        int[] layerById = new int[totalStates];
        int[][] idsInLayer = new int[maxLayer + 1][];
        idsInLayer[0] = new int[0];
        idsInLayer[1] = new int[0];
        int nextId = 0;

        for (int layer = 2; layer <= maxLayer; layer++) {
            Set<Long> boards = graph.layers().get(layer);
            if (boards == null || boards.isEmpty()) {
                idsInLayer[layer] = new int[0];
                continue;
            }
            int[] layerIds = new int[boards.size()];
            int pos = 0;
            for (long board : boards) {
                idByBoard.put(board, nextId);
                boardById[nextId] = board;
                layerById[nextId] = layer;
                layerIds[pos++] = nextId;
                nextId++;
            }
            idsInLayer[layer] = layerIds;
        }

        boolean[] isWinning = new boolean[totalStates];
        int[][][] moveChildren2 = new int[totalStates][][];
        int[][][] moveChildren4 = new int[totalStates][][];
        @SuppressWarnings("unchecked")
        List<Integer>[] parentLists = new List[totalStates];

        int[] scratchVals = new int[Math.max(polyomino.rows(), polyomino.cols())];
        long[] moveBuffer = new long[4];
        Direction[] directions = Direction.values();
        int[] emptyIndices = new int[n];
        int[][] emptyMoves = new int[0][];

        for (int id = 0; id < totalStates; id++) {
            long board = boardById[id];
            if (isWinning(board, n, goalExp)) {
                isWinning[id] = true;
                moveChildren2[id] = emptyMoves;
                moveChildren4[id] = emptyMoves;
                continue;
            }
            int moveCount = collectDistinctMoves(board, polyomino, directions, scratchVals, moveBuffer);
            if (moveCount == 0) {
                moveChildren2[id] = emptyMoves;
                moveChildren4[id] = emptyMoves;
                continue;
            }
            int[][] c2s = new int[moveCount][];
            int[][] c4s = new int[moveCount][];
            for (int m = 0; m < moveCount; m++) {
                long afterMove = moveBuffer[m];
                int k = findEmptyCells(afterMove, n, emptyIndices);
                int[] m2 = new int[k];
                int[] m4 = new int[k];
                for (int i = 0; i < k; i++) {
                    int cell = emptyIndices[i];
                    int id2 = idByBoard.get(setExp(afterMove, cell, 1));
                    int id4 = idByBoard.get(setExp(afterMove, cell, 2));
                    m2[i] = id2;
                    m4[i] = id4;
                    if (parentLists[id2] == null) {
                        parentLists[id2] = new ArrayList<>(4);
                    }
                    parentLists[id2].add(id);
                    if (parentLists[id4] == null) {
                        parentLists[id4] = new ArrayList<>(4);
                    }
                    parentLists[id4].add(id);
                }
                c2s[m] = m2;
                c4s[m] = m4;
            }
            moveChildren2[id] = c2s;
            moveChildren4[id] = c4s;
        }

        int[][] parentsById = new int[totalStates][];
        int[] emptyParents = new int[0];
        for (int id = 0; id < totalStates; id++) {
            List<Integer> plist = parentLists[id];
            if (plist == null) {
                parentsById[id] = emptyParents;
            } else {
                int[] arr = new int[plist.size()];
                int unique = 0;
                for (int pid : plist) {
                    boolean seen = false;
                    for (int j = 0; j < unique; j++) {
                        if (arr[j] == pid) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) {
                        arr[unique++] = pid;
                    }
                }
                parentsById[id] = Arrays.copyOf(arr, unique);
            }
        }

        int[] init22Ids = mapBoardIds(graph.init22(), idByBoard);
        int[] init24Ids = mapBoardIds(graph.init24And42(), idByBoard);
        int[] init44Ids = mapBoardIds(graph.init44(), idByBoard);

        // Compute tightest structural upper bound on policy polynomial degree from DAG longest path.
        int[] maxSubtreeDeg = new int[totalStates];
        for (int layer = maxLayer; layer >= 2; layer--) {
            for (int id : idsInLayer[layer]) {
                int[][] c2s = moveChildren2[id];
                if (isWinning[id] || c2s.length == 0) {
                    continue;
                }
                int[][] c4s = moveChildren4[id];
                int bestChildDeg = 0;
                for (int m = 0; m < c2s.length; m++) {
                    int[] m2 = c2s[m];
                    int[] m4 = c4s[m];
                    for (int i = 0; i < m2.length; i++) {
                        int d2 = maxSubtreeDeg[m2[i]];
                        int d4 = maxSubtreeDeg[m4[i]];
                        if (d2 > bestChildDeg) {
                            bestChildDeg = d2;
                        }
                        if (d4 > bestChildDeg) {
                            bestChildDeg = d4;
                        }
                    }
                }
                maxSubtreeDeg[id] = 1 + bestChildDeg;
            }
        }

        int maxStartSubtree = 0;
        for (int id : init22Ids) {
            maxStartSubtree = Math.max(maxStartSubtree, maxSubtreeDeg[id]);
        }
        for (int id : init24Ids) {
            maxStartSubtree = Math.max(maxStartSubtree, maxSubtreeDeg[id]);
        }
        for (int id : init44Ids) {
            maxStartSubtree = Math.max(maxStartSubtree, maxSubtreeDeg[id]);
        }
        int maxDegree = Math.max(1, 2 + maxStartSubtree);

        return new CompiledGame(
                polyomino,
                n,
                goalExp,
                graph.numPairs(),
                maxLayer,
                totalStates,
                maxDegree,
                boardById,
                layerById,
                idsInLayer,
                isWinning,
                moveChildren2,
                moveChildren4,
                parentsById,
                init22Ids,
                init24Ids,
                init44Ids);
    }

    static SolveResult solveCompiled(
            CompiledGame game,
            Fraction requestedSpawnRate,
            Fraction maxEpsilon,
            int maxPolynomials) {
        return solveCompiledExact(game, requestedSpawnRate, maxEpsilon, maxPolynomials, true);
    }

    static SolveResult solveCompiledExact(
            CompiledGame game,
            Fraction requestedSpawnRate,
            Fraction maxEpsilon,
            int maxPolynomials,
            boolean includeSecondBest) {
        Objects.requireNonNull(game, "game must not be null");
        Objects.requireNonNull(requestedSpawnRate, "requestedSpawnRate must not be null");
        Objects.requireNonNull(maxEpsilon, "maxEpsilon must not be null");
        if (requestedSpawnRate.compareTo(Fraction.ZERO) < 0 || requestedSpawnRate.compareTo(Fraction.ONE) > 0) {
            throw new IllegalArgumentException("requestedSpawnRate must be in [0, 1], got: " + requestedSpawnRate);
        }
        if (maxEpsilon.signum() < 0) {
            throw new IllegalArgumentException("maxEpsilon must be non-negative, got: " + maxEpsilon);
        }
        if (maxPolynomials < 1) {
            throw new IllegalArgumentException("maxPolynomials must be >= 1, got: " + maxPolynomials);
        }

        long maxRawCombinations = Math.max(100_000L, (long) maxPolynomials * 100L);

        try {
            return solveCompiledForExactRate(
                    game, requestedSpawnRate, maxPolynomials, maxRawCombinations, includeSecondBest);
        } catch (PolynomialExplosionException ignored) {
            // Fall through to epsilon perturbations around the requested rate.
        }

        if (!maxEpsilon.isZero()) {
            Fraction effectiveMax = computeEffectiveMaxEpsilon(requestedSpawnRate, maxEpsilon);
            Fraction baseEps = chooseBaseEpsilon(effectiveMax);
            boolean testedMax = false;

            for (int step = 1; step <= MAX_TWEAK_STEPS; step++) {
                Fraction delta = baseEps.multiply(Fraction.of(step));
                if (delta.compareTo(effectiveMax) > 0) {
                    break;
                }
                if (delta.equals(effectiveMax)) {
                    testedMax = true;
                }
                SolveResult tweaked = tryPerturbations(
                        game, requestedSpawnRate, delta, maxPolynomials, maxRawCombinations, includeSecondBest);
                if (tweaked != null) {
                    return tweaked;
                }
            }

            if (!testedMax && effectiveMax.signum() > 0) {
                SolveResult tweaked = tryPerturbations(
                        game, requestedSpawnRate, effectiveMax, maxPolynomials, maxRawCombinations, includeSecondBest);
                if (tweaked != null) {
                    return tweaked;
                }
            }
        }

        throw new IllegalStateException(
                "Unable to find a 4-spawn rate within +/-" + maxEpsilon + " of " + requestedSpawnRate
                        + " with at most " + maxPolynomials + " optimal polynomials");
    }

    private static SolveResult tryPerturbations(
            CompiledGame game,
            Fraction requestedSpawnRate,
            Fraction delta,
            int maxPolynomials,
            long maxRawCombinations,
            boolean includeSecondBest) {
        Fraction plus = requestedSpawnRate.add(delta);
        if (plus.compareTo(Fraction.ONE) < 0) {
            try {
                return solveCompiledForExactRate(
                        game, plus, maxPolynomials, maxRawCombinations, includeSecondBest);
            } catch (PolynomialExplosionException ignored) {
                // Try negative perturbation.
            }
        }
        Fraction minus = requestedSpawnRate.subtract(delta);
        if (minus.compareTo(Fraction.ZERO) > 0) {
            try {
                return solveCompiledForExactRate(
                        game, minus, maxPolynomials, maxRawCombinations, includeSecondBest);
            } catch (PolynomialExplosionException ignored) {
                // Continue search.
            }
        }
        return null;
    }

    private static Fraction computeEffectiveMaxEpsilon(Fraction rate, Fraction maxEpsilon) {
        Fraction distToZero = rate;
        Fraction distToOne = Fraction.ONE.subtract(rate);
        Fraction minEdgeDist = distToZero.compareTo(distToOne) <= 0 ? distToZero : distToOne;
        if (minEdgeDist.isZero()) {
            return maxEpsilon;
        }
        Fraction halfEdge = minEdgeDist.divide(Fraction.of(2));
        return maxEpsilon.compareTo(halfEdge) <= 0 ? maxEpsilon : halfEdge;
    }

    private static Fraction chooseBaseEpsilon(Fraction effectiveMax) {
        if (DEFAULT_EPSILON.compareTo(effectiveMax) <= 0) {
            return DEFAULT_EPSILON;
        }
        return effectiveMax.divide(Fraction.of(4));
    }

    private static LayerGraph buildLayerGraph(Polyomino polyomino, int tileGoal) {
        int n = polyomino.size();
        int goalExp = Integer.numberOfTrailingZeros(tileGoal);
        int numPairs = n * (n - 1) / 2;

        List<Long> init22 = new ArrayList<>(numPairs);
        List<Long> init24And42 = new ArrayList<>(numPairs * 2);
        List<Long> init44 = new ArrayList<>(numPairs);

        Map<Integer, Set<Long>> layers = new HashMap<>();
        Set<Long> layer2 = new LinkedHashSet<>();
        Set<Long> layer3 = new LinkedHashSet<>();
        Set<Long> layer4 = new LinkedHashSet<>();
        layers.put(2, layer2);
        layers.put(3, layer3);
        layers.put(4, layer4);

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long b22 = setExp(setExp(0L, i, 1), j, 1);
                long b24 = setExp(setExp(0L, i, 1), j, 2);
                long b42 = setExp(setExp(0L, i, 2), j, 1);
                long b44 = setExp(setExp(0L, i, 2), j, 2);
                init22.add(b22);
                init24And42.add(b24);
                init24And42.add(b42);
                init44.add(b44);
                layer2.add(b22);
                layer3.add(b24);
                layer3.add(b42);
                layer4.add(b44);
            }
        }

        int maxLayer = 4;
        int[] scratchVals = new int[Math.max(polyomino.rows(), polyomino.cols())];
        long[] moveBuffer = new long[4];
        Direction[] directions = Direction.values();

        for (int layer = 2; layer <= maxLayer; layer++) {
            checkCancelled();
            Set<Long> current = layers.get(layer);
            if (current == null || current.isEmpty()) {
                continue;
            }
            Set<Long> nextLayer1 = null;
            Set<Long> nextLayer2 = null;

            for (long board : current) {
                if (isWinning(board, n, goalExp)) {
                    continue;
                }
                int moveCount = collectDistinctMoves(board, polyomino, directions, scratchVals, moveBuffer);
                for (int m = 0; m < moveCount; m++) {
                    long afterMove = moveBuffer[m];
                    for (int c = 0; c < n; c++) {
                        if (getExp(afterMove, c) == 0) {
                            if (nextLayer1 == null) {
                                nextLayer1 = layers.computeIfAbsent(layer + 1, k -> new HashSet<>());
                            }
                            if (nextLayer2 == null) {
                                nextLayer2 = layers.computeIfAbsent(layer + 2, k -> new HashSet<>());
                            }
                            nextLayer1.add(setExp(afterMove, c, 1));
                            nextLayer2.add(setExp(afterMove, c, 2));
                            if (layer + 2 > maxLayer) {
                                maxLayer = layer + 2;
                            }
                        }
                    }
                }
            }
        }

        return new LayerGraph(n, goalExp, numPairs, maxLayer, init22, init24And42, init44, layers);
    }

    private static SolveResult solveCompiledForExactRate(
            CompiledGame game,
            Fraction spawnRate,
            int maxPolynomials,
            long maxRawCombinations,
            boolean includeSecondBest) {

        int n = game.n;
        int totalStates = game.totalStates;
        BoundaryMode mode;
        if (spawnRate.isZero()) {
            mode = BoundaryMode.ZERO_PLUS_EPSILON;
        } else if (spawnRate.isOne()) {
            mode = BoundaryMode.ONE_MINUS_EPSILON;
        } else {
            mode = BoundaryMode.INTERIOR;
        }

        Fraction oneMinusP = Fraction.ONE.subtract(spawnRate);
        Fraction[] invK = new Fraction[n + 1];
        Fraction[] oneMinusPOverK = new Fraction[n + 1];
        Fraction[] pOverK = new Fraction[n + 1];
        for (int k = 1; k <= n; k++) {
            Fraction fk = Fraction.of(k);
            invK[k] = Fraction.of(1, k);
            oneMinusPOverK[k] = oneMinusP.divide(fk);
            pOverK[k] = spawnRate.divide(fk);
        }

        Fraction[] optRate = new Fraction[totalStates];
        Fraction[] secondMoveRate = new Fraction[totalStates];
        Fraction[][] moveRatesById = new Fraction[totalStates][];
        int[][] optimalMoveIndices = new int[totalStates][];
        @SuppressWarnings("unchecked")
        List<Polynomial>[] polysById = new List[totalStates];

        List<Polynomial> polyOneList = List.of(Polynomial.ONE);
        List<Polynomial> polyZeroList = List.of(Polynomial.ZERO);
        Polynomial[] weight2ByK = new Polynomial[n + 1];
        Polynomial[] weight4ByK = new Polynomial[n + 1];
        for (int k = 1; k <= n; k++) {
            weight2ByK[k] = Polynomial.ONE_MINUS_X.multiply(invK[k]);
            weight4ByK[k] = Polynomial.X.multiply(invK[k]);
        }

        boolean anySecondMoveCandidate = false;
        int[] bestMovesBuf = new int[4];

        for (int layer = game.maxLayer; layer >= 2; layer--) {
            checkCancelled();
            int[] ids = game.idsInLayer[layer];
            for (int id : ids) {
                if (game.isWinning[id]) {
                    optRate[id] = Fraction.ONE;
                    polysById[id] = polyOneList;
                    continue;
                }
                int[][] c2s = game.moveChildren2[id];
                int moveCount = c2s.length;
                if (moveCount == 0) {
                    optRate[id] = Fraction.ZERO;
                    polysById[id] = polyZeroList;
                    continue;
                }
                int[][] c4s = game.moveChildren4[id];

                Fraction[] moveRates = new Fraction[moveCount];
                Fraction bestRate = null;
                Fraction secondRate = null;
                int bestCount = 0;

                for (int m = 0; m < moveCount; m++) {
                    int[] m2 = c2s[m];
                    int[] m4 = c4s[m];
                    int k = m2.length;
                    Fraction sum2 = Fraction.ZERO;
                    Fraction sum4 = Fraction.ZERO;
                    for (int i = 0; i < k; i++) {
                        sum2 = sum2.add(optRate[m2[i]]);
                        sum4 = sum4.add(optRate[m4[i]]);
                    }
                    Fraction moveRate = oneMinusPOverK[k].multiply(sum2).add(pOverK[k].multiply(sum4));
                    moveRates[m] = moveRate;

                    if (bestRate == null) {
                        bestRate = moveRate;
                        bestMovesBuf[bestCount++] = m;
                    } else {
                        int cmp = moveRate.compareTo(bestRate);
                        if (cmp > 0) {
                            secondRate = bestRate;
                            bestRate = moveRate;
                            bestCount = 0;
                            bestMovesBuf[bestCount++] = m;
                        } else if (cmp == 0) {
                            bestMovesBuf[bestCount++] = m;
                        } else if (secondRate == null || moveRate.compareTo(secondRate) > 0) {
                            secondRate = moveRate;
                        }
                    }
                }

                optRate[id] = bestRate;
                moveRatesById[id] = moveRates;
                optimalMoveIndices[id] = Arrays.copyOf(bestMovesBuf, bestCount);
                if (secondRate != null) {
                    secondMoveRate[id] = secondRate;
                    anySecondMoveCandidate = true;
                }

                if (mode == BoundaryMode.INTERIOR) {
                    polysById[id] = solveInteriorPolynomials(
                            c2s, c4s, bestMovesBuf, bestCount,
                            polysById, weight2ByK, weight4ByK,
                            maxPolynomials, maxRawCombinations);
                } else {
                    polysById[id] = solveBoundaryPolynomial(
                            c2s, c4s, moveCount, mode, polysById, invK);
                }
            }

            if (layer + 2 <= game.maxLayer && layer + 2 > 4) {
                for (int doneId : game.idsInLayer[layer + 2]) {
                    polysById[doneId] = null;
                }
            }
        }

        Fraction totalRate = computeStartRate(game, spawnRate, oneMinusP, optRate);
        List<Polynomial> optimalPolys = combineStartPolynomials(
                game, spawnRate, totalRate, polysById, maxPolynomials, maxRawCombinations);

        Fraction secondHighestRate = (includeSecondBest && anySecondMoveCandidate)
                ? computeSecondHighestSuccessRate(
                        game, spawnRate, oneMinusP, totalRate,
                        oneMinusPOverK, pOverK,
                        optRate, secondMoveRate, moveRatesById, optimalMoveIndices)
                : null;

        return new SolveResult(totalRate, optimalPolys, spawnRate, secondHighestRate);
    }

    private static List<Polynomial> solveInteriorPolynomials(
            int[][] c2s,
            int[][] c4s,
            int[] bestMoves,
            int bestCount,
            List<Polynomial>[] polysById,
            Polynomial[] weight2ByK,
            Polynomial[] weight4ByK,
            int maxPolynomials,
            long maxRawCombinations) {

        long predictedRaw = 0L;
        for (int idx = 0; idx < bestCount; idx++) {
            int m = bestMoves[idx];
            int[] m2 = c2s[m];
            int[] m4 = c4s[m];
            long twoProd = 1L;
            long fourProd = 1L;
            for (int i = 0; i < m2.length; i++) {
                twoProd = saturatedMultiply(twoProd, polysById[m2[i]].size());
                fourProd = saturatedMultiply(fourProd, polysById[m4[i]].size());
            }
            predictedRaw = saturatedAdd(predictedRaw, saturatedMultiply(twoProd, fourProd));
            if (predictedRaw > maxRawCombinations) {
                throw PolynomialExplosionException.INSTANCE;
            }
        }

        Set<Polynomial> boardPolynomials = new LinkedHashSet<>();
        for (int idx = 0; idx < bestCount; idx++) {
            int m = bestMoves[idx];
            int[] m2 = c2s[m];
            int[] m4 = c4s[m];
            int k = m2.length;

            List<List<Polynomial>> twoChildPolys = new ArrayList<>(k);
            List<List<Polynomial>> fourChildPolys = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                twoChildPolys.add(polysById[m2[i]]);
                fourChildPolys.add(polysById[m4[i]]);
            }

            Set<Polynomial> twoSums = combineSums(twoChildPolys, maxPolynomials, maxRawCombinations);
            Set<Polynomial> fourSums = combineSums(fourChildPolys, maxPolynomials, maxRawCombinations);
            if (saturatedMultiply(twoSums.size(), fourSums.size()) > maxRawCombinations) {
                throw PolynomialExplosionException.INSTANCE;
            }

            Polynomial weight2 = weight2ByK[k];
            Polynomial weight4 = weight4ByK[k];

            List<Polynomial> scaled2 = new ArrayList<>(twoSums.size());
            for (Polynomial s2 : twoSums) {
                scaled2.add(s2.multiply(weight2));
            }
            List<Polynomial> scaled4 = new ArrayList<>(fourSums.size());
            for (Polynomial s4 : fourSums) {
                scaled4.add(s4.multiply(weight4));
            }

            for (Polynomial p2 : scaled2) {
                for (Polynomial p4 : scaled4) {
                    boardPolynomials.add(p2.add(p4));
                    if (boardPolynomials.size() > maxPolynomials) {
                        throw PolynomialExplosionException.INSTANCE;
                    }
                }
            }
        }

        return List.copyOf(boardPolynomials);
    }

    private static List<Polynomial> solveBoundaryPolynomial(
            int[][] c2s,
            int[][] c4s,
            int moveCount,
            BoundaryMode mode,
            List<Polynomial>[] polysById,
            Fraction[] invK) {

        Polynomial bestPoly = null;
        for (int m = 0; m < moveCount; m++) {
            int[] m2 = c2s[m];
            int[] m4 = c4s[m];
            int k = m2.length;
            Polynomial sum2 = Polynomial.ZERO;
            Polynomial sum4 = Polynomial.ZERO;
            for (int i = 0; i < k; i++) {
                sum2 = sum2.add(polysById[m2[i]].get(0));
                sum4 = sum4.add(polysById[m4[i]].get(0));
            }
            Polynomial movePoly = sum2.multiply(Polynomial.ONE_MINUS_X)
                    .add(sum4.multiply(Polynomial.X))
                    .multiply(invK[k]);

            if (bestPoly == null) {
                bestPoly = movePoly;
            } else {
                int cmp = mode == BoundaryMode.ZERO_PLUS_EPSILON
                        ? movePoly.compareAtZeroPlusEpsilon(bestPoly)
                        : movePoly.compareAtOneMinusEpsilon(bestPoly);
                if (cmp > 0) {
                    bestPoly = movePoly;
                }
            }
        }
        return List.of(bestPoly);
    }

    private static Fraction computeSecondHighestSuccessRate(
            CompiledGame game,
            Fraction spawnRate,
            Fraction oneMinusP,
            Fraction optimalStartRate,
            Fraction[] oneMinusPOverK,
            Fraction[] pOverK,
            Fraction[] optRate,
            Fraction[] secondMoveRate,
            Fraction[][] moveRatesById,
            int[][] optimalMoveIndices) {

        boolean[] isReachable = markOptimallyReachable(
                game, spawnRate, oneMinusP, optimalMoveIndices);

        boolean anyCandidate = false;
        for (int id = 0; id < game.totalStates; id++) {
            if (isReachable[id] && secondMoveRate[id] != null) {
                anyCandidate = true;
                break;
            }
        }
        if (!anyCandidate) {
            return null;
        }

        int totalStates = game.totalStates;
        int[][] sortedMoveOrder = new int[totalStates][];
        Fraction[][] sortedOptMoveRates = new Fraction[totalStates][];

        for (int id = 0; id < totalStates; id++) {
            Fraction[] rates = moveRatesById[id];
            if (rates == null) {
                continue;
            }
            int moveCount = rates.length;
            if (moveCount == 1) {
                sortedMoveOrder[id] = SINGLE_MOVE_ORDER;
                sortedOptMoveRates[id] = rates;
            } else {
                int[] order = new int[moveCount];
                Fraction[] sortedRates = rates.clone();
                for (int m = 0; m < moveCount; m++) {
                    order[m] = m;
                }
                for (int i = 1; i < moveCount; i++) {
                    Fraction rKey = sortedRates[i];
                    int mKey = order[i];
                    int j = i - 1;
                    while (j >= 0 && sortedRates[j].compareTo(rKey) < 0) {
                        sortedRates[j + 1] = sortedRates[j];
                        order[j + 1] = order[j];
                        j--;
                    }
                    sortedRates[j + 1] = rKey;
                    order[j + 1] = mKey;
                }
                sortedMoveOrder[id] = order;
                sortedOptMoveRates[id] = sortedRates;
            }
        }

        Fraction invPairs = Fraction.of(1, game.numPairs);
        Fraction w22Scalar = oneMinusP.multiply(oneMinusP).multiply(invPairs);
        Fraction w24Scalar = spawnRate.multiply(oneMinusP).multiply(invPairs);
        Fraction w44Scalar = spawnRate.multiply(spawnRate).multiply(invPairs);

        Fraction[] deltaRate = new Fraction[totalStates];
        boolean[] isChanged = new boolean[totalStates];
        int[] changedIds = new int[totalStates];
        boolean[] isDirty = new boolean[totalStates];
        int[][] dirtyIdsByLayer = new int[game.maxLayer + 1][];
        int[] dirtyCounts = new int[game.maxLayer + 1];
        for (int layer = 2; layer <= game.maxLayer; layer++) {
            dirtyIdsByLayer[layer] = new int[game.idsInLayer[layer].length];
        }

        Fraction secondHighestStartRate = null;

        for (int lockedLayer = game.maxLayer; lockedLayer >= 2; lockedLayer--) {
            checkCancelled();
            for (int lockedId : game.idsInLayer[lockedLayer]) {
                if (!isReachable[lockedId]) {
                    continue;
                }
                Fraction lockedRate = secondMoveRate[lockedId];
                if (lockedRate == null) {
                    continue;
                }

                int changedCount = 0;
                deltaRate[lockedId] = lockedRate.subtract(optRate[lockedId]);
                isChanged[lockedId] = true;
                changedIds[changedCount++] = lockedId;

                for (int pid : game.parentsById[lockedId]) {
                    if (!isDirty[pid]) {
                        isDirty[pid] = true;
                        int pLayer = game.layerById[pid];
                        dirtyIdsByLayer[pLayer][dirtyCounts[pLayer]++] = pid;
                    }
                }

                for (int layer = lockedLayer - 1; layer >= 2; layer--) {
                    int dCount = dirtyCounts[layer];
                    if (dCount == 0) {
                        continue;
                    }
                    int[] dirtyList = dirtyIdsByLayer[layer];
                    for (int idx = 0; idx < dCount; idx++) {
                        int id = dirtyList[idx];
                        isDirty[id] = false;

                        Fraction newRate = recomputeBoardBestRateDelta(
                                game.moveChildren2[id], game.moveChildren4[id],
                                sortedMoveOrder[id], sortedOptMoveRates[id],
                                oneMinusPOverK, pOverK, deltaRate, isChanged);
                        if (!newRate.equals(optRate[id])) {
                            deltaRate[id] = newRate.subtract(optRate[id]);
                            isChanged[id] = true;
                            changedIds[changedCount++] = id;
                            for (int pid : game.parentsById[id]) {
                                if (!isDirty[pid]) {
                                    isDirty[pid] = true;
                                    int pLayer = game.layerById[pid];
                                    dirtyIdsByLayer[pLayer][dirtyCounts[pLayer]++] = pid;
                                }
                            }
                        }
                    }
                    dirtyCounts[layer] = 0;
                }

                Fraction candidateStart = computeStartRateDelta(
                        optimalStartRate, game.init22Ids, game.init24Ids, game.init44Ids,
                        w22Scalar, w24Scalar, w44Scalar,
                        deltaRate, isChanged);

                for (int i = 0; i < changedCount; i++) {
                    int cid = changedIds[i];
                    deltaRate[cid] = null;
                    isChanged[cid] = false;
                }

                if (candidateStart.compareTo(optimalStartRate) < 0) {
                    if (secondHighestStartRate == null || candidateStart.compareTo(secondHighestStartRate) > 0) {
                        secondHighestStartRate = candidateStart;
                    }
                }
            }
        }

        return secondHighestStartRate;
    }

    private static Fraction recomputeBoardBestRateDelta(
            int[][] c2s,
            int[][] c4s,
            int[] moveOrder,
            Fraction[] sortedOptMoveRates,
            Fraction[] oneMinusPOverK,
            Fraction[] pOverK,
            Fraction[] deltaRate,
            boolean[] isChanged) {
        Fraction best = null;
        int moveCount = moveOrder.length;
        for (int pos = 0; pos < moveCount; pos++) {
            if (best != null && sortedOptMoveRates[pos].compareTo(best) <= 0) {
                break;
            }
            int m = moveOrder[pos];
            int[] m2 = c2s[m];
            int[] m4 = c4s[m];
            int k = m2.length;
            Fraction d2 = Fraction.ZERO;
            Fraction d4 = Fraction.ZERO;
            for (int i = 0; i < k; i++) {
                int id2 = m2[i];
                if (isChanged[id2]) {
                    d2 = d2.add(deltaRate[id2]);
                }
                int id4 = m4[i];
                if (isChanged[id4]) {
                    d4 = d4.add(deltaRate[id4]);
                }
            }
            Fraction moveRate = (d2.isZero() && d4.isZero())
                    ? sortedOptMoveRates[pos]
                    : sortedOptMoveRates[pos].add(oneMinusPOverK[k].multiply(d2)).add(pOverK[k].multiply(d4));
            if (best == null || moveRate.compareTo(best) > 0) {
                best = moveRate;
            }
        }
        return best;
    }

    private static Fraction computeStartRateDelta(
            Fraction optimalStartRate,
            int[] init22Ids,
            int[] init24Ids,
            int[] init44Ids,
            Fraction w22Scalar,
            Fraction w24Scalar,
            Fraction w44Scalar,
            Fraction[] deltaRate,
            boolean[] isChanged) {
        Fraction d22 = sumChangedDeltas(init22Ids, deltaRate, isChanged);
        Fraction d24 = sumChangedDeltas(init24Ids, deltaRate, isChanged);
        Fraction d44 = sumChangedDeltas(init44Ids, deltaRate, isChanged);
        if (d22.isZero() && d24.isZero() && d44.isZero()) {
            return optimalStartRate;
        }
        return optimalStartRate
                .add(w22Scalar.multiply(d22))
                .add(w24Scalar.multiply(d24))
                .add(w44Scalar.multiply(d44));
    }

    private static Fraction sumChangedDeltas(
            int[] ids,
            Fraction[] deltaRate,
            boolean[] isChanged) {
        Fraction sum = Fraction.ZERO;
        for (int id : ids) {
            if (isChanged[id]) {
                sum = sum.add(deltaRate[id]);
            }
        }
        return sum;
    }

    private static boolean[] markOptimallyReachable(
            CompiledGame game,
            Fraction spawnRate,
            Fraction oneMinusP,
            int[][] optimalMoveIndices) {
        boolean[] marked = new boolean[game.totalStates];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        if (oneMinusP.signum() > 0) {
            for (int id : game.init22Ids) {
                if (!marked[id]) {
                    marked[id] = true;
                    queue.add(id);
                }
            }
        }
        if (oneMinusP.signum() > 0 && spawnRate.signum() > 0) {
            for (int id : game.init24Ids) {
                if (!marked[id]) {
                    marked[id] = true;
                    queue.add(id);
                }
            }
        }
        if (spawnRate.signum() > 0) {
            for (int id : game.init44Ids) {
                if (!marked[id]) {
                    marked[id] = true;
                    queue.add(id);
                }
            }
        }

        while (!queue.isEmpty()) {
            int id = queue.poll();
            int[] optMoves = optimalMoveIndices[id];
            if (optMoves == null) {
                continue;
            }
            int[][] c2s = game.moveChildren2[id];
            int[][] c4s = game.moveChildren4[id];
            for (int m : optMoves) {
                if (oneMinusP.signum() > 0) {
                    for (int c2 : c2s[m]) {
                        if (!marked[c2]) {
                            marked[c2] = true;
                            queue.add(c2);
                        }
                    }
                }
                if (spawnRate.signum() > 0) {
                    for (int c4 : c4s[m]) {
                        if (!marked[c4]) {
                            marked[c4] = true;
                            queue.add(c4);
                        }
                    }
                }
            }
        }
        return marked;
    }

    private static Fraction computeStartRate(
            CompiledGame game,
            Fraction p,
            Fraction oneMinusP,
            Fraction[] optRate) {
        Fraction invPairs = Fraction.of(1, game.numPairs);
        Fraction sum22 = Fraction.ZERO;
        for (int id : game.init22Ids) {
            sum22 = sum22.add(optRate[id]);
        }
        Fraction sum24 = Fraction.ZERO;
        for (int id : game.init24Ids) {
            sum24 = sum24.add(optRate[id]);
        }
        Fraction sum44 = Fraction.ZERO;
        for (int id : game.init44Ids) {
            sum44 = sum44.add(optRate[id]);
        }
        return oneMinusP.multiply(oneMinusP).multiply(sum22)
                .add(p.multiply(oneMinusP).multiply(sum24))
                .add(p.multiply(p).multiply(sum44))
                .multiply(invPairs);
    }

    private static List<Polynomial> combineStartPolynomials(
            CompiledGame game,
            Fraction p,
            Fraction expectedTotalRate,
            List<Polynomial>[] polysById,
            int maxPolynomials,
            long maxRawCombinations) {

        Fraction invPairs = Fraction.of(1, game.numPairs);

        List<List<Polynomial>> polys22 = new ArrayList<>(game.init22Ids.length);
        for (int id : game.init22Ids) {
            polys22.add(polysById[id]);
        }
        List<List<Polynomial>> polys24 = new ArrayList<>(game.init24Ids.length);
        for (int id : game.init24Ids) {
            polys24.add(polysById[id]);
        }
        List<List<Polynomial>> polys44 = new ArrayList<>(game.init44Ids.length);
        for (int id : game.init44Ids) {
            polys44.add(polysById[id]);
        }

        long prod22 = saturatedProductOfSizes(polys22);
        long prod24 = saturatedProductOfSizes(polys24);
        long prod44 = saturatedProductOfSizes(polys44);
        long totalPredictedRaw = saturatedMultiply(saturatedMultiply(prod22, prod24), prod44);
        if (totalPredictedRaw > maxRawCombinations) {
            throw PolynomialExplosionException.INSTANCE;
        }

        Set<Polynomial> s22 = combineSums(polys22, maxPolynomials, maxRawCombinations);
        Set<Polynomial> s24 = combineSums(polys24, maxPolynomials, maxRawCombinations);
        Set<Polynomial> s44 = combineSums(polys44, maxPolynomials, maxRawCombinations);

        if (saturatedMultiply(s22.size(), s24.size()) > maxRawCombinations) {
            throw PolynomialExplosionException.INSTANCE;
        }

        Polynomial w22 = Polynomial.ONE_MINUS_X.multiply(Polynomial.ONE_MINUS_X).multiply(invPairs);
        Polynomial w24 = Polynomial.X.multiply(Polynomial.ONE_MINUS_X).multiply(invPairs);
        Polynomial w44 = Polynomial.X.multiply(Polynomial.X).multiply(invPairs);

        Set<Polynomial> combined22And24 = new LinkedHashSet<>();
        for (Polynomial p22 : s22) {
            Polynomial scaled22 = p22.multiply(w22);
            for (Polynomial p24 : s24) {
                combined22And24.add(scaled22.add(p24.multiply(w24)));
                if (combined22And24.size() > maxPolynomials) {
                    throw PolynomialExplosionException.INSTANCE;
                }
            }
        }

        if (saturatedMultiply(combined22And24.size(), s44.size()) > maxRawCombinations) {
            throw PolynomialExplosionException.INSTANCE;
        }

        Set<Polynomial> totalPolys = new LinkedHashSet<>();
        for (Polynomial p44 : s44) {
            Polynomial scaled44 = p44.multiply(w44);
            for (Polynomial partial : combined22And24) {
                totalPolys.add(partial.add(scaled44));
                if (totalPolys.size() > maxPolynomials) {
                    throw PolynomialExplosionException.INSTANCE;
                }
            }
        }

        List<Polynomial> resultList = List.copyOf(totalPolys);
        for (Polynomial poly : resultList) {
            if (!poly.evaluate(p).equals(expectedTotalRate)) {
                throw new IllegalStateException(
                        "Internal consistency failure: polynomial " + poly + " evaluated at " + p
                                + " gave " + poly.evaluate(p) + " instead of " + expectedTotalRate);
            }
        }
        return resultList;
    }

    private static int[] mapBoardIds(List<Long> boards, Map<Long, Integer> idByBoard) {
        int[] ids = new int[boards.size()];
        for (int i = 0; i < boards.size(); i++) {
            ids[i] = idByBoard.get(boards.get(i));
        }
        return ids;
    }

    /**
     * Folds a Cartesian sum over lists of polynomials while deduplicating after every term
     * and aborting early if either the raw work or deduplicated set size exceeds the budget.
     */
    private static Set<Polynomial> combineSums(
            List<List<Polynomial>> termOptions,
            int maxPolynomials,
            long maxRawCombinations) {
        Set<Polynomial> acc = new LinkedHashSet<>();
        acc.add(Polynomial.ZERO);
        for (List<Polynomial> options : termOptions) {
            if (options.size() == 1 && options.get(0).isZero()) {
                continue;
            }
            if (saturatedMultiply(acc.size(), options.size()) > maxRawCombinations) {
                throw PolynomialExplosionException.INSTANCE;
            }
            Set<Polynomial> next = new LinkedHashSet<>(acc.size() * options.size() * 2);
            for (Polynomial prefix : acc) {
                for (Polynomial opt : options) {
                    next.add(prefix.add(opt));
                    if (next.size() > maxPolynomials) {
                        throw PolynomialExplosionException.INSTANCE;
                    }
                }
            }
            acc = next;
        }
        return acc;
    }

    private static long saturatedProductOfSizes(List<List<Polynomial>> lists) {
        long prod = 1L;
        for (List<Polynomial> list : lists) {
            prod = saturatedMultiply(prod, list.size());
        }
        return prod;
    }

    private static long saturatedMultiply(long a, long b) {
        if (a == 0L || b == 0L) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private static long saturatedAdd(long a, long b) {
        if (a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private static int collectDistinctMoves(
            long board,
            Polyomino polyomino,
            Direction[] directions,
            int[] scratchVals,
            long[] moveBuffer) {

        int count = 0;
        for (Direction dir : directions) {
            long moved = slide(board, polyomino, dir, scratchVals);
            if (moved == board) {
                continue;
            }
            boolean duplicate = false;
            for (int i = 0; i < count; i++) {
                if (moveBuffer[i] == moved) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                moveBuffer[count++] = moved;
            }
        }
        return count;
    }

    private static long slide(long board, Polyomino polyomino, Direction dir, int[] scratchVals) {
        long next = board;
        for (int[] seg : polyomino.segments(dir)) {
            int count = 0;
            for (int cellIdx : seg) {
                int exp = getExp(board, cellIdx);
                if (exp != 0) {
                    scratchVals[count++] = exp;
                }
            }
            if (count == 0) {
                continue;
            }
            int out = 0;
            int i = 0;
            while (i < count) {
                if (i + 1 < count && scratchVals[i] == scratchVals[i + 1]) {
                    next = setExp(next, seg[out++], scratchVals[i] + 1);
                    i += 2;
                } else {
                    next = setExp(next, seg[out++], scratchVals[i]);
                    i += 1;
                }
            }
            while (out < seg.length) {
                next = setExp(next, seg[out++], 0);
            }
        }
        return next;
    }

    private static int findEmptyCells(long board, int n, int[] emptyIndices) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (getExp(board, i) == 0) {
                emptyIndices[count++] = i;
            }
        }
        return count;
    }

    private static boolean isWinning(long board, int n, int goalExp) {
        for (int i = 0; i < n; i++) {
            if (getExp(board, i) == goalExp) {
                return true;
            }
        }
        return false;
    }

    private static int getExp(long board, int cellIdx) {
        return (int) ((board >>> (cellIdx * BITS_PER_CELL)) & CELL_MASK);
    }

    private static long setExp(long board, int cellIdx, int exp) {
        int shift = cellIdx * BITS_PER_CELL;
        return (board & ~(CELL_MASK << shift)) | (((long) exp) << shift);
    }
}
