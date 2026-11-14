package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.common.api.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
class AccessTokenFilter extends OncePerRequestFilter {
    private final ServiceTokens tokens;
    private final JsonMapper json;

    AccessTokenFilter(ServiceTokens tokens, JsonMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        boolean expired = false;
        if (bearer) {
            String userId = null;
            try {
                userId = tokens.accessSubject(authorization.substring(7));
            } catch (ServiceTokens.ExpiredAccessToken exception) {
                expired = true;
            }
            if (userId != null) {
                String subject = userId;
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override
                    public Principal getUserPrincipal() {
                        return () -> subject;
                    }
                }, response);
                return;
            }
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (("/api/v1/me".equals(path) && List.of("GET", "HEAD", "PATCH", "DELETE").contains(request.getMethod()))
                || ("/api/v1/me/onboarding".equals(path) && "PUT".equals(request.getMethod()))
                || ("POST".equals(request.getMethod())
                        && List.of("/api/v1/auth/reauth", "/api/v1/auth/reauth/authorizations").contains(path))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), ErrorResponse.of(
                    expired ? "ACCESS_TOKEN_EXPIRED" : "AUTHENTICATION_REQUIRED",
                    expired ? "액세스 토큰이 만료되었습니다." : "로그인이 필요합니다.", List.of()));
            return;
        }
        chain.doFilter(request, response);
    }
}
