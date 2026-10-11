package com.steelreed.cronbell.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A minimal browser for session tests: keeps cookies (parsed by hand, so tests can see
 * their attributes) and sends the CSRF header like the dashboard does.
 */
final class Browser {

	static final String CSRF_COOKIE = "XSRF-TOKEN";

	static final String CSRF_HEADER = "X-XSRF-TOKEN";

	private final HttpClient client = HttpClient.newHttpClient();

	private final String baseUrl;

	private final Map<String, String> cookies = new LinkedHashMap<>();

	/** Extra headers sent with every request. */
	private final Map<String, String> headers = new LinkedHashMap<>();

	Browser(int port) {
		this.baseUrl = "http://localhost:" + port;
	}

	Browser header(String name, String value) {
		this.headers.put(name, value);
		return this;
	}

	String cookie(String name) {
		return this.cookies.get(name);
	}

	void setCookie(String name, String value) {
		this.cookies.put(name, value);
	}

	/** GET without CSRF. */
	HttpResponse<String> get(String path) {
		return send("GET", path, null, false);
	}

	/**
	 * An unsafe request with the CSRF header taken from the cookie, as the dashboard
	 * does.
	 */
	HttpResponse<String> call(String method, String path, String json) {
		return send(method, path, json, true);
	}

	HttpResponse<String> send(String method, String path, String json, boolean csrf) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + path))
			.method(method,
					(json != null) ? HttpRequest.BodyPublishers.ofString(json) : HttpRequest.BodyPublishers.noBody());
		if (json != null) {
			request.header("Content-Type", "application/json");
		}
		if (!this.cookies.isEmpty()) {
			request.header("Cookie",
					this.cookies.entrySet()
						.stream()
						.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
						.collect(Collectors.joining("; ")));
		}
		if (csrf && this.cookies.containsKey(CSRF_COOKIE)) {
			request.header(CSRF_HEADER, this.cookies.get(CSRF_COOKIE));
		}
		this.headers.forEach(request::header);
		try {
			HttpResponse<String> response = this.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			for (String setCookie : setCookies(response)) {
				String pair = setCookie.split(";", 2)[0];
				String name = pair.substring(0, pair.indexOf('='));
				String value = pair.substring(pair.indexOf('=') + 1);
				if (value.isEmpty() || setCookie.contains("Max-Age=0")) {
					this.cookies.remove(name);
				}
				else {
					this.cookies.put(name, value);
				}
			}
			return response;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	static List<String> setCookies(HttpResponse<?> response) {
		return response.headers().allValues("Set-Cookie");
	}

	/** The Set-Cookie header for this cookie in the response, if any. */
	static String setCookie(HttpResponse<?> response, String name) {
		return setCookies(response).stream().filter((c) -> c.startsWith(name + "=")).findFirst().orElse(null);
	}

}
