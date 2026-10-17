package com.steelreed.cronbell.server.accounts.internal.security;

import java.util.ArrayList;
import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.Account;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import com.steelreed.cronbell.server.accounts.internal.ProjectService.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * Starts, changes and ends browser sessions (DESIGN.md §6). Sign-in changes the session
 * id, rotates the CSRF token and records the account's session version; sign-out
 * invalidates the session and clears both cookies.
 */
@Component
public class SessionSignIn {

	/** Session attribute: the account's session version at sign-in. */
	static final String VERSION_ATTRIBUTE = SessionSignIn.class.getName() + ".version";

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
	 * Signs the account in for this browser, acting on its oldest membership (or on no
	 * project when it has none).
	 * @return the new principal
	 */
	public ProjectPrincipal signIn(Account account, HttpServletRequest request, HttpServletResponse response) {
		String projectId = this.projects.findMemberships(account.id())
			.stream()
			.findFirst()
			.map(ProjectService.Membership::projectId)
			.orElse(null);
		return signIn(account, projectId, request, response);
	}

	/**
	 * Signs the account in for this browser, acting on {@code projectId}, which must be
	 * one of its memberships.
	 * @return the new principal
	 */
	public ProjectPrincipal signIn(Account account, String projectId, HttpServletRequest request,
			HttpServletResponse response) {
		Role role = (projectId != null)
				? this.projects.findMembership(account.id(), projectId)
					.map(ProjectService.Membership::role)
					.orElseThrow(() -> new IllegalStateException("account " + account.id() + " is not in " + projectId))
				: null;
		ProjectPrincipal principal = new ProjectPrincipal(projectId, account.id(), account.email());
		Authentication authentication = authentication(principal, account.instanceAdmin(), role);
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		save(authentication, request, response);
		request.getSession().setAttribute(VERSION_ATTRIBUTE, account.sessionVersion());
		new CsrfAuthenticationStrategy(this.csrfTokens).onAuthentication(authentication, request, response);
		loadCsrfToken(request);
		return principal;
	}

	/**
	 * Makes another project the session's current one. The caller has checked the
	 * membership.
	 * @return the new principal
	 */
	public ProjectPrincipal switchProject(ProjectPrincipal current, ProjectService.Membership membership,
			boolean instanceAdmin, HttpServletRequest request, HttpServletResponse response) {
		ProjectPrincipal principal = new ProjectPrincipal(membership.projectId(), current.accountId(), current.email());
		save(authentication(principal, instanceAdmin, membership.role()), request, response);
		return principal;
	}

	private void save(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
		var context = this.holder.createEmptyContext();
		context.setAuthentication(authentication);
		this.holder.setContext(context);
		this.contexts.saveContext(context, request, response);
	}

	public void signOut(HttpServletRequest request, HttpServletResponse response) {
		Authentication authentication = this.holder.getContext().getAuthentication();
		new CsrfLogoutHandler(this.csrfTokens).logout(request, response, authentication);
		new CookieClearingLogoutHandler(this.sessionCookieName).logout(request, response, authentication);
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		loadCsrfToken(request);
	}

	/**
	 * The authentication of a signed-in account: always {@code ACCOUNT}; {@code PROJECT}
	 * and {@code MEMBER} when it is a member of the current project, {@code OWNER} when
	 * it owns it; {@code ADMIN} for the instance admin.
	 */
	static Authentication authentication(ProjectPrincipal principal, boolean instanceAdmin, Role role) {
		List<GrantedAuthority> authorities = new ArrayList<>(List.of(SecurityConfig.ACCOUNT));
		if (role != null && principal.projectId() != null) {
			authorities.add(SecurityConfig.PROJECT);
			authorities.add(SecurityConfig.MEMBER);
			if (role == Role.OWNER) {
				authorities.add(SecurityConfig.OWNER);
			}
		}
		if (instanceAdmin) {
			authorities.add(SecurityConfig.ADMIN);
		}
		return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
	}

	/** Makes sure the response carries a CSRF cookie for the next request. */
	static void loadCsrfToken(HttpServletRequest request) {
		if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
			token.getToken();
		}
	}

}
