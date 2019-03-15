package com.vaadin.flow.server;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.communication.AtmospherePushConnection;
import com.vaadin.flow.shared.ApplicationConstants;
import elemental.json.Json;
import elemental.json.JsonObject;

import java.util.Locale;
import java.util.function.Function;

/**
 * Class for extracting parameters from {@link BootstrapContext}.
 * @author miki
 * @since 2019-03-14
 */
class ApplicationParameterBuilder {
    private static final String CAPTION = "caption";
    private static final String MESSAGE = "message";
    private static final String URL = "url";

    private final Function<VaadinRequest, String> contextRootProvider;

    ApplicationParameterBuilder(Function<VaadinRequest, String> contextRootProvider) {
        this.contextRootProvider = contextRootProvider;
    }

    public JsonObject buildFromContext(BootstrapContext context) {
        JsonObject appConfig = getApplicationParameters(context.getRequest(),
            context.getSession());

        appConfig.put(ApplicationConstants.UI_ID_PARAMETER,
            context.getUI().getUIId());
        return appConfig;
    }

    private JsonObject getApplicationParameters(VaadinRequest request,
        VaadinSession session) {
        DeploymentConfiguration deploymentConfiguration = session
                                                              .getConfiguration();
        final boolean productionMode = deploymentConfiguration
                                           .isProductionMode();

        JsonObject appConfig = Json.createObject();

        appConfig.put(ApplicationConstants.FRONTEND_URL_ES6,
            deploymentConfiguration.getEs6FrontendPrefix());
        appConfig.put(ApplicationConstants.FRONTEND_URL_ES5,
            deploymentConfiguration.getEs5FrontendPrefix());
        appConfig.put(ApplicationConstants.UI_ELEMENT_ID,
            deploymentConfiguration.getRootElementId());

        if (!productionMode) {
            JsonObject versionInfo = Json.createObject();
            versionInfo.put("vaadinVersion", Version.getFullVersion());
            String atmosphereVersion = AtmospherePushConnection
                                           .getAtmosphereVersion();
            if (atmosphereVersion != null) {
                versionInfo.put("atmosphereVersion", atmosphereVersion);
            }
            appConfig.put("versionInfo", versionInfo);
        }

        // Use locale from session if set, else from the request
        Locale locale = ServletHelper.findLocale(session, request);
        // Get system messages
        SystemMessages systemMessages = session.getService()
                                            .getSystemMessages(locale, request);
        if (systemMessages != null) {
            JsonObject sessExpMsg = Json.createObject();
            putValueOrNull(sessExpMsg, CAPTION,
                systemMessages.getSessionExpiredCaption());
            putValueOrNull(sessExpMsg, MESSAGE,
                systemMessages.getSessionExpiredMessage());
            putValueOrNull(sessExpMsg, URL,
                systemMessages.getSessionExpiredURL());

            appConfig.put("sessExpMsg", sessExpMsg);
        }

        String contextRoot = this.contextRootProvider.apply(request);
        appConfig.put(ApplicationConstants.CONTEXT_ROOT_URL, contextRoot);

        if (!productionMode) {
            appConfig.put("debug", true);
        }

        if (deploymentConfiguration.isRequestTiming()) {
            appConfig.put("requestTiming", true);
        }

        appConfig.put("heartbeatInterval",
            deploymentConfiguration.getHeartbeatInterval());

        boolean sendUrlsAsParameters = deploymentConfiguration
                                           .isSendUrlsAsParameters();
        if (!sendUrlsAsParameters) {
            appConfig.put("sendUrlsAsParameters", false);
        }

        return appConfig;
    }

    private static void putValueOrNull(JsonObject object, String key,
        String value) {
        assert object != null;
        assert key != null;
        if (value == null) {
            object.put(key, Json.createNull());
        } else {
            object.put(key, value);
        }
    }



}
