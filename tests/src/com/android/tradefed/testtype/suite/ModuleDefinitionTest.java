/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.LogSaverResultForwarder;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.multi.IMultiTargetPreparer;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.module.BaseModuleController;
import com.android.tradefed.testtype.suite.module.IModuleController;
import com.android.tradefed.testtype.suite.module.TestFailureModuleController;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Unit tests for {@link ModuleDefinition} */
@RunWith(JUnit4.class)
public class ModuleDefinitionTest {

    private static final String MODULE_NAME = "fakeName";
    private static final String DEFAULT_DEVICE_NAME = "DEFAULT_DEVICE";
    private ModuleDefinition mModule;
    private List<IRemoteTest> mTestList;
    private ITestInterface mMockTest;
    private ITargetPreparer mMockPrep;
    private ITargetCleaner mMockCleaner;
    private List<ITargetPreparer> mTargetPrepList;
    private Map<String, List<ITargetPreparer>> mMapDeviceTargetPreparer;
    private List<IMultiTargetPreparer> mMultiTargetPrepList;
    private ITestInvocationListener mMockListener;
    private IBuildInfo mMockBuildInfo;
    private ITestDevice mMockDevice;
    // Extra mock for log saving testing
    private ILogSaver mMockLogSaver;
    private ILogSaverListener mMockLogSaverListener;

    private interface ITestInterface extends IRemoteTest, IBuildReceiver, IDeviceTest {}

    /** Test implementation that allows us to exercise different use cases * */
    private class TestObject implements ITestInterface {

        private ITestDevice mDevice;
        private String mRunName;
        private int mNumTest;
        private boolean mShouldThrow;

        public TestObject(String runName, int numTest, boolean shouldThrow) {
            mRunName = runName;
            mNumTest = numTest;
            mShouldThrow = shouldThrow;
        }

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            listener.testRunStarted(mRunName, mNumTest);
            for (int i = 0; i < mNumTest; i++) {
                TestDescription test = new TestDescription(mRunName + "class", "test" + i);
                listener.testStarted(test);
                if (mShouldThrow && i == mNumTest / 2) {
                    throw new DeviceNotAvailableException();
                }
                listener.testEnded(test, Collections.emptyMap());
            }
            listener.testRunEnded(0, Collections.emptyMap());
        }

        @Override
        public void setBuild(IBuildInfo buildInfo) {
            // ignore
        }

        @Override
        public void setDevice(ITestDevice device) {
            mDevice = device;
        }

        @Override
        public ITestDevice getDevice() {
            return mDevice;
        }
    }

    @Before
    public void setUp() {
        mMockLogSaver = EasyMock.createMock(ILogSaver.class);
        mMockLogSaverListener = EasyMock.createMock(ILogSaverListener.class);

        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        mTestList = new ArrayList<>();
        mMockTest = EasyMock.createMock(ITestInterface.class);
        mTestList.add(mMockTest);
        mTargetPrepList = new ArrayList<>();
        mMockPrep = EasyMock.createMock(ITargetPreparer.class);
        mMockCleaner = EasyMock.createMock(ITargetCleaner.class);
        mTargetPrepList.add(mMockPrep);
        mTargetPrepList.add(mMockCleaner);
        mMapDeviceTargetPreparer = new LinkedHashMap<>();
        mMapDeviceTargetPreparer.put(DEFAULT_DEVICE_NAME, mTargetPrepList);

        mMultiTargetPrepList = new ArrayList<>();
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));

        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
    }

    /**
     * Helper for replaying mocks.
     */
    private void replayMocks() {
        EasyMock.replay(mMockListener, mMockLogSaver, mMockLogSaverListener);
        for (IRemoteTest test : mTestList) {
            EasyMock.replay(test);
        }
        for (ITargetPreparer prep : mTargetPrepList) {
            try {
                EasyMock.replay(prep);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
    }

    /**
     * Helper for verifying mocks.
     */
    private void verifyMocks() {
        EasyMock.verify(mMockListener, mMockLogSaver, mMockLogSaverListener);
        for (IRemoteTest test : mTestList) {
            EasyMock.verify(test);
        }
        for (ITargetPreparer prep : mTargetPrepList) {
            try {
                EasyMock.verify(prep);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow.
     */
    @Test
    public void testRun() throws Exception {
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockTest.setBuild(EasyMock.eq(mMockBuildInfo));
        mMockTest.setDevice(EasyMock.eq(mMockDevice));
        mMockTest.run((ITestInvocationListener)EasyMock.anyObject());
        mMockCleaner.tearDown(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo),
                EasyMock.isNull());
        mMockListener.testRunStarted(MODULE_NAME, 0);
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow and skip target preparers if disabled.
     */
    @Test
    public void testRun_disabledPreparation() throws Exception {
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        // No setup and teardown expected from preparers.
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(true);
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(true);
        mMockTest.setBuild(EasyMock.eq(mMockBuildInfo));
        mMockTest.setDevice(EasyMock.eq(mMockDevice));
        mMockTest.run((ITestInvocationListener) EasyMock.anyObject());
        mMockListener.testRunStarted(MODULE_NAME, 0);
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)}
     */
    @Test
    public void testRun_failPreparation() throws Exception {
        final String exceptionMessage = "ouch I failed";
        mTargetPrepList.clear();
        mTargetPrepList.add(
                new BaseTargetPreparer() {
                    @Override
                    public void setUp(ITestDevice device, IBuildInfo buildInfo)
                            throws TargetSetupError, BuildError, DeviceNotAvailableException {
                        DeviceDescriptor nullDescriptor = null;
                        throw new TargetSetupError(exceptionMessage, nullDescriptor);
                    }
                });
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        mTestList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mMockCleaner.tearDown(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo),
                EasyMock.isNull());
        mMockListener.testRunStarted(EasyMock.eq(MODULE_NAME), EasyMock.eq(1));
        mMockListener.testStarted(
                new TestDescription(TargetSetupError.class.getCanonicalName(), "preparationError"));
        mMockListener.testFailed(EasyMock.anyObject(), EasyMock.contains(exceptionMessage));
        mMockListener.testEnded(EasyMock.anyObject(), EasyMock.anyObject());
        mMockListener.testRunFailed(EasyMock.contains(exceptionMessage));
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow with actual test callbacks.
     */
    @Test
    public void testRun_fullPass() throws Exception {
        final int testCount = 5;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(MODULE_NAME, testCount);
        for (int i = 0; i < testCount; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.anyObject());
        }
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that {@link ModuleDefinition#run(ITestInvocationListener)} is properly going through the
     * execution flow with actual test callbacks.
     */
    @Test
    public void testRun_partialRun() throws Exception {
        final int testCount = 4;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, true));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        mMockListener.testRunStarted(MODULE_NAME, testCount);
        for (int i = 0; i < 3; i++) {
            mMockListener.testStarted((TestDescription) EasyMock.anyObject(), EasyMock.anyLong());
            mMockListener.testEnded(
                    (TestDescription) EasyMock.anyObject(),
                    EasyMock.anyLong(),
                    EasyMock.anyObject());
        }
        mMockListener.testFailed(EasyMock.anyObject(), EasyMock.anyObject());
        mMockListener.testRunFailed(EasyMock.anyObject());
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        try {
            mModule.run(mMockListener);
            fail("Should have thrown an exception.");
        } catch (DeviceNotAvailableException expected) {
            // expected
        }
        // Only one module
        assertEquals(1, mModule.getTestsResults().size());
        assertEquals(2, mModule.getTestsResults().get(0).getNumCompleteTests());
        verifyMocks();
    }

    /**
     * Test that when a module is created with some particular informations, the resulting {@link
     * IInvocationContext} of the module is properly populated.
     */
    @Test
    public void testAbiSetting() throws Exception {
        final int testCount = 5;
        IConfiguration config = new Configuration("", "");
        ConfigurationDescriptor descriptor = new ConfigurationDescriptor();
        descriptor.setAbi(new Abi("arm", "32"));
        descriptor.setModuleName(MODULE_NAME);
        config.setConfigurationObject(
                Configuration.CONFIGURATION_DESCRIPTION_TYPE_NAME, descriptor);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false));
        mModule =
                new ModuleDefinition(
                        "arm32 " + MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        // Check that the invocation module created has expected informations
        IInvocationContext moduleContext = mModule.getModuleInvocationContext();
        assertEquals(
                MODULE_NAME,
                moduleContext.getAttributes().get(ModuleDefinition.MODULE_NAME).get(0));
        assertEquals("arm", moduleContext.getAttributes().get(ModuleDefinition.MODULE_ABI).get(0));
        assertEquals(
                "arm32 " + MODULE_NAME,
                moduleContext.getAttributes().get(ModuleDefinition.MODULE_ID).get(0));
    }

    /**
     * Test running a module when the configuration has a module controller object that force a full
     * bypass of the module.
     */
    @Test
    public void testModuleController_fullBypass() throws Exception {
        IConfiguration config = new Configuration("", "");
        BaseModuleController moduleConfig =
                new BaseModuleController() {
                    @Override
                    public RunStrategy shouldRun(IInvocationContext context) {
                        return RunStrategy.FULL_MODULE_BYPASS;
                    }
                };
        config.setConfigurationObject(ModuleDefinition.MODULE_CONTROLLER, moduleConfig);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {
                        listener.testRunStarted("test", 1);
                        listener.testFailed(
                                new TestDescription("failedclass", "failedmethod"), "trace");
                    }
                });
        mTargetPrepList.clear();
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        // module is completely skipped, no tests is recorded.
        replayMocks();
        mModule.run(mMockListener, null);
        verifyMocks();
    }

    /**
     * Test running a module when the configuration has a module controller object that force to
     * skip all the module test cases.
     */
    @Test
    public void testModuleController_skipTestCases() throws Exception {
        IConfiguration config = new Configuration("", "");
        BaseModuleController moduleConfig =
                new BaseModuleController() {
                    @Override
                    public RunStrategy shouldRun(IInvocationContext context) {
                        return RunStrategy.SKIP_MODULE_TESTCASES;
                    }
                };
        config.setConfigurationObject(ModuleDefinition.MODULE_CONTROLLER, moduleConfig);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {
                        TestDescription tid = new TestDescription("class", "method");
                        listener.testRunStarted("test", 1);
                        listener.testStarted(tid);
                        listener.testFailed(tid, "I failed");
                        listener.testEnded(tid, new HashMap<>());
                        listener.testRunEnded(0, new HashMap<>());
                    }
                });
        mTargetPrepList.clear();
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        // expect the module to run but tests to be ignored
        mMockListener.testRunStarted(EasyMock.anyObject(), EasyMock.anyInt());
        mMockListener.testStarted(EasyMock.anyObject(), EasyMock.anyLong());
        mMockListener.testIgnored(EasyMock.anyObject());
        mMockListener.testEnded(EasyMock.anyObject(), EasyMock.anyLong(), EasyMock.anyObject());
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener, null);
        verifyMocks();
    }

    /** Test {@link IRemoteTest} that log a file during its run. */
    public class TestLogClass implements ITestInterface {

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            listener.testLog(
                    "testlogclass",
                    LogDataType.TEXT,
                    new ByteArrayInputStreamSource("".getBytes()));
        }

        @Override
        public void setBuild(IBuildInfo buildInfo) {}

        @Override
        public void setDevice(ITestDevice device) {}

        @Override
        public ITestDevice getDevice() {
            return null;
        }
    }

    /**
     * Test that the invocation level result_reporter receive the testLogSaved information from the
     * modules.
     *
     * <p>The {@link LogSaverResultForwarder} from the module is expected to log the file and ensure
     * that it passes the information to the {@link LogSaverResultForwarder} from the {@link
     * TestInvocation} in order for final result_reporter to know about logged files.
     */
    @Test
    public void testModule_LogSaverResultForwarder() throws Exception {
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestLogClass());
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        mModule.setLogSaver(mMockLogSaver);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockLogSaverListener.testRunStarted(MODULE_NAME, 0);
        mMockLogSaverListener.testRunEnded(
                EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());

        LogFile loggedFile = new LogFile("path", "url", false, false);
        EasyMock.expect(
                        mMockLogSaver.saveLogData(
                                EasyMock.eq("testlogclass"),
                                EasyMock.eq(LogDataType.TEXT),
                                EasyMock.anyObject()))
                .andReturn(loggedFile);
        mMockLogSaverListener.setLogSaver(mMockLogSaver);
        // mMockLogSaverListener should receive the testLogSaved call even from the module
        mMockLogSaverListener.testLogSaved(
                EasyMock.eq("testlogclass"),
                EasyMock.eq(LogDataType.TEXT),
                EasyMock.anyObject(),
                EasyMock.eq(loggedFile));

        // Simulate how the invoker actually put the log saver
        replayMocks();
        LogSaverResultForwarder forwarder =
                new LogSaverResultForwarder(mMockLogSaver, Arrays.asList(mMockLogSaverListener));
        mModule.run(forwarder);
        verifyMocks();
    }

    /**
     * Test a run where the runner is not part of the whitelist. The runner is skipped and no test
     * cases are reported.
     */
    @Test
    public void testRun_NotPartOfWhiteList() throws Exception {
        final int testCount = 5;
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(new TestObject("run1", testCount, false));
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        new Configuration("", ""));
        Set<String> whiteList = new HashSet<>();
        whiteList.add("this.is.not.TestObject");
        mModule.setRunnerWhiteList(whiteList);
        mModule.getModuleInvocationContext().addAllocatedDevice(DEFAULT_DEVICE_NAME, mMockDevice);
        mModule.getModuleInvocationContext()
                .addDeviceBuildInfo(DEFAULT_DEVICE_NAME, mMockBuildInfo);
        mModule.setBuild(mMockBuildInfo);
        mModule.setDevice(mMockDevice);
        EasyMock.expect(mMockPrep.isDisabled()).andReturn(false);
        mMockPrep.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        EasyMock.expect(mMockCleaner.isDisabled()).andStubReturn(false);
        mMockCleaner.setUp(EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo));
        mMockCleaner.tearDown(
                EasyMock.eq(mMockDevice), EasyMock.eq(mMockBuildInfo), EasyMock.isNull());
        mMockListener.testRunStarted(MODULE_NAME, 0);
        // No test cases are run.
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        replayMocks();
        mModule.run(mMockListener);
        verifyMocks();
    }

    /**
     * Test that the {@link IModuleController} object can override the behavior of the capture of
     * the failure.
     */
    @Test
    public void testOverrideModuleConfig() throws Exception {
        // failure listener with capture logcat on failure and screenshot on failure.
        List<ITestDevice> listDevice = new ArrayList<>();
        listDevice.add(mMockDevice);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn("Serial");
        TestFailureListener failureListener =
                new TestFailureListener(mMockListener, listDevice, false, true, true, false, 5);
        IConfiguration config = new Configuration("", "");
        TestFailureModuleController moduleConfig = new TestFailureModuleController();
        OptionSetter setter = new OptionSetter(moduleConfig);
        // Module option should override the logcat on failure
        setter.setOptionValue("logcat-on-failure", "false");
        config.setConfigurationObject(ModuleDefinition.MODULE_CONTROLLER, moduleConfig);
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(
                new IRemoteTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceNotAvailableException {
                        listener.testFailed(
                                new TestDescription("failedclass", "failedmethod"), "trace");
                    }
                });
        mTargetPrepList.clear();
        mModule =
                new ModuleDefinition(
                        MODULE_NAME,
                        testList,
                        mMapDeviceTargetPreparer,
                        mMultiTargetPrepList,
                        config);
        mMockListener.testRunStarted("fakeName", 0);
        mMockListener.testRunEnded(EasyMock.anyLong(), (Map<String, String>) EasyMock.anyObject());
        // Only screenshot is captured
        EasyMock.expect(mMockDevice.getScreenshot())
                .andReturn(new ByteArrayInputStreamSource("".getBytes()));
        // Only a screenshot is capture, logcat for that module was disabled.
        mMockListener.testLog(
                EasyMock.anyObject(), EasyMock.eq(LogDataType.PNG), EasyMock.anyObject());
        EasyMock.replay(mMockDevice);
        replayMocks();
        mModule.run(mMockListener, failureListener);
        EasyMock.verify(mMockDevice);
    }
}
