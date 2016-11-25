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

package org.springframework.data.neo4j.repository.query;


import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.neo4j.ogm.model.QueryStatistics;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Parameter;
import org.springframework.data.repository.query.ParameterAccessor;
import org.springframework.data.repository.query.Parameters;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.util.StringUtils;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;


/**
 * Specialisation of {@link RepositoryQuery} that handles mapping to object annotated with <code>&#064;Query</code>.
 *
 * @author Mark Angrish
 * @author Luanne Misquitta
 * @author Jasper Blues
 */
public class GraphRepositoryQuery implements RepositoryQuery {

	protected static final String SKIP = "sdnSkip";
	protected static final String LIMIT = "sdnLimit";
	protected static final String SKIP_LIMIT = " SKIP {" + SKIP + "} LIMIT {" + LIMIT + "}";
	protected static final String ORDER_BY_CLAUSE = " ORDER BY %s";

	protected final GraphQueryMethod graphQueryMethod;
	protected final Session session;

	public GraphRepositoryQuery(GraphQueryMethod graphQueryMethod, Session session) {
		this.graphQueryMethod = graphQueryMethod;
		this.session = session;
	}

	@Override
	public final Object execute(Object[] parameters) {
		Class<?> returnType = graphQueryMethod.getMethod().getReturnType();
		Class<?> concreteType = graphQueryMethod.resolveConcreteReturnType();

		Map<String, Object> params = resolveParams(parameters);

		ParameterAccessor accessor = new ParametersParameterAccessor(graphQueryMethod.getParameters(), parameters);
		ResultProcessor processor = graphQueryMethod.getResultProcessor();

		Object result = execute(returnType, concreteType, getQueryString(), params, accessor);

		Object processed =  Result.class.equals(returnType) ? result :
				processor.withDynamicProjection(accessor).processResult(result);

		return graphQueryMethod.wrapIfOptional(processed);
	}

	protected Object execute(Class<?> returnType, Class<?> concreteType, String cypherQuery,
							 Map<String, Object> queryParams, ParameterAccessor parameterAccessor) {

		Pageable pageable = parameterAccessor.getPageable();
		Sort sort = parameterAccessor.getSort();
		if (pageable != null && pageable.getSort() != null) {
			sort = pageable.getSort();
		}
		if (sort != null) {
			//Custom queries in the OGM do not support pageable
			cypherQuery = addSorting(cypherQuery, sort);
		}

		if (returnType.equals(Void.class) || returnType.equals(void.class)) {
			session.query(cypherQuery, queryParams);
			return null;
		}

		if (Iterable.class.isAssignableFrom(returnType) && !queryReturnsStatistics()) {
			return mappedCollection(concreteType, cypherQuery, queryParams, pageable);
		}

		if (queryReturnsStatistics()) {
			return session.query(cypherQuery, queryParams);
		}

		return session.queryForObject(concreteType, cypherQuery, queryParams);
	}

	@Override
	public GraphQueryMethod getQueryMethod() {
		return graphQueryMethod;
	}

	protected String getQueryString() {
		return getQueryMethod().getQuery();
	}


	protected Object createPage(GraphQueryMethod graphQueryMethod, List resultList, Pageable pageable, Long count) {
		if (pageable == null) {
			return graphQueryMethod.isPageQuery() ? new PageImpl(resultList) : new SliceImpl(resultList);
		}
		int currentTotal;
		if (count != null) {
			currentTotal = count.intValue();
		} else {
			int pageOffset = pageable.getOffset();
			currentTotal = pageOffset + resultList.size() + (resultList.size() == pageable.getPageSize() ? pageable.getPageSize() : 0);
		}
		int resultWindowSize = Math.min(resultList.size(), pageable.getPageSize());
		boolean hasNext = resultWindowSize < resultList.size();
		List resultListPage = resultList.subList(0, resultWindowSize);

		return graphQueryMethod.isPageQuery() ?
				new PageImpl(resultListPage, pageable, currentTotal) :
				new SliceImpl(resultListPage, pageable, hasNext);
	}

	protected String addSorting(String baseQuery, Sort sort) {
		baseQuery = formatBaseQuery(baseQuery);
		if (sort == null) {
			return baseQuery;
		}
		final String sortOrder = getSortOrder(sort);
		if (sortOrder.isEmpty()) {
			return baseQuery;
		}
		return baseQuery + String.format(ORDER_BY_CLAUSE, sortOrder);
	}

	protected String addPaging(String cypherQuery, Map<String, Object> queryParams, int pageNumber, int pageSize) {
		//Custom queries in the OGM do not support pageable
		cypherQuery = formatBaseQuery(cypherQuery);
		cypherQuery = cypherQuery + SKIP_LIMIT;
		queryParams.put(SKIP, pageNumber * pageSize);
		if (graphQueryMethod.isSliceQuery()) {
			queryParams.put(LIMIT, pageSize + 1);
		} else {
			queryParams.put(LIMIT, pageSize);
		}
		return cypherQuery;
	}

	protected Long computeCount(Map<String, Object> params) {
		String countQuery = graphQueryMethod.getCountQueryString();
		if (countQuery == null || !StringUtils.hasText(countQuery)) {
			return null;
		}
		Result countResult = session.query(countQuery, params);
		if (countResult != null && countResult.iterator().hasNext()) {
			return ((Number) countResult.iterator().next().values().iterator().next()).longValue();
		}
		return null;
	}

	private boolean queryReturnsStatistics() {
		Class returnType = graphQueryMethod.getMethod().getReturnType();
		return QueryStatistics.class.isAssignableFrom(returnType) || Result.class.isAssignableFrom(returnType);
	}

	private Map<String, Object> resolveParams(Object[] parameters) {

		Map<String, Object> params = new HashMap<>();
		Parameters<?, ?> methodParameters = graphQueryMethod.getParameters();

		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = methodParameters.getParameter(i);

			//The parameter might be an entity, try to resolve its id
			Object parameterValue = session.resolveGraphIdFor(parameters[i]);
			if (parameterValue == null) { //Either not an entity or not persisted
				parameterValue = parameters[i];
			}

			if (parameter.isNamedParameter()) {
				params.put(parameter.getName(), parameterValue);
			} else {
				params.put("" + i, parameterValue);
			}
		}
		return params;
	}

	private Object mappedCollection(Class<?> concreteType, String cypherQuery, Map<String, Object> queryParams, Pageable pageable) {// Special method to handle SDN Iterable<Map<String, Object>> behaviour.
		// TODO: Do we really want this method in an OGM? It's a little too low level and/or doesn't really fit.
		if (Map.class.isAssignableFrom(concreteType)) {
			return session.query(cypherQuery, queryParams).queryResults();
		}
		List resultList;
		if (graphQueryMethod.isPageQuery() || graphQueryMethod.isSliceQuery()) {
			cypherQuery = addPaging(cypherQuery, queryParams, pageable.getPageNumber(), pageable.getPageSize());
			resultList = (List) session.query(concreteType, cypherQuery, queryParams);
			return createPage(graphQueryMethod, resultList, pageable, computeCount(queryParams));
		} else {
			resultList = (List) session.query(concreteType, cypherQuery, queryParams);
		}
		return resultList;
	}

	private String getSortOrder(Sort sort) {
		String result = "";
		for (Sort.Order order : sort) {
			if (!result.isEmpty()) {
				result += ", ";
			}
			result += order.getProperty() + " " + order.getDirection();
		}
		return result;
	}

	private String formatBaseQuery(String cypherQuery) {
		cypherQuery = cypherQuery.trim();
		if (cypherQuery.endsWith(";")) {
			cypherQuery = cypherQuery.substring(0, cypherQuery.length() - 1);
		}
		return cypherQuery;
	}
}
