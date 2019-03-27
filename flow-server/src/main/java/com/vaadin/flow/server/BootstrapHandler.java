/*
 * Copyright 2000-2018 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.flow.server;

import com.vaadin.flow.component.PushConfiguration;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.internal.ReflectTools;
import com.vaadin.flow.server.communication.PushConnectionFactory;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.shared.communication.PushMode;
import org.jsoup.nodes.Document;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Iterator;
import java.util.Optional;
import java.util.ServiceLoader;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Request handler which handles bootstrapping of the application, i.e. the
 * initial GET request.
 *
 * @author Vaadin Ltd
 * @since 1.0
 */
public class BootstrapHandler extends SynchronizedRequestHandler {

    private final BootstrapPageBuilder pageBuilder;

    /**
     * Constructs the handler using the default page builder.
     */
    public BootstrapHandler() {
        this(new BootstrapPageBuilder());
    }

    /**
     * Constructs the handler using specific page builder. Useful when the contents of the page are to be inserted elsewhere.
     * @param pageBuilder A page builder to use.
     */
    protected BootstrapHandler(BootstrapPageBuilder pageBuilder) {
        this.pageBuilder = pageBuilder;
    }

    @Override
    public boolean synchronizedHandleRequest(VaadinSession session,
            VaadinRequest request, VaadinResponse response) throws IOException {
        // Find UI class
        Class<? extends UI> uiClass = getUIClass(request);

        BootstrapContext context = createAndInitUI(uiClass, request, response,
                session);

        ServletHelper.setResponseNoCacheHeaders(response::setHeader,
                response::setDateHeader);

        Document document = pageBuilder.buildFromContext(context);
        writeBootstrapPage(response, document.outerHtml());

        return true;
    }

    private static void writeBootstrapPage(VaadinResponse response, String html)
            throws IOException {
        response.setContentType(
                ApplicationConstants.CONTENT_TYPE_TEXT_HTML_UTF_8);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(response.getOutputStream(), UTF_8))) {
            writer.append(html);
        }
    }

    /**
     * Provides context root. Override this method if using something else than servlets.
     * @param request Request.
     * @return Context root, with a trailing {@code /}.
     */
    // todo consider moving this function into VaadinRequest interface (it may make more sense there)
    protected String getContextRootFromRequest(VaadinRequest request) {
        return ServletHelper.getContextRootRelativePath(request) + "/";
    }

    protected BootstrapContext createAndInitUI(Class<? extends UI> uiClass,
            VaadinRequest request, VaadinResponse response,
            VaadinSession session) {
        UI ui = ReflectTools.createInstance(uiClass);
        ui.getInternals().setContextRoot(this.getContextRootFromRequest(request));

        PushConfiguration pushConfiguration = ui.getPushConfiguration();

        ui.getInternals().setSession(session);
        ui.setLocale(session.getLocale());

        BootstrapContext context = new BootstrapContext(request, response,
                session, ui, this::getContextRootFromRequest, this::getApplicationRootElementId);

        Optional<Push> push = context
                .getPageConfigurationAnnotation(Push.class);

        DeploymentConfiguration deploymentConfiguration = context.getSession()
                .getService().getDeploymentConfiguration();
        PushMode pushMode = push.map(Push::value)
                .orElseGet(deploymentConfiguration::getPushMode);
        setupPushConnectionFactory(pushConfiguration, context);
        pushConfiguration.setPushMode(pushMode);
        pushConfiguration.setPushUrl(deploymentConfiguration.getPushURL());
        push.map(Push::transport).ifPresent(pushConfiguration::setTransport);

        // Set thread local here so it is available in init
        UI.setCurrent(ui);
        ui.doInit(request, session.getNextUIid());

        session.addUI(ui);

        // After init and adding UI to session fire init listeners.
        session.getService().fireUIInitListeners(ui);

        if (ui.getRouter() != null) {
            ui.getRouter().initializeUI(ui, request);
        }

        return context;
    }

    /**
     * Returns application id. Override this to place the contents of the app into a predefined element.
     * @param session Session.
     * @return Id of an element to put the contents to.
     */
    protected String getApplicationRootElementId(VaadinSession session) {
        return session.getConfiguration().getRootElementId();
    }

    protected void setupPushConnectionFactory(
            PushConfiguration pushConfiguration, BootstrapContext context) {
        VaadinService service = context.getSession().getService();
        Iterator<PushConnectionFactory> iter = ServiceLoader
                .load(PushConnectionFactory.class, service.getClassLoader())
                .iterator();
        if (iter.hasNext()) {
            pushConfiguration.setPushConnectionFactory(iter.next());
            if (iter.hasNext()) {
                throw new BootstrapException(
                        "Multiple " + PushConnectionFactory.class.getName()
                                + " implementations found");
            }
        }
    }

    /**
     * Returns the UI class mapped for servlet that handles the given request.
     * <p>
     * This method is protected for testing purposes.
     *
     * @param request
     *            the request for the UI
     * @return the UI class for the request
     */
    protected static Class<? extends UI> getUIClass(VaadinRequest request) {
        String uiClassName = request.getService().getDeploymentConfiguration()
                .getUIClassName();
        if (uiClassName == null) {
            throw new BootstrapException(
                    "Could not determine the uiClassName for the request path "
                            + request.getPathInfo());
        }

        ClassLoader classLoader = request.getService().getClassLoader();
        try {
            return Class.forName(uiClassName, true, classLoader)
                    .asSubclass(UI.class);
        } catch (ClassNotFoundException e) {
            throw new BootstrapException(
                    "Vaadin Servlet mapped to the request path "
                            + request.getPathInfo()
                            + " cannot find the mapped UI class with name "
                            + uiClassName,
                    e);
        }
    }

}
