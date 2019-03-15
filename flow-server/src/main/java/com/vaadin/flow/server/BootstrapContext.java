package com.vaadin.flow.server;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.AnnotationReader;
import com.vaadin.flow.shared.communication.PushMode;
import com.vaadin.flow.theme.ThemeDefinition;
import elemental.json.JsonObject;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Provides context information for the bootstrap process.
 */
public class BootstrapContext {

    private final ApplicationParameterBuilder applicationParameterBuilder;

    private final VaadinRequest request;
    private final VaadinResponse response;
    private final VaadinSession session;
    private final UI ui;
    private final Class<?> pageConfigurationHolder;

    private String appId;
    private PushMode pushMode;
    private JsonObject applicationParameters;
    private BootstrapUriResolver uriResolver;

    /**
     * Creates a new context instance using the given parameters.
     *
     * @param request
     *            the request object
     * @param response
     *            the response object
     * @param session
     *            the current session
     * @param ui
     *            the UI object
     * @param contextRootProvider
     *            function that determines what is the current context root
     */
    public BootstrapContext(VaadinRequest request,
            VaadinResponse response, VaadinSession session, UI ui, Function<VaadinRequest, String> contextRootProvider) {
        this.request = request;
        this.response = response;
        this.session = session;
        this.ui = ui;
        this.applicationParameterBuilder = new ApplicationParameterBuilder(contextRootProvider);

        pageConfigurationHolder = BootstrapUtils
                .resolvePageConfigurationHolder(ui, request).orElse(null);

    }

    /**
     * Gets the Vaadin/HTTP response.
     *
     * @return the Vaadin/HTTP response
     */
    public VaadinResponse getResponse() {
        return response;
    }

    /**
     * Gets the Vaadin/HTTP request.
     *
     * @return the Vaadin/HTTP request
     */
    public VaadinRequest getRequest() {
        return request;
    }

    /**
     * Gets the Vaadin session.
     *
     * @return the Vaadin session
     */
    public VaadinSession getSession() {
        return session;
    }

    /**
     * Gets the UI.
     *
     * @return the UI
     */
    public UI getUI() {
        return ui;
    }

    /**
     * Gets the push mode to use.
     *
     * @return the desired push mode
     */
    public PushMode getPushMode() {
        if (pushMode == null) {

            pushMode = getUI().getPushConfiguration().getPushMode();
            if (pushMode == null) {
                pushMode = getRequest().getService()
                        .getDeploymentConfiguration().getPushMode();
            }

            if (pushMode.isEnabled()
                    && !getRequest().getService().ensurePushAvailable()) {
                /*
                 * Fall back if not supported (ensurePushAvailable will log
                 * information to the developer the first time this happens)
                 */
                pushMode = PushMode.DISABLED;
            }
        }
        return pushMode;
    }

    /**
     * Gets the application id.
     *
     * The application id is defined by
     * {@link VaadinService#getMainDivId(VaadinSession, VaadinRequest)}
     *
     * @return the application id
     */
    public String getAppId() {
        if (appId == null) {
            appId = getRequest().getService().getMainDivId(getSession(),
                    getRequest());
        }
        return appId;
    }

    /**
     * Gets the application parameters specified by the BootstrapHandler.
     *
     * @return the application parameters that will be written on the page
     */
    public JsonObject getApplicationParameters() {
        if (applicationParameters == null) {
            applicationParameters = applicationParameterBuilder.buildFromContext(this);
        }

        return applicationParameters;
    }

    /**
     * Gets the URI resolver to use for bootstrap resources.
     *
     * @return the URI resolver
     */
    public BootstrapUriResolver getUriResolver() {
        if (uriResolver == null) {
            uriResolver = new BootstrapUriResolver(getUI());
        }

        return uriResolver;
    }

    /**
     * Checks if the application is running in production mode.
     *
     * @return <code>true</code> if in production mode, <code>false</code>
     *         otherwise.
     */
    public boolean isProductionMode() {
        return request.getService().getDeploymentConfiguration()
                .isProductionMode();
    }

    /**
     * Gets an annotation from the topmost class in the current navigation
     * target hierarchy.
     *
     * @param <T>
     *            the type of the annotation
     * @param annotationType
     *            the type of the annotation to get
     * @return an annotation, or an empty optional if there is no current
     *         navigation target or if it doesn't have the annotation
     */
    public <T extends Annotation> Optional<T> getPageConfigurationAnnotation(
            Class<T> annotationType) {
        if (pageConfigurationHolder == null) {
            return Optional.empty();
        } else {
            return AnnotationReader.getAnnotationFor(
                    pageConfigurationHolder, annotationType);
        }
    }

    /**
     * Gets a a list of annotations from the topmost class in the current
     * navigation target hierarchy.
     *
     * @param <T>
     *            the type of the annotations
     * @param annotationType
     *            the type of the annotation to get
     * @return a list of annotation, or an empty list if there is no current
     *         navigation target or if it doesn't have the annotation
     */
    public <T extends Annotation> List<T> getPageConfigurationAnnotations(
            Class<T> annotationType) {
        if (pageConfigurationHolder == null) {
            return Collections.emptyList();
        } else {
            return AnnotationReader.getAnnotationsFor(
                    pageConfigurationHolder, annotationType);
        }
    }

    /**
     * Gets the {@link ThemeDefinition} associated with the
     * pageConfigurationHolder of this context, if any.
     *
     * @return the theme definition, or empty if none is found, or
     *         pageConfigurationHolder is <code>null</code>
     * @see UI#getThemeFor(Class, String)
     */
    protected Optional<ThemeDefinition> getTheme() {
        return ui.getThemeFor(pageConfigurationHolder, null);
    }
}
