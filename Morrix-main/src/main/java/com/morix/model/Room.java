package com.morix.model;

import com.morix.game.GameEngine;

import javax.websocket.Session;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds all state for a single active game room.
 * Access must be synchronised on the Room instance itself.
 */
public class Room {

    public final String     code;
    public final GameEngine game        = new GameEngine();

    // Slot 0 = X, slot 1 = O
    public final Session[]  players     = new Session[2];
    public final String[]   usernames   = new String[2];  // 0=X,1=O

    public String  turn;         // "X" or "O"
    public String  firstTurn;
    public int     placed       = 0;   // pieces placed so far (max 6)
    public int     totalMoves   = 0;
    public boolean gameOver     = false;

    // "X" -> "pending" or absent
    public final Map<String, String> rematchVotes = new HashMap<>();

    public Room(String code, Session xSession, String xUsername, String firstTurn) {
        this.code      = code;
        this.turn      = firstTurn;
        this.firstTurn = firstTurn;
        players[0]   = xSession;
        usernames[0] = xUsername;
    }

    /** Slot for a symbol: 0 for X, 1 for O. */
    public static int slot(String symbol) {
        return "X".equals(symbol) ? 0 : 1;
    }

    /** Symbol for a slot. */
    public static String symbol(int slot) {
        return slot == 0 ? "X" : "O";
    }

    public String opponentSymbol(String sym) {
        return "X".equals(sym) ? "O" : "X";
    }

    public Session opponentSession(String sym) {
        return players[slot(opponentSymbol(sym))];
    }

    public String opponentUsername(String sym) {
        return usernames[slot(opponentSymbol(sym))];
    }
}
