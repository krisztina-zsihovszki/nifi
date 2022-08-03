/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.dropbox;

import static org.apache.nifi.processors.dropbox.DropboxFileInfo.ID;

import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.oauth.DbxCredential;
import com.dropbox.core.v2.DbxClientV2;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.Record;

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"dropbox", "storage", "fetch"})
@CapabilityDescription("Fetches files from a Dropbox Folder. Designed to be used in tandem with ListDropbox.")
@SeeAlso({ListDropbox.class})
@WritesAttributes(
        @WritesAttribute(attribute = FetchDropbox.ERROR_MESSAGE_ATTRIBUTE, description = "The error message returned by Dropbox when the fetch of a file fails"))
public class FetchDropbox extends AbstractProcessor {

    public static final String ERROR_MESSAGE_ATTRIBUTE = "error.message";

    public static final PropertyDescriptor APP_KEY = new PropertyDescriptor.Builder()
            .name("app-key")
            .displayName("App Key")
            .description("Dropbox API app key of the user. "+
                    "You can obtain an API app key by registering with Dropbox: https://dropbox.com/developers/apps")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor APP_SECRET = new PropertyDescriptor.Builder()
            .name("app-secret")
            .displayName("App Secret")
            .description("App Secret of the user. "+
                    "You can obtain an API app secret by registering with Dropbox: https://dropbox.com/developers/apps")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor ACCESS_TOKEN = new PropertyDescriptor.Builder()
            .name("access-token")
            .displayName("Access Token")
            .description("")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();


    public static final PropertyDescriptor REFRESH_TOKEN = new PropertyDescriptor.Builder()
            .name("refresh-token")
            .displayName("Refresh Token")
            .description("")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor FILE_ID = new PropertyDescriptor
            .Builder().name("dropbox-file-id")
            .displayName("Dropbox File ID")
            .description("The ID of Dropbox file to fetch")
            .required(true)
            .defaultValue("${dropbox.id}")
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("record-reader")
            .displayName("Record Reader")
            .description("Specifies the Controller Service to use for reading incoming Dropbox file meta-data as NiFi Records."
                    + " If not set, the Processor expects as attributes of a separate flowfile for each File to fetch.")
            .identifiesControllerService(RecordReaderFactory.class)
            .required(false)
            .build();

    public static final Relationship REL_SUCCESS =
            new Relationship.Builder()
                    .name("success")
                    .description("A flowfile will be routed here for each successfully fetched File.")
                    .build();

    public static final Relationship REL_FAILURE =
            new Relationship.Builder().name("failure")
                    .description("A flowfile will be routed here for each File for which fetch was attempted but failed.")
                    .build();

    public static final Relationship REL_INPUT_FAILURE =
            new Relationship.Builder().name("input_failure")
                    .description("The incoming flowfile will be routed here if it's content could not be processed.")
                    .build();

    private static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            FILE_ID,
            RECORD_READER,
            APP_KEY,
            APP_SECRET,
            ACCESS_TOKEN,
            REFRESH_TOKEN
    ));

    public static final Set<Relationship> relationships = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            REL_SUCCESS,
            REL_FAILURE,
            REL_INPUT_FAILURE
    )));


    private DbxClientV2 dropboxApiClient;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws IOException {
        final String appKey = context.getProperty(APP_KEY).evaluateAttributeExpressions().getValue();
        final String appSecret = context.getProperty(APP_SECRET).evaluateAttributeExpressions().getValue();
        final String accessToken = context.getProperty(ACCESS_TOKEN).evaluateAttributeExpressions().getValue();
        final String refreshToken = context.getProperty(REFRESH_TOKEN).evaluateAttributeExpressions().getValue();

        //TODO: client id
        DbxRequestConfig config = new DbxRequestConfig("nifi");
        DbxCredential credentials = new DbxCredential(accessToken, -1L, refreshToken, appKey, appSecret);
        dropboxApiClient = new DbxClientV2(config, credentials);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        if (context.getProperty(RECORD_READER).isSet()) {
            RecordReaderFactory recordReaderFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);

            try (InputStream inFlowFile = session.read(flowFile)) {
                final Map<String, String> flowFileAttributes = flowFile.getAttributes();
                final RecordReader
                        reader = recordReaderFactory.createRecordReader(flowFileAttributes, inFlowFile, flowFile.getSize(), getLogger());

                Record record;
                while ((record = reader.nextRecord()) != null) {
                    String fileId = record.getAsString(ID);
                    FlowFile outFlowFile = session.create(flowFile);
                    try {
                        addAttributes(session, outFlowFile, record);

                        fetchFile(fileId, session, outFlowFile);

                        session.transfer(outFlowFile, REL_SUCCESS);
                    } catch (Exception e) {
                        handleUnexpectedError(session, outFlowFile, fileId, e);
                    }
                }
                session.remove(flowFile);
            } catch (IOException | MalformedRecordException | SchemaNotFoundException e) {
                getLogger().error("Couldn't read file metadata content as records from incoming flowfile", e);

                session.putAttribute(flowFile, ERROR_MESSAGE_ATTRIBUTE, e.getMessage());

                session.transfer(flowFile, REL_INPUT_FAILURE);
            } catch (Exception e) {
                getLogger().error("Unexpected error while processing incoming flowfile", e);

                session.putAttribute(flowFile, ERROR_MESSAGE_ATTRIBUTE, e.getMessage());

                session.transfer(flowFile, REL_INPUT_FAILURE);
            }
        } else {
            String fileId = context.getProperty(FILE_ID).evaluateAttributeExpressions(flowFile).getValue();
            FlowFile outFlowFile = flowFile;
            try {
                fetchFile(fileId, session, outFlowFile);
                session.transfer(outFlowFile, REL_SUCCESS);
            } catch (Exception e) {
                handleUnexpectedError(session, flowFile, fileId, e);
            }
        }
        session.commitAsync();
    }

    private void addAttributes(ProcessSession session, FlowFile outFlowFile, Record record) {
        Map<String, String> attributes = new HashMap<>();

        for (DropboxFlowFileAttribute attribute : DropboxFlowFileAttribute.values()) {
            Optional.ofNullable(attribute.getValue(record))
                    .ifPresent(value -> attributes.put(attribute.getName(), value));
        }

        session.putAllAttributes(outFlowFile, attributes);
    }

    void fetchFile(String fileId, ProcessSession session, FlowFile outFlowFile) throws IOException {
        try {
            InputStream dropboxInputStream =
                    dropboxApiClient.files().download(fileId)
                            .getInputStream();
            session.importFrom(dropboxInputStream, outFlowFile);
        } catch (DbxException e) {
           throw new IOException("Error while downloading file with id "+fileId, e);
        }
    }

    private void handleUnexpectedError(ProcessSession session, FlowFile flowFile, String fileId, Exception e) {
        getLogger().error("Unexpected error while fetching and processing file with id '{}'", fileId, e);
        session.putAttribute(flowFile, ERROR_MESSAGE_ATTRIBUTE, e.getMessage());
        session.transfer(flowFile, REL_FAILURE);
    }
}
