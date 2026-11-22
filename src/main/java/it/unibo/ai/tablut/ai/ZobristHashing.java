package it.unibo.ai.tablut.ai;

import it.unibo.ai.tablut.model.BoardConstants;
import java.util.Random;

public final class ZobristHashing {
    private static final long[][][] ZOBRIST_TABLE = new long[BoardConstants.BOARD_SIZE][BoardConstants.BOARD_SIZE][4];
    private static final long ZOBRIST_BLACK_TURN;

    static {
        Random rng = new Random(20261110L);
        for (int r = 0; r < BoardConstants.BOARD_SIZE; r++) {
            for (int c = 0; c < BoardConstants.BOARD_SIZE; c++) {
                for (int p = 0; p < 4; p++) {
                    ZOBRIST_TABLE[r][c][p] = rng.nextLong();
                }
            }
        }
        ZOBRIST_BLACK_TURN = rng.nextLong();
    }

    public static long getPieceHash(int r, int c, byte piece) {
        return ZOBRIST_TABLE[r][c][piece];
    }

    public static long getBlackTurnHash() {
        return ZOBRIST_BLACK_TURN;
    }
}
