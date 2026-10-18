package com.steelreed.cronbell.server.alerts.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * POSTs JSON for the HTTP channels with short timeouts, so a stuck receiver cannot hold
 * up alerts. Redirects are not followed. Error messages never contain the URL.
 */
@Component
class HttpPoster {

	private static final int MAX_ERROR_BODY = 200;

	/** What came back. {@code body} is at most a few KB, for error messages. */
	record Response(int status, String body, String retryAfter) {

		boolean successful() {
			return this.status >= 200 && this.status < 300;
		}

	}

	private final RestClient client;

	private final NotifierProperties properties;

	HttpPoster(NotifierProperties properties) {
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(properties.readTimeout());
		this.client = RestClient.builder()
			.requestFactory(factory)
			.defaultHeader(HttpHeaders.USER_AGENT, "Cronbell")
			.build();
		this.properties = properties;
	}

	/**
	 * Sends {@code body} and returns the response, whatever its status.
	 * @throws DeliveryException (retryable) when no response arrived
	 */
	Response post(URI uri, Map<String, String> headers, byte[] body) throws DeliveryException {
		try {
			return this.client.post()
				.uri(uri)
				.headers((h) -> headers.forEach(h::set))
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange((request, response) -> new Response(response.getStatusCode().value(),
						read(response.getBody()), response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
		}
		catch (RestClientException ex) {
			// The exception's own message holds the URL; its causes do not.
			int port = (uri.getPort() != -1) ? uri.getPort() : ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443);
			throw DeliveryException.retryable(DeliveryErrors.network(ex.getCause(), uri.getHost(), port,
					this.properties.connectTimeout(), this.properties.readTimeout()));
		}
	}

	/**
	 * Turns a non-2xx response into a delivery error that names the receiver ("HTTP 401
	 * from Telegram: check the bot token"): 408, 429 and 5xx are retried, other statuses
	 * are permanent.
	 */
	static DeliveryException failure(Response response, String receiver, String detail, Duration retryAfter) {
		int status = response.status();
		String message = "HTTP " + status + " from " + receiver
				+ ((detail != null && !detail.isBlank()) ? ": " + oneLine(detail) : "");
		boolean retryable = status == 408 || status == 429 || status >= 500;
		Duration wait = (retryAfter != null) ? retryAfter : parseRetryAfter(response.retryAfter());
		return new DeliveryException(message, retryable, retryable ? wait : null);
	}

	static Duration parseRetryAfter(String header) {
		if (header == null) {
			return null;
		}
		try {
			long seconds = Long.parseLong(header.trim());
			return (seconds >= 0) ? Duration.ofSeconds(seconds) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	static String oneLine(String text) {
		String line = text.replaceAll("\\s+", " ").strip();
		return (line.length() > MAX_ERROR_BODY) ? line.substring(0, MAX_ERROR_BODY) + "…" : line;
	}

	private static String read(InputStream body) throws IOException {
		if (body == null) {
			return "";
		}
		return new String(body.readNBytes(4096), StandardCharsets.UTF_8);
	}

}
