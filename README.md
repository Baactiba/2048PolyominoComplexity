I created this project with Gemini 3.8 Flash in Antigravity, below is the agent's Readme, but my notes are going to be here at the top of the file.

This project can be used for small polyomino 2048 variants in order to find the success rate of a tile at any given spawn rate. The main goal of this project was to see which variants are the most complex in terms of how many different strategies can be optimal. 

If you have Java 17+, you should be able to download the code and then build it with the commands that follow below, and run the program with the .bat file, or you can run it through command prompt. If you want to use the .bat, then I would recommend changing "-Xmx32G" to whatever the maximum amount of memory you want to use is (I have it set to 32 gigabytes for my computer).

Brief analysis of the algorithm, which I came up with and directed Antigravity to implement: First, the tables are solved for the polyomino for one arbitrary spawn rate. While we solve, we can also mark each solved board with a polynomial that represents its success chance, eventually obtaining one or multiple polynomials that represent the optimal strategies at a specific spawn rate. We also solve for the highest suboptimal success rate. Based on the maximum degree of these polynomials and the difference between the optimal and maximum suboptimal success rates, we can calculate a delta-spawnrate at which we can increase and decrease our currently checked spawn rate by and then find the optimal polynomials for those spawn rates and so on. At the end, we are left with many optimal polynomials and we just need to figure out where they intersect and which ones are the maximum. This part is fairly simple. The result is a piecewise that represents the maximum success rate at any spawn rate.




# 2048 Polyomino Complexity

Exact algebraic and numerical analysis of optimal play, strategy transitions, and success probability upper envelopes for arbitrary polyomino board shapes in the 2048 game as a function of the 4-tile spawn rate $x \in [0, 1]$.

![2048 Polyomino Complexity GUI](https://raw.githubusercontent.com/Baactiba/2048PolyominoComplexity/main/Launch2048OminoGUI.bat?raw=true#screenshot)

## Overview

In generalized 2048 on an arbitrary polyomino board:
- Success probability under optimal play is piecewise algebraic—specifically, a upper envelope of rational polynomials $P_i(x)$ over spawn rates $x \in [0, 1]$.
- Each piece corresponds to a distinct Markov Decision Process policy (strategy).
- This project provides both an **exact algebraic solver engine** and an **interactive Swing GUI** to draw arbitrary polyomino boards, solve their exact envelopes, and visualize strategy switches, breakpoints, and extrema.

---

## Features

### 1. High-Performance Exact & Certified Solver Engine
- **Certified DP with Error Tracking**: IEEE-754 error-bounded backward DP combined with exact rational polynomial reconstruction (`FastPoly`) and symmetry/tie-closure verification.
- **Fast Real Root Isolation**: Replaces slow Sturm chains with Vincent–Collins–Akritas (VCA Descartes bisection), Bernstein basis sign filtering, and modular square-free certification (`ExactRootFinder`).
- **Parallel Divide-and-Conquer Envelope Construction**: Deadlock-free tree merge of algebraic upper envelopes across arbitrary thread counts.
- **Incremental Crossing Verification**: Stern–Brocot rational checkpointing and multi-round refinement to capture narrow strategy slivers down to $10^{-6}$ width.

### 2. Interactive Swing GUI (`OminoGuiFrame`)
- **7x7 Interactive Grid Builder**: Click cells to toggle polyomino tiles, with shape presets (1x2 domino, 1x3 / L trominoes, 1x4 / 2x2 / T / L / S tetrominoes).
- **Tile Goal Selector**: Target goals from 2 to 2048.
- **Multi-Threaded Execution**: Configurable worker pool with live percentage, phase reporting, and clean cooperative cancellation.
- **Frontier & Extension Visualization**:
  - High-contrast color palette mapping each distinct optimal strategy to a unique color.
  - Checkbox toggle to view off-frontier extensions of every strategy (dashed lines across all $x$).
  - Breakpoint markers at strategy switch points.
- **Custom Log-Tail Y Scaling**:
  - Middle 1/3 of the vertical axis $[1/3, 2/3]$ is linear.
  - Outer thirds $[0, 1/3]$ and $[2/3, 1]$ expand logarithmically toward the edges for extreme dynamic range near 0 and 1.
  - Scale factor adjustable via text box or toggleable back to purely linear.
- **Min / Max Extrema Overlay**:
  - Identifies boundary extrema ($x=0, 1$), smooth interior extrema ($P'(x)=0$), and V-shaped breakpoint cusps.
  - Displays red guide lines, points, and collision-free $(x, y)$ coordinate labels placed in whitespace.
- **Zoom & Pan**: Box zoom by dragging, mouse wheel zoom, Shift/Right-click pan, and double-click to reset.

---

## Quick Start

### Prerequisites
- **Java 17 or higher** (JRE or JDK)

### Running on Windows
Double-click **`Launch2048OminoGUI.bat`** (or run it from terminal).
> It automatically locates a modern 64-bit Java runtime, launches the GUI detached with a 32 GB heap limit (`-Xmx32G`), and closes the command prompt.

### Running via JAR
You can run the packaged JAR directly from any terminal:
```powershell
# Launch the interactive GUI
java -jar 2048OminoComplexity.jar

# Run sample CLI solver benchmarks
java -jar 2048OminoComplexity.jar --cli

# Run specific CLI drivers
java -cp 2048OminoComplexity.jar Main
java -cp 2048OminoComplexity.jar TetrominoEnvelopeRunner
```

---

## Building from Source

To compile the project and create the executable JAR:
```powershell
# Compile all sources targeting Java 17+
javac --release 17 -d out (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName })

# Package executable JAR
jar --create --file 2048OminoComplexity.jar --main-class GuiMain -C out .
```

---

## Project Structure

```
├── src/
│   ├── GuiMain.java                        # GUI application entry point
│   ├── Main.java                           # CLI test runner
│   ├── TetrominoEnvelopeRunner.java        # Tetromino benchmark driver
│   └── omino2048/
│       ├── Direction.java                  # Grid move directions (UP, DOWN, LEFT, RIGHT)
│       ├── EnvelopeExtrema.java            # Exact local & global extrema finder
│       ├── EnvelopeResult.java             # Solved envelope data container
│       ├── EnvelopeSegment.java            # Piecewise segment representation
│       ├── EnvelopeSolver.java             # Multi-threaded sweep & envelope orchestrator
│       ├── ExactRootFinder.java            # VCA / Descartes / Bernstein root isolator
│       ├── FastEnvelopeDp.java             # Certified DP & rational polynomial builder
│       ├── Fraction.java                   # Arbitrary-precision rational arithmetic
│       ├── GameSolver.java                 # Exact board compilation & layer graph DP
│       ├── Polynomial.java                 # Univariate polynomial algebra
│       ├── Polyomino.java                  # Board geometry & symmetry canonicalization
│       ├── SolveResult.java                # Point evaluation results
│       └── gui/
│           ├── EnvelopePlotPanel.java      # 2D interactive chart with log-tail scaling
│           ├── OminoGuiFrame.java          # Main Swing window & controls
│           ├── PlottedEnvelope.java        # High-precision visual envelope model
│           └── PolyominoGridPanel.java     # 7x7 interactive tile drawing grid
├── 2048OminoComplexity.jar                 # Standalone runnable JAR
├── Launch2048OminoGUI.bat                  # One-click Windows 32GB launcher
└── README.md                               # Project documentation
```
