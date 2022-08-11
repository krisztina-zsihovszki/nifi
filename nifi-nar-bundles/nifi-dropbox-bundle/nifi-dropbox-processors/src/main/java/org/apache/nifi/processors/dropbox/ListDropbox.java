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

import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.oauth.DbxCredential;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.PrimaryNodeOnly;
import org.apache.nifi.annotation.behavior.Stateful;
import org.apache.nifi.annotation.behavior.TriggerSerially;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.context.PropertyContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processor.util.list.AbstractListProcessor;
import org.apache.nifi.processor.util.list.ListedEntityTracker;
import org.apache.nifi.serialization.record.RecordSchema;

@PrimaryNodeOnly
@TriggerSerially
@Tags({"dropbox", "storage"})
@CapabilityDescription("Lists concrete files (shortcuts are ignored) in a Google Drive folder. " +
        "Each listed file may result in one flowfile, the metadata being written as flowfile attributes. " +
        "Or - in case the 'Record Writer' property is set - the entire result is written as records to a single flowfile. " +
        "This Processor is designed to run on Primary Node only in a cluster. If the primary node changes, the new Primary Node will pick up where the " +
        "previous node left off without duplicating all of the data.")
@SeeAlso({FetchDropbox.class})
@InputRequirement(Requirement.INPUT_FORBIDDEN)
@WritesAttributes({@WritesAttribute(attribute = DropboxFileInfo.ID, description = "The Dropbox identifier of the file"),
        @WritesAttribute(attribute = DropboxFileInfo.PATH, description = "The folder path where the file is located"),
        @WritesAttribute(attribute = DropboxFileInfo.FILENAME, description = "The name of the file"),
        @WritesAttribute(attribute = DropboxFileInfo.SIZE, description = "The size of the file"),
        @WritesAttribute(attribute = DropboxFileInfo.TIMESTAMP, description = "The last modified time or created time (whichever is greater) of the file." +
                " The reason for this is that the original modified date of a file is preserved when uploaded to Google Drive." +
                " 'Created time' takes the time when the upload occurs. However uploaded files can still be modified later."),
        @WritesAttribute(attribute = DropboxFileInfo.REVISION, description = "Revision of the file")})
@Stateful(scopes = {Scope.CLUSTER}, description = "The processor stores necessary data to be able to keep track what files have been listed already." +
        " What exactly needs to be stored depends on the 'Listing Strategy'." +
        " State is stored across the cluster so that this Processor can be run on Primary Node only and if a new Primary Node is selected, the new node can pick up" +
        " where the previous node left off, without duplicating the data.")
public class ListDropbox extends AbstractListProcessor<DropboxFileInfo>  {

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


    public static final PropertyDescriptor FOLDER_NAME = new PropertyDescriptor.Builder()
            .name("folder-name")
            .displayName("Folder Name")
            .description("The name of the folder from which to pull list of files. "+
                    " Providing empty string as folder list files from user root directory." +
                    " WARNING: Unauthorized access to the folder is treated as if the folder was empty." +
                    " This results in the processor not creating result flowfiles. No additional error message is provided.")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("(/(.|[\\r\\n])*)?|id:.*|(ns:[0-9]+(/.*)?)")))
            .build();

    public static final PropertyDescriptor RECURSIVE_SEARCH = new PropertyDescriptor.Builder()
            .name("recursive-search")
            .displayName("Search Recursively")
            .description("When 'true', will include list of files from concrete sub-folders (ignores shortcuts)." +
                    " Otherwise, will return only files that have the defined 'Folder Name' as their parent directly." +
                    " WARNING: The listing may fail if there are too many sub-folders (500+).")
            .required(true)
            .defaultValue("true")
            .allowableValues("true", "false")
            .build();

    public static final PropertyDescriptor MIN_AGE = new PropertyDescriptor.Builder()
            .name("min-age")
            .displayName("Minimum File Age")
            .description("The minimum age a file must be in order to be considered; any files younger than this will be ignored.")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .defaultValue("0 sec")
            .build();

    public static final PropertyDescriptor LISTING_STRATEGY = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(AbstractListProcessor.LISTING_STRATEGY)
            .allowableValues(BY_TIMESTAMPS, BY_ENTITIES, BY_TIME_WINDOW, NO_TRACKING)
            .build();

    public static final PropertyDescriptor TRACKING_STATE_CACHE = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(ListedEntityTracker.TRACKING_STATE_CACHE)
            .dependsOn(LISTING_STRATEGY, BY_ENTITIES)
            .build();

    public static final PropertyDescriptor TRACKING_TIME_WINDOW = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(ListedEntityTracker.TRACKING_TIME_WINDOW)
            .dependsOn(LISTING_STRATEGY, BY_ENTITIES)
            .build();

    public static final PropertyDescriptor INITIAL_LISTING_TARGET = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(ListedEntityTracker.INITIAL_LISTING_TARGET)
            .dependsOn(LISTING_STRATEGY, BY_ENTITIES)
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            FOLDER_NAME,
            RECURSIVE_SEARCH,
            MIN_AGE,
            LISTING_STRATEGY,
            APP_KEY,
            APP_SECRET,
            ACCESS_TOKEN,
            REFRESH_TOKEN,
            TRACKING_STATE_CACHE,
            TRACKING_TIME_WINDOW,
            INITIAL_LISTING_TARGET,
            RECORD_WRITER
    ));

    private DbxClientV2 dropboxApiClient;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    protected void customValidate(ValidationContext validationContext, Collection<ValidationResult> results) {
    }

    @Override
    protected Map<String, String> createAttributes(
            final DropboxFileInfo entity,
            final ProcessContext context) {
        final Map<String, String> attributes = new HashMap<>();

        for (DropboxFlowFileAttribute attribute : DropboxFlowFileAttribute.values()) {
            Optional.ofNullable(attribute.getValue(entity))
                    .ifPresent(value -> attributes.put(attribute.getName(), value));
        }

        return attributes;
    }

    @Override
    protected String getPath(final ProcessContext context) {
        return context.getProperty(FOLDER_NAME).evaluateAttributeExpressions().getValue();
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
    protected List<DropboxFileInfo> performListing(ProcessContext context, Long minTimestamp,
            ListingMode listingMode) throws IOException {
        final List<DropboxFileInfo> listing = new ArrayList<>();

        final String folderName = context.getProperty(FOLDER_NAME).evaluateAttributeExpressions().getValue();
        final Boolean recursive = context.getProperty(RECURSIVE_SEARCH).asBoolean();
        //TODO: check min age
        final Long minAge = context.getProperty(MIN_AGE).asTimePeriod(TimeUnit.MILLISECONDS);

        try {
            for (Metadata metadata : listFolder(folderName, recursive)) {
                DropboxFileInfo.Builder builder = new DropboxFileInfo.Builder()
                        .id(((FileMetadata) metadata).getId())
                        .path(metadata.getPathLower())
                        .name(metadata.getName())
                        .size(((FileMetadata) metadata).getSize())
                        .timestamp(((FileMetadata) metadata).getServerModified().getTime())
                        .revision(((FileMetadata) metadata).getRev());

                listing.add(builder.build());
            }
        } catch (DbxException e) {
            throw new IOException("Failed to list Dropbox folder", e);
        }

        return listing;
    }

    @Override
    protected boolean isListingResetNecessary(final PropertyDescriptor property) {
        return LISTING_STRATEGY.equals(property)
                || FOLDER_NAME.equals(property)
                || RECURSIVE_SEARCH.equals(property);
    }

    @Override
    protected Scope getStateScope(final PropertyContext context) {
        return Scope.CLUSTER;
    }

    @Override
    protected RecordSchema getRecordSchema() {
        return DropboxFileInfo.getRecordSchema();
    }

    @Override
    protected Integer countUnfilteredListing(final ProcessContext context) throws IOException {
        return performListing(context, null, ListingMode.CONFIGURATION_VERIFICATION).size();
    }

    @Override
    protected String getListingContainerName(final ProcessContext context) {
        return String.format("Dropbox Folder [%s]", getPath(context));
    }

    private List<Metadata> listFolder(String folderName, boolean isRecursive) throws DbxException {
        List<Metadata> metadataList = new ArrayList<>();
        ListFolderResult result = dropboxApiClient.files().listFolder(folderName);

        for (Metadata metadata : result.getEntries()) {
            if (metadata instanceof FileMetadata) {
                metadataList.add(metadata);
            } else if (isRecursive){
                metadataList.addAll(listFolder(metadata.getPathLower(), isRecursive));
            }
        }
        return metadataList;
    }
}
