package com.morix.game;

import java.util.*;

/**
 * Direct Java port of game_engine.py — same rules, same adjacency map.
 */
public class GameEngine {

    private final String[] board = new String[9]; // null = empty

    // Winning combinations (0-indexed)
    private static final int[][] WINNING_COMBOS = {
        {0,1,2}, {3,4,5}, {6,7,8},
        {0,3,6}, {1,4,7}, {2,5,8},
        {0,4,8}, {2,4,6}
    };

    // 1-indexed adjacency (positions 1-9)
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

    public void reset() {
        Arrays.fill(board, null);
    }

    /** Place a piece at position (1-9). Returns true on success. */
    public boolean placePiece(int position, String player) {
        if (position < 1 || position > 9) return false;
        if (board[position - 1] != null) return false;
        board[position - 1] = player;
        return true;
    }

    /** Move from fromPos to toPos (both 1-indexed). Returns true on success. */
    public boolean movePiece(int fromPos, int toPos, String player) {
        if (fromPos < 1 || fromPos > 9 || toPos < 1 || toPos > 9) return false;
        if (!player.equals(board[fromPos - 1])) return false;
        if (board[toPos - 1] != null) return false;
        List<Integer> adj = ADJACENT.getOrDefault(fromPos, Collections.emptyList());
        if (!adj.contains(toPos)) return false;
        board[fromPos - 1] = null;
        board[toPos   - 1] = player;
        return true;
    }

    /** Return true if player has three in a row. */
    public boolean checkWin(String player) {
        for (int[] combo : WINNING_COMBOS) {
            if (player.equals(board[combo[0]]) &&
                player.equals(board[combo[1]]) &&
                player.equals(board[combo[2]])) {
                return true;
            }
        }
        return false;
    }

    /** Return a copy of the board array (null = empty). */
    public String[] getBoard() {
        return Arrays.copyOf(board, 9);
    }
}
