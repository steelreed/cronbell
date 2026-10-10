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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <project API key>}. A missing or unknown key leaves
 * the request anonymous; the authorization rules then answer 401.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final ProjectService projects;

	ApiKeyAuthenticationFilter(ProjectService projects) {
		this.projects = projects;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			String key = header.substring(BEARER.length()).trim();
			if (!key.isEmpty()) {
				this.projects.findProjectIdByApiKey(key).ifPresent(projectId -> {
					var authentication = UsernamePasswordAuthenticationToken.authenticated(
							new ProjectPrincipal(projectId), null, List.of(new SimpleGrantedAuthority("ROLE_PROJECT")));
					var context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(authentication);
					SecurityContextHolder.setContext(context);
				});
			}
		}
		chain.doFilter(request, response);
	}

}
