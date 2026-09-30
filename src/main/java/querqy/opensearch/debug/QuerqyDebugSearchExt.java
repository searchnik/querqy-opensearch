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

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.search.SearchExtBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * The Querqy debug information in the <code>ext</code> section of the search response.
 */
public class QuerqyDebugSearchExt extends SearchExtBuilder {

    public static final String NAME = "querqy";

    private final Map<String, Object> debugInfo;

    public QuerqyDebugSearchExt(final Map<String, Object> debugInfo) {
        this.debugInfo = Objects.requireNonNull(debugInfo);
    }

    @SuppressWarnings("unchecked")
    public QuerqyDebugSearchExt(final StreamInput in) throws IOException {
        debugInfo = (Map<String, Object>) in.readGenericValue();
    }

    public static QuerqyDebugSearchExt fromXContent(final XContentParser parser) throws IOException {
        return new QuerqyDebugSearchExt(parser.map());
    }

    public Map<String, Object> getDebugInfo() {
        return debugInfo;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeGenericValue(debugInfo);
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        return builder.field(NAME, debugInfo);
    }

    @Override
    public boolean equals(final Object o) {
        return (o instanceof QuerqyDebugSearchExt that) && debugInfo.equals(that.debugInfo);
    }

    @Override
    public int hashCode() {
        return debugInfo.hashCode();
    }
}
