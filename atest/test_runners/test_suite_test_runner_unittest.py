#!/usr/bin/env python
#
# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Unittests for test_suite_test_runner."""

import unittest
import mock

# pylint: disable=import-error
import test_suite_test_runner
import unittest_utils
from test_finders import test_info


# pylint: disable=protected-access
class SuiteTestRunnerUnittests(unittest.TestCase):
    """Unit tests for test_suite_test_runner.py"""

    def setUp(self):
        self.suite_tr = test_suite_test_runner.TestSuiteTestRunner(results_dir='')

    def tearDown(self):
        mock.patch.stopall()

    @mock.patch('atest_utils.get_result_server_args')
    def test_generate_run_commands(self, mock_resultargs):
        """Test _generate_run_command method.
        Strategy:
            suite_name: cts --> run_cmd: cts-tradefed run commandAndExit cts
        """
        test_infos = set()
        suite_name = 'cts'
        t_info = test_info.TestInfo(suite_name,
                                    test_suite_test_runner.TestSuiteTestRunner.NAME,
                                    {suite_name})
        test_infos.add(t_info)

        # Basic Run Cmd
        run_cmd = []
        exe_cmd = test_suite_test_runner.TestSuiteTestRunner.EXECUTABLE % suite_name
        run_cmd.append(test_suite_test_runner.TestSuiteTestRunner._RUN_CMD.format(
            exe=exe_cmd,
            test=suite_name,
            args=''))
        mock_resultargs.return_value = []
        unittest_utils.assert_strict_equal(
            self,
            self.suite_tr._generate_run_commands(test_infos, ''),
            run_cmd)

        # Run cmd with --serial LG123456789.
        run_cmd = []
        run_cmd.append(test_suite_test_runner.TestSuiteTestRunner._RUN_CMD.format(
            exe=exe_cmd,
            test=suite_name,
            args='--serial LG123456789'))
        unittest_utils.assert_strict_equal(
            self,
            self.suite_tr._generate_run_commands(test_infos, {'SERIAL':'LG123456789'}),
            run_cmd)


if __name__ == '__main__':
    unittest.main()