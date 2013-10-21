/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.sdk;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetConfiguration;
import com.intellij.facet.FacetManager;
import com.intellij.ide.DataManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileSystemUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.CharFilter;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiElement;
import com.intellij.remotesdk.RemoteSdkData;
import com.intellij.remotesdk.RemoteSdkDataHolder;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.NullableConsumer;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.PythonHelpersLocator;
import com.jetbrains.python.codeInsight.userSkeletons.PyUserSkeletonsUtil;
import com.jetbrains.python.facet.PythonFacetSettings;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.search.PyProjectScopeBuilder;
import com.jetbrains.python.remote.PythonRemoteInterpreterManager;
import com.jetbrains.python.sdk.flavors.CPythonSdkFlavor;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;
import com.jetbrains.python.sdk.skeletons.PySkeletonRefresher;
import icons.PythonIcons;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author yole
 */
public class PythonSdkType extends SdkType {
  private static final Logger LOG = Logger.getInstance("#" + PythonSdkType.class.getName());
  private static final String[] WINDOWS_EXECUTABLE_SUFFIXES = new String[]{"cmd", "exe", "bat", "com"};

  static final int MINUTE = 60 * 1000; // 60 seconds, used with script timeouts
  @NonNls public static final String SKELETONS_TOPIC = "Skeletons";
  private static final String[] DIRS_WITH_BINARY = new String[]{"", "bin", "Scripts"};
  private static final String[] UNIX_BINARY_NAMES = new String[]{"jython", "pypy", "python"};
  private static final String[] WIN_BINARY_NAMES = new String[]{"jython.bat", "ipy.exe", "pypy.exe", "python.exe"};

  private static final Key<WeakReference<Component>> SDK_CREATOR_COMPONENT_KEY = Key.create("#com.jetbrains.python.sdk.creatorComponent");

  public static PythonSdkType getInstance() {
    return SdkType.findInstance(PythonSdkType.class);
  }

  public PythonSdkType() {
    super("Python SDK");
  }

  protected PythonSdkType(@NonNls String name) {
    super(name);
  }

  public Icon getIcon() {
    return PythonIcons.Python.Python;
  }

  @NotNull
  @Override
  public String getHelpTopic() {
    return "reference.project.structure.sdk.python";
  }

  public Icon getIconForAddAction() {
    return PythonFileType.INSTANCE.getIcon();
  }

  /**
   * Name of directory where skeleton files (despite the value) are stored.
   */
  public static final String SKELETON_DIR_NAME = "python_stubs";

  /**
   * @return name of builtins skeleton file; for Python 2.x it is '{@code __builtins__.py}'.
   */
  @NotNull
  @NonNls
  public static String getBuiltinsFileName(Sdk sdk) {
    final String version = sdk.getVersionString();
    if (version != null && version.startsWith("Python 3")) {
      return PyBuiltinCache.BUILTIN_FILE_3K;
    }
    return PyBuiltinCache.BUILTIN_FILE;
  }

  @NonNls
  @Nullable
  public String suggestHomePath() {
    for (PythonSdkFlavor flavor : PythonSdkFlavor.getApplicableFlavors()) {
      TreeSet<String> candidates = createVersionSet();
      candidates.addAll(flavor.suggestHomePaths());
      if (!candidates.isEmpty()) {
        // return latest version
        String[] candidateArray = ArrayUtil.toStringArray(candidates);
        return candidateArray[candidateArray.length - 1];
      }
    }
    return null;
  }

  @Override
  public Collection<String> suggestHomePaths() {
    List<String> candidates = new ArrayList<String>();
    for (PythonSdkFlavor flavor : PythonSdkFlavor.getApplicableFlavors()) {
      candidates.addAll(flavor.suggestHomePaths());
    }
    return candidates;
  }

  private static TreeSet<String> createVersionSet() {
    return new TreeSet<String>(new Comparator<String>() {
      public int compare(String o1, String o2) {
        return findDigits(o1).compareTo(findDigits(o2));
      }
    });
  }

  private static String findDigits(String s) {
    int pos = StringUtil.findFirst(s, new CharFilter() {
      public boolean accept(char ch) {
        return Character.isDigit(ch);
      }
    });
    if (pos >= 0) {
      return s.substring(pos);
    }
    return s;
  }

  public boolean isValidSdkHome(@Nullable final String path) {
    return PythonSdkFlavor.getFlavor(path) != null;
  }

  public static boolean isInvalid(@NotNull Sdk sdk) {
    if (isRemote(sdk)) {
      return false;
    }
    final VirtualFile interpreter = sdk.getHomeDirectory();
    return interpreter == null || !interpreter.exists();
  }

  public static boolean isRemote(@Nullable Sdk sdk) {
    return PySdkUtil.isRemote(sdk);
  }

  public static boolean isRemote(@Nullable String sdkPath) {
    return isRemote(findSdkByPath(sdkPath));
  }

  @Override
  public FileChooserDescriptor getHomeChooserDescriptor() {
    final boolean is_windows = SystemInfo.isWindows;
    FileChooserDescriptor result = new FileChooserDescriptor(true, false, false, false, false, false) {
      public void validateSelectedFiles(VirtualFile[] files) throws Exception {
        if (files.length != 0) {
          if (!isValidSdkHome(files[0].getPath())) {
            throw new Exception(PyBundle.message("sdk.error.invalid.interpreter.name.$0", files[0].getName()));
          }
        }
      }

      @Override
      public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
        // TODO: add a better, customizable filtering
        if (!file.isDirectory()) {
          if (is_windows) {
            String path = file.getPath();
            boolean looks_executable = false;
            for (String ext : WINDOWS_EXECUTABLE_SUFFIXES) {
              if (path.endsWith(ext)) {
                looks_executable = true;
                break;
              }
            }
            return looks_executable && super.isFileVisible(file, showHiddenFiles);
          }
        }
        return super.isFileVisible(file, showHiddenFiles);
      }
    };
    result.setTitle(PyBundle.message("sdk.select.path"));
    return result;
  }

  public boolean supportsCustomCreateUI() {
    return true;
  }

  public void showCustomCreateUI(SdkModel sdkModel, final JComponent parentComponent, final Consumer<Sdk> sdkCreatedCallback) {
    Project project = PlatformDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent));
    InterpreterPathChooser.show(project, sdkModel.getSdks(), RelativePoint.getCenterOf(parentComponent), true, new NullableConsumer<Sdk>() {
      @Override
      public void consume(@Nullable Sdk sdk) {
        if (sdk != null) {
          sdk.putUserData(SDK_CREATOR_COMPONENT_KEY, new WeakReference<Component>(parentComponent));
          sdkCreatedCallback.consume(sdk);
        }
      }
    });
  }

  public static boolean isVirtualEnv(Sdk sdk) {
    final String path = sdk.getHomePath();
    return path != null && getVirtualEnvRoot(path) != null;
  }

  @Nullable
  public Sdk getVirtualEnvBaseSdk(Sdk sdk) {
    if (isVirtualEnv(sdk)) {
      final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdk);
      final String version = getVersionString(sdk);
      if (flavor != null && version != null) {
        for (Sdk baseSdk : getAllSdks()) {
          final PythonSdkFlavor baseFlavor = PythonSdkFlavor.getFlavor(baseSdk);
          if (!isVirtualEnv(baseSdk) && flavor.equals(baseFlavor) && version.equals(getVersionString(baseSdk))) {
            return baseSdk;
          }
        }
      }
    }
    return null;
  }

  /**
   * @param binaryPath must point to a Python interpreter
   * @return if the surroundings look like a virtualenv installation, its root is returned (normally the grandparent of binaryPath).
   */
  @Nullable
  public static File getVirtualEnvRoot(@NotNull final String binaryPath) {
    final File bin = new File(binaryPath).getParentFile();
    if (bin != null) {
      final String rootPath = bin.getParent();
      if (rootPath != null) {
        final File root = new File(rootPath);
        final File activateThis = new File(bin, "activate_this.py");
        // binaryPath should contain an 'activate' script, and root should have bin (with us) and include and libp
        if (activateThis.exists()) {
          final File activate = findExecutableFile(bin, "activate");
          if (activate != null) {
            return root;
          }
        }
        // Python 3.3 virtualenvs can be found as described in PEP 405
        final String pyVenvCfg = "pyvenv.cfg";
        if (new File(root, pyVenvCfg).exists() || new File(bin, pyVenvCfg).exists()) {
          return root;
        }
      }
    }
    return null;
  }

  /**
   * Finds a file that looks executable: an .exe or .cmd under windows, plain file under *nix.
   *
   * @param parent directory to look at
   * @param name   name of the executable without suffix
   * @return File representing the executable, or null.
   */
  @Nullable
  public static File findExecutableFile(File parent, String name) {
    if (SystemInfo.isWindows || SystemInfo.isOS2) {
      for (String suffix : WINDOWS_EXECUTABLE_SUFFIXES) {
        File file = new File(parent, name + "." + suffix);
        if (file.exists()) return file;
      }
    }
    else if (SystemInfo.isUnix) {
      File file = new File(parent, name);
      if (file.exists()) return file;
    }
    return null;
  }

  /**
   * Alters PATH so that a virtualenv is activated, if present.
   *
   * @param commandLine           what to patch
   * @param sdkHome               home of SDK we're using
   * @param passParentEnvironment iff true, include system paths in PATH
   */
  public static void patchCommandLineForVirtualenv(GeneralCommandLine commandLine, String sdkHome, boolean passParentEnvironment) {
    File virtualEnvRoot = getVirtualEnvRoot(sdkHome);
    if (virtualEnvRoot != null) {
      @NonNls final String PATH = "PATH";

      // prepend virtualenv bin if it's not already on PATH
      File bin = new File(virtualEnvRoot, "bin");
      if (!bin.exists()) {
        bin = new File(virtualEnvRoot, "Scripts");   // on Windows
      }
      String virtualenvBin = bin.getPath();

      Map<String, String> env = commandLine.getEnvironment();
      String pathValue;
      if (env.containsKey(PATH)) {
        pathValue = PythonEnvUtil.appendToPathEnvVar(env.get(PATH), virtualenvBin);
      }
      else if (passParentEnvironment) {
        // append to PATH
        pathValue = PythonEnvUtil.appendToPathEnvVar(System.getenv(PATH), virtualenvBin);
      }
      else {
        pathValue = virtualenvBin;
      }
      env.put(PATH, pathValue);
    }
  }

  public String suggestSdkName(final String currentSdkName, final String sdkHome) {
    String name = getVersionString(sdkHome);
    return suggestSdkNameFromVersion(sdkHome, name);
  }

  public static String suggestSdkNameFromVersion(String sdkHome, String version) {
    final String short_home_name = FileUtil.getLocationRelativeToUserHome(sdkHome);
    if (version != null) {
      File virtualenv_root = getVirtualEnvRoot(sdkHome);
      if (virtualenv_root != null) {
        version += " virtualenv at " + FileUtil.getLocationRelativeToUserHome(virtualenv_root.getAbsolutePath());
      }
      else {
        version += " (" + short_home_name + ")";
      }
    }
    else {
      version = "Unknown at " + short_home_name;
    } // last resort
    return version;
  }

  @Nullable
  public AdditionalDataConfigurable createAdditionalDataConfigurable(final SdkModel sdkModel, final SdkModificator sdkModificator) {
    return null;
  }

  public void saveAdditionalData(final SdkAdditionalData additionalData, final Element additional) {
    if (additionalData instanceof PythonSdkAdditionalData) {
      ((PythonSdkAdditionalData)additionalData).save(additional);
    }
  }

  @Override
  public SdkAdditionalData loadAdditionalData(final Sdk currentSdk, final Element additional) {
    if (RemoteSdkDataHolder.isRemoteSdk(currentSdk.getHomePath())) {
      PythonRemoteInterpreterManager manager = PythonRemoteInterpreterManager.getInstance();
      if (manager != null) {
        return manager.loadRemoteSdkData(currentSdk, additional);
      }
    }
    return PythonSdkAdditionalData.load(currentSdk, additional);
  }

  private boolean switchPathToInterpreter(Sdk currentSdk, String... variants) {
    File sdk_file = new File(currentSdk.getHomePath());
    final String sdk_name = currentSdk.getName();
    boolean success = false;
    for (String interpreter : variants) {
      File binary = interpreter.startsWith("/") ? new File(interpreter) : new File(sdk_file, interpreter);
      if (binary.exists()) {
        if (currentSdk instanceof SdkModificator) {
          final SdkModificator sdk_as_modificator = (SdkModificator)currentSdk;
          sdk_as_modificator.setHomePath(binary.getPath());
          sdk_as_modificator.setName(suggestSdkName(currentSdk.getName(), binary.getAbsolutePath()));
          //setupSdkPaths(currentSdk);
          success = true;
          break;
        }
      }
    }
    if (!success) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          Messages.showWarningDialog(
            "Failed to convert Python SDK '" + sdk_name + "'\nplease delete and re-create it",
            "Converting Python SDK"
          );
        }
      }, ModalityState.NON_MODAL);
    }
    return success;
  }

  @Nullable
  public static String findSkeletonsPath(Sdk sdk) {
    final String[] urls = sdk.getRootProvider().getUrls(BUILTIN_ROOT_TYPE);
    for (String url : urls) {
      if (url.contains(SKELETON_DIR_NAME)) {
        return VfsUtil.urlToPath(url);
      }
    }
    return null;
  }

  @Nullable
  public static VirtualFile findSkeletonsDir(@NotNull final Sdk sdk) {
    final VirtualFile[] virtualFiles = sdk.getRootProvider().getFiles(BUILTIN_ROOT_TYPE);
    for (VirtualFile virtualFile : virtualFiles) {
      if (virtualFile.isValid() && virtualFile.getPath().contains(SKELETON_DIR_NAME)) {
        return virtualFile;
      }
    }
    return null;
  }

  @NonNls
  public String getPresentableName() {
    return "Python SDK";
  }

  @Override
  public String sdkPath(VirtualFile homePath) {
    String path = super.sdkPath(homePath);
    PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(path);
    if (flavor != null) {
      VirtualFile sdkPath = flavor.getSdkPath(homePath);
      if (sdkPath != null) {
        return sdkPath.getPath();
      }
    }
    return path;
  }

  public void setupSdkPaths(@NotNull final Sdk sdk) {
    final Project project;
    Component ownerComponent = null;
    final WeakReference<Component> ownerComponentRef = sdk.getUserData(SDK_CREATOR_COMPONENT_KEY);
    if (ownerComponentRef != null) {
      ownerComponent = ownerComponentRef.get();
    }
    if (ownerComponent != null) {
      project = PlatformDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(ownerComponent));
    }
    else {
      project = PlatformDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext());
    }
    setupSdkPaths(sdk, project, ownerComponent);
  }

  public static void setupSdkPaths(Sdk sdk, @Nullable Project project, @Nullable Component ownerComponent) {
    final SdkModificator sdkModificator = sdk.getSdkModificator();
    final boolean success = setupSdkPaths(project, ownerComponent, sdk, sdkModificator);
    if (success) {
      sdkModificator.commitChanges();
    }
    else {
      Messages.showErrorDialog(
        project,
        PyBundle.message("MSG.cant.setup.sdk.$0", FileUtil.toSystemDependentName(sdk.getSdkModificator().getHomePath())),
        PyBundle.message("MSG.title.bad.sdk")
      );
    }
  }

  public static boolean setupSdkPaths(@Nullable final Project project,
                                      @Nullable final Component ownerComponent,
                                      @NotNull final Sdk sdk,
                                      @NotNull final SdkModificator sdkModificator) {
    if (isRemote(sdk) && project == null && ownerComponent == null) {
      LOG.error("For refreshing skeletons of remote SDK, either project or owner component must be specified");
    }
    final ProgressManager progressManager = ProgressManager.getInstance();
    final Ref<Boolean> success = new Ref<Boolean>();
    success.set(true);
    final Task.Modal setupTask = new Task.Modal(project, "Setting up library files for " + sdk.getName(), false) {
      // TODO: make this a backgroundable task. see #setupSdkPaths(final Sdk sdk) and its modificator handling
      public void run(@NotNull final ProgressIndicator indicator) {
        sdkModificator.removeAllRoots();
        try {
          updateSdkRootsFromSysPath(sdk, sdkModificator, indicator);
          updateUserAddedPaths(sdk, sdkModificator, indicator);
          if (!ApplicationManager.getApplication().isUnitTestMode()) {
            PySkeletonRefresher.refreshSkeletonsOfSdk(project, ownerComponent,
                                                      getSkeletonsPath(PathManager.getSystemPath(), sdk.getHomePath()),
                                                      null, sdk
            );
            PythonSdkUpdater.getInstance().markAlreadyUpdated(sdk.getHomePath());
          }
        }
        catch (InvalidSdkException e) {
          if (!isInvalid(sdk)) {
            LOG.warn(e);
            final Notification notification = createInvalidSdkNotification(project);
            notification.notify(project);
          }
        }
      }
    };
    progressManager.run(setupTask);
    return success.get();
  }

  @NotNull
  public static Notification createInvalidSdkNotification(@Nullable final Project project) {
    return new Notification("xxx",
                            "Invalid Project Interpreter",
                            "Cannot run the project interpreter. <a href=\"xxx\">Configure...</a>",
                            NotificationType.ERROR,
                            new NotificationListener() {
                              @Override
                              public void hyperlinkUpdate(@NotNull Notification notification,
                                                          @NotNull HyperlinkEvent event) {
                                final ShowSettingsUtil settings = ShowSettingsUtil.getInstance();
                                settings.showSettingsDialog(project, "Project Interpreter");
                                notification.expire();
                              }
                            });
  }

  /**
   * In which root type built-in skeletons are put.
   */
  public static final OrderRootType BUILTIN_ROOT_TYPE = OrderRootType.CLASSES;

  private final static Pattern PYTHON_NN_RE = Pattern.compile("python\\d\\.\\d.*");

  public static void updateSdkRootsFromSysPath(Sdk sdk, SdkModificator sdkModificator, ProgressIndicator indicator)
    throws InvalidSdkException {
    Application application = ApplicationManager.getApplication();
    boolean not_in_unit_test_mode = (application != null && !application.isUnitTestMode());

    String bin_path = sdkModificator.getHomePath();
    assert bin_path != null;
    final String sep = File.separator;
    // we have a number of lib dirs, those listed in python's sys.path
    if (indicator != null) {
      indicator.setText("Adding library roots");
    }
    // Add folders from sys.path
    if (!PySdkUtil.isRemote(sdk)) { //no sense to add roots of remote sdk
      final List<String> paths = getSysPath(bin_path);
      if (paths.size() > 0) {
        // add every path as root.
        for (String path : paths) {
          if (!path.contains(sep)) continue; // TODO: interpret possible 'special' paths reasonably
          if (indicator != null) {
            indicator.setText2(path);
          }
          addSdkRoot(sdkModificator, path);
        }
      }
    }

    PyUserSkeletonsUtil.addUserSkeletonsRoot(sdkModificator);
    addSkeletonsRoot(sdkModificator, bin_path);

    if (not_in_unit_test_mode) {
      File venv_root = getVirtualEnvRoot(bin_path);
      if (venv_root != null && venv_root.isDirectory()) {
        File lib_root = new File(venv_root, "lib");
        if (lib_root.isDirectory()) {
          String[] inside = lib_root.list();
          for (String s : inside) {
            if (PYTHON_NN_RE.matcher(s).matches()) {
              File py_lib_root = new File(lib_root, s);
              String[] flag_files = py_lib_root.list(new FilenameFilter() {
                @Override
                public boolean accept(File file, String s) {
                  return "no-global-site-packages.txt".equals(s);
                }
              });
              if (flag_files != null) return; // don't add hardcoded paths
            }
          }
        }
      }
      addHardcodedPaths(sdkModificator);
    }
  }

  public static void updateUserAddedPaths(Sdk sdk, SdkModificator sdkModificator, ProgressIndicator indicator)
    throws InvalidSdkException {
    if (indicator != null) {
      indicator.setText("Adding user-added roots");
    }
    SdkAdditionalData data = sdk.getSdkAdditionalData();
    if (data instanceof PythonSdkAdditionalData) {
      for (VirtualFile file : ((PythonSdkAdditionalData)data).getAddedPathFiles()) {
        addSdkRoot(sdkModificator, file);
      }
    }
  }

  private static void addSkeletonsRoot(@NotNull SdkModificator sdkModificator, String bin_path) {
    @NonNls final String skeletonsPath = getSkeletonsPath(PathManager.getSystemPath(), bin_path);
    new File(skeletonsPath).mkdirs();
    final VirtualFile builtins_root = LocalFileSystem.getInstance().refreshAndFindFileByPath(skeletonsPath);
    assert builtins_root != null : "Cannot find skeletons path " + skeletonsPath + " in VFS";
    sdkModificator.addRoot(builtins_root, BUILTIN_ROOT_TYPE);
  }

  protected static void addHardcodedPaths(SdkModificator sdkModificator) {
    // Add python-django installed as package in Linux
    // NOTE: fragile and arbitrary
    if (SystemInfo.isLinux) {
      final VirtualFile file = LocalFileSystem.getInstance().findFileByPath("/usr/lib/python-django");
      if (file != null) {
        sdkModificator.addRoot(file, OrderRootType.CLASSES);
      }
    }
  }

  public static void addSdkRoot(SdkModificator sdkModificator, String path) {
    VirtualFile child = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
    if (child != null) {
      addSdkRoot(sdkModificator, child);
    }
    else {
      LOG.info("Bogus sys.path entry " + path);
    }
  }

  private static void addSdkRoot(@NotNull SdkModificator sdkModificator, @NotNull VirtualFile child) {
    @NonNls String suffix = child.getExtension();
    if (suffix != null) suffix = suffix.toLowerCase(); // Why on earth empty suffix is null and not ""?
    VirtualFile toAdd = child;
    if ((!child.isDirectory()) && ("zip".equals(suffix) || "egg".equals(suffix))) {
      // a .zip / .egg file must have its root extracted first
      toAdd = JarFileSystem.getInstance().getJarRootForLocalFile(child);
    }
    if (toAdd != null) {
      // NOTE: Files marked as library sources are not considered part of project source. Since the directory of the project the
      // user is working on is included in PYTHONPATH with many configurations (e.g. virtualenv), we must not mark SDK paths as
      // library sources, only as classes.
      sdkModificator.addRoot(toAdd, OrderRootType.CLASSES);
    }
  }

  public static String getSkeletonsPath(String basePath, String sdkHome) {
    String sep = File.separator;
    return getSkeletonsRootPath(basePath) + sep + FileUtil.toSystemIndependentName(sdkHome).hashCode() + sep;
  }

  public static String getSkeletonsRootPath(String basePath) {
    return basePath + File.separator + SKELETON_DIR_NAME;
  }

  @NotNull
  public static List<String> getSysPath(String bin_path) throws InvalidSdkException {
    String working_dir = new File(bin_path).getParent();
    Application application = ApplicationManager.getApplication();
    if (application != null && !application.isUnitTestMode()) {
      return getSysPathsFromScript(bin_path);
    }
    else { // mock sdk
      List<String> ret = new ArrayList<String>(1);
      ret.add(working_dir);
      return ret;
    }
  }

  @NotNull
  public static List<String> getSysPathsFromScript(String bin_path) throws InvalidSdkException {
    String scriptFile = PythonHelpersLocator.getHelperPath("syspath.py");
    // to handle the situation when PYTHONPATH contains ., we need to run the syspath script in the
    // directory of the script itself - otherwise the dir in which we run the script (e.g. /usr/bin) will be added to SDK path
    String[] add_environment = getVirtualEnvAdditionalEnv(bin_path);
    final ProcessOutput run_result = PySdkUtil.getProcessOutput(
      new File(scriptFile).getParent(),
      new String[]{bin_path, scriptFile},
      add_environment, MINUTE
    );
    if (!run_result.checkSuccess(LOG)) {
      throw new InvalidSdkException(String.format("Failed to determine Python's sys.path value:\nSTDOUT: %s\nSTDERR: %s",
                                                  run_result.getStdout(),
                                                  run_result.getStderr()));
    }
    return run_result.getStdoutLines();
  }

  // Returns a piece of env good as additional env for getProcessOutput.
  @Nullable
  public static String[] getVirtualEnvAdditionalEnv(String bin_path) {
    File virtualenv_root = getVirtualEnvRoot(bin_path);
    String[] add_environment = null;
    if (virtualenv_root != null) {
      add_environment = new String[]{"PATH=" + virtualenv_root + File.pathSeparator};
    }
    return add_environment;
  }

  @Nullable
  public String getVersionString(final String sdkHome) {
    final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdkHome);
    return flavor != null ? flavor.getVersionString(sdkHome) : null;
  }

  public static List<Sdk> getAllSdks() {
    return ProjectJdkTable.getInstance().getSdksOfType(getInstance());
  }

  @Nullable
  public static Sdk findPythonSdk(@Nullable Module module) {
    if (module == null) return null;
    final Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    if (sdk != null && sdk.getSdkType() instanceof PythonSdkType) return sdk;
    final Facet[] facets = FacetManager.getInstance(module).getAllFacets();
    for (Facet facet : facets) {
      final FacetConfiguration configuration = facet.getConfiguration();
      if (configuration instanceof PythonFacetSettings) {
        return ((PythonFacetSettings)configuration).getSdk();
      }
    }
    return null;
  }

  @Nullable
  public static Sdk findSdkByPath(@Nullable String path) {
    if (path != null) {
      return findSdkByPath(getAllSdks(), path);
    }
    return null;
  }

  @Nullable
  public static Sdk findSdkByPath(List<Sdk> sdkList, @Nullable String path) {
    if (path != null) {
      for (Sdk sdk : sdkList) {
        if (sdk != null && FileUtil.pathsEqual(path, sdk.getHomePath())) {
          return sdk;
        }
      }
    }
    return null;
  }

  public static LanguageLevel getLanguageLevelForSdk(@Nullable Sdk sdk) {
    if (sdk != null && sdk.getSdkType() instanceof PythonSdkType) {
      final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdk);
      if (flavor != null) {
        return flavor.getLanguageLevel(sdk);
      }
    }
    return LanguageLevel.getDefault();
  }

  public boolean isRootTypeApplicable(final OrderRootType type) {
    return type == OrderRootType.CLASSES;
  }

  public boolean sdkHasValidPath(@NotNull Sdk sdk) {
    if (PySdkUtil.isRemote(sdk)) {
      return true;
    }
    VirtualFile homeDir = sdk.getHomeDirectory();
    return homeDir != null && homeDir.isValid();
  }

  public static boolean isStdLib(VirtualFile vFile, Sdk pythonSdk) {
    if (pythonSdk != null) {
      final VirtualFile libDir = PyProjectScopeBuilder.findLibDir(pythonSdk);
      if (libDir != null && VfsUtilCore.isAncestor(libDir, vFile, false)) {
        return isNotSitePackages(vFile, libDir);
      }
      final VirtualFile venvLibDir = PyProjectScopeBuilder.findVirtualEnvLibDir(pythonSdk);
      if (venvLibDir != null && VfsUtilCore.isAncestor(venvLibDir, vFile, false)) {
        return isNotSitePackages(vFile, venvLibDir);
      }
      final VirtualFile skeletonsDir = findSkeletonsDir(pythonSdk);
      if (skeletonsDir != null &&
          Comparing.equal(vFile.getParent(), skeletonsDir)) {   // note: this will pick up some of the binary libraries not in packages
        return true;
      }
    }
    return false;
  }

  private static boolean isNotSitePackages(VirtualFile vFile, VirtualFile libDir) {
    final VirtualFile sitePackages = libDir.findChild(PyNames.SITE_PACKAGES);
    if (sitePackages != null && VfsUtilCore.isAncestor(sitePackages, vFile, false)) {
      return false;
    }
    return true;
  }

  @Nullable
  public static Sdk findPython2Sdk(@Nullable Module module) {
    Sdk moduleSDK = findPythonSdk(module);
    if (moduleSDK != null && !getLanguageLevelForSdk(moduleSDK).isPy3K()) {
      return moduleSDK;
    }
    List<Sdk> allSdks = getAllSdks();
    Collections.sort(allSdks, PreferredSdkComparator.INSTANCE);
    for (Sdk sdk : allSdks) {
      if (!getLanguageLevelForSdk(sdk).isPy3K()) {
        return sdk;
      }
    }
    return null;
  }


  @Nullable
  public static Sdk findLocalCPython(@Nullable Module module) {
    Sdk moduleSDK = findPythonSdk(module);
    if (moduleSDK != null && !isRemote(moduleSDK) && PythonSdkFlavor.getFlavor(moduleSDK) instanceof CPythonSdkFlavor) {
      return moduleSDK;
    }
    List<Sdk> allSdks = getAllSdks();
    Collections.sort(allSdks, PreferredSdkComparator.INSTANCE);
    for (Sdk sdk : allSdks) {
      if (!isRemote(sdk)) {
        return sdk;
      }
    }
    return null;
  }

  @Nullable
  public static String getPythonExecutable(@NotNull String rootPath) {
    final File rootFile = new File(rootPath);
    if (rootFile.isFile()) {
      return rootFile.getAbsolutePath();
    }
    for (String dir : DIRS_WITH_BINARY) {
      final File subDir;
      if (StringUtil.isEmpty(dir)) {
        subDir = rootFile;
      }
      else {
        subDir = new File(rootFile, dir);
      }
      if (!subDir.isDirectory()) {
        continue;
      }
      for (String binaryName : getBinaryNames()) {
        final File executable = new File(subDir, binaryName);
        if (executable.isFile()) {
          return executable.getAbsolutePath();
        }
      }
    }
    return null;
  }

  @Nullable
  public static String getExecutablePath(@NotNull final String homeDirectory, @NotNull String name) {
    File binPath = new File(homeDirectory);
    File binDir = binPath.getParentFile();
    if (binDir == null) return null;
    File runner = new File(binDir, name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(binDir, "Scripts"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(binDir.getParentFile(), "Scripts"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(binDir.getParentFile(), "local"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(new File(binDir.getParentFile(), "local"), "bin"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());

    // if interpreter is a symlink
    if (FileSystemUtil.isSymLink(homeDirectory)) {
      String resolvedPath = FileSystemUtil.resolveSymLink(homeDirectory);
      if (resolvedPath != null) {
        return getExecutablePath(resolvedPath, name);
      }
    }
    // Search in standard unix path
    runner = new File(new File("/usr", "bin"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(new File("/usr", "local"), "bin"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    return null;
  }

  @Nullable
  public static String getExecutablePath(@NotNull final Sdk sdk, @NotNull String name) {
    final String homeDirectory = sdk.getHomePath();
    if (homeDirectory == null) return null;
    return getExecutablePath(homeDirectory, name);
  }

  private static String[] getBinaryNames() {
    if (SystemInfo.isUnix) {
      return UNIX_BINARY_NAMES;
    }
    else {
      return WIN_BINARY_NAMES;
    }
  }

  public static boolean isIncompleteRemote(Sdk sdk) {
    if (sdk.getSdkAdditionalData() instanceof RemoteSdkData) {
      //noinspection ConstantConditions
      if (!((RemoteSdkData)sdk.getSdkAdditionalData()).isInitialized()) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  public static Sdk getSdk(@NotNull final PsiElement element) {
    Module module = ModuleUtilCore.findModuleForPsiElement(element);
    if (module == null) {
      return null;
    }
    return ModuleRootManager.getInstance(module).getSdk();
  }
}

