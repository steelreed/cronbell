package com.steelreed.cronbell.server;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import com.steelreed.cronbell.server.checks.CheckNotFoundException;
import com.steelreed.cronbell.server.checks.CheckService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * Code rules that module boundaries do not cover (those are checked by
 * {@link ModularityTests}). See ARCHITECTURE.md, "How the rules are enforced".
 */
@AnalyzeClasses(packages = "com.steelreed.cronbell.server", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

	private static final String CHECKS = "com.steelreed.cronbell.server.checks";

	private static final String CHECKS_DOMAIN = CHECKS + ".internal.domain..";

	/** The value types of the checks API: records, enums and the event. */
	private static final DescribedPredicate<JavaClass> CHECK_MODEL = resideInAPackage(CHECKS)
		.and(not(belongToAnyOf(CheckService.class, CheckNotFoundException.class)))
		.and(not(simpleName("package-info")))
		.as("the check model");

	/**
	 * The state machine and schedule evaluation are plain Java, so they can be tested and
	 * reasoned about without a container. Spring's cron parser is the one exception: the
	 * cron syntax is Spring's, and rewriting it is not worth the risk.
	 */
	@ArchTest
	static final ArchRule checkDomainDependsOnlyOnTheJdk = classes().that()
		.resideInAPackage(CHECKS_DOMAIN)
		.should()
		.onlyDependOnClassesThat(resideInAnyPackage("java..", CHECKS_DOMAIN).or(CHECK_MODEL)
			.or(belongToAnyOf(CronExpression.class))
			.as("the JDK, the check model or Spring's CronExpression"));

	@ArchTest
	static final ArchRule checkModelDependsOnlyOnTheJdk = classes().that(CHECK_MODEL)
		.should()
		.onlyDependOnClassesThat(resideInAPackage("java..").or(CHECK_MODEL).as("the JDK or the check model"));

	@ArchTest
	static final ArchRule controllersLiveInWebPackages = classes().that()
		.areMetaAnnotatedWith(Controller.class)
		.or()
		.areMetaAnnotatedWith(ControllerAdvice.class)
		.should()
		.resideInAPackage("..web..");

	@ArchTest
	static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

	@ArchTest
	static final ArchRule noLegacyDateTime = noClasses().should()
		.dependOnClassesThat()
		.belongToAnyOf(Date.class, Calendar.class, GregorianCalendar.class, java.sql.Date.class, java.sql.Time.class,
				java.sql.Timestamp.class)
		.because("time is java.time and stored as epoch milliseconds");

	@ArchTest
	static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

	@ArchTest
	static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

}
