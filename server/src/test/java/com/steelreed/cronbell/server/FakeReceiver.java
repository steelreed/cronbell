package com.steelreed.cronbell.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server standing in for Slack, Telegram and webhook receivers. Records
 * every request and answers with scripted responses per path (default 200).
 */
public final class FakeReceiver {

	/** A recorded request. Header names are lower case. */
	public record Request(String method, String path, Map<String, String> headers, String body) {

		public String header(String name) {
			return this.headers.get(name.toLowerCase(java.util.Locale.ROOT));
		}

	}

	/** A scripted answer. */
	public record Response(int status, String body, Map<String, String> headers, Duration delay) {

		public static Response status(int status) {
			return new Response(status, "", Map.of(), Duration.ZERO);
		}

		public static Response of(int status, String body) {
			return new Response(status, body, Map.of(), Duration.ZERO);
		}

	}

	private final HttpServer server;

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final Map<String, Deque<Response>> scripts = new ConcurrentHashMap<>();

	private final Map<String, Response> always = new ConcurrentHashMap<>();

	public FakeReceiver() {
		try {
			this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	public String url(String path) {
		return "http://localhost:" + this.server.getAddress().getPort() + path;
	}

	/** Answers the next requests to {@code path} with these responses, in order. */
	public void script(String path, Response... responses) {
		this.scripts.computeIfAbsent(path, (key) -> new ArrayDeque<>()).addAll(List.of(responses));
	}

	/** Answers every request to {@code path} (after any script) with this response. */
	public void always(String path, Response response) {
		this.always.put(path, response);
	}

	public List<Request> requests(String path) {
		return this.requests.stream().filter((request) -> request.path().equals(path)).toList();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String path = exchange.getRequestURI().getPath();
			Map<String, String> headers = new ConcurrentHashMap<>();
			exchange.getRequestHeaders()
				.forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst()));
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			this.requests.add(new Request(exchange.getRequestMethod(), path, Map.copyOf(headers), body));
			Response response = next(path);
			if (!response.delay().isZero()) {
				try {
					Thread.sleep(response.delay());
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
			byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), (bytes.length > 0) ? bytes.length : -1);
			if (bytes.length > 0) {
				exchange.getResponseBody().write(bytes);
			}
		}
		catch (IOException ex) {
			// The client gave up (timeout test).
		}
	}

	private synchronized Response next(String path) {
		Deque<Response> script = this.scripts.get(path);
		if (script != null && !script.isEmpty()) {
			return script.poll();
		}
		return this.always.getOrDefault(path, Response.status(200));
	}

}
