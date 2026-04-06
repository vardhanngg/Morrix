package com.morix.servlet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URL;

/**
 * Serves the single-page frontend (index.html) for all non-API GET requests.
 */
public class IndexServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path = req.getPathInfo();
        // Only serve index for root — let Tomcat handle static assets normally
        resp.setContentType("text/html;charset=UTF-8");
        InputStream is = getServletContext().getResourceAsStream("/index.html");
        if (is == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "index.html not found");
            return;
        }
        try (is; OutputStream os = resp.getOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }
}
