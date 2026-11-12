import omino2048.Polyomino;

/**
 * Computes and prints exact upper envelopes for all trominoes and tetrominoes at goal 16.
 * Note: T, L4, and S tetrominoes require roughly 1-2.5 minutes each.
 */
public class TetrominoEnvelopeRunner {
    public static void main(String[] args) {
        Main.printEnvelope("1x3 tromino (goal 16)", Polyomino.fromPattern("###"), 16);
        Main.printEnvelope("L tromino (goal 16)", Polyomino.fromPattern("#.", "##"), 16);
        Main.printEnvelope("1x4 tetromino (goal 16)", Polyomino.fromPattern("####"), 16);
        Main.printEnvelope("2x2 (O) tetromino (goal 16)", Polyomino.fromPattern("##", "##"), 16);
        Main.printEnvelope("T tetromino (goal 16)", Polyomino.fromPattern("###", ".#."), 16);
        Main.printEnvelope("L4 tetromino (goal 16)", Polyomino.fromPattern("#.", "#.", "##"), 16);
        Main.printEnvelope("S tetromino (goal 16)", Polyomino.fromPattern(".##", "##."), 16);
    }
}
