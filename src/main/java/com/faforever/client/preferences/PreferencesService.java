package com.faforever.client.preferences;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.faforever.client.config.ClientProperties;
import com.faforever.client.fx.JavaFxUtil;
import com.faforever.client.preferences.gson.ColorTypeAdapter;
import com.faforever.client.preferences.gson.ExcludeFieldsWithExcludeAnnotationStrategy;
import com.faforever.client.preferences.gson.PathTypeAdapter;
import com.faforever.client.preferences.gson.PropertyTypeAdapter;
import com.faforever.client.remote.gson.FactionTypeAdapter;
import com.faforever.client.update.ClientConfiguration;
import com.faforever.client.util.Assert;
import com.faforever.commons.api.dto.Faction;
import com.github.nocatch.NoCatch.NoCatchRunnable;
import com.github.nocatch.NoCatchException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.ShlObj;
import javafx.beans.property.Property;
import javafx.collections.ObservableMap;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.paint.Color;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.github.nocatch.NoCatch.noCatch;

@Slf4j
@Lazy
@Service
public class PreferencesService implements InitializingBean {

  public static final String SUPREME_COMMANDER_EXE = "SupremeCommander.exe";
  public static final String FORGED_ALLIANCE_EXE = "ForgedAlliance.exe";

  /**
   * Points to the FAF data directory where log files, config files and others are held. The returned value varies
   * depending on the operating system.
   */
  protected static final Path FAF_DATA_DIRECTORY;
  private static final long STORE_DELAY = 1000;
  private static final Charset CHARSET = StandardCharsets.UTF_8;
  private static final String PREFS_FILE_NAME = "client.prefs";
  private static final String APP_DATA_SUB_FOLDER = "Forged Alliance Forever";
  private static final String USER_HOME_SUB_FOLDER = ".faforever";
  private static final String REPLAYS_SUB_FOLDER = "replays";
  private static final String CORRUPTED_REPLAYS_SUB_FOLDER = "corrupt";
  private static final String CACHE_SUB_FOLDER = "cache";
  private static final String FEATURED_MOD_CACHE_SUB_FOLDER = "featured_mod";
  private static final String CACHE_STYLESHEETS_SUB_FOLDER = Paths.get(CACHE_SUB_FOLDER, "stylesheets").toString();
  private static final Path CACHE_DIRECTORY;
  private static final Pattern GAME_LOG_PATTERN = Pattern.compile("game(_\\d*)?.log");
  private static final int NUMBER_GAME_LOGS_STORED = 10;
  private static final Path FEATURED_MOD_CACHE_PATH;

  static {
    if (org.bridj.Platform.isWindows()) {
      FAF_DATA_DIRECTORY = Paths.get(Shell32Util.getFolderPath(ShlObj.CSIDL_COMMON_APPDATA), "FAForever");
    } else {
      FAF_DATA_DIRECTORY = Paths.get(System.getProperty("user.home")).resolve(USER_HOME_SUB_FOLDER);
    }
    CACHE_DIRECTORY = FAF_DATA_DIRECTORY.resolve(CACHE_SUB_FOLDER);
    FEATURED_MOD_CACHE_PATH = CACHE_DIRECTORY.resolve(FEATURED_MOD_CACHE_SUB_FOLDER);

    System.setProperty("logging.file.name", PreferencesService.FAF_DATA_DIRECTORY
        .resolve("logs")
        .resolve("client.log")
        .toString());
    // duplicated, see getFafLogDirectory; make getFafLogDirectory or log dir static?

    System.setProperty("ICE_ADVANCED_LOG", PreferencesService.FAF_DATA_DIRECTORY
        .resolve("logs/iceAdapterLogs")
        .resolve("advanced-ice-adapter.log")
        .toString());
    // duplicated, see getIceAdapterLogDirectory; make getIceAdapterLogDirectory or ice log dir static?

    System.setProperty("MAP_GENERATOR_LOG", PreferencesService.FAF_DATA_DIRECTORY
        .resolve("logs")
        .resolve("map-generator.log")
        .toString());
    // duplicated, see getIRCLogDirectory; make getIRCLogDirectory or ice log dir static?

    System.setProperty("IRC_LOG", PreferencesService.FAF_DATA_DIRECTORY
        .resolve("logs")
        .resolve("irc.log")
        .toString());

    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();
  }

  private final Path preferencesFilePath;
  private final Gson gson;
  /**
   * @see #storeInBackground()
   */
  private final Timer timer;
  private final Collection<WeakReference<PreferenceUpdateListener>> updateListeners;
  private final ClientProperties clientProperties;
  private ClientConfiguration clientConfiguration;

  private Preferences preferences;
  private TimerTask storeInBackgroundTask;

  public PreferencesService(ClientProperties clientProperties) {
    this.clientProperties = clientProperties;
    updateListeners = new ArrayList<>();
    this.preferencesFilePath = getPreferencesDirectory().resolve(PREFS_FILE_NAME);
    timer = new Timer("PrefTimer", true);
    gson = new GsonBuilder()
        .setPrettyPrinting()
        .addDeserializationExclusionStrategy(new ExcludeFieldsWithExcludeAnnotationStrategy())
        .addSerializationExclusionStrategy(new ExcludeFieldsWithExcludeAnnotationStrategy())
        .registerTypeHierarchyAdapter(Property.class, PropertyTypeAdapter.INSTANCE)
        .registerTypeHierarchyAdapter(Path.class, PathTypeAdapter.INSTANCE)
        .registerTypeAdapter(Color.class, new ColorTypeAdapter())
        .registerTypeAdapter(Faction.class, FactionTypeAdapter.INSTANCE)
        .registerTypeAdapter(ObservableMap.class, FactionTypeAdapter.INSTANCE)
        .create();
  }

  public Path getPreferencesDirectory() {
    if (org.bridj.Platform.isWindows()) {
      return Paths.get(System.getenv("APPDATA")).resolve(APP_DATA_SUB_FOLDER);
    }
    return Paths.get(System.getProperty("user.home")).resolve(USER_HOME_SUB_FOLDER);
  }

  @Override
  public void afterPropertiesSet() throws IOException {
    if (Files.exists(preferencesFilePath)) {
      if (!deleteFileIfEmpty()) {
        readExistingFile(preferencesFilePath);
      }
    } else {
      preferences = new Preferences();
    }

    setLoggingLevel();
    JavaFxUtil.addListener(preferences.debugLogEnabledProperty(), (observable, oldValue, newValue) -> setLoggingLevel());

    Path gamePrefs = preferences.getForgedAlliance().getPreferencesFile();
    if (Files.notExists(gamePrefs)) {
      log.info("Initializing game preferences file: {}", gamePrefs);
      Files.createDirectories(gamePrefs.getParent());
      Files.copy(getClass().getResourceAsStream("/game.prefs"), gamePrefs);
    }
  }

  /**
   * Sometimes, old preferences values are renamed or moved. The purpose of this method is to temporarily perform such
   * migrations.
   */
  private void migratePreferences(Preferences preferences) {
    if (preferences.getForgedAlliance().getPath() != null) {
      preferences.getForgedAlliance().setInstallationPath(preferences.getForgedAlliance().getPath());
      preferences.getForgedAlliance().setPath(null);
    }
    storeInBackground();
  }

  public static void configureLogging() {
    // Calling this method causes the class to be initialized (static initializers) which in turn causes the logger to initialize.
    log.debug("Logger initialized");
  }


  /**
   * It may happen that the file is empty when the process is forcibly killed, so remove the file if that happened.
   *
   * @return true if the file was deleted
   */
  private boolean deleteFileIfEmpty() throws IOException {
    if (Files.size(preferencesFilePath) == 0) {
      Files.delete(preferencesFilePath);
      preferences = new Preferences();
      return true;
    }
    return false;
  }

  public Path getFafBinDirectory() {
    return getFafDataDirectory().resolve("bin");
  }

  public Path getFafDataDirectory() {
    return FAF_DATA_DIRECTORY;
  }

  public Path getIceAdapterLogDirectory() {
    return getFafLogDirectory().resolve("iceAdapterLogs");
  }

  /**
   * This is where FAF stores maps and mods. This avoids writing to the My Documents folder on Windows
   */
  public Path getFAFVaultLocation() {
    return ForgedAlliancePrefs.FAF_VAULT_PATH;
  }

  public Path getGPGVaultLocation() {
    return ForgedAlliancePrefs.GPG_VAULT_PATH;
  }

  public Path getPatchReposDirectory() {
    return getFafDataDirectory().resolve("repos");
  }

  private void readExistingFile(Path path) {
    Assert.checkNotNullIllegalState(preferences, "Preferences have already been initialized");

    try (Reader reader = Files.newBufferedReader(path, CHARSET)) {
      log.debug("Reading preferences file {}", preferencesFilePath.toAbsolutePath());
      preferences = gson.fromJson(reader, Preferences.class);
      migratePreferences(preferences);
    } catch (Exception e) {
      log.warn("Preferences file " + path.toAbsolutePath() + " could not be read", e);
      CountDownLatch waitForUser = new CountDownLatch(1);
      JavaFxUtil.runLater(() -> {
        Alert errorReading = new Alert(AlertType.ERROR, "Error reading setting. Reset settings? ", ButtonType.YES, ButtonType.CANCEL);
        errorReading.showAndWait();

        if (errorReading.getResult() == ButtonType.YES) {
          try {
            Files.delete(path);
            preferences = new Preferences();
            waitForUser.countDown();
          } catch (Exception ex) {
            log.error("Error deleting settings file", ex);
            Alert errorDeleting = new Alert(AlertType.ERROR, MessageFormat.format("Error deleting setting. Please delete them yourself. You find them under {} .", preferencesFilePath.toAbsolutePath()), ButtonType.OK);
            errorDeleting.showAndWait();
            preferences = new Preferences();
            waitForUser.countDown();
          }
        }
      });
      noCatch((NoCatchRunnable) waitForUser::await);

    }

    if (preferences != null) {
      preferences.getForgedAlliance().bindVaultPath();
    }
  }

  public Preferences getPreferences() {
    return preferences;
  }

  public void store() {
    Path parent = preferencesFilePath.getParent();
    try {
      if (!Files.exists(parent)) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      log.warn("Could not create directory " + parent.toAbsolutePath(), e);
      return;
    }

    try (Writer writer = Files.newBufferedWriter(preferencesFilePath, CHARSET)) {
      log.debug("Writing preferences file {}", preferencesFilePath.toAbsolutePath());
      gson.toJson(preferences, writer);
    } catch (IOException e) {
      log.warn("Preferences file " + preferencesFilePath.toAbsolutePath() + " could not be written", e);
    }
  }

  /**
   * Stores the preferences in background, with a delay of {@link #STORE_DELAY}. Each subsequent call to this method
   * during that delay causes the delay to be reset. This ensures that the prefs file is written only once if multiple
   * calls occur within a short time.
   */
  public void storeInBackground() {
    if (storeInBackgroundTask != null) {
      storeInBackgroundTask.cancel();
    }

    storeInBackgroundTask = new TimerTask() {
      @Override
      public void run() {
        store();
        ArrayList<WeakReference<PreferenceUpdateListener>> toBeRemoved = new ArrayList<>();
        for (WeakReference<PreferenceUpdateListener> updateListener : updateListeners) {
          PreferenceUpdateListener preferenceUpdateListener = updateListener.get();
          if (preferenceUpdateListener == null) {
            toBeRemoved.add(updateListener);
            continue;
          }
          preferenceUpdateListener.onPreferencesUpdated(preferences);
        }

        for (WeakReference<PreferenceUpdateListener> preferenceUpdateListenerWeakReference : toBeRemoved) {
          updateListeners.remove(preferenceUpdateListenerWeakReference);
        }
      }
    };

    timer.schedule(storeInBackgroundTask, STORE_DELAY);
  }

  /**
   * Adds a listener to be notified whenever the preferences have been updated (that is, stored to file).
   */
  public void addUpdateListener(WeakReference<PreferenceUpdateListener> listener) {
    updateListeners.add(listener);
  }

  public Path getCorruptedReplaysDirectory() {
    return getReplaysDirectory().resolve(CORRUPTED_REPLAYS_SUB_FOLDER);
  }

  public Path getReplaysDirectory() {
    return getFafDataDirectory().resolve(REPLAYS_SUB_FOLDER);
  }

  public Path getCacheDirectory() {
    return CACHE_DIRECTORY;
  }

  public Path getFeaturedModCachePath() {
    return FEATURED_MOD_CACHE_PATH;
  }

  public Path getFafLogDirectory() {
    return getFafDataDirectory().resolve("logs");
  }

  public Path getThemesDirectory() {
    return getFafDataDirectory().resolve("themes");
  }

  public Path getNewGameLogFile(int gameUID) {
    try (Stream<Path> listOfLogFiles = Files.list(getFafLogDirectory())) {
      listOfLogFiles
          .filter(p -> GAME_LOG_PATTERN.matcher(p.getFileName().toString()).matches())
          .sorted(Comparator.comparingLong(p -> ((Path) p).toFile().lastModified()).reversed())
          .skip(NUMBER_GAME_LOGS_STORED - 1)
          .forEach(p -> noCatch(() -> Files.delete(p)));
    } catch (IOException e) {
      log.error("Could not list log directory.", e);
    } catch (NoCatchException e) {
      log.error("Could not delete game log file");
    }
    return getFafLogDirectory().resolve(String.format("game_%d.log", gameUID));
  }

  public Optional<Path> getMostRecentGameLogFile() {
    try (Stream<Path> listOfLogFiles = Files.list(getFafLogDirectory())) {
      return listOfLogFiles
          .filter(p -> GAME_LOG_PATTERN.matcher(p.getFileName().toString()).matches()).max(Comparator.comparingLong(p -> p.toFile().lastModified()));
    } catch (IOException e) {
      log.error("Could not list log directory.", e);
    } catch (NoCatchException e) {
      log.error("Could not delete game log file");
    }
    return Optional.empty();
  }

  @SneakyThrows
  public boolean isGamePathValid() {
    return isGamePathValidWithError(preferences.getForgedAlliance().getInstallationPath()) == null;
  }

  public String isGamePathValidWithError(Path installationPath) throws IOException, NoSuchAlgorithmException {
    boolean valid = installationPath != null && isGamePathValid(installationPath.resolve("bin"));
    if (!valid) {
      return "gamePath.select.noValidExe";
    }
    Path binPath = installationPath.resolve("bin");
    String exeHash;
    if (Files.exists(binPath.resolve(FORGED_ALLIANCE_EXE))) {
      exeHash = sha256OfFile(binPath.resolve(FORGED_ALLIANCE_EXE));
    } else {
      exeHash = sha256OfFile(binPath.resolve(SUPREME_COMMANDER_EXE));
    }
    for (String hash : clientProperties.getVanillaGameHashes()) {
      log.debug("Hash of Supreme Commander.exe in selected User directory: " + exeHash);
      if (hash.equals(exeHash)) {
        return "gamePath.select.vanillaGameSelected";
      }
    }

    if (binPath.equals(getFafBinDirectory())) {
      return "gamePath.select.fafDataSelected";
    }

    return null;
  }

  private String sha256OfFile(Path path) throws IOException, NoSuchAlgorithmException {
    byte[] buffer = new byte[4096];
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()));
    DigestInputStream digestInputStream = new DigestInputStream(bis, digest);
    while (digestInputStream.read(buffer) > -1) {
    }
    digest = digestInputStream.getMessageDigest();
    digestInputStream.close();
    byte[] sha256 = digest.digest();
    StringBuilder sb = new StringBuilder();
    for (byte b : sha256) {
      sb.append(String.format("%02X", b));
    }
    return sb.toString().toUpperCase();
  }

  public boolean isGamePathValid(Path binPath) {
    return binPath != null
        && (Files.isRegularFile(binPath.resolve(FORGED_ALLIANCE_EXE))
        || Files.isRegularFile(binPath.resolve(SUPREME_COMMANDER_EXE))
    );
  }

  public Path getCacheStylesheetsDirectory() {
    return getFafDataDirectory().resolve(CACHE_STYLESHEETS_SUB_FOLDER);
  }

  public Path getLanguagesDirectory() {
    return getFafDataDirectory().resolve("languages");
  }

  public ClientConfiguration getRemotePreferences() throws IOException {
    if (clientConfiguration != null) {
      return clientConfiguration;
    }

    URL url = new URL(clientProperties.getClientConfigUrl());
    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    urlConnection.setConnectTimeout((int) clientProperties.getClientConfigConnectTimeout().toMillis());

    try (Reader reader = new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8)) {
      clientConfiguration = gson.fromJson(reader, ClientConfiguration.class);
      return clientConfiguration;
    }
  }


  public CompletableFuture<ClientConfiguration> getRemotePreferencesAsync() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return getRemotePreferences();
      } catch (IOException e) {
        throw new CompletionException(e);
      }
    });
  }

  public void setLoggingLevel() {
    storeInBackground();
    Level targetLogLevel = preferences.isDebugLogEnabled() ? Level.DEBUG : Level.INFO;
    final LoggerContext loggerContext = ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())).getLoggerContext();
    loggerContext.getLoggerList()
        .stream()
        .filter(logger -> logger.getName().startsWith("com.faforever"))
        .forEach(logger -> ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logger.getName())).setLevel(targetLogLevel));

    log.info("Switching FA Forever logging configuration to {}", targetLogLevel.levelStr);
    if (targetLogLevel == Level.DEBUG) {
      log.debug("Confirming debug logging");
    }
  }
}
