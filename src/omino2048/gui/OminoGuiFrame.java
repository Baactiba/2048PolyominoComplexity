package omino2048.gui;

import omino2048.EnvelopeResult;
import omino2048.EnvelopeSegment;
import omino2048.EnvelopeSolver;
import omino2048.Polyomino;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Main Swing window combining a 7x7 polyomino grid builder, tile goal selector,
 * background exact envelope solver controls, strategy color legend, and interactive chart.
 */
public final class OminoGuiFrame extends JFrame {
    private static final Integer[] TILE_GOALS = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048};
    private static final int MAX_SUPPORTED_CELLS = 12;

    private final PolyominoGridPanel gridPanel;
    private final JLabel shapeInfoLabel = new JLabel();
    private final JComboBox<Integer> goalCombo = new JComboBox<>(TILE_GOALS);
    private final JSpinner threadsSpinner = new JSpinner(new SpinnerNumberModel(
            EnvelopeSolver.defaultThreads(),
            1,
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            1));
    private final JButton solveButton = new JButton("Solve Envelope");
    private final JButton cancelButton = new JButton("Cancel");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel solverStatusLabel = new JLabel("Ready.");

    private final JCheckBox showOffFrontierCheck = new JCheckBox("Show non-frontier polynomial curves", true);
    private final JCheckBox showBreakpointsCheck = new JCheckBox("Breakpoints", true);
    private final JCheckBox showExtremaCheck = new JCheckBox("Min / Max extrema", false);
    private final JCheckBox logTailCheck = new JCheckBox("Log-tail Y (middle 1/3 linear)", true);
    private final JTextField logScaleField = new JTextField("3", 4);
    private final JButton resetZoomButton = new JButton("Reset Zoom");
    private final JButton copySummaryButton = new JButton("Copy Summary");
    private final JLabel plotHeaderLabel = new JLabel("No envelope solved yet");

    private final DefaultListModel<PlottedEnvelope.StrategyCurve> legendModel = new DefaultListModel<>();
    private final JList<PlottedEnvelope.StrategyCurve> legendList = new JList<>(legendModel);
    private final JButton clearHighlightButton = new JButton("Clear Selection");

    private final JTextArea inspectorArea = new JTextArea(3, 40);
    private final EnvelopePlotPanel plotPanel;

    private SwingWorker<SolveOutput, EnvelopeSolver.ProgressUpdate> activeWorker;
    private PlottedEnvelope currentPlotted;

    private record SolveOutput(PlottedEnvelope plotted, long elapsedMs) {}

    public OminoGuiFrame() {
        super("2048 Polyomino Exact Success Envelope Explorer");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        this.plotPanel = new EnvelopePlotPanel(this::onPlotHover, this::onViewBoundsChanged);
        this.gridPanel = new PolyominoGridPanel(this::onGridChanged);

        initComponents();
        layoutWindow();

        // Start with a multi-strategy preset (1x4) so solving right away demonstrates colored transitions.
        gridPanel.setCenteredPattern("####");
        goalCombo.setSelectedItem(16);
        updateShapeValidationUI();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelSolveIfRunning();
            }
        });

        setSize(1220, 780);
        setMinimumSize(new Dimension(920, 620));
        setLocationRelativeTo(null);
    }

    private void initComponents() {
        goalCombo.setSelectedItem(16);
        goalCombo.setFont(goalCombo.getFont().deriveFont(Font.BOLD, 13f));

        solveButton.setFont(solveButton.getFont().deriveFont(Font.BOLD, 13f));
        solveButton.addActionListener(e -> startSolve());

        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> cancelSolveIfRunning());

        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        progressBar.setString("Idle");

        solverStatusLabel.setFont(solverStatusLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        solverStatusLabel.setForeground(new Color(0x4B, 0x55, 0x63));

        shapeInfoLabel.setFont(shapeInfoLabel.getFont().deriveFont(Font.PLAIN, 12f));

        showOffFrontierCheck.setFont(showOffFrontierCheck.getFont().deriveFont(Font.BOLD, 12f));
        showOffFrontierCheck.setToolTipText(
                "When checked, every distinct strategy polynomial is drawn across all x in [0, 1] "
                        + "(solid on the frontier, dashed elsewhere). Unchecked shows only the optimal frontier.");
        showOffFrontierCheck.addItemListener(e -> plotPanel.setShowOffFrontier(showOffFrontierCheck.isSelected()));

        showBreakpointsCheck.addItemListener(e -> plotPanel.setShowBreakpoints(showBreakpointsCheck.isSelected()));

        showExtremaCheck.setToolTipText(
                "Show local & global minimums and maximums on the envelope with red guide lines, points, "
                        + "and (x, y) coordinate labels in open whitespace.");
        showExtremaCheck.addItemListener(e -> plotPanel.setShowExtrema(showExtremaCheck.isSelected()));

        logTailCheck.setToolTipText(
                "Middle 1/3 [1/3, 2/3] uses linear Y scaling; [0, 1/3] and [2/3, 1] expand logarithmically toward 0 and 1.");
        logTailCheck.addItemListener(e -> {
            boolean enabled = logTailCheck.isSelected();
            logScaleField.setEnabled(enabled);
            plotPanel.setLogTailY(enabled);
        });

        logScaleField.setHorizontalAlignment(JTextField.RIGHT);
        logScaleField.setToolTipText("Tail log decades in (0, 15], e.g. 2, 3, 4.5 (press Enter to apply)");
        logScaleField.addActionListener(e -> applyLogScaleFromField());
        logScaleField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyLogScaleFromField();
            }
        });

        resetZoomButton.setEnabled(false);
        resetZoomButton.addActionListener(e -> plotPanel.resetZoom());

        copySummaryButton.setEnabled(false);
        copySummaryButton.addActionListener(e -> copyEnvelopeSummary());

        plotHeaderLabel.setFont(plotHeaderLabel.getFont().deriveFont(Font.BOLD, 13.5f));
        plotHeaderLabel.setForeground(new Color(0x11, 0x18, 0x27));

        legendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        legendList.setCellRenderer(new StrategyCellRenderer());
        legendList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int sel = legendList.getSelectedIndex();
                plotPanel.setHighlightedStrategyIndex(sel);
                clearHighlightButton.setEnabled(sel >= 0);
                updateInspectorFromSelection();
            }
        });

        clearHighlightButton.setEnabled(false);
        clearHighlightButton.addActionListener(e -> legendList.clearSelection());

        inspectorArea.setEditable(false);
        inspectorArea.setLineWrap(true);
        inspectorArea.setWrapStyleWord(true);
        inspectorArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inspectorArea.setBackground(new Color(0xF9, 0xFA, 0xFB));
        inspectorArea.setText("Draw or select a polyomino, choose a tile goal, and click Solve Envelope.\n"
                + "Tip: Drag on the chart to box-zoom, scroll mouse wheel to zoom, Shift/Right-drag to pan, double-click to reset.");
    }

    private void layoutWindow() {
        JPanel leftPanel = buildLeftSidebar();
        JPanel rightPanel = buildRightPlotArea();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setDividerLocation(360);
        split.setResizeWeight(0.0);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        setContentPane(split);
    }

    private JPanel buildLeftSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 10));
        sidebar.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 8));
        sidebar.setMinimumSize(new Dimension(320, 400));
        sidebar.setPreferredSize(new Dimension(360, 740));

        // Top stack: Grid Builder + Presets + Solver Controls
        JPanel topStack = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // 1. Polyomino Grid Card
        JPanel gridCard = new JPanel(new BorderLayout(0, 6));
        gridCard.setBorder(BorderFactory.createTitledBorder("1. Draw Polyomino (7x7 Grid)"));

        JPanel gridCenterWrap = new JPanel(new BorderLayout());
        gridCenterWrap.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        gridCenterWrap.add(gridPanel, BorderLayout.CENTER);
        gridCard.add(gridCenterWrap, BorderLayout.CENTER);

        JPanel infoAndPresets = new JPanel(new BorderLayout(0, 6));
        infoAndPresets.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
        infoAndPresets.add(shapeInfoLabel, BorderLayout.NORTH);
        infoAndPresets.add(buildPresetButtonsPanel(), BorderLayout.CENTER);
        gridCard.add(infoAndPresets, BorderLayout.SOUTH);

        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 8, 0);
        topStack.add(gridCard, gbc);

        // 2. Goal & Solve Controls Card
        JPanel solveCard = new JPanel(new GridBagLayout());
        solveCard.setBorder(BorderFactory.createTitledBorder("2. Goal & Solve"));
        GridBagConstraints sc = new GridBagConstraints();
        sc.insets = new Insets(4, 6, 4, 6);
        sc.fill = GridBagConstraints.HORIZONTAL;

        sc.gridx = 0;
        sc.gridy = 0;
        sc.weightx = 0.0;
        solveCard.add(new JLabel("Tile Goal:"), sc);

        sc.gridx = 1;
        sc.weightx = 1.0;
        solveCard.add(goalCombo, sc);

        sc.gridx = 0;
        sc.gridy = 1;
        sc.weightx = 0.0;
        solveCard.add(new JLabel("Threads:"), sc);

        sc.gridx = 1;
        sc.weightx = 1.0;
        solveCard.add(threadsSpinner, sc);

        JPanel buttonRow = new JPanel(new GridLayout(1, 2, 8, 0));
        buttonRow.add(solveButton);
        buttonRow.add(cancelButton);
        sc.gridx = 0;
        sc.gridy = 2;
        sc.gridwidth = 2;
        sc.weightx = 1.0;
        solveCard.add(buttonRow, sc);

        sc.gridy = 3;
        solveCard.add(progressBar, sc);

        sc.gridy = 4;
        solveCard.add(solverStatusLabel, sc);

        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 8, 0);
        topStack.add(solveCard, gbc);

        sidebar.add(topStack, BorderLayout.NORTH);

        // 3. Strategy Legend List (takes remaining height)
        JPanel legendCard = new JPanel(new BorderLayout(0, 6));
        legendCard.setBorder(BorderFactory.createTitledBorder("3. Optimal Strategy Polynomials"));

        JPanel legendHeader = new JPanel(new BorderLayout(6, 0));
        legendHeader.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 6));
        JLabel hintLabel = new JLabel("Click a strategy to highlight it:");
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        hintLabel.setForeground(new Color(0x4B, 0x55, 0x63));
        legendHeader.add(hintLabel, BorderLayout.WEST);
        legendHeader.add(clearHighlightButton, BorderLayout.EAST);

        legendCard.add(legendHeader, BorderLayout.NORTH);
        JScrollPane legendScroll = new JScrollPane(
                legendList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        legendCard.add(legendScroll, BorderLayout.CENTER);

        sidebar.add(legendCard, BorderLayout.CENTER);
        return sidebar;
    }

    private JPanel buildPresetButtonsPanel() {
        JPanel presets = new JPanel(new GridLayout(3, 3, 4, 4));
        addPresetButton(presets, "1x2", "##");
        addPresetButton(presets, "1x3", "###");
        addPresetButton(presets, "L3", "#.", "##");
        addPresetButton(presets, "1x4", "####");
        addPresetButton(presets, "2x2", "##", "##");
        addPresetButton(presets, "T4", "###", ".#.");
        addPresetButton(presets, "L4", "#.", "#.", "##");
        addPresetButton(presets, "S4", ".##", "##.");

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> gridPanel.clear());
        presets.add(clearBtn);
        return presets;
    }

    private void addPresetButton(JPanel container, String name, String... pattern) {
        JButton btn = new JButton(name);
        btn.setMargin(new Insets(2, 4, 2, 4));
        btn.addActionListener(e -> gridPanel.setCenteredPattern(pattern));
        container.add(btn);
    }

    private JPanel buildRightPlotArea() {
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setBorder(BorderFactory.createEmptyBorder(12, 4, 12, 12));

        // Top toolbar over graph
        JPanel headerBar = new JPanel(new BorderLayout(8, 2));
        headerBar.add(plotHeaderLabel, BorderLayout.NORTH);

        JPanel toolbarRows = new JPanel(new GridLayout(2, 1, 0, 2));
        JPanel controlsRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 1));
        controlsRow1.add(showOffFrontierCheck);
        controlsRow1.add(showBreakpointsCheck);
        controlsRow1.add(showExtremaCheck);
        controlsRow1.add(resetZoomButton);
        controlsRow1.add(copySummaryButton);

        JPanel controlsRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 1));
        controlsRow2.add(logTailCheck);
        controlsRow2.add(new JLabel("Log decades:"));
        controlsRow2.add(logScaleField);

        toolbarRows.add(controlsRow1);
        toolbarRows.add(controlsRow2);
        headerBar.add(toolbarRows, BorderLayout.CENTER);

        right.add(headerBar, BorderLayout.NORTH);

        // Plot container with crisp border
        JPanel plotBorderWrap = new JPanel(new BorderLayout());
        plotBorderWrap.setBorder(BorderFactory.createLineBorder(new Color(0xD1, 0xD5, 0xDB)));
        plotBorderWrap.add(plotPanel, BorderLayout.CENTER);
        right.add(plotBorderWrap, BorderLayout.CENTER);

        // Bottom exact inspector
        JScrollPane inspectorScroll = new JScrollPane(inspectorArea);
        inspectorScroll.setBorder(BorderFactory.createTitledBorder("Exact Polynomial / Cursor Inspector"));
        inspectorScroll.setPreferredSize(new Dimension(400, 105));
        right.add(inspectorScroll, BorderLayout.SOUTH);

        return right;
    }

    private void onGridChanged() {
        updateShapeValidationUI();
    }

    private void updateShapeValidationUI() {
        int count = gridPanel.cellCount();
        boolean workerRunning = activeWorker != null && !activeWorker.isDone();
        if (count < 2) {
            shapeInfoLabel.setForeground(new Color(0xB9, 0x1C, 0x1C));
            shapeInfoLabel.setText(count + " cell(s) selected — select at least 2 cells.");
            solveButton.setEnabled(false);
            return;
        }
        if (count > MAX_SUPPORTED_CELLS) {
            shapeInfoLabel.setForeground(new Color(0xB9, 0x1C, 0x1C));
            shapeInfoLabel.setText(count + " cells selected — max supported is " + MAX_SUPPORTED_CELLS + ".");
            solveButton.setEnabled(false);
            return;
        }

        Optional<Polyomino> optPoly = gridPanel.buildPolyomino();
        if (optPoly.isEmpty()) {
            solveButton.setEnabled(false);
            return;
        }
        Polyomino poly = optPoly.get();
        String dims = poly.rows() + "x" + poly.cols();
        if (!poly.isConnected()) {
            shapeInfoLabel.setForeground(new Color(0xB4, 0x53, 0x09));
            shapeInfoLabel.setText(count + " cells (" + dims + ") • Disconnected (gaps act as walls)");
        } else if (count >= 6) {
            shapeInfoLabel.setForeground(new Color(0xB4, 0x53, 0x09));
            shapeInfoLabel.setText(count + " cells (" + dims + ") • Connected (may take longer)");
        } else {
            shapeInfoLabel.setForeground(new Color(0x15, 0x80, 0x3D));
            shapeInfoLabel.setText(count + " cells (" + dims + ") • Connected");
        }
        solveButton.setEnabled(!workerRunning);
    }

    private void startSolve() {
        Optional<Polyomino> optPoly = gridPanel.buildPolyomino();
        if (optPoly.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select at least 2 cells on the grid.",
                    "Invalid Polyomino", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Polyomino poly = optPoly.get();
        if (poly.size() > MAX_SUPPORTED_CELLS) {
            JOptionPane.showMessageDialog(this, "At most " + MAX_SUPPORTED_CELLS + " cells are supported.",
                    "Too Many Cells", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Integer goal = (Integer) goalCombo.getSelectedItem();
        if (goal == null) {
            return;
        }
        int threads = ((Number) threadsSpinner.getValue()).intValue();

        cancelSolveIfRunning();

        solveButton.setEnabled(false);
        cancelButton.setEnabled(true);
        goalCombo.setEnabled(false);
        threadsSpinner.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setString("Starting...");
        solverStatusLabel.setText("Initializing exact DP solver (" + threads + (threads == 1 ? " thread)..." : " threads)..."));

        activeWorker = new SwingWorker<>() {
            @Override
            protected SolveOutput doInBackground() {
                long t0 = System.nanoTime();
                EnvelopeResult env = EnvelopeSolver.solve(poly, goal, threads, this::publish);
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Solve cancelled");
                }
                PlottedEnvelope plotted = PlottedEnvelope.from(env);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                return new SolveOutput(plotted, elapsedMs);
            }

            @Override
            protected void process(List<EnvelopeSolver.ProgressUpdate> chunks) {
                if (chunks.isEmpty() || isCancelled()) {
                    return;
                }
                EnvelopeSolver.ProgressUpdate latest = chunks.get(chunks.size() - 1);
                int pct = (int) Math.round(latest.progressFraction() * 100.0);
                progressBar.setValue(Math.max(0, Math.min(100, pct)));
                progressBar.setString(pct + "%");
                solverStatusLabel.setText(latest.phase());
            }

            @Override
            protected void done() {
                cancelButton.setEnabled(false);
                goalCombo.setEnabled(true);
                threadsSpinner.setEnabled(true);
                updateShapeValidationUI();

                if (isCancelled()) {
                    progressBar.setValue(0);
                    progressBar.setString("Cancelled");
                    solverStatusLabel.setText("Solve cancelled.");
                    return;
                }
                try {
                    SolveOutput out = get();
                    applySolvedEnvelope(out.plotted(), out.elapsedMs());
                } catch (CancellationException ex) {
                    progressBar.setValue(0);
                    progressBar.setString("Cancelled");
                    solverStatusLabel.setText("Solve cancelled.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    progressBar.setValue(0);
                    progressBar.setString("Interrupted");
                    solverStatusLabel.setText("Solve interrupted.");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof CancellationException) {
                        progressBar.setValue(0);
                        progressBar.setString("Cancelled");
                        solverStatusLabel.setText("Solve cancelled.");
                    } else {
                        progressBar.setValue(0);
                        progressBar.setString("Error");
                        solverStatusLabel.setText("Error: " + cause.getMessage());
                        JOptionPane.showMessageDialog(OminoGuiFrame.this,
                                "Solve failed:\n" + cause.getMessage(),
                                "Solver Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        };
        activeWorker.execute();
    }

    private void cancelSolveIfRunning() {
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
        }
    }

    private void applySolvedEnvelope(PlottedEnvelope plotted, long elapsedMs) {
        this.currentPlotted = plotted;
        EnvelopeResult env = plotted.result();

        progressBar.setValue(100);
        progressBar.setString("100%");
        solverStatusLabel.setText(String.format(
                Locale.US,
                "Solved in %.2f s (%d solver calls)",
                elapsedMs / 1000.0,
                env.solverCalls()));

        plotHeaderLabel.setText(String.format(
                Locale.US,
                "%dx%d Polyomino (%d cells)  •  Goal: %d  •  Distinct strategies: %d  •  Segments: %d  •  Max deg: %d",
                env.polyomino().rows(),
                env.polyomino().cols(),
                env.polyomino().size(),
                env.tileGoal(),
                env.distinctPolynomials().size(),
                env.segments().size(),
                env.maxPolynomialDegree()));

        legendModel.clear();
        for (PlottedEnvelope.StrategyCurve s : plotted.strategies()) {
            legendModel.addElement(s);
        }
        clearHighlightButton.setEnabled(false);
        copySummaryButton.setEnabled(true);

        plotPanel.setPlottedEnvelope(plotted);
        plotPanel.setShowOffFrontier(showOffFrontierCheck.isSelected());
        plotPanel.setShowBreakpoints(showBreakpointsCheck.isSelected());
        plotPanel.setShowExtrema(showExtremaCheck.isSelected());
        plotPanel.setLogTailY(logTailCheck.isSelected());
        applyLogScaleFromField();

        updateInspectorDefaultSummary();
    }

    private void applyLogScaleFromField() {
        String text = logScaleField.getText().trim();
        try {
            double parsed = Double.parseDouble(text);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed <= 0.0 || parsed > 15.0) {
                throw new NumberFormatException("Out of range (0, 15]");
            }
            logScaleField.setBackground(UIManager.getColor("TextField.background"));
            plotPanel.setLogTailDecades(parsed);
        } catch (NumberFormatException ex) {
            logScaleField.setBackground(new Color(0xFE, 0xE2, 0xE2));
            logScaleField.setText(formatScaleDisplay(plotPanel.logTailDecades()));
            solverStatusLabel.setText("Log decades must be a number in (0, 15]. Reverted.");
        }
    }

    private static String formatScaleDisplay(double val) {
        if (Math.abs(val - Math.rint(val)) < 1e-9) {
            return Long.toString(Math.round(val));
        }
        return Double.toString(val);
    }

    private void onViewBoundsChanged() {
        resetZoomButton.setEnabled(plotPanel.isZoomed());
    }

    private void onPlotHover(EnvelopePlotPanel.HoverReadout hover) {
        if (legendList.getSelectedIndex() >= 0) {
            return;
        }
        if (hover == null || currentPlotted == null) {
            updateInspectorDefaultSummary();
            return;
        }
        PlottedEnvelope.SegmentEntry active = hover.activeSegment();
        if (active == null) {
            return;
        }
        EnvelopeSegment seg = active.segment();
        PlottedEnvelope.StrategyCurve s = active.strategy();
        inspectorArea.setText(String.format(
                Locale.US,
                "Cursor x = %.6f | Optimal frontier: %s = %.8f | Segment: [%s, %s]%nP(x) = %s",
                hover.x(),
                s.label(),
                hover.frontierY(),
                seg.formatStart(),
                seg.formatEnd(),
                s.polynomial()));
        inspectorArea.setCaretPosition(0);
    }

    private void updateInspectorFromSelection() {
        PlottedEnvelope.StrategyCurve selected = legendList.getSelectedValue();
        if (selected == null) {
            updateInspectorDefaultSummary();
            return;
        }
        inspectorArea.setText(String.format(
                Locale.US,
                "%s (deg %d) | Frontier intervals: %s%nP(x) = %s",
                selected.label(),
                selected.polynomial().degree(),
                selected.activeIntervalsSummary(),
                selected.polynomial()));
        inspectorArea.setCaretPosition(0);
    }

    private void updateInspectorDefaultSummary() {
        if (currentPlotted == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        List<PlottedEnvelope.SegmentEntry> segs = currentPlotted.segments();
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            PlottedEnvelope.SegmentEntry seg = segs.get(i);
            sb.append(String.format(
                    "[%s, %s] -> %s: %s",
                    seg.segment().formatStart(),
                    seg.segment().formatEnd(),
                    seg.strategy().label(),
                    seg.strategy().polynomial()));
        }
        inspectorArea.setText(sb.toString());
        inspectorArea.setCaretPosition(0);
    }

    private void copyEnvelopeSummary() {
        if (currentPlotted == null) {
            return;
        }
        EnvelopeResult env = currentPlotted.result();
        StringBuilder sb = new StringBuilder();
        sb.append("Polyomino:\n").append(env.polyomino()).append('\n');
        sb.append("Goal: ").append(env.tileGoal())
                .append(", Distinct strategies: ").append(env.distinctPolynomials().size())
                .append(", Segments: ").append(env.segments().size())
                .append(", Max degree: ").append(env.maxPolynomialDegree()).append('\n');
        for (PlottedEnvelope.SegmentEntry seg : currentPlotted.segments()) {
            sb.append("  [").append(seg.segment().formatStart())
                    .append(", ").append(seg.segment().formatEnd()).append("] -> ")
                    .append(seg.strategy().label()).append(": ")
                    .append(seg.strategy().polynomial()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        solverStatusLabel.setText("Copied exact envelope summary to clipboard.");
    }

    private static final class StrategyCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JPanel row = new JPanel(new BorderLayout(8, 2));
            row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            row.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());

            if (value instanceof PlottedEnvelope.StrategyCurve s) {
                JPanel swatch = new JPanel();
                swatch.setBackground(s.color());
                swatch.setPreferredSize(new Dimension(14, 14));
                swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

                JPanel swatchWrap = new JPanel(new GridBagLayout());
                swatchWrap.setOpaque(false);
                swatchWrap.add(swatch);
                row.add(swatchWrap, BorderLayout.WEST);

                JPanel textStack = new JPanel(new GridLayout(2, 1, 0, 1));
                textStack.setOpaque(false);

                String titleText = s.label() + " (deg " + Math.max(0, s.polynomial().degree()) + ")  "
                        + s.activeIntervalsSummary();
                JLabel top = new JLabel(titleText);
                top.setFont(top.getFont().deriveFont(Font.BOLD, 11.5f));
                top.setForeground(isSelected ? list.getSelectionForeground() : new Color(0x11, 0x18, 0x27));

                String polyStr = s.polynomial().toString();
                if (polyStr.length() > 34) {
                    polyStr = polyStr.substring(0, 31) + "...";
                }
                JLabel bottom = new JLabel(polyStr);
                bottom.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                bottom.setForeground(isSelected ? list.getSelectionForeground() : new Color(0x4B, 0x55, 0x63));

                textStack.add(top);
                textStack.add(bottom);
                row.add(textStack, BorderLayout.CENTER);
                return row;
            }
            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Fall back to default Swing look & feel.
            }
            new OminoGuiFrame().setVisible(true);
        });
    }
}
