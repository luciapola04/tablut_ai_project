# L^2 — Tablut AI Player (Adversarial MMAS)

**L^2** is an autonomous Java 11 agent developed for the **Tablut Challenge** (9×9 Ashton rules). It employs a Swarm Intelligence approach based on an **Adversarial Min-Max Ant System (MMAS)**.

## Key Features
* **Adversarial MMAS Engine (`AntColonySearchEngine`):** Anytime wave-based ant colony search up to 6 plies with bounded pheromone trails ($\tau \in [0.05, 5.0]$), evaporation ($\rho = 0.12$), and asymmetric MAX/MIN policies (`BETA_MIN = 2.8`, `EPSILON_MIN = 0.02`) to model strong opponent replies.
* **Tactical Pre-Filters:** Instant depth-1 winning move execution and depth-2 blunder screening.
* **Prudent Move Selection & Warm-Start:** Combines pheromone trails, mean reward, and worst-case refutation (`minReward`), while caching subtree pheromones across consecutive turns.
* **Domain Heuristic & Zobrist Hashing:** Asymmetric material/positional evaluation (`EvaluationHeuristic`) paired with 64-bit Zobrist board hashing to avoid accidental repetition draws.

## Build & Run
All required dependencies are bundled locally in `lib/` (no internet connection needed at runtime).

```bash
# Compile
mkdir -p target/classes
javac -cp "lib/*" -d target/classes \$(find src/main/java -name "*.java")
chmod +x runmyplayer.sh

# Run (<WHITE|BLACK> <timeout_seconds> <server_ip>)
example: ./runmyplayer.sh WHITE 60 127.0.0.1
