/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.tradefed.result.proto.SummaryRecordProto.SummaryRecord;

/**
 * Interface describing an {@link ITestInvocationListener} that can receive a summary of the
 * invocation only instead of the full results.
 */
public interface IResultSummaryReceiver {

    /**
     * Callback where the {@link SummaryRecord} of the results is passed. Will always be called
     * before {@link ITestInvocationListener#invocationEnded(long)}.
     *
     * @param summary a {@link SummaryRecord} containing the summary of the invocation.
     */
    public void setResultSummary(SummaryRecord summary);
}
