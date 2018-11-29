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
package com.android.tradefed.util;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.jackson2.JacksonFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;

/** Utils for create Google API client. */
public class GoogleApiClientUtil {

    public static final String APP_NAME = "tradefed";

    /**
     * Create credential from json key file.
     *
     * @param file is the p12 key file
     * @param scopes is the API's scope.
     * @return a {@link GoogleCredential}.
     * @throws FileNotFoundException
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static GoogleCredential createCredentialFromJsonKeyFile(
            File file, Collection<String> scopes) throws IOException, GeneralSecurityException {
        return GoogleCredential.fromStream(
                        new FileInputStream(file),
                        GoogleNetHttpTransport.newTrustedTransport(),
                        JacksonFactory.getDefaultInstance())
                .createScoped(scopes);
    }

    /**
     * Create credential from p12 file for service account.
     *
     * @deprecated It's better to use json key file, since p12 is deprecated by Google App Engine.
     *     And json key file have more information.
     * @param serviceAccount is the service account
     * @param keyFile is the p12 key file
     * @param scopes is the API's scope.
     * @return a {@link GoogleCredential}.
     * @throws GeneralSecurityException
     * @throws IOException
     */
    @Deprecated
    public static GoogleCredential createCredentialFromP12File(
            String serviceAccount, File keyFile, Collection<String> scopes)
            throws GeneralSecurityException, IOException {
        return new GoogleCredential.Builder()
                .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                .setJsonFactory(JacksonFactory.getDefaultInstance())
                .setServiceAccountId(serviceAccount)
                .setServiceAccountScopes(scopes)
                .setServiceAccountPrivateKeyFromP12File(keyFile)
                .build();
    }

    /**
     * @param requestInitializer a {@link HttpRequestInitializer}, normally it's {@link
     *     GoogleCredential}.
     * @param connectTimeout connect timeout in milliseconds.
     * @param readTimeout read timeout in milliseconds.
     * @return a {@link HttpRequestInitializer} with timeout.
     */
    public static HttpRequestInitializer setHttpTimeout(
            final HttpRequestInitializer requestInitializer, int connectTimeout, int readTimeout) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) throws IOException {
                requestInitializer.initialize(request);
                request.setConnectTimeout(connectTimeout);
                request.setReadTimeout(readTimeout);
            }
        };
    }
}