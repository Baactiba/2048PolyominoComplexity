package omino2048.gui;

import omino2048.Polyomino;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Optional;

/**
 * Interactive 7x7 square grid allowing the user to draw an arbitrary polyomino shape
 * by clicking or dragging across cells.
 */
public final class PolyominoGridPanel extends JPanel {
    public static final int GRID_SIZE = 7;

    private static final Color BG_BOARD = new Color(0xD8, 0xCF, 0xC4);
    private static final Color CELL_EMPTY = new Color(0xEE, 0xE7, 0xDE);
    private static final Color CELL_EMPTY_HOVER = new Color(0xE4, 0xDA, 0xCD);
    private static final Color CELL_FILLED = new Color(0xF5, 0x95, 0x23);
    private static final Color CELL_FILLED_HOVER = new Color(0xF7, 0xA8, 0x45);
    private static final Color CELL_FILLED_BORDER = new Color(0xC9, 0x6F, 0x0B);

    private final boolean[][] mask = new boolean[GRID_SIZE][GRID_SIZE];
    private final Runnable onChange;
    private boolean painting;
    private boolean paintTargetValue;
    private int hoverRow = -1;
    private int hoverCol = -1;

    public PolyominoGridPanel(Runnable onChange) {
        this.onChange = onChange;
        setPreferredSize(new Dimension(238, 238));
        setMinimumSize(new Dimension(196, 196));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Click or drag cells to toggle active board squares");

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point cell = cellAt(e.getX(), e.getY());
                if (cell == null) {
                    return;
                }
                painting = true;
                // Right-click always clears; left-click toggles initial cell and paints consistently during drag.
                paintTargetValue = e.getButton() == MouseEvent.BUTTON3 ? false : !mask[cell.y][cell.x];
                setCell(cell.y, cell.x, paintTargetValue);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point cell = cellAt(e.getX(), e.getY());
                updateHover(cell);
                if (painting && cell != null) {
                    setCell(cell.y, cell.x, paintTargetValue);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                painting = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(cellAt(e.getX(), e.getY()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                updateHover(null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void clear() {
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                mask[r][c] = false;
            }
        }
        notifyChanged();
    }

    public void setCenteredPattern(String... rows) {
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                mask[r][c] = false;
            }
        }
        int pRows = rows.length;
        int pCols = 0;
        for (String row : rows) {
            pCols = Math.max(pCols, row.length());
        }
        int startR = Math.max(0, (GRID_SIZE - pRows) / 2);
        int startC = Math.max(0, (GRID_SIZE - pCols) / 2);
        for (int r = 0; r < pRows && startR + r < GRID_SIZE; r++) {
            String row = rows[r];
            for (int c = 0; c < row.length() && startC + c < GRID_SIZE; c++) {
                char ch = row.charAt(c);
                mask[startR + r][startC + c] =
                        ch == '#' || ch == 'X' || ch == 'x' || ch == '1' || ch == '*';
            }
        }
        notifyChanged();
    }

    public int cellCount() {
        int count = 0;
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                if (mask[r][c]) {
                    count++;
                }
            }
        }
        return count;
    }

    public Optional<Polyomino> buildPolyomino() {
        if (cellCount() < 2) {
            return Optional.empty();
        }
        return Optional.of(Polyomino.fromMask(mask));
    }

    private void setCell(int r, int c, boolean value) {
        if (mask[r][c] != value) {
            mask[r][c] = value;
            notifyChanged();
        }
    }

    private void notifyChanged() {
        repaint();
        if (onChange != null) {
            onChange.run();
        }
    }

    private void updateHover(Point cell) {
        int nr = cell == null ? -1 : cell.y;
        int nc = cell == null ? -1 : cell.x;
        if (nr != hoverRow || nc != hoverCol) {
            hoverRow = nr;
            hoverCol = nc;
            repaint();
        }
    }

    private GridGeometry geometry() {
        int size = Math.min(getWidth(), getHeight());
        int ox = (getWidth() - size) / 2;
        int oy = (getHeight() - size) / 2;
        double pad = Math.max(5.0, size * 0.03);
        double gap = Math.max(3.0, size * 0.02);
        double cellSize = (size - 2.0 * pad - (GRID_SIZE - 1) * gap) / GRID_SIZE;
        return new GridGeometry(ox, oy, size, pad, gap, cellSize);
    }

    private Point cellAt(int px, int py) {
        GridGeometry g = geometry();
        double relX = px - g.ox - g.pad;
        double relY = py - g.oy - g.pad;
        if (relX < 0 || relY < 0) {
            return null;
        }
        double stride = g.cellSize + g.gap;
        int c = (int) Math.floor(relX / stride);
        int r = (int) Math.floor(relY / stride);
        if (r < 0 || r >= GRID_SIZE || c < 0 || c >= GRID_SIZE) {
            return null;
        }
        double inCellX = relX - c * stride;
        double inCellY = relY - r * stride;
        if (inCellX > g.cellSize || inCellY > g.cellSize) {
            return null;
        }
        return new Point(c, r);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            GridGeometry g = geometry();

            g2.setColor(BG_BOARD);
            g2.fill(new RoundRectangle2D.Double(g.ox, g.oy, g.boardSize, g.boardSize, 12, 12));

            double stride = g.cellSize + g.gap;
            double arc = Math.max(5.0, g.cellSize * 0.22);

            for (int r = 0; r < GRID_SIZE; r++) {
                for (int c = 0; c < GRID_SIZE; c++) {
                    double x = g.ox + g.pad + c * stride;
                    double y = g.oy + g.pad + r * stride;
                    boolean filled = mask[r][c];
                    boolean hovered = r == hoverRow && c == hoverCol;

                    RoundRectangle2D rect = new RoundRectangle2D.Double(x, y, g.cellSize, g.cellSize, arc, arc);
                    if (filled) {
                        g2.setColor(hovered ? CELL_FILLED_HOVER : CELL_FILLED);
                        g2.fill(rect);
                        g2.setColor(CELL_FILLED_BORDER);
                        g2.setStroke(new BasicStroke(1.3f));
                        g2.draw(rect);
                    } else {
                        g2.setColor(hovered ? CELL_EMPTY_HOVER : CELL_EMPTY);
                        g2.fill(rect);
                    }
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private record GridGeometry(double ox, double oy, double boardSize, double pad, double gap, double cellSize) {}
}
