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

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;

import com.google.common.base.Strings;
import com.google.common.net.HostAndPort;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Structure to hold relevant data for a given GCE AVD instance. */
public class GceAvdInfo {

    private String mInstanceName;
    private HostAndPort mHostAndPort;
    private String mErrors;
    private GceStatus mStatus;

    public static enum GceStatus {
        SUCCESS,
        FAIL,
        BOOT_FAIL
    }

    public GceAvdInfo(String instanceName, HostAndPort hostAndPort) {
        mInstanceName = instanceName;
        mHostAndPort = hostAndPort;
    }

    public GceAvdInfo(
            String instanceName, HostAndPort hostAndPort, String errors, GceStatus status) {
        mInstanceName = instanceName;
        mHostAndPort = hostAndPort;
        mErrors = errors;
        mStatus = status;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "GceAvdInfo [mInstanceName="
                + mInstanceName
                + ", mHostAndPort="
                + mHostAndPort
                + ", mErrors="
                + mErrors
                + ", mStatus="
                + mStatus
                + "]";
    }

    public String instanceName() {
        return mInstanceName;
    }

    public HostAndPort hostAndPort() {
        return mHostAndPort;
    }

    public String getErrors() {
        return mErrors;
    }

    public GceStatus getStatus() {
        return mStatus;
    }

    /**
     * Parse a given file to obtain the GCE AVD device info.
     *
     * @param f {@link File} file to read the JSON output from GCE Driver.
     * @param descriptor the descriptor of the device that needs the info.
     * @param remoteAdbPort the remote port that should be used for adb connection
     * @return the {@link GceAvdInfo} of the device if found, or null if error.
     */
    public static GceAvdInfo parseGceInfoFromFile(
            File f, DeviceDescriptor descriptor, int remoteAdbPort) throws TargetSetupError {
        String data;
        try {
            data = FileUtil.readStringFromFile(f);
        } catch (IOException e) {
            CLog.e("Failed to read result file from GCE driver:");
            CLog.e(e);
            return null;
        }
        return parseGceInfoFromString(data, descriptor, remoteAdbPort);
    }

    /**
     * Parse a given string to obtain the GCE AVD device info.
     *
     * @param data JSON string.
     * @param descriptor the descriptor of the device that needs the info.
     * @param remoteAdbPort the remote port that should be used for adb connection
     * @return the {@link GceAvdInfo} of the device if found, or null if error.
     */
    public static GceAvdInfo parseGceInfoFromString(
            String data, DeviceDescriptor descriptor, int remoteAdbPort) throws TargetSetupError {
        if (Strings.isNullOrEmpty(data)) {
            CLog.w("No data provided");
            return null;
        }
        String errors = data;
        try {
            errors = parseErrorField(data);
            JSONObject res = new JSONObject(data);
            String status = res.getString("status");
            JSONArray devices = null;
            GceStatus gceStatus = GceStatus.valueOf(status);
            if (GceStatus.FAIL.equals(gceStatus) || GceStatus.BOOT_FAIL.equals(gceStatus)) {
                // In case of failure we still look for instance name to shutdown if needed.
                if (res.getJSONObject("data").has("devices_failing_boot")) {
                    devices = res.getJSONObject("data").getJSONArray("devices_failing_boot");
                }
            } else {
                devices = res.getJSONObject("data").getJSONArray("devices");
            }
            if (devices != null) {
                if (devices.length() == 1) {
                    JSONObject d = (JSONObject) devices.get(0);
                    String ip = d.getString("ip");
                    String instanceName = d.getString("instance_name");
                    return new GceAvdInfo(
                            instanceName,
                            HostAndPort.fromString(ip).withDefaultPort(remoteAdbPort),
                            errors,
                            gceStatus);
                } else {
                    CLog.w("Expected only one device to return but found %d", devices.length());
                }
            } else {
                CLog.w("No device information, device was not started.");
            }
        } catch (JSONException e) {
            CLog.e("Failed to parse JSON %s:", data);
            CLog.e(e);
        }
        // If errors are found throw an exception with the acloud message.
        if (errors.isEmpty()) {
            throw new TargetSetupError(String.format("acloud errors: %s", data), descriptor);
        } else {
            throw new TargetSetupError(String.format("acloud errors: %s", errors), descriptor);
        }
    }

    private static String parseErrorField(String data) throws JSONException {
        String res = "";
        JSONObject response = new JSONObject(data);
        JSONArray errors = response.getJSONArray("errors");
        for (int i = 0; i < errors.length(); i++) {
            res += (errors.getString(i) + "\n");
        }
        return res;
    }
}
