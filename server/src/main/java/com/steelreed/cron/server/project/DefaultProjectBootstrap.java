package com.steelreed.cron.server.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * On first start, when there is no project, creates a "Default" project with one API key.
 *
 * <p>The key comes from {@code CRON_API_KEY} when set; otherwise one is generated and printed to
 * the log once. Only its hash is stored, so it cannot be shown again.
 */
@Component
class DefaultProjectBootstrap implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(DefaultProjectBootstrap.class);

	private final ProjectService projects;

	private final String presetKey;

	DefaultProjectBootstrap(ProjectService projects, @Value("${cron.bootstrap.api-key:}") String presetKey) {
		this.projects = projects;
		this.presetKey = presetKey;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (this.projects.anyProjectExists()) {
			return;
		}
		String projectId = this.projects.createProject("Default");
		if (StringUtils.hasText(this.presetKey)) {
			this.projects.addApiKey(projectId, "bootstrap", this.presetKey.trim());
			logger.info("Created the Default project with the API key from CRON_API_KEY");
			return;
		}
		String key = this.projects.createApiKey(projectId, "bootstrap");
		logger.warn("""

				Created the Default project. Its API key is shown only this once, store it now:

				    {}

				Use it as "Authorization: Bearer <key>" on /api/v1.
				""", key);
	}

}
