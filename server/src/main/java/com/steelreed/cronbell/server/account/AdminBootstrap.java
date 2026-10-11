package com.steelreed.cronbell.server.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates the admin account from {@code CRONBELL_ADMIN_EMAIL} and
 * {@code CRONBELL_ADMIN_PASSWORD} on start, when no account exists yet. Without them the
 * dashboard shows the one-time setup page. Runs after the Default project is made.
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(AdminBootstrap.class);

	private final AccountService accounts;

	private final String email;

	private final String password;

	public AdminBootstrap(AccountService accounts, @Value("${cronbell.bootstrap.admin-email:}") String email,
			@Value("${cronbell.bootstrap.admin-password:}") String password) {
		this.accounts = accounts;
		this.email = email;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		boolean hasEmail = StringUtils.hasText(this.email);
		boolean hasPassword = StringUtils.hasText(this.password);
		if (this.accounts.anyAccountExists()) {
			return;
		}
		if (hasEmail != hasPassword) {
			throw new IllegalStateException("Set both CRONBELL_ADMIN_EMAIL and CRONBELL_ADMIN_PASSWORD, or neither");
		}
		if (!hasEmail) {
			logger.info("No account yet: open the dashboard to create the admin account");
			return;
		}
		try {
			this.accounts.createFirstAccount(this.email, this.password);
			logger.info("Created the admin account from CRONBELL_ADMIN_EMAIL");
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(
					"Invalid CRONBELL_ADMIN_EMAIL or CRONBELL_ADMIN_PASSWORD: " + ex.getMessage());
		}
		catch (SetupAlreadyDoneException ex) {
			// Another instance created it first.
		}
	}

}
