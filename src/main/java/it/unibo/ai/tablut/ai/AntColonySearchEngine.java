package it.unibo.ai.tablut.ai;

import it.unibo.ai.tablut.model.BoardConstants;
import it.unibo.ai.tablut.model.GameState;
import it.unibo.ai.tablut.model.Move;
import it.unibo.ai.tablut.rules.RulesEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Ant Colony Optimization v2.2 (Parallel Adversarial MMAS)
 * Parallelizes each swarm wave across available CPU cores (up to 4).
 */
public class AntColonySearchEngine implements AutoCloseable {
    private static final int ANTS_PER_WAVE = 48;
    private static final int MAX_PLAYOUT_DEPTH = 6;
    private static final double ALPHA = 1.0;
    private static final double BETA_MAX = 1.8;
    private static final double BETA_MIN = 2.8;
    private static final double RHO = 0.12;
    private static final double TAU_INIT = 1.0;
    private static final double TAU_MIN = 0.05;
    private static final double TAU_MAX = 5.0;
    private static final double EPSILON_MAX = 0.06;
    private static final double EPSILON_MIN = 0.02;
    private static final double SCORE_SCALE = 1500.0;
    // Tree size is bounded by the available heap. Measured cost: roughly 5-6 KB per node
    // (state + per-move arrays + cached child states); we budget 12 KB/node for GC headroom.
    private static final int MAX_TREE_NODES_CAP = 200_000;
    private static final int MIN_TREE_NODES = 10_000;
    private static final long BYTES_PER_NODE_BUDGET = 12L * 1024L;

    private final long timeLimitMs;
    private final int numThreads;
    private final int maxTreeNodes;
    private final ExecutorService executor;
    private final Set<Long> gameHistoryHashes = new HashSet<>();
    private final Map<Long, Map<Move, Double>> previousPheromoneCache = new HashMap<>();

    private volatile long startTime;
    private volatile int currentWave;
    private byte myRole;
    private final AtomicInteger totalNodesCreated = new AtomicInteger(0);

    public AntColonySearchEngine(long timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
        long maxHeap = Runtime.getRuntime().maxMemory();
        this.maxTreeNodes = (int) Math.max(MIN_TREE_NODES,
                Math.min(MAX_TREE_NODES_CAP, maxHeap / BYTES_PER_NODE_BUDGET));
        System.out.println("[ACO] Max heap: " + (maxHeap >> 20) + " MB -> node budget: " + maxTreeNodes);
        this.numThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        this.executor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r, "ACO-Ant-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void recordStateInHistory(GameState state) {
        gameHistoryHashes.add(state.boardOnlyHash);
    }

    public Move findBestMove(GameState state) {
        Thread.interrupted(); // clear any stale interrupt flag so invokeAll can never fail instantly
        this.startTime = System.currentTimeMillis();
        this.myRole = state.turn;
        this.currentWave = 0;
        this.totalNodesCreated.set(0);

        List<Move> legalMoves = RulesEngine.generateLegalMoves(state);
        if (legalMoves.isEmpty()) {
            return null;
        }
        if (legalMoves.size() == 1) {
            return legalMoves.get(0);
        }

        // PHASE 1: Fast Depth-1 Tactical Check + Depth-2 Blunder Screening
        List<Move> safeMoves = new ArrayList<>(legalMoves.size());
        List<GameState> safeNextStates = new ArrayList<>(legalMoves.size());
        List<GameState> allNextStates = new ArrayList<>(legalMoves.size());

        for (Move move : legalMoves) {
            GameState next = RulesEngine.applyMove(state, move);
            allNextStates.add(next);

            if (isImmediateWinFor(next, myRole)) {
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("[ACO-v2.2 Tactical] Immediate Depth-1 WIN found in %dms! Playing: %s%n",
                        elapsed, move);
                return move;
            }

            if (System.currentTimeMillis() - startTime < timeLimitMs * 0.35) {
                if (!opponentHasImmediateWin(next, myRole)) {
                    safeMoves.add(move);
                    safeNextStates.add(next);
                }
            } else {
                safeMoves.add(move);
                safeNextStates.add(next);
            }
        }

        List<Move> candidateMoves = safeMoves.isEmpty() ? legalMoves : safeMoves;
        List<GameState> candidateNextStates = safeMoves.isEmpty() ? allNextStates : safeNextStates;

        if (candidateMoves.size() == 1) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("[ACO-v2.2 Tactical] Single non-losing move found in %dms! Playing: %s%n",
                    elapsed, candidateMoves.get(0));
            return candidateMoves.get(0);
        }

        // PHASE 2: Build Root Node & Warm-Start Pheromone / Tactical Visibility
        AcoNode root = createRootNode(state, candidateMoves, candidateNextStates);

        int wavesCompleted = 0;
        int totalAntsRun = 0;
        AntPath globalBestPath = null;

        // PHASE 3: Parallel Adversarial MMAS Swarm Search Loop
        while (System.currentTimeMillis() - startTime < timeLimitMs) {
            currentWave = wavesCompleted;
            List<AntPath> wavePaths = runWaveParallel(root);
            if (wavePaths.isEmpty()) {
                break;
            }

            AntPath waveBestPath = null;
            AntPath waveWorstPath = null;

            for (AntPath path : wavePaths) {
                totalAntsRun++;
                if (path.length > 0) {
                    int rootEdge = path.edgeIndices[0];
                    root.visits[rootEdge]++;
                    root.rewardSum[rootEdge] += path.reward;
                    if (path.reward < root.minReward[rootEdge]) {
                        root.minReward[rootEdge] = path.reward;
                    }
                }

                if (waveBestPath == null || path.reward > waveBestPath.reward) {
                    waveBestPath = path;
                }
                if (waveWorstPath == null || path.reward < waveWorstPath.reward) {
                    waveWorstPath = path;
                }
                if (globalBestPath == null || path.reward > globalBestPath.reward) {
                    globalBestPath = path;
                }
            }

            evaporateTouched(wavePaths, wavesCompleted);
            depositAlongPath(waveBestPath, 0.65);

            if (waveWorstPath != null && waveWorstPath != waveBestPath) {
                depositOpponentRefutation(waveWorstPath, 0.55);
            }

            if (globalBestPath != null) {
                depositAlongPath(globalBestPath, 0.35);
            }

            wavesCompleted++;
        }

        // PHASE 4: Prudent Root Selection & Cache Subtree Pheromones for Next Turn
        int bestIdx = selectBestRootEdge(root);
        Move bestMove = root.moves[bestIdx];
        cacheNextTurnPheromones(root.children.get(bestIdx));

        double avgReward = root.visits[bestIdx] > 0
                ? (root.rewardSum[bestIdx] / root.visits[bestIdx])
                : 0.5;
        double minReward = root.visits[bestIdx] > 0 ? root.minReward[bestIdx] : 0.5;
        long totalElapsed = System.currentTimeMillis() - startTime;

        System.out.printf(
                "[ACO-v2.2 | %dT] Waves: %d | Ants: %d | Nodes: %d | Safe: %d/%d | Time: %dms | Best: %s (tau=%.3f, avgR=%.3f, minR=%.3f, v=%d)%n",
                numThreads, wavesCompleted, totalAntsRun, totalNodesCreated.get(),
                candidateMoves.size(), legalMoves.size(), totalElapsed,
                bestMove, root.pheromone[bestIdx], avgReward, minReward, root.visits[bestIdx]
        );

        return bestMove;
    }

    private List<AntPath> runWaveParallel(AcoNode root) {
        if (numThreads == 1) {
            List<AntPath> results = new ArrayList<>(ANTS_PER_WAVE);
            for (int a = 0; a < ANTS_PER_WAVE; a++) {
                if (System.currentTimeMillis() - startTime >= timeLimitMs) break;
                results.add(runSingleAnt(root));
            }
            return results;
        }

        int antsPerWorker = ANTS_PER_WAVE / numThreads;
        int remainder = ANTS_PER_WAVE % numThreads;

        List<Callable<List<AntPath>>> tasks = new ArrayList<>(numThreads);
        for (int t = 0; t < numThreads; t++) {
            final int batchSize = antsPerWorker + (t < remainder ? 1 : 0);
            tasks.add(() -> {
                List<AntPath> localPaths = new ArrayList<>(batchSize);
                for (int a = 0; a < batchSize; a++) {
                    if (System.currentTimeMillis() - startTime >= timeLimitMs) {
                        break;
                    }
                    localPaths.add(runSingleAnt(root));
                }
                return localPaths;
            });
        }

        List<AntPath> allWavePaths = new ArrayList<>(ANTS_PER_WAVE);
        List<Future<List<AntPath>>> futures;
        try {
            futures = executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return allWavePaths;
        }
        for (Future<List<AntPath>> f : futures) {
            try {
                allWavePaths.addAll(f.get());
            } catch (ExecutionException e) {
                // A worker crashed: report it, keep the paths of the other workers.
                System.err.println("[ACO] Worker failure: " + e.getCause());
                e.getCause().printStackTrace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return allWavePaths;
    }

    private AntPath runSingleAnt(AcoNode root) {
        AntPath path = new AntPath(MAX_PLAYOUT_DEPTH);
        AcoNode current = root;
        GameState currentState = root.state;

        long[] pathHashes = new long[MAX_PLAYOUT_DEPTH + 1];
        pathHashes[0] = currentState.boardOnlyHash;
        int pathHashCount = 1;

        for (int ply = 0; ply < MAX_PLAYOUT_DEPTH; ply++) {
            if (current.moves.length == 0) {
                path.reward = (currentState.turn == myRole) ? 0.0 : 1.0;
                return path;
            }

            boolean isMax = (currentState.turn == myRole);
            int chosenIdx = selectEdgeProbabilistically(current, isMax);
            path.nodes[path.length] = current;
            path.edgeIndices[path.length] = chosenIdx;
            path.isMaxNode[path.length] = isMax;
            path.length++;

            // Lock-free lazy state caching
            GameState nextState = current.cachedNextStates.get(chosenIdx);
            if (nextState == null) {
                GameState computed = RulesEngine.applyMove(currentState, current.moves[chosenIdx]);
                if (current.cachedNextStates.compareAndSet(chosenIdx, null, computed)) {
                    nextState = computed;
                } else {
                    nextState = current.cachedNextStates.get(chosenIdx);
                }
            }

            if (isImmediateWinFor(nextState, myRole)) {
                path.reward = 1.0;
                return path;
            }
            if (isImmediateLossFor(nextState, myRole)) {
                path.reward = 0.0;
                return path;
            }

            long nextBoardHash = nextState.boardOnlyHash;
            boolean repeatedInPath = false;
            for (int h = 0; h < pathHashCount; h++) {
                if (pathHashes[h] == nextBoardHash) {
                    repeatedInPath = true;
                    break;
                }
            }
            if (repeatedInPath || gameHistoryHashes.contains(nextBoardHash)) {
                path.reward = 0.45;
                return path;
            }
            pathHashes[pathHashCount++] = nextBoardHash;

            if (ply == MAX_PLAYOUT_DEPTH - 1) {
                path.reward = evaluateNormalized(nextState);
                return path;
            }

            // Lock-free lazy child node expansion
            AcoNode child = current.children.get(chosenIdx);
            if (child == null) {
                if (totalNodesCreated.get() < maxTreeNodes) {
                    AcoNode created = createInternalNode(nextState);
                    if (current.children.compareAndSet(chosenIdx, null, created)) {
                        child = created;
                    } else {
                        totalNodesCreated.decrementAndGet();
                        child = current.children.get(chosenIdx);
                    }
                } else {
                    path.reward = evaluateNormalized(nextState);
                    return path;
                }
            }

            current = child;
            currentState = nextState;
        }

        path.reward = evaluateNormalized(currentState);
        return path;
    }

    private int selectEdgeProbabilistically(AcoNode node, boolean isMax) {
        int n = node.moves.length;
        if (n == 1) {
            return 0;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double epsilon = isMax ? EPSILON_MAX : EPSILON_MIN;
        if (rng.nextDouble() < epsilon) {
            return rng.nextInt(n);
        }

        double total = 0.0;
        for (int i = 0; i < n; i++) {
            total += node.pheromone[i] * node.etaPow[i];
        }

        if (total <= 0.0 || Double.isNaN(total)) {
            return rng.nextInt(n);
        }

        double pick = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < n; i++) {
            cumulative += node.pheromone[i] * node.etaPow[i];
            if (pick <= cumulative) {
                return i;
            }
        }
        return n - 1;
    }


    private void evaporateTouched(List<AntPath> paths, int wave) {
        for (AntPath p : paths) {
            for (int i = 0; i < p.length; i++) {
                AcoNode n = p.nodes[i];
                int missed = wave + 1 - n.lastEvapWave;
                if (missed <= 0) {
                    continue;
                }
                double keep = Math.pow(1.0 - RHO, missed);
                double[] ph = n.pheromone;
                for (int k = 0; k < ph.length; k++) {
                    double updated = ph[k] * keep;
                    ph[k] = (updated < TAU_MIN) ? TAU_MIN : updated;
                }
                n.lastEvapWave = wave + 1;
            }
        }
    }

    private void depositAlongPath(AntPath path, double weight) {
        double r = path.reward;
        for (int i = 0; i < path.length; i++) {
            AcoNode node = path.nodes[i];
            int edge = path.edgeIndices[i];
            double delta = path.isMaxNode[i] ? (weight * r) : (weight * (1.0 - r));
            double updated = node.pheromone[edge] + delta;
            node.pheromone[edge] = clamp(updated, TAU_MIN, TAU_MAX);
        }
    }

    private void depositOpponentRefutation(AntPath worstPath, double weight) {
        double oppSuccess = 1.0 - worstPath.reward;
        for (int i = 0; i < worstPath.length; i++) {
            AcoNode node = worstPath.nodes[i];
            int edge = worstPath.edgeIndices[i];
            if (!worstPath.isMaxNode[i]) {
                double updated = node.pheromone[edge] + (weight * oppSuccess);
                node.pheromone[edge] = clamp(updated, TAU_MIN, TAU_MAX);
            } else if (worstPath.reward < 0.30) {
                double updated = node.pheromone[edge] * (1.0 - 0.18 * weight);
                node.pheromone[edge] = Math.max(TAU_MIN, updated);
            }
        }
    }

    private int selectBestRootEdge(AcoNode root) {
        int bestIdx = 0;
        double bestComposite = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < root.moves.length; i++) {
            int v = root.visits[i];
            double avgR = (v > 0) ? (root.rewardSum[i] / v) : 0.5;
            double minR = (v > 0) ? root.minReward[i] : 0.5;
            double normTau = root.pheromone[i] / TAU_MAX;
            double composite = (0.45 * normTau) + (0.30 * avgR) + (0.25 * minR);

            if (composite > bestComposite + 1e-6
                    || (Math.abs(composite - bestComposite) <= 1e-6 && v > root.visits[bestIdx])) {
                bestComposite = composite;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private void cacheNextTurnPheromones(AcoNode opponentNode) {
        previousPheromoneCache.clear();
        if (opponentNode == null) {
            return;
        }
        int len = opponentNode.children.length();
        for (int i = 0; i < len; i++) {
            AcoNode myNextNode = opponentNode.children.get(i);
            if (myNextNode != null && myNextNode.moves.length > 0) {
                Map<Move, Double> moveMap = new HashMap<>(myNextNode.moves.length * 2);
                for (int j = 0; j < myNextNode.moves.length; j++) {
                    moveMap.put(myNextNode.moves[j], myNextNode.pheromone[j]);
                }
                previousPheromoneCache.put(myNextNode.state.hash, moveMap);
            }
        }
    }

    private AcoNode createRootNode(GameState state, List<Move> moves, List<GameState> nextStates) {
        int n = moves.size();
        AcoNode node = new AcoNode(state, n);
        totalNodesCreated.incrementAndGet();

        Map<Move, Double> warmPheromones = previousPheromoneCache.get(state.hash);

        for (int i = 0; i < n; i++) {
            Move m = moves.get(i);
            GameState next = nextStates.get(i);
            node.moves[i] = m;
            node.cachedNextStates.set(i, next);

            if (warmPheromones != null && warmPheromones.containsKey(m)) {
                double cached = warmPheromones.get(m);
                node.pheromone[i] = clamp(0.5 * cached + 0.5 * TAU_INIT, TAU_MIN, TAU_MAX);
            } else {
                node.pheromone[i] = TAU_INIT;
            }

            double vis = evaluateNormalized(next);

            boolean madeCapture = (myRole == BoardConstants.TURN_WHITE)
                    ? (next.blackCount < state.blackCount)
                    : (next.whiteCount < state.whiteCount);
            if (madeCapture) {
                vis = Math.min(1.4, vis + 0.25);
            }

            if (isSquareExposed(next, m.toR, m.toC, myRole)) {
                vis *= 0.65;
            }

            if (gameHistoryHashes.contains(next.boardOnlyHash)) {
                vis *= 0.35;
            }
            node.visibility[i] = Math.max(0.05, vis);
        }
        double beta = (state.turn == myRole) ? BETA_MAX : BETA_MIN;
        for (int i = 0; i < n; i++) {
            node.etaPow[i] = Math.pow(node.visibility[i], beta);
        }
        return node;
    }

    private AcoNode createInternalNode(GameState state) {
        List<Move> moves = RulesEngine.generateLegalMoves(state);
        int n = moves.size();
        AcoNode node = new AcoNode(state, n);
        node.lastEvapWave = currentWave;
        totalNodesCreated.incrementAndGet();

        int kr = state.kingR;
        int kc = state.kingC;

        for (int i = 0; i < n; i++) {
            Move m = moves.get(i);
            node.moves[i] = m;
            node.pheromone[i] = TAU_INIT;
            node.visibility[i] = computeFastMoveVisibility(state, m, kr, kc);
        }
        double beta = (state.turn == myRole) ? BETA_MAX : BETA_MIN;
        for (int i = 0; i < n; i++) {
            node.etaPow[i] = Math.pow(node.visibility[i], beta);
        }
        return node;
    }

    private double computeFastMoveVisibility(GameState state, Move m, int kr, int kc) {
        double vis = 0.35;
        byte piece = state.board[m.fromR][m.fromC];
        byte moverRole = state.turn;

        if (piece == BoardConstants.KING) {
            if (BoardConstants.IS_ESCAPE[m.toR][m.toC]) {
                return 3.0;
            }
            int edgeDist = Math.min(Math.min(m.toR, 8 - m.toR), Math.min(m.toC, 8 - m.toC));
            vis += (4 - edgeDist) * 0.15;
        } else if (piece == BoardConstants.BLACK && kr != -1) {
            int distToKing = Math.abs(m.toR - kr) + Math.abs(m.toC - kc);
            if (distToKing == 1) {
                vis += 0.85;
            } else if (distToKing == 2) {
                vis += 0.25;
            }
            if (m.toR == kr || m.toC == kc) {
                vis += 0.22;
            }
            if ((m.toR == 1 || m.toR == 2 || m.toR == 6 || m.toR == 7)
                    && (m.toC == 1 || m.toC == 2 || m.toC == 6 || m.toC == 7)) {
                vis += 0.12;
            }
        } else if (piece == BoardConstants.WHITE && kr != -1) {
            int distToKing = Math.abs(m.toR - kr) + Math.abs(m.toC - kc);
            if (distToKing <= 2) {
                vis += 0.20;
            }
            if ((m.fromR == kr || m.fromC == kc) && (m.toR != kr && m.toC != kc)) {
                vis += 0.18;
            }
        }

        byte enemy = (moverRole == BoardConstants.TURN_WHITE) ? BoardConstants.BLACK : BoardConstants.WHITE;
        if (hasAdjacentPiece(state, m.toR, m.toC, enemy)) {
            vis += 0.35;
        }

        if (isSquareExposed(state, m.toR, m.toC, moverRole)) {
            vis *= 0.60;
        }

        return Math.max(0.05, vis);
    }

    private boolean isSquareExposed(GameState state, int r, int c, byte ownerRole) {
        int[][] axes = {{-1, 0}, {0, -1}};
        for (int[] axis : axes) {
            int r1 = r + axis[0], c1 = c + axis[1];
            int r2 = r - axis[0], c2 = c - axis[1];
            if (!BoardConstants.inBounds(r1, c1) || !BoardConstants.inBounds(r2, c2)) {
                continue;
            }
            boolean h1 = isHostileBarrier(state, r1, c1, ownerRole);
            boolean h2 = isHostileBarrier(state, r2, c2, ownerRole);
            boolean e1 = (state.board[r1][c1] == BoardConstants.EMPTY);
            boolean e2 = (state.board[r2][c2] == BoardConstants.EMPTY);
            if ((h1 && e2) || (h2 && e1)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHostileBarrier(GameState state, int r, int c, byte victimRole) {
        if (r == BoardConstants.CASTLE_R && c == BoardConstants.CASTLE_C) {
            return true;
        }
        byte p = state.board[r][c];
        if (victimRole == BoardConstants.TURN_WHITE) {
            return BoardConstants.IS_CAMP[r][c] || p == BoardConstants.BLACK;
        } else {
            boolean isHostileCamp = BoardConstants.IS_CAMP[r][c] && !RulesEngine.isCentralExternalCamp(r, c);
            return isHostileCamp || p == BoardConstants.WHITE || p == BoardConstants.KING;
        }
    }

    private boolean hasAdjacentPiece(GameState state, int r, int c, byte targetPiece) {
        if (r > 0 && state.board[r - 1][c] == targetPiece) return true;
        if (r < 8 && state.board[r + 1][c] == targetPiece) return true;
        if (c > 0 && state.board[r][c - 1] == targetPiece) return true;
        if (c < 8 && state.board[r][c + 1] == targetPiece) return true;
        return false;
    }

    private double evaluateNormalized(GameState state) {
        if (isImmediateWinFor(state, myRole)) return 1.0;
        if (isImmediateLossFor(state, myRole)) return 0.0;

        int rawEval = EvaluationHeuristic.evaluate(state);
        int myScore = (state.turn == myRole) ? rawEval : -rawEval;

        double x = -myScore / SCORE_SCALE;
        if (x > 20.0) return 0.0001;
        if (x < -20.0) return 0.9999;
        return 1.0 / (1.0 + Math.exp(x));
    }

    private boolean isImmediateWinFor(GameState state, byte role) {
        if (role == BoardConstants.TURN_WHITE) {
            return state.kingR != -1 && BoardConstants.IS_ESCAPE[state.kingR][state.kingC];
        } else {
            return state.kingR == -1;
        }
    }

    private boolean isImmediateLossFor(GameState state, byte role) {
        if (role == BoardConstants.TURN_WHITE) {
            return state.kingR == -1;
        } else {
            return state.kingR != -1 && BoardConstants.IS_ESCAPE[state.kingR][state.kingC];
        }
    }

    private boolean opponentHasImmediateWin(GameState stateAfterMyMove, byte myRole) {
        byte opponentRole = (myRole == BoardConstants.TURN_WHITE)
                ? BoardConstants.TURN_BLACK
                : BoardConstants.TURN_WHITE;

        List<Move> oppMoves = RulesEngine.generateLegalMoves(stateAfterMyMove);
        for (Move oppMove : oppMoves) {
            if (opponentRole == BoardConstants.TURN_WHITE) {
                if (stateAfterMyMove.board[oppMove.fromR][oppMove.fromC] == BoardConstants.KING
                        && BoardConstants.IS_ESCAPE[oppMove.toR][oppMove.toC]) {
                    return true;
                }
            } else {
                int kr = stateAfterMyMove.kingR;
                int kc = stateAfterMyMove.kingC;
                if (kr != -1 && (Math.abs(oppMove.toR - kr) + Math.abs(oppMove.toC - kc) == 1)) {
                    GameState afterOpp = RulesEngine.applyMove(stateAfterMyMove, oppMove);
                    if (afterOpp.kingR == -1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class AcoNode {
        final GameState state;
        final Move[] moves;
        final double[] pheromone;
        final double[] visibility;
        final double[] etaPow;      // visibility^beta, precomputed
        int lastEvapWave = 0;       // touched by the main thread only
        final int[] visits;
        final double[] rewardSum;
        final double[] minReward;
        final AtomicReferenceArray<GameState> cachedNextStates;
        final AtomicReferenceArray<AcoNode> children;

        AcoNode(GameState state, int numMoves) {
            this.state = state;
            this.moves = new Move[numMoves];
            this.pheromone = new double[numMoves];
            this.visibility = new double[numMoves];
            this.etaPow = new double[numMoves];
            this.visits = new int[numMoves];
            this.rewardSum = new double[numMoves];
            this.minReward = new double[numMoves];
            for (int i = 0; i < numMoves; i++) {
                this.minReward[i] = 1.0;
            }
            this.cachedNextStates = new AtomicReferenceArray<>(numMoves);
            this.children = new AtomicReferenceArray<>(numMoves);
        }
    }

    private static final class AntPath {
        final AcoNode[] nodes;
        final int[] edgeIndices;
        final boolean[] isMaxNode;
        int length = 0;
        double reward = 0.5;

        AntPath(int maxDepth) {
            this.nodes = new AcoNode[maxDepth];
            this.edgeIndices = new int[maxDepth];
            this.isMaxNode = new boolean[maxDepth];
        }
    }
}