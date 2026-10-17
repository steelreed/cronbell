package com.steelreed.cronbell.server.accounts.internal.security;

import java.io.IOException;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads a signed-in session's account and membership again on every request (DESIGN.md
 * §6, Projects and invites), so the stored session never outlives a change:
 * <ul>
 * <li>a session made before a password change (older session version), or of an account
 * that no longer exists, is invalidated and the request is anonymous;</li>
 * <li>the role in the current project, and whether there still is one, come from the
 * database: a removed member loses the project's authorities at once;</li>
 * <li>the instance admin flag likewise.</li>
 * </ul>
 * The refreshed authentication is used for this request only; the session keeps what
 * {@link SessionSignIn} stored.
 */
class AccountSessionFilter extends OncePerRequestFilter {

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	private final AccountService accounts;

	AccountSessionFilter(AccountService accounts) {
		this.accounts = accounts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!ApiKeyAuthenticationFilter.hasBearer(request)) {
			refresh(request);
		}
		chain.doFilter(request, response);
	}

	private void refresh(HttpServletRequest request) {
		Authentication authentication = this.holder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectPrincipal principal)
				|| !principal.isAccount()) {
			return;
		}
		HttpSession session = request.getSession(false);
		Object version = (session != null) ? session.getAttribute(SessionSignIn.VERSION_ATTRIBUTE) : null;
		var state = this.accounts.findSessionState(principal.accountId(), principal.projectId());
		if (state.isEmpty() || !(version instanceof Integer signedInVersion)
				|| signedInVersion != state.get().account().sessionVersion()) {
			if (session != null) {
				session.invalidate();
			}
			this.holder.setContext(this.holder.createEmptyContext());
			return;
		}
		var account = state.get().account();
		var role = state.get().role();
		ProjectPrincipal current = new ProjectPrincipal((role != null) ? principal.projectId() : null, account.id(),
				account.email());
		var context = this.holder.createEmptyContext();
		context.setAuthentication(SessionSignIn.authentication(current, account.instanceAdmin(), role));
		this.holder.setContext(context);
	}

}
