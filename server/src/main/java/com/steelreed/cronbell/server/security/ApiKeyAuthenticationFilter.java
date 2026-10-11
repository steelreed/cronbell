package com.steelreed.cronbell.server.security;

import java.io.IOException;
import java.util.List;

import com.steelreed.cronbell.server.project.ProjectService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <project API key>}. A request with a bearer
 * header never uses the session: a missing or unknown key leaves it anonymous, even next
 * to a session cookie, and the authorization rules then answer 401.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final ProjectService projects;

	ApiKeyAuthenticationFilter(ProjectService projects) {
		this.projects = projects;
	}

	/** Whether the request carries a bearer token, so is stateless and CSRF-free. */
	static boolean hasBearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (hasBearer(request)) {
			String key = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length()).trim();
			var context = SecurityContextHolder.createEmptyContext();
			if (!key.isEmpty()) {
				this.projects.findProjectIdByApiKey(key)
					.ifPresent((projectId) -> context.setAuthentication(UsernamePasswordAuthenticationToken
						.authenticated(ProjectPrincipal.apiKey(projectId), null, List.of(SecurityConfig.PROJECT))));
			}
			SecurityContextHolder.setContext(context);
		}
		chain.doFilter(request, response);
	}

}
