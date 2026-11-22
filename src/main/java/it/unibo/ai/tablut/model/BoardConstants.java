package it.unibo.ai.tablut.model;

public final class BoardConstants {
    private BoardConstants() {}

    public static final int BOARD_SIZE = 9;

    // Piece definitions
    public static final byte EMPTY = 0;
    public static final byte WHITE = 1;
    public static final byte BLACK = 2;
    public static final byte KING = 3;

    // Turn states matching State
    public static final byte TURN_WHITE = 1;
    public static final byte TURN_BLACK = 2;
    public static final byte TURN_WHITEWIN = 3;
    public static final byte TURN_BLACKWIN = 4;
    public static final byte TURN_DRAW = 5;

    // Castle coordinate (Throne e5 -> row 4, col 4)
    public static final int CASTLE_R = 4;
    public static final int CASTLE_C = 4;

    // Evaluation constants
    public static final int WIN_SCORE = 1_000_000;
    public static final int LOSE_SCORE = -1_000_000;

    public static final boolean[][] IS_CAMP = new boolean[BOARD_SIZE][BOARD_SIZE];
    public static final boolean[][] IS_ESCAPE = new boolean[BOARD_SIZE][BOARD_SIZE];

    static {
        // North, South, West, East Camps
        int[][] camps = {
            {0,3}, {0,4}, {0,5}, {1,4},
            {8,3}, {8,4}, {8,5}, {7,4},
            {3,0}, {4,0}, {5,0}, {4,1},
            {3,8}, {4,8}, {5,8}, {4,7}
        };
        for (int[] c : camps) {
            IS_CAMP[c[0]][c[1]] = true;
        }

        // Perimeter Escapes
        int[][] escapes = {
            {0,1}, {0,2}, {0,6}, {0,7},
            {8,1}, {8,2}, {8,6}, {8,7},
            {1,0}, {2,0}, {6,0}, {7,0},
            {1,8}, {2,8}, {6,8}, {7,8}
        };
        for (int[] e : escapes) {
            IS_ESCAPE[e[0]][e[1]] = true;
        }
    }

    public static boolean inBounds(int r, int c) {
        return r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE;
    }
}
