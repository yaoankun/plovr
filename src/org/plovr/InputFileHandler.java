package org.plovr;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.data.SoyMapData;
import com.google.template.soy.tofu.SoyTofu;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.plovr.JsInput.CodeWithEtag;
import org.plovr.io.Responses;
import org.plovr.util.HttpExchangeUtil;

import java.io.IOException;
import java.net.URI;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link InputFileHandler} serves the content of input files to a compilation.
 *
 * @author bolinfest@gmail.com (Michael Bolin)
 */
public class InputFileHandler extends AbstractGetHandler {

  private static final String PARENT_DIRECTORY_TOKEN = "../";
  private static final String PARENT_DIRECTORY_PATTERN =
      Pattern.quote(PARENT_DIRECTORY_TOKEN);
  private static final String PARENT_DIRECTORY_REPLACEMENT_TOKEN = "$$/";
  private static final String PARENT_DIRECTORY_REPLACEMENT_PATTERN =
      PARENT_DIRECTORY_REPLACEMENT_TOKEN.replaceAll("\\$",
          Matcher.quoteReplacement("\\$"));

  private static final SoyTofu TOFU;

  static {
    SoyFileSet.Builder builder = SoyFileSet.builder();
    builder.add(Resources.getResource(InputFileHandler.class, "raw.soy"));
    SoyFileSet fileSet = builder.build();
    TOFU = fileSet.compileToTofu();
  }

  private final ClientErrorReporter reporter;

  public InputFileHandler(CompilationServer server) {
    super(server, true /* usesRestfulPath */);
    this.reporter = new ClientErrorReporter();
  }

  /**
   * Returns the JavaScript code that bootstraps loading the application code.
   */
  static String getJsToLoadManifest(CompilationServer server,
      final Config config,
      Manifest manifest,
      HttpExchange exchange) throws CompilationException {
    // Function that converts a JsInput to the URI where its raw content can be
    // loaded.
   Function<JsInput,JsonPrimitive> inputToUri = Functions.compose(
       GsonUtil.STRING_TO_JSON_PRIMITIVE,
       createInputNameToUriConverter(
           server,
           exchange,
           config.getId(),
           (config.getCompilationMode() == CompilationMode.RAW &&
            config.getEnableAggressiveRawCaching())));

    String moduleInfo;
    String moduleUris;
    ModuleConfig moduleConfig = config.getModuleConfig();
    List<JsInput> inputs;
    if (moduleConfig == null) {
      moduleInfo = null;
      moduleUris = null;
      inputs = manifest.getInputsInCompilationOrder();
    } else {
      moduleInfo = Compilation.createModuleInfo(moduleConfig).toString();

      // Get the list of JsInputs for each module and use that to construct
      // the JsonObject that will be used for the PLOVR_MODULE_URIS variable.
      Map<String, List<JsInput>> moduleToInputList = moduleConfig.
          partitionInputsIntoModules(manifest);
      JsonObject obj = new JsonObject();
      for (Map.Entry<String, List<JsInput>> entry : moduleToInputList.entrySet()) {
        String moduleName = entry.getKey();
        JsonArray uris = new JsonArray();
        for (JsInput input : entry.getValue()) {
          uris.add(inputToUri.apply(input));
        }
        obj.add(moduleName, uris);
      }
      moduleUris = obj.toString();

      // Have the initial JS load the list of files that correspond to the root
      // module. The root module is responsible for loading the other modules.
      inputs = moduleToInputList.get(moduleConfig.getRootModule());
    }

    JsonArray inputUrls = new JsonArray();
    for (JsInput input : inputs) {
      inputUrls.add(inputToUri.apply(input));
    }

    // TODO(bolinfest): Figure out how to reuse Compilation#appendRootModuleInfo
    // May require moving the method to ModuleConfig.
    SoyMapData mapData = new SoyMapData(
        "moduleInfo", moduleInfo,
        "moduleUris", moduleUris,
        "filesAsJsonArray", inputUrls.toString(),
        "path", exchange.getRequestURI().getPath());

    return TOFU.newRenderer("org.plovr.raw").setData(mapData).render();
  }

  /**
   * Pattern that matches the path to the REST URI for this handler.
   * The \\w+ will match the config id and the (/.*) will match the
   * input name.
   */
  private static final Pattern URI_INPUT_PATTERN = Pattern.compile(
      "/input/" + AbstractGetHandler.CONFIG_ID_PATTERN + "(/.*)");

  @Override
  protected void doGet(HttpExchange exchange, QueryData data, Config config)
      throws IOException {
    Manifest manifest = config.getManifest();

    URI uri = exchange.getRequestURI();
    Matcher matcher = URI_INPUT_PATTERN.matcher(uri.getPath());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Input could not be extracted from URI");
    }
    String name = matcher.group(1);

    // If the user requests the deps.js alongside base.js, then return the
    // generated dependency info for this config rather than the default deps.js
    // that comes with the Closure Library.
    String depsJsName = Manifest.DEPS_JS_INPUT_NAME;
    if (name.equals(depsJsName)) {
      super.setCacheHeaders(exchange.getResponseHeaders());
      StringBuilder builder = new StringBuilder();
      try {
        builder.append(getCodeForDepsJs(manifest));
      } catch (UncheckedCompilationException e) {
        reporter.newReport(config)
            .withErrors(e.createCompilationErrors())
            .appendTo(builder);
      }
      Responses.writeJs(builder.toString(), config, exchange);
      return;
    }

    // Reverse the rewriting done by createInputNameToUriConverter().
    name = name.replaceAll(PARENT_DIRECTORY_REPLACEMENT_PATTERN,
        PARENT_DIRECTORY_TOKEN);

    // Find the JsInput that matches the specified name.
    // TODO: eliminate this hack with the slash -- just make it an invariant of
    // the system.
    JsInput requestedInput = manifest.getJsInputByName(name);
    if (requestedInput == null && name.startsWith("/")) {
      // Remove the leading slash and try again.
      name = name.substring(1);
      requestedInput = manifest.getJsInputByName(name);
    }

    if (requestedInput == null) {
      HttpUtil.writeNullResponse(exchange);
      return;
    }

    // Find the code for the requested input.
    String code = null;

    // Add 'use strict' headers if we're in strict mode.
    String prefix = "";
    if (config.getLanguageIn() != null && config.getLanguageIn().isStrict()) {
      prefix = "'use strict';";
    }

    try {
      if (requestedInput.supportsEtags()) {
        // Set/check an ETag, if appropriate.
        CodeWithEtag codeWithEtag = requestedInput.getCodeWithEtag();
        String eTag = codeWithEtag.eTag;
        String ifNoneMatch = exchange.getRequestHeaders().getFirst(
            "If-None-Match");
        Headers headers = exchange.getResponseHeaders();

        if (data.getParam(ETAG_QUERY_PARAM) != null) {
          setAggressiveCacheHeaders(headers);
        }

        // Don't send etags on old version of Chrome, because of a weird Java
        // HttpServer bug that it reacted badly to. See:
        // https://code.google.com/p/chromium/issues/detail?id=105824
        if (eTag.equals(ifNoneMatch) &&
            !HttpExchangeUtil.isGoogleChrome35OrEarlier(exchange)) {
          Responses.notModified(exchange);
          return;
        } else {
          headers.set("ETag", eTag);
          code = prefix + codeWithEtag.code;
        }
      } else {
        // Do not set cache headers if the logic for ETags has not been defined
        // for this JsInput. Setting an "Expires" header based on the last
        // modified time for a file has been observed to cause resources to be
        // cached incorrectly by IE6.
        super.setCacheHeaders(exchange.getResponseHeaders());
        code = prefix + requestedInput.getCode();
      }
    } catch (UncheckedCompilationException e) {
      StringBuilder builder = new StringBuilder();
      reporter.newReport(config)
          .withErrors(e.createCompilationErrors())
          .appendTo(builder);
      code = builder.toString();
    }

    if (code == null) {
      HttpUtil.writeNullResponse(exchange);
      return;
    }

    Responses.writeJs(code, config, exchange);
  }

  private String getCodeForDepsJs(Manifest manifest) {
    String baseJsPath = Manifest.BASE_JS_INPUT_NAME;

    // Although baseJsPath is a constant, so this logic will always produce the
    // same result, we keep it here in case Manifest.BASE_JS_INPUT_NAME is
    // redefined in the future.
    if (baseJsPath.startsWith("/")) {
      baseJsPath = baseJsPath.substring(1);
    }
    int numDirectories = baseJsPath.split("\\/").length - 1;
    final String relativePath = Strings.repeat("../", numDirectories);

    Function<JsInput, String> converter = new Function<JsInput, String>() {
      @Override
      public String apply(JsInput input) {
        String path = InputFileHandler.escapeRelativePath.apply(input.getName());
        if (path.startsWith("/")) {
          path = path.substring(1);
        }
        return relativePath + path;
      }
    };

    return manifest.buildDepsJs(converter);
  }

  /**
   * By default, do nothing. {@link AbstractGetHandler#setCacheHeaders(Headers)}
   * will be called as appropriate from
   * {@link #doGet(HttpExchange, QueryData, Config)}.
   */
  @Override
  protected void setCacheHeaders(Headers headers) {}

  private void setAggressiveCacheHeaders(Headers headers) {
    setDateHeader(headers);

    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.SECOND, 31536000);

    headers.set("Expires", formatDate(cal.getTime()));
    headers.set("Cache-control", "max-age=31536000");
  }

  private static final String ETAG_QUERY_PARAM = "_tag";
  private static final Pattern ETAG_PATTERN = Pattern.compile("^W?\"([^\"]+)\"$");

  static Function<JsInput, String> createInputNameToUriConverter(
      CompilationServer server, HttpExchange exchange, final String configId,
      final boolean includeEtag) {
    String moduleUriBase = server.getServerForExchange(exchange);
    return createInputNameToUriConverter(moduleUriBase, configId, includeEtag);
  }

  @VisibleForTesting
  static Function<JsInput, String> createInputNameToUriConverter(
      final String moduleUriBase, final String configId, final boolean includeEtag) {
    return new Function<JsInput, String>() {
      @Override
      public String apply(JsInput input) {
        // TODO(bolinfest): Should input.getName() be URI-escaped? Maybe all
        // characters other than slashes?

        // Hack: some input names do not have a leading slash, so add one when
        // that is not the case and special case this in doGet().
        String name = input.getName();
        if (!name.startsWith("/")) {
          name = "/" + name;
        }

        // If an input name has "../" as part of its name, then the URL will be
        // rewritten as if it were "back a directory." To prevent this from
        // happening, this pattern is replaced with "$$/". This pattern must be
        // translated back to the original "../" when handling a request so that
        // the input can be identified by its name (which contains the relative
        // path information).
        name = escapeRelativePath.apply(name);

        String uri = String.format("%sinput/%s%s",
            moduleUriBase,
            QueryData.encode(configId),
            name);

        if (includeEtag && input.supportsEtags()) {
            String eTag = input.getCodeWithEtag().eTag;
            String tag = ETAG_PATTERN.matcher(eTag).replaceAll("$1");
            uri = String.format("%s?%s=%s", uri, ETAG_QUERY_PARAM, tag);
        }

        return uri;
      }
    };
  }

  static final Function<String, String> escapeRelativePath =
      new Function<String, String>() {
        @Override
        public String apply(String path) {
          return path.replaceAll(PARENT_DIRECTORY_PATTERN,
              PARENT_DIRECTORY_REPLACEMENT_PATTERN);
        }
  };
}
