package com.heroku.sdk.deploy;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.io.FileUtils;
import sun.misc.BASE64Encoder;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class App {

  private static Map<String,String> jdkUrlStrings = new HashMap<String,String>();

  static {
    jdkUrlStrings.put("1.6", "https://lang-jvm.s3.amazonaws.com/jdk/openjdk1.6-latest.tar.gz");
    jdkUrlStrings.put("1.7", "https://lang-jvm.s3.amazonaws.com/jdk/openjdk1.7-latest.tar.gz");
    jdkUrlStrings.put("1.8", "https://lang-jvm.s3.amazonaws.com/jdk/openjdk1.8-latest.tar.gz");
  }

  private String buildPackDesc;

  private String name;

  private File rootDir;

  private File targetDir;

  private String encodedApiKey = null;

  public void logInfo(String message) { /* nothing by default */ }

  public void logDebug(String message) { /* nothing by default */ }

  public void logWarn(String message) { /* nothing by default */ }

  public App(String name) throws IOException {
    this("heroku-deploy", name, new File(System.getProperty("user.dir")), createTempDir());
  }

  public App(String buildPackDesc, String name, File rootDir, File targetDir) {
    this.buildPackDesc = buildPackDesc;
    this.name = name;
    this.rootDir = rootDir;
    this.targetDir = targetDir;

    getHerokuDir().mkdir();
    getAppDir().mkdir();
  }

  protected void deploy(List<File> includedFiles, Map<String,String> configVars, String jdkVersion, URL jdkUrl, Map<String,String> processTypes) throws Exception {
    prepare(includedFiles, jdkVersion, jdkUrl);

    Map<String,String> existingConfigVars = getConfigVars();
    logDebug("Heroku existing config variables: " + existingConfigVars.keySet());

    Map<String,String> newConfigVars = new HashMap<String, String>();
    newConfigVars.putAll(addConfigVar("PATH", ".jdk/bin:/usr/local/bin:/usr/bin:/bin", existingConfigVars, true));
    for (String key : configVars.keySet()) {
      newConfigVars.putAll(addConfigVar(key, configVars.get(key), existingConfigVars));
    }
    setConfigVars(newConfigVars);

    deploySlug(processTypes);
  }

  public void deploy(List<File> includedFiles, Map<String,String> configVars, String jdkVersion, Map<String,String> processTypes) throws Exception {
    String realJdkVersion = jdkVersion == null ? getJdkVersion() : jdkVersion;
    if (!jdkUrlStrings.containsKey(realJdkVersion)) throw new IllegalArgumentException("Invalid JDK version: " + realJdkVersion);
    URL jdkUrl = new URL(jdkUrlStrings.get(realJdkVersion));
    deploy(includedFiles, configVars, realJdkVersion, jdkUrl, processTypes);
  }

  public void deploy(List<File> includedFiles, Map<String,String> configVars, URL jdkUrl, Map<String,String> processTypes) throws Exception {
    deploy(includedFiles, configVars, jdkUrl.toString(), jdkUrl, processTypes);
  }

  protected void prepare(List<File> includedFiles, String jdkVersion, URL jdkUrl) throws Exception {
    logInfo("---> Packaging application...");
    logInfo("     - app: " + name);

    try {
      for (File file : includedFiles) {
        logInfo("     - including: ./" + relativize(file));
        if (file.isDirectory()) {
          FileUtils.copyDirectory(file, new File(getAppDir(), relativize(file)));
        } else {
          FileUtils.copyFile(file, new File(getAppDir(), relativize(file)));
        }
      }
    } catch (IOException ioe) {
      throw new Exception("There was an error packaging the application for deployment.", ioe);
    }

    try {
      logInfo("     - installing: " + jdkVersion);
      vendorJdk(jdkUrl);
    } catch (Exception e) {
      throw new Exception("There was an error downloading the JDK.", e);
    }
  }

  public Map<String,String> getConfigVars() throws Exception {
    String urlStr = Slug.BASE_URL + "/apps/" + URLEncoder.encode(name, "UTF-8") + "/config-vars";

    Map<String,String> headers = new HashMap<String,String>();
    headers.put("Authorization", getEncodedApiKey());
    headers.put("Accept", "application/vnd.heroku+json; version=3");

    Map m = Curl.get(urlStr, headers);
    Map<String,String> configVars = new HashMap<String,String>();
    for (Object key : m.keySet()) {
      Object value = m.get(key);
      if ((key instanceof String) && (value instanceof String)) {
        configVars.put(key.toString(), value.toString());
      } else {
        throw new Exception("Unexpected return type: " + m);
      }
    }
    return configVars;
  }

  protected void setConfigVars(Map<String,String> configVars) throws IOException, Curl.CurlException {
    if (!configVars.isEmpty()) {
      String urlStr = Slug.BASE_URL + "/apps/" + URLEncoder.encode(name, "UTF-8") + "/config_vars";

      String data = "{";
      boolean first = true;
      for (String key : configVars.keySet()) {
        String value = configVars.get(key);
        if (!first) data += ", ";
        first = false;
        data += "\"" + key + "\"" + ":" + "\"" + sanitizeJson(value) + "\"";
      }
      data +=  "}";

      Map<String,String> headers = new HashMap<String,String>();
      headers.put("Authorization", getEncodedApiKey());
      headers.put("Accept", "application/json");

      Curl.put(urlStr, data, headers);
    }
  }

  protected Slug deploySlug(Map<String,String> processTypes) throws IOException, Curl.CurlException, ArchiveException, InterruptedException {
    Map<String,String> allProcessTypes = getProcfile();
    allProcessTypes.putAll(processTypes);
    if (allProcessTypes.isEmpty()) logWarn("No processTypes specified!");

    Slug slug = new Slug(buildPackDesc, name, getEncodedApiKey(), allProcessTypes);
    logDebug("Heroku Slug request: " + slug.getSlugRequest());

    logInfo("---> Creating slug...");
    File slugFile = Tar.create("slug", "./app", getHerokuDir());
    logInfo("     - file: ./" + relativize(slugFile));
    logInfo("     - size: " + (slugFile.length() / (1024 * 1024)) + "MB");

    Map slugResponse = slug.create();
    logDebug("Heroku Slug response: " + slugResponse);
    logDebug("Heroku Blob URL: " + slug.getBlobUrl());
    logDebug("Heroku Slug Id: " + slug.getSlugId());

    logInfo("---> Uploading slug...");
    slug.upload(slugFile);
    logInfo("     - stack: " + slug.getStackName());
    logInfo("     - process types: " + ((Map) slugResponse.get("process_types")).keySet());

    logInfo("---> Releasing...");
    Map releaseResponse = slug.release();
    logDebug("Heroku Release response: " + releaseResponse);
    logInfo("     - version: " + releaseResponse.get("version"));

    return slug;
  }

  protected String getJdkVersion() {
    String defaultJdkVersion = "1.8";
    File sysPropsFile = new File(rootDir, "system.properties");
    if (sysPropsFile.exists()) {
      Properties props = new Properties();
      try {
        props.load(new FileInputStream(sysPropsFile));
        return props.getProperty("java.runtime.version", defaultJdkVersion);
      } catch (IOException e) {
        logDebug(e.getMessage());
      }
    }
    return defaultJdkVersion;
  }

  protected Map<String,String> getProcfile() {
    Map<String,String> procTypes = new HashMap<String, String>();

    File procfile = new File(rootDir, "Procfile");
    if (procfile.exists()) {
      try {
        BufferedReader reader = new BufferedReader(new FileReader(procfile));
        String line = reader.readLine();
        while (line != null) {
          Integer colon = line.indexOf(":");
          String key = line.substring(0, colon);
          String value = line.substring(colon + 1);
          procTypes.put(key.trim(), value.trim());

          line = reader.readLine();
        }
      } catch (Exception e) {
        logDebug(e.getMessage());
      }
    }

    return procTypes;
  }

  private void vendorJdk(URL jdkUrl) throws IOException, InterruptedException, ArchiveException {
    File jdkHome = new File(getAppDir(), ".jdk");
    jdkHome.mkdir();

    File jdkTgz = new File(getHerokuDir(), "jdk-pkg.tar.gz");
    FileUtils.copyURLToFile(jdkUrl, jdkTgz);

    Tar.extract(jdkTgz, jdkHome);
  }

  protected String relativize(File path) {
    return rootDir.toURI().relativize(path.toURI()).getPath();
  }

  private String getEncodedApiKey() throws IOException {
    if (encodedApiKey == null) {
      String apiKey = System.getenv("HEROKU_API_KEY");
      if (null == apiKey || apiKey.equals("")) {
        ProcessBuilder pb = new ProcessBuilder().command("heroku", "auth:token");
        Process p = pb.start();

        BufferedReader bri = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        apiKey = "";
        while ((line = bri.readLine()) != null) {
          apiKey += line;
        }
      }
      encodedApiKey = new BASE64Encoder().encode((":" + apiKey).getBytes());
    }
    return encodedApiKey;
  }

  private Map<String,String> addConfigVar(String key, String value, Map<String,String> existingConfigVars) {
    return addConfigVar(key, value, existingConfigVars, false);
  }

  private Map<String,String> addConfigVar(String key, String value, Map<String,String> existingConfigVars, Boolean force) {
    Map<String,String> m = new HashMap<String,String>();
    if (!existingConfigVars.containsKey(key) || (!value.equals(existingConfigVars.get(key)) && force)) {
      m.put(key, value);
    }
    return m;
  }

  protected File getAppDir() {
    return new File(getHerokuDir(), "app");
  }

  protected File getHerokuDir() {
    return new File(targetDir, "heroku");
  }

  protected File getRootDir() {
    return rootDir;
  }

  protected String sanitizeJson(String json) {
    return json.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static File createTempDir() throws IOException {
    return Files.createTempDirectory("heroku-deploy").toFile();
  }
}
