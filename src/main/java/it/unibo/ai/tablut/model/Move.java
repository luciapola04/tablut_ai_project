package it.unibo.ai.tablut.model;

/**
 * Matches it.unibo.ai.didattica.competition.tablut.domain.Action
 * Serialized via Gson into {"from":"e3","to":"f3","turn":"WHITE"}
 */
public class Move {
    public final int fromR, fromC, toR, toC;
    public int score;

    public Move(int fromR, int fromC, int toR, int toC) {
        this.fromR = fromR;
        this.fromC = fromC;
        this.toR = toR;
        this.toC = toC;
    }

    public String getFromCoordinate() {
        char col = (char) ('a' + fromC);
        int row = fromR + 1;
        return "" + col + row;
    }

    public String getToCoordinate() {
        char col = (char) ('a' + toC);
        int row = toR + 1;
        return "" + col + row;
    }

    // Exact JSON representation expected by Server.java (Gson Action.class)
    public String toActionJson(byte role) {
        String turnStr = (role == BoardConstants.WHITE) ? "WHITE" : "BLACK";
        return String.format("{\"from\":\"%s\",\"to\":\"%s\",\"turn\":\"%s\"}",
                getFromCoordinate(), getToCoordinate(), turnStr);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Move)) return false;
        Move m = (Move) o;
        return fromR == m.fromR && fromC == m.fromC && toR == m.toR && toC == m.toC;
    }

    @Override
    public int hashCode() {
        return (fromR << 12) | (fromC << 8) | (toR << 4) | toC;
    }

    @Override
    public String toString() {
        return getFromCoordinate() + " -> " + getToCoordinate();
    }
}
