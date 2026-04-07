package com.morix.model;

import javax.websocket.Session;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central in-memory store — thread-safe singleton.
 * Replaces Python's module-level dicts: rooms, sessions, online_sockets, auth_tokens.
 */
public class GameStore {

    private static final GameStore INSTANCE = new GameStore();
    public  static GameStore get() { return INSTANCE; }
    private GameStore() {}

    private static final SecureRandom RNG = new SecureRandom();

    // ── Active rooms ─────────────────────────────────────────────────────────
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public Room getRoom(String code)                { return rooms.get(code); }
    public void putRoom(String code, Room room)     { rooms.put(code, room); }
    public Room removeRoom(String code)             { return rooms.remove(code); }
    public boolean roomExists(String code)          { return rooms.containsKey(code); }
    public int roomCount()                          { return rooms.size(); }

    /** Generate a unique 4-digit room code. */
    public String generateCode() {
        String code;
        do { code = String.format("%04d", RNG.nextInt(10000)); }
        while (rooms.containsKey(code));
        return code;
    }

    // ── Game sessions (token -> session info) ────────────────────────────────
    private static class GameSession {
        String symbol, roomCode, username;
        long   expires;
        GameSession(String sym, String code, String user) {
            this.symbol   = sym;
            this.roomCode = code;
            this.username = user;
            this.expires  = System.currentTimeMillis() + 3_600_000L; // 1 h
        }
        boolean valid() { return System.currentTimeMillis() < expires; }
    }

    private final Map<String, GameSession> gameSessions = new ConcurrentHashMap<>();

    public String createGameSession(String symbol, String roomCode, String username) {
        String token = hexToken(16);
        gameSessions.put(token, new GameSession(symbol, roomCode, username));
        return token;
    }

    public String[] validateGameSession(String token) {
        // Returns [symbol, roomCode, username] or null
        GameSession s = gameSessions.get(token);
        if (s == null || !s.valid()) { gameSessions.remove(token); return null; }
        return new String[]{ s.symbol, s.roomCode, s.username };
    }

    public void invalidateSessionsForRoom(String code) {
        gameSessions.entrySet().removeIf(e -> code.equals(e.getValue().roomCode));
    }

    public void purgeExpiredGameSessions() {
        gameSessions.entrySet().removeIf(e -> !e.getValue().valid());
    }

    /** Find active game session for a username (used for auto-login rejoin). */
    public String[] findActiveGameForUser(String username) {
        for (Map.Entry<String, GameSession> e : gameSessions.entrySet()) {
            GameSession s = e.getValue();
            if (username.equals(s.username) && s.valid() && rooms.containsKey(s.roomCode))
                return new String[]{ e.getKey(), s.symbol, s.roomCode };
        }
        return null;
    }

    // ── Auth tokens ──────────────────────────────────────────────────────────
    private static class AuthToken {
        String username;
        long   expires;
        AuthToken(String u) { this.username = u; this.expires = System.currentTimeMillis() + 7 * 86_400_000L; }
        boolean valid() { return System.currentTimeMillis() < expires; }
    }

    private final Map<String, AuthToken> authTokens = new ConcurrentHashMap<>();

    public String createAuthToken(String username) {
        String token = hexToken(20);
        authTokens.put(token, new AuthToken(username));
        return token;
    }

    public String validateAuthToken(String token) {
        AuthToken a = authTokens.get(token);
        if (a == null || !a.valid()) { authTokens.remove(token); return null; }
        return a.username;
    }

    // ── Online sockets (username -> WebSocket Session) ───────────────────────
    private final Map<String, Session> onlineSockets = new ConcurrentHashMap<>();

    public void putOnline(String username, Session ws)   { onlineSockets.put(username, ws); }
    public void removeOnline(String username)            { onlineSockets.remove(username); }
    public Session getOnline(String username)            { return onlineSockets.get(username); }
    public boolean isOnline(String username)             { return onlineSockets.containsKey(username); }
    public int onlineCount()                             { return onlineSockets.size(); }

    // ── Pending invite coordination ──────────────────────────────────────────
    // pendingGameFor[username] = {code, symbol, sessionToken, opponent}
    private final Map<String, String[]> pendingGameFor = new ConcurrentHashMap<>();

    public void setPendingGame(String username, String[] info) { pendingGameFor.put(username, info); }
    public String[] popPendingGame(String username)            { return pendingGameFor.remove(username); }

    // ── Player status ────────────────────────────────────────────────────────
    public String getStatus(String username) {
        if (!onlineSockets.containsKey(username)) return "offline";
        for (Room r : rooms.values()) {
            if (username.equals(r.usernames[0]) || username.equals(r.usernames[1]))
                return "ingame";
        }
        return "online";
    }

    // ── Utility ──────────────────────────────────────────────────────────────
    private static String hexToken(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }
}
