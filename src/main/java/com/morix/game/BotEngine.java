package com.morix.game;

import java.util.*;

/**
 * Simple AI bot for 3 Men's Morris.
 * Strategy:
 *   1. Win if possible
 *   2. Block opponent from winning
 *   3. Prefer center (pos 5), then corners/edges
 *   4. Random valid move as fallback
 */
public class BotEngine {

    private static final int[][] WINNING_COMBOS = {
        {0,1,2},{3,4,5},{6,7,8},
        {0,3,6},{1,4,7},{2,5,8},
        {0,4,8},{2,4,6}
    };

    private static final Map<Integer, List<Integer>> ADJACENT;
    static {
        ADJACENT = new HashMap<>();
        ADJACENT.put(1, Arrays.asList(2,4,5));
        ADJACENT.put(2, Arrays.asList(1,3,5));
        ADJACENT.put(3, Arrays.asList(2,6,5));
        ADJACENT.put(4, Arrays.asList(1,7,5));
        ADJACENT.put(5, Arrays.asList(1,2,3,4,6,7,8,9));
        ADJACENT.put(6, Arrays.asList(3,9,5));
        ADJACENT.put(7, Arrays.asList(4,8,5));
        ADJACENT.put(8, Arrays.asList(7,9,5));
        ADJACENT.put(9, Arrays.asList(6,8,5));
    }

    private static final Random RNG = new Random();

    // Preferred positions: center first, then corners, then edges
    private static final int[] PREFERRED = {5, 1, 3, 7, 9, 2, 4, 6, 8};

    /**
     * Decide a placement position (1-9) for the bot.
     * @param board current board (null = empty)
     * @param botSymbol "X" or "O"
     * @return 1-indexed position to place
     */
    public static int choosePlacement(String[] board, String botSymbol) {
        String oppSymbol = "X".equals(botSymbol) ? "O" : "X";

        // 1. Can we win?
        int win = findWinningPlacement(board, botSymbol);
        if (win != -1) return win;

        // 2. Block opponent from winning
        int block = findWinningPlacement(board, oppSymbol);
        if (block != -1) return block;

        // 3. Preferred positions
        for (int pos : PREFERRED) {
            if (board[pos - 1] == null) return pos;
        }

        // 4. Any empty
        for (int i = 0; i < 9; i++) {
            if (board[i] == null) return i + 1;
        }
        return -1;
    }

    /**
     * Decide a move (from, to) for the bot during movement phase.
     * @param board current board
     * @param botSymbol "X" or "O"
     * @return int[]{from, to} (1-indexed), or null if no valid move
     */
    public static int[] chooseMove(String[] board, String botSymbol) {
        String oppSymbol = "X".equals(botSymbol) ? "O" : "X";

        // 1. Can we win by moving?
        int[] winMove = findWinningMove(board, botSymbol);
        if (winMove != null) return winMove;

        // 2. Block opponent from winning
        int[] blockMove = findBlockingMove(board, botSymbol, oppSymbol);
        if (blockMove != null) return blockMove;

        // 3. Try to move toward center or preferred positions
        int[] strategicMove = findStrategicMove(board, botSymbol);
        if (strategicMove != null) return strategicMove;

        // 4. Random valid move
        return findRandomMove(board, botSymbol);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private static int findWinningPlacement(String[] board, String symbol) {
        for (int pos = 1; pos <= 9; pos++) {
            if (board[pos - 1] != null) continue;
            board[pos - 1] = symbol;
            boolean wins = checkWin(board, symbol);
            board[pos - 1] = null;
            if (wins) return pos;
        }
        return -1;
    }

    private static int[] findWinningMove(String[] board, String symbol) {
        for (int from = 1; from <= 9; from++) {
            if (!symbol.equals(board[from - 1])) continue;
            for (int to : ADJACENT.getOrDefault(from, Collections.emptyList())) {
                if (board[to - 1] != null) continue;
                board[from - 1] = null;
                board[to - 1]   = symbol;
                boolean wins = checkWin(board, symbol);
                board[to - 1]   = null;
                board[from - 1] = symbol;
                if (wins) return new int[]{from, to};
            }
        }
        return null;
    }

    private static int[] findBlockingMove(String[] board, String botSymbol, String oppSymbol) {
        // Find moves that prevent opponent from winning on next turn
        for (int oppFrom = 1; oppFrom <= 9; oppFrom++) {
            if (!oppSymbol.equals(board[oppFrom - 1])) continue;
            for (int oppTo : ADJACENT.getOrDefault(oppFrom, Collections.emptyList())) {
                if (board[oppTo - 1] != null) continue;
                // Simulate opponent's move
                board[oppFrom - 1] = null;
                board[oppTo - 1]   = oppSymbol;
                boolean oppWins = checkWin(board, oppSymbol);
                board[oppTo - 1]   = null;
                board[oppFrom - 1] = oppSymbol;

                if (oppWins) {
                    // Try to move bot's piece to oppTo to block
                    for (int botFrom = 1; botFrom <= 9; botFrom++) {
                        if (!botSymbol.equals(board[botFrom - 1])) continue;
                        List<Integer> adj = ADJACENT.getOrDefault(botFrom, Collections.emptyList());
                        if (adj.contains(oppTo) && board[oppTo - 1] == null) {
                            return new int[]{botFrom, oppTo};
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int[] findStrategicMove(String[] board, String symbol) {
        // Try to move a piece toward a preferred position
        for (int pref : PREFERRED) {
            if (board[pref - 1] != null) continue;
            // Find a bot piece adjacent to this preferred pos
            for (int from = 1; from <= 9; from++) {
                if (!symbol.equals(board[from - 1])) continue;
                if (ADJACENT.getOrDefault(from, Collections.emptyList()).contains(pref)) {
                    return new int[]{from, pref};
                }
            }
        }
        return null;
    }

    private static int[] findRandomMove(String[] board, String symbol) {
        List<int[]> moves = new ArrayList<>();
        for (int from = 1; from <= 9; from++) {
            if (!symbol.equals(board[from - 1])) continue;
            for (int to : ADJACENT.getOrDefault(from, Collections.emptyList())) {
                if (board[to - 1] == null) moves.add(new int[]{from, to});
            }
        }
        if (moves.isEmpty()) return null;
        return moves.get(RNG.nextInt(moves.size()));
    }

    private static boolean checkWin(String[] board, String symbol) {
        for (int[] combo : WINNING_COMBOS) {
            if (symbol.equals(board[combo[0]]) &&
                symbol.equals(board[combo[1]]) &&
                symbol.equals(board[combo[2]])) return true;
        }
        return false;
    }
}
