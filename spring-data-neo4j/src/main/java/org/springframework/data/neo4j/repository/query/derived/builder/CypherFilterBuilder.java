/*
 * Copyright (c)  [2011-2016] "Pivotal Software, Inc." / "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 *
 */

package org.springframework.data.neo4j.repository.query.derived.builder;

import java.util.List;

import org.neo4j.ogm.cypher.BooleanOperator;
import org.springframework.data.neo4j.repository.query.derived.CypherFilter;
import org.springframework.data.repository.query.parser.Part;

/**
 * @author Jasper Blues
 */
public abstract class CypherFilterBuilder {

	protected Part part;
	protected BooleanOperator booleanOperator;
	protected Class<?> entityType;

	public CypherFilterBuilder(Part part, BooleanOperator booleanOperator, Class<?> entityType) {
		this.part = part;
		this.booleanOperator = booleanOperator;
		this.entityType = entityType;
	}

	public abstract List<CypherFilter> build();

	protected boolean isNegated() {
		return part.getType().name().startsWith("NOT");
	}

	protected String propertyName() {
		return part.getProperty().getSegment();
	}

	protected void setNestedAttributes(Part part, CypherFilter filter) {
		if (part.getProperty().next() != null) {
			filter.setOwnerEntityType(part.getProperty().getOwningType().getType());
			filter.setNestedPropertyType(part.getProperty().getType());
			filter.setPropertyName(part.getProperty().getLeafProperty().getSegment());
			filter.setNestedPropertyName(part.getProperty().getSegment());
		}
	}

}
