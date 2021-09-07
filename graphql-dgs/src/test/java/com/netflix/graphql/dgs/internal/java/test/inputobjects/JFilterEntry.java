/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.internal.java.test.inputobjects;

import java.util.List;

public abstract class JFilterEntry {

    protected JFilterOperator operator;
    protected List<String> values;

    protected JFilterEntry() {
    }

    protected JFilterEntry(JFilterOperator operator, List<String> values) {
        this.operator = operator;
        this.values = values;
    }

    protected JFilterOperator getOperator() {
        return operator;
    }

    protected void setOperator(JFilterOperator operator) {
        this.operator = operator;
    }

    protected List<String> getValues() {
        return values;
    }

    protected void setValues(List<String> values) {
        this.values = values;
    }
}