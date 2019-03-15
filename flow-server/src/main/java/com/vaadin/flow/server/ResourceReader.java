package com.vaadin.flow.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Resource reader for a given class.
 * Uses the provided class to read the resource.
 * @author miki
 * @since 2019-03-14
 */
public class ResourceReader {

    private final Class<?> baseClass;

    private ResourceReader(Class<?> baseClass) {
        this.baseClass = baseClass;
    }

    /**
     * Reads the resource as string.
     * @param fileName Resource file name.
     * @return Contents of the file.
     * @throws ExceptionInInitializerError when file cannot be read
     */
    public String readResource(String fileName) {
        try (InputStream stream = this.baseClass
                                      .getResourceAsStream(fileName);
             BufferedReader bf = new BufferedReader(new InputStreamReader(
                 stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            bf.lines().forEach(builder::append);
            return builder.toString();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Method for those preferring static ways of constructing objects.
     * Opens up a possibility to cache resource readers to save memory.
     * @param type Base class to read resource for.
     * @return Resource reader.
     */
    public static final ResourceReader forClass(Class<?> type) {
        return new ResourceReader(type);
    }

    /**
     * Reads resource. Method for those who prefer static access.
     * @param baseClass Base class to read resource for.
     * @param fileName Name of the file with the resource.
     * @return Contents of the resource file.
     * @throws ExceptionInInitializerError when file cannot be read
     */
    public static final String readResource(Class<?> baseClass, String fileName) {
        return new ResourceReader(baseClass).readResource(fileName);
    }

}
