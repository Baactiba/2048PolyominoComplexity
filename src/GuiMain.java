import omino2048.gui.OminoGuiFrame;

/**
 * Launches the interactive Swing GUI for drawing polyominoes, choosing a tile goal,
 * and visualizing the exact optimal success-rate envelope across 4-tile spawn rates in [0, 1].
 */
public class GuiMain {
    public static void main(String[] args) {
        if (args.length > 0) {
            if ("--cli".equalsIgnoreCase(args[0])) {
                Main.main(new String[0]);
                return;
            }
            if ("--help".equalsIgnoreCase(args[0]) || "-h".equalsIgnoreCase(args[0])) {
                System.out.println("Usage:");
                System.out.println("  java -jar 2048OminoComplexity.jar         Launch the Swing GUI (default)");
                System.out.println("  java -jar 2048OminoComplexity.jar --cli   Run sample CLI solver benchmarks");
                System.out.println("  java -cp 2048OminoComplexity.jar Main     Run CLI Main");
                System.out.println("  java -cp 2048OminoComplexity.jar TetrominoEnvelopeRunner");
                return;
            }
        }
        OminoGuiFrame.launch();
    }
}
