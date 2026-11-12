package omino2048;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Immutable polyomino board shape represented as a normalized set of (row, col) grid cells.
 * Missing cells act as walls during slides.
 */
public final class Polyomino {
    public record Cell(int row, int col) implements Comparable<Cell> {
        @Override
        public int compareTo(Cell other) {
            int r = Integer.compare(this.row, other.row);
            return r != 0 ? r : Integer.compare(this.col, other.col);
        }
    }

    private final List<Cell> cells;
    private final int rows;
    private final int cols;
    private final Map<Direction, int[][]> segmentsByDirection;

    private Polyomino(List<Cell> normalizedSortedCells, int rows, int cols) {
        this.cells = Collections.unmodifiableList(normalizedSortedCells);
        this.rows = rows;
        this.cols = cols;
        this.segmentsByDirection = buildSegments(normalizedSortedCells, rows, cols);
    }

    /**
     * Creates a polyomino from ASCII grid rows.
     * Occupied characters: '#', 'X', 'x', '1', '*', 'O', 'o'.
     * Empty/wall characters: '.', ' ', '0', '_'.
     */
    public static Polyomino fromPattern(String... patternRows) {
        Objects.requireNonNull(patternRows, "patternRows must not be null");
        if (patternRows.length == 0) {
            throw new IllegalArgumentException("patternRows must not be empty");
        }
        List<Cell> parsed = new ArrayList<>();
        for (int r = 0; r < patternRows.length; r++) {
            String row = Objects.requireNonNull(patternRows[r], "pattern row must not be null");
            for (int c = 0; c < row.length(); c++) {
                char ch = row.charAt(c);
                if (ch == '#' || ch == 'X' || ch == 'x' || ch == '1' || ch == '*' || ch == 'O' || ch == 'o') {
                    parsed.add(new Cell(r, c));
                } else if (ch != '.' && ch != ' ' && ch != '0' && ch != '_') {
                    throw new IllegalArgumentException("Unexpected character '" + ch + "' in pattern at (" + r + ", " + c + ")");
                }
            }
        }
        return fromCells(parsed);
    }

    public static Polyomino fromMask(boolean[][] mask) {
        Objects.requireNonNull(mask, "mask must not be null");
        List<Cell> parsed = new ArrayList<>();
        for (int r = 0; r < mask.length; r++) {
            boolean[] row = Objects.requireNonNull(mask[r], "mask row must not be null");
            for (int c = 0; c < row.length; c++) {
                if (row[c]) {
                    parsed.add(new Cell(r, c));
                }
            }
        }
        return fromCells(parsed);
    }

    public static Polyomino fromCells(int[]... coords) {
        Objects.requireNonNull(coords, "coords must not be null");
        List<Cell> parsed = new ArrayList<>(coords.length);
        for (int[] coord : coords) {
            if (coord == null || coord.length != 2) {
                throw new IllegalArgumentException("Each coordinate must be a 2-element [row, col] array");
            }
            parsed.add(new Cell(coord[0], coord[1]));
        }
        return fromCells(parsed);
    }

    public static Polyomino fromCells(Collection<Cell> rawCells) {
        Objects.requireNonNull(rawCells, "rawCells must not be null");
        if (rawCells.isEmpty()) {
            throw new IllegalArgumentException("Polyomino must contain at least 2 cells");
        }
        Set<Cell> unique = new HashSet<>(rawCells);
        if (unique.size() < 2) {
            throw new IllegalArgumentException("Polyomino must contain at least 2 cells for 2048 two-tile start");
        }
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        for (Cell cell : unique) {
            Objects.requireNonNull(cell, "cell must not be null");
            minRow = Math.min(minRow, cell.row());
            minCol = Math.min(minCol, cell.col());
        }
        List<Cell> normalized = new ArrayList<>(unique.size());
        int maxRow = 0;
        int maxCol = 0;
        for (Cell cell : unique) {
            int r = cell.row() - minRow;
            int c = cell.col() - minCol;
            maxRow = Math.max(maxRow, r);
            maxCol = Math.max(maxCol, c);
            normalized.add(new Cell(r, c));
        }
        normalized.sort(Comparator.naturalOrder());
        return new Polyomino(normalized, maxRow + 1, maxCol + 1);
    }

    public static Polyomino rectangle(int rows, int cols) {
        if (rows <= 0 || cols <= 0 || rows * cols < 2) {
            throw new IllegalArgumentException("Rectangle must have positive dimensions and at least 2 cells");
        }
        List<Cell> rect = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                rect.add(new Cell(r, c));
            }
        }
        return new Polyomino(rect, rows, cols);
    }

    public int size() {
        return cells.size();
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public List<Cell> cells() {
        return cells;
    }

    /**
     * Returns contiguous slide lines (length >= 2) in leading-to-trailing cell-index order.
     */
    int[][] segments(Direction direction) {
        return segmentsByDirection.get(direction);
    }

    public boolean isConnected() {
        Set<Cell> all = new HashSet<>(cells);
        Set<Cell> visited = new HashSet<>();
        Queue<Cell> queue = new ArrayDeque<>();
        Cell start = cells.get(0);
        queue.add(start);
        visited.add(start);
        int[][] deltas = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        while (!queue.isEmpty()) {
            Cell curr = queue.poll();
            for (int[] d : deltas) {
                Cell next = new Cell(curr.row() + d[0], curr.col() + d[1]);
                if (all.contains(next) && visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited.size() == cells.size();
    }

    private static Map<Direction, int[][]> buildSegments(List<Cell> cells, int rows, int cols) {
        Map<Cell, Integer> indexMap = new HashMap<>(cells.size() * 2);
        for (int i = 0; i < cells.size(); i++) {
            indexMap.put(cells.get(i), i);
        }

        List<int[]> leftList = new ArrayList<>();
        List<int[]> rightList = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<Integer> run = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                Integer idx = indexMap.get(new Cell(r, c));
                if (idx != null) {
                    run.add(idx);
                } else {
                    flushRun(run, leftList, rightList);
                }
            }
            flushRun(run, leftList, rightList);
        }

        List<int[]> upList = new ArrayList<>();
        List<int[]> downList = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            List<Integer> run = new ArrayList<>();
            for (int r = 0; r < rows; r++) {
                Integer idx = indexMap.get(new Cell(r, c));
                if (idx != null) {
                    run.add(idx);
                } else {
                    flushRun(run, upList, downList);
                }
            }
            flushRun(run, upList, downList);
        }

        EnumMap<Direction, int[][]> result = new EnumMap<>(Direction.class);
        result.put(Direction.LEFT, leftList.toArray(int[][]::new));
        result.put(Direction.RIGHT, rightList.toArray(int[][]::new));
        result.put(Direction.UP, upList.toArray(int[][]::new));
        result.put(Direction.DOWN, downList.toArray(int[][]::new));
        return Collections.unmodifiableMap(result);
    }

    private static void flushRun(List<Integer> run, List<int[]> forward, List<int[]> backward) {
        if (run.size() >= 2) {
            int n = run.size();
            int[] fwd = new int[n];
            int[] bwd = new int[n];
            for (int i = 0; i < n; i++) {
                fwd[i] = run.get(i);
                bwd[i] = run.get(n - 1 - i);
            }
            forward.add(fwd);
            backward.add(bwd);
        }
        run.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Polyomino other)) {
            return false;
        }
        return cells.equals(other.cells);
    }

    @Override
    public int hashCode() {
        return cells.hashCode();
    }

    @Override
    public String toString() {
        char[][] grid = new char[rows][cols];
        for (char[] row : grid) {
            Arrays.fill(row, '.');
        }
        for (Cell cell : cells) {
            grid[cell.row()][cell.col()] = '#';
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            if (r > 0) {
                sb.append('\n');
            }
            sb.append(grid[r]);
        }
        return sb.toString();
    }
}
