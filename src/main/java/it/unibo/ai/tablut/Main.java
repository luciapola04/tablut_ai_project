package it.unibo.ai.tablut;

import com.google.gson.Gson;
import it.unibo.ai.tablut.ai.AntColonySearchEngine;
import it.unibo.ai.tablut.model.BoardConstants;
import it.unibo.ai.tablut.model.GameState;
import it.unibo.ai.tablut.model.Move;
import it.unibo.ai.tablut.network.JsonStateParser;
import it.unibo.ai.tablut.network.NetworkClient;
import it.unibo.ai.tablut.rules.RulesEngine;
import java.io.EOFException;
import java.util.List;

/**
 * Entry point compliant with https://github.com/AGalassi/TablutCompetition
 * and official TablutClient.java protocol:
 * 1. Connect to port 5800 (WHITE) or 5801 (BLACK)
 * 2. Declare player name via gson.toJson(name)
 * 3. Loop:
 *    - Read state from server
 *    - Check if game ended (WHITEWIN, BLACKWIN, DRAW)
 *    - If my turn: compute best move via Parallel Ant Colony Optimization (MMAS) -> send Action JSON
 *    - If opponent's turn: wait for next state
 */
public class Main {
    public static final String TEAM_NAME = "L^2";
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar tablut-player.jar <WHITE|BLACK> [TIMEOUT_SEC] [SERVER_IP]");
            System.exit(1);
        }

        String roleArg = args[0].trim().toUpperCase();
        byte myRole;
        if (roleArg.equals("WHITE") || roleArg.equals("W")) {
            myRole = BoardConstants.TURN_WHITE;
        } else if (roleArg.equals("BLACK") || roleArg.equals("B")) {
            myRole = BoardConstants.TURN_BLACK;
        } else {
            System.err.println("Invalid role: " + args[0] + ". Must be WHITE or BLACK.");
            System.exit(1);
            return;
        }

        int timeoutSec = 60;
        if (args.length >= 2) {
            try {
                timeoutSec = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                System.err.println("Invalid timeout format, defaulting to 60s.");
            }
        }

        String serverIp = "localhost";
        if (args.length >= 3) {
            serverIp = args[2].trim();
        }

        // Leave a safety margin (86% of timeout, or at least 1.8s buffer) so we never lose on timeout
        long searchBudgetMs = Math.max(800L, Math.min((long) (timeoutSec * 1000L * 0.86), (timeoutSec * 1000L) - 1800L));
        int port = (myRole == BoardConstants.TURN_WHITE) ? 5800 : 5801;

        System.out.println("==================================================");
        System.out.println(" " + TEAM_NAME + " - Tablut Challenge 2026/2027 (ACO Swarm)");
        System.out.println(" Role: " + (myRole == BoardConstants.TURN_WHITE ? "WHITE" : "BLACK"));
        System.out.println(" Timeout: " + timeoutSec + "s (Search budget: " + searchBudgetMs + "ms)");
        System.out.println(" Server: " + serverIp + ":" + port);
        System.out.println("==================================================");

        try (NetworkClient client = new NetworkClient(serverIp, port);
             AntColonySearchEngine engine = new AntColonySearchEngine(searchBudgetMs)) {

            // Step 1: Declare player name exactly like TablutClient.declareName()
            client.sendString(GSON.toJson(TEAM_NAME));
            System.out.println("[Network] Connected and declared name: " + TEAM_NAME);

            // Step 2: Main Game Loop
            while (!client.isClosed()) {
                String stateJson;
                try {
                    stateJson = client.readString();
                } catch (EOFException e) {
                    System.out.println("[Network] Server closed the stream. Match finished.");
                    break;
                }

                if (stateJson == null || stateJson.isEmpty()) {
                    break;
                }

                GameState state = JsonStateParser.parse(stateJson);
                engine.recordStateInHistory(state);

                // Check terminal states (WHITEWIN, BLACKWIN, DRAW)
                if (state.turn == BoardConstants.TURN_WHITEWIN) {
                    System.out.println(myRole == BoardConstants.TURN_WHITE ? "YOU WIN!" : "YOU LOSE!");
                    break;
                } else if (state.turn == BoardConstants.TURN_BLACKWIN) {
                    System.out.println(myRole == BoardConstants.TURN_BLACK ? "YOU WIN!" : "YOU LOSE!");
                    break;
                } else if (state.turn == BoardConstants.TURN_DRAW) {
                    System.out.println("DRAW!");
                    break;
                }

                // If it's our turn, compute and send move
                if (state.turn == myRole) {
                    Move bestMove;
                    try {
                        bestMove = engine.findBestMove(state);
                    } catch (Throwable t) {
                        // Never forfeit on a crash (incl. OutOfMemoryError): play any legal move.
                        t.printStackTrace();
                        List<Move> legal = RulesEngine.generateLegalMoves(state);
                        bestMove = legal.isEmpty() ? null : legal.get(0);
                    }
                    if (bestMove != null) {
                        String actionJson = bestMove.toActionJson(myRole);
                        System.out.println("[Action] Sending move: " + actionJson);
                        client.sendString(actionJson);
                    } else {
                        System.err.println("[Error] No legal moves available!");
                        break;
                    }
                } else {
                    System.out.println("[Wait] Waiting for adversary move...");
                }
            }
        } catch (Exception e) {
            System.err.println("[Fatal] Connection or execution error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}