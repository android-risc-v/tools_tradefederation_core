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
package com.android.tradefed.util.testmapping;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A class for loading a TEST_MAPPING file. */
public class TestMapping {

    // Key for test sources information stored in meta data of ConfigurationDescription.
    public static final String TEST_SOURCES = "Test Sources";

    private static final String PRESUBMIT = "presubmit";
    private static final String POSTSUBMIT = "postsubmit";
    private static final String IMPORTS = "imports";
    private static final String KEY_NAME = "name";
    private static final String TEST_MAPPING = "TEST_MAPPING";
    private static final String TEST_MAPPINGS_ZIP = "test_mappings.zip";
    private static final String DISABLED_PRESUBMIT_TESTS = "disabled-presubmit-tests";
    private static final String KEY_OPTIONS = "options";

    private Map<String, List<TestInfo>> mTestCollection = null;

    /**
     * Constructor to create a {@link TestMapping} object from a path to TEST_MAPPING file.
     *
     * @param path The {@link Path} to a TEST_MAPPING file.
     * @param testMappingsDir The {@link Path} to the folder of all TEST_MAPPING files for a build.
     */
    public TestMapping(Path path, Path testMappingsDir) {
        mTestCollection = new LinkedHashMap<>();
        String relativePath = testMappingsDir.relativize(path.getParent()).toString();
        String errorMessage = null;
        try {
            String content = String.join("", Files.readAllLines(path, StandardCharsets.UTF_8));
            if (content != null) {
                JSONTokener tokener = new JSONTokener(content);
                JSONObject root = new JSONObject(tokener);
                Iterator<String> testGroups = (Iterator<String>) root.keys();
                while (testGroups.hasNext()) {
                    String group = testGroups.next();
                    if (group.equals(IMPORTS)) {
                        // TF runs tests in all TEST_MAPPING files in a build, so imports do not
                        // need to be considered.
                        continue;
                    }
                    List<TestInfo> testsForGroup = new ArrayList<TestInfo>();
                    mTestCollection.put(group, testsForGroup);
                    JSONArray arr = root.getJSONArray(group);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject testObject = arr.getJSONObject(i);
                        TestInfo test = new TestInfo(testObject.getString(KEY_NAME), relativePath);
                        if (testObject.has(KEY_OPTIONS)) {
                            JSONArray optionObjects = testObject.getJSONArray(KEY_OPTIONS);
                            for (int j = 0; j < optionObjects.length(); j++) {
                                JSONObject optionObject = optionObjects.getJSONObject(j);
                                for (int k = 0; k < optionObject.names().length(); k++) {
                                    String name = optionObject.names().getString(k);
                                    String value = optionObject.getString(name);
                                    TestOption option = new TestOption(name, value);
                                    test.addOption(option);
                                }
                            }
                        }
                        testsForGroup.add(test);
                    }
                }
            }
        } catch (IOException e) {
            errorMessage = String.format("TEST_MAPPING file does not exist: %s.", path.toString());
            CLog.e(errorMessage);
        } catch (JSONException e) {
            errorMessage =
                    String.format(
                            "Error parsing TEST_MAPPING file: %s. Error: %s", path.toString(), e);
        }

        if (errorMessage != null) {
            CLog.e(errorMessage);
            throw new RuntimeException(errorMessage);
        }
    }

    /**
     * Helper to get all tests set in a TEST_MAPPING file for a given group.
     *
     * @param testGroup A {@link String} of the test group.
     * @param disabledTests A set of {@link String} for the name of the disabled tests.
     * @return A {@code List<TestInfo>} of the test infos.
     */
    public List<TestInfo> getTests(String testGroup, Set<String> disabledTests) {
        List<TestInfo> tests = new ArrayList<TestInfo>();

        List<String> testGroups = new ArrayList<>();
        testGroups.add(testGroup);
        // All presubmit tests should be part of postsubmit too.
        if (testGroup.equals(POSTSUBMIT)) {
            testGroups.add(PRESUBMIT);
        }
        for (String group : testGroups) {
            for (TestInfo test : mTestCollection.getOrDefault(group, new ArrayList<TestInfo>())) {
                if (disabledTests != null && disabledTests.contains(test.getName())) {
                    CLog.d("Test is disabled: %s.", test);
                    continue;
                }
                tests.add(test);
            }
        }

        return tests;
    }

    /**
     * Merge multiple tests if there are any for the same test module, but with different test
     * options.
     *
     * @param tests A {@code Set<TestInfo>} of the test infos to be processed.
     * @return A {@code Set<TestInfo>} of tests that each is for a unique test module.
     */
    private static Set<TestInfo> mergeTests(Set<TestInfo> tests) {
        Map<String, List<TestInfo>> testsGroupedbyName =
                tests.stream()
                        .collect(Collectors.groupingBy(TestInfo::getName, Collectors.toList()));

        Set<TestInfo> mergedTests = new HashSet<>();
        for (List<TestInfo> multiTests : testsGroupedbyName.values()) {
            TestInfo mergedTest = multiTests.get(0);
            if (multiTests.size() > 1) {
                for (TestInfo test : multiTests.subList(1, multiTests.size())) {
                    mergedTest.merge(test);
                }
            }
            mergedTests.add(mergedTest);
        }

        return mergedTests;
    }

    /**
     * Helper to find all tests in all TEST_MAPPING files. This is needed when a suite run requires
     * to run all tests in TEST_MAPPING files for a given group, e.g., presubmit.
     *
     * @param testGroup a {@link String} of the test group.
     * @return A {@code Set<TestInfo>} of tests set in the build artifact, test_mappings.zip.
     */
    public static Set<TestInfo> getTests(IBuildInfo buildInfo, String testGroup) {
        Set<TestInfo> tests = new HashSet<TestInfo>();
        Set<String> disabledTests = new HashSet<>();

        File testMappingsZip = buildInfo.getFile(TEST_MAPPINGS_ZIP);
        File testMappingsDir = null;
        Stream<Path> stream = null;
        try {
            testMappingsDir = ZipUtil2.extractZipToTemp(testMappingsZip, TEST_MAPPINGS_ZIP);
            Path testMappingsRootPath = Paths.get(testMappingsDir.getAbsolutePath());
            if (testGroup.equals(PRESUBMIT)) {
                File disabledPresubmitTestsFile =
                        new File(testMappingsRootPath.toString(), DISABLED_PRESUBMIT_TESTS);
                disabledTests.addAll(
                        Arrays.asList(
                                FileUtil.readStringFromFile(disabledPresubmitTestsFile)
                                        .split("\\r?\\n")));
            }

            stream = Files.walk(testMappingsRootPath, FileVisitOption.FOLLOW_LINKS);
            stream.filter(path -> path.getFileName().toString().equals(TEST_MAPPING))
                    .forEach(
                            path ->
                                    tests.addAll(
                                            new TestMapping(path, testMappingsRootPath)
                                                    .getTests(testGroup, disabledTests)));

        } catch (IOException e) {
            RuntimeException runtimeException =
                    new RuntimeException(
                            String.format(
                                    "IO error (%s) when reading tests from TEST_MAPPING files (%s)",
                                    e.getMessage(), testMappingsZip.getAbsolutePath()),
                            e);
            throw runtimeException;
        } finally {
            if (stream != null) {
                stream.close();
            }
            FileUtil.recursiveDelete(testMappingsDir);
        }

        return TestMapping.mergeTests(tests);
    }
}