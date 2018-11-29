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
package com.android.tradefed.testtype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.result.ITestInvocationListener;

import org.easymock.EasyMock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GTestBase}. */
@RunWith(JUnit4.class)
public class GTestBaseTest {

    private OptionSetter mSetter;
    /** Very basic implementation of {@link GTestBase} to test it. */
    static class GTestBaseImpl extends GTestBase {
        @Override
        public String loadFilter(String binaryPath) {
            return "filter1";
        }

        @Override
        public void run(ITestInvocationListener listener) {
            return;
        }
    }

    /** Test get the filters string. */
    @Test
    public void testGetGTestFilters_default()
            throws ConfigurationException, DeviceNotAvailableException {
        String moduleName = "test1";
        GTestBase gTestBase = new GTestBaseImpl();
        mSetter = new OptionSetter(gTestBase);
        mSetter.setOptionValue("test-filter-key", "filterKey");

        String filters = gTestBase.getGTestFilters(moduleName);
        assertEquals("--gtest_filter=filter1", filters);
    }

    /** Test get postive gtest filters. */
    @Test
    public void testGTestFilters_positive()
            throws ConfigurationException, DeviceNotAvailableException {
        String moduleName = "test1";
        GTestBase gTestBase = new GTestBaseImpl();
        mSetter = new OptionSetter(gTestBase);
        mSetter.setOptionValue("positive-testname-filter", "filter1:filter2");

        String filters = gTestBase.getGTestFilters(moduleName);
        assertEquals(String.format("--gtest_filter=%s", "filter1:filter2"), filters);
    }

    /** Test get native gtest filters. */
    @Test
    public void testGTestFilters_negative()
            throws ConfigurationException, DeviceNotAvailableException {
        String moduleName = "test1";
        GTestBase gTestBase = new GTestBaseImpl();
        mSetter = new OptionSetter(gTestBase);
        mSetter.setOptionValue("negative-testname-filter", "filter1:filter2");

        String filters = gTestBase.getGTestFilters(moduleName);
        assertEquals(String.format("--gtest_filter=-%s", "filter1:filter2"), filters);
    }

    /** Test get both postive and negative filters. */
    @Test
    public void testGTestFilters_postive_and_negative()
            throws ConfigurationException, DeviceNotAvailableException {
        String moduleName = "test1";
        GTestBase gTestBase = new GTestBaseImpl();
        mSetter = new OptionSetter(gTestBase);
        mSetter.setOptionValue("positive-testname-filter", "filter1:filter2");
        mSetter.setOptionValue("negative-testname-filter", "filter3:filter4");

        String filters = gTestBase.getGTestFilters(moduleName);
        assertEquals(
                String.format("--gtest_filter=%s-%s", "filter1:filter2", "filter3:filter4"),
                filters);
    }

    /** Test the return instance type of createResultParser. */
    @Test
    public void testCreateResultParser() throws ConfigurationException {
        GTestBase gTestBase = new GTestBaseImpl();
        mSetter = new OptionSetter(gTestBase);
        ITestInvocationListener listener = EasyMock.createNiceMock(ITestInvocationListener.class);
        EasyMock.replay(listener);

        IShellOutputReceiver receiver = gTestBase.createResultParser("test1", listener);
        assertTrue(receiver instanceof GTestResultParser);

        mSetter.setOptionValue("collect-tests-only", "true");
        receiver = gTestBase.createResultParser("test1", listener);
        assertTrue(receiver instanceof GTestListTestParser);

        EasyMock.verify(listener);
    }

    /**
     * Test that when the GTest is a shard and we add after this additional filters we remove the
     * sharding. GTest sharding is applied after the filtering so we would run nothing in most
     * cases.
     */
    @Test
    public void testGTestFilters_positiveAndSharding()
            throws ConfigurationException, DeviceNotAvailableException {
        String moduleName = "test1";
        GTestBase gTestBase = new GTestBaseImpl();
        gTestBase.setShardCount(2);
        gTestBase.setShardIndex(1);
        mSetter = new OptionSetter(gTestBase);
        mSetter.setOptionValue("positive-testname-filter", "filter1:filter2");

        String filters = gTestBase.getGTestFilters(moduleName);
        assertEquals(String.format("--gtest_filter=%s", "filter1:filter2"), filters);
        assertEquals(2, gTestBase.getShardCount());

        gTestBase.addIncludeFilter("filter3");
        filters = gTestBase.getGTestFilters(moduleName);
        assertEquals(String.format("--gtest_filter=%s", "filter1:filter2:filter3"), filters);
        assertEquals(0, gTestBase.getShardCount());
    }
}