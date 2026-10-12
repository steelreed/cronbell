package com.steelreed.cronbell.server.accounts.internal.security;

import java.util.Set;
import java.util.regex.Pattern;

import com.steelreed.cronbell.server.accounts.internal.AccountService;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Two ways in (DESIGN.md §6). Scripts and the starter send a project API key as a bearer
 * token: stateless, no CSRF. The dashboard signs in and uses a session cookie, so its
 * unsafe requests to /api need the CSRF header (single-page-app pattern: the token is in
 * the readable {@code XSRF-TOKEN} cookie and comes back as {@code X-XSRF-TOKEN}). Both
 * resolve to a {@link com.steelreed.cronbell.server.accounts.ProjectPrincipal}. Pings are
 * open: the ping key is the secret.
 *
 * <p>
 * Authorities (DESIGN.md §6, Roles): {@code PROJECT} acts on a project (an API key, or a
 * session whose account is a member of its current project); {@code ACCOUNT} is any
 * signed-in session; {@code MEMBER} and {@code OWNER} are the session's role in its
 * current project; {@code ADMIN} is the instance admin. {@link AccountSessionFilter}
 * recomputes a session's authorities on every request.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	static final SimpleGrantedAuthority PROJECT = new SimpleGrantedAuthority("ROLE_PROJECT");

	/** Only signed-in accounts, never API keys. */
	static final SimpleGrantedAuthority ACCOUNT = new SimpleGrantedAuthority("ROLE_ACCOUNT");

	/** A signed-in account that is a member (any role) of its current project. */
	static final SimpleGrantedAuthority MEMBER = new SimpleGrantedAuthority("ROLE_MEMBER");

	/** A signed-in account that owns its current project. */
	static final SimpleGrantedAuthority OWNER = new SimpleGrantedAuthority("ROLE_OWNER");

	/** The instance admin. */
	static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	/** Requests that sign in, so need CSRF protection without a session (login CSRF). */
	private static final Pattern SIGN_IN_PATHS = Pattern.compile("/api/v1/(session|setup|invites/[^/]+/accept)");

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProjectService projects, AccountService accounts,
			SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) throws Exception {
		// Cookie-authenticated changes, plus sign-in, setup and accepting an invite
		// (login
		// CSRF). Without a session cookie or with a bearer key there is nothing a forged
		// request could ride on.
		RequestMatcher csrfRequired = (request) -> !SAFE_METHODS.contains(request.getMethod())
				&& request.getRequestURI().startsWith(request.getContextPath() + "/api/")
				&& !ApiKeyAuthenticationFilter.hasBearer(request)
				&& (request.getRequestedSessionId() != null || SIGN_IN_PATHS.matcher(path(request)).matches());
		http.authorizeHttpRequests((requests) -> requests
			.requestMatchers("/ping/**", "/error", "/actuator/health/**", "/api/v1/openapi.json", "/api/v1/setup",
					"/api/v1/session")
			.permitAll()
			// Reading and accepting an invite link.
			.requestMatchers(HttpMethod.GET, "/api/v1/invites/*")
			.permitAll()
			.requestMatchers(HttpMethod.POST, "/api/v1/invites/*/accept")
			.permitAll()
			.requestMatchers("/api/v1/admin/**")
			.hasRole("ADMIN")
			.requestMatchers("/api/v1/invites", "/api/v1/invites/**")
			.hasRole("OWNER")
			.requestMatchers(HttpMethod.GET, "/api/v1/keys")
			.hasRole("MEMBER")
			.requestMatchers("/api/v1/keys", "/api/v1/keys/**")
			.hasRole("OWNER")
			.requestMatchers("/api/v1/members", "/api/v1/members/**")
			.hasRole("MEMBER")
			.requestMatchers("/api/v1/session/**", "/api/v1/projects", "/api/v1/account/**")
			.hasRole("ACCOUNT")
			.requestMatchers("/api/**")
			.hasRole("PROJECT")
			.anyRequest()
			.permitAll())
			.addFilterBefore(new ApiKeyAuthenticationFilter(projects), BasicAuthenticationFilter.class)
			.addFilterAfter(new AccountSessionFilter(accounts), ApiKeyAuthenticationFilter.class)
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			.securityContext((context) -> context.securityContextRepository(contexts))
			// Only SessionSignIn stores a context in the session, never a bearer request.
			.sessionManagement((session) -> session.requireExplicitAuthenticationStrategy(true))
			.requestCache((cache) -> cache.requestCache(new NullRequestCache()))
			.csrf((csrf) -> csrf.csrfTokenRepository(csrfTokens)
				.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
				.requireCsrfProtectionMatcher(csrfRequired))
			.httpBasic((basic) -> basic.disable())
			.formLogin((form) -> form.disable())
			.logout((logout) -> logout.disable())
			.exceptionHandling((exceptions) -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
		return http.build();
	}

	private static String path(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length());
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
				new HttpSessionSecurityContextRepository());
	}

	/**
	 * Readable by the page; {@code Secure} follows the request, like the session cookie.
	 */
	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieCustomizer((cookie) -> cookie.sameSite("Lax"));
		return repository;
	}

	/**
	 * Accounts are not Spring users; this stops Boot from generating a default password.
	 */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}

}
