package ru.yandex.qatools.allure.cucumberjvm;

import gherkin.formatter.model.Feature;
import gherkin.formatter.model.ScenarioOutline;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.ConfigurationException;

import org.apache.commons.lang3.reflect.FieldUtils;

import org.junit.Ignore;
import org.junit.internal.AssumptionViolatedException;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import ru.yandex.qatools.allure.Allure;
import ru.yandex.qatools.allure.annotations.Features;
import ru.yandex.qatools.allure.annotations.Stories;
import ru.yandex.qatools.allure.config.AllureModelUtils;
import ru.yandex.qatools.allure.events.TestCaseCanceledEvent;
import ru.yandex.qatools.allure.events.TestCaseFailureEvent;
import ru.yandex.qatools.allure.events.TestCaseFinishedEvent;
import ru.yandex.qatools.allure.events.TestCasePendingEvent;
import ru.yandex.qatools.allure.events.TestCaseStartedEvent;
import ru.yandex.qatools.allure.events.TestSuiteFinishedEvent;
import ru.yandex.qatools.allure.events.TestSuiteStartedEvent;
import ru.yandex.qatools.allure.utils.AnnotationManager;

/**
 * @author Viktor Sidochenko viktor.sidochenko@gmail.com
 */
public class AllureRunListener extends RunListener {

    private final Allure lifecycle = Allure.LIFECYCLE;

    private final Map<Object, String> suites = new HashMap<>();

    /**
     * All tests object
     */
    private Description parentDescription;

    public Allure getLifecycle() {
        return lifecycle;
    }

    @Override
    public void testRunStarted(Description description) {
        parentDescription = description;
    }

    @Override
    public void testStarted(Description description) throws IllegalAccessException, ConfigurationException {
        startTestCase(description);
    }

    @Override
    public void testFailure(Failure failure) {
        Throwable throwable = failure.getException();
        // Produces additional failure step for all test case
        if (throwable instanceof AssumptionViolatedException) {
            getLifecycle().fire(new TestCaseCanceledEvent().withThrowable(throwable));
        } else {
            getLifecycle().fire(new TestCaseFailureEvent().withThrowable(throwable));
        }

    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        testFailure(failure);
    }

    @Override
    public void testIgnored(Description description) throws IllegalAccessException, ConfigurationException {
        startTestCase(description);
        getLifecycle().fire(new TestCasePendingEvent().withMessage(getIgnoredMessage(description)));

        getLifecycle().fire(new TestCaseFinishedEvent());
    }

    @Override
    public void testFinished(Description description) throws IllegalAccessException, ConfigurationException {
        if (description.isSuite()) {
            getLifecycle().fire(new TestSuiteFinishedEvent(getSuiteUuid(description)));
        } else {
            getLifecycle().fire(new TestCaseFinishedEvent());
        }
    }

    /**
     * <p>
     * Start test case. If no test suite for it yet, create and start it
     * first.</p>
     *
     * @param description {@link Description} Description to start
     * @throws IllegalAccessException
     * @throws ConfigurationException
     */
    private void startTestCase(Description description) throws IllegalAccessException, ConfigurationException {
        Scenario scenario = findScenarioByDescription(description);

        if (description.isTest()) {

            String suiteUuid = getSuiteUuid(scenario.getScenario().getObject());

            if (suiteUuid == null) {
                //Create ans start test suite
                startScenario(scenario, description);
                suiteUuid = getSuiteUuid(description);
            }

            TestCaseStartedEvent event = new TestCaseStartedEvent(
                    suiteUuid,
                    extractMethodName(description));
            event.setTitle(extractMethodName(description));

            Collection<Annotation> annotations = new ArrayList<>();
            for (Annotation annotation : description.getAnnotations()) {
                annotations.add(annotation);
            }

            AnnotationManager am = new AnnotationManager(annotations);
            am.update(event);
            getLifecycle().fire(event);
        }
    }

    /**
     * <p>
     * Create and start test suite Junit entity.</p>
     *
     * @param scenario
     * @param description
     * @throws IllegalAccessException
     * @throws ConfigurationException
     */
    private void startScenario(Scenario scenario, Description description) throws IllegalAccessException, ConfigurationException {
        scenario = findScenarioByDescription(description);
        Features featureAnnotation = getFeaturesAnnotation(
                new String[]{scenario.getFeature().getObject().getDisplayName()});

        String storyName = scenario.getScenario().getObject().getDisplayName();
        if (scenario.getScenario().getType().equals(GherkinEntityType.SCENARIO_OUTLINE)) {
            storyName = ((gherkin.formatter.model.Scenario) getDescriptionUniqueId(scenario.getScenario().getObject())).
                    getName() + " : " + storyName;
        }

        Stories storyAnnotation = getStoriesAnnotation(
                new String[]{storyName});

        String uuid = generateSuiteUid(scenario.getScenario().getObject());
        TestSuiteStartedEvent event = new TestSuiteStartedEvent(uuid, storyAnnotation.value()[0]);
        event.setTitle(storyAnnotation.value()[0]);
        //Add feature and story annotations
        Collection<Annotation> annotations = new ArrayList<>();
        for (Annotation annotation : description.getAnnotations()) {
            annotations.add(annotation);
        }
        annotations.add(storyAnnotation);
        annotations.add(featureAnnotation);
        AnnotationManager am = new AnnotationManager(annotations);
        am.update(event);

        event.withLabels(AllureModelUtils.createTestFrameworkLabel("CucumberJVM"));

        getLifecycle().fire(event);

    }

    /**
     * <p>
     * Find Test classes level<p>
     * JUnit`s test {@link Description} is multilevel object with liquid
     * hierarchy.<br>
     * This method recursively query
     * {@link #getDescriptionUniqueId(org.junit.runner.Description)} method
     * until it matches {@link Feature} type and when returns parent of this
     * object as list of test classes descriptions
     *
     * @param description {@link Description} Description to start search where
     * @return {@link List<Description>} test classes description list
     * @throws IllegalAccessException
     */
    private List<Description> findTestClassesLevel(List<Description> description) throws IllegalAccessException {
        if (description.isEmpty()) {
            return new ArrayList<>();
        }
        Object possibleClass = getDescriptionUniqueId(description.get(0));
        if (possibleClass instanceof String && !((String) possibleClass).isEmpty()) {
            if (!description.get(0).getChildren().isEmpty()) {
                Object possibleFeature = getDescriptionUniqueId(description.get(0).getChildren().get(0));
                if (possibleFeature instanceof Feature) {
                    return description;
                } else {
                    return findTestClassesLevel(description.get(0).getChildren());
                }
            } else {
                //No scenarios in feature
                return description;
            }

        } else {
            return findTestClassesLevel(description.get(0).getChildren());
        }

    }

    /**
     * <p>
     * Find features level<p>
     * JUnit`s test {@link Description} is multilevel object with liquid
     * hierarchy.<br>
     * This method recursively query
     * {@link #getDescriptionUniqueId(org.junit.runner.Description)} method
     * until it matches {@link Feature} type and when returns list of
     * {@link Feature} descriptions
     *
     * @param description {@link Description} Description to start search where
     * @return {@link List <Description>} features description list
     * @throws IllegalAccessException
     */
    private List<Description> findFeaturesLevel(List<Description> description) throws IllegalAccessException {
        if (description.isEmpty()) {
            return new ArrayList<>();
        }
        Object entityType = getDescriptionUniqueId(description.get(0));
        if (entityType instanceof Feature) {
            return description;
        } else {
            return findFeaturesLevel(description.get(0).getChildren());
        }

    }

    /**
     * <p>
     * Find {@link Scenario} by Junit description.</p>
     *
     * @param description {@link Description}
     * @return {@link Scenario}
     * @throws IllegalAccessException
     * @throws ConfigurationException
     */
    private Scenario findScenarioByDescription(Description description) throws IllegalAccessException, ConfigurationException {
        List<Description> testClasses = findTestClassesLevel(parentDescription.getChildren());
        GherkinEntity feature = null;
        GherkinEntity scenario = null;

        for (Description testClass : testClasses) {

            List<Description> features = findFeaturesLevel(testClass.getChildren());

            for (Description featureCandidate : features) {
                GherkinEntity scenarioCandidate = findScenarioInFeature(featureCandidate, description);
                if (scenarioCandidate != null) {
                    feature = new GherkinEntity(GherkinEntityType.FEATURE, featureCandidate);
                    scenario = scenarioCandidate;
                    return new Scenario(feature, scenario);
                }
            }
        }
        throw new ConfigurationException("Cannot find scenario by description. "
                + "Looks like pom.xml missconfigured. See "
                + "https://github.com/allure-framework/allure-cucumber-jvm-adaptor/wiki/Configure-pom.xml "
                + "for details.");
    }

    /**
     * <p>
     * Find {@link GherkinEntity} scenario in given feature description.</p>
     *
     * @param feature {@link Description} feature to search in.
     * @param step {@link Description} entity which scenario needs to be found.
     * @return {@link GherkinEntity} found scenario or null.
     * @throws IllegalAccessException
     */
    private GherkinEntity findScenarioInFeature(Description feature, Description step) throws IllegalAccessException {
        GherkinEntityType gherkinEntityType = null;
        Description scenario = null;
        for (Description scenarioCandidate : feature.getChildren()) {
            Object scenarioInstance = getDescriptionUniqueId(scenarioCandidate);
            if (scenarioCandidate.equals(step)) {
                scenario = scenarioCandidate;
                gherkinEntityType = GherkinEntityType.SCENARIO;
                return new GherkinEntity(gherkinEntityType, scenario);
            }
            //Scenario
            if (scenarioInstance instanceof gherkin.formatter.model.Scenario) {
                for (Description child : scenarioCandidate.getChildren()) {
                    if (child.equals(step)) {
                        scenario = scenarioCandidate;
                        gherkinEntityType = GherkinEntityType.SCENARIO;
                        return new GherkinEntity(gherkinEntityType, scenario);
                    }
                }
                //Scenario Outline
            } else if (scenarioInstance instanceof gherkin.formatter.model.ScenarioOutline) {
                List<Description> examples = scenarioCandidate.getChildren().get(0).getChildren();
                // we need to go deeper :
                for (Description example : examples) {
                    if (example.equals(step) || example.getChildren().contains(step)) {
                        scenario = example;
                        gherkinEntityType = GherkinEntityType.SCENARIO_OUTLINE;
                        return new GherkinEntity(gherkinEntityType, scenario);
                    }
                }
            }
        }
        return null;
    }

    /**
     * <p>
     * Get Description unique object</p>
     *
     * @param description See {@link Description}
     * @return {@link Object} what represents by uniqueId on {@link Description}
     * creation as an arbitrary object used to define its type.<br>
     * It can be instance of {@link String}, {@link Feature}, {@link Scenario}
     * or {@link ScenarioOutline}.<br>
     * In case of {@link String} object it could be Suite, TestClass or an
     * empty, regardless to level of {@link #parentDescription}
     * @throws IllegalAccessException
     */
    private Object getDescriptionUniqueId(Description description) throws IllegalAccessException {
        return FieldUtils.readField(description, "fUniqueId", true);
    }

    /**
     * <p>
     * Generate suite {@link UUID} and put it to suites storage.</p>
     *
     * @param description {@link Description} Suite description
     * @return generated UUID
     * @throws IllegalAccessException
     */
    private String generateSuiteUid(Description description) throws IllegalAccessException {
        String uuid = UUID.randomUUID().toString();
        Object fUniqueId = FieldUtils.readField(description, "fUniqueId", true);
        synchronized (getSuites()) {
            getSuites().put(fUniqueId, uuid);
        }
        return uuid;
    }

    /**
     * <p>
     * Get suite UUID or null if not exist.</p>
     *
     * @param description {@link Description} Suite description
     * @return UUID
     * @throws IllegalAccessException
     * @throws ConfigurationException
     */
    public String getSuiteUuid(Description description) throws IllegalAccessException, ConfigurationException {
        Scenario scenario = findScenarioByDescription(description);
        Object fUniqueId = FieldUtils.readField(scenario.getScenario().getObject(), "fUniqueId", true);
        return getSuites().get(fUniqueId);
    }

    private Map<Object, String> getSuites() {
        return suites;
    }

    /**
     * <p>
     * Creates Story annotation object.</p>
     *
     * @param value story names array
     * @return Story annotation object
     */
    private Stories getStoriesAnnotation(final String[] value) {
        return new Stories() {

            @Override
            public String[] value() {
                return value;
            }

            @Override
            public Class<Stories> annotationType() {
                return Stories.class;
            }
        };
    }

    /**
     * <p>
     * Creates Feature annotation object.</p>
     *
     * @param value feature names array
     * @return {@link Features} annotation object
     */
    private Features getFeaturesAnnotation(final String[] value) {
        return new Features() {

            @Override
            public String[] value() {
                return value;
            }

            @Override
            public Class<Features> annotationType() {
                return Features.class;
            }
        };
    }

    /**
     * <p>
     * Extract method name from Junit description.</p>
     *
     * @param {@link Description} description
     * @return Formatted method name
     */
    private String extractMethodName(Description description) {
        String displayName = description.getDisplayName();
        Pattern pattern = Pattern.compile("^(.*)\\(\\|");
        Matcher matcher = pattern.matcher(displayName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return description.getMethodName();
    }

    private String getIgnoredMessage(Description description) {
        Ignore ignore = description.getAnnotation(Ignore.class
        );
        return ignore
                == null || ignore.value()
                .isEmpty() ? "Test ignored (without reason)!" : ignore.value();
    }
}
