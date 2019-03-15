package com.vaadin.flow.server;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.communication.UidlWriter;
import com.vaadin.flow.shared.ApplicationConstants;
import elemental.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author miki
 * @since 2019-03-14
 */
public class UidlBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(UidlBuilder.class.getName());

    /**
     * Generates the initial UIDL message which is included in the initial
     * bootstrap page.
     *
     * @param ui
     *            the UI for which the UIDL should be generated
     * @return a JSON object with the initial UIDL message
     */
    public JsonObject getInitialUidl(UI ui) {
        JsonObject json = new UidlWriter().createUidl(ui, false);

        VaadinSession session = ui.getSession();
        if (session.getConfiguration().isXsrfProtectionEnabled()) {
            writeSecurityKeyUIDL(json, session);
        }
        writePushIdUIDL(json, session);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Initial UIDL: {}", json.asString());
        }
        return json;
    }

    /**
     * Writes the security key (and generates one if needed) to the given JSON
     * object.
     *
     * @param response
     *            the response JSON object to write security key into
     * @param session
     *            the vaadin session to which the security key belongs
     */
    private static void writeSecurityKeyUIDL(JsonObject response,
        VaadinSession session) {
        String seckey = session.getCsrfToken();
        response.put(ApplicationConstants.UIDL_SECURITY_TOKEN_ID, seckey);
    }

    /**
     * Writes the push id (and generates one if needed) to the given JSON
     * object.
     *
     * @param response
     *            the response JSON object to write security key into
     * @param session
     *            the vaadin session to which the security key belongs
     */
    private static void writePushIdUIDL(JsonObject response,
        VaadinSession session) {
        String pushId = session.getPushId();
        response.put(ApplicationConstants.UIDL_PUSH_ID, pushId);
    }

}
