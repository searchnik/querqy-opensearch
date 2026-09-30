/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Querqy for OpenSearch Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package querqy.opensearch.debug;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;
import java.util.Map;

public class QuerqyDebugSearchExtTest extends OpenSearchTestCase {

    private static final Map<String, Object> DEBUG_INFO = Map.of("queries", List.of(Map.of(
            "query", "k",
            "rewriters", List.of("common_rules"),
            "rewrite_chain", List.of(Map.of("rewriter_id", "common_rules", "actions", List.of(Map.of(
                    "message", "msg1",
                    "match", Map.of("term", "k", "type", "exact"))))))));

    public void testStreamRoundTrip() throws Exception {
        final QuerqyDebugSearchExt ext = new QuerqyDebugSearchExt(DEBUG_INFO);
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            ext.writeTo(out);
            assertEquals(ext, new QuerqyDebugSearchExt(out.bytes().streamInput()));
        }
    }

    public void testXContent() throws Exception {
        final XContentBuilder builder = JsonXContent.contentBuilder().startObject();
        new QuerqyDebugSearchExt(DEBUG_INFO).toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();

        final Map<String, Object> json = XContentHelper.convertToMap(BytesReference.bytes(builder), false,
                XContentType.JSON).v2();
        assertEquals(Map.of(QuerqyDebugSearchExt.NAME, DEBUG_INFO), json);
    }
}
