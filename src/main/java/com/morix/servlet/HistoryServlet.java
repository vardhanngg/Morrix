package com.morix.servlet;

import com.morix.util.DB;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * GET /api/history?username=xxx&limit=10
 * Returns JSON array of last N games for the user.
 * Used via AJAX fetch() from the frontend history/profile page.
 *
 * Response format:
 * [
 *   { "opponent": "alice", "result": "win", "moves": 12, "date": "2024-06-01 14:32" },
 *   ...
 * ]
 */
public class HistoryServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        String username = req.getParameter("username");
        if (username == null || username.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().print("{\"error\":\"username parameter required\"}");
            return;
        }

        int limit = 10;
        String limitParam = req.getParameter("limit");
        if (limitParam != null) {
            try { limit = Math.min(50, Integer.parseInt(limitParam)); }
            catch (NumberFormatException ignored) {}
        }

        try {
            resp.getWriter().print(DB.getGameHistory(username.trim(), limit).toString());
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().print("[]");
        }
    }
}
