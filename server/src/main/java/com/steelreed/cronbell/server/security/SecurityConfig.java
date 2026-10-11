package com.steelreed.cronbell.server.security;

import java.util.Set;

import com.steelreed.cronbell.server.project.ProjectService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * resolve to a {@link ProjectPrincipal}. Pings are open: the ping key is the secret.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	static final SimpleGrantedAuthority PROJECT = new SimpleGrantedAuthority("ROLE_PROJECT");

	/** Only signed-in accounts, never API keys. */
	static final SimpleGrantedAuthority ACCOUNT = new SimpleGrantedAuthority("ROLE_ACCOUNT");

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	private static final Set<String> SIGN_IN_PATHS = Set.of("/api/v1/session", "/api/v1/setup");

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProjectService projects,
			SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) throws Exception {
		// Cookie-authenticated changes, plus sign-in and setup (login CSRF). Without a
		// session
		// cookie or with a bearer key there is nothing a forged request could ride on.
		RequestMatcher csrfRequired = (request) -> !SAFE_METHODS.contains(request.getMethod())
				&& request.getRequestURI().startsWith(request.getContextPath() + "/api/")
				&& !ApiKeyAuthenticationFilter.hasBearer(request)
				&& (request.getRequestedSessionId() != null || SIGN_IN_PATHS
					.contains(request.getRequestURI().substring(request.getContextPath().length())));
		http.authorizeHttpRequests((requests) -> requests
			.requestMatchers("/ping/**", "/error", "/actuator/health/**", "/api/v1/openapi.json", "/api/v1/setup",
					"/api/v1/session")
			.permitAll()
			.requestMatchers("/api/v1/keys/**", "/api/v1/keys")
			.hasRole("ACCOUNT")
			.requestMatchers("/api/**")
			.authenticated()
			.anyRequest()
			.permitAll())
			.addFilterBefore(new ApiKeyAuthenticationFilter(projects), BasicAuthenticationFilter.class)
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
