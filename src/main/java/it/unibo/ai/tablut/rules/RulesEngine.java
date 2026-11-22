package it.unibo.ai.tablut.rules;

import it.unibo.ai.tablut.ai.ZobristHashing;
import it.unibo.ai.tablut.model.BoardConstants;
import it.unibo.ai.tablut.model.GameState;
import it.unibo.ai.tablut.model.Move;
import java.util.ArrayList;
import java.util.List;

/**
 * The validation and capture logic of Ashton Tablut
 */
public final class RulesEngine {
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private RulesEngine() {}

    public static List<Move> generateLegalMoves(GameState state) {
        List<Move> moves = new ArrayList<>(64);
        byte player = state.turn;

        for (int r = 0; r < BoardConstants.BOARD_SIZE; r++) {
            for (int c = 0; c < BoardConstants.BOARD_SIZE; c++) {
                byte piece = state.board[r][c];
                if (piece == BoardConstants.EMPTY) continue;

                if (player == BoardConstants.TURN_WHITE && (piece == BoardConstants.WHITE || piece == BoardConstants.KING)) {
                    addOrthogonalMoves(state, r, c, piece, moves);
                } else if (player == BoardConstants.TURN_BLACK && piece == BoardConstants.BLACK) {
                    addOrthogonalMoves(state, r, c, piece, moves);
                }
            }
        }
        return moves;
    }

    private static void addOrthogonalMoves(GameState state, int fromR, int fromC, byte piece, List<Move> moves) {
        boolean startsInCamp = BoardConstants.IS_CAMP[fromR][fromC];

        for (int[] d : DIRECTIONS) {
            int step = 1;
            while (true) {
                int toR = fromR + d[0] * step;
                int toC = fromC + d[1] * step;

                if (!BoardConstants.inBounds(toR, toC)) break;
                if (state.board[toR][toC] != BoardConstants.EMPTY) break;

                // Citadel / Throne (e5) can never be crossed or entered
                if (toR == BoardConstants.CASTLE_R && toC == BoardConstants.CASTLE_C) break;

                // Camp constraints
                if (BoardConstants.IS_CAMP[toR][toC]) {
                    if (piece != BoardConstants.BLACK) {
                        break; // White pieces cannot enter or cross camps
                    }
                    if (!startsInCamp || !isSameCampRegion(fromR, fromC, toR, toC)) {
                        break; // Black cannot re-enter a camp once outside, or jump between different camps
                    }
                }

                moves.add(new Move(fromR, fromC, toR, toC));
                step++;
            }
        }
    }

    private static boolean isSameCampRegion(int r1, int c1, int r2, int c2) {
        if (r1 <= 1 && c1 >= 3 && c1 <= 5) return (r2 <= 1 && c2 >= 3 && c2 <= 5);
        if (r1 >= 7 && c1 >= 3 && c1 <= 5) return (r2 >= 7 && c2 >= 3 && c2 <= 5);
        if (c1 <= 1 && r1 >= 3 && r1 <= 5) return (c2 <= 1 && r2 >= 3 && r2 <= 5);
        if (c1 >= 7 && r1 >= 3 && r1 <= 5) return (c2 >= 7 && r2 >= 3 && r2 <= 5);
        return false;
    }

    /**
     * Exact exception:
     * The 4 outer central camp tiles (e1, e9, a5, i5) do NOT act as hostile barriers for White captures.
     */
    public static boolean isCentralExternalCamp(int r, int c) {
        return (r == 0 && c == 4) || (r == 8 && c == 4) || (r == 4 && c == 0) || (r == 4 && c == 8);
    }

    public static GameState applyMove(GameState state, Move move) {
        GameState next = state.cloneState();
        byte mover = next.board[move.fromR][move.fromC];

        next.board[move.fromR][move.fromC] = BoardConstants.EMPTY;
        next.board[move.toR][move.toC] = mover;

        long deltaHash = ZobristHashing.getPieceHash(move.fromR, move.fromC, mover)
                       ^ ZobristHashing.getPieceHash(move.toR, move.toC, mover);
        next.boardOnlyHash ^= deltaHash;
        next.hash ^= deltaHash;

        if (mover == BoardConstants.KING) {
            next.kingR = move.toR;
            next.kingC = move.toC;
        }

        // Check captures in all 4 directions around the destination square
        for (int[] d : DIRECTIONS) {
            int targetR = move.toR + d[0];
            int targetC = move.toC + d[1];

            if (!BoardConstants.inBounds(targetR, targetC)) continue;
            byte targetPiece = next.board[targetR][targetC];
            if (targetPiece == BoardConstants.EMPTY) continue;

            if (mover == BoardConstants.BLACK && (targetPiece == BoardConstants.WHITE || targetPiece == BoardConstants.KING)) {
                checkBlackCapture(next, targetR, targetC, d[0], d[1]);
            } else if ((mover == BoardConstants.WHITE || mover == BoardConstants.KING) && targetPiece == BoardConstants.BLACK) {
                checkWhiteCapture(next, targetR, targetC, d[0], d[1]);
            }
        }

        next.turn = (next.turn == BoardConstants.TURN_WHITE) ? BoardConstants.TURN_BLACK : BoardConstants.TURN_WHITE;
        next.hash ^= ZobristHashing.getBlackTurnHash();

        return next;
    }

    private static void checkWhiteCapture(GameState state, int targetR, int targetC, int dr, int dc) {
        int flankR = targetR + dr;
        int flankC = targetC + dc;
        if (!BoardConstants.inBounds(flankR, flankC)) return;

        // A Black pawn (even if inside a camp) is captured if flanked by White, King, Throne,
        // or any Camp EXCEPT the 4 outer central camps (e1, e9, a5, i5).
        byte flankPiece = state.board[flankR][flankC];
        boolean isFlankHostile = (flankPiece == BoardConstants.WHITE || flankPiece == BoardConstants.KING)
                || (flankR == BoardConstants.CASTLE_R && flankC == BoardConstants.CASTLE_C)
                || (BoardConstants.IS_CAMP[flankR][flankC] && !isCentralExternalCamp(flankR, flankC));

        if (isFlankHostile) {
            state.board[targetR][targetC] = BoardConstants.EMPTY;
            long h = ZobristHashing.getPieceHash(targetR, targetC, BoardConstants.BLACK);
            state.boardOnlyHash ^= h;
            state.hash ^= h;
            state.blackCount--;
        }
    }

    private static void checkBlackCapture(GameState state, int targetR, int targetC, int dr, int dc) {
        byte target = state.board[targetR][targetC];

        if (target == BoardConstants.KING) {
            // King in the Castle (e5): must be surrounded on all 4 sides by Black pawns
            if (targetR == BoardConstants.CASTLE_R && targetC == BoardConstants.CASTLE_C) {
                if (isBlack(state, targetR - 1, targetC) &&
                    isBlack(state, targetR + 1, targetC) &&
                    isBlack(state, targetR, targetC - 1) &&
                    isBlack(state, targetR, targetC + 1)) {
                    removeKing(state, targetR, targetC);
                }
            }
            // King adjacent to the Castle (e4, e6, d5, f5): must be surrounded on all 3 free sides by Black pawns
            else if (isAdjacentToCastle(targetR, targetC)) {
                int surroundedFreeSides = 0;
                for (int[] d : DIRECTIONS) {
                    int nr = targetR + d[0];
                    int nc = targetC + d[1];
                    if (nr == BoardConstants.CASTLE_R && nc == BoardConstants.CASTLE_C) continue;
                    if (BoardConstants.inBounds(nr, nc) && isBlack(state, nr, nc)) {
                        surroundedFreeSides++;
                    }
                }
                if (surroundedFreeSides == 3) {
                    removeKing(state, targetR, targetC);
                }
            }
            // King elsewhere: captured like a normal pawn (flanked by Black pawn or any Camp barrier)
            else {
                int flankR = targetR + dr;
                int flankC = targetC + dc;
                if (BoardConstants.inBounds(flankR, flankC)) {
                    boolean isFlankHostile = (state.board[flankR][flankC] == BoardConstants.BLACK)
                            || BoardConstants.IS_CAMP[flankR][flankC];
                    if (isFlankHostile) {
                        removeKing(state, targetR, targetC);
                    }
                }
            }
        } else {
            // Capturing a White soldier
            int flankR = targetR + dr;
            int flankC = targetC + dc;
            if (BoardConstants.inBounds(flankR, flankC)) {
                boolean isFlankHostile = (state.board[flankR][flankC] == BoardConstants.BLACK)
                        || (flankR == BoardConstants.CASTLE_R && flankC == BoardConstants.CASTLE_C)
                        || BoardConstants.IS_CAMP[flankR][flankC];
                if (isFlankHostile) {
                    state.board[targetR][targetC] = BoardConstants.EMPTY;
                    long h = ZobristHashing.getPieceHash(targetR, targetC, BoardConstants.WHITE);
                    state.boardOnlyHash ^= h;
                    state.hash ^= h;
                    state.whiteCount--;
                }
            }
        }
    }

    private static void removeKing(GameState state, int r, int c) {
        state.board[r][c] = BoardConstants.EMPTY;
        long h = ZobristHashing.getPieceHash(r, c, BoardConstants.KING);
        state.boardOnlyHash ^= h;
        state.hash ^= h;
        state.kingR = -1;
        state.kingC = -1;
    }

    private static boolean isBlack(GameState state, int r, int c) {
        return BoardConstants.inBounds(r, c) && state.board[r][c] == BoardConstants.BLACK;
    }

    public static boolean isAdjacentToCastle(int r, int c) {
        return (Math.abs(r - BoardConstants.CASTLE_R) + Math.abs(c - BoardConstants.CASTLE_C)) == 1;
    }
}