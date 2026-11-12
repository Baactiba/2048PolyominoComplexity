import omino2048.EnvelopeResult;
import omino2048.EnvelopeSegment;
import omino2048.Fraction;
import omino2048.GameSolver;
import omino2048.Polynomial;
import omino2048.Polyomino;
import omino2048.SolveResult;

public class Main {
    public static void main(String[] args) {
        // 1. Sanity check: 1x2 domino, goal 8 => polynomial x^3 - x^2 + x
        Polyomino domino = Polyomino.rectangle(1, 2);
        SolveResult domino8 = GameSolver.solve(domino, 8, Fraction.parse("0.4"));
        System.out.println("1x2 domino, goal 8, requested spawnRate = 0.4 (tested: " + domino8.testedSpawnRate() + "):");
        System.out.println("  Success rate: " + domino8.successRate() + " (~" + domino8.successRate().toBigDecimal(10) + ")");
        System.out.println("  2nd-highest success rate: " + domino8.secondHighestSuccessRate());
        System.out.println("  Optimal polynomials (" + domino8.optimalPolynomials().size() + "): " + domino8.optimalPolynomials());
        System.out.println();

        // 2. 1x3 tromino, goal 16, requested spawnRate = 0.4, predict at 0.7
        Polyomino tromino1x3 = Polyomino.rectangle(1, 3);
        Fraction rate04 = Fraction.parse("0.4");
        Fraction rate07 = Fraction.parse("0.7");

        SolveResult res04 = GameSolver.solve(tromino1x3, 16, rate04);
        System.out.println("1x3 tromino, goal 16, requested spawnRate = 0.4 (tested: " + res04.testedSpawnRate() + "):");
        System.out.println("  Exact success rate: " + res04.successRate() + " (~" + res04.successRate().toBigDecimal(12) + ")");
        System.out.println("  2nd-highest success rate: " + res04.secondHighestSuccessRate()
                + (res04.secondHighestSuccessRate() != null ? " (~" + res04.secondHighestSuccessRate().toBigDecimal(12) + ")" : ""));
        System.out.println("  Optimal polynomial count: " + res04.optimalPolynomials().size());
        for (int i = 0; i < res04.optimalPolynomials().size(); i++) {
            Polynomial poly = res04.optimalPolynomials().get(i);
            Fraction pred07 = poly.evaluate(rate07);
            System.out.println("  Polynomial[" + i + "]: " + poly);
            System.out.println("    Predicted success rate at 0.7: " + pred07 + " (~" + pred07.toBigDecimal(12) + ")");
        }
        System.out.println();

        // 3. Tie-explosion protection check: 1x4 tetromino, goal 16, requested spawnRate = 0.5
        Polyomino tetromino1x4 = Polyomino.rectangle(1, 4);
        SolveResult res05 = GameSolver.solve(tetromino1x4, 16, Fraction.parse("0.5"));
        System.out.println("1x4 tetromino, goal 16, requested spawnRate = 0.5:");
        System.out.println("  Tested spawn rate: " + res05.testedSpawnRate() + " (~" + res05.testedSpawnRate().toBigDecimal(10) + ")");
        System.out.println("  Success rate: " + res05.successRate().toBigDecimal(10));
        System.out.println("  2nd-highest success rate: " + res05.secondHighestSuccessRate().toBigDecimal(10));
        System.out.println("  Optimal polynomials (" + res05.optimalPolynomials().size() + "): " + res05.optimalPolynomials());
        System.out.println();

        // 4. Piecewise upper envelope demonstrations (<= 4 cells fast suite; run TetrominoEnvelopeRunner for T/L4/S too)
        printEnvelope("1x3 tromino (goal 8)", tromino1x3, 8);
        printEnvelope("1x3 tromino (goal 16)", tromino1x3, 16);
        printEnvelope("L tromino (goal 16)", Polyomino.fromPattern("#.", "##"), 16);
        printEnvelope("1x4 tetromino (goal 16)", tetromino1x4, 16);
    }

    static void printEnvelope(String label, Polyomino poly, int goal) {
        long t0 = System.nanoTime();
        EnvelopeResult env = GameSolver.solveEnvelope(poly, goal);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("Envelope: " + label + " (D=" + env.maxPolynomialDegree()
                + ", solverCalls=" + env.solverCalls()
                + ", distinctStrategies=" + env.distinctPolynomials().size()
                + ", segments=" + env.segments().size()
                + ", time=" + ms + " ms)");
        for (EnvelopeSegment seg : env.segments()) {
            System.out.println("  [" + seg.formatStart() + ", " + seg.formatEnd() + "] -> " + seg.polynomial());
        }
        System.out.println();
    }
}
