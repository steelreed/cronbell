package com.steelreed.cronbell.server.db;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

/**
 * Turns {@code CRONBELL_DATABASE_URL} into {@code spring.datasource.*}. Accepts
 * {@code postgres://user:password@host:port/db?params} (the usual shape from hosting providers)
 * and plain {@code jdbc:} URLs. Explicit {@code spring.datasource.*} settings still win.
 *
 * <p>Without it the server uses SQLite under {@code CRONBELL_DATA_DIR} (see application.properties).
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String PROPERTY = "CRONBELL_DATABASE_URL";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String url = environment.getProperty(PROPERTY);
		if (!StringUtils.hasText(url)) {
			return;
		}
		MapPropertySource source = new MapPropertySource("cronbellDatabaseUrl", toDataSourceProperties(url.trim()));
		var sources = environment.getPropertySources();
		if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
		}
		else {
			sources.addFirst(source);
		}
	}

	static Map<String, Object> toDataSourceProperties(String url) {
		Map<String, Object> properties = new LinkedHashMap<>();
		if (url.startsWith("jdbc:")) {
			properties.put("spring.datasource.url", url);
			return properties;
		}
		URI uri = URI.create(url);
		String scheme = uri.getScheme();
		if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
			throw new IllegalArgumentException(
					PROPERTY + " must be a postgres://, postgresql:// or jdbc: URL, got scheme '" + scheme + "'");
		}
		StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
		if (uri.getPort() != -1) {
			jdbc.append(':').append(uri.getPort());
		}
		jdbc.append(StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "/");
		if (uri.getRawQuery() != null) {
			jdbc.append('?').append(uri.getRawQuery());
		}
		properties.put("spring.datasource.url", jdbc.toString());
		String userInfo = uri.getRawUserInfo();
		if (userInfo != null) {
			int colon = userInfo.indexOf(':');
			String user = (colon >= 0) ? userInfo.substring(0, colon) : userInfo;
			properties.put("spring.datasource.username", URLDecoder.decode(user, StandardCharsets.UTF_8));
			if (colon >= 0) {
				properties.put("spring.datasource.password",
						URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8));
			}
		}
		return properties;
	}

}
