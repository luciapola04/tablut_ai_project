package it.unibo.ai.tablut.ai;

import it.unibo.ai.tablut.model.BoardConstants;
import it.unibo.ai.tablut.model.GameState;
import it.unibo.ai.tablut.rules.RulesEngine;

public final class EvaluationHeuristic {
    private static final int[][] DIRECTIONS = {{-1,0}, {1,0}, {0,-1}, {0,1}};

    private static final int[][] KING_POSITION_TABLE = {
        {   0, 1000, 1000, -100, -100, -100, 1000, 1000,    0},
        {1000,  150,  220,   60,  -50,   60,  220,  150, 1000},
        {1000,  220,  300,  140,   80,  140,  300,  220, 1000},
        {-100,   60,  140,   60,   30,   60,  140,   60, -100},
        {-100,  -50,   80,   30,    0,   30,   80,  -50, -100},
        {-100,   60,  140,   60,   30,   60,  140,   60, -100},
        {1000,  220,  300,  140,   80,  140,  300,  220, 1000},
        {1000,  150,  220,   60,  -50,   60,  220,  150, 1000},
        {   0, 1000, 1000, -100, -100, -100, 1000, 1000,    0}
    };

    private EvaluationHeuristic() {}

    public static int evaluate(GameState state) {
        int whiteScore = 0;

        // Material Balance (8 White Soldiers vs 16 Black Soldiers)
        whiteScore += (state.whiteCount * 190);
        whiteScore -= (state.blackCount * 95);

        int kr = state.kingR;
        int kc = state.kingC;

        if (kr != -1) {
            whiteScore += KING_POSITION_TABLE[kr][kc];

            // Direct Open Escape Corridors & King Mobility (reachable squares)
            int openEscapePaths = 0;
            int kingMobility = 0;

            for (int[] d : DIRECTIONS) {
                int r = kr + d[0];
                int c = kc + d[1];
                boolean reachedEscape = false;
                while (BoardConstants.inBounds(r, c)) {
                    if (state.board[r][c] != BoardConstants.EMPTY ||
                        (r == BoardConstants.CASTLE_R && c == BoardConstants.CASTLE_C) ||
                        BoardConstants.IS_CAMP[r][c]) {
                        break;
                    }
                    kingMobility++;
                    if (BoardConstants.IS_ESCAPE[r][c]) {
                        reachedEscape = true;
                        break;
                    }
                    r += d[0];
                    c += d[1];
                }
                if (reachedEscape) {
                    openEscapePaths++;
                }
            }

            // Reward King freedom of movement
            whiteScore += (kingMobility * 28);

            // Escape threat evaluation:
            // - >=2 open paths: unstoppable win even if it's Black's turn
            // - ==1 open path & White's turn: immediate win on next move
            // - ==1 open path & Black's turn: strong pressure (+1400), NOT +12000 (which saturated the sigmoid to 0.9997!)
            if (openEscapePaths >= 2) {
                whiteScore += 80000;
            } else if (openEscapePaths == 1) {
                whiteScore += (state.turn == BoardConstants.TURN_WHITE) ? 80000 : 1400;
            }

            // King Encirclement Threats
            int hostileSides = 0;
            int friendlyEscortSides = 0;
            for (int[] d : DIRECTIONS) {
                int nr = kr + d[0];
                int nc = kc + d[1];
                if (BoardConstants.inBounds(nr, nc)) {
                    byte adj = state.board[nr][nc];
                    if (adj == BoardConstants.BLACK ||
                        BoardConstants.IS_CAMP[nr][nc] ||
                        (nr == BoardConstants.CASTLE_R && nc == BoardConstants.CASTLE_C)) {
                        hostileSides++;
                    } else if (adj == BoardConstants.WHITE) {
                        friendlyEscortSides++;
                    }
                }
            }

            // Small bonus for White guards protecting adjacent sides of the King
            whiteScore += (friendlyEscortSides * 45);

            if (kr == BoardConstants.CASTLE_R && kc == BoardConstants.CASTLE_C) {
                whiteScore -= (hostileSides * 280);
                if (hostileSides == 3) whiteScore -= 2500;
            } else if (RulesEngine.isAdjacentToCastle(kr, kc)) {
                // hostileSides includes the throne itself: 3 = throne + 2 Black pawns,
                // i.e. the King is captured as soon as the third Black pawn arrives.
                whiteScore -= (hostileSides * 480);
                if (hostileSides >= 3) whiteScore -= 3200;
            } else {
                // Open field: the King is captured only when one side is hostile (Black pawn or camp)
                // and the opposite side is (or becomes) a Black pawn. A hostile side with a White
                // pawn or a wall opposite is not a threat.
                for (int axis = 0; axis < 2; axis++) {
                    int dr = (axis == 0) ? 1 : 0;
                    int dc = (axis == 0) ? 0 : 1;
                    boolean aHostile = isHostile(state, kr - dr, kc - dc);
                    boolean bHostile = isHostile(state, kr + dr, kc + dc);
                    boolean aFree = isFree(state, kr - dr, kc - dc);
                    boolean bFree = isFree(state, kr + dr, kc + dc);
                    if ((aHostile && bFree) || (bHostile && aFree)) {
                        whiteScore -= 1650;
                    }
                }
            }
        }

        // Black Perimeter Blockade Bonus
        int[][] strategicBlockers = {
            {1,2}, {2,1}, {1,6}, {2,7},
            {6,1}, {7,2}, {6,7}, {7,6},
            {2,2}, {2,6}, {6,2}, {6,6}
        };
        for (int[] pos : strategicBlockers) {
            if (state.board[pos[0]][pos[1]] == BoardConstants.BLACK) {
                whiteScore -= 40;
            }
        }

        return (state.turn == BoardConstants.TURN_WHITE) ? whiteScore : -whiteScore;
    }

    // Black pawn or any camp tile.
    private static boolean isHostile(GameState s, int r, int c) {
        return BoardConstants.inBounds(r, c)
                && (s.board[r][c] == BoardConstants.BLACK || BoardConstants.IS_CAMP[r][c]);
    }

    //Empty, non-camp, non-throne square: a Black pawn could still land there.
    private static boolean isFree(GameState s, int r, int c) {
        return BoardConstants.inBounds(r, c)
                && s.board[r][c] == BoardConstants.EMPTY
                && !BoardConstants.IS_CAMP[r][c]
                && !(r == BoardConstants.CASTLE_R && c == BoardConstants.CASTLE_C);
    }
}