package omino2048;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Computes the piecewise-polynomial upper envelope of the optimal 2048 success probability over
 * {@code x in [0, 1]} using adaptive steps derived from the 2nd-highest policy bound:
 *
 * <ul>
 *   <li>{@code G = arcsin(2M - 1) - arcsin(2S - 1)}</li>
 *   <li>{@code H = G / (8D)} where {@code D} is the maximum policy polynomial degree</li>
 *   <li>{@code T = arcsin(sqrt(x_0))}</li>
 *   <li>Right bound {@code sin^2(T + H)} and left bound {@code sin^2(T - H)} (clamped to {@code [0, pi/2]})</li>
 * </ul>
 *
 * <p>Discovered optimal polynomials are assembled into exact algebraic intervals via primitive integer
 * Sturm root isolation and verified at all segment interiors and crossings.</p>
 */
public final class EnvelopeSolver {
    public static final double DEFAULT_MIN_STEP = 1e-4;
    private static final int MAX_REFINEMENT_ROUNDS = 8;

    public static int defaultThreads() {
        return Math.max(1, Math.min(12, Runtime.getRuntime().availableProcessors()));
    }

    public record ProgressUpdate(
            String phase,
            double progressFraction,
            int solverCalls,
            int candidateCount) {}

    private record SampleRecord(
            Fraction testedX,
            double x,
            double certifiedLeft,
            double certifiedRight,
            boolean wasCertifiedLeap,
            SolveResult result) {}

    private record ChunkResult(
            int chunkIndex,
            List<Polynomial> candidatePolys,
            List<Fraction> testedPoints,
            List<SampleRecord> samples,
            int solverCalls) {}

    private EnvelopeSolver() {}

    public static EnvelopeResult solve(Polyomino polyomino, int tileGoal) {
        return solve(
                polyomino,
                tileGoal,
                DEFAULT_MIN_STEP,
                GameSolver.DEFAULT_MAX_EPSILON,
                GameSolver.DEFAULT_MAX_POLYNOMIALS,
                defaultThreads(),
                null);
    }

    public static EnvelopeResult solve(
            Polyomino polyomino,
            int tileGoal,
            Consumer<ProgressUpdate> progressListener) {
        return solve(
                polyomino,
                tileGoal,
                DEFAULT_MIN_STEP,
                GameSolver.DEFAULT_MAX_EPSILON,
                GameSolver.DEFAULT_MAX_POLYNOMIALS,
                defaultThreads(),
                progressListener);
    }

    public static EnvelopeResult solve(
            Polyomino polyomino,
            int tileGoal,
            int threads,
            Consumer<ProgressUpdate> progressListener) {
        return solve(
                polyomino,
                tileGoal,
                DEFAULT_MIN_STEP,
                GameSolver.DEFAULT_MAX_EPSILON,
                GameSolver.DEFAULT_MAX_POLYNOMIALS,
                threads,
                progressListener);
    }

    public static EnvelopeResult solve(
            Polyomino polyomino,
            int tileGoal,
            double minStep,
            Fraction maxEpsilon,
            int maxPolynomials) {
        return solve(polyomino, tileGoal, minStep, maxEpsilon, maxPolynomials, defaultThreads(), null);
    }

    public static EnvelopeResult solve(
            Polyomino polyomino,
            int tileGoal,
            double minStep,
            Fraction maxEpsilon,
            int maxPolynomials,
            Consumer<ProgressUpdate> progressListener) {
        return solve(polyomino, tileGoal, minStep, maxEpsilon, maxPolynomials, defaultThreads(), progressListener);
    }

    public static EnvelopeResult solve(
            Polyomino polyomino,
            int tileGoal,
            double minStep,
            Fraction maxEpsilon,
            int maxPolynomials,
            int threads,
            Consumer<ProgressUpdate> progressListener) {
        Objects.requireNonNull(polyomino, "polyomino must not be null");
        Objects.requireNonNull(maxEpsilon, "maxEpsilon must not be null");
        if (Double.isNaN(minStep) || Double.isInfinite(minStep) || minStep <= 0.0 || minStep > 0.5) {
            throw new IllegalArgumentException("minStep must be in (0, 0.5], got: " + minStep);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got: " + threads);
        }

        reportProgress(progressListener, "Compiling 2048 state graph...", 0.02, 0, 0);
        GameSolver.CompiledGame game = GameSolver.compileGame(polyomino, tileGoal);
        int maxDegree = game.maxDegree;

        int workerThreads = Math.min(threads, Math.max(1, Runtime.getRuntime().availableProcessors() * 2));
        ExecutorService executor = workerThreads > 1
                ? Executors.newFixedThreadPool(workerThreads, r -> {
                    Thread t = new Thread(r, "omino-envelope-worker");
                    t.setDaemon(true);
                    return t;
                })
                : null;

        try {
            return solveWithExecutor(
                    polyomino, tileGoal, game, maxDegree, minStep, maxEpsilon, maxPolynomials,
                    workerThreads, executor, progressListener);
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    private static EnvelopeResult solveWithExecutor(
            Polyomino polyomino,
            int tileGoal,
            GameSolver.CompiledGame game,
            int maxDegree,
            double minStep,
            Fraction maxEpsilon,
            int maxPolynomials,
            int workerThreads,
            ExecutorService executor,
            Consumer<ProgressUpdate> progressListener) {

        Set<Polynomial> candidatePolys = new LinkedHashSet<>();
        TreeSet<Fraction> testedPoints = new TreeSet<>();
        List<SampleRecord> allSamples = new ArrayList<>();
        int[] totalSolverCalls = {0};

        AtomicInteger sharedCallCounter = new AtomicInteger();
        AtomicLong phase1CoveredMicros = new AtomicLong();
        Set<Polynomial> livePolysForProgress = executor != null ? ConcurrentHashMap.newKeySet() : null;
        Object progressLock = new Object();
        ConcurrentHashMap<FastEnvelopeDp.PolicySignature, Polynomial> policyCache = new ConcurrentHashMap<>();
        ThreadLocal<FastEnvelopeDp.Workspace> threadWs =
                ThreadLocal.withInitial(() -> new FastEnvelopeDp.Workspace(game));

        try {
            // Phase 1: Sweep [0, 1] using exact Δx right/left bounds across parallel sub-intervals.
            int numChunks = (executor != null && workerThreads > 1)
                    ? Math.min(64, Math.max(16, workerThreads * 4))
                    : 1;

            if (numChunks == 1) {
                ChunkResult single = sweepRange(
                        0, Fraction.ZERO, Fraction.ONE, true,
                        game, maxDegree, minStep, maxEpsilon, maxPolynomials,
                        sharedCallCounter, phase1CoveredMicros, candidatePolys, progressLock, progressListener,
                        policyCache, threadWs);
                candidatePolys.addAll(single.candidatePolys());
                testedPoints.addAll(single.testedPoints());
                allSamples.addAll(single.samples());
                totalSolverCalls[0] += single.solverCalls();
            } else {
                List<Callable<ChunkResult>> tasks = new ArrayList<>(numChunks);
                for (int c = 0; c < numChunks; c++) {
                    final int chunkIdx = c;
                    final Fraction lo = Fraction.of(c, numChunks);
                    final Fraction hi = Fraction.of(c + 1, numChunks);
                    final boolean isLast = (c == numChunks - 1);
                    tasks.add(() -> sweepRange(
                            chunkIdx, lo, hi, isLast,
                            game, maxDegree, minStep, maxEpsilon, maxPolynomials,
                            sharedCallCounter, phase1CoveredMicros, livePolysForProgress, progressLock, progressListener,
                            policyCache, threadWs));
                }
                List<ChunkResult> chunkResults = invokeAllOrdered(executor, tasks);
                chunkResults.sort(Comparator.comparingInt(ChunkResult::chunkIndex));
                for (ChunkResult cr : chunkResults) {
                    candidatePolys.addAll(cr.candidatePolys());
                    testedPoints.addAll(cr.testedPoints());
                    allSamples.addAll(cr.samples());
                    totalSolverCalls[0] += cr.solverCalls();
                }

                // Verify any uncovered certified gap across adjacent chunk seams.
                allSamples.sort(Comparator.comparing(SampleRecord::testedX));
                double gapTol = Math.max(1e-12, minStep * 0.5);
                int seamLimit = allSamples.size() - 1;
                for (int i = 0; i < seamLimit; i++) {
                    checkCancelled();
                    SampleRecord leftS = allSamples.get(i);
                    SampleRecord rightS = allSamples.get(i + 1);
                    if (leftS.wasCertifiedLeap()
                            && rightS.wasCertifiedLeap()
                            && rightS.certifiedLeft() > leftS.certifiedRight() + gapTol) {
                        fillCertifiedGap(
                                game, maxDegree, maxEpsilon, maxPolynomials, minStep,
                                leftS.certifiedRight(), rightS.certifiedLeft(),
                                candidatePolys, testedPoints, allSamples, totalSolverCalls,
                                policyCache, threadWs.get());
                    }
                }
            }

            // Phase 2 & 3: Construct exact algebraic upper envelope and verify crossings & segments.
            synchronized (progressLock) {
                reportProgress(
                        progressListener,
                        "Building exact algebraic upper envelope (" + candidatePolys.size() + " polys, "
                                + workerThreads + " threads)...",
                        0.86,
                        totalSolverCalls[0],
                        candidatePolys.size());
            }
            List<EnvelopeSegment> segments = buildExactEnvelope(candidatePolys, executor);
            for (int round = 0; round < MAX_REFINEMENT_ROUNDS; round++) {
                checkCancelled();
                final int roundNum = round + 1;
                synchronized (progressLock) {
                    reportProgress(
                            progressListener,
                            "Verifying crossings (round " + roundNum + ", " + segments.size() + " segs, "
                                    + workerThreads + " threads)...",
                            0.90 + 0.08 * ((double) round / MAX_REFINEMENT_ROUNDS),
                            totalSolverCalls[0],
                            candidatePolys.size());
                }
                List<Polynomial> newlyDiscovered = new ArrayList<>();
                boolean discoveredNew = verifyAndRefineEnvelope(
                        game, maxEpsilon, maxPolynomials,
                        segments, candidatePolys, newlyDiscovered, testedPoints, totalSolverCalls,
                        executor, policyCache, threadWs,
                        roundNum, progressLock, progressListener);
                if (!discoveredNew) {
                    break;
                }
                if (!newlyDiscovered.isEmpty()) {
                    List<EnvelopeSegment> deltaEnv = buildExactEnvelope(newlyDiscovered, executor);
                    segments = mergeEnvelopes(segments, deltaEnv, executor);
                } else {
                    segments = buildExactEnvelope(candidatePolys, executor);
                }
            }

            List<Polynomial> activePolys = extractDistinctSegmentPolynomials(segments);
            synchronized (progressLock) {
                reportProgress(
                        progressListener,
                        "Done (" + activePolys.size() + " strategies, " + segments.size() + " segments, "
                                + workerThreads + (workerThreads == 1 ? " thread)" : " threads)"),
                        1.0,
                        totalSolverCalls[0],
                        activePolys.size());
            }
            return new EnvelopeResult(
                    polyomino,
                    tileGoal,
                    maxDegree,
                    segments,
                    activePolys,
                    totalSolverCalls[0]);
        } finally {
            threadWs.remove();
        }
    }

    private static ChunkResult sweepRange(
            int chunkIndex,
            Fraction chunkLo,
            Fraction chunkHi,
            boolean includeRightEndpoint,
            GameSolver.CompiledGame game,
            int maxDegree,
            double minStep,
            Fraction maxEpsilon,
            int maxPolynomials,
            AtomicInteger sharedCallCounter,
            AtomicLong phase1CoveredMicros,
            Set<Polynomial> progressPolySet,
            Object progressLock,
            Consumer<ProgressUpdate> progressListener,
            ConcurrentHashMap<FastEnvelopeDp.PolicySignature, Polynomial> policyCache,
            ThreadLocal<FastEnvelopeDp.Workspace> threadWs) {

        FastEnvelopeDp.Workspace ws = threadWs.get();
        double chunkLoDouble = chunkLo.doubleValue();
        double chunkHiDouble = chunkHi.doubleValue();
        double coveredCursor = chunkLoDouble;

        Set<Polynomial> localPolySeen = new LinkedHashSet<>();
        TreeSet<Fraction> localTestedSet = new TreeSet<>();
        List<SampleRecord> localSamples = new ArrayList<>();
        int[] localCalls = {0};

        Fraction requested = chunkLo;
        Fraction lastProgressX = Fraction.MINUS_ONE;
        double prevCertifiedRight = chunkLoDouble;
        boolean prevWasCertifiedLeap = false;

        while (true) {
            checkCancelled();
            SolveResult res = FastEnvelopeDp.solveForEnvelope(
                    game, requested, maxEpsilon, maxPolynomials, minStep, maxDegree, policyCache, ws);
            localCalls[0]++;
            int globalCall = sharedCallCounter.incrementAndGet();
            Fraction x0Frac = res.testedSpawnRate();
            double x0 = clampUnitDouble(x0Frac.doubleValue());

            localTestedSet.add(x0Frac);
            int sizeBefore = localPolySeen.size();
            addEnvelopeRelevantPolynomials(res, localPolySeen);
            if (localPolySeen.size() > sizeBefore && progressPolySet != null && progressPolySet != localPolySeen) {
                progressPolySet.addAll(localPolySeen);
            }

            double clampedNow = Math.max(chunkLoDouble, Math.min(chunkHiDouble, x0));
            long coveredMicrosTotal = phase1CoveredMicros.get();
            if (clampedNow > coveredCursor) {
                long deltaMicros = Math.round((clampedNow - coveredCursor) * 1_000_000.0);
                coveredCursor = clampedNow;
                if (deltaMicros > 0) {
                    coveredMicrosTotal = phase1CoveredMicros.addAndGet(deltaMicros);
                }
            }
            if (progressListener != null && (globalCall <= 2 || (globalCall & 15) == 0)) {
                double frac = Math.min(1.0, coveredMicrosTotal / 1_000_000.0);
                int polyCount = progressPolySet != null ? progressPolySet.size() : localPolySeen.size();
                synchronized (progressLock) {
                    reportProgress(
                            progressListener,
                            String.format(java.util.Locale.US, "Sweeping %.1f%% (%d calls, %d polys)",
                                    frac * 100.0, globalCall, polyCount),
                            0.05 + 0.80 * frac,
                            globalCall,
                            polyCount);
                }
            }

            Bounds bounds = computeDeltaBounds(x0, res, maxDegree);
            double deltaRight = Math.max(0.0, bounds.right() - x0);
            boolean currentLeap = deltaRight >= minStep;
            localSamples.add(new SampleRecord(x0Frac, x0, bounds.left(), bounds.right(), currentLeap, res));

            // Only verify two-sided gap [prevCertifiedRight, bounds.left()] within the owned range.
            if (prevWasCertifiedLeap
                    && bounds.left() > prevCertifiedRight + Math.max(1e-12, minStep * 0.5)
                    && x0Frac.compareTo(lastProgressX) > 0) {
                fillCertifiedGap(
                        game, maxDegree, maxEpsilon, maxPolynomials, minStep,
                        prevCertifiedRight, Math.min(chunkHiDouble, bounds.left()),
                        localPolySeen, localTestedSet, localSamples, localCalls,
                        policyCache, ws);
            }
            prevCertifiedRight = Math.max(prevCertifiedRight, bounds.right());

            boolean doneChunk = includeRightEndpoint
                    ? x0Frac.equals(Fraction.ONE)
                    : x0Frac.compareTo(chunkHi) >= 0;
            if (doneChunk) {
                if (!includeRightEndpoint && chunkHiDouble > coveredCursor) {
                    long rem = Math.round((chunkHiDouble - coveredCursor) * 1_000_000.0);
                    if (rem > 0) {
                        phase1CoveredMicros.addAndGet(rem);
                    }
                }
                break;
            }

            if (x0Frac.compareTo(lastProgressX) > 0) {
                lastProgressX = x0Frac;
            } else {
                prevWasCertifiedLeap = false;
                double forced = Math.min(chunkHiDouble, lastProgressX.doubleValue() + 2.0 * minStep);
                requested = pickCleanNextFraction(lastProgressX, forced * 0.5 + lastProgressX.doubleValue() * 0.5, forced);
                continue;
            }

            double nextTarget;
            double loRatio;
            if (currentLeap) {
                nextTarget = Math.min(chunkHiDouble, x0 + 1.8 * deltaRight);
                loRatio = 0.65;
                prevWasCertifiedLeap = true;
            } else if (res.secondHighestSuccessRate() == null) {
                double step = Math.max(minStep, 1.0 / 256.0);
                nextTarget = Math.min(chunkHiDouble, x0 + step);
                loRatio = 0.8;
                prevWasCertifiedLeap = false;
            } else {
                nextTarget = Math.min(chunkHiDouble, x0 + minStep);
                loRatio = 0.8;
                prevWasCertifiedLeap = false;
            }

            double loDouble = x0 + loRatio * (nextTarget - x0);
            if (nextTarget >= chunkHiDouble - 1e-12 || loDouble >= chunkHiDouble - 1e-12) {
                requested = chunkHi;
            } else {
                requested = pickCleanNextFraction(x0Frac, loDouble, nextTarget);
                if (requested.compareTo(x0Frac) <= 0) {
                    requested = x0Frac.add(doubleToFraction(minStep));
                    if (requested.compareTo(chunkHi) >= 0) {
                        requested = chunkHi;
                    }
                }
            }
        }

        return new ChunkResult(chunkIndex, List.copyOf(localPolySeen), List.copyOf(localTestedSet), localSamples, localCalls[0]);
    }

    private static <T> List<T> invokeAllOrdered(ExecutorService executor, List<Callable<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(task));
        }
        List<T> results = new ArrayList<>(tasks.size());
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                cancelFutures(futures);
                Thread.currentThread().interrupt();
                throw new CancellationException("Solve cancelled");
            } catch (ExecutionException e) {
                cancelFutures(futures);
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException ce) {
                    throw ce;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(cause);
            }
        }
        return results;
    }

    private static <T> void cancelFutures(List<Future<T>> futures) {
        for (Future<T> f : futures) {
            f.cancel(true);
        }
    }

    private static void reportProgress(
            Consumer<ProgressUpdate> listener,
            String phase,
            double fraction,
            int calls,
            int candidates) {
        if (listener != null) {
            listener.accept(new ProgressUpdate(phase, Math.max(0.0, Math.min(1.0, fraction)), calls, candidates));
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Solve cancelled");
        }
    }

    private record Bounds(double left, double right) {}

    private static Bounds computeDeltaBounds(double x0, SolveResult res, int maxDegree) {
        Fraction sFrac = res.secondHighestSuccessRate();
        if (sFrac == null) {
            return new Bounds(x0, x0);
        }
        double m = clampUnitDouble(res.successRate().doubleValue());
        double s = clampUnitDouble(sFrac.doubleValue());
        double uM = Math.max(-1.0, Math.min(1.0, 2.0 * m - 1.0));
        double uS = Math.max(-1.0, Math.min(1.0, 2.0 * s - 1.0));
        double g = Math.max(0.0, Math.asin(uM) - Math.asin(uS));
        double h = g / (8.0 * Math.max(1, maxDegree));
        double t = Math.asin(Math.sqrt(clampUnitDouble(x0)));

        double tRight = t + h;
        double xRight = (tRight >= Math.PI / 2.0) ? 1.0 : Math.pow(Math.sin(tRight), 2);
        double tLeft = t - h;
        double xLeft = (tLeft <= 0.0) ? 0.0 : Math.pow(Math.sin(tLeft), 2);

        return new Bounds(Math.min(x0, xLeft), Math.max(x0, xRight));
    }

    private static void fillCertifiedGap(
            GameSolver.CompiledGame game,
            int maxDegree,
            Fraction maxEpsilon,
            int maxPolynomials,
            double minStep,
            double gapStart,
            double gapEnd,
            Set<Polynomial> candidatePolys,
            TreeSet<Fraction> testedPoints,
            List<SampleRecord> samples,
            int[] solverCalls,
            ConcurrentHashMap<FastEnvelopeDp.PolicySignature, Polynomial> policyCache,
            FastEnvelopeDp.Workspace ws) {

        double cursor = gapStart;
        int guard = 0;
        while (cursor < gapEnd - minStep * 0.25 && guard++ < 8) {
            checkCancelled();
            double hi = Math.min(gapEnd, cursor + Math.max(minStep, 0.5 * (gapEnd - cursor)));
            double lo = cursor + 0.5 * (hi - cursor);
            Fraction probe = pickCleanNextFraction(doubleToFraction(cursor), lo, hi);
            if (testedPoints.contains(probe)) {
                cursor = hi;
                continue;
            }
            SolveResult res = FastEnvelopeDp.solveForEnvelope(
                    game, probe, maxEpsilon, maxPolynomials, minStep, maxDegree, policyCache, ws);
            solverCalls[0]++;
            testedPoints.add(res.testedSpawnRate());
            addEnvelopeRelevantPolynomials(res, candidatePolys);

            double x = clampUnitDouble(res.testedSpawnRate().doubleValue());
            Bounds b = computeDeltaBounds(x, res, maxDegree);
            boolean isLeap = b.right() - x >= minStep;
            samples.add(new SampleRecord(res.testedSpawnRate(), x, b.left(), b.right(), isLeap, res));
            double advanced = Math.max(b.right(), x + minStep);
            if (advanced <= cursor + 1e-12) {
                break;
            }
            cursor = advanced;
        }
    }

    /**
     * Avoids dumping hundreds of tied deep-subgame variants into the pairwise root finder at degenerate
     * rational points (e.g. x = 1/2), keeping the continuation optimal immediately left and right of x0.
     */
    private static void addEnvelopeRelevantPolynomials(SolveResult res, Set<Polynomial> out) {
        List<Polynomial> polys = res.optimalPolynomials();
        if (polys.size() <= 4) {
            out.addAll(polys);
            return;
        }
        Fraction x0 = res.testedSpawnRate();
        Fraction tiny = Fraction.of(1, 1_000_000_000L);
        if (x0.compareTo(Fraction.ZERO) > 0) {
            Fraction delta = x0.compareTo(tiny) <= 0 ? x0.divide(Fraction.of(2)) : tiny;
            addMaxEvaluatedAt(polys, x0.subtract(delta), out);
        }
        if (x0.compareTo(Fraction.ONE) < 0) {
            Fraction dist = Fraction.ONE.subtract(x0);
            Fraction delta = dist.compareTo(tiny) <= 0 ? dist.divide(Fraction.of(2)) : tiny;
            addMaxEvaluatedAt(polys, x0.add(delta), out);
        }
    }

    private static void addMaxEvaluatedAt(List<Polynomial> polys, Fraction x, Set<Polynomial> out) {
        Fraction bestVal = null;
        List<Polynomial> best = new ArrayList<>();
        for (Polynomial p : polys) {
            Fraction val = p.evaluate(x);
            int cmp = bestVal == null ? 1 : val.compareTo(bestVal);
            if (cmp > 0) {
                bestVal = val;
                best.clear();
                best.add(p);
            } else if (cmp == 0 && best.size() < 4) {
                best.add(p);
            }
        }
        out.addAll(best);
    }

    static List<EnvelopeSegment> buildExactEnvelope(Set<Polynomial> polySet) {
        return buildExactEnvelope(polySet, null);
    }

    private static List<EnvelopeSegment> buildExactEnvelope(
            java.util.Collection<Polynomial> polyColl,
            ExecutorService executor) {
        List<Polynomial> polys = polyColl instanceof Set<?>
                ? new ArrayList<>(polyColl)
                : new ArrayList<>(new LinkedHashSet<>(polyColl));
        if (polys.isEmpty()) {
            return List.of();
        }
        if (polys.size() == 1) {
            return List.of(new EnvelopeSegment(
                    Fraction.ZERO, Fraction.ZERO, Fraction.ONE, Fraction.ONE, polys.get(0)));
        }
        if (polys.size() <= 4) {
            return buildSmallExactEnvelope(polys, Fraction.ZERO, Fraction.ONE);
        }

        // Bottom-up deadlock-free parallel divide-and-conquer upper envelope construction
        final int leafSize = 4;
        List<Callable<List<EnvelopeSegment>>> leafTasks = new ArrayList<>();
        for (int start = 0; start < polys.size(); start += leafSize) {
            int end = Math.min(polys.size(), start + leafSize);
            List<Polynomial> slice = polys.subList(start, end);
            leafTasks.add(() -> buildSmallExactEnvelope(slice, Fraction.ZERO, Fraction.ONE));
        }

        List<List<EnvelopeSegment>> frontier;
        if (executor != null && leafTasks.size() > 1) {
            frontier = invokeAllOrdered(executor, leafTasks);
        } else {
            frontier = new ArrayList<>(leafTasks.size());
            for (Callable<List<EnvelopeSegment>> task : leafTasks) {
                try {
                    frontier.add(task.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        while (frontier.size() > 1) {
            checkCancelled();
            int pairCount = frontier.size() / 2;
            boolean hasOdd = (frontier.size() & 1) != 0;
            ExecutorService refinerForSingleMerge = pairCount == 1 ? executor : null;
            List<Callable<List<EnvelopeSegment>>> mergeTasks = new ArrayList<>(pairCount);
            for (int i = 0; i < pairCount; i++) {
                final List<EnvelopeSegment> left = frontier.get(2 * i);
                final List<EnvelopeSegment> right = frontier.get(2 * i + 1);
                mergeTasks.add(() -> mergeEnvelopes(left, right, refinerForSingleMerge));
            }
            List<List<EnvelopeSegment>> nextFrontier;
            if (executor != null && pairCount > 1) {
                nextFrontier = invokeAllOrdered(executor, mergeTasks);
            } else {
                nextFrontier = new ArrayList<>(pairCount);
                for (Callable<List<EnvelopeSegment>> task : mergeTasks) {
                    try {
                        nextFrontier.add(task.call());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            if (hasOdd) {
                nextFrontier.add(frontier.get(frontier.size() - 1));
            }
            frontier = nextFrontier;
        }

        return frontier.get(0);
    }

    private static List<EnvelopeSegment> mergeEnvelopes(
            List<EnvelopeSegment> envA,
            List<EnvelopeSegment> envB,
            ExecutorService executor) {
        checkCancelled();
        if (envA.isEmpty()) {
            return envB;
        }
        if (envB.isEmpty()) {
            return envA;
        }

        List<ExactRootFinder.RootBracket> rawBrackets = new ArrayList<>(envA.size() + envB.size() + 2);
        for (EnvelopeSegment seg : envA) {
            rawBrackets.add(new ExactRootFinder.RootBracket(seg.startLower(), seg.startUpper()));
            rawBrackets.add(new ExactRootFinder.RootBracket(seg.endLower(), seg.endUpper()));
        }
        for (EnvelopeSegment seg : envB) {
            rawBrackets.add(new ExactRootFinder.RootBracket(seg.startLower(), seg.startUpper()));
            rawBrackets.add(new ExactRootFinder.RootBracket(seg.endLower(), seg.endUpper()));
        }
        List<ExactRootFinder.RootBracket> zones = sortAndMergeBrackets(rawBrackets);

        List<EnvelopeSegment> pieces = new ArrayList<>();
        int idxA = 0;
        int idxB = 0;

        // Track any pending uncertainty zone immediately after the last emitted piece so its
        // [lower, upper] crossing bracket is preserved rather than collapsed to an endpoint.
        Fraction pendingLo = zones.get(0).lower();
        Fraction pendingHi = zones.get(0).upper();
        Set<Polynomial> pendingPolys = new LinkedHashSet<>();
        if (pendingLo.compareTo(pendingHi) < 0) {
            collectOverlappingPolys(envA, pendingLo, pendingHi, pendingPolys);
            collectOverlappingPolys(envB, pendingLo, pendingHi, pendingPolys);
        }

        for (int zi = 0; zi + 1 < zones.size(); zi++) {
            checkCancelled();
            Fraction gLo = zones.get(zi).upper();
            Fraction gHi = zones.get(zi + 1).lower();
            Fraction nextZHi = zones.get(zi + 1).upper();

            while (idxA + 1 < envA.size() && envA.get(idxA).endLower().compareTo(gHi) < 0
                    && envA.get(idxA).endUpper().compareTo(gLo) <= 0) {
                idxA++;
            }
            while (idxB + 1 < envB.size() && envB.get(idxB).endLower().compareTo(gHi) < 0
                    && envB.get(idxB).endUpper().compareTo(gLo) <= 0) {
                idxB++;
            }
            Polynomial pA = envA.get(idxA).polynomial();
            Polynomial pB = envB.get(idxB).polynomial();

            List<EnvelopeSegment> gapSegs = resolvePairOnOpenGap(pA, pB, gLo, gHi);
            if (gapSegs == null) {
                // Entire tiny gap [gLo, gHi] lies inside one root bracket of pA - pB; keep pending zone open.
                pendingPolys.add(pA);
                pendingPolys.add(pB);
                pendingHi = nextZHi;
                if (gHi.compareTo(nextZHi) < 0) {
                    collectOverlappingPolys(envA, gHi, nextZHi, pendingPolys);
                    collectOverlappingPolys(envB, gHi, nextZHi, pendingPolys);
                }
                continue;
            }

            appendGapAcrossPendingZone(pieces, pendingLo, pendingHi, pendingPolys, gapSegs);

            // Start the next pending uncertainty zone from zones[zi + 1].
            pendingPolys.clear();
            EnvelopeSegment tail = pieces.get(pieces.size() - 1);
            pendingLo = tail.endLower().compareTo(gHi) < 0 ? tail.endLower() : gHi;
            pendingHi = nextZHi;
            if (pendingLo.compareTo(pendingHi) < 0) {
                pendingPolys.add(tail.polynomial());
                collectOverlappingPolys(envA, pendingLo, pendingHi, pendingPolys);
                collectOverlappingPolys(envB, pendingLo, pendingHi, pendingPolys);
            }
        }

        // Resolve any trailing zone ending at 1 (only non-empty when the last zone has pendingLo < 1).
        if (pieces.isEmpty()) {
            List<Polynomial> all = new ArrayList<>(pendingPolys);
            if (all.isEmpty()) {
                all.add(envA.get(0).polynomial());
                all.add(envB.get(0).polynomial());
            }
            Polynomial winner = findExactWinner(all, Fraction.of(1, 2), Fraction.ZERO, Fraction.ONE);
            return List.of(new EnvelopeSegment(Fraction.ZERO, Fraction.ZERO, Fraction.ONE, Fraction.ONE, winner));
        }
        if (pendingLo.compareTo(pendingHi) < 0) {
            finalizeTrailingZone(pieces, pendingLo, pendingHi, pendingPolys);
        } else {
            EnvelopeSegment last = pieces.get(pieces.size() - 1);
            pieces.set(pieces.size() - 1, new EnvelopeSegment(
                    last.startLower(), last.startUpper(), Fraction.ONE, Fraction.ONE, last.polynomial()));
        }

        return finalizeAndRefineSegments(pieces, executor);
    }

    private static void collectOverlappingPolys(
            List<EnvelopeSegment> env,
            Fraction lo,
            Fraction hi,
            Set<Polynomial> out) {
        int low = 0;
        int high = env.size() - 1;
        int first = env.size();
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (env.get(mid).endUpper().compareTo(lo) >= 0) {
                first = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        for (int i = first; i < env.size() && env.get(i).startLower().compareTo(hi) <= 0; i++) {
            out.add(env.get(i).polynomial());
        }
    }

    /**
     * Resolves a single pair (pA, pB) on [gLo, gHi]. Returns {@code null} only if a single root bracket
     * covers the entire [gLo, gHi] interval without any interior sampleable sub-interval.
     */
    private static List<EnvelopeSegment> resolvePairOnOpenGap(
            Polynomial pA,
            Polynomial pB,
            Fraction gLo,
            Fraction gHi) {
        if (pA.equals(pB)) {
            return List.of(new EnvelopeSegment(gLo, gLo, gHi, gHi, pA));
        }
        Polynomial diff = pA.subtract(pB);
        List<ExactRootFinder.RootBracket> roots = ExactRootFinder.findDistinctRealRootsInInterval(diff, gLo, gHi);
        if (roots.isEmpty()) {
            Fraction mid = simplestBetweenOrMid(gLo, gHi);
            Polynomial winner = findExactWinner(List.of(pA, pB), mid, gLo, gHi);
            return List.of(new EnvelopeSegment(gLo, gLo, gHi, gHi, winner));
        }

        List<ExactRootFinder.RootBracket> raw = new ArrayList<>(roots.size() + 2);
        raw.add(new ExactRootFinder.RootBracket(gLo, gLo));
        raw.add(new ExactRootFinder.RootBracket(gHi, gHi));
        for (ExactRootFinder.RootBracket b : roots) {
            Fraction cLo = b.lower().compareTo(gLo) < 0 ? gLo : b.lower();
            Fraction cHi = b.upper().compareTo(gHi) > 0 ? gHi : b.upper();
            if (cLo.compareTo(cHi) <= 0) {
                raw.add(new ExactRootFinder.RootBracket(cLo, cHi));
            }
        }
        List<ExactRootFinder.RootBracket> merged = sortAndMergeBrackets(raw);
        if (merged.size() <= 1) {
            return null;
        }

        List<Polynomial> pair = List.of(pA, pB);
        List<EnvelopeSegment> segs = new ArrayList<>(merged.size() - 1);
        for (int i = 0; i + 1 < merged.size(); i++) {
            ExactRootFinder.RootBracket leftB = merged.get(i);
            ExactRootFinder.RootBracket rightB = merged.get(i + 1);
            if (leftB.upper().compareTo(rightB.lower()) >= 0) {
                continue;
            }
            Fraction mid = simplestBetweenOrMid(leftB.upper(), rightB.lower());
            Polynomial winner = findExactWinner(pair, mid, leftB.upper(), rightB.lower());
            appendMergedPiece(segs, leftB.lower(), leftB.upper(), rightB.lower(), rightB.upper(), winner);
        }
        return segs.isEmpty() ? null : segs;
    }

    private static Fraction simplestBetweenOrMid(Fraction a, Fraction b) {
        Fraction mid = ExactRootFinder.simplestRationalStrictlyBetween(a, b);
        return mid != null ? mid : a.add(b).divide(Fraction.of(2));
    }

    private static void appendGapAcrossPendingZone(
            List<EnvelopeSegment> pieces,
            Fraction pendingLo,
            Fraction pendingHi,
            Set<Polynomial> pendingPolys,
            List<EnvelopeSegment> gapSegs) {
        EnvelopeSegment first = gapSegs.get(0);
        int startIdx = 0;

        if (pieces.isEmpty()) {
            if (pendingLo.compareTo(first.startUpper()) < 0 && !pendingPolys.isEmpty()) {
                List<Polynomial> locals = new ArrayList<>(pendingPolys);
                if (!locals.contains(first.polynomial())) {
                    locals.add(first.polynomial());
                }
                List<EnvelopeSegment> bridge = resolveZoneChain(
                        null, first.polynomial(), locals, pendingLo, first.startUpper());
                pieces.addAll(bridge);
                if (!pieces.isEmpty() && pieces.get(pieces.size() - 1).polynomial().equals(first.polynomial())) {
                    EnvelopeSegment prev = pieces.get(pieces.size() - 1);
                    pieces.set(pieces.size() - 1, new EnvelopeSegment(
                            prev.startLower(), prev.startUpper(), first.endLower(), first.endUpper(), prev.polynomial()));
                    startIdx = 1;
                } else if (pieces.isEmpty()) {
                    pieces.add(new EnvelopeSegment(
                            pendingLo, pendingLo, first.endLower(), first.endUpper(), first.polynomial()));
                    startIdx = 1;
                }
            } else if (pendingLo.compareTo(first.startLower()) < 0) {
                pieces.add(new EnvelopeSegment(
                        pendingLo, pendingLo, first.endLower(), first.endUpper(), first.polynomial()));
                startIdx = 1;
            }
        } else {
            EnvelopeSegment prev = pieces.get(pieces.size() - 1);
            Polynomial pL = prev.polynomial();
            Polynomial pR = first.polynomial();
            Fraction jLo = pendingLo;
            Fraction jHi = first.startUpper().compareTo(pendingHi) > 0 ? first.startUpper() : pendingHi;

            if (jLo.compareTo(jHi) >= 0) {
                if (pL.equals(pR)) {
                    pieces.set(pieces.size() - 1, new EnvelopeSegment(
                            prev.startLower(), prev.startUpper(), first.endLower(), first.endUpper(), pL));
                    startIdx = 1;
                } else {
                    pieces.set(pieces.size() - 1, new EnvelopeSegment(
                            prev.startLower(), prev.startUpper(), jLo, jLo, pL));
                    pieces.add(new EnvelopeSegment(jLo, jLo, first.endLower(), first.endUpper(), pR));
                    startIdx = 1;
                }
            } else {
                List<Polynomial> locals = new ArrayList<>(pendingPolys);
                if (!locals.contains(pL)) {
                    locals.add(pL);
                }
                if (!locals.contains(pR)) {
                    locals.add(pR);
                }
                List<EnvelopeSegment> midChain = resolveZoneChain(pL, pR, locals, jLo, jHi);
                if (midChain.isEmpty()) {
                    if (pL.equals(pR)) {
                        pieces.set(pieces.size() - 1, new EnvelopeSegment(
                                prev.startLower(), prev.startUpper(), first.endLower(), first.endUpper(), pL));
                    } else {
                        pieces.set(pieces.size() - 1, new EnvelopeSegment(
                                prev.startLower(), prev.startUpper(), jLo, jHi, pL));
                        pieces.add(new EnvelopeSegment(jLo, jHi, first.endLower(), first.endUpper(), pR));
                    }
                    startIdx = 1;
                } else {
                    Fraction firstEndLo = midChain.get(0).startLower();
                    Fraction firstEndHi = midChain.get(0).startUpper();
                    pieces.set(pieces.size() - 1, new EnvelopeSegment(
                            prev.startLower(), prev.startUpper(), firstEndLo, firstEndHi, pL));
                    for (EnvelopeSegment m : midChain) {
                        appendMergedPiece(pieces, m.startLower(), m.startUpper(), m.endLower(), m.endUpper(), m.polynomial());
                    }
                    EnvelopeSegment lastMid = pieces.get(pieces.size() - 1);
                    pieces.add(new EnvelopeSegment(
                            lastMid.endLower(), lastMid.endUpper(), first.endLower(), first.endUpper(), pR));
                    startIdx = 1;
                }
            }
        }

        for (int i = startIdx; i < gapSegs.size(); i++) {
            EnvelopeSegment s = gapSegs.get(i);
            appendMergedPiece(pieces, s.startLower(), s.startUpper(), s.endLower(), s.endUpper(), s.polynomial());
        }
    }

    /**
     * Finds any strictly-interior winning segments inside an uncertainty zone [jLo, jHi] between pL and pR
     * without collapsing uncertainty brackets at jLo or jHi.
     */
    private static List<EnvelopeSegment> resolveZoneChain(
            Polynomial pL,
            Polynomial pR,
            List<Polynomial> locals,
            Fraction jLo,
            Fraction jHi) {
        if (locals.size() <= 2 && pL != null && pR != null) {
            return List.of();
        }
        List<ExactRootFinder.RootBracket> innerRaw = new ArrayList<>();
        for (int i = 0; i < locals.size(); i++) {
            Polynomial pi = locals.get(i);
            for (int j = i + 1; j < locals.size(); j++) {
                for (ExactRootFinder.RootBracket b :
                        ExactRootFinder.findDistinctRealRootsInInterval(pi.subtract(locals.get(j)), jLo, jHi)) {
                    Fraction cLo = b.lower().compareTo(jLo) < 0 ? jLo : b.lower();
                    Fraction cHi = b.upper().compareTo(jHi) > 0 ? jHi : b.upper();
                    if (cLo.compareTo(cHi) <= 0) {
                        innerRaw.add(new ExactRootFinder.RootBracket(cLo, cHi));
                    }
                }
            }
        }
        List<ExactRootFinder.RootBracket> merged = sortAndMergeBrackets(innerRaw);
        List<EnvelopeSegment> chain = new ArrayList<>();
        if (pL == null && !merged.isEmpty() && jLo.compareTo(merged.get(0).lower()) < 0) {
            Fraction mid = simplestBetweenOrMid(jLo, merged.get(0).lower());
            Polynomial w = findExactWinner(locals, mid, jLo, merged.get(0).lower());
            chain.add(new EnvelopeSegment(jLo, jLo, merged.get(0).lower(), merged.get(0).upper(), w));
        }
        for (int i = 0; i + 1 < merged.size(); i++) {
            ExactRootFinder.RootBracket leftB = merged.get(i);
            ExactRootFinder.RootBracket rightB = merged.get(i + 1);
            if (leftB.upper().compareTo(rightB.lower()) >= 0) {
                continue;
            }
            Fraction mid = simplestBetweenOrMid(leftB.upper(), rightB.lower());
            Polynomial w = findExactWinner(locals, mid, leftB.upper(), rightB.lower());
            chain.add(new EnvelopeSegment(leftB.lower(), leftB.upper(), rightB.lower(), rightB.upper(), w));
        }
        // Trim chain ends equal to pL or pR so adjacent equal polynomials don't emit spurious joints.
        if (pL != null && !chain.isEmpty() && chain.get(0).polynomial().equals(pL)) {
            chain.remove(0);
        }
        if (pR != null && !chain.isEmpty() && chain.get(chain.size() - 1).polynomial().equals(pR)) {
            chain.remove(chain.size() - 1);
        }
        return chain;
    }

    private static void finalizeTrailingZone(
            List<EnvelopeSegment> pieces,
            Fraction pendingLo,
            Fraction pendingHi,
            Set<Polynomial> pendingPolys) {
        EnvelopeSegment last = pieces.get(pieces.size() - 1);
        List<Polynomial> locals = new ArrayList<>(pendingPolys);
        if (!locals.contains(last.polynomial())) {
            locals.add(last.polynomial());
        }
        List<EnvelopeSegment> chain = resolveZoneChain(last.polynomial(), null, locals, pendingLo, pendingHi);
        if (chain.isEmpty()) {
            pieces.set(pieces.size() - 1, new EnvelopeSegment(
                    last.startLower(), last.startUpper(), pendingHi, pendingHi, last.polynomial()));
            return;
        }
        pieces.set(pieces.size() - 1, new EnvelopeSegment(
                last.startLower(), last.startUpper(), chain.get(0).startLower(), chain.get(0).startUpper(), last.polynomial()));
        for (EnvelopeSegment m : chain) {
            appendMergedPiece(pieces, m.startLower(), m.startUpper(), m.endLower(), m.endUpper(), m.polynomial());
        }
        EnvelopeSegment end = pieces.get(pieces.size() - 1);
        pieces.set(pieces.size() - 1, new EnvelopeSegment(
                end.startLower(), end.startUpper(), pendingHi, pendingHi, end.polynomial()));
    }

    private static void appendMergedPiece(
            List<EnvelopeSegment> pieces,
            Fraction sLo,
            Fraction sHi,
            Fraction eLo,
            Fraction eHi,
            Polynomial poly) {
        if (!pieces.isEmpty()) {
            EnvelopeSegment prev = pieces.get(pieces.size() - 1);
            if (prev.polynomial().equals(poly)) {
                pieces.set(pieces.size() - 1, new EnvelopeSegment(
                        prev.startLower(), prev.startUpper(), eLo, eHi, poly));
                return;
            }
        }
        pieces.add(new EnvelopeSegment(sLo, sHi, eLo, eHi, poly));
    }

    private static List<EnvelopeSegment> buildSmallExactEnvelope(
            List<Polynomial> polys,
            Fraction lo,
            Fraction hi) {
        if (polys.size() == 1) {
            return List.of(new EnvelopeSegment(lo, lo, hi, hi, polys.get(0)));
        }

        List<ExactRootFinder.RootBracket> rawBrackets = new ArrayList<>();
        rawBrackets.add(new ExactRootFinder.RootBracket(lo, lo));
        rawBrackets.add(new ExactRootFinder.RootBracket(hi, hi));

        for (int i = 0; i < polys.size(); i++) {
            Polynomial pi = polys.get(i);
            for (int j = i + 1; j < polys.size(); j++) {
                checkCancelled();
                Polynomial diff = pi.subtract(polys.get(j));
                for (ExactRootFinder.RootBracket b : ExactRootFinder.findDistinctRealRootsInInterval(diff, lo, hi)) {
                    Fraction cLo = b.lower().compareTo(lo) < 0 ? lo : b.lower();
                    Fraction cHi = b.upper().compareTo(hi) > 0 ? hi : b.upper();
                    if (cLo.compareTo(cHi) <= 0) {
                        rawBrackets.add(new ExactRootFinder.RootBracket(cLo, cHi));
                    }
                }
            }
        }

        List<ExactRootFinder.RootBracket> merged = sortAndMergeBrackets(rawBrackets);
        List<EnvelopeSegment> rawSegments = new ArrayList<>();
        for (int i = 0; i + 1 < merged.size(); i++) {
            ExactRootFinder.RootBracket leftB = merged.get(i);
            ExactRootFinder.RootBracket rightB = merged.get(i + 1);
            if (leftB.upper().compareTo(rightB.lower()) >= 0) {
                continue;
            }
            Fraction mid = ExactRootFinder.simplestRationalStrictlyBetween(leftB.upper(), rightB.lower());
            if (mid == null) {
                mid = leftB.upper().add(rightB.lower()).divide(Fraction.of(2));
            }
            Polynomial winner = findExactWinner(polys, mid, leftB.upper(), rightB.lower());
            appendMergedPiece(rawSegments, leftB.lower(), leftB.upper(), rightB.lower(), rightB.upper(), winner);
        }

        if (rawSegments.isEmpty()) {
            Fraction mid = lo.add(hi).divide(Fraction.of(2));
            Polynomial winner = findExactWinner(polys, mid, lo, hi);
            return List.of(new EnvelopeSegment(lo, lo, hi, hi, winner));
        }
        return finalizeAndRefineSegments(rawSegments, null);
    }

    private static List<ExactRootFinder.RootBracket> sortAndMergeBrackets(List<ExactRootFinder.RootBracket> rawBrackets) {
        rawBrackets.sort(Comparator.comparing(ExactRootFinder.RootBracket::lower)
                .thenComparing(ExactRootFinder.RootBracket::upper));
        List<ExactRootFinder.RootBracket> merged = new ArrayList<>(rawBrackets.size());
        for (ExactRootFinder.RootBracket b : rawBrackets) {
            if (merged.isEmpty()) {
                merged.add(b);
            } else {
                ExactRootFinder.RootBracket prev = merged.get(merged.size() - 1);
                if (b.lower().compareTo(prev.upper()) <= 0) {
                    Fraction hi = b.upper().compareTo(prev.upper()) > 0 ? b.upper() : prev.upper();
                    merged.set(merged.size() - 1, new ExactRootFinder.RootBracket(prev.lower(), hi));
                } else {
                    merged.add(b);
                }
            }
        }
        return merged;
    }

    private static List<EnvelopeSegment> finalizeAndRefineSegments(
            List<EnvelopeSegment> rawSegments,
            ExecutorService executor) {
        if (rawSegments.size() <= 1) {
            return List.copyOf(rawSegments);
        }

        Fraction outerLo = rawSegments.get(0).startLower();
        Fraction outerHi = rawSegments.get(rawSegments.size() - 1).endUpper();
        int crossingCount = rawSegments.size() - 1;
        List<ExactRootFinder.RootBracket> interiorBoundaries;

        if (executor != null && crossingCount >= 8) {
            List<Callable<ExactRootFinder.RootBracket>> refTasks = new ArrayList<>(crossingCount);
            for (int i = 0; i < crossingCount; i++) {
                final EnvelopeSegment leftSeg = rawSegments.get(i);
                final EnvelopeSegment rightSeg = rawSegments.get(i + 1);
                refTasks.add(() -> refineAdjacentCrossingBracket(
                        leftSeg.polynomial(), rightSeg.polynomial(), leftSeg.endLower(), leftSeg.endUpper()));
            }
            interiorBoundaries = invokeAllOrdered(executor, refTasks);
        } else {
            interiorBoundaries = new ArrayList<>(crossingCount);
            for (int i = 0; i < crossingCount; i++) {
                EnvelopeSegment leftSeg = rawSegments.get(i);
                EnvelopeSegment rightSeg = rawSegments.get(i + 1);
                interiorBoundaries.add(refineAdjacentCrossingBracket(
                        leftSeg.polynomial(), rightSeg.polynomial(), leftSeg.endLower(), leftSeg.endUpper()));
            }
        }

        List<EnvelopeSegment> refined = new ArrayList<>(rawSegments.size());
        for (int i = 0; i < rawSegments.size(); i++) {
            Fraction sLo = i == 0 ? outerLo : interiorBoundaries.get(i - 1).lower();
            Fraction sHi = i == 0 ? outerLo : interiorBoundaries.get(i - 1).upper();
            Fraction eLo = i == rawSegments.size() - 1 ? outerHi : interiorBoundaries.get(i).lower();
            Fraction eHi = i == rawSegments.size() - 1 ? outerHi : interiorBoundaries.get(i).upper();
            refined.add(new EnvelopeSegment(sLo, sHi, eLo, eHi, rawSegments.get(i).polynomial()));
        }
        return List.copyOf(refined);
    }

    private static ExactRootFinder.RootBracket refineAdjacentCrossingBracket(
            Polynomial pLeft,
            Polynomial pRight,
            Fraction approxLo,
            Fraction approxHi) {
        Polynomial diff = pLeft.subtract(pRight);
        if (approxLo.equals(approxHi) && diff.evaluate(approxLo).isZero()) {
            return new ExactRootFinder.RootBracket(approxLo, approxHi);
        }
        List<ExactRootFinder.RootBracket> pairRoots =
                ExactRootFinder.findDistinctRealRootsInInterval(diff, approxLo, approxHi);
        for (ExactRootFinder.RootBracket root : pairRoots) {
            if (root.upper().compareTo(approxLo) >= 0 && root.lower().compareTo(approxHi) <= 0) {
                return root;
            }
        }
        return new ExactRootFinder.RootBracket(approxLo, approxHi);
    }

    private static Polynomial findExactWinner(
            List<Polynomial> polys,
            Fraction mid,
            Fraction intervalLo,
            Fraction intervalHi) {
        Polynomial best = null;
        Fraction bestVal = null;
        List<Polynomial> ties = null;
        for (Polynomial p : polys) {
            Fraction val = p.evaluate(mid);
            int cmp = bestVal == null ? 1 : val.compareTo(bestVal);
            if (cmp > 0) {
                bestVal = val;
                best = p;
                ties = null;
            } else if (cmp == 0) {
                if (ties == null) {
                    ties = new ArrayList<>();
                    ties.add(best);
                }
                ties.add(p);
            }
        }
        if (ties != null && ties.size() > 1) {
            Fraction probe = mid.add(intervalHi).divide(Fraction.of(2));
            if (probe.compareTo(intervalHi) < 0) {
                Polynomial bestTie = ties.get(0);
                Fraction bestTieVal = bestTie.evaluate(probe);
                for (int i = 1; i < ties.size(); i++) {
                    Fraction v = ties.get(i).evaluate(probe);
                    if (v.compareTo(bestTieVal) > 0) {
                        bestTieVal = v;
                        bestTie = ties.get(i);
                    }
                }
                return bestTie;
            }
        }
        return best;
    }

    private static boolean verifyAndRefineEnvelope(
            GameSolver.CompiledGame game,
            Fraction maxEpsilon,
            int maxPolynomials,
            List<EnvelopeSegment> segments,
            Set<Polynomial> candidatePolys,
            List<Polynomial> newlyDiscoveredOut,
            TreeSet<Fraction> testedPoints,
            int[] solverCalls,
            ExecutorService executor,
            ConcurrentHashMap<FastEnvelopeDp.PolicySignature, Polynomial> policyCache,
            ThreadLocal<FastEnvelopeDp.Workspace> threadWs,
            int roundNum,
            Object progressLock,
            Consumer<ProgressUpdate> progressListener) {

        boolean discovered = false;
        List<Fraction> checkPoints = new ArrayList<>();

        // 1. Check an interior sample point inside each segment not already containing a Phase 1 sample.
        for (EnvelopeSegment seg : segments) {
            if (seg.startUpper().compareTo(seg.endLower()) < 0) {
                boolean covered = !testedPoints.subSet(seg.startUpper(), false, seg.endLower(), false).isEmpty();
                if (!covered || seg.endDouble() - seg.startDouble() < DEFAULT_MIN_STEP * 2.0) {
                    Fraction mid = pickCleanInteriorPoint(seg.startUpper(), seg.endLower());
                    if (mid != null && !testedPoints.contains(mid)) {
                        checkPoints.add(mid);
                    }
                }
            }
        }

        // 2. Check right at (or infinitesimally near) every interior crossing breakpoint to catch narrow slivers.
        Fraction crossingWindow = Fraction.of(1, 100_000_000L);
        for (int i = 0; i + 1 < segments.size(); i++) {
            EnvelopeSegment leftSeg = segments.get(i);
            EnvelopeSegment rightSeg = segments.get(i + 1);
            Fraction cLo = leftSeg.endLower();
            Fraction cHi = leftSeg.endUpper();
            Fraction probe;
            if (cLo.equals(cHi)) {
                probe = cLo;
            } else {
                Fraction wLo = cLo.subtract(crossingWindow);
                Fraction wHi = cHi.add(crossingWindow);
                if (wLo.compareTo(leftSeg.startUpper()) <= 0) {
                    wLo = leftSeg.startUpper();
                }
                if (wHi.compareTo(rightSeg.endLower()) >= 0) {
                    wHi = rightSeg.endLower();
                }
                probe = ExactRootFinder.simplestRationalStrictlyBetween(wLo, wHi, 48);
                if (probe == null) {
                    probe = cLo.add(cHi).divide(Fraction.of(2));
                }
            }
            if (!testedPoints.contains(probe)) {
                checkPoints.add(probe);
            }
        }

        // Deduplicate checkpoints in case adjacent checks fell on the same rational
        List<Fraction> uniqueCheckPoints = new ArrayList<>(new LinkedHashSet<>(checkPoints));
        int totalChecks = uniqueCheckPoints.size();
        AtomicInteger doneChecks = new AtomicInteger();

        record VerifyProbeResult(SolveResult actual, boolean beatsEnvelope) {}

        List<VerifyProbeResult> results;
        if (executor != null && totalChecks > 1) {
            List<Callable<VerifyProbeResult>> probeTasks = new ArrayList<>(totalChecks);
            for (Fraction pt : uniqueCheckPoints) {
                probeTasks.add(() -> {
                    checkCancelled();
                    SolveResult actual = FastEnvelopeDp.solveForEnvelope(
                            game, pt, maxEpsilon, maxPolynomials, 0.0, game.maxDegree, policyCache, threadWs.get());
                    Fraction segMax = evaluateEnvelopeSegmentsAt(segments, actual.testedSpawnRate());
                    boolean beatsSeg = actual.successRate().compareTo(segMax) > 0;
                    int done = doneChecks.incrementAndGet();
                    if (progressListener != null && (done == totalChecks || (done & 15) == 0)) {
                        synchronized (progressLock) {
                            reportProgress(
                                    progressListener,
                                    String.format(java.util.Locale.US,
                                            "Verifying crossings (round %d: %d/%d)",
                                            roundNum, done, totalChecks),
                                    0.90 + 0.08 * ((double) (roundNum - 1) / MAX_REFINEMENT_ROUNDS)
                                            + 0.08 * ((double) done / Math.max(1, totalChecks)) / MAX_REFINEMENT_ROUNDS,
                                    solverCalls[0] + done,
                                    candidatePolys.size());
                        }
                    }
                    return new VerifyProbeResult(actual, beatsSeg);
                });
            }
            results = invokeAllOrdered(executor, probeTasks);
        } else {
            results = new ArrayList<>(totalChecks);
            FastEnvelopeDp.Workspace ws = threadWs.get();
            for (Fraction pt : uniqueCheckPoints) {
                checkCancelled();
                SolveResult actual = FastEnvelopeDp.solveForEnvelope(
                        game, pt, maxEpsilon, maxPolynomials, 0.0, game.maxDegree, policyCache, ws);
                Fraction segMax = evaluateEnvelopeSegmentsAt(segments, actual.testedSpawnRate());
                results.add(new VerifyProbeResult(actual, actual.successRate().compareTo(segMax) > 0));
            }
        }

        for (VerifyProbeResult res : results) {
            SolveResult actual = res.actual();
            solverCalls[0]++;
            Fraction xTested = actual.testedSpawnRate();
            testedPoints.add(xTested);

            if (res.beatsEnvelope()) {
                Fraction currentEnvMax = maxEvaluate(candidatePolys, xTested);
                if (actual.successRate().compareTo(currentEnvMax) > 0) {
                    discovered = true;
                    Set<Polynomial> addedHere = new LinkedHashSet<>();
                    addEnvelopeRelevantPolynomials(actual, addedHere);
                    for (Polynomial np : addedHere) {
                        if (candidatePolys.add(np)) {
                            newlyDiscoveredOut.add(np);
                        }
                    }
                }
            }
        }
        return discovered;
    }

    private static Fraction evaluateEnvelopeSegmentsAt(List<EnvelopeSegment> segments, Fraction x) {
        int lo = 0;
        int hi = segments.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            EnvelopeSegment s = segments.get(mid);
            if (x.compareTo(s.startLower()) < 0) {
                hi = mid - 1;
            } else if (x.compareTo(s.endUpper()) > 0) {
                lo = mid + 1;
            } else {
                Fraction best = s.polynomial().evaluate(x);
                if (mid > 0 && x.compareTo(segments.get(mid - 1).endUpper()) <= 0) {
                    Fraction leftVal = segments.get(mid - 1).polynomial().evaluate(x);
                    if (leftVal.compareTo(best) > 0) {
                        best = leftVal;
                    }
                }
                if (mid + 1 < segments.size() && x.compareTo(segments.get(mid + 1).startLower()) >= 0) {
                    Fraction rightVal = segments.get(mid + 1).polynomial().evaluate(x);
                    if (rightVal.compareTo(best) > 0) {
                        best = rightVal;
                    }
                }
                return best;
            }
        }
        return Fraction.ZERO;
    }

    private static Fraction maxEvaluate(Set<Polynomial> polys, Fraction x) {
        Fraction best = null;
        for (Polynomial p : polys) {
            Fraction val = p.evaluate(x);
            if (best == null || val.compareTo(best) > 0) {
                best = val;
            }
        }
        return best;
    }

    private static List<Polynomial> extractDistinctSegmentPolynomials(List<EnvelopeSegment> segments) {
        Set<Polynomial> distinct = new LinkedHashSet<>(segments.size());
        for (EnvelopeSegment seg : segments) {
            distinct.add(seg.polynomial());
        }
        return List.copyOf(distinct);
    }

    private static Fraction pickCleanInteriorPoint(Fraction lo, Fraction hi) {
        if (lo.compareTo(hi) >= 0) {
            return null;
        }
        Fraction span = hi.subtract(lo);
        Fraction innerLo = lo.add(span.divide(Fraction.of(4)));
        Fraction innerHi = hi.subtract(span.divide(Fraction.of(4)));
        Fraction clean = ExactRootFinder.simplestRationalStrictlyBetween(innerLo, innerHi);
        if (clean != null) {
            return clean;
        }
        clean = ExactRootFinder.simplestRationalStrictlyBetween(lo, hi);
        return clean != null ? clean : lo.add(hi).divide(Fraction.of(2));
    }

    private static Fraction pickCleanNextFraction(Fraction strictlyAfter, double loDouble, double hiDouble) {
        if (hiDouble >= 1.0) {
            hiDouble = 1.0;
        }
        if (loDouble >= hiDouble) {
            Fraction f = doubleToFraction(hiDouble);
            return f.compareTo(Fraction.ONE) >= 0 ? Fraction.ONE : f;
        }
        Fraction loFrac = doubleToFraction(loDouble);
        if (loFrac.compareTo(strictlyAfter) < 0) {
            loFrac = strictlyAfter;
        }
        Fraction hiFrac = doubleToFraction(hiDouble);
        if (loFrac.compareTo(hiFrac) >= 0) {
            return hiFrac.compareTo(Fraction.ONE) >= 0 ? Fraction.ONE : hiFrac;
        }
        Fraction simplest = ExactRootFinder.simplestRationalStrictlyBetween(loFrac, hiFrac);
        if (simplest != null && simplest.compareTo(strictlyAfter) > 0) {
            return simplest;
        }
        Fraction mid = loFrac.add(hiFrac).divide(Fraction.of(2));
        if (mid.compareTo(strictlyAfter) > 0 && mid.compareTo(Fraction.ONE) < 0) {
            return mid;
        }
        return hiFrac.compareTo(Fraction.ONE) >= 0 ? Fraction.ONE : hiFrac;
    }

    private static Fraction doubleToFraction(double d) {
        if (d <= 0.0) {
            return Fraction.ZERO;
        }
        if (d >= 1.0) {
            return Fraction.ONE;
        }
        BigDecimal bd = BigDecimal.valueOf(d);
        return Fraction.of(bd.unscaledValue(), java.math.BigInteger.TEN.pow(Math.max(0, bd.scale())));
    }

    private static double clampUnitDouble(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
