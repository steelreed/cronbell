package com.steelreed.cronbell.server.accounts.internal.security;

import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.Account;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Starts and ends browser sessions (DESIGN.md §6). Sign-in changes the session id and
 * rotates the CSRF token; sign-out invalidates the session and clears both cookies.
 */
@Component
public class SessionSignIn {

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	private final SecurityContextRepository contexts;

	private final CsrfTokenRepository csrfTokens;

	private final ProjectService projects;

	private final String sessionCookieName;

	SessionSignIn(SecurityContextRepository contexts, CsrfTokenRepository csrfTokens, ProjectService projects,
			@Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
		this.contexts = contexts;
		this.csrfTokens = csrfTokens;
		this.projects = projects;
		this.sessionCookieName = sessionCookieName;
	}

	/**
	 * Signs the account in for this browser, acting on its first project.
	 * @return the new principal
	 */
	public ProjectPrincipal signIn(Account account, HttpServletRequest request, HttpServletResponse response) {
		String projectId = this.projects.findMemberships(account.id())
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("account " + account.id() + " has no project"))
			.projectId();
		ProjectPrincipal principal = new ProjectPrincipal(projectId, account.id(), account.email());
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of(SecurityConfig.PROJECT, SecurityConfig.ACCOUNT));
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		var context = this.holder.createEmptyContext();
		context.setAuthentication(authentication);
		this.holder.setContext(context);
		this.contexts.saveContext(context, request, response);
		new CsrfAuthenticationStrategy(this.csrfTokens).onAuthentication(authentication, request, response);
		loadCsrfToken(request);
		return principal;
	}

	public void signOut(HttpServletRequest request, HttpServletResponse response) {
		Authentication authentication = this.holder.getContext().getAuthentication();
		new CsrfLogoutHandler(this.csrfTokens).logout(request, response, authentication);
		new CookieClearingLogoutHandler(this.sessionCookieName).logout(request, response, authentication);
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		loadCsrfToken(request);
	}

	/** Makes sure the response carries a CSRF cookie for the next request. */
	static void loadCsrfToken(HttpServletRequest request) {
		if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
			token.getToken();
		}
	}

}
