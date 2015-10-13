/**
 *
 * Copyright (c) 2006-2015, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.db.crud;

import com.speedment.annotation.Api;
import com.speedment.field2.predicate.PredicateType;

import java.util.Optional;

/**
 * @author Emil Forslund
 * @since 2.2
 */
@Api(version = "2.2")
public interface Selector {

    /**
     * Returns the name of the column described by this selector.
     *
     * @return  the column name
     */
    String getColumnName();

    /**
     * Returns the operator used in this selector.
     *
     * @return  the operator
     */
    PredicateType getPredicateType();

    /**
     * Returns the operand (if any) that this selector compares with. If the 
     * operator specified by {@link #getPredicateType()} does not require any 
     * operands, this will be {@code empty}.
     *
     * @return  the operand if any and {@code empty} otherwise
     */
    Optional<Object> getOperand();

}