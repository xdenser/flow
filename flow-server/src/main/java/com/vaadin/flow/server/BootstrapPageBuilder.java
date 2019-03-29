package com.vaadin.flow.server;

import com.vaadin.flow.client.ClientResourcesUtils;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.component.page.Viewport;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.internal.UsageStatistics;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.shared.ui.Dependency;
import com.vaadin.flow.shared.ui.LoadMode;
import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;
import elemental.json.JsonValue;
import elemental.json.impl.JsonUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the initial bootstrap page.
 *
 */
public class BootstrapPageBuilder {

    private static final CharSequence GWT_STAT_EVENTS_JS = "if (typeof window.__gwtStatsEvent != 'function') {"
                                                               + "window.Vaadin.Flow.gwtStatsEvents = [];"
                                                               + "window.__gwtStatsEvent = function(event) {"
                                                               + "window.Vaadin.Flow.gwtStatsEvents.push(event); "
                                                               + "return true;};};";
    static final String CONTENT_ATTRIBUTE = "content";
    private static final String DEFER_ATTRIBUTE = "defer";
    static final String VIEWPORT = "viewport";
    private static final String META_TAG = "meta";
    private static final String SCRIPT_TAG = "script";

    /**
     * Location of client nocache file, relative to the context root.
     */
    private static final String CLIENT_ENGINE_NOCACHE_FILE = ApplicationConstants.CLIENT_ENGINE_PATH
                                                                 + "/client.nocache.js";
    private static final String BOOTSTRAP_JS = ResourceReader.readResource(BootstrapPageBuilder.class,
        "BootstrapHandler.js");
    private static final String BABEL_HELPERS_JS = ResourceReader.readResource(BootstrapPageBuilder.class,
        "babel-helpers.min.js");
    private static final String ES6_COLLECTIONS = "//<![CDATA[\n"
                                                      + ResourceReader.readResource(BootstrapPageBuilder.class,
        "es6-collections.js") + "//]]>";
    private static final String CSS_TYPE_ATTRIBUTE_VALUE = "text/css";

    private final UidlBuilder uidlBuilder = new UidlBuilder();

    /**
     * Builds the page based on the bootstrap context.
     * @param context Context with all relevant information.
     * @return A document with a page.
     */
    public Document buildFromContext(BootstrapContext context) {
        final Document document = this.createDocument(context);

        final Map.Entry<Element, Element> headAndBody = this.appendHeadAndBodyElements(document, context);
        final Element head = headAndBody.getKey();
        final Element body = headAndBody.getValue();

        List<Element> dependenciesToInlineInBody = setupDocumentHead(head,
            context);
        dependenciesToInlineInBody.forEach(body::appendChild);
        setupDocumentBody(body);

        document.outputSettings().prettyPrint(false);

        BootstrapUtils.getInlineTargets(context)
            .ifPresent(targets -> handleInlineTargets(context, head,
                body, targets));

        BootstrapUtils.getInitialPageSettings(context).ifPresent(
            initialPageSettings -> handleInitialPageSettings(context, head,
                initialPageSettings));

        /* Append any theme elements to initial page. */
        handleThemeContents(context, head, body);

        if (!context.isProductionMode()) {
            exportUsageStatistics(body);
        }

        setupPwa(context, head, body);

        BootstrapPageResponse response = new BootstrapPageResponse(
            context.getRequest(), context.getSession(),
            context.getResponse(), document, context.getUI(),
            context.getUriResolver());
        context.getSession().getService().modifyBootstrapPage(response);

        return document;
    }

    /**
     * Creates a new document (with document type html).
     * Override this method if you are interested in creating documents other than html documents.
     * @param context Bootstrap context.
     * @return Document.
     */
    protected Document createDocument(BootstrapContext context) {
        Document document = new Document("");
        DocumentType doctype = new DocumentType("html", "", ""); //document.baseUri() as last parameter is not used
        document.appendChild(doctype);
        return document;
    }

    /**
     * Appends head and body elements into the given document.
     * Override this method if you do not want to create a full html page with {@code <html>} as the root element.
     * @param document Document to create the root element in.
     * @return Pair of elements: head and body.
     */
    // why is Map.Entry used here? because it is the only java class that features two elements of known type
    protected Map.Entry<Element, Element> appendHeadAndBodyElements(Document document, BootstrapContext context) {
        Element html = document.appendElement("html");
        html.attr("lang", context.getUI().getLocale().getLanguage());
        Element head = html.appendElement("head");
        Element body = html.appendElement("body");
        return new AbstractMap.SimpleImmutableEntry<>(head, body);
    }

    /**
     * Builds the head of the document.
     * @param head Head element.
     * @param context Bootstrap context.
     * @return List of elements to be added to the head element.
     */
    private List<Element> setupDocumentHead(Element head,
        BootstrapContext context) {
        setupMetaAndTitle(head, context);
        setupCss(head, context);

        JsonObject initialUIDL = this.uidlBuilder.getInitialUidl(context.getUI());
        Map<LoadMode, JsonArray> dependenciesToProcessOnServer = popDependenciesToProcessOnServer(
            initialUIDL);
        setupFrameworkLibraries(head, initialUIDL, context);
        return applyUserDependencies(head, context,
            dependenciesToProcessOnServer);
    }

    private List<Element> applyUserDependencies(Element head,
        BootstrapContext context,
        Map<LoadMode, JsonArray> dependenciesToProcessOnServer) {
        List<Element> dependenciesToInlineInBody = new ArrayList<>();
        for (Map.Entry<LoadMode, JsonArray> entry : dependenciesToProcessOnServer
                                                        .entrySet()) {
            dependenciesToInlineInBody.addAll(
                inlineDependenciesInHead(head, context.getUriResolver(),
                    entry.getKey(), entry.getValue()));
        }
        return dependenciesToInlineInBody;
    }

    protected List<Element> inlineDependenciesInHead(Element head,
        BootstrapUriResolver uriResolver, LoadMode loadMode,
        JsonArray dependencies) {
        List<Element> dependenciesToInlineInBody = new ArrayList<>();

        for (int i = 0; i < dependencies.length(); i++) {
            JsonObject dependencyJson = dependencies.getObject(i);
            Dependency.Type dependencyType = Dependency.Type
                                                 .valueOf(dependencyJson.getString(Dependency.KEY_TYPE));
            Element dependencyElement = createDependencyElement(uriResolver,
                loadMode, dependencyJson, dependencyType);

            if (loadMode == LoadMode.INLINE
                    && dependencyType == Dependency.Type.HTML_IMPORT) {
                dependenciesToInlineInBody.add(dependencyElement);
            } else {
                head.appendChild(dependencyElement);
            }
        }
        return dependenciesToInlineInBody;
    }

    private Map<LoadMode, JsonArray> popDependenciesToProcessOnServer(
        JsonObject initialUIDL) {
        Map<LoadMode, JsonArray> result = new EnumMap<>(LoadMode.class);
        Stream.of(LoadMode.EAGER, LoadMode.INLINE).forEach(mode -> {
            if (initialUIDL.hasKey(mode.name())) {
                result.put(mode, initialUIDL.getArray(mode.name()));
                initialUIDL.remove(mode.name());
            }
        });
        return result;
    }

    private void setupFrameworkLibraries(Element head,
        JsonObject initialUIDL, BootstrapContext context) {
        inlineEs6Collections(head, context);
        appendWebComponentsPolyfills(head, context);

        if (context.getPushMode().isEnabled()) {
            head.appendChild(getPushScript(context));
        }

        head.appendChild(getBootstrapScript(initialUIDL, context));
        head.appendChild(createJavaScriptElement(getClientEngineUrl(context)));
    }

    private void inlineEs6Collections(Element head,
        BootstrapContext context) {
        if (!context.getSession().getBrowser().isEs6Supported()) {
            head.appendChild(createInlineJavaScriptElement(ES6_COLLECTIONS));
        }
    }

    /**
     * Creates additional CSS styles to be appended to {@code style} markup.
     * @param context Bootstrap context, if needed.
     * @return A list of valid CSS styles.
     */
    protected List<String> createAdditionalCss(BootstrapContext context) {
        return Arrays.asList(
            // Basic reconnect dialog style just to make it visible and outside of
            // normal flow
            ".v-reconnect-dialog {" //
                              + "position: absolute;" //
                              + "top: 1em;" //
                              + "right: 1em;" //
                              + "border: 1px solid black;" //
                              + "padding: 1em;" //
                              + "z-index: 10000;" //
                              + "}",

        // Basic system error dialog style just to make it visible and outside
        // of normal flow
        ".v-system-error {" //
                              + "color: red;" //
                              + "background: white;" //
                              + "position: absolute;" //
                              + "top: 1em;" //
                              + "right: 1em;" //
                              + "border: 1px solid black;" //
                              + "padding: 1em;" //
                              + "z-index: 10000;" //
                              + "pointer-events: auto;" //
                              + "}");
    }

    /**
     * Adds {@code style} tags to the head.
     * @param head Head element.
     * @param context Bootstrap context.
     */
    private void setupCss(Element head, BootstrapContext context) {
        Element styles = head.appendElement("style").attr("type", CSS_TYPE_ATTRIBUTE_VALUE);
        // Add any body style that is defined for the application using
        // @BodySize
        String bodySizeContent = BootstrapUtils.getBodySizeContent(context);
        styles.appendText(bodySizeContent);

        this.createAdditionalCss(context).forEach(styles::appendText);
    }

    /**
     * Appends all {@code meta} and {@code title} elements to the head.
     * @param head Head element to add things to.
     * @param context Bootstrap context.
     */
    private void setupMetaAndTitle(Element head,
        BootstrapContext context) {
        head.appendElement(META_TAG).attr("http-equiv", "Content-Type").attr(
            CONTENT_ATTRIBUTE,
            ApplicationConstants.CONTENT_TYPE_TEXT_HTML_UTF_8);

        head.appendElement(META_TAG).attr("http-equiv", "X-UA-Compatible")
            .attr(CONTENT_ATTRIBUTE, "IE=edge");

        head.appendElement("base").attr("href", getServiceUrl(context));

        head.appendElement(META_TAG).attr("name", VIEWPORT)
            .attr(CONTENT_ATTRIBUTE, BootstrapUtils
                                         .getViewportContent(context).orElse(Viewport.DEFAULT));

        if (!BootstrapUtils.getMetaTargets(context).isEmpty()) {
            BootstrapUtils.getMetaTargets(context)
                .forEach((name, content) -> head.appendElement(META_TAG)
                                                .attr("name", name)
                                                .attr(CONTENT_ATTRIBUTE, content));
        }
        resolvePageTitle(context).ifPresent(title -> {
            if (!title.isEmpty()) {
                head.appendElement("title").appendText(title);
            }
        });
    }

    private void setupPwa(BootstrapContext context, Element head, Element body) {
        VaadinService vaadinService = context.getSession().getService();
        if (vaadinService == null) {
            return;
        }

        PwaRegistry registry = vaadinService.getPwaRegistry();
        if (registry == null) {
            return;
        }

        PwaConfiguration config = registry.getPwaConfiguration();

        if (config.isEnabled()) {

            // Describe PWA capability for iOS devices
            head.appendElement(META_TAG)
                .attr("name", "apple-mobile-web-app-capable")
                .attr(CONTENT_ATTRIBUTE, "yes");

            // Theme color
            head.appendElement(META_TAG).attr("name", "theme-color")
                .attr(CONTENT_ATTRIBUTE, config.getThemeColor());
            head.appendElement(META_TAG)
                .attr("name", "apple-mobile-web-app-status-bar-style")
                .attr(CONTENT_ATTRIBUTE, config.getThemeColor());

            // Add manifest
            head.appendElement("link").attr("rel", "manifest").attr("href",
                config.getManifestPath());

            // Add icons
            for (PwaIcon icon : registry.getHeaderIcons()) {
                head.appendChild(icon.asElement());
            }

            // Add service worker initialization
            head.appendElement(SCRIPT_TAG)
                .text("if ('serviceWorker' in navigator) {\n"
                          + "  window.addEventListener('load', function() {\n"
                          + "    navigator.serviceWorker.register('"
                          + config.getServiceWorkerPath() + "');\n"
                          + "  });\n" + "}");

            // add body injections
            if (registry.getPwaConfiguration().isInstallPromptEnabled()) {
                // PWA Install prompt html/js
                body.append(registry.getInstallPrompt());
            }
        }
    }

    private void appendWebComponentsPolyfills(Element head,
        BootstrapContext context) {
        VaadinSession session = context.getSession();
        DeploymentConfiguration config = session.getConfiguration();

        String webcomponentsLoaderUrl = "frontend://bower_components/webcomponentsjs/webcomponents-loader.js";
        String es5AdapterUrl = "frontend://bower_components/webcomponentsjs/custom-elements-es5-adapter.js";
        VaadinService service = session.getService();
        if (!service.isResourceAvailable(webcomponentsLoaderUrl,
            session.getBrowser(), null)) {
            // No webcomponents polyfill, load nothing
            return;
        }

        boolean loadEs5Adapter = config
                                     .getBooleanProperty(Constants.LOAD_ES5_ADAPTERS, true);
        if (loadEs5Adapter && !session.getBrowser().isEs6Supported()) {
            // This adapter is required since lots of our current customers
            // use polymer-cli to transpile sources,
            // this tool adds babel-helpers dependency into each file, see:
            // https://github.com/Polymer/polymer-cli/blob/master/src/build/build.ts#L64
            // and
            // https://github.com/Polymer/polymer-cli/blob/master/src/build/optimize-streams.ts#L119
            head.appendChild(createInlineJavaScriptElement(BABEL_HELPERS_JS));

            if (session.getBrowser().isEs5AdapterNeeded()) {
                head.appendChild(
                    createJavaScriptElement(context.getUriResolver()
                                                .resolveVaadinUri(es5AdapterUrl), false));
            }
        }

        String resolvedUrl = context.getUriResolver()
                                 .resolveVaadinUri(webcomponentsLoaderUrl);
        head.appendChild(createJavaScriptElement(resolvedUrl, false));

    }

    private void handleInlineTargets(BootstrapContext context,
        Element head, Element body, InlineTargets targets) {
        targets.getInlineHead(Inline.Position.PREPEND).stream()
            .map(dependency -> createDependencyElement(context, dependency))
            .forEach(
                element -> insertElements(element, head::prependChild));
        targets.getInlineHead(Inline.Position.APPEND).stream()
            .map(dependency -> createDependencyElement(context, dependency))
            .forEach(element -> insertElements(element, head::appendChild));

        targets.getInlineBody(Inline.Position.PREPEND).stream()
            .map(dependency -> createDependencyElement(context, dependency))
            .forEach(
                element -> insertElements(element, body::prependChild));
        targets.getInlineBody(Inline.Position.APPEND).stream()
            .map(dependency -> createDependencyElement(context, dependency))
            .forEach(element -> insertElements(element, body::appendChild));
    }

    private void handleInitialPageSettings(BootstrapContext context,
        Element head, InitialPageSettings initialPageSettings) {
        if (initialPageSettings.getViewport() != null) {
            Elements viewport = head.getElementsByAttributeValue("name",
                VIEWPORT);
            if (!viewport.isEmpty() && viewport.size() == 1) {
                viewport.get(0).attr(CONTENT_ATTRIBUTE,
                    initialPageSettings.getViewport());
            } else {
                head.appendElement(META_TAG).attr("name", VIEWPORT).attr(
                    CONTENT_ATTRIBUTE, initialPageSettings.getViewport());
            }
        }

        initialPageSettings.getInline(InitialPageSettings.Position.PREPEND)
            .stream()
            .map(dependency -> createDependencyElement(context, dependency))
            .forEach(
                element -> insertElements(element, head::prependChild));
        initialPageSettings.getInline(InitialPageSettings.Position.APPEND)
            .stream()
            .map(dependency -> createDependencyElement(context, dependency))
            .forEach(element -> insertElements(element, head::appendChild));

        initialPageSettings.getElement(InitialPageSettings.Position.PREPEND)
            .forEach(
                element -> insertElements(element, head::prependChild));
        initialPageSettings.getElement(InitialPageSettings.Position.APPEND)
            .forEach(element -> insertElements(element, head::appendChild));
    }

    private void insertElements(Element element,
        Consumer<Element> action) {
        if (element instanceof Document) {
            element.getAllElements().stream()
                .filter(item -> !(item instanceof Document)
                                    && element.equals(item.parent()))
                .forEach(action::accept);
        } else {
            action.accept(element);
        }
    }

    private void setupDocumentBody(Element body) {
        body.appendElement("noscript").append(
            "You have to enable javascript in your browser to use this web site.");
    }

    private Element getPushScript(BootstrapContext context) {
        VaadinRequest request = context.getRequest();

        // Parameter appended to JS to bypass caches after version upgrade.
        String versionQueryParam = "?v=" + Version.getFullVersion();

        // Load client-side dependencies for push support
        String pushJSPath = ServletHelper.getContextRootRelativePath(request)
                                + "/";
        if (request.getService().getDeploymentConfiguration()
                .isProductionMode()) {
            pushJSPath += ApplicationConstants.VAADIN_PUSH_JS;
        } else {
            pushJSPath += ApplicationConstants.VAADIN_PUSH_DEBUG_JS;
        }

        pushJSPath += versionQueryParam;

        return createJavaScriptElement(pushJSPath);
    }

    private Element getBootstrapScript(JsonValue initialUIDL,
        BootstrapContext context) {
        return createInlineJavaScriptElement("//<![CDATA[\n"
                                                 + getBootstrapJS(initialUIDL, context) + "//]]>");
    }

    private String getBootstrapJS(JsonValue initialUIDL,
        BootstrapContext context) {
        boolean productionMode = context.getSession().getConfiguration()
                                     .isProductionMode();
        String result = getBootstrapJS();
        JsonObject appConfig = context.getApplicationParameters();
        appConfig.put(ApplicationConstants.UI_TAG,
            this.getUiTag(context));

        int indent = 0;
        if (!productionMode) {
            indent = 4;
        }
        String appConfigString = JsonUtil.stringify(appConfig, indent);

        String initialUIDLString = JsonUtil.stringify(initialUIDL, indent);

        /*
         * The < symbol is escaped to prevent two problems:
         *
         * 1 - The browser interprets </script> as end of script no matter if it
         * is inside a string
         *
         * 2 - Scripts can be injected with <!-- <script>, that can cause
         * unexpected behavior or complete crash of the app
         */
        initialUIDLString = initialUIDLString.replace("<", "\\x3C");

        if (!productionMode) {
            // only used in debug mode by profiler
            result = result.replace("{{GWT_STAT_EVENTS}}", GWT_STAT_EVENTS_JS);
        } else {
            result = result.replace("{{GWT_STAT_EVENTS}}", "");
        }

        result = result.replace("{{APP_ID}}", context.getAppId());
        result = result.replace("{{CONFIG_JSON}}", appConfigString);
        // {{INITIAL_UIDL}} should be the last replaced so that it may have
        // other patterns inside it (like {{CONFIG_JSON}})
        result = result.replace("{{INITIAL_UIDL}}", initialUIDLString);
        return result;
    }

    /**
     * Returns the tag name of the UI element.
     * @param context Context.
     * @return Tag name.
     */
    protected String getUiTag(BootstrapContext context) {
        return context.getUI().getElement().getTag();
    }

    /**
     * Gets the service URL as a URL relative to the request URI.
     *
     * @param context
     *            the bootstrap context
     * @return the relative service URL
     */
    protected String getServiceUrl(BootstrapContext context) {
        String pathInfo = context.getRequest().getPathInfo();
        if (pathInfo == null) {
            return ".";
        } else {
            /*
             * Make a relative URL to the servlet by adding one ../ for each
             * path segment in pathInfo (i.e. the part of the requested path
             * that comes after the servlet mapping)
             */
            return ServletHelper.getCancelingRelativePath(pathInfo);
        }
    }

    /**
     * Resolves the initial page title for the given bootstrap context and
     * cancels any pending JS execution for it.
     *
     * @param context
     *            the bootstrap context
     * @return the optional initial page title
     */
    protected Optional<String> resolvePageTitle(
        BootstrapContext context) {
        // check for explicitly set page title, e.g. by PageTitleGenerator or
        // View level title or page.setTitle
        String title = context.getUI().getInternals().getTitle();
        if (title != null) {
            // cancel the unnecessary execute javascript
            context.getUI().getInternals().cancelPendingTitleUpdate();
        }
        return Optional.ofNullable(title);
    }

    private void exportUsageStatistics(Element body) {
        String registerScript = UsageStatistics.getEntries().map(entry -> {
            String name = entry.getName();
            String version = entry.getVersion();

            JsonObject json = Json.createObject();
            json.put("is", name);
            json.put("version", version);

            String escapedName = Json.create(name).toJson();

            // Registers the entry in a way that is picked up as a Vaadin
            // WebComponent by the usage stats gatherer
            return String.format("window.Vaadin[%s]=%s;", escapedName, json);
        }).collect(Collectors.joining("\n"));

        if (!registerScript.isEmpty()) {
            body.appendElement(SCRIPT_TAG).text(registerScript);
        }
    }

    private void handleThemeContents(BootstrapContext context,
        Element head, Element body) {
        BootstrapUtils.ThemeSettings themeSettings = BootstrapUtils.getThemeSettings(context);

        if (themeSettings == null) {
            // no theme configured for the application
            return;
        }

        List<JsonObject> themeContents = themeSettings.getHeadContents();
        if (themeContents != null) {
            themeContents.stream().map(
                dependency -> createDependencyElement(context, dependency))
                .forEach(element -> insertElements(element,
                    head::appendChild));
        }

        JsonObject themeContent = themeSettings.getHeadInjectedContent();
        if (themeContent != null) {
            Element dependency = createDependencyElement(context, themeContent);
            insertElements(dependency, head::appendChild);
        }

        if (themeSettings.getHtmlAttributes() != null) {
            Element html = body.parent();
            assert html.tagName().equalsIgnoreCase("html");
            themeSettings.getHtmlAttributes().forEach(html::attr);
        }
    }

    private Element createInlineJavaScriptElement(
        String javaScriptContents) {
        // defer makes no sense without src:
        // https://developer.mozilla.org/en/docs/Web/HTML/Element/script
        Element wrapper = createJavaScriptElement(null, false);
        wrapper.appendChild(
            new DataNode(javaScriptContents, wrapper.baseUri()));
        return wrapper;
    }

    private Element createJavaScriptElement(String sourceUrl,
        boolean defer) {
        Element jsElement = new Element(Tag.valueOf(SCRIPT_TAG), "")
                                .attr("type", "text/javascript").attr(DEFER_ATTRIBUTE, defer);
        if (sourceUrl != null) {
            jsElement = jsElement.attr("src", sourceUrl);
        }
        return jsElement;
    }

    private Element createJavaScriptElement(String sourceUrl) {
        return createJavaScriptElement(sourceUrl, true);
    }

    private Element createDependencyElement(BootstrapContext context,
        JsonObject dependencyJson) {
        String type = dependencyJson.getString(Dependency.KEY_TYPE);
        if (Dependency.Type.contains(type)) {
            Dependency.Type dependencyType = Dependency.Type.valueOf(type);
            return createDependencyElement(context.getUriResolver(),
                LoadMode.INLINE, dependencyJson, dependencyType);
        }
        return Jsoup.parse(dependencyJson.getString(Dependency.KEY_CONTENTS),
            "", Parser.xmlParser());
    }

    private Element createDependencyElement(
        BootstrapUriResolver resolver, LoadMode loadMode,
        JsonObject dependency, Dependency.Type type) {
        boolean inlineElement = loadMode == LoadMode.INLINE;
        String url = dependency.hasKey(Dependency.KEY_URL)
                         ? resolver.resolveVaadinUri(
            dependency.getString(Dependency.KEY_URL))
                         : null;

        final Element dependencyElement;
        switch (type) {
        case STYLESHEET:
            dependencyElement = createStylesheetElement(url);
            break;
        case JAVASCRIPT:
            dependencyElement = createJavaScriptElement(url, !inlineElement);
            break;
        case HTML_IMPORT:
            dependencyElement = createHtmlImportElement(url);
            break;
        default:
            throw new IllegalStateException(
                "Unsupported dependency type: " + type);
        }

        if (inlineElement) {
            dependencyElement.appendChild(
                new DataNode(dependency.getString(Dependency.KEY_CONTENTS),
                    dependencyElement.baseUri()));
        }

        return dependencyElement;
    }

    private Element createHtmlImportElement(String url) {
        final Element htmlImportElement;
        if (url != null) {
            htmlImportElement = new Element(Tag.valueOf("link"), "")
                                    .attr("rel", "import").attr("href", url);
        } else {
            htmlImportElement = new Element(Tag.valueOf("span"), "")
                                    .attr("hidden", true);
        }
        return htmlImportElement;
    }

    private Element createStylesheetElement(String url) {
        final Element cssElement;
        if (url != null) {
            cssElement = new Element(Tag.valueOf("link"), "")
                             .attr("rel", "stylesheet")
                             .attr("type", CSS_TYPE_ATTRIBUTE_VALUE).attr("href", url);
        } else {
            cssElement = new Element(Tag.valueOf("style"), "").attr("type",
                CSS_TYPE_ATTRIBUTE_VALUE);
        }
        return cssElement;
    }


    private String getBootstrapJS() {
        if (BOOTSTRAP_JS.isEmpty()) {
            throw new BootstrapException(
                "BootstrapHandler.js has not been loaded during initialization");
        }
        return BOOTSTRAP_JS;
    }

    private String getClientEngineUrl(BootstrapContext context) {
        // use nocache version of client engine if it
        // has been compiled by SDM or eclipse
        // In production mode, this should really be loaded by the static block
        // so emit a warning if we get here (tests will always get here)
        final boolean productionMode = context.getSession().getConfiguration()
                                           .isProductionMode();

        boolean resolveNow = !productionMode || BootstrapClientEngine.getClientEngine() == null;
        if (resolveNow && ClientResourcesUtils.getResource(
            "/META-INF/resources/" + CLIENT_ENGINE_NOCACHE_FILE) != null) {
            return context.getUriResolver().resolveVaadinUri(
                "context://" + CLIENT_ENGINE_NOCACHE_FILE);
        }

        if (BootstrapClientEngine.getClientEngine() == null) {
            throw new BootstrapException(
                "Client engine file name has not been resolved during initialization");
        }
        return context.getUriResolver()
                   .resolveVaadinUri("context://" + BootstrapClientEngine.getClientEngine());
    }

}
