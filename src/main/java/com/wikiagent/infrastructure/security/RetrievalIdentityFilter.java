package com.wikiagent.infrastructure.security;

import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * E5 检索身份注入：读取 X-Business-Identity header 写入 {@link RetrievalSecurityContext}，
 * 请求结束清理。header 缺省时不过滤（保持旧行为）。
 */
@Component
public class RetrievalIdentityFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Business-Identity";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String identity = request.getHeader(HEADER);
        try {
            if (identity != null && !identity.isBlank()) {
                RetrievalSecurityContext.setIdentity(identity.trim());
            }
            filterChain.doFilter(request, response);
        } finally {
            RetrievalSecurityContext.clear();
        }
    }
}
