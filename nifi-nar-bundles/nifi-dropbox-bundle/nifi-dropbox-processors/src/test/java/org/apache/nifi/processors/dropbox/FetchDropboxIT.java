/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.dropbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dropbox.core.v2.files.FileMetadata;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.json.JsonTreeReader;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.util.MockFlowFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FetchDropboxIT extends AbstractDropboxIT<FetchDropbox> {

    private static final String DEFAULT_FILE_CONTENT = "test_file_content1";

    @BeforeEach
    public void init() throws Exception {
        super.init();
    }

    @Override
    protected FetchDropbox createTestSubject() {
        return new FetchDropbox();
    }

    @Test
    void testFetchSingleFileByInputAttributes() throws Exception {
        // GIVEN
        FileMetadata file = createFile("test_file1", DEFAULT_FILE_CONTENT, "/testFolder");

        Map<String, String> inputFlowFileAttributes = new HashMap<>();
        inputFlowFileAttributes.put("dropbox.id", file.getId());
        inputFlowFileAttributes.put("filename", file.getName());

        HashSet<Map<String, String>> expectedAttributes = new HashSet<>(Arrays.asList(inputFlowFileAttributes));
        List<String> expectedContent = Arrays.asList(DEFAULT_FILE_CONTENT);

        // WHEN
        testRunner.enqueue("unimportant_data", inputFlowFileAttributes);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_FAILURE, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_INPUT_FAILURE, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_SUCCESS, 1);

        checkAttributes(FetchDropbox.REL_SUCCESS, expectedAttributes);
        checkContent(FetchDropbox.REL_SUCCESS, expectedContent);
    }

    @Test
    void testInputFlowFileReferencesMissingFile() throws Exception {
        // GIVEN
        Map<String, String> inputFlowFileAttributes = new HashMap<>();
        inputFlowFileAttributes.put("dropbox.id", "id:missing");
        inputFlowFileAttributes.put("filename", "missing_filename");

        Set<Map<String, String>> expectedFailureAttributes = new HashSet<>(Arrays.asList(
                new HashMap<String, String>() {{
                    put("dropbox.id", "id:missing");
                    put("filename", "missing_filename");
                    put("error.message", "Error while downloading file from Dropbox id:missing");
                }}
        ));

        // WHEN
        testRunner.enqueue("unimportant_data", inputFlowFileAttributes);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_SUCCESS, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_INPUT_FAILURE, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_FAILURE, 1);
    }

    @Test
    void testInputFlowFileThrowsExceptionBeforeFetching() throws Exception {
        // GIVEN
        FileMetadata file = createFile("test_file1", DEFAULT_FILE_CONTENT, "/testFolder");

        Map<String, String> inputFlowFileAttributes = new HashMap<>();
        inputFlowFileAttributes.put("dropbox.id", file.getId());

        MockFlowFile input = new MockFlowFile(1) {
            AtomicBoolean throwException = new AtomicBoolean(true);

            @Override
            public boolean isPenalized() {
                // We want to throw exception only once because the exception handling itself calls this again
                if (throwException.get()) {
                    throwException.set(false);
                    throw new RuntimeException("Intentional exception");
                } else {
                    return super.isPenalized();
                }
            }

            @Override
            public Map<String, String> getAttributes() {
                return inputFlowFileAttributes;
            }
        };

        // WHEN
        testRunner.enqueue(input);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_SUCCESS, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_INPUT_FAILURE, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_FAILURE, 1);
    }

    @Test
    void testFetchMultipleFilesByInputRecords() throws Exception {
        // GIVEN
        addJsonRecordReaderFactory();

        FileMetadata file1 = createFile("test_file_1.txt", "test_content_1", MAIN_FOLDER);
        FileMetadata file2 = createFile("test_file_2.txt", "test_content_2", MAIN_FOLDER);

        String input = "[" +
                "{" +
                "\"dropbox.id\":\"" + file1.getId() + "\"," +
                "\"filename\":\"" + file1.getName() + "\"" +
                "}," +
                "{" +
                "\"dropbox.id\":\"" + file2.getId() + "\"," +
                "\"filename\":\"" + file2.getName() + "\"" +
                "}" +
                "]";

        List<String> expectedContent = Arrays.asList(
                "test_content_1",
                "test_content_2"
        );

        Set<Map<String, String>> expectedAttributes = new HashSet<>(Arrays.asList(
                new HashMap<String, String>() {{
                    put("dropbox.id", "" + file1.getId());
                    put("filename", file1.getName());
                }},
                new HashMap<String, String>() {{
                    put("dropbox.id", "" + file2.getId());
                    put("filename", file2.getName());
                }}
        ));

        // WHEN
        testRunner.enqueue(input);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_FAILURE, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_INPUT_FAILURE, 0);

        checkContent(FetchDropbox.REL_SUCCESS, expectedContent);
        checkAttributes(FetchDropbox.REL_SUCCESS, expectedAttributes);
    }

    @Test
    void testInputRecordReferencesMissingFile() throws Exception {
        // GIVEN
        addJsonRecordReaderFactory();

        String input = "[" +
                "{" +
                "\"dropbox.id\":\"missing\"," +
                "\"filename\":\"missing_filename\"" +
                "}" +
                "]";

        Set<Map<String, String>> expectedFailureAttributes = new HashSet<>(Arrays.asList(
                new HashMap<String, String>() {{
                    put("dropbox.id", "missing");
                    put("filename", "missing_filename");
                }}
        ));

        // WHEN
        testRunner.enqueue(input);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_SUCCESS, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_INPUT_FAILURE, 0);

        checkAttributes(FetchDropbox.REL_FAILURE, expectedFailureAttributes);
    }

    @Test
    void testInputRecordsAreInvalid() throws Exception {
        // GIVEN
        addJsonRecordReaderFactory();

        String input = "invalid json";

        List<String> expectedContents = Arrays.asList("invalid json");

        // WHEN
        testRunner.enqueue(input);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_SUCCESS, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_FAILURE, 0);

        checkContent(FetchDropbox.REL_INPUT_FAILURE, expectedContents);
    }

    @Test
    void testThrowExceptionBeforeRecordsAreProcessed() throws Exception {
        // GIVEN
        addJsonRecordReaderFactory();

        FileMetadata file = createFile("test_file.txt", DEFAULT_FILE_CONTENT, MAIN_FOLDER);

        String validInputContent = "[" +
                "{" +
                "\"dropbox.id\":\"" + file.getId() + "\"," +
                "\"filename\":\"" + file.getName() + "\"" +
                "}" +
                "]";

        MockFlowFile input = new MockFlowFile(1) {
            @Override
            public Map<String, String> getAttributes() {
                throw new RuntimeException("Intentional exception");
            }

            @Override
            public String getContent() {
                return validInputContent;
            }
        };

        List<String> expectedContents = Arrays.asList(validInputContent);

        // WHEN
        testRunner.enqueue(input);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_SUCCESS, 0);
        testRunner.assertTransferCount(FetchDropbox.REL_FAILURE, 0);

        checkContent(FetchDropbox.REL_INPUT_FAILURE, expectedContents);
    }

    @Test
    void testOneInputRecordOutOfManyThrowsUnexpectedException() throws Exception {
        // GIVEN
        AtomicReference<String> fileIdToThrowException = new AtomicReference<>();

        testSubject = new FetchDropbox() {
            @Override
            void fetchFile(String fileId, ProcessSession session, FlowFile outFlowFile) throws IOException {
                if (fileId.equals(fileIdToThrowException.get())) {
                    throw new RuntimeException(fileId + " intentionally forces exception");
                }
                super.fetchFile(fileId, session, outFlowFile);
            }
        };
        testRunner = createTestRunner();

        addJsonRecordReaderFactory();

        FileMetadata file1 = createFile("test_file_1.txt", "test_content_1", MAIN_FOLDER);
        FileMetadata file2 = createFile("test_file_2.txt", "test_content_2", MAIN_FOLDER);

        String input = "[" +
                "{" +
                "\"dropbox.id\":\"" + file1.getId() + "\"," +
                "\"filename\":\"" + file1.getName() + "\"" +
                "}," +
                "{" +
                "\"dropbox.id\":\"" + file2.getId() + "\"," +
                "\"filename\":\"" + file2.getName() + "\"" +
                "}" +
                "]";

        fileIdToThrowException.set(file2.getId());

        Set<Map<String, String>> expectedSuccessAttributes = new HashSet<>(Arrays.asList(
                new HashMap<String, String>() {{
                    put("dropbox.id", file1.getId());
                    put("filename", file1.getName());
                }}
        ));
        List<String> expectedSuccessContents = Arrays.asList("test_content_1");

        Set<Map<String, String>> expectedFailureAttributes = new HashSet<>(Arrays.asList(
                new HashMap<String, String>() {{
                    put("dropbox.id", file2.getId());
                    put("filename", file2.getName());
                }}
        ));

        // WHEN
        testRunner.enqueue(input);
        testRunner.run();

        // THEN
        testRunner.assertTransferCount(FetchDropbox.REL_INPUT_FAILURE, 0);

        checkAttributes(FetchDropbox.REL_SUCCESS, expectedSuccessAttributes);
        checkContent(FetchDropbox.REL_SUCCESS, expectedSuccessContents);

        checkAttributes(FetchDropbox.REL_FAILURE, expectedFailureAttributes);
        checkContent(FetchDropbox.REL_FAILURE, Arrays.asList(""));
    }

    private void addJsonRecordReaderFactory() throws InitializationException {
        RecordReaderFactory recordReader = new JsonTreeReader();
        testRunner.addControllerService("record_reader", recordReader);
        testRunner.enableControllerService(recordReader);
        testRunner.setProperty(FetchDropbox.RECORD_READER, "record_reader");
    }

    private void checkAttributes(Relationship relationship, Set<Map<String, String>> expectedAttributes) {
        getTestRunner().assertTransferCount(relationship, expectedAttributes.size());
        List<MockFlowFile> flowFiles = getTestRunner().getFlowFilesForRelationship(relationship);

        Set<String> checkedAttributeNames = getCheckedAttributeNames();

        Set<Map<String, String>> actualAttributes = flowFiles.stream()
                .map(flowFile -> flowFile.getAttributes().entrySet().stream()
                        .filter(attributeNameAndValue -> checkedAttributeNames.contains(attributeNameAndValue.getKey()))
                        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))

                )
                .collect(Collectors.toSet());

        assertEquals(expectedAttributes, actualAttributes);
    }

    private Set<String> getCheckedAttributeNames() {
        return Arrays.stream(DropboxFlowFileAttribute.values())
                .map(DropboxFlowFileAttribute::getName)
                .collect(Collectors.toSet());
    }

    private void checkContent(Relationship relationship, List<String> expectedContent) {
        getTestRunner().assertTransferCount(relationship, expectedContent.size());
        List<MockFlowFile> flowFiles = getTestRunner().getFlowFilesForRelationship(relationship);

        List<String> actualContent = flowFiles.stream()
                .map(flowFile -> flowFile.getContent())
                .collect(Collectors.toList());

        assertEquals(expectedContent, actualContent);
    }
}
