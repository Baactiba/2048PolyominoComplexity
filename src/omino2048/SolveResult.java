package omino2048;

import java.util.List;
import java.util.Objects;

/**
 * Exact solver output for a given polyomino, tile goal, and spawn rate.
 *
 * @param successRate exact optimal win probability at {@code testedSpawnRate}
 * @param optimalPolynomials deduplicated polynomials representing optimal strategies at {@code testedSpawnRate}
 * @param testedSpawnRate the actual 4-tile spawn rate that was solved (equals the requested rate unless
 *                        tweaked by a small epsilon to avoid polynomial tie explosion)
 * @param secondHighestSuccessRate exact 2nd-highest policy win probability at {@code testedSpawnRate},
 *                                 or {@code null} if no optimally reachable board has a strictly suboptimal move
 */
public record SolveResult(
        Fraction successRate,
        List<Polynomial> optimalPolynomials,
        Fraction testedSpawnRate,
        Fraction secondHighestSuccessRate) {

    public SolveResult {
        Objects.requireNonNull(successRate, "successRate must not be null");
        Objects.requireNonNull(optimalPolynomials, "optimalPolynomials must not be null");
        Objects.requireNonNull(testedSpawnRate, "testedSpawnRate must not be null");
        optimalPolynomials = List.copyOf(optimalPolynomials);
    }
}
