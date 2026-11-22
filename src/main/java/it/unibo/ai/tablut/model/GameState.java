package it.unibo.ai.tablut.model;

import it.unibo.ai.tablut.ai.ZobristHashing;

public class GameState {
    public final byte[][] board = new byte[BoardConstants.BOARD_SIZE][BoardConstants.BOARD_SIZE];
    public byte turn; // TURN_WHITE, TURN_BLACK, TURN_WHITEWIN, TURN_BLACKWIN, TURN_DRAW
    public int kingR = -1;
    public int kingC = -1;
    public int whiteCount = 0; // Soldiers only (excluding King)
    public int blackCount = 0;
    public long hash = 0L;
    public long boardOnlyHash = 0L; // Used to detect repeated board states

    public GameState cloneState() {
        GameState copy = new GameState();
        for (int r = 0; r < BoardConstants.BOARD_SIZE; r++) {
            System.arraycopy(this.board[r], 0, copy.board[r], 0, BoardConstants.BOARD_SIZE);
        }
        copy.turn = this.turn;
        copy.kingR = this.kingR;
        copy.kingC = this.kingC;
        copy.whiteCount = this.whiteCount;
        copy.blackCount = this.blackCount;
        copy.hash = this.hash;
        copy.boardOnlyHash = this.boardOnlyHash;
        return copy;
    }

    public void computeHash() {
        boardOnlyHash = 0L;
        for (int r = 0; r < BoardConstants.BOARD_SIZE; r++) {
            for (int c = 0; c < BoardConstants.BOARD_SIZE; c++) {
                byte p = board[r][c];
                if (p != BoardConstants.EMPTY) {
                    boardOnlyHash ^= ZobristHashing.getPieceHash(r, c, p);
                }
            }
        }
        hash = boardOnlyHash;
        if (turn == BoardConstants.TURN_BLACK) {
            hash ^= ZobristHashing.getBlackTurnHash();
        }
    }

    public boolean isTerminal() {
        return turn == BoardConstants.TURN_WHITEWIN ||
               turn == BoardConstants.TURN_BLACKWIN ||
               turn == BoardConstants.TURN_DRAW;
    }
}
