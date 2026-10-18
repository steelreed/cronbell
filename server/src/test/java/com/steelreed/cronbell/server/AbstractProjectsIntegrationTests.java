package com.steelreed.cronbell.server;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.steelreed.cronbell.server.accounts.internal.AccountService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * Accounts, projects, invites and tenant isolation (DESIGN.md §6, Roles; Projects and
 * invites), over HTTP. Kept apart from {@link AbstractIntegrationTests} for readability;
 * the SQLite and PostgreSQL classes extend this one, so both sets share one application
 * context per database.
 *
 * <p>
 * Every browser comes from its own client address ({@code X-Forwarded-For}, trusted in
 * the tests), so the sign-in and invite rate limits of one test never affect another.
 */
@SuppressWarnings("unchecked")
abstract class AbstractProjectsIntegrationTests extends AbstractIntegrationTests {

	static final String PASSWORD = "tenant password 1";

	/** Lets a test run a concurrent request at an exact point of another one. */
	@MockitoSpyBean
	AccountService accountsSpy;

	// --- Invites ---

	@Test
	void projectInviteGivesANewAccountAnIsolatedProjectOfItsOwn() {
		Browser admin = admin();
		HttpResponse<String> created = admin.call("POST", "/api/v1/admin/invites", "{\"note\":\"Beta tester Lan\"}");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		String url = json(created, "$.url", String.class);
		assertThat(url).startsWith("https://cronbell.example.com/invite/");
		String token = url.substring(url.lastIndexOf('/') + 1);
		assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
		assertThat(json(created, "$.kind", String.class)).isEqualTo("PROJECT");
		assertThat(json(created, "$.status", String.class)).isEqualTo("PENDING");
		assertThat(json(created, "$.expiresAt", String.class)).isEqualTo("2026-01-12T10:00:00Z");
		String id = json(created, "$.id", String.class);
		// Only the hash is stored.
		assertThat(this.jdbc.sql("SELECT token_hash FROM invites WHERE id = ?").param(id).query(String.class).single())
			.hasSize(64)
			.isNotEqualTo(token);
		HttpResponse<String> list = admin.get("/api/v1/admin/invites");
		assertThat(list.body()).doesNotContain(token);
		assertThat((List<String>) JsonPath.read(list.body(), "$[?(@.id == '" + id + "')].note"))
			.containsExactly("Beta tester Lan");

		Browser visitor = browser();
		HttpResponse<String> offer = visitor.get("/api/v1/invites/" + token);
		assertThat(offer.statusCode()).isEqualTo(200);
		assertThat(json(offer, "$.kind", String.class)).isEqualTo("PROJECT");
		assertThat(json(offer, "$.invitedBy", String.class)).isEqualTo(ADMIN_EMAIL);
		assertThat((Object) JsonPath.read(offer.body(), "$.projectName")).isNull();

		String email = uniqueEmail();
		HttpResponse<String> accepted = visitor.call("POST", "/api/v1/invites/" + token + "/accept",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"projectName\":\"Lan's jobs\"}");
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
		assertThat(json(accepted, "$.account.email", String.class)).isEqualTo(email);
		assertThat(json(accepted, "$.account.instanceAdmin", Boolean.class)).isFalse();
		assertThat(json(accepted, "$.project.name", String.class)).isEqualTo("Lan's jobs");
		assertThat(json(accepted, "$.project.role", String.class)).isEqualTo("OWNER");
		assertThat((List<Object>) JsonPath.read(accepted.body(), "$.projects")).hasSize(1);
		assertThat(Browser.setCookie(accepted, "cronbell_session")).contains("HttpOnly");
		// The new project starts empty: no checks, channels or keys.
		assertThat(visitor.get("/api/v1/checks").body()).isEqualTo("[]");
		assertThat(visitor.get("/api/v1/channels").body()).isEqualTo("[]");
		assertThat(visitor.get("/api/v1/keys").body()).isEqualTo("[]");
		assertThat(visitor.get("/api/v1/admin/invites").statusCode()).isEqualTo(403);

		// Used once.
		assertThat(browser().get("/api/v1/invites/" + token).statusCode()).isEqualTo(404);
		assertThat(browser().call("POST", "/api/v1/invites/" + token + "/accept", credentials(uniqueEmail(), PASSWORD))
			.statusCode()).isEqualTo(404);
		String after = admin.get("/api/v1/admin/invites").body();
		assertThat((List<String>) JsonPath.read(after, "$[?(@.id == '" + id + "')].status"))
			.containsExactly("ACCEPTED");
		assertThat((List<String>) JsonPath.read(after, "$[?(@.id == '" + id + "')].acceptedBy")).containsExactly(email);
		assertThat((List<String>) JsonPath.read(after, "$[?(@.id == '" + id + "')].projectName"))
			.containsExactly("Lan's jobs");
		// The admin does not get into the new project.
		assertThat((List<String>) JsonPath.read(admin.get("/api/v1/projects").body(), "$[*].name"))
			.doesNotContain("Lan's jobs");
	}

	@Test
	void projectNameDefaultsToTheEmailsLocalPart() {
		String token = projectInvite(admin());
		Browser visitor = browser();
		String email = "lan." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
		HttpResponse<String> accepted = visitor.call("POST", "/api/v1/invites/" + token + "/accept",
				credentials(email, PASSWORD));
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
		assertThat(json(accepted, "$.project.name", String.class))
			.isEqualTo(email.substring(0, email.indexOf('@')) + "'s project");
	}

	@Test
	void invalidAcceptsCreateNothingAndKeepTheLink() {
		String token = projectInvite(admin());
		Browser visitor = browser();
		String email = uniqueEmail();
		assertThat(visitor.send("POST", "/api/v1/invites/" + token + "/accept", credentials(email, PASSWORD), false)
			.statusCode()).as("CSRF header needed").isEqualTo(403);
		assertThat(
				visitor.call("POST", "/api/v1/invites/" + token + "/accept", credentials(email, "short")).statusCode())
			.isEqualTo(400);
		assertThat(visitor.call("POST", "/api/v1/invites/" + token + "/accept", credentials("nope", PASSWORD))
			.statusCode()).isEqualTo(400);
		HttpResponse<String> taken = visitor.call("POST", "/api/v1/invites/" + token + "/accept",
				credentials(ADMIN_EMAIL, PASSWORD));
		assertThat(taken.statusCode()).isEqualTo(409);
		assertThat(json(taken, "$.detail", String.class)).contains("sign in first");
		assertThat(
				this.jdbc.sql("SELECT COUNT(*) FROM accounts WHERE email = ?").param(email).query(Long.class).single())
			.isZero();
		assertThat(visitor.get("/api/v1/invites/" + token).statusCode()).isEqualTo(200);
	}

	@Test
	void memberInviteAddsAMemberWithLimitedRights() {
		Browser owner = tenant();
		String ownerId = json(owner.get("/api/v1/session"), "$.account.id", String.class);
		String projectId = json(owner.get("/api/v1/session"), "$.project.id", String.class);
		String projectName = json(owner.get("/api/v1/session"), "$.project.name", String.class);
		HttpResponse<String> invite = owner.call("POST", "/api/v1/invites", "{\"note\":\"Minh\"}");
		assertThat(invite.statusCode()).as(invite.body()).isEqualTo(201);
		assertThat(json(invite, "$.kind", String.class)).isEqualTo("MEMBER");
		String token = tokenOf(invite);
		HttpResponse<String> offer = browser().get("/api/v1/invites/" + token);
		assertThat(json(offer, "$.kind", String.class)).isEqualTo("MEMBER");
		assertThat(json(offer, "$.projectName", String.class)).isEqualTo(projectName);

		Browser member = browser();
		String memberEmail = uniqueEmail();
		HttpResponse<String> joined = member.call("POST", "/api/v1/invites/" + token + "/accept",
				credentials(memberEmail, PASSWORD));
		assertThat(joined.statusCode()).as(joined.body()).isEqualTo(201);
		assertThat(json(joined, "$.project.id", String.class)).isEqualTo(projectId);
		assertThat(json(joined, "$.project.role", String.class)).isEqualTo("MEMBER");
		String memberId = json(joined, "$.account.id", String.class);

		// Members manage checks and channels.
		String slug = uniqueSlug();
		assertThat(member.call("PUT", "/api/v1/checks/" + slug, "{\"cron\":\"0 * * * *\"}").statusCode())
			.isEqualTo(201);
		assertThat((List<String>) JsonPath.read(owner.get("/api/v1/checks").body(), "$[*].slug")).contains(slug);
		assertThat(member
			.call("POST", "/api/v1/channels",
					"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url("/hook/member") + "\"}}")
			.statusCode()).isEqualTo(201);
		// They see the keys but cannot make or revoke them, nor manage invites.
		String keyId = json(owner.call("POST", "/api/v1/keys", "{\"name\":\"ci\"}"), "$.id", String.class);
		assertThat(member.get("/api/v1/keys").statusCode()).isEqualTo(200);
		assertThat(member.call("POST", "/api/v1/keys", "{}").statusCode()).isEqualTo(403);
		assertThat(member.call("DELETE", "/api/v1/keys/" + keyId, null).statusCode()).isEqualTo(403);
		assertThat(member.get("/api/v1/invites").statusCode()).isEqualTo(403);
		assertThat(member.call("POST", "/api/v1/invites", "{}").statusCode()).isEqualTo(403);
		// Members see each other; only owners remove others.
		HttpResponse<String> members = member.get("/api/v1/members");
		assertThat((List<String>) JsonPath.read(members.body(), "$[*].email")).hasSize(2).contains(memberEmail);
		assertThat((List<String>) JsonPath.read(members.body(), "$[*].role")).containsExactly("OWNER", "MEMBER");
		assertThat((List<String>) JsonPath.read(members.body(), "$[?(@.you == true)].email"))
			.containsExactly(memberEmail);
		assertThat(member.call("DELETE", "/api/v1/members/" + ownerId, null).statusCode()).isEqualTo(403);
		// The last owner cannot leave.
		HttpResponse<String> lastOwner = owner.call("DELETE", "/api/v1/members/" + ownerId, null);
		assertThat(lastOwner.statusCode()).isEqualTo(409);
		assertThat(json(lastOwner, "$.detail", String.class)).contains("last owner");

		// The owner removes the member, who loses access at once.
		assertThat(owner.call("DELETE", "/api/v1/members/" + memberId, null).statusCode()).isEqualTo(204);
		assertThat(member.get("/api/v1/checks").statusCode()).isEqualTo(403);
		HttpResponse<String> session = member.get("/api/v1/session");
		assertThat(session.statusCode()).isEqualTo(200);
		assertThat((Object) JsonPath.read(session.body(), "$.project")).isNull();
		assertThat((List<Object>) JsonPath.read(session.body(), "$.projects")).isEmpty();
		assertThat(owner.call("DELETE", "/api/v1/members/" + memberId, null).statusCode()).isEqualTo(404);
		// The account stays and can sign in, to no project.
		HttpResponse<String> signIn = signIn(memberEmail, PASSWORD);
		assertThat(signIn.statusCode()).isEqualTo(200);
		assertThat((Object) JsonPath.read(signIn.body(), "$.project")).isNull();
	}

	@Test
	void aMemberCanLeave() {
		Browser owner = tenant();
		Browser member = browser();
		HttpResponse<String> joined = member.call("POST",
				"/api/v1/invites/" + tokenOf(owner.call("POST", "/api/v1/invites", null)) + "/accept",
				credentials(uniqueEmail(), PASSWORD));
		String memberId = json(joined, "$.account.id", String.class);
		assertThat(member.call("DELETE", "/api/v1/members/" + memberId, null).statusCode()).isEqualTo(204);
		assertThat(member.get("/api/v1/checks").statusCode()).isEqualTo(403);
		assertThat((List<Object>) JsonPath.read(owner.get("/api/v1/members").body(), "$")).hasSize(1);
	}

	@Test
	void aSignedInAccountAcceptsWithItsOwnAccountAndSwitchesProjects() {
		Browser owner = tenant();
		String ownersProject = json(owner.get("/api/v1/session"), "$.project.id", String.class);
		Browser other = tenant();
		String othersProject = json(other.get("/api/v1/session"), "$.project.id", String.class);
		String token = tokenOf(owner.call("POST", "/api/v1/invites", null));
		// Memberships are ordered by when they were made.
		CLOCK.advance(Duration.ofSeconds(1));

		HttpResponse<String> accepted = other.call("POST", "/api/v1/invites/" + token + "/accept", "{}");
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
		assertThat(json(accepted, "$.project.id", String.class)).isEqualTo(ownersProject);
		assertThat(json(accepted, "$.project.role", String.class)).isEqualTo("MEMBER");
		assertThat((List<String>) JsonPath.read(accepted.body(), "$.projects[*].id")).containsExactly(othersProject,
				ownersProject);
		assertThat((List<String>) JsonPath.read(other.get("/api/v1/projects").body(), "$[*].role"))
			.containsExactly("OWNER", "MEMBER");
		// Working in the owner's project now.
		String slug = uniqueSlug();
		assertThat(other.call("PUT", "/api/v1/checks/" + slug, "{\"cron\":\"0 * * * *\"}").statusCode()).isEqualTo(201);
		assertThat(owner.get("/api/v1/checks/" + slug).statusCode()).isEqualTo(200);
		// Switching back: the other project's checks, the owner role.
		HttpResponse<String> switched = other.call("POST", "/api/v1/session/project",
				"{\"projectId\":\"" + othersProject + "\"}");
		assertThat(switched.statusCode()).as(switched.body()).isEqualTo(200);
		assertThat(json(switched, "$.project.role", String.class)).isEqualTo("OWNER");
		assertThat(other.get("/api/v1/checks/" + slug).statusCode()).isEqualTo(404);
		assertThat(other.get("/api/v1/session").body()).contains(othersProject);
		// A second member invite to a project it is in already is refused and stays
		// usable.
		String again = tokenOf(owner.call("POST", "/api/v1/invites", null));
		assertThat(other.call("POST", "/api/v1/invites/" + again + "/accept", "{}").statusCode()).isEqualTo(409);
		assertThat(browser().get("/api/v1/invites/" + again).statusCode()).isEqualTo(200);
		// A project invite gives a signed-in account one more project.
		String project = projectInvite(admin());
		HttpResponse<String> third = other.call("POST", "/api/v1/invites/" + project + "/accept",
				"{\"projectName\":\"Side jobs\"}");
		assertThat(third.statusCode()).isEqualTo(200);
		assertThat(json(third, "$.project.name", String.class)).isEqualTo("Side jobs");
		assertThat((List<Object>) JsonPath.read(third.body(), "$.projects")).hasSize(3);
	}

	@Test
	void invitesExpireAndCanBeDeleted() {
		Browser admin = admin();
		String expiring = projectInvite(admin);
		CLOCK.advance(Duration.ofDays(7));
		assertThat(browser().get("/api/v1/invites/" + expiring).statusCode()).isEqualTo(404);
		assertThat(
				browser().call("POST", "/api/v1/invites/" + expiring + "/accept", credentials(uniqueEmail(), PASSWORD))
					.statusCode())
			.isEqualTo(404);
		assertThat(admin.get("/api/v1/admin/invites").body()).contains("\"EXPIRED\"");

		HttpResponse<String> created = admin.call("POST", "/api/v1/admin/invites", null);
		String token = tokenOf(created);
		String id = json(created, "$.id", String.class);
		assertThat(admin.call("DELETE", "/api/v1/admin/invites/" + id, null).statusCode()).isEqualTo(204);
		assertThat(browser().get("/api/v1/invites/" + token).statusCode()).isEqualTo(404);
		assertThat(admin.call("DELETE", "/api/v1/admin/invites/" + id, null).statusCode()).isEqualTo(404);

		// Member and project invites are apart: neither path deletes the other kind.
		HttpResponse<String> member = admin.call("POST", "/api/v1/invites", null);
		String memberId = json(member, "$.id", String.class);
		assertThat(admin.call("DELETE", "/api/v1/admin/invites/" + memberId, null).statusCode()).isEqualTo(404);
		HttpResponse<String> project = admin.call("POST", "/api/v1/admin/invites", null);
		assertThat(admin.call("DELETE", "/api/v1/invites/" + json(project, "$.id", String.class), null).statusCode())
			.isEqualTo(404);
		assertThat(admin.call("DELETE", "/api/v1/invites/" + memberId, null).statusCode()).isEqualTo(204);
		assertThat(admin.call("POST", "/api/v1/invites", "{\"note\":\"" + "x".repeat(201) + "\"}").statusCode())
			.isEqualTo(400);
	}

	@Test
	void onlyTheInstanceAdminManagesProjectInvites() {
		Browser tenant = tenant();
		assertThat(tenant.get("/api/v1/admin/invites").statusCode()).isEqualTo(403);
		assertThat(tenant.call("POST", "/api/v1/admin/invites", null).statusCode()).isEqualTo(403);
		assertThat(browser().get("/api/v1/admin/invites").statusCode()).isEqualTo(401);
		assertThat(exchange(HttpMethod.GET, "/api/v1/admin/invites", null, API_KEY).getStatusCode().value())
			.isEqualTo(403);
		assertThat(exchange(HttpMethod.POST, "/api/v1/invites", null, API_KEY).getStatusCode().value()).isEqualTo(403);
		assertThat(exchange(HttpMethod.GET, "/api/v1/members", null, API_KEY).getStatusCode().value()).isEqualTo(403);
		HttpResponse<String> session = admin().get("/api/v1/session");
		assertThat(json(session, "$.account.instanceAdmin", Boolean.class)).isTrue();
		assertThat(json(session, "$.project.role", String.class)).isEqualTo("OWNER");
	}

	@Test
	void concurrentAcceptsOfOneInviteHaveOneWinner() throws Exception {
		String token = projectInvite(admin());
		List<Browser> browsers = new ArrayList<>();
		List<String> emails = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			browsers.add(browser());
			emails.add(uniqueEmail());
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
			List<Future<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Browser browser = browsers.get(i);
				String email = emails.get(i);
				calls.add(executor.submit(
						() -> browser.call("POST", "/api/v1/invites/" + token + "/accept", credentials(email, PASSWORD))
							.statusCode()));
			}
			for (Future<Integer> call : calls) {
				statuses.add(call.get());
			}
		}
		assertThat(statuses).containsOnly(201, 404).filteredOn((status) -> status == 201).hasSize(1);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM accounts WHERE email IN (:emails)")
			.param("emails", emails)
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void unknownInviteLinksAreRateLimitedPerAddress() {
		Browser prober = browser();
		for (int i = 0; i < 5; i++) {
			assertThat(prober.get("/api/v1/invites/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
		}
		HttpResponse<String> blocked = prober.get("/api/v1/invites/" + UUID.randomUUID());
		assertThat(blocked.statusCode()).isEqualTo(429);
		assertThat(blocked.headers().firstValue("Retry-After")).hasValue("30");
		assertThat(prober.call("POST", "/api/v1/invites/x/accept", credentials(uniqueEmail(), PASSWORD)).statusCode())
			.isEqualTo(429);
		// Another address is not affected.
		String token = projectInvite(admin());
		assertThat(browser().get("/api/v1/invites/" + token).statusCode()).isEqualTo(200);
	}

	// --- Accounts ---

	@Test
	void changingThePasswordEndsTheOtherSessions() {
		String email = uniqueEmail();
		Browser first = tenant(email);
		Browser second = signedInAs(email, PASSWORD);
		HttpResponse<String> wrong = first.call("POST", "/api/v1/account/password",
				"{\"currentPassword\":\"not it at all\",\"newPassword\":\"a new password\"}");
		assertThat(wrong.statusCode()).isEqualTo(400);
		assertThat(first
			.call("POST", "/api/v1/account/password",
					"{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}")
			.statusCode()).isEqualTo(400);
		String sessionBefore = first.cookie("cronbell_session");
		HttpResponse<String> changed = first.call("POST", "/api/v1/account/password",
				"{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a new password\"}");
		assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
		assertThat(first.cookie("cronbell_session")).isNotEqualTo(sessionBefore);
		assertThat(first.get("/api/v1/session").statusCode()).isEqualTo(200);
		assertThat(first.get("/api/v1/checks").statusCode()).isEqualTo(200);
		assertThat(second.get("/api/v1/checks").statusCode()).isEqualTo(401);
		assertThat(second.get("/api/v1/session").statusCode()).isEqualTo(401);
		assertThat(signIn(email, PASSWORD).statusCode()).isEqualTo(401);
		assertThat(signIn(email, "a new password").statusCode()).isEqualTo(200);
		assertThat(exchange(HttpMethod.POST, "/api/v1/account/password", "{}", API_KEY).getStatusCode().value())
			.isEqualTo(403);
	}

	@Test
	void ofTwoConcurrentPasswordChangesTheSecondIsRefused() {
		String email = uniqueEmail();
		Browser first = tenant(email);
		Browser second = signedInAs(email, PASSWORD);
		String accountId = json(first.get("/api/v1/session"), "$.account.id", String.class);
		AtomicBoolean interleaved = new AtomicBoolean();
		AtomicInteger firstStatus = new AtomicInteger();
		// The second change checks the old password, then the first one completes before
		// the second stores its own.
		doAnswer((call) -> {
			Object checked = call.callRealMethod();
			if (interleaved.compareAndSet(false, true)) {
				firstStatus
					.set(first.call("POST", "/api/v1/account/password", passwordChange(PASSWORD, "first new one"))
						.statusCode());
			}
			return checked;
		}).when(this.accountsSpy).authenticateById(accountId, PASSWORD);

		HttpResponse<String> late = second.call("POST", "/api/v1/account/password",
				passwordChange(PASSWORD, "second new one"));
		assertThat(firstStatus.get()).isEqualTo(204);
		assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
		assertThat(signIn(email, "first new one").statusCode()).isEqualTo(200);
		assertThat(signIn(email, "second new one").statusCode()).isEqualTo(401);
		assertThat(first.get("/api/v1/session").statusCode()).isEqualTo(200);
		assertThat(second.get("/api/v1/session").statusCode()).isEqualTo(401);
	}

	@Test
	void aPasswordChangeDuringASignedInAcceptStillEndsThatSession() {
		String email = uniqueEmail();
		Browser accepting = tenant(email);
		Browser changing = signedInAs(email, PASSWORD);
		String accountId = json(accepting.get("/api/v1/session"), "$.account.id", String.class);
		String token = projectInvite(admin());
		AtomicBoolean interleaved = new AtomicBoolean();
		AtomicInteger changeStatus = new AtomicInteger();
		// The password changes after the accept passed the session check, before it signs
		// the session in again.
		doAnswer((call) -> {
			if (interleaved.compareAndSet(false, true)) {
				changeStatus
					.set(changing.call("POST", "/api/v1/account/password", passwordChange(PASSWORD, "a new password"))
						.statusCode());
			}
			return call.callRealMethod();
		}).when(this.accountsSpy).findById(accountId);

		HttpResponse<String> accepted = accepting.call("POST", "/api/v1/invites/" + token + "/accept", "{}");
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
		assertThat(changeStatus.get()).isEqualTo(204);
		assertThat(accepting.get("/api/v1/session").statusCode()).isEqualTo(401);
		assertThat(changing.get("/api/v1/session").statusCode()).isEqualTo(200);
	}

	// --- Tenant isolation ---

	/**
	 * Tenant B, by session and by its API key, can neither see nor touch anything of
	 * tenant A, by slug or by id.
	 */
	@Test
	void projectsAreIsolatedFromEachOther() {
		Browser a = tenant();
		String aAccount = json(a.get("/api/v1/session"), "$.account.id", String.class);
		String aProject = json(a.get("/api/v1/session"), "$.project.id", String.class);
		String slug = uniqueSlug();
		HttpResponse<String> check = a.call("PUT", "/api/v1/checks/" + slug, "{\"cron\":\"0 * * * *\"}");
		String pingKey = json(check, "$.pingKey", String.class);
		get("/ping/" + pingKey);
		String channel = json(
				a.call("POST", "/api/v1/channels",
						"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url("/hook/tenant-a") + "\"}}"),
				"$.id", String.class);
		HttpResponse<String> aKey = a.call("POST", "/api/v1/keys", "{\"name\":\"a\"}");
		String aKeyId = json(aKey, "$.id", String.class);
		String aInvite = json(a.call("POST", "/api/v1/invites", "{\"note\":\"a\"}"), "$.id", String.class);

		Browser b = tenant();
		String bKey = json(b.call("POST", "/api/v1/keys", "{\"name\":\"b\"}"), "$.key", String.class);
		String body = "{\"cron\":\"0 * * * *\"}";
		String channelBody = "{\"name\":\"x\",\"config\":{\"url\":\"https://example.com/x\"}}";

		// By session.
		assertThat((List<String>) JsonPath.read(b.get("/api/v1/checks").body(), "$[*].slug")).doesNotContain(slug);
		assertThat(b.get("/api/v1/checks/" + slug).statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/checks/" + slug + "/pings").statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/checks/" + slug + "/events").statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/checks/" + slug + "/alerts").statusCode()).isEqualTo(404);
		assertThat(b.call("POST", "/api/v1/checks/" + slug + "/pause", null).statusCode()).isEqualTo(404);
		assertThat(b.call("POST", "/api/v1/checks/" + slug + "/resume", null).statusCode()).isEqualTo(404);
		assertThat(b.call("DELETE", "/api/v1/checks/" + slug, null).statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/runs/recent").body()).doesNotContain(slug);
		assertThat((List<Integer>) JsonPath.read(b.get("/api/v1/stats/daily?days=1").body(), "$[*].runs"))
			.containsExactly(0);
		assertThat(b.get("/api/v1/events").body()).doesNotContain(slug);
		assertThat(b.get("/api/v1/stats/status?days=1").body()).doesNotContain(slug);
		assertThat(b.get("/api/v1/channels").body()).doesNotContain(channel);
		assertThat(b.get("/api/v1/channels/" + channel).statusCode()).isEqualTo(404);
		assertThat(b.call("PUT", "/api/v1/channels/" + channel, channelBody).statusCode()).isEqualTo(404);
		assertThat(b.call("POST", "/api/v1/channels/" + channel + "/test", null).statusCode()).isEqualTo(404);
		assertThat(b.call("DELETE", "/api/v1/channels/" + channel, null).statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/keys").body()).doesNotContain(aKeyId);
		assertThat(b.call("DELETE", "/api/v1/keys/" + aKeyId, null).statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/members").body()).doesNotContain(aAccount);
		assertThat(b.call("DELETE", "/api/v1/members/" + aAccount, null).statusCode()).isEqualTo(404);
		assertThat(b.get("/api/v1/invites").body()).doesNotContain(aInvite);
		assertThat(b.call("DELETE", "/api/v1/invites/" + aInvite, null).statusCode()).isEqualTo(404);
		assertThat(b.call("POST", "/api/v1/session/project", "{\"projectId\":\"" + aProject + "\"}").statusCode())
			.isEqualTo(404);
		assertThat(
				b.call("POST", "/api/v1/session/project", "{\"projectId\":\"" + UUID.randomUUID() + "\"}").statusCode())
			.isEqualTo(404);
		assertThat(b.get("/api/v1/projects").body()).doesNotContain(aProject);

		// By B's API key.
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks", null, bKey).getBody()).doesNotContain(slug);
		assertThat(exchange(HttpMethod.GET, "/api/v1/events", null, bKey).getBody()).doesNotContain(slug);
		for (String path : List.of("/api/v1/checks/" + slug, "/api/v1/checks/" + slug + "/pings",
				"/api/v1/checks/" + slug + "/events", "/api/v1/checks/" + slug + "/alerts",
				"/api/v1/channels/" + channel)) {
			assertThat(exchange(HttpMethod.GET, path, null, bKey).getStatusCode().value()).as(path).isEqualTo(404);
		}
		assertThat(exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/pause", null, bKey).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/resume", null, bKey).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/checks/" + slug, null, bKey).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.PUT, "/api/v1/channels/" + channel, channelBody, bKey).getStatusCode().value())
			.isEqualTo(404);
		assertThat(
				exchange(HttpMethod.POST, "/api/v1/channels/" + channel + "/test", null, bKey).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/channels/" + channel, null, bKey).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.GET, "/api/v1/channels", null, bKey).getBody()).doesNotContain(channel);
		assertThat(exchange(HttpMethod.GET, "/api/v1/runs/recent", null, bKey).getBody()).doesNotContain(slug);
		for (String path : List.of("/api/v1/keys", "/api/v1/members", "/api/v1/invites", "/api/v1/projects")) {
			assertThat(exchange(HttpMethod.GET, path, null, bKey).getStatusCode().value()).as(path).isEqualTo(403);
		}
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/keys/" + aKeyId, null, bKey).getStatusCode().value())
			.isEqualTo(403);

		// The same slug in B's project is a different check.
		ResponseEntity<String> own = exchange(HttpMethod.PUT, "/api/v1/checks/" + slug, body, bKey);
		assertThat(own.getStatusCode().value()).isEqualTo(201);
		assertThat((String) JsonPath.read(own.getBody(), "$.pingKey")).isNotEqualTo(pingKey);

		// A's things are untouched.
		HttpResponse<String> aCheck = a.get("/api/v1/checks/" + slug);
		assertThat(json(aCheck, "$.status", String.class)).isEqualTo("UP");
		assertThat(json(aCheck, "$.pingKey", String.class)).isEqualTo(pingKey);
		assertThat(a.get("/api/v1/channels/" + channel).statusCode()).isEqualTo(200);
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks/" + slug, null, json(aKey, "$.key", String.class))
			.getStatusCode()
			.value()).isEqualTo(200);
		assertThat(a.get("/api/v1/invites").body()).contains(aInvite);
		assertThat(a.get("/api/v1/stats/daily?days=1").body()).contains("\"runs\":1");
		assertThat(a.get("/api/v1/events").body()).contains(slug);
		assertThat(a.get("/api/v1/stats/status?days=1").body()).contains(slug);
	}

	// --- Upgrade ---

	/**
	 * The V5 migration on a database of 0.1.0-beta.1 (one admin, the Default project,
	 * checks, a channel, keys): the setup account becomes the instance admin. V6 starts
	 * the status history of the checks that are DOWN or paused. V7 rebuilds the channels
	 * and deliveries tables and keeps their rows, with the status event V6 gave each
	 * delivery.
	 */
	void upgradeFromBeta1MakesTheSetupAccountInstanceAdmin(Flyway beta1, Flyway history, Flyway latest,
			JdbcClient jdbc) {
		beta1.migrate();
		jdbc.sql("INSERT INTO projects (id, name, created_at) VALUES ('p1', 'Default', 1)").update();
		jdbc.sql("""
				INSERT INTO accounts (id, email, password_hash, setup_slot, created_at, updated_at)
				VALUES ('a1', 'admin@example.com', '{noop}x', 1, 1, 1)""").update();
		jdbc.sql(
				"INSERT INTO project_members (project_id, account_id, role, created_at) VALUES ('p1', 'a1', 'OWNER', 1)")
			.update();
		jdbc.sql("""
				INSERT INTO api_keys (id, project_id, name, key_hash, key_prefix, created_at)
				VALUES ('k1', 'p1', 'bootstrap', 'hash', 'cb_abcde', 1)""").update();
		jdbc.sql("""
				INSERT INTO checks (id, ping_key, project_id, slug, name, schedule_kind, cron_expression, grace_seconds,
				                    status, created_at, updated_at)
				VALUES ('c1', 'k-1', 'p1', 'backup', 'backup', 'CRON', '0 3 * * *', 3600, 'NEW', 1, 1)""").update();
		jdbc.sql("""
				INSERT INTO checks (id, ping_key, project_id, slug, name, schedule_kind, cron_expression, grace_seconds,
				                    status, down_reason, next_due_at, created_at, updated_at)
				VALUES ('c2', 'k-2', 'p1', 'report', 'report', 'CRON', '0 3 * * *', 600, 'DOWN', 'MISSED', 10800000,
				        1, 11400000)""").update();
		jdbc.sql("""
				INSERT INTO checks (id, ping_key, project_id, slug, name, schedule_kind, cron_expression, grace_seconds,
				                    status, created_at, updated_at)
				VALUES ('c3', 'k-3', 'p1', 'sync', 'sync', 'CRON', '0 3 * * *', 600, 'PAUSED', 1, 5000)""").update();
		jdbc.sql("""
				INSERT INTO channels (id, project_id, type, name, config, created_at, updated_at)
				VALUES ('ch1', 'p1', 'WEBHOOK', 'hook', '{}', 1, 1)""").update();
		jdbc.sql("""
				INSERT INTO deliveries (id, channel_id, check_id, event, payload, status, attempts, next_attempt_at,
				                        created_at)
				VALUES ('d1', 'ch1', 'c1', 'DOWN', '{}', 'PENDING', 0, 1, 1)""").update();
		history.migrate();
		jdbc.sql("UPDATE deliveries SET event_id = 'e1' WHERE id = 'd1'").update();
		latest.migrate();
		assertThat(jdbc.sql("SELECT channel_id, event_id FROM deliveries WHERE id = 'd1'")
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2)))
			.single()).containsExactly("ch1", "e1");
		jdbc.sql("""
				INSERT INTO channels (id, project_id, type, name, config, created_at, updated_at)
				VALUES ('ch2', 'p1', 'NTFY', 'ntfy', '{}', 1, 1)""").update();
		jdbc.sql("INSERT INTO check_channels (check_id, channel_id) VALUES ('c1', 'ch2')").update();
		assertThat(jdbc.sql("SELECT instance_admin FROM accounts WHERE id = 'a1'").query(Integer.class).single())
			.isEqualTo(1);
		assertThat(jdbc.sql("SELECT session_version FROM accounts WHERE id = 'a1'").query(Integer.class).single())
			.isZero();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM invites").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM checks WHERE project_id = 'p1'").query(Long.class).single())
			.isEqualTo(3);
		assertThat(jdbc.sql("SELECT check_id, kind, reason, run_at, at FROM status_events ORDER BY check_id")
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2), String.valueOf(rs.getString(3)),
					String.valueOf(rs.getObject(4)), rs.getLong(5)))
			.list()).containsExactly(List.of("c2", "DOWN", "MISSED", "10800000", 11400000L),
					List.of("c3", "PAUSED", "null", "null", 5000L));
	}

	// --- Helpers ---

	static String passwordChange(String current, String next) {
		return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
	}

	static String uniqueEmail() {
		return "t-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
	}

	/** A browser from an address of its own, with a CSRF cookie. */
	Browser browser() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Browser browser = new Browser(this.port).header("X-Forwarded-For",
				"10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255));
		browser.get("/api/v1/session");
		return browser;
	}

	Browser signedInAs(String email, String password) {
		Browser browser = browser();
		HttpResponse<String> response = browser.call("POST", "/api/v1/session", credentials(email, password));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return browser;
	}

	/** The instance admin, signed in. */
	Browser admin() {
		ensureAdmin();
		return signedInAs(ADMIN_EMAIL, ADMIN_PASSWORD);
	}

	/** A new project invite's token. */
	String projectInvite(Browser admin) {
		HttpResponse<String> created = admin.call("POST", "/api/v1/admin/invites", null);
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		return tokenOf(created);
	}

	static String tokenOf(HttpResponse<String> created) {
		String url = json(created, "$.url", String.class);
		return url.substring(url.lastIndexOf('/') + 1);
	}

	/** A new account with a project of its own, signed in. */
	Browser tenant() {
		return tenant(uniqueEmail());
	}

	Browser tenant(String email) {
		String token = projectInvite(admin());
		Browser browser = browser();
		HttpResponse<String> accepted = browser.call("POST", "/api/v1/invites/" + token + "/accept",
				credentials(email, PASSWORD));
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
		return browser;
	}

}
