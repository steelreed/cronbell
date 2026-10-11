package com.steelreed.cronbell.server.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Loads the deferred CSRF token on browser requests to the API, so the {@code XSRF-TOKEN}
 * cookie is set before the page needs it.
 */
class CsrfCookieFilter extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + "/api/")
				|| ApiKeyAuthenticationFilter.hasBearer(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		SessionSignIn.loadCsrfToken(request);
		chain.doFilter(request, response);
	}

}
