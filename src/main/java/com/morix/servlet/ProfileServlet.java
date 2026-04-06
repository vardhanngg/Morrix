package com.morix.servlet;

import com.morix.util.DB;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * GET /api/profile?username=xxx
 * Returns JSON profile stats for a player.
 * Used via AJAX fetch() from the frontend.
 *
 * Response format:
 * { "username": "alice", "wins": 10, "losses": 4, "total": 14, "winRate": 71.4 }
 */
public class ProfileServlet extends HttpServlet {

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

        try {
            resp.getWriter().print(DB.getProfile(username.trim()).toString());
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().print("{\"error\":\"Server error\"}");
        }
    }
}
