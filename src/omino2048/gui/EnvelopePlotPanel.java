package omino2048.gui;

import omino2048.EnvelopeExtrema;
import omino2048.Fraction;

import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Interactive 2D plot of exact 2048 success probability as a function of 4-tile spawn rate x in [0, 1].
 * Supports toggling off-frontier extensions of every strategy polynomial, highlighting individual
 * strategies, zooming/panning, and inspecting exact values with a hover crosshair.
 */
public final class EnvelopePlotPanel extends JPanel {
    private static final int PAD_LEFT = 74;
    private static final int PAD_RIGHT = 24;
    private static final int PAD_TOP = 22;
    private static final int PAD_BOTTOM = 48;

    private static final Color BG_PANEL = Color.WHITE;
    private static final Color GRID_MAJOR = new Color(0xE5, 0xE7, 0xEB);
    private static final Color GRID_MINOR = new Color(0xF3, 0xF4, 0xF6);
    private static final Color TAIL_DIVIDER_COLOR = new Color(0x94, 0xA3, 0xB8, 170);
    private static final Color AXIS_LINE = new Color(0x6B, 0x72, 0x80);
    private static final Color TEXT_DARK = new Color(0x1F, 0x29, 0x37);
    private static final Color TEXT_MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color ZOOM_BOX_FILL = new Color(0x3B, 0x82, 0xF6, 36);
    private static final Color ZOOM_BOX_BORDER = new Color(0x25, 0x63, 0xEB, 190);
    private static final Color EXTREMA_RED = new Color(0xDC, 0x26, 0x26);
    private static final Color EXTREMA_TEXT_RED = new Color(0x99, 0x1B, 0x1B);
    private static final Color EXTREMA_LINE_COLOR = new Color(0xDC, 0x26, 0x26, 155);
    private static final Color EXTREMA_H_LINE_COLOR = new Color(0xDC, 0x26, 0x26, 95);
    private static final Color EXTREMA_LEADER_COLOR = new Color(0xDC, 0x26, 0x26, 180);

    private static final Stroke FRONTIER_STROKE = new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke FRONTIER_HIGHLIGHT_STROKE = new BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke OFF_FRONTIER_STROKE = new BasicStroke(
            1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{6f, 4f}, 0f);
    private static final Stroke OFF_FRONTIER_HIGHLIGHT_STROKE = new BasicStroke(
            2.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 4f}, 0f);
    private static final Stroke CROSSHAIR_STROKE = new BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f);
    private static final Stroke TAIL_DIVIDER_STROKE = new BasicStroke(
            1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f);
    private static final Stroke EXTREMA_GUIDE_STROKE = new BasicStroke(
            1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f);
    private static final Stroke EXTREMA_TANGENT_STROKE = new BasicStroke(
            1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke EXTREMA_LEADER_STROKE = new BasicStroke(
            1.15f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    public record HoverReadout(
            double x,
            PlottedEnvelope.SegmentEntry activeSegment,
            double frontierY,
            List<StrategyValue> rankedAtX) {}

    public record StrategyValue(PlottedEnvelope.StrategyCurve strategy, double y) {}

    private PlottedEnvelope plotted;
    private boolean showOffFrontier = true;
    private boolean showBreakpoints = true;
    private boolean showExtrema = false;
    private int lastPlacedExtremaLabels = 0;
    private boolean logTailY = true;
    private double logTailDecades = 3.0;
    private int highlightedStrategyIndex = -1;

    private double viewMinX = 0.0;
    private double viewMaxX = 1.0;
    private double viewMinY = 0.0;
    private double viewMaxY = 1.0;

    // Cached curve evaluation across the visible x window.
    private double cachedMinX = Double.NaN;
    private double cachedMaxX = Double.NaN;
    private boolean cachedLogTailY = false;
    private double cachedLogTailDecades = Double.NaN;
    private PlottedEnvelope cachedEnvelope = null;
    private double[] sampleXs = new double[0];
    private double[][] sampleYs = new double[0][0];

    // Mouse / interaction state.
    private Point hoverPixel = null;
    private HoverReadout lastHoverReadout = null;
    private Point dragStartPixel = null;
    private Point dragCurrentPixel = null;
    private boolean panning = false;
    private double panStartMinX, panStartMaxX, panStartMinT, panStartMaxT;

    private final Consumer<HoverReadout> hoverListener;
    private final Runnable viewChangeListener;

    public EnvelopePlotPanel(Consumer<HoverReadout> hoverListener, Runnable viewChangeListener) {
        this.hoverListener = hoverListener;
        this.viewChangeListener = viewChangeListener;
        setBackground(BG_PANEL);
        setPreferredSize(new Dimension(760, 480));
        setMinimumSize(new Dimension(420, 320));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (dragStartPixel == null) {
                    updateHover(null);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    resetZoom();
                    return;
                }
                Rectangle plot = plotRect();
                if (!plot.contains(e.getPoint())) {
                    return;
                }
                dragStartPixel = e.getPoint();
                dragCurrentPixel = e.getPoint();
                panning = e.getButton() == MouseEvent.BUTTON3 || e.isShiftDown();
                panStartMinX = viewMinX;
                panStartMaxX = viewMaxX;
                panStartMinT = mapY(viewMinY);
                panStartMaxT = mapY(viewMaxY);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStartPixel == null) {
                    return;
                }
                Rectangle plot = plotRect();
                if (panning) {
                    double dxFrac = (double) (dragStartPixel.x - e.getX()) / Math.max(1, plot.width);
                    double dyFrac = (double) (e.getY() - dragStartPixel.y) / Math.max(1, plot.height);
                    double spanX = panStartMaxX - panStartMinX;
                    double spanT = panStartMaxT - panStartMinT;
                    double newMinX = clamp(panStartMinX + dxFrac * spanX, 0.0, 1.0 - spanX);
                    double newMinT = clamp(panStartMinT + dyFrac * spanT, 0.0, 1.0 - spanT);
                    setViewBounds(newMinX, newMinX + spanX, unmapY(newMinT), unmapY(newMinT + spanT));
                } else {
                    dragCurrentPixel = new Point(
                            Math.max(plot.x, Math.min(plot.x + plot.width, e.getX())),
                            Math.max(plot.y, Math.min(plot.y + plot.height, e.getY())));
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragStartPixel != null && !panning && dragCurrentPixel != null) {
                    int x1 = Math.min(dragStartPixel.x, dragCurrentPixel.x);
                    int x2 = Math.max(dragStartPixel.x, dragCurrentPixel.x);
                    int y1 = Math.min(dragStartPixel.y, dragCurrentPixel.y);
                    int y2 = Math.max(dragStartPixel.y, dragCurrentPixel.y);
                    if (x2 - x1 >= 10 && y2 - y1 >= 10) {
                        Rectangle plot = plotRect();
                        double nMinX = pixelToX(x1, plot);
                        double nMaxX = pixelToX(x2, plot);
                        double nMinY = pixelToY(y2, plot);
                        double nMaxY = pixelToY(y1, plot);
                        if (nMaxX - nMinX >= 1e-6 && nMaxY - nMinY >= 1e-12) {
                            setViewBounds(nMinX, nMaxX, nMinY, nMaxY);
                        }
                    }
                }
                dragStartPixel = null;
                dragCurrentPixel = null;
                panning = false;
                updateHover(e.getPoint());
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                Rectangle plot = plotRect();
                if (!plot.contains(e.getPoint())) {
                    return;
                }
                double factor = Math.pow(1.18, e.getPreciseWheelRotation());
                double anchorX = pixelToX(e.getX(), plot);
                double anchorT = mapY(pixelToY(e.getY(), plot));

                double spanX = viewMaxX - viewMinX;
                double minT = mapY(viewMinY);
                double maxT = mapY(viewMaxY);
                double spanT = Math.max(1e-9, maxT - minT);

                boolean zoomX = !e.isControlDown();
                boolean zoomY = !e.isShiftDown();

                double newSpanX = zoomX ? clamp(spanX * factor, 1e-6, 1.0) : spanX;
                double newSpanT = zoomY ? clamp(spanT * factor, 1e-6, 1.0) : spanT;

                double relX = (anchorX - viewMinX) / spanX;
                double relT = (anchorT - minT) / spanT;

                double nMinX = clamp(anchorX - relX * newSpanX, 0.0, 1.0 - newSpanX);
                double nMinT = clamp(anchorT - relT * newSpanT, 0.0, 1.0 - newSpanT);
                setViewBounds(nMinX, nMinX + newSpanX, unmapY(nMinT), unmapY(nMinT + newSpanT));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setPlottedEnvelope(PlottedEnvelope plotted) {
        this.plotted = plotted;
        this.highlightedStrategyIndex = -1;
        this.cachedEnvelope = null;
        this.hoverPixel = null;
        this.lastHoverReadout = null;
        resetZoom();
    }

    public void setShowOffFrontier(boolean showOffFrontier) {
        if (this.showOffFrontier != showOffFrontier) {
            this.showOffFrontier = showOffFrontier;
            refreshHoverReadout();
            repaint();
        }
    }

    public void setShowBreakpoints(boolean showBreakpoints) {
        if (this.showBreakpoints != showBreakpoints) {
            this.showBreakpoints = showBreakpoints;
            repaint();
        }
    }

    public void setShowExtrema(boolean showExtrema) {
        if (this.showExtrema != showExtrema) {
            this.showExtrema = showExtrema;
            if (!showExtrema) {
                lastPlacedExtremaLabels = 0;
            }
            repaint();
        }
    }

    public boolean isShowExtrema() {
        return showExtrema;
    }

    int lastPlacedExtremaLabels() {
        return lastPlacedExtremaLabels;
    }

    public void setLogTailY(boolean logTailY) {
        if (this.logTailY != logTailY) {
            this.logTailY = logTailY;
            cachedEnvelope = null;
            refreshHoverReadout();
            repaint();
        }
    }

    public boolean isLogTailY() {
        return logTailY;
    }

    public void setLogTailDecades(double decades) {
        if (Double.isNaN(decades) || Double.isInfinite(decades) || decades <= 0.0 || decades > 15.0) {
            throw new IllegalArgumentException("Scale must be in (0, 15], got: " + decades);
        }
        if (Math.abs(this.logTailDecades - decades) > 1e-12) {
            this.logTailDecades = decades;
            cachedEnvelope = null;
            refreshHoverReadout();
            repaint();
        }
    }

    public double logTailDecades() {
        return logTailDecades;
    }

    public void setHighlightedStrategyIndex(int index) {
        if (this.highlightedStrategyIndex != index) {
            this.highlightedStrategyIndex = index;
            repaint();
        }
    }

    public void resetZoom() {
        setViewBounds(0.0, 1.0, 0.0, 1.0);
    }

    public double viewMinX() { return viewMinX; }
    public double viewMaxX() { return viewMaxX; }
    public double viewMinY() { return viewMinY; }
    public double viewMaxY() { return viewMaxY; }

    public boolean isZoomed() {
        return viewMinX > 1e-9 || viewMaxX < 1.0 - 1e-9 || viewMinY > 1e-9 || viewMaxY < 1.0 - 1e-9;
    }

    private void setViewBounds(double minX, double maxX, double minY, double maxY) {
        this.viewMinX = clamp(minX, 0.0, 1.0);
        this.viewMaxX = clamp(maxX, 0.0, 1.0);
        this.viewMinY = clamp(minY, 0.0, 1.0);
        this.viewMaxY = clamp(maxY, 0.0, 1.0);
        ensureViewportSamples();
        refreshHoverReadout();
        if (viewChangeListener != null) {
            viewChangeListener.run();
        }
        repaint();
    }

    private void ensureViewportSamples() {
        if (plotted == null) {
            sampleXs = new double[0];
            sampleYs = new double[0][0];
            cachedEnvelope = null;
            return;
        }
        if (cachedEnvelope == plotted
                && Math.abs(cachedMinX - viewMinX) < 1e-12
                && Math.abs(cachedMaxX - viewMaxX) < 1e-12
                && cachedLogTailY == logTailY
                && (!logTailY || Math.abs(cachedLogTailDecades - logTailDecades) < 1e-12)) {
            return;
        }
        TreeSet<Double> xsSet = new TreeSet<>();
        int gridSamples = 520;
        double span = viewMaxX - viewMinX;
        for (int i = 0; i <= gridSamples; i++) {
            xsSet.add(viewMinX + (span * i) / gridSamples);
        }
        // Extra log-clustered x samples near endpoints x=0 and x=1 so stretched log-tail slopes stay silky smooth.
        if (logTailY) {
            for (int e = 1; e <= 6; e++) {
                for (double m : new double[]{1.0, 2.0, 3.0, 5.0, 7.0}) {
                    double xLo = m * Math.pow(10.0, -e);
                    if (xLo > viewMinX && xLo < viewMaxX) {
                        xsSet.add(xLo);
                    }
                    double xHi = 1.0 - xLo;
                    if (xHi > viewMinX && xHi < viewMaxX) {
                        xsSet.add(xHi);
                    }
                }
            }
        }
        xsSet.add(viewMinX);
        xsSet.add(viewMaxX);
        for (PlottedEnvelope.SegmentEntry entry : plotted.segments()) {
            if (entry.startX() >= viewMinX && entry.startX() <= viewMaxX) {
                xsSet.add(entry.startX());
            }
            if (entry.endX() >= viewMinX && entry.endX() <= viewMaxX) {
                xsSet.add(entry.endX());
            }
        }

        double[] xs = new double[xsSet.size()];
        int idx = 0;
        for (double x : xsSet) {
            xs[idx++] = x;
        }

        List<PlottedEnvelope.StrategyCurve> strategies = plotted.strategies();
        double[][] ys = new double[strategies.size()][xs.length];
        for (int s = 0; s < strategies.size(); s++) {
            PlottedEnvelope.StrategyCurve curve = strategies.get(s);
            double[] curveY = ys[s];
            for (int i = 0; i < xs.length; i++) {
                curveY[i] = curve.evaluateDouble(xs[i]);
            }
        }

        this.sampleXs = xs;
        this.sampleYs = ys;
        this.cachedEnvelope = plotted;
        this.cachedMinX = viewMinX;
        this.cachedMaxX = viewMaxX;
        this.cachedLogTailY = logTailY;
        this.cachedLogTailDecades = logTailDecades;
    }

    private void updateHover(Point point) {
        Rectangle plot = plotRect();
        if (point == null || plotted == null || !plot.contains(point)) {
            if (hoverPixel != null || lastHoverReadout != null) {
                hoverPixel = null;
                lastHoverReadout = null;
                if (hoverListener != null) {
                    hoverListener.accept(null);
                }
                repaint();
            }
            return;
        }
        hoverPixel = point;
        refreshHoverReadout();
        repaint();
    }

    private void refreshHoverReadout() {
        if (hoverPixel == null || plotted == null) {
            lastHoverReadout = null;
            if (hoverListener != null) {
                hoverListener.accept(null);
            }
            return;
        }
        Rectangle plot = plotRect();
        double x = clamp(pixelToX(hoverPixel.x, plot), viewMinX, viewMaxX);
        PlottedEnvelope.SegmentEntry active = plotted.segmentAt(x);
        double frontierY = active != null ? active.strategy().evaluateDouble(x) : 0.0;

        List<StrategyValue> ranked = new ArrayList<>();
        if (showOffFrontier) {
            for (PlottedEnvelope.StrategyCurve s : plotted.strategies()) {
                ranked.add(new StrategyValue(s, s.evaluateDouble(x)));
            }
            ranked.sort(Comparator.comparingDouble(StrategyValue::y).reversed());
        } else if (active != null) {
            ranked.add(new StrategyValue(active.strategy(), frontierY));
        }

        lastHoverReadout = new HoverReadout(x, active, frontierY, ranked);
        if (hoverListener != null) {
            hoverListener.accept(lastHoverReadout);
        }
    }

    private Rectangle plotRect() {
        int w = Math.max(40, getWidth() - PAD_LEFT - PAD_RIGHT);
        int h = Math.max(40, getHeight() - PAD_TOP - PAD_BOTTOM);
        return new Rectangle(PAD_LEFT, PAD_TOP, w, h);
    }

    /**
     * Maps probability {@code y in [0, 1]} to normalized vertical display coordinate {@code t in [0, 1]}:
     * when {@code logTailY} is enabled, {@code [1/3, 2/3]} is linear and {@code [0, 1/3]} / {@code [2/3, 1]}
     * stretch logarithmically toward the lower and upper edges.
     */
    double mapY(double y) {
        double clamped = clamp(y, 0.0, 1.0);
        if (!logTailY || logTailDecades <= 1e-6) {
            return clamped;
        }
        double k = Math.pow(10.0, logTailDecades) - 1.0;
        double denom = Math.log1p(k);
        if (clamped < 1.0 / 3.0) {
            double u = 3.0 * clamped;
            return (1.0 / 3.0) * (Math.log1p(k * u) / denom);
        }
        if (clamped > 2.0 / 3.0) {
            double u = 3.0 * (1.0 - clamped);
            return 1.0 - (1.0 / 3.0) * (Math.log1p(k * u) / denom);
        }
        return clamped;
    }

    double unmapY(double t) {
        double clamped = clamp(t, 0.0, 1.0);
        if (!logTailY || logTailDecades <= 1e-6) {
            return clamped;
        }
        double k = Math.pow(10.0, logTailDecades) - 1.0;
        double logDenom = Math.log1p(k);
        if (clamped < 1.0 / 3.0) {
            double u = Math.expm1(3.0 * clamped * logDenom) / k;
            return clamp(u / 3.0, 0.0, 1.0 / 3.0);
        }
        if (clamped > 2.0 / 3.0) {
            double u = Math.expm1(3.0 * (1.0 - clamped) * logDenom) / k;
            return clamp(1.0 - u / 3.0, 2.0 / 3.0, 1.0);
        }
        return clamped;
    }

    private double xToPixel(double x, Rectangle plot) {
        return plot.x + ((x - viewMinX) / (viewMaxX - viewMinX)) * plot.width;
    }

    private double yToPixel(double y, Rectangle plot) {
        double tMin = mapY(viewMinY);
        double tMax = mapY(viewMaxY);
        double t = mapY(y);
        return plot.y + plot.height - ((t - tMin) / Math.max(1e-15, tMax - tMin)) * plot.height;
    }

    private double pixelToX(int px, Rectangle plot) {
        return viewMinX + ((double) (px - plot.x) / plot.width) * (viewMaxX - viewMinX);
    }

    private double pixelToY(int py, Rectangle plot) {
        double tMin = mapY(viewMinY);
        double tMax = mapY(viewMaxY);
        double t = tMin + ((double) (plot.y + plot.height - py) / plot.height) * (tMax - tMin);
        return unmapY(t);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        ensureViewportSamples();

        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            Rectangle plot = plotRect();
            paintGridAndAxes(g2, plot);

            if (plotted == null) {
                paintEmptyPlaceholder(g2, plot);
                return;
            }

            ShapeRestore clip = new ShapeRestore(g2);
            g2.clip(new Rectangle2D.Double(plot.x - 2.0, plot.y - 2.0, plot.width + 4.0, plot.height + 4.0));

            if (showOffFrontier) {
                paintOffFrontierCurves(g2, plot);
            }
            paintFrontierSegments(g2, plot);
            if (showBreakpoints) {
                paintBreakpointMarkers(g2, plot);
            }
            if (showExtrema) {
                paintExtremaOverlay(g2, plot);
            }

            clip.restore(g2);

            if (dragStartPixel != null && !panning && dragCurrentPixel != null) {
                paintZoomBox(g2);
            } else if (lastHoverReadout != null && hoverPixel != null && plot.contains(hoverPixel)) {
                paintHoverOverlay(g2, plot, lastHoverReadout);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintGridAndAxes(Graphics2D g2, Rectangle plot) {
        g2.setColor(Color.WHITE);
        g2.fillRect(plot.x, plot.y, plot.width, plot.height);

        double majorX = niceTickStep(viewMaxX - viewMinX);
        double minorX = majorX / 2.0;

        Font tickFont = g2.getFont().deriveFont(Font.PLAIN, 11f);
        Font axisFont = g2.getFont().deriveFont(Font.BOLD, 12f);

        // Minor vertical X grid lines
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(GRID_MINOR);
        for (double x = Math.ceil(viewMinX / minorX - 1e-9) * minorX; x <= viewMaxX + 1e-9; x += minorX) {
            double px = xToPixel(x, plot);
            g2.drawLine((int) Math.round(px), plot.y, (int) Math.round(px), plot.y + plot.height);
        }

        g2.setFont(tickFont);
        FontMetrics fm = g2.getFontMetrics();
        int decimalsX = decimalsForStep(majorX);

        if (!logTailY) {
            double majorY = niceTickStep(viewMaxY - viewMinY);
            double minorY = majorY / 2.0;
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(GRID_MINOR);
            for (double y = Math.ceil(viewMinY / minorY - 1e-9) * minorY; y <= viewMaxY + 1e-9; y += minorY) {
                double py = yToPixel(y, plot);
                g2.drawLine(plot.x, (int) Math.round(py), plot.x + plot.width, (int) Math.round(py));
            }
            int decimalsY = decimalsForStep(majorY);
            for (double y = Math.ceil(viewMinY / majorY - 1e-9) * majorY; y <= viewMaxY + 1e-9; y += majorY) {
                double py = yToPixel(y, plot);
                g2.setColor(GRID_MAJOR);
                g2.drawLine(plot.x, (int) Math.round(py), plot.x + plot.width, (int) Math.round(py));
                String label = formatTick(y, decimalsY);
                int lw = fm.stringWidth(label);
                g2.setColor(TEXT_MUTED);
                g2.drawString(label, plot.x - lw - 8, (float) (py + fm.getAscent() / 2.0 - 1));
            }
        } else {
            paintLogTailYGridAndTicks(g2, plot, fm);
        }

        // Major vertical X grid lines + tick labels
        g2.setStroke(new BasicStroke(1f));
        for (double x = Math.ceil(viewMinX / majorX - 1e-9) * majorX; x <= viewMaxX + 1e-9; x += majorX) {
            double px = xToPixel(x, plot);
            g2.setColor(GRID_MAJOR);
            g2.drawLine((int) Math.round(px), plot.y, (int) Math.round(px), plot.y + plot.height);
            String label = formatTick(x, decimalsX);
            int lw = fm.stringWidth(label);
            g2.setColor(TEXT_MUTED);
            g2.drawString(label, (float) (px - lw / 2.0), plot.y + plot.height + 16);
        }

        // Outer frame
        g2.setColor(AXIS_LINE);
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRect(plot.x, plot.y, plot.width, plot.height);

        // Axis titles
        g2.setFont(axisFont);
        g2.setColor(TEXT_DARK);
        FontMetrics afm = g2.getFontMetrics();
        String xTitle = "4-Tile Spawn Rate (x)";
        g2.drawString(xTitle, plot.x + (plot.width - afm.stringWidth(xTitle)) / 2f, plot.y + plot.height + 36);

        Graphics2D rot = (Graphics2D) g2.create();
        rot.rotate(-Math.PI / 2.0);
        String yTitle = "Success Rate P(x)";
        rot.drawString(yTitle, -(plot.y + (plot.height + afm.stringWidth(yTitle)) / 2f), 18f);
        rot.dispose();
    }

    private void paintEmptyPlaceholder(Graphics2D g2, Rectangle plot) {
        g2.setColor(TEXT_MUTED);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
        String msg = "Draw a polyomino on the 7x7 grid, pick a tile goal, and click Solve Envelope.";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, plot.x + (plot.width - fm.stringWidth(msg)) / 2f, plot.y + plot.height / 2f);
    }

    private void paintOffFrontierCurves(Graphics2D g2, Rectangle plot) {
        if (sampleXs.length < 2) {
            return;
        }
        Composite oldComp = g2.getComposite();
        List<PlottedEnvelope.StrategyCurve> strategies = plotted.strategies();
        for (int pass = 0; pass < 2; pass++) {
            boolean drawingHighlightPass = pass == 1;
            for (int s = 0; s < strategies.size(); s++) {
                boolean isHighlighted = s == highlightedStrategyIndex;
                if (drawingHighlightPass != isHighlighted) {
                    continue;
                }
                PlottedEnvelope.StrategyCurve curve = strategies.get(s);
                float alpha;
                if (isHighlighted) {
                    alpha = 0.92f;
                } else if (highlightedStrategyIndex >= 0) {
                    alpha = 0.20f;
                } else {
                    alpha = strategies.size() > 15 ? 0.48f : 0.65f;
                }
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(curve.color());
                g2.setStroke(isHighlighted ? OFF_FRONTIER_HIGHLIGHT_STROKE : OFF_FRONTIER_STROKE);

                Path2D.Double path = new Path2D.Double();
                double[] ys = sampleYs[s];
                for (int i = 0; i < sampleXs.length; i++) {
                    double px = xToPixel(sampleXs[i], plot);
                    double py = yToPixel(ys[i], plot);
                    if (i == 0) {
                        path.moveTo(px, py);
                    } else {
                        path.lineTo(px, py);
                    }
                }
                g2.draw(path);
            }
        }
        g2.setComposite(oldComp);
    }

    private void paintFrontierSegments(Graphics2D g2, Rectangle plot) {
        if (sampleXs.length < 2) {
            return;
        }
        Composite oldComp = g2.getComposite();
        for (int pass = 0; pass < 2; pass++) {
            boolean drawingHighlightPass = pass == 1;
            for (PlottedEnvelope.SegmentEntry entry : plotted.segments()) {
                double segStart = entry.startX();
                double segEnd = entry.endX();
                if (segEnd < viewMinX || segStart > viewMaxX) {
                    continue;
                }
                PlottedEnvelope.StrategyCurve curve = entry.strategy();
                boolean isHighlighted = curve.index() == highlightedStrategyIndex;
                if (drawingHighlightPass != isHighlighted) {
                    continue;
                }

                float alpha = (highlightedStrategyIndex >= 0 && !isHighlighted) ? 0.35f : 1.0f;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(curve.color());
                g2.setStroke(isHighlighted ? FRONTIER_HIGHLIGHT_STROKE : FRONTIER_STROKE);

                double lo = Math.max(viewMinX, segStart);
                double hi = Math.min(viewMaxX, segEnd);
                int idxStart = findNearestIndexGE(sampleXs, lo - 1e-13);
                int idxEnd = findNearestIndexLE(sampleXs, hi + 1e-13);

                Path2D.Double path = new Path2D.Double();
                double[] ys = sampleYs[curve.index()];
                boolean started = false;
                if (idxStart <= idxEnd && idxStart < sampleXs.length && idxEnd >= 0) {
                    for (int i = idxStart; i <= idxEnd; i++) {
                        double px = xToPixel(sampleXs[i], plot);
                        double py = yToPixel(ys[i], plot);
                        if (!started) {
                            path.moveTo(px, py);
                            started = true;
                        } else {
                            path.lineTo(px, py);
                        }
                    }
                }
                if (!started) {
                    path.moveTo(xToPixel(lo, plot), yToPixel(curve.evaluateDouble(lo), plot));
                    path.lineTo(xToPixel(hi, plot), yToPixel(curve.evaluateDouble(hi), plot));
                }
                g2.draw(path);
            }
        }
        g2.setComposite(oldComp);
    }

    private void paintBreakpointMarkers(Graphics2D g2, Rectangle plot) {
        List<PlottedEnvelope.SegmentEntry> segs = plotted.segments();
        for (int i = 0; i + 1 < segs.size(); i++) {
            PlottedEnvelope.SegmentEntry left = segs.get(i);
            double bx = left.endX();
            if (bx < viewMinX || bx > viewMaxX) {
                continue;
            }
            double by = left.strategy().evaluateDouble(bx);
            if (by < viewMinY || by > viewMaxY) {
                continue;
            }
            double px = xToPixel(bx, plot);
            double py = yToPixel(by, plot);
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(px - 3.8, py - 3.8, 7.6, 7.6));
            g2.setColor(left.strategy().color());
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new Ellipse2D.Double(px - 3.8, py - 3.8, 7.6, 7.6));
        }
    }

    private record ExtremumScreenPoint(EnvelopeExtrema.Extremum extremum, double px, double py) {}

    private record PlacedExtremaLabel(
            ExtremumScreenPoint anchor,
            int bx,
            int by,
            int boxW,
            int boxH,
            String text) {}

    private void paintExtremaOverlay(Graphics2D g2, Rectangle plot) {
        List<ExtremumScreenPoint> visible = new ArrayList<>();
        for (EnvelopeExtrema.Extremum ex : plotted.extrema()) {
            if (ex.x() < viewMinX - 1e-12 || ex.x() > viewMaxX + 1e-12
                    || ex.y() < viewMinY - 1e-12 || ex.y() > viewMaxY + 1e-12) {
                continue;
            }
            visible.add(new ExtremumScreenPoint(ex, xToPixel(ex.x(), plot), yToPixel(ex.y(), plot)));
        }
        if (visible.isEmpty()) {
            lastPlacedExtremaLabels = 0;
            return;
        }

        // 1. Draw red guide lines and horizontal tangent/cusp ticks on the envelope.
        boolean drawHorizontalGuides = visible.size() <= 16;
        g2.setStroke(EXTREMA_GUIDE_STROKE);
        for (ExtremumScreenPoint pt : visible) {
            int ix = (int) Math.round(pt.px());
            int iy = (int) Math.round(pt.py());
            g2.setColor(EXTREMA_LINE_COLOR);
            int yBottom = plot.y + plot.height;
            if (yBottom - iy >= 2) {
                g2.drawLine(ix, iy, ix, yBottom);
            } else {
                // Bottom-edge point (y = 0): draw a crisp vertical tick upward so the red line stays visible.
                g2.drawLine(ix, yBottom, ix, Math.max(plot.y, yBottom - 16));
            }
            if (drawHorizontalGuides && ix - plot.x >= 2) {
                g2.setColor(EXTREMA_H_LINE_COLOR);
                g2.drawLine(plot.x, iy, ix, iy);
            }
        }
        g2.setColor(EXTREMA_RED);
        g2.setStroke(EXTREMA_TANGENT_STROKE);
        for (ExtremumScreenPoint pt : visible) {
            int x1 = Math.max(plot.x, (int) Math.round(pt.px() - 9.0));
            int x2 = Math.min(plot.x + plot.width, (int) Math.round(pt.px() + 9.0));
            int iy = (int) Math.round(pt.py());
            if (x2 > x1) {
                g2.drawLine(x1, iy, x2, iy);
            }
        }

        // 2. Build 4x4 px whitespace occupancy grid so coordinate pills avoid curves, lines, and markers.
        final int cell = 4;
        int gw = Math.max(1, (plot.width + cell - 1) / cell);
        int gh = Math.max(1, (plot.height + cell - 1) / cell);
        byte[] occ = buildWhitespaceOccupancyGrid(plot, cell, gw, gh, visible, drawHorizontalGuides);

        // 3. Place coordinate labels into clean whitespace, prioritizing global & interior extrema.
        List<ExtremumScreenPoint> ordered = new ArrayList<>(visible);
        ordered.sort(Comparator
                .comparing((ExtremumScreenPoint p) -> !p.extremum().isGlobal())
                .thenComparingInt(p -> extremaSourcePriority(p.extremum().source()))
                .thenComparingDouble(ExtremumScreenPoint::px));

        Font labelFont = g2.getFont().deriveFont(Font.BOLD, 10.5f);
        g2.setFont(labelFont);
        FontMetrics fm = g2.getFontMetrics();
        int boxH = fm.getHeight() + 6;
        int textAscentOffset = 3 + fm.getAscent();

        List<PlacedExtremaLabel> placed = new ArrayList<>();
        for (ExtremumScreenPoint pt : ordered) {
            String text = formatExtremumLabel(pt.extremum());
            int boxW = fm.stringWidth(text) + 12;
            PlacedExtremaLabel label = placeLabelInWhitespace(pt, text, boxW, boxH, plot, occ, cell, gw, gh);
            if (label != null) {
                placed.add(label);
                markRect(occ, gw, gh, plot, cell,
                        label.bx() - 4, label.by() - 4,
                        label.bx() + label.boxW() + 4, label.by() + label.boxH() + 4,
                        (byte) 2);
            }
        }
        lastPlacedExtremaLabels = placed.size();

        // 4. Draw leader lines and coordinate label pills in whitespace.
        g2.setStroke(EXTREMA_LEADER_STROKE);
        for (PlacedExtremaLabel lbl : placed) {
            double cx = Math.max(lbl.bx(), Math.min(lbl.bx() + lbl.boxW(), lbl.anchor().px()));
            double cy = Math.max(lbl.by(), Math.min(lbl.by() + lbl.boxH(), lbl.anchor().py()));
            double distSq = Math.pow(cx - lbl.anchor().px(), 2) + Math.pow(cy - lbl.anchor().py(), 2);
            if (distSq > 36.0) {
                g2.setColor(EXTREMA_LEADER_COLOR);
                g2.drawLine((int) Math.round(lbl.anchor().px()), (int) Math.round(lbl.anchor().py()),
                        (int) Math.round(cx), (int) Math.round(cy));
            }
        }

        for (PlacedExtremaLabel lbl : placed) {
            RoundRectangle2D.Double pill = new RoundRectangle2D.Double(
                    lbl.bx(), lbl.by(), lbl.boxW(), lbl.boxH(), 7, 7);
            g2.setColor(new Color(255, 255, 255, 236));
            g2.fill(pill);
            g2.setColor(new Color(0xDC, 0x26, 0x26, 195));
            g2.setStroke(new BasicStroke(1.0f));
            g2.draw(pill);
            g2.setColor(EXTREMA_TEXT_RED);
            g2.drawString(lbl.text(), lbl.bx() + 6, lbl.by() + textAscentOffset);
        }

        // 5. Draw red extremum points on top of curves, lines, and leaders.
        for (ExtremumScreenPoint pt : visible) {
            Ellipse2D.Double dot = new Ellipse2D.Double(pt.px() - 4.0, pt.py() - 4.0, 8.0, 8.0);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.2f));
            g2.draw(dot);
            g2.setColor(EXTREMA_RED);
            g2.fill(dot);
        }
    }

    private static int extremaSourcePriority(EnvelopeExtrema.Source src) {
        return switch (src) {
            case INTERIOR -> 0;
            case BREAKPOINT -> 1;
            case BOUNDARY_ZERO, BOUNDARY_ONE -> 2;
        };
    }

    private byte[] buildWhitespaceOccupancyGrid(
            Rectangle plot,
            int cell,
            int gw,
            int gh,
            List<ExtremumScreenPoint> visible,
            boolean markHorizontalGuides) {
        byte[] occ = new byte[gw * gh];

        // Keep labels comfortably inside the plot frame.
        markRect(occ, gw, gh, plot, cell, plot.x, plot.y, plot.x + plot.width, plot.y + 4, (byte) 2);
        markRect(occ, gw, gh, plot, cell, plot.x, plot.y + plot.height - 4, plot.x + plot.width, plot.y + plot.height, (byte) 2);
        markRect(occ, gw, gh, plot, cell, plot.x, plot.y, plot.x + 4, plot.y + plot.height, (byte) 2);
        markRect(occ, gw, gh, plot, cell, plot.x + plot.width - 4, plot.y, plot.x + plot.width, plot.y + plot.height, (byte) 2);

        if (sampleXs.length >= 2) {
            if (showOffFrontier) {
                for (int s = 0; s < sampleYs.length; s++) {
                    double[] ys = sampleYs[s];
                    double prevPx = xToPixel(sampleXs[0], plot);
                    double prevPy = yToPixel(ys[0], plot);
                    for (int i = 1; i < sampleXs.length; i++) {
                        double curPx = xToPixel(sampleXs[i], plot);
                        double curPy = yToPixel(ys[i], plot);
                        markSegment(occ, gw, gh, plot, cell, prevPx, prevPy, curPx, curPy, 3, (byte) 1);
                        prevPx = curPx;
                        prevPy = curPy;
                    }
                }
            }

            for (PlottedEnvelope.SegmentEntry entry : plotted.segments()) {
                double segStart = entry.startX();
                double segEnd = entry.endX();
                if (segEnd < viewMinX || segStart > viewMaxX) {
                    continue;
                }
                double lo = Math.max(viewMinX, segStart);
                double hi = Math.min(viewMaxX, segEnd);
                int idxStart = findNearestIndexGE(sampleXs, lo - 1e-13);
                int idxEnd = findNearestIndexLE(sampleXs, hi + 1e-13);
                double[] ys = sampleYs[entry.strategy().index()];
                if (idxStart <= idxEnd && idxStart < sampleXs.length && idxEnd >= 0) {
                    double prevPx = xToPixel(sampleXs[idxStart], plot);
                    double prevPy = yToPixel(ys[idxStart], plot);
                    for (int i = idxStart + 1; i <= idxEnd; i++) {
                        double curPx = xToPixel(sampleXs[i], plot);
                        double curPy = yToPixel(ys[i], plot);
                        markSegment(occ, gw, gh, plot, cell, prevPx, prevPy, curPx, curPy, 7, (byte) 2);
                        prevPx = curPx;
                        prevPy = curPy;
                    }
                }
            }
        }

        for (ExtremumScreenPoint pt : visible) {
            markSegment(occ, gw, gh, plot, cell, pt.px(), pt.py(), pt.px(), plot.y + plot.height, 2, (byte) 1);
            if (markHorizontalGuides) {
                markSegment(occ, gw, gh, plot, cell, plot.x, pt.py(), pt.px(), pt.py(), 2, (byte) 1);
            }
            markRect(occ, gw, gh, plot, cell,
                    (int) Math.floor(pt.px() - 10), (int) Math.floor(pt.py() - 10),
                    (int) Math.ceil(pt.px() + 10), (int) Math.ceil(pt.py() + 10),
                    (byte) 2);
        }
        return occ;
    }

    private PlacedExtremaLabel placeLabelInWhitespace(
            ExtremumScreenPoint pt,
            String text,
            int boxW,
            int boxH,
            Rectangle plot,
            byte[] occ,
            int cell,
            int gw,
            int gh) {
        if (boxW + 14 > plot.width || boxH + 14 > plot.height) {
            return null;
        }
        int minBx = plot.x + 6;
        int maxBx = plot.x + plot.width - boxW - 6;
        int minBy = plot.y + 6;
        int maxBy = plot.y + plot.height - boxH - 6;

        // Prefer placing above peaks (MAX) and below valleys (MIN), scanning outward rings in whitespace.
        double preferDy = pt.extremum().kind() == EnvelopeExtrema.Kind.MAX ? -1.0 : 1.0;
        double[][] dirs = {
                {0.0, preferDy},
                {0.55, preferDy * 0.85},
                {-0.55, preferDy * 0.85},
                {0.9, preferDy * 0.4},
                {-0.9, preferDy * 0.4},
                {1.0, 0.0},
                {-1.0, 0.0},
                {0.85, -preferDy * 0.5},
                {-0.85, -preferDy * 0.5},
                {0.0, -preferDy},
                {0.5, -preferDy * 0.85},
                {-0.5, -preferDy * 0.85}
        };
        int[] radii = {18, 30, 44, 62, 84, 110, 138};

        PlacedExtremaLabel best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int r : radii) {
            for (double[] dir : dirs) {
                double cx = pt.px() + dir[0] * (r + boxW * 0.35);
                double cy = pt.py() + dir[1] * (r + boxH * 0.55);
                int bx = Math.max(minBx, Math.min(maxBx, (int) Math.round(cx - boxW * 0.5)));
                int by = Math.max(minBy, Math.min(maxBy, (int) Math.round(cy - boxH * 0.5)));

                int c0 = Math.max(0, (bx - plot.x) / cell);
                int c1 = Math.min(gw - 1, (bx + boxW - plot.x) / cell);
                int r0 = Math.max(0, (by - plot.y) / cell);
                int r1 = Math.min(gh - 1, (by + boxH - plot.y) / cell);

                boolean blocked = false;
                int softHits = 0;
                for (int gy = r0; gy <= r1 && !blocked; gy++) {
                    int rowBase = gy * gw;
                    for (int gx = c0; gx <= c1; gx++) {
                        byte v = occ[rowBase + gx];
                        if (v >= 2) {
                            blocked = true;
                            break;
                        }
                        if (v == 1) {
                            softHits++;
                        }
                    }
                }
                if (blocked) {
                    continue;
                }

                double nearestX = Math.max(bx, Math.min(bx + boxW, pt.px()));
                double nearestY = Math.max(by, Math.min(by + boxH, pt.py()));
                double dist = Math.hypot(nearestX - pt.px(), nearestY - pt.py());
                double score = dist + softHits * 32.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = new PlacedExtremaLabel(pt, bx, by, boxW, boxH, text);
                }
            }
            // Take the clean minimum-soft-hit match in the closest clear ring when one has zero soft hits.
            if (best != null && bestScore < r + 40.0) {
                break;
            }
        }
        return best;
    }

    private static void markRect(
            byte[] occ,
            int gw,
            int gh,
            Rectangle plot,
            int cell,
            int x0,
            int y0,
            int x1,
            int y1,
            byte level) {
        int c0 = Math.max(0, (x0 - plot.x) / cell);
        int c1 = Math.min(gw - 1, (x1 - plot.x) / cell);
        int r0 = Math.max(0, (y0 - plot.y) / cell);
        int r1 = Math.min(gh - 1, (y1 - plot.y) / cell);
        for (int gy = r0; gy <= r1; gy++) {
            int rowBase = gy * gw;
            for (int gx = c0; gx <= c1; gx++) {
                if (level > occ[rowBase + gx]) {
                    occ[rowBase + gx] = level;
                }
            }
        }
    }

    private static void markSegment(
            byte[] occ,
            int gw,
            int gh,
            Rectangle plot,
            int cell,
            double x0,
            double y0,
            double x1,
            double y1,
            int padPx,
            byte level) {
        double dist = Math.hypot(x1 - x0, y1 - y0);
        int steps = Math.max(1, (int) Math.ceil(dist / Math.max(2.0, cell * 0.75)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int px = (int) Math.round(x0 + (x1 - x0) * t);
            int py = (int) Math.round(y0 + (y1 - y0) * t);
            markRect(occ, gw, gh, plot, cell, px - padPx, py - padPx, px + padPx, py + padPx, level);
        }
    }

    private static String formatExtremumLabel(EnvelopeExtrema.Extremum ex) {
        String kindLabel = ex.kind() == EnvelopeExtrema.Kind.MAX ? "Max" : "Min";
        return kindLabel + " (" + formatExtremumCoord(ex.x(), ex.exactX()) + ", "
                + formatExtremumCoord(ex.y(), ex.exactY()) + ")";
    }

    private static String formatExtremumCoord(double value, Fraction exact) {
        if (exact != null) {
            if (exact.isZero()) {
                return "0";
            }
            if (exact.isOne()) {
                return "1";
            }
            String fracStr = exact.toString();
            if (fracStr.length() <= 6) {
                return fracStr;
            }
        }
        if (Math.abs(value) < 1e-14) {
            return "0";
        }
        if (Math.abs(1.0 - value) < 1e-14) {
            return "1";
        }
        if (value > 0.0 && value < 1e-4) {
            return String.format(Locale.US, "%.2e", value);
        }
        if (value < 1.0 && 1.0 - value < 1e-4) {
            return String.format(Locale.US, "1-%.1e", 1.0 - value);
        }
        String formatted = String.format(Locale.US, "%.5f", value);
        int dot = formatted.indexOf('.');
        if (dot >= 0) {
            int end = formatted.length();
            while (end > dot + 2 && formatted.charAt(end - 1) == '0') {
                end--;
            }
            formatted = formatted.substring(0, end);
        }
        return formatted;
    }

    private void paintZoomBox(Graphics2D g2) {
        int x = Math.min(dragStartPixel.x, dragCurrentPixel.x);
        int y = Math.min(dragStartPixel.y, dragCurrentPixel.y);
        int w = Math.abs(dragCurrentPixel.x - dragStartPixel.x);
        int h = Math.abs(dragCurrentPixel.y - dragStartPixel.y);
        g2.setColor(ZOOM_BOX_FILL);
        g2.fillRect(x, y, w, h);
        g2.setColor(ZOOM_BOX_BORDER);
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRect(x, y, w, h);
    }

    private void paintHoverOverlay(Graphics2D g2, Rectangle plot, HoverReadout hover) {
        double px = xToPixel(hover.x(), plot);
        double py = yToPixel(hover.frontierY(), plot);

        g2.setColor(new Color(0x6B, 0x72, 0x80, 140));
        g2.setStroke(CROSSHAIR_STROKE);
        g2.drawLine((int) Math.round(px), plot.y, (int) Math.round(px), plot.y + plot.height);

        if (hover.activeSegment() != null && hover.frontierY() >= viewMinY && hover.frontierY() <= viewMaxY) {
            Color c = hover.activeSegment().strategy().color();
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(px - 5, py - 5, 10, 10));
            g2.setColor(c);
            g2.setStroke(new BasicStroke(2.2f));
            g2.draw(new Ellipse2D.Double(px - 5, py - 5, 10, 10));
        }

        // Draw floating HUD tooltip near cursor
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        lines.add(String.format(Locale.US, "x = %.5f", hover.x()));
        colors.add(null);

        if (hover.activeSegment() != null) {
            PlottedEnvelope.StrategyCurve win = hover.activeSegment().strategy();
            lines.add(String.format(Locale.US, "Frontier %s: %.6f", win.label(), hover.frontierY()));
            colors.add(win.color());
        }
        if (showOffFrontier && hover.rankedAtX().size() > 1) {
            int maxOthers = Math.min(5, hover.rankedAtX().size());
            for (int i = 0; i < maxOthers; i++) {
                StrategyValue sv = hover.rankedAtX().get(i);
                if (hover.activeSegment() != null && sv.strategy().index() == hover.activeSegment().strategy().index()) {
                    continue;
                }
                lines.add(String.format(Locale.US, "%s: %.6f", sv.strategy().label(), sv.y()));
                colors.add(sv.strategy().color());
            }
            if (hover.rankedAtX().size() > maxOthers) {
                lines.add("... (" + hover.rankedAtX().size() + " curves total)");
                colors.add(null);
            }
        }

        Font hudFont = g2.getFont().deriveFont(Font.PLAIN, 11.5f);
        g2.setFont(hudFont);
        FontMetrics fm = g2.getFontMetrics();
        int lineH = fm.getHeight() + 2;
        int boxW = 0;
        for (int i = 0; i < lines.size(); i++) {
            int w = fm.stringWidth(lines.get(i)) + (colors.get(i) != null ? 16 : 0);
            boxW = Math.max(boxW, w);
        }
        boxW += 18;
        int boxH = lines.size() * lineH + 12;

        int bx = (int) Math.round(px) + 14;
        if (bx + boxW > plot.x + plot.width - 4) {
            bx = (int) Math.round(px) - boxW - 14;
        }
        bx = Math.max(plot.x + 4, bx);
        int by = Math.max(plot.y + 6, Math.min(plot.y + plot.height - boxH - 6, (int) Math.round(py) - boxH / 2));

        g2.setColor(new Color(255, 255, 255, 238));
        g2.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 8, 8));
        g2.setColor(new Color(0xCB, 0xD5, 0xE1));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Double(bx, by, boxW, boxH, 8, 8));

        for (int i = 0; i < lines.size(); i++) {
            int tx = bx + 9;
            int ty = by + 8 + fm.getAscent() + i * lineH;
            Color swatch = colors.get(i);
            if (swatch != null) {
                g2.setColor(swatch);
                g2.fillRect(tx, ty - 9, 9, 9);
                tx += 14;
            }
            g2.setColor(i == 1 ? TEXT_DARK : TEXT_MUTED);
            g2.drawString(lines.get(i), tx, ty);
        }
    }

    private record YTickCandidate(double value, String label, int priority) {}

    private void paintLogTailYGridAndTicks(Graphics2D g2, Rectangle plot, FontMetrics fm) {
        List<YTickCandidate> candidates = new ArrayList<>();
        candidates.add(new YTickCandidate(0.0, "0", 0));
        candidates.add(new YTickCandidate(1.0, "1", 0));
        candidates.add(new YTickCandidate(1.0 / 3.0, "1/3", 0));
        candidates.add(new YTickCandidate(2.0 / 3.0, "2/3", 0));
        candidates.add(new YTickCandidate(0.5, "0.5", 1));
        candidates.add(new YTickCandidate(0.4, "0.4", 1));
        candidates.add(new YTickCandidate(0.6, "0.6", 1));

        double midLo = Math.max(1.0 / 3.0, viewMinY);
        double midHi = Math.min(2.0 / 3.0, viewMaxY);
        if (midHi > midLo + 1e-9) {
            double midStep = niceTickStep(midHi - midLo);
            int dec = decimalsForStep(midStep);
            for (double y = Math.ceil(midLo / midStep - 1e-9) * midStep; y <= midHi + 1e-9; y += midStep) {
                if (y > 1.0 / 3.0 + 1e-6 && y < 2.0 / 3.0 - 1e-6) {
                    candidates.add(new YTickCandidate(y, formatTick(y, dec), 2));
                }
            }
        }

        int maxExp = Math.max(3, (int) Math.ceil(logTailDecades) + 3);
        for (int e = 1; e <= maxExp; e++) {
            double pow = Math.pow(10.0, -e);
            if (pow < 1.0 / 3.0 - 1e-9) {
                candidates.add(new YTickCandidate(pow, formatSmallPowerLabel(pow, e), 1));
                candidates.add(new YTickCandidate(1.0 - pow, formatNearOneLabel(pow, e), 1));
            }
            for (int m : new int[]{2, 3, 5}) {
                double yLo = m * pow;
                if (yLo > 0.0 && yLo < 1.0 / 3.0 - 1e-9) {
                    candidates.add(new YTickCandidate(yLo, formatSmallMultipleLabel(m, e), 2));
                }
                double yHi = 1.0 - m * pow;
                if (yHi > 2.0 / 3.0 + 1e-9 && yHi < 1.0) {
                    candidates.add(new YTickCandidate(yHi, formatNearOneMultipleLabel(m, pow, e), 2));
                }
            }
        }

        // Filter to visible range and deduplicate nearly-equal tick values keeping highest priority.
        List<YTickCandidate> visible = new ArrayList<>();
        for (YTickCandidate c : candidates) {
            if (c.value() >= viewMinY - 1e-12 && c.value() <= viewMaxY + 1e-12) {
                visible.add(c);
            }
        }
        visible.sort(Comparator.comparingInt(YTickCandidate::priority).thenComparingDouble(YTickCandidate::value));

        List<YTickCandidate> labeled = new ArrayList<>();
        List<Double> labeledPixels = new ArrayList<>();
        List<YTickCandidate> minorLines = new ArrayList<>();

        for (YTickCandidate c : visible) {
            double py = yToPixel(c.value(), plot);
            boolean tooClose = false;
            for (double existingPy : labeledPixels) {
                if (Math.abs(py - existingPy) < 22.0) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                labeled.add(c);
                labeledPixels.add(py);
            } else if (c.priority() <= 2) {
                minorLines.add(c);
            }
        }

        // If deeply zoomed into an interval with very few standard ticks, supplement linearly in t-space.
        if (labeled.size() < 3) {
            double tMin = mapY(viewMinY);
            double tMax = mapY(viewMaxY);
            int extraCount = 5;
            for (int i = 0; i <= extraCount; i++) {
                double t = tMin + (tMax - tMin) * i / extraCount;
                double y = unmapY(t);
                double py = yToPixel(y, plot);
                boolean tooClose = false;
                for (double existingPy : labeledPixels) {
                    if (Math.abs(py - existingPy) < 24.0) {
                        tooClose = true;
                        break;
                    }
                }
                if (!tooClose) {
                    labeled.add(new YTickCandidate(y, formatCompactProbability(y), 3));
                    labeledPixels.add(py);
                }
            }
        }

        // Draw minor gridlines first
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(GRID_MINOR);
        for (YTickCandidate c : minorLines) {
            double py = yToPixel(c.value(), plot);
            g2.drawLine(plot.x, (int) Math.round(py), plot.x + plot.width, (int) Math.round(py));
        }

        // Draw major gridlines & labels
        for (YTickCandidate c : labeled) {
            double py = yToPixel(c.value(), plot);
            if (Math.abs(c.value() - 1.0 / 3.0) < 1e-12 || Math.abs(c.value() - 2.0 / 3.0) < 1e-12) {
                g2.setColor(TAIL_DIVIDER_COLOR);
                g2.setStroke(TAIL_DIVIDER_STROKE);
            } else {
                g2.setColor(GRID_MAJOR);
                g2.setStroke(new BasicStroke(1f));
            }
            g2.drawLine(plot.x, (int) Math.round(py), plot.x + plot.width, (int) Math.round(py));

            int lw = fm.stringWidth(c.label());
            g2.setColor(c.priority() == 0 ? TEXT_DARK : TEXT_MUTED);
            g2.drawString(c.label(), plot.x - lw - 8, (float) (py + fm.getAscent() / 2.0 - 1));
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private static String formatSmallPowerLabel(double val, int e) {
        return e <= 4 ? formatTick(val, e) : ("1e-" + e);
    }

    private static String formatSmallMultipleLabel(int mult, int e) {
        return e <= 4 ? formatTick(mult * Math.pow(10.0, -e), e) : (mult + "e-" + e);
    }

    private static String formatNearOneLabel(double dist, int e) {
        return e <= 4 ? formatTick(1.0 - dist, e) : ("1-1e-" + e);
    }

    private static String formatNearOneMultipleLabel(int mult, double pow, int e) {
        return e <= 4 ? formatTick(1.0 - mult * pow, e) : ("1-" + mult + "e-" + e);
    }

    private static String formatCompactProbability(double y) {
        if (y < 1e-4 && y > 0.0) {
            return String.format(Locale.US, "%.2e", y);
        }
        if (1.0 - y < 1e-4 && y < 1.0) {
            return String.format(Locale.US, "1-%.1e", 1.0 - y);
        }
        return String.format(Locale.US, "%.5f", y);
    }

    private static int findNearestIndexGE(double[] arr, double target) {
        int pos = Arrays.binarySearch(arr, target);
        return pos >= 0 ? pos : -pos - 1;
    }

    private static int findNearestIndexLE(double[] arr, double target) {
        int pos = Arrays.binarySearch(arr, target);
        return pos >= 0 ? pos : -pos - 2;
    }

    private static double niceTickStep(double span) {
        double raw = span / 5.0;
        double pow10 = Math.pow(10.0, Math.floor(Math.log10(Math.max(1e-12, raw))));
        double norm = raw / pow10;
        double step;
        if (norm <= 1.0) {
            step = 1.0;
        } else if (norm <= 2.0) {
            step = 2.0;
        } else if (norm <= 5.0) {
            step = 5.0;
        } else {
            step = 10.0;
        }
        return step * pow10;
    }

    private static int decimalsForStep(double step) {
        int exp = (int) Math.floor(Math.log10(step) + 1e-9);
        return Math.max(0, -exp);
    }

    private static String formatTick(double v, int decimals) {
        if (Math.abs(v) < 1e-12) {
            v = 0.0;
        }
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class ShapeRestore {
        private final java.awt.Shape prevClip;

        ShapeRestore(Graphics2D g2) {
            this.prevClip = g2.getClip();
        }

        void restore(Graphics2D g2) {
            g2.setClip(prevClip);
        }
    }
}
