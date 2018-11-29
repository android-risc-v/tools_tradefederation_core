/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.device.cloud;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.AcloudConfigParser.AcloudKeys;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GoogleApiClientUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.Compute.Instances.GetSerialPortOutput;
import com.google.api.services.compute.ComputeScopes;
import com.google.api.services.compute.model.SerialPortOutput;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helper that manages the GCE calls to start/stop and collect logs from GCE. */
public class GceManager {
    private static final long BUGREPORT_TIMEOUT = 15 * 60 * 1000L;
    private static final long REMOTE_FILE_OP_TIMEOUT = 10 * 60 * 1000L;
    private static final Pattern BUGREPORTZ_RESPONSE_PATTERN = Pattern.compile("(OK:)(.*)");
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Arrays.asList(ComputeScopes.COMPUTE_READONLY);

    private DeviceDescriptor mDeviceDescriptor;
    private TestDeviceOptions mDeviceOptions;
    private IBuildInfo mBuildInfo;
    private List<IBuildInfo> mTestResourceBuildInfos;
    private File mGceBootFailureLogCat = null;
    private File mGceBootFailureSerialLog = null;

    private String mGceInstanceName = null;
    private String mGceHost = null;
    private GceAvdInfo mGceAvdInfo = null;

    /**
     * Ctor
     *
     * @param deviceDesc The {@link DeviceDescriptor} that will be associated with the GCE device.
     * @param deviceOptions A {@link TestDeviceOptions} associated with the device.
     * @param buildInfo A {@link IBuildInfo} describing the gce build to start.
     * @param testResourceBuildInfos A list {@link IBuildInfo} describing test resources
     */
    public GceManager(
            DeviceDescriptor deviceDesc,
            TestDeviceOptions deviceOptions,
            IBuildInfo buildInfo,
            List<IBuildInfo> testResourceBuildInfos) {
        mDeviceDescriptor = deviceDesc;
        mDeviceOptions = deviceOptions;
        mBuildInfo = buildInfo;
        mTestResourceBuildInfos = testResourceBuildInfos;
    }

    /**
     * Ctor, variation that can be used to provide the GCE instance name to use directly.
     *
     * @param deviceDesc The {@link DeviceDescriptor} that will be associated with the GCE device.
     * @param deviceOptions A {@link TestDeviceOptions} associated with the device
     * @param buildInfo A {@link IBuildInfo} describing the gce build to start.
     * @param testResourceBuildInfos A list {@link IBuildInfo} describing test resources
     * @param gceInstanceName The instance name to use.
     * @param gceHost The host name or ip of the instance to use.
     */
    public GceManager(
            DeviceDescriptor deviceDesc,
            TestDeviceOptions deviceOptions,
            IBuildInfo buildInfo,
            List<IBuildInfo> testResourceBuildInfos,
            String gceInstanceName,
            String gceHost) {
        this(deviceDesc, deviceOptions, buildInfo, testResourceBuildInfos);
        mGceInstanceName = gceInstanceName;
        mGceHost = gceHost;
    }

    /**
     * Attempt to start a gce instance
     *
     * @return a {@link GceAvdInfo} describing the GCE instance. Could be a BOOT_FAIL instance.
     * @throws TargetSetupError
     */
    public GceAvdInfo startGce() throws TargetSetupError {
        mGceAvdInfo = null;
        // For debugging purposes bypass.
        if (mGceHost != null && mGceInstanceName != null) {
            mGceAvdInfo =
                    new GceAvdInfo(
                            mGceInstanceName,
                            HostAndPort.fromString(mGceHost)
                                    .withDefaultPort(mDeviceOptions.getRemoteAdbPort()));
            return mGceAvdInfo;
        }
        // Add extra args.
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("gce_avd_driver", ".json");
            List<String> gceArgs = buildGceCmd(reportFile, mBuildInfo);

            CLog.i("Launching GCE with %s", gceArgs.toString());
            CommandResult cmd =
                    getRunUtil()
                            .runTimedCmd(
                                    getTestDeviceOptions().getGceCmdTimeout(),
                                    gceArgs.toArray(new String[gceArgs.size()]));
            CLog.i("GCE driver stderr: %s", cmd.getStderr());
            String instanceName = extractInstanceName(cmd.getStderr());
            if (instanceName != null) {
                mBuildInfo.addBuildAttribute("gce-instance-name", instanceName);
            } else {
                CLog.w("Could not extract an instance name for the gce device.");
            }
            if (CommandStatus.TIMED_OUT.equals(cmd.getStatus())) {
                String errors =
                        String.format(
                                "acloud errors: timeout after %dms, " + "acloud did not return",
                                getTestDeviceOptions().getGceCmdTimeout());
                if (instanceName != null) {
                    // If we managed to parse the instance name, report the boot failure so it
                    // can be shutdown.
                    mGceAvdInfo = new GceAvdInfo(instanceName, null, errors, GceStatus.BOOT_FAIL);
                    return mGceAvdInfo;
                }
                throw new TargetSetupError(errors, mDeviceDescriptor);
            } else if (!CommandStatus.SUCCESS.equals(cmd.getStatus())) {
                CLog.w("Error when booting the Gce instance, reading output of gce driver");
                mGceAvdInfo =
                        GceAvdInfo.parseGceInfoFromFile(
                                reportFile, mDeviceDescriptor, mDeviceOptions.getRemoteAdbPort());
                String errors = "";
                if (mGceAvdInfo != null) {
                    // We always return the GceAvdInfo describing the instance when possible
                    // The caller can decide actions to be taken.
                    return mGceAvdInfo;
                } else {
                    errors =
                            "Could not get a valid instance name, check the gce driver's output."
                                    + "The instance may not have booted up at all.";
                    CLog.e(errors);
                    throw new TargetSetupError(
                            String.format("acloud errors: %s", errors), mDeviceDescriptor);
                }
            }
            mGceAvdInfo =
                    GceAvdInfo.parseGceInfoFromFile(
                            reportFile, mDeviceDescriptor, mDeviceOptions.getRemoteAdbPort());
            return mGceAvdInfo;
        } catch (IOException e) {
            throw new TargetSetupError("failed to create log file", e, mDeviceDescriptor);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }

    /**
     * Retrieve the instance name from the gce boot logs. Search for the 'name': 'gce-<name>'
     * pattern to extract the name of it. We extract from the logs instead of result file because on
     * gce boot failure, the attempted instance name won't show in json.
     */
    protected String extractInstanceName(String bootupLogs) {
        if (bootupLogs != null) {
            final String pattern = "'name': '(((gce-)|(ins-))(.*?))'";
            Pattern namePattern = Pattern.compile(pattern);
            Matcher matcher = namePattern.matcher(bootupLogs);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** Build and return the command to launch GCE. Exposed for testing. */
    protected List<String> buildGceCmd(File reportFile, IBuildInfo b) throws IOException {
        List<String> gceArgs =
                ArrayUtil.list(getTestDeviceOptions().getAvdDriverBinary().getAbsolutePath());
        gceArgs.add(
                TestDeviceOptions.getCreateCommandByInstanceType(
                        getTestDeviceOptions().getInstanceType()));
        if (getTestDeviceOptions().getSystemImageTarget() != null) {
            // TF invoked with emulator binary build information
            gceArgs.add("--build_target");
            gceArgs.add(getTestDeviceOptions().getSystemImageTarget());
            gceArgs.add("--branch");
            gceArgs.add(getTestDeviceOptions().getSystemImageBranch());
            // TODO(b/119440413) clean this up when this part is migrated into infra config
            getTestDeviceOptions().setGceDriverBuildIdParam("emulator-build-id");
        } else {
            gceArgs.add("--build_target");
            if (b.getBuildAttributes().containsKey("build_target")) {
                // If BuildInfo contains the attribute for a build target, use that.
                gceArgs.add(b.getBuildAttributes().get("build_target"));
            } else {
                gceArgs.add(b.getBuildFlavor());
            }
            gceArgs.add("--branch");
            gceArgs.add(b.getBuildBranch());
        }
        // handled the build id related params
        gceArgs.add("--" + getTestDeviceOptions().getGceDriverBuildIdParam());
        gceArgs.add(b.getBuildId());
        gceArgs.addAll(
                TestDeviceOptions.getExtraParamsByInstanceType(
                        getTestDeviceOptions().getInstanceType(),
                        getTestDeviceOptions().getBaseImage()));
        gceArgs.add("--config_file");
        gceArgs.add(getAvdConfigFile().getAbsolutePath());
        if (getTestDeviceOptions().getSerivceAccountJsonKeyFile() != null) {
            gceArgs.add("--service_account_json_private_key_path");
            gceArgs.add(getTestDeviceOptions().getSerivceAccountJsonKeyFile().getAbsolutePath());
        }
        gceArgs.add("--report_file");
        gceArgs.add(reportFile.getAbsolutePath());
        switch (getTestDeviceOptions().getGceDriverLogLevel()) {
            case DEBUG:
                gceArgs.add("-v");
                break;
            case VERBOSE:
                gceArgs.add("-vv");
                break;
            default:
                break;
        }
        if (getTestDeviceOptions().getGceAccount() != null) {
            gceArgs.add("--email");
            gceArgs.add(getTestDeviceOptions().getGceAccount());
        }
        // Add flags to collect logcat and serial logs in case of boot failures.
        mGceBootFailureLogCat = FileUtil.createTempFile("gce_logcat_boot", ".tar.gz");
        gceArgs.add("--logcat_file");
        gceArgs.add(mGceBootFailureLogCat.getAbsolutePath());
        mGceBootFailureSerialLog = FileUtil.createTempFile("gce_serial_boot", ".tar.gz");
        gceArgs.add("--serial_log_file");
        gceArgs.add(mGceBootFailureSerialLog.getAbsolutePath());

        // Add additional args passed in.
        gceArgs.addAll(getTestDeviceOptions().getGceDriverParams());
        return gceArgs;
    }

    /** Shutdown the Gce instance associated with the {@link #startGce()}. */
    public void shutdownGce() {
        if (!getTestDeviceOptions().getAvdDriverBinary().canExecute()) {
            mGceAvdInfo = null;
            throw new RuntimeException(
                    String.format(
                            "GCE launcher %s is invalid",
                            getTestDeviceOptions().getAvdDriverBinary()));
        }
        if (mGceAvdInfo == null) {
            CLog.d("No instance to shutdown.");
            return;
        }
        List<String> gceArgs =
                ArrayUtil.list(getTestDeviceOptions().getAvdDriverBinary().getAbsolutePath());
        gceArgs.add("delete");
        // Add extra args.
        File f = null;
        try {
            gceArgs.add("--instance_names");
            gceArgs.add(mGceAvdInfo.instanceName());
            gceArgs.add("--config_file");
            gceArgs.add(getAvdConfigFile().getAbsolutePath());
            if (getTestDeviceOptions().getSerivceAccountJsonKeyFile() != null) {
                gceArgs.add("--service_account_json_private_key_path");
                gceArgs.add(
                        getTestDeviceOptions().getSerivceAccountJsonKeyFile().getAbsolutePath());
            }
            f = FileUtil.createTempFile("gce_avd_driver", ".json");
            gceArgs.add("--report_file");
            gceArgs.add(f.getAbsolutePath());
            CLog.i("Tear down of GCE with %s", gceArgs.toString());
            if (getTestDeviceOptions().waitForGceTearDown()) {
                CommandResult cmd =
                        getRunUtil()
                                .runTimedCmd(
                                        getTestDeviceOptions().getGceCmdTimeout(),
                                        gceArgs.toArray(new String[gceArgs.size()]));
                if (!CommandStatus.SUCCESS.equals(cmd.getStatus())) {
                    CLog.w(
                            "Failed to tear down GCE %s with the following arg, %s",
                            mGceAvdInfo.instanceName(), gceArgs);
                }
            } else {
                getRunUtil().runCmdInBackground(gceArgs.toArray(new String[gceArgs.size()]));
            }
        } catch (IOException e) {
            CLog.e("failed to create log file for GCE Teardown");
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(f);
            mGceAvdInfo = null;
        }
    }

    /**
     * Get a bugreportz from the device using ssh to avoid any adb connection potential issue.
     *
     * @param gceAvd The {@link GceAvdInfo} that describe the device.
     * @param options a {@link TestDeviceOptions} describing the device options to be used for the
     *     GCE device.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @return A file pointing to the zip bugreport, or null if an issue occurred.
     * @throws IOException
     */
    public static File getBugreportzWithSsh(
            GceAvdInfo gceAvd, TestDeviceOptions options, IRunUtil runUtil) throws IOException {
        String output = remoteSshCommandExec(gceAvd, options, runUtil, "bugreportz");
        Matcher match = BUGREPORTZ_RESPONSE_PATTERN.matcher(output);
        if (!match.find()) {
            CLog.e("Something went wrong during bugreportz collection: '%s'", output);
            return null;
        }
        String remoteFilePath = match.group(2);
        File localTmpFile = FileUtil.createTempFile("bugreport-ssh", ".zip");
        if (!RemoteFileUtil.fetchRemoteFile(
                gceAvd, options, runUtil, REMOTE_FILE_OP_TIMEOUT, remoteFilePath, localTmpFile)) {
            FileUtil.deleteFile(localTmpFile);
            return null;
        }
        return localTmpFile;
    }

    /**
     * Get a bugreport via ssh for a nested instance. This requires requesting the adb in the nested
     * virtual instance.
     *
     * @param gceAvd The {@link GceAvdInfo} that describe the device.
     * @param options a {@link TestDeviceOptions} describing the device options to be used for the
     *     GCE device.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @return A file pointing to the zip bugreport, or null if an issue occurred.
     * @throws IOException
     */
    public static File getNestedDeviceSshBugreportz(
            GceAvdInfo gceAvd, TestDeviceOptions options, IRunUtil runUtil) throws IOException {
        String output = "";
        // Retry a couple of time because adb might not be started for that user.
        // FIXME: See if we can use vsoc-01 directly to avoid this
        for (int i = 0; i < 3; i++) {
            output = remoteSshCommandExec(gceAvd, options, runUtil, "adb", "shell", "bugreportz");
            Matcher match = BUGREPORTZ_RESPONSE_PATTERN.matcher(output);
            if (match.find()) {
                break;
            }
        }
        Matcher match = BUGREPORTZ_RESPONSE_PATTERN.matcher(output);
        if (!match.find()) {
            CLog.e("Something went wrong during bugreportz collection: '%s'", output);
            return null;
        }
        String deviceFilePath = match.group(2);
        String pullOutput =
                remoteSshCommandExec(gceAvd, options, runUtil, "adb", "pull", deviceFilePath);
        CLog.d(pullOutput);
        String remoteFilePath = "./" + new File(deviceFilePath).getName();
        File localTmpFile = FileUtil.createTempFile("bugreport-ssh", ".zip");
        if (!RemoteFileUtil.fetchRemoteFile(
                gceAvd, options, runUtil, REMOTE_FILE_OP_TIMEOUT, remoteFilePath, localTmpFile)) {
            FileUtil.deleteFile(localTmpFile);
            return null;
        }
        return localTmpFile;
    }

    /**
     * Fetch a remote file from a nested instance and log it.
     *
     * @param logger The {@link ITestLogger} where to log the file.
     * @param gceAvd The {@link GceAvdInfo} that describe the device.
     * @param options a {@link TestDeviceOptions} describing the device options to be used for the
     *     GCE device.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param remoteFilePath The remote path where to find the file.
     * @param type the {@link LogDataType} of the logged file.
     */
    public static void logNestedRemoteFile(
            ITestLogger logger,
            GceAvdInfo gceAvd,
            TestDeviceOptions options,
            IRunUtil runUtil,
            String remoteFilePath,
            LogDataType type) {
        File remoteFile =
                RemoteFileUtil.fetchRemoteFile(
                        gceAvd, options, runUtil, REMOTE_FILE_OP_TIMEOUT, remoteFilePath);
        if (remoteFile != null) {
            try (InputStreamSource remoteFileStream = new FileInputStreamSource(remoteFile, true)) {
                logger.testLog(remoteFile.getName(), type, remoteFileStream);
            }
        }
    }

    private static String remoteSshCommandExec(
            GceAvdInfo gceAvd, TestDeviceOptions options, IRunUtil runUtil, String... command) {
        List<String> sshCmd =
                GceRemoteCmdFormatter.getSshCommand(
                        options.getSshPrivateKeyPath(),
                        null,
                        options.getInstanceUser(),
                        gceAvd.hostAndPort().getHostText(),
                        command);
        CommandResult res = runUtil.runTimedCmd(BUGREPORT_TIMEOUT, sshCmd.toArray(new String[0]));
        if (!CommandStatus.SUCCESS.equals(res.getStatus())) {
            CLog.e("issue when attempting to execute '%s':", Arrays.asList(command));
            CLog.e("%s", res.getStderr());
        }
        // We attempt to get a clean output from our command
        String output = res.getStdout().trim();
        return output;
    }

    /**
     * Reads the current content of the Gce Avd instance serial log.
     *
     * @param infos The {@link GceAvdInfo} describing the instance.
     * @param avdConfigFile the avd config file
     * @param jsonKeyFile the service account json key file.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @return The serial log output or null if something goes wrong.
     */
    public static String getInstanceSerialLog(
            GceAvdInfo infos, File avdConfigFile, File jsonKeyFile, IRunUtil runUtil) {
        AcloudConfigParser config = AcloudConfigParser.parseConfig(avdConfigFile);
        if (config == null) {
            CLog.e("Failed to parse our acloud config.");
            return null;
        }
        if (infos == null) {
            return null;
        }
        try {
            Credential credential = createCredential(config, jsonKeyFile);
            String project = config.getValueForKey(AcloudKeys.PROJECT);
            String zone = config.getValueForKey(AcloudKeys.ZONE);
            String instanceName = infos.instanceName();
            Compute compute =
                    new Compute.Builder(
                                    GoogleNetHttpTransport.newTrustedTransport(),
                                    JSON_FACTORY,
                                    null)
                            .setApplicationName(project)
                            .setHttpRequestInitializer(credential)
                            .build();
            GetSerialPortOutput outputPort =
                    compute.instances().getSerialPortOutput(project, zone, instanceName);
            SerialPortOutput output = outputPort.execute();
            return output.getContents();
        } catch (GeneralSecurityException | IOException e) {
            CLog.e(e);
            return null;
        }
    }

    private static Credential createCredential(AcloudConfigParser config, File jsonKeyFile)
            throws GeneralSecurityException, IOException {
        if (jsonKeyFile != null) {
            return GoogleApiClientUtil.createCredentialFromJsonKeyFile(jsonKeyFile, SCOPES);
        } else if (config.getValueForKey(AcloudKeys.SERVICE_ACCOUNT_JSON_PRIVATE_KEY) != null) {
            jsonKeyFile =
                    new File(config.getValueForKey(AcloudKeys.SERVICE_ACCOUNT_JSON_PRIVATE_KEY));
            return GoogleApiClientUtil.createCredentialFromJsonKeyFile(jsonKeyFile, SCOPES);
        } else {
            String serviceAccount = config.getValueForKey(AcloudKeys.SERVICE_ACCOUNT_NAME);
            String serviceKey = config.getValueForKey(AcloudKeys.SERVICE_ACCOUNT_PRIVATE_KEY);
            return GoogleApiClientUtil.createCredentialFromP12File(
                    serviceAccount, new File(serviceKey), SCOPES);
        }
    }

    public void cleanUp() {
        // Clean up logs file if any was created.
        FileUtil.deleteFile(mGceBootFailureLogCat);
        FileUtil.deleteFile(mGceBootFailureSerialLog);
    }

    /** Returns the instance of the {@link IRunUtil}. */
    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Log the serial output of a device described by {@link GceAvdInfo}.
     *
     * @param infos The {@link GceAvdInfo} describing the instance.
     * @param logger The {@link ITestLogger} where to log the serial log.
     */
    public void logSerialOutput(GceAvdInfo infos, ITestLogger logger) {
        String output =
                GceManager.getInstanceSerialLog(
                        infos,
                        getAvdConfigFile(),
                        getTestDeviceOptions().getSerivceAccountJsonKeyFile(),
                        getRunUtil());
        if (output == null) {
            CLog.w("Failed to collect the instance serial logs.");
            return;
        }
        try (ByteArrayInputStreamSource source =
                new ByteArrayInputStreamSource(output.getBytes())) {
            logger.testLog("gce_full_serial_log", LogDataType.TEXT, source);
        }
    }

    /**
     * Returns the {@link TestDeviceOptions} associated with the device that the gce manager was
     * initialized with.
     */
    private TestDeviceOptions getTestDeviceOptions() {
        return mDeviceOptions;
    }

    /** Returns the boot logcat of the gce instance. */
    public File getGceBootLogcatLog() {
        return mGceBootFailureLogCat;
    }

    /** Returns the boot serial log of the gce instance. */
    public File getGceBootSerialLog() {
        return mGceBootFailureSerialLog;
    }

    @VisibleForTesting
    File getAvdConfigFile() {
        if (getTestDeviceOptions().getAvdConfigTestResourceName() != null) {
            return BuildInfo.getTestResource(
                    mTestResourceBuildInfos, getTestDeviceOptions().getAvdConfigTestResourceName());
        }
        return getTestDeviceOptions().getAvdConfigFile();
    }
}