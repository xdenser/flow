package com.vaadin.flow.server;

import com.vaadin.flow.client.ClientResourcesUtils;
import com.vaadin.flow.shared.ApplicationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * @author miki
 * @since 2019-03-14
 */
public class BootstrapClientEngine {
    // to be honest, why is this so convoluted?
    // each call to #getClientEngine() calls a supplier, which in turns returns a static field
    // what is the difference between this and initialising String field directly and returning it?

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapClientEngine.class);

    static Supplier<String> CLIENT_ENGINE_SUPPLIER = () -> LazyClientEngineInit.CLIENT_ENGINE_FILE;

    static String getClientEngine() {
        return CLIENT_ENGINE_SUPPLIER.get();
    }

    private static class LazyClientEngineInit {
        private static final String CLIENT_ENGINE_FILE = readClientEngine();
    }

    private static String readClientEngine() {
        // read client engine file name
        try (InputStream prop = ClientResourcesUtils.getResource(
            "/META-INF/resources/" + ApplicationConstants.CLIENT_ENGINE_PATH
                + "/compile.properties")) {
            // null when running SDM or tests
            if (prop != null) {
                Properties properties = new Properties();
                properties.load(prop);
                return ApplicationConstants.CLIENT_ENGINE_PATH + "/"
                           + properties.getProperty("jsFile");
            } else {
                LOGGER.warn(
                    "No compile.properties available on initialization, "
                        + "could not read client engine file name.");
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return null;
    }


}
