package com.vaadin.flow.server;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.shared.VaadinUriResolver;

/**
 * The URI resolver used in the bootstrap process.
 */
public class BootstrapUriResolver extends VaadinUriResolver {
    private String frontendRootUrl;
    private String servletPathToContextRoot;

    /**
     * Creates a new bootstrap resolver based on the given request and
     * session.
     *
     * @param ui
     *            the ui to resolve for
     */
    BootstrapUriResolver(UI ui) {
        servletPathToContextRoot = ui.getInternals()
                .getContextRootRelativePath();
        VaadinSession session = ui.getSession();
        DeploymentConfiguration config = session.getConfiguration();
        if (session.getBrowser().isEs6Supported()) {
            frontendRootUrl = config.getEs6FrontendPrefix();
        } else {
            frontendRootUrl = config.getEs5FrontendPrefix();
        }
        assert frontendRootUrl.endsWith("/");
        assert servletPathToContextRoot.endsWith("/");
    }

    /**
     * Translates a Vaadin URI to a URL that can be loaded by the browser.
     * The following URI schemes are supported:
     * <ul>
     * <li><code>{@value ApplicationConstants#CONTEXT_PROTOCOL_PREFIX}</code>
     * - resolves to the application context root</li>
     * <li><code>{@value ApplicationConstants#FRONTEND_PROTOCOL_PREFIX}</code>
     * - resolves to the build path where web components were compiled.
     * Browsers supporting ES6 can receive different, more optimized files
     * than browsers that only support ES5.</li>
     * <li><code>{@value ApplicationConstants#BASE_PROTOCOL_PREFIX}</code> -
     * resolves to the base URI of the page</li>
     * </ul>
     * Any other URI protocols, such as <code>http://</code> or
     * <code>https://</code> are passed through this method unmodified.
     *
     * @param uri
     *            the URI to resolve
     * @return the resolved URI
     */
    public String resolveVaadinUri(String uri) {
        return super.resolveVaadinUri(uri, frontendRootUrl,
                servletPathToContextRoot);
    }

}
