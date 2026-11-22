package it.unibo.ai.tablut.network;

import it.unibo.ai.tablut.model.BoardConstants;
import it.unibo.ai.tablut.model.GameState;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonStateParser {
    private static final Pattern TURN_PATTERN = Pattern.compile("\"turn\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CELL_PATTERN = Pattern.compile("\"(EMPTY|WHITE|BLACK|THRONE|KING|O|W|B|T|K)\"");

    private JsonStateParser() {}

    public static GameState parse(String json) {
        GameState state = new GameState();

        Matcher turnMatcher = TURN_PATTERN.matcher(json);
        if (turnMatcher.find()) {
            String turnStr = turnMatcher.group(1).trim().toUpperCase();
            switch (turnStr) {
                case "WHITE":
                case "W":
                    state.turn = BoardConstants.TURN_WHITE;
                    break;
                case "BLACK":
                case "B":
                    state.turn = BoardConstants.TURN_BLACK;
                    break;
                case "WHITEWIN":
                case "WW":
                    state.turn = BoardConstants.TURN_WHITEWIN;
                    break;
                case "BLACKWIN":
                case "BW":
                    state.turn = BoardConstants.TURN_BLACKWIN;
                    break;
                case "DRAW":
                case "D":
                    state.turn = BoardConstants.TURN_DRAW;
                    break;
                default:
                    state.turn = BoardConstants.TURN_WHITE;
                    break;
            }
        }

        int boardIdx = json.indexOf("\"board\"");
        if (boardIdx != -1) {
            Matcher cellMatcher = CELL_PATTERN.matcher(json.substring(boardIdx));
            int count = 0;
            while (cellMatcher.find() && count < 81) {
                int r = count / BoardConstants.BOARD_SIZE;
                int c = count % BoardConstants.BOARD_SIZE;
                String cell = cellMatcher.group(1).toUpperCase();

                switch (cell) {
                    case "WHITE":
                    case "W":
                        state.board[r][c] = BoardConstants.WHITE;
                        state.whiteCount++;
                        break;
                    case "BLACK":
                    case "B":
                        state.board[r][c] = BoardConstants.BLACK;
                        state.blackCount++;
                        break;
                    case "KING":
                    case "K":
                        state.board[r][c] = BoardConstants.KING;
                        state.kingR = r;
                        state.kingC = c;
                        break;
                    default:
                        state.board[r][c] = BoardConstants.EMPTY;
                        break;
                }
                count++;
            }
        }

        state.computeHash();
        return state;
    }
}
