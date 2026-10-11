package com.steelreed.cronbell.server.security;

import com.steelreed.cronbell.server.project.ProjectService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Pings are open (the ping key is the secret). The management API needs a project API key
 * as a bearer token. Session sign-in for the web app comes later; it will need CSRF on
 * /api/v1.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProjectService projects) throws Exception {
		http.authorizeHttpRequests((requests) -> requests
			.requestMatchers("/ping/**", "/error", "/actuator/health/**", "/api/v1/openapi.json")
			.permitAll()
			.requestMatchers("/api/**")
			.authenticated()
			.anyRequest()
			.permitAll())
			.addFilterBefore(new ApiKeyAuthenticationFilter(projects), BasicAuthenticationFilter.class)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache((cache) -> cache.requestCache(new NullRequestCache()))
			// Only bearer tokens for now: no cookies, so no CSRF.
			.csrf((csrf) -> csrf.disable())
			.httpBasic((basic) -> basic.disable())
			.formLogin((form) -> form.disable())
			.logout((logout) -> logout.disable())
			.exceptionHandling((exceptions) -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
		return http.build();
	}

	/** No users yet; this stops Boot from generating a default password. */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}

}
